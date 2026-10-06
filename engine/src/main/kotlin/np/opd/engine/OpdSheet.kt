package np.opd.engine

/* ------------------------------------------------------------------
 * The "OPD consultation sheet", built live from everything captured so far.
 *
 * It is a plain summary of the case state plus the provisional diagnosis, so it can be opened at any
 * moment of the consultation and is always current. Nothing here is invented: every line comes from the
 * case state, the catalogue or the differential. Existing notes ([Notes.localDraft], the AI note) are
 * untouched; this is an additional view.
 * ------------------------------------------------------------------ */

data class SheetComplaint(val title: String, val symptoms: List<String>, val denies: List<String>)

/** [status] is one of "PRESENT", "possible", "not asked", "absent". */
data class SheetFlag(val label: String, val status: String)

data class OpdSheet(
    val patient: String,
    val vitals: String,
    val allergies: List<String>,
    val knownConditions: List<String>,
    val currentMeds: List<String>,
    val complaints: List<SheetComplaint>,
    val exam: List<String>,
    val impression: List<String>,
    val aiImpression: List<String>,
    val danger: List<String>,
    val redFlags: List<SheetFlag>,
    val toAsk: List<String>,
    val investigations: List<String>,
    val treatment: List<String>,
    val alerts: List<String>
) {
    companion object {
        private const val MAX_TO_ASK = 8

        fun build(s: CaseState, r: DifferentialResult, c: Catalogues): OpdSheet {
            val d = s.demographics

            val patient = buildList {
                add(d.age?.let { "$it y" } ?: "age not recorded")
                add(when (d.gender) { Gender.MALE -> "Male"; Gender.FEMALE -> "Female"; Gender.OTHER -> "Other"; Gender.UNKNOWN -> "sex not recorded" })
                if (d.pregnancy == Pregnancy.PREGNANT) add("Pregnant")
                else if (s.pregnancyCheckNeeded) add("pregnancy not confirmed")
                if (d.lactating) add("Breastfeeding")
            }.joinToString(" · ")

            val complaints = s.problems.map { p ->
                SheetComplaint(
                    title = p.label + if (p.visitType == "FOLLOW_UP") " (follow-up)" else "",
                    symptoms = p.symptoms.map { it.name + (it.duration?.let { du -> " ($du)" } ?: "") },
                    denies = p.negatives.map { it.name }
                )
            }.ifEmpty {
                val said = s.transcript.filter { it.speaker != "doctor" }.take(3).map { it.text.take(160) }
                if (said.isEmpty()) emptyList() else listOf(SheetComplaint("Patient said", said, emptyList()))
            }

            val impression = r.ranked.map { e ->
                val band = when (e.likelihood) { Likelihood.LIKELY -> "Likely"; Likelihood.POSSIBLE -> "Possible"; Likelihood.UNLIKELY -> "Less likely" }
                "$band: ${e.label}" + (e.icd11.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            } + r.outsideCatalogue.map { "Other: $it (no protocol in this app)" }

            val aiImpression = r.ai.map { a ->
                val tag = when (a.likelihood) { AiLikelihood.LIKELY -> "likely"; AiLikelihood.POSSIBLE -> "possible"; AiLikelihood.MUST_NOT_MISS -> "do not miss" }
                "${a.name} ($tag)" + (a.reason.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")
            }

            val danger = r.danger.map { x ->
                "${x.implies}: ${x.label} [${if (x.confirmed) "present" else "possible"}] - ${x.action}"
            }

            val problemFlagIds = s.problems.flatMap { c.conditions[it.conditionCode]?.mandatoryRedFlags.orEmpty() }
            val flagIds = (s.flags.filterValues { it.status != FlagStatus.UNASSESSED }.keys + problemFlagIds).distinct()
            val redFlags = flagIds.mapNotNull { id ->
                val def = c.redFlags[id] ?: return@mapNotNull null
                if (def.kind == FlagKind.REDIRECT) return@mapNotNull null
                val status = when (s.flags[id]?.status) {
                    FlagStatus.CONFIRMED -> "PRESENT"
                    FlagStatus.SUSPECTED -> "possible"
                    FlagStatus.EXCLUDED, FlagStatus.RESOLVED_BY_DOCTOR -> "absent"
                    FlagStatus.UNASSESSED, null -> "not asked"
                }
                SheetFlag(def.label, status)
            }.sortedBy { listOf("PRESENT", "possible", "not asked", "absent").indexOf(it.status) }

            val toAsk = buildList {
                for (p in s.problems) {
                    val def = c.conditions[p.conditionCode] ?: continue
                    def.checklist(p.visitType)?.questions.orEmpty().filter { it.id in p.pendingQuestionIds }.take(3)
                        .forEach { add("Ask: ${it.promptEn}") }
                }
                for (p in s.problems) for (id in p.unassessedFlagIds) c.redFlags[id]?.let { f ->
                    add(if (f.derivationRule != null) "Measure: ${f.label}" else "Rule out: ${f.label}")
                }
                for (f in r.ruleOut) add(if (f.derivationRule != null) "Measure: ${f.label}" else "Rule out: ${f.label}")
            }.distinct().take(MAX_TO_ASK)

            val invCodes = (s.problems.map { it.conditionCode } + listOfNotNull(r.working?.code)).distinct()
            val investigations = invCodes.flatMap { c.conditions[it]?.linkedInvestigations.orEmpty() }.distinct()
                .mapNotNull { c.investigations[it]?.name }

            val treatment = buildList {
                for (rid in s.accepted) c.regimens[rid]?.let { add("✓ ${it.label}") }
                for (p in s.problems) {
                    when (p.phase) {
                        Phase.PLAN -> p.suggestedRegimenId?.takeIf { it !in s.accepted }?.let { rid ->
                            c.regimens[rid]?.let { add("Suggested for ${p.label}: ${it.label}") }
                        }
                        Phase.HISTORY -> p.provisionalRegimenId?.let { rid ->
                            c.regimens[rid]?.let { add("Likely for ${p.label} (history incomplete): ${it.label}") }
                        }
                        Phase.ESCALATED -> add("${p.label}: escalated - OPD medicines hidden")
                        Phase.DICTATION -> Unit
                    }
                }
                for (item in s.dictated) {
                    val blocked = item.findings.any { it.severity == Severity.BLOCKED }
                    add("Dictated: ${item.stated}" + (item.frequency?.let { " $it" } ?: "") + (item.durationDays?.let { " x $it days" } ?: "") + if (blocked) "  ⚠ BLOCKED by safety check" else "")
                }
            }

            val alerts = buildList {
                addAll(s.sessionAlerts)
                addAll(s.scopeAlerts)
                if (s.pregnancyCheckNeeded) add("Pregnancy not confirmed: some medicines are held back.")
                s.conflicts.forEach { add("Heard a different ${it.field}: ${it.proposed} (kept ${it.existing}) - please settle.") }
            }

            return OpdSheet(
                patient = patient,
                vitals = s.vitals.summary(),
                allergies = s.allergies.map { c.allergies[it]?.label ?: it } + s.allergyNotes,
                knownConditions = s.comorbidities.map { c.comorbidities[it] ?: it },
                currentMeds = s.currentMeds.map { it.stated + (it.dailyDoseMg?.let { mg -> " (${mg.toInt()} mg/day)" } ?: "") },
                complaints = complaints,
                exam = s.examFindings,
                impression = impression,
                aiImpression = aiImpression,
                danger = danger,
                redFlags = redFlags,
                toAsk = toAsk,
                investigations = investigations,
                treatment = treatment,
                alerts = alerts
            )
        }
    }

    /** Plain text for sharing or pasting. Always marked as a provisional draft. */
    fun toText(c: Catalogues): String {
        val sb = StringBuilder()
        fun section(title: String, lines: List<String>, empty: String? = null) {
            if (lines.isEmpty() && empty == null) return
            sb.appendLine(title)
            if (lines.isEmpty()) sb.appendLine("  $empty") else lines.forEach { sb.appendLine("  - $it") }
            sb.appendLine()
        }
        sb.appendLine("*** PROVISIONAL OPD SHEET - LIVE DRAFT - PROTOTYPE, TEST DATA ONLY ***")
        sb.appendLine()
        sb.appendLine("Patient: $patient")
        if (vitals.isNotBlank()) sb.appendLine("Vitals: $vitals")
        sb.appendLine("Allergies: " + allergies.ifEmpty { listOf("none recorded") }.joinToString("; "))
        if (knownConditions.isNotEmpty()) sb.appendLine("Known conditions: " + knownConditions.joinToString(", "))
        if (currentMeds.isNotEmpty()) sb.appendLine("Already taking: " + currentMeds.joinToString(", "))
        sb.appendLine()
        section("ALERTS", alerts)
        sb.appendLine("COMPLAINTS / HISTORY")
        if (complaints.isEmpty()) sb.appendLine("  (nothing captured yet)")
        complaints.forEach {
            sb.appendLine("  - ${it.title}" + if (it.symptoms.isNotEmpty()) ": " + it.symptoms.joinToString("; ") else "")
            if (it.denies.isNotEmpty()) sb.appendLine("      Denies: " + it.denies.joinToString("; "))
        }
        sb.appendLine()
        section("EXAMINATION (as spoken)", exam)
        section("PROVISIONAL DIAGNOSIS (on-phone evidence)", impression, empty = "not yet clear")
        section("AI SUGGESTS (unverified)", aiImpression)
        section("DON'T MISS", danger)
        section("RED FLAGS", redFlags.map { "${it.label}: ${it.status}" })
        section("STILL TO COVER", toAsk)
        section("INVESTIGATIONS TO CONSIDER", investigations)
        section("TREATMENT SO FAR", treatment)
        val v = c.verification()
        sb.appendLine("Protocol content (catalogue v${c.version}): ${v.conditions.sourceMatched + v.conditions.clinicianVerified} of ${v.conditions.total} conditions checked against a guideline source. UNVERIFIED prototype data.")
        return sb.toString()
    }
}
