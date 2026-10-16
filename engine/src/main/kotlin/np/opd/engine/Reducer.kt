package np.opd.engine

/**
 * The deterministic heart of the app.
 *
 *   reduce(state, aiDelta)   -> new state   (AI can only ADD information or TIGHTEN safety)
 *   handle(state, doctorAct) -> new state   (only the doctor can LOOSEN safety)
 *   evaluate(state)          -> recomputes phases, scores, suggestions and safety findings
 *
 * Pure functions, no I/O, no AI calls.
 */
object Reducer {

    /* ============================ AI input ============================ */

    fun reduce(state: CaseState, d: LiveDelta, c: Catalogues, segmentStartMs: Long = 0L): CaseState {
        val tick = state.tick + 1
        val audit = state.audit.toMutableList()
        val log: (String) -> Unit = { audit += "T$tick AI: $it" }
        var counter = state.counter
        fun nextId(prefix: String): String { counter++; return prefix + "_" + counter.toString().padStart(3, '0') }

        // 1. Transcript
        // Kept in time order, so a segment that is retried later still lands in the right place.
        val transcript = (state.transcript + d.transcript
            .filter { it.text.isNotBlank() }
            .map { it.copy(offsetMs = segmentStartMs + it.offsetMs) })
            .sortedBy { it.offsetMs }

        // 2. Demographics: first heard value sticks; later disagreements become conflicts for the doctor
        var demo = state.demographics
        val conflicts = state.conflicts.toMutableList()
        fun conflict(field: String, existing: String, proposed: String) {
            if (conflicts.none { it.field == field && it.proposed == proposed }) {
                conflicts += Conflict(field, existing, proposed, tick)
                log("$field conflict: kept $existing, heard $proposed")
            }
        }
        d.age?.let { a ->
            if (a in 0..120) {
                val cur = demo.age
                if (cur == null) demo = demo.copy(age = a) else if (cur != a) conflict("age", cur.toString(), a.toString())
            }
        }
        parseGender(d.gender)?.let { g ->
            if (demo.gender == Gender.UNKNOWN) demo = demo.copy(gender = g)
            else if (demo.gender != g) conflict("gender", demo.gender.name, g.name)
        }
        when (d.pregnancy?.trim()?.lowercase()) {
            "pregnant" -> if (demo.pregnancy != Pregnancy.PREGNANT) {
                demo = demo.copy(pregnancy = Pregnancy.PREGNANT); log("pregnancy recorded")
            }
            "not_pregnant" -> if (demo.pregnancy != Pregnancy.NOT_PREGNANT) log("ignored AI 'not pregnant' (needs doctor confirmation)")
        }
        if (d.lactating == true && !demo.lactating) demo = demo.copy(lactating = true)

        // 3. Vitals
        val newVitals = mergeVitals(state.vitals, d.vitals)
        val changed = changedFields(state.vitals, newVitals).toMutableSet()
        if (demo.age != state.demographics.age) changed += "age"

        // 4. Allergies (append-only from AI)
        var allergies = state.allergies
        val allergyNotes = state.allergyNotes.toMutableList()
        for (a in d.allergies) {
            if (a.stated.isBlank()) continue
            val text = a.stated.trim() + (a.reaction?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            if (a.isIntolerance) {
                val note = "Intolerance: $text"
                if (allergyNotes.none { it.equals(note, true) }) allergyNotes += note
                continue
            }
            val codes = mapAllergy(a.stated, c)
            if (codes.isEmpty()) {
                val note = "Allergy (not coded, check manually): $text"
                if (allergyNotes.none { it.equals(note, true) }) { allergyNotes += note; log(note) }
            } else {
                val added = codes - allergies
                allergies = allergies + codes
                if (added.isNotEmpty()) log("allergy $added from '${a.stated}'")
            }
        }

        // 5. Comorbidities (append-only, vocabulary-checked)
        val goodComorb = d.comorbidities.map { it.trim().uppercase() }.filter { it in c.comorbidities }
        (d.comorbidities.map { it.trim().uppercase() } - goodComorb.toSet()).forEach { log("dropped unknown comorbidity '$it'") }
        val comorbidities = state.comorbidities + goodComorb

        // 6. Current medicines: merged field by field
        val meds = LinkedHashMap<String, CurrentMed>()
        state.currentMeds.forEach { meds[it.key] = it }
        for (m in d.currentMeds) {
            if (m.stated.isBlank()) continue
            val drug = c.findDrug(m.stated)
            val key = drug?.id ?: ("TEXT:" + norm(m.stated))
            val ex = meds[key]
            meds[key] = if (ex == null) CurrentMed(key, drug?.id, m.stated.trim(), m.dailyDoseMg, "conversation")
            else ex.copy(dailyDoseMg = m.dailyDoseMg ?: ex.dailyDoseMg)
        }

        // 7. Exam findings (only what the doctor said aloud)
        val exam = (state.examFindings + d.examFindings.map { it.trim() }.filter { it.isNotEmpty() }).distinct()

        // 8. Red flags from conversation
        val flags = state.flags.toMutableMap()
        for (rawId in d.flagsExcluded) {
            val id = rawId.trim()
            val def = c.redFlags[id]
            if (def == null) { log("dropped unknown flag id '$id'"); continue }
            if (def.derivationRule != null) { log("ignored conversational exclusion of measured flag $id"); continue }
            val ex = flags[id]
            if (ex == null || ex.status == FlagStatus.UNASSESSED) flags[id] = FlagRecord(id, FlagStatus.EXCLUDED, Source.AI, tick = tick)
        }
        for (f in d.flagsIdentified) {
            val id = f.flagId.trim()
            val def = c.redFlags[id]
            if (def == null) { log("dropped unknown flag id '$id'"); continue }
            // Measured flags can only be "suspected" from conversation; a number confirms them.
            val incoming = if (f.confirmed && def.derivationRule == null) FlagStatus.CONFIRMED else FlagStatus.SUSPECTED
            val ex = flags[id]
            when (ex?.status) {
                null, FlagStatus.UNASSESSED, FlagStatus.EXCLUDED -> {
                    flags[id] = FlagRecord(id, incoming, Source.AI, f.evidence, tick = tick)
                    log("${def.label}: $incoming")
                }
                FlagStatus.SUSPECTED -> if (incoming == FlagStatus.CONFIRMED) {
                    flags[id] = ex.copy(status = FlagStatus.CONFIRMED, evidence = f.evidence ?: ex.evidence, tick = tick)
                    log("${def.label}: CONFIRMED")
                }
                FlagStatus.CONFIRMED, FlagStatus.RESOLVED_BY_DOCTOR -> Unit
            }
        }

        // 9. Red flags derived from measurements (only when a relevant value changed)
        applyDerivations(flags, changed, newVitals, demo, c, tick, log)

        // 10. Problems
        var problems = state.problems.toMutableList()
        for (pIn in d.problems) {
            var code = pIn.conditionCode.trim().uppercase()
            var label = pIn.freeLabel?.trim()?.takeIf { it.isNotEmpty() }
            if (code != OTHER && c.conditions[code] == null) {
                log("unknown condition '$code' filed as other")
                label = label ?: code
                code = OTHER
            }
            val def = c.conditions[code]
            val resolvedLabel = def?.displayName ?: (label ?: "Other complaint")
            var idx = problems.indexOfFirst {
                if (code == OTHER) it.conditionCode == OTHER && norm(it.label) == norm(resolvedLabel) else it.conditionCode == code
            }
            if (idx < 0) {
                problems.add(Problem(problemId = nextId("prob"), conditionCode = code, label = resolvedLabel))
                idx = problems.size - 1
                log("new problem: $resolvedLabel")
            }
            val p = problems[idx]
            val sx = p.symptoms.toMutableList()
            for (s in pIn.symptoms) {
                val n = s.name.trim()
                if (n.isEmpty() || sx.any { norm(it.name) == norm(n) }) continue
                sx += Symptom(nextId("sx"), n, s.duration?.takeIf { it.isNotBlank() }, s.source, s.evidence)
            }
            val ng = p.negatives.toMutableList()
            for (s in pIn.negatives) {
                val n = s.name.trim()
                if (n.isEmpty() || ng.any { norm(it.name) == norm(n) }) continue
                ng += Negative(nextId("neg"), n, s.evidence)
            }
            val valid = def?.allQuestionIds() ?: emptySet()
            val good = pIn.exploredQuestionIds.map { it.trim() }.filter { it in valid }
            val bad = pIn.exploredQuestionIds.map { it.trim() }.filter { it !in valid }
            if (bad.isNotEmpty()) log("dropped question ids $bad for $code")
            problems[idx] = p.copy(symptoms = sx, negatives = ng, exploredQuestions = p.exploredQuestions + good)
        }

        // 11. Reclassification (e.g. watery diarrhoea + blood -> dysentery)
        problems = applyRedirects(problems, flags, c, log)

        // 12. Doctor cues heard in the audio (sticky per problem)
        val dictationNow = d.dictationCue || d.dictatedItems.isNotEmpty()
        val dictTarget = d.dictationCueCondition ?: d.dictatedItems.mapNotNull { it.conditionCode }.distinct().singleOrNull()
        fun applies(target: String?, p: Problem): Boolean =
            target == null || c.conditions[target] == null || target == p.conditionCode
        if (d.planCue) log("doctor signalled treatment plan" + (d.planCueCondition?.let { " for $it" } ?: ""))
        if (dictationNow) log("doctor dictating prescription")
        problems = problems.map { p ->
            var q = p
            if (d.planCue && applies(d.planCueCondition, p)) q = q.copy(planCue = true)
            if (dictationNow && applies(dictTarget, p)) q = q.copy(dictation = true)
            if (d.followUpVisit && !q.visitTypeSetByDoctor && c.conditions[q.conditionCode]?.visitTypes?.containsKey("FOLLOW_UP") == true)
                q = q.copy(visitType = "FOLLOW_UP")
            q
        }.toMutableList()

        // 13. Dictated prescription items
        val dictated = state.dictated.toMutableList()
        for (di in d.dictatedItems) {
            if (di.stated.isBlank()) continue
            val drug = c.findDrug(di.stated)
            val freq = normFrequency(di.frequency)
            val idx = dictated.indexOfFirst { (drug != null && it.drugId == drug.id) || norm(it.stated) == norm(di.stated) }
            if (idx >= 0) {
                val ex = dictated[idx]
                dictated[idx] = ex.copy(
                    doseCount = di.doseCount ?: ex.doseCount,
                    frequency = freq ?: ex.frequency,
                    durationDays = di.durationDays ?: ex.durationDays,
                    targetCondition = di.conditionCode ?: ex.targetCondition
                )
            } else {
                dictated += DictatedItem(nextId("rx"), di.stated.trim(), drug?.id, di.doseCount, freq, di.durationDays, di.conditionCode)
                log("dictated '${di.stated}'" + if (drug == null) " (not in formulary)" else "")
            }
        }

        val next = state.copy(
            tick = tick,
            demographics = demo,
            conflicts = conflicts,
            vitals = newVitals,
            problems = problems,
            flags = flags,
            allergies = allergies,
            allergyNotes = allergyNotes,
            comorbidities = comorbidities,
            currentMeds = meds.values.toList(),
            dictated = dictated,
            transcript = transcript,
            examFindings = exam,
            aiNextQuestions = d.nextQuestions.filter { it.en.isNotBlank() || it.ne.isNotBlank() }.take(3)
                .ifEmpty { state.aiNextQuestions },
            counter = counter,
            audit = audit
        )
        return evaluate(next, c)
    }

    /* ============================ Doctor input ============================ */

    fun handle(s: CaseState, a: DoctorAction, c: Catalogues): CaseState {
        val audit = s.audit.toMutableList()
        val log: (String) -> Unit = { audit += "Doctor: $it" }
        var counter = s.counter
        fun mapProblem(id: String, f: (Problem) -> Problem) = s.problems.map { if (it.problemId == id) f(it) else it }

        val next: CaseState = when (a) {
            is DoctorAction.SetDemographics -> {
                var d = s.demographics
                a.age?.let { d = d.copy(age = it) }
                a.gender?.let { d = d.copy(gender = it) }
                log("set age=${a.age ?: "-"} sex=${a.gender ?: "-"}")
                val flags = s.flags.toMutableMap()
                if (d.age != s.demographics.age) applyDerivations(flags, setOf("age"), s.vitals, d, c, s.tick, log)
                s.copy(
                    demographics = d,
                    flags = flags,
                    conflicts = s.conflicts.filterNot { (a.age != null && it.field == "age") || (a.gender != null && it.field == "gender") }
                )
            }
            is DoctorAction.SettleConflict -> {
                val d = when (a.field) {
                    "age" -> s.demographics.copy(age = a.value.trim().toIntOrNull() ?: s.demographics.age)
                    "gender" -> parseGender(a.value)?.let { s.demographics.copy(gender = it) } ?: s.demographics
                    else -> s.demographics
                }
                log("settled ${a.field} = ${a.value}")
                s.copy(demographics = d, conflicts = s.conflicts.filter { it.field != a.field })
            }
            is DoctorAction.ConfirmPregnancy -> {
                log("pregnancy status: ${a.status}")
                s.copy(demographics = s.demographics.copy(pregnancy = a.status))
            }
            is DoctorAction.SetVitals -> {
                val flags = s.flags.toMutableMap()
                val v = mergeVitals(s.vitals, a.vitals)
                applyDerivations(flags, changedFields(s.vitals, v), v, s.demographics, c, s.tick, log)
                log("entered vitals: ${v.summary()}")
                s.copy(vitals = v, flags = flags)
            }
            is DoctorAction.ClearFlag -> {
                val ex = s.flags[a.flagId]
                val status = if (ex?.status == FlagStatus.SUSPECTED || ex?.status == FlagStatus.CONFIRMED) FlagStatus.RESOLVED_BY_DOCTOR else FlagStatus.EXCLUDED
                log("${c.redFlags[a.flagId]?.label ?: a.flagId}: $status (${a.reason})")
                s.copy(flags = s.flags + (a.flagId to FlagRecord(a.flagId, status, Source.DOCTOR, ex?.evidence, a.reason, s.tick)))
            }
            is DoctorAction.ConfirmFlag -> {
                log("${c.redFlags[a.flagId]?.label ?: a.flagId}: CONFIRMED")
                val flags = s.flags + (a.flagId to FlagRecord(a.flagId, FlagStatus.CONFIRMED, Source.DOCTOR, s.flags[a.flagId]?.evidence, null, s.tick))
                s.copy(flags = flags, problems = applyRedirects(s.problems.toMutableList(), flags, c, log))
            }
            is DoctorAction.MarkQuestionAsked -> {
                log("marked ${a.questionId} as asked")
                s.copy(problems = mapProblem(a.problemId) { it.copy(exploredQuestions = it.exploredQuestions + a.questionId) })
            }
            is DoctorAction.OverrideEscalation -> {
                log("manage ${s.problem(a.problemId)?.label} in OPD despite escalation: ${a.reason}")
                s.copy(problems = mapProblem(a.problemId) { it.copy(escalationOverride = a.reason, planCue = true) })
            }
            is DoctorAction.UnlockPlan -> {
                log("unlocked plan for ${s.problem(a.problemId)?.label}")
                s.copy(problems = mapProblem(a.problemId) { it.copy(planCue = true) })
            }
            is DoctorAction.SetVisitType -> {
                log("visit type ${a.visitType} for ${s.problem(a.problemId)?.label}")
                s.copy(problems = mapProblem(a.problemId) { it.copy(visitType = a.visitType, visitTypeSetByDoctor = true) })
            }
            is DoctorAction.RemoveProblem -> {
                log("removed problem ${s.problem(a.problemId)?.label}")
                s.copy(problems = s.problems.filter { it.problemId != a.problemId })
            }
            is DoctorAction.AddProblem -> {
                val def = c.conditions[a.conditionCode]
                if (def == null || s.problems.any { it.conditionCode == a.conditionCode }) s
                else {
                    counter++
                    log("added problem ${def.displayName}")
                    s.copy(problems = s.problems + Problem("prob_" + counter.toString().padStart(3, '0'), def.code, def.displayName))
                }
            }
            is DoctorAction.AddAllergy -> {
                if (a.code !in c.allergies) s else { log("added ${c.allergies[a.code]?.label}"); s.copy(allergies = s.allergies + a.code) }
            }
            is DoctorAction.RemoveAllergy -> {
                log("removed ${c.allergies[a.code]?.label ?: a.code}: ${a.reason}")
                s.copy(allergies = s.allergies - a.code)
            }
            is DoctorAction.AddComorbidity -> {
                if (a.code !in c.comorbidities) s else { log("added ${c.comorbidities[a.code]}"); s.copy(comorbidities = s.comorbidities + a.code) }
            }
            is DoctorAction.RemoveComorbidity -> {
                log("removed ${c.comorbidities[a.code] ?: a.code}")
                s.copy(comorbidities = s.comorbidities - a.code)
            }
            is DoctorAction.AcceptRegimen -> {
                log("accepted ${a.regimenId}")
                s.copy(accepted = s.accepted + a.regimenId, dismissed = s.dismissed - a.regimenId)
            }
            is DoctorAction.DismissRegimen -> {
                log("dismissed ${a.regimenId}")
                s.copy(dismissed = s.dismissed + a.regimenId, accepted = s.accepted - a.regimenId)
            }
            is DoctorAction.RemoveDictated -> {
                log("removed dictated item ${s.dictated.firstOrNull { it.id == a.itemId }?.stated}")
                s.copy(dictated = s.dictated.filter { it.id != a.itemId })
            }
        }
        return evaluate(next.copy(audit = audit, counter = counter), c)
    }

    /* ============================ Evaluation ============================ */

    fun evaluate(s: CaseState, c: Catalogues): CaseState {
        // Session-level alerts: ANY confirmed red flag counts, whatever problems exist.
        var level = SessionLevel.INFO
        val alerts = mutableListOf<Pair<SessionLevel, String>>()
        for (f in s.flags.values) {
            val def = c.redFlags[f.flagId] ?: continue
            if (def.kind == FlagKind.REDIRECT) continue
            when (f.status) {
                FlagStatus.CONFIRMED -> if (def.sessionLevel != SessionLevel.INFO) {
                    if (def.sessionLevel > level) level = def.sessionLevel
                    alerts += def.sessionLevel to "${def.label} - ${dispositionText(def.disposition)}"
                }
                FlagStatus.SUSPECTED -> if (def.sessionLevel == SessionLevel.EMERGENCY) {
                    if (level < SessionLevel.URGENT) level = SessionLevel.URGENT
                    alerts += SessionLevel.URGENT to "Possible: ${def.label} - clarify"
                }
                else -> Unit
            }
        }

        val dictated = s.dictated.map { it.copy(findings = Safety.evaluateDictated(it, s, c)) }
        val s1 = s.copy(dictated = dictated)
        val problems = s1.problems.map { evaluateProblem(it, s1, c) }

        val demo = s.demographics
        val pregCheck = demo.pregnancy == Pregnancy.UNKNOWN && demo.gender == Gender.FEMALE && (demo.age == null || demo.age in 12..50)

        return s1.copy(
            problems = problems,
            sessionLevel = level,
            sessionAlerts = alerts.sortedByDescending { it.first.ordinal }.map { it.second }.distinct(),
            pregnancyCheckNeeded = pregCheck
        )
    }

    private fun evaluateProblem(p: Problem, s: CaseState, c: Catalogues): Problem {
        val def = c.conditions[p.conditionCode]
        val reasons = mutableListOf<String>()
        var escalate = false

        def?.mandatoryRedFlags?.forEach { id ->
            val fd = c.redFlags[id] ?: return@forEach
            if (fd.kind != FlagKind.REDIRECT && s.flags[id]?.status == FlagStatus.CONFIRMED) {
                escalate = true
                reasons += "${fd.label} - ${dispositionText(fd.disposition)}"
            }
        }

        var scoreText: String? = null
        var scoreIncomplete = false
        def?.score?.let { sc ->
            var pts = 0
            val hits = mutableListOf<String>()
            val unknown = mutableListOf<String>()
            for (comp in sc.components) {
                val v: Boolean? = when {
                    comp.flagRef != null -> when (s.flags[comp.flagRef]?.status) {
                        FlagStatus.CONFIRMED -> true
                        FlagStatus.EXCLUDED, FlagStatus.RESOLVED_BY_DOCTOR -> false
                        else -> null
                    }
                    comp.rule != null -> RuleEvaluator.evaluate(comp.rule, s.vitals, s.demographics)
                    else -> null
                }
                when (v) {
                    true -> { pts += comp.points; hits += comp.label }
                    null -> unknown += comp.label
                    false -> Unit
                }
            }
            scoreText = "${sc.scoreName} = $pts" +
                (if (hits.isNotEmpty()) " (${hits.joinToString()})" else "") +
                (if (unknown.isNotEmpty()) "; not yet assessed: ${unknown.joinToString()}" else "")
            scoreIncomplete = unknown.isNotEmpty()
            if (pts >= sc.escalateThreshold) {
                escalate = true
                reasons += "${sc.scoreName} score $pts (threshold ${sc.escalateThreshold}): consider admission"
            }
        }

        val checklist = def?.checklist(p.visitType)
        val qIds = checklist?.questions?.map { it.id } ?: emptyList()
        val pending = qIds.filter { it !in p.exploredQuestions }
        val covered = qIds.size - pending.size
        val mandatory = def?.mandatoryRedFlags ?: emptyList()
        val unassessed = mandatory.filter { val st = s.flags[it]?.status; st == null || st == FlagStatus.UNASSESSED }
        val flagsCleared = mandatory.all { val st = s.flags[it]?.status; st == FlagStatus.EXCLUDED || st == FlagStatus.RESOLVED_BY_DOCTOR }
        val checklistComplete = checklist != null && covered >= checklist.minQuestions && flagsCleared

        val phase = when {
            escalate && p.escalationOverride == null -> Phase.ESCALATED
            p.dictation -> Phase.DICTATION
            p.planCue || checklistComplete -> Phase.PLAN
            else -> Phase.HISTORY
        }

        var suggested: String? = null
        var findings: List<SafetyFinding> = emptyList()
        var skips: List<String> = emptyList()
        var noSafe = false
        var provisional: String? = null
        var provisionalFindings: List<SafetyFinding> = emptyList()
        val blockers = mutableListOf<String>()
        if (def != null && def.linkedRegimens.isNotEmpty() && (phase == Phase.PLAN || phase == Phase.HISTORY)) {
            val sel = selectRegimen(def, s, c)
            if (phase == Phase.PLAN) {
                suggested = sel.regimenId
                findings = sel.findings
                skips = sel.skips
                noSafe = sel.regimenId == null
            } else {
                // Preview only: shown greyed with what is still missing before it can be suggested.
                provisional = sel.regimenId
                provisionalFindings = sel.findings
                if (checklist != null) {
                    val needed = (checklist.minQuestions - covered).coerceAtLeast(0)
                    if (needed > 0) blockers += checklist.questions.filter { it.id in pending }.take(needed).map { "Ask: ${it.promptEn}" }
                }
                mandatory.forEach { id ->
                    val st = s.flags[id]?.status
                    val label = c.redFlags[id]?.label ?: id
                    when (st) {
                        null, FlagStatus.UNASSESSED -> blockers += "Rule out: $label"
                        FlagStatus.SUSPECTED -> blockers += "Clarify: $label"
                        else -> Unit
                    }
                }
            }
        }

        return p.copy(
            phase = phase,
            pendingQuestionIds = pending,
            unassessedFlagIds = unassessed,
            escalationReasons = reasons,
            scoreText = scoreText,
            scoreIncomplete = scoreIncomplete,
            suggestedRegimenId = suggested,
            suggestionFindings = findings,
            skipReasons = skips,
            noSafeRegimen = noSafe,
            provisionalRegimenId = provisional,
            provisionalFindings = provisionalFindings,
            provisionalBlockers = blockers
        )
    }

    private class Selection(val regimenId: String?, val findings: List<SafetyFinding>, val skips: List<String>)

    /** Tries the condition's regimens in catalogue order; the first eligible, not dismissed, not BLOCKED one wins. */
    private fun selectRegimen(def: ConditionDef, s: CaseState, c: Catalogues): Selection {
        val skips = mutableListOf<String>()
        for (rid in def.linkedRegimens) {
            val r = c.regimens[rid] ?: continue
            if (rid in s.dismissed) { skips += "${r.label}: dismissed by doctor"; continue }
            val inelig = eligibilityProblem(r, s, c)
            if (inelig != null) { skips += "${r.label}: $inelig"; continue }
            val f = Safety.evaluateRegimen(r, s, c)
            val blocked = f.filter { it.severity == Severity.BLOCKED }
            if (blocked.isNotEmpty()) { skips += "${r.label}: " + blocked.joinToString("; ") { it.message }; continue }
            return Selection(rid, f, skips)
        }
        return Selection(null, emptyList(), skips)
    }

    /* ============================ Helpers ============================ */

    const val OTHER = "COND_OTHER"

    private fun eligibilityProblem(r: RegimenDef, s: CaseState, c: Catalogues): String? {
        val e = r.eligibility
        val age = s.demographics.age
        if (age == null) return "adult age not recorded; this catalogue has no paediatric dosing"
        if (age < ADULT_MIN_AGE) return "adult-only protocol; age $age is below $ADULT_MIN_AGE"
        if (e.genders.isNotEmpty()) {
            val g = s.demographics.gender
            if (g == Gender.UNKNOWN) return "sex not recorded"
            if (g.name !in e.genders) return "only for " + e.genders.joinToString("/") { it.lowercase() }
        }
        e.minAge?.let { if (age < it) return "not for age below $it" }
        e.maxAge?.let { if (age > it) return "not for age above $it" }
        if (e.requiresAny.isNotEmpty()) {
            val ok = e.requiresAny.any { code -> code in s.comorbidities || s.problems.any { it.conditionCode == code } }
            if (!ok) return "only when " + e.requiresAny.joinToString(" or ") { c.comorbidities[it] ?: c.conditions[it]?.displayName ?: it } + " is present"
        }
        return null
    }

    private fun applyRedirects(
        problems: MutableList<Problem>,
        flags: Map<String, FlagRecord>,
        c: Catalogues,
        log: (String) -> Unit
    ): MutableList<Problem> {
        var list = problems.toMutableList()
        var guard = 0
        while (guard++ < 5) {
            var changed = false
            for (p in list.toList()) {
                val def = c.conditions[p.conditionCode] ?: continue
                val trigger = def.mandatoryRedFlags.mapNotNull { c.redFlags[it] }.firstOrNull { fd ->
                    val st = flags[fd.id]?.status
                    fd.kind == FlagKind.REDIRECT && fd.redirectTo != null && fd.redirectTo != p.conditionCode &&
                        (st == FlagStatus.SUSPECTED || st == FlagStatus.CONFIRMED)
                } ?: continue
                val target = c.conditions[trigger.redirectTo!!] ?: continue
                val existing = list.firstOrNull { it.conditionCode == target.code }
                list = if (existing != null) {
                    val merged = existing.copy(
                        symptoms = (existing.symptoms + p.symptoms).distinctBy { norm(it.name) },
                        negatives = (existing.negatives + p.negatives).distinctBy { norm(it.name) },
                        planCue = existing.planCue || p.planCue,
                        dictation = existing.dictation || p.dictation
                    )
                    list.filter { it.problemId != p.problemId }.map { if (it.problemId == existing.problemId) merged else it }.toMutableList()
                } else {
                    list.map {
                        if (it.problemId == p.problemId) it.copy(
                            conditionCode = target.code,
                            label = target.displayName,
                            visitType = "INITIAL",
                            exploredQuestions = it.exploredQuestions.filter { q -> q in target.allQuestionIds() }.toSet()
                        ) else it
                    }.toMutableList()
                }
                log("${p.label} reclassified as ${target.displayName} (${trigger.label})")
                changed = true
                break
            }
            if (!changed) break
        }
        return list
    }

    private fun applyDerivations(
        flags: MutableMap<String, FlagRecord>,
        changed: Set<String>,
        v: Vitals,
        demo: Demographics,
        c: Catalogues,
        tick: Int,
        log: (String) -> Unit
    ) {
        if (changed.isEmpty()) return
        for (def in c.redFlags.values) {
            val rule = def.derivationRule ?: continue
            if (rule.fields().none { it in changed }) continue
            val ex = flags[def.id]
            if (ex?.status == FlagStatus.RESOLVED_BY_DOCTOR) continue // the doctor's decision stands
            when (RuleEvaluator.evaluate(rule, v, demo)) {
                true -> if (ex?.status != FlagStatus.CONFIRMED) {
                    flags[def.id] = FlagRecord(def.id, FlagStatus.CONFIRMED, Source.DERIVED, "Measured: " + v.summary(), tick = tick)
                    log("${def.label}: CONFIRMED from measurement")
                }
                false -> when (ex?.status) {
                    null, FlagStatus.UNASSESSED -> flags[def.id] = FlagRecord(def.id, FlagStatus.EXCLUDED, Source.DERIVED, "Measured: " + v.summary(), tick = tick)
                    FlagStatus.CONFIRMED, FlagStatus.SUSPECTED -> log("${def.label}: new reading is normal; doctor may clear it")
                    else -> Unit
                }
                null -> Unit
            }
        }
    }

    fun mergeVitals(old: Vitals, w: Vitals?): Vitals {
        if (w == null) return old
        val temp = w.tempF?.let { t -> if (t in 30.0..45.0) t * 9.0 / 5.0 + 32.0 else t }?.takeIf { it in 90.0..110.0 }
        return Vitals(
            bpSystolic = w.bpSystolic?.takeIf { it in 40..300 } ?: old.bpSystolic,
            bpDiastolic = w.bpDiastolic?.takeIf { it in 20..200 } ?: old.bpDiastolic,
            pulse = w.pulse?.takeIf { it in 20..250 } ?: old.pulse,
            rr = w.rr?.takeIf { it in 4..80 } ?: old.rr,
            spo2 = w.spo2?.takeIf { it in 40..100 } ?: old.spo2,
            tempF = temp ?: old.tempF,
            bloodGlucose = w.bloodGlucose?.takeIf { it in 20.0..1500.0 } ?: old.bloodGlucose
        )
    }

    fun changedFields(old: Vitals, new: Vitals): Set<String> {
        val out = mutableSetOf<String>()
        for (f in listOf("bp_systolic", "bp_diastolic", "pulse", "rr", "spo2", "temp_f", "blood_glucose")) {
            val n = new.value(f)
            if (n != null && n != old.value(f)) out += f
        }
        return out
    }

    fun mapAllergy(stated: String, c: Catalogues): Set<String> {
        val out = mutableSetOf<String>()
        val direct = stated.trim().uppercase()
        if (direct in c.allergies) out += direct
        val drug = c.findDrug(stated)
        if (drug != null) for (r in c.allergies.values) if (drug.classes.any { it in r.blockedClasses }) out += r.code
        val t = stated.lowercase()
        for (r in c.allergies.values) if (r.keywords.any { t.contains(it) }) out += r.code
        return out
    }

    fun parseGender(g: String?): Gender? = when (g?.trim()?.lowercase()) {
        "male", "m", "man", "पुरुष" -> Gender.MALE
        "female", "f", "woman", "महिला" -> Gender.FEMALE
        "other" -> Gender.OTHER
        else -> null
    }

    fun normFrequency(f: String?): String? = when (f?.trim()?.uppercase()?.replace(".", "")) {
        null, "" -> null
        "OD", "QD", "ONCE DAILY", "DAILY" -> "OD"
        "BD", "BID", "TWICE DAILY" -> "BD"
        "TDS", "TID", "THRICE DAILY" -> "TDS"
        "QID", "QDS" -> "QID"
        "HS", "AT NIGHT" -> "HS"
        "PRN", "SOS", "AS NEEDED" -> "PRN"
        "STAT" -> "STAT"
        else -> null
    }

    fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun dispositionText(d: Disposition): String = when (d) {
        Disposition.OPD_TREAT -> "manage in OPD"
        Disposition.STAT_INVESTIGATE -> "urgent tests"
        Disposition.REFERRAL -> "refer"
        Disposition.EMERGENCY_TRANSFER -> "emergency care / transfer"
    }

    private const val ADULT_MIN_AGE = 18
}
