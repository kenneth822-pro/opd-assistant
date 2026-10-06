package np.opd.engine

/**
 * Saves and restores an in-progress consultation, so a phone call, a crash or Android reclaiming the app
 * does not lose what was captured.
 *
 * Only facts are stored (what was said, measured, tapped and decided). Everything derived (phases, scores,
 * suggestions, safety findings) is recomputed by [Reducer.evaluate] on load, so a restored case is always
 * judged by the current catalogue and safety rules rather than by whatever they were when it was saved.
 */
data class SavedSession(
    val case: CaseState,
    val mode: String = "LIVE",
    val elapsedSec: Int = 0,
    val heard: Set<String> = emptySet(),
    val doneTips: Set<String> = emptySet(),
    val soap: Prompts.SoapNote? = null,
    val savedAtMs: Long = 0L
)

object CaseJson {

    const val VERSION = 1

    fun encode(x: SavedSession): String {
        val s = x.case
        val d = s.demographics
        val v = s.vitals
        return J.obj(
            "version" to VERSION,
            "saved_at_ms" to x.savedAtMs,
            "mode" to x.mode,
            "elapsed_sec" to x.elapsedSec,
            "heard" to x.heard.toList(),
            "done_tips" to x.doneTips.toList(),
            "soap" to x.soap?.let {
                J.obj(
                    "chief_complaint" to it.chiefComplaint, "history" to it.history, "exam_spoken" to it.examSpoken,
                    "assessment" to it.assessment, "plan_spoken" to it.planSpoken, "follow_up" to it.followUp,
                    "advice_spoken" to it.adviceSpoken, "local_draft" to it.localDraft
                )
            },
            "case" to J.obj(
                "session_id" to s.sessionId,
                "tick" to s.tick,
                "counter" to s.counter,
                "demographics" to J.obj("age" to d.age, "gender" to d.gender.name, "pregnancy" to d.pregnancy.name, "lactating" to d.lactating),
                "conflicts" to s.conflicts.map { J.obj("field" to it.field, "existing" to it.existing, "proposed" to it.proposed, "tick" to it.tick) },
                "vitals" to J.obj(
                    "bp_systolic" to v.bpSystolic, "bp_diastolic" to v.bpDiastolic, "pulse" to v.pulse, "rr" to v.rr,
                    "spo2" to v.spo2, "temp_f" to v.tempF, "blood_glucose" to v.bloodGlucose
                ),
                "problems" to s.problems.map { p ->
                    J.obj(
                        "problem_id" to p.problemId, "condition_code" to p.conditionCode, "label" to p.label,
                        "visit_type" to p.visitType, "visit_type_set_by_doctor" to p.visitTypeSetByDoctor,
                        "plan_cue" to p.planCue, "dictation" to p.dictation, "escalation_override" to p.escalationOverride,
                        "symptoms" to p.symptoms.map { J.obj("id" to it.id, "name" to it.name, "duration" to it.duration, "source" to it.source, "evidence" to it.evidence) },
                        "negatives" to p.negatives.map { J.obj("id" to it.id, "name" to it.name, "evidence" to it.evidence) },
                        "explored" to p.exploredQuestions.toList()
                    )
                },
                "flags" to s.flags.values.map {
                    J.obj("flag_id" to it.flagId, "status" to it.status.name, "source" to it.source.name, "evidence" to it.evidence, "reason" to it.reason, "tick" to it.tick)
                },
                "allergies" to s.allergies.toList(),
                "allergy_notes" to s.allergyNotes,
                "comorbidities" to s.comorbidities.toList(),
                "current_meds" to s.currentMeds.map { J.obj("key" to it.key, "drug_id" to it.drugId, "stated" to it.stated, "daily_dose_mg" to it.dailyDoseMg, "source" to it.source) },
                "dictated" to s.dictated.map {
                    J.obj("id" to it.id, "stated" to it.stated, "drug_id" to it.drugId, "dose_count" to it.doseCount, "frequency" to it.frequency,
                        "duration_days" to it.durationDays, "target_condition" to it.targetCondition)
                },
                "accepted" to s.accepted.toList(),
                "dismissed" to s.dismissed.toList(),
                "transcript" to s.transcript.map { J.obj("speaker" to it.speaker, "text" to it.text, "offset_ms" to it.offsetMs) },
                "exam_findings" to s.examFindings,
                "ai_next_questions" to s.aiNextQuestions.map { J.obj("ne" to it.ne, "en" to it.en, "reason" to it.reason) },
                "ai_differential" to s.aiDifferential.map { J.obj("name" to it.name, "likelihood" to it.likelihood.name, "reason" to it.reason, "condition_code" to it.conditionCode) },
                "audit" to s.audit
            )
        ).toJson()
    }

    /** Throws [JsonException] if the text is not a readable save from this or an older version. */
    fun decode(text: String, c: Catalogues): SavedSession {
        val root = J.parse(text) as? J.Obj ?: throw JsonException("Saved consultation is not a JSON object")
        val version = root.int("version") ?: throw JsonException("Saved consultation has no version")
        if (version > VERSION) throw JsonException("Saved consultation is from a newer app version ($version)")
        val o = root.obj("case") ?: throw JsonException("Saved consultation has no case")

        fun <E : Enum<E>> enumOf(values: Array<E>, name: String?, fallback: E): E = values.firstOrNull { it.name == name } ?: fallback

        val d = o.obj("demographics")
        val v = o.obj("vitals")
        val state = CaseState(
            sessionId = o.strOr("session_id", CaseState().sessionId),
            tick = o.int("tick") ?: 0,
            counter = o.int("counter") ?: 0,
            demographics = Demographics(
                age = d?.int("age"),
                gender = enumOf(Gender.values(), d?.str("gender"), Gender.UNKNOWN),
                pregnancy = enumOf(Pregnancy.values(), d?.str("pregnancy"), Pregnancy.UNKNOWN),
                lactating = d?.bool("lactating") ?: false
            ),
            conflicts = o.objs("conflicts").map { Conflict(it.strOr("field", ""), it.strOr("existing", ""), it.strOr("proposed", ""), it.int("tick") ?: 0) },
            vitals = Vitals(
                bpSystolic = v?.int("bp_systolic"), bpDiastolic = v?.int("bp_diastolic"), pulse = v?.int("pulse"), rr = v?.int("rr"),
                spo2 = v?.int("spo2"), tempF = v?.dbl("temp_f"), bloodGlucose = v?.dbl("blood_glucose")
            ),
            problems = o.objs("problems").map { p ->
                Problem(
                    problemId = p.req("problem_id"),
                    conditionCode = p.req("condition_code"),
                    label = p.strOr("label", p.req("condition_code")),
                    visitType = p.strOr("visit_type", "INITIAL"),
                    visitTypeSetByDoctor = p.bool("visit_type_set_by_doctor") ?: false,
                    planCue = p.bool("plan_cue") ?: false,
                    dictation = p.bool("dictation") ?: false,
                    escalationOverride = p.str("escalation_override"),
                    symptoms = p.objs("symptoms").map { Symptom(it.req("id"), it.strOr("name", ""), it.str("duration"), it.str("source"), it.str("evidence")) },
                    negatives = p.objs("negatives").map { Negative(it.req("id"), it.strOr("name", ""), it.str("evidence")) },
                    exploredQuestions = p.strs("explored").toSet()
                )
            },
            flags = o.objs("flags").associate {
                val id = it.req("flag_id")
                id to FlagRecord(
                    flagId = id,
                    status = enumOf(FlagStatus.values(), it.str("status"), FlagStatus.UNASSESSED),
                    source = enumOf(Source.values(), it.str("source"), Source.AI),
                    evidence = it.str("evidence"),
                    reason = it.str("reason"),
                    tick = it.int("tick") ?: 0
                )
            },
            allergies = o.strs("allergies").toSet(),
            allergyNotes = o.strs("allergy_notes"),
            comorbidities = o.strs("comorbidities").toSet(),
            currentMeds = o.objs("current_meds").map { CurrentMed(it.req("key"), it.str("drug_id"), it.strOr("stated", ""), it.dbl("daily_dose_mg"), it.str("source")) },
            dictated = o.objs("dictated").map {
                DictatedItem(it.req("id"), it.strOr("stated", ""), it.str("drug_id"), it.dbl("dose_count"), it.str("frequency"), it.int("duration_days"), it.str("target_condition"))
            },
            accepted = o.strs("accepted").toSet(),
            dismissed = o.strs("dismissed").toSet(),
            transcript = o.objs("transcript").map { TranscriptLine(it.strOr("speaker", "unknown"), it.strOr("text", ""), (it.dbl("offset_ms") ?: 0.0).toLong()) },
            examFindings = o.strs("exam_findings"),
            aiNextQuestions = o.objs("ai_next_questions").map { AiQuestion(it.strOr("ne", ""), it.strOr("en", ""), it.strOr("reason", "")) },
            aiDifferential = o.objs("ai_differential").map {
                AiDiagnosis(it.strOr("name", ""), enumOf(AiLikelihood.values(), it.str("likelihood"), AiLikelihood.POSSIBLE), it.strOr("reason", ""), it.str("condition_code"))
            },
            audit = o.strs("audit")
        )
        val soap = root.obj("soap")?.let {
            Prompts.SoapNote(
                chiefComplaint = it.strOr("chief_complaint", ""), history = it.strOr("history", ""), examSpoken = it.strOr("exam_spoken", ""),
                assessment = it.strOr("assessment", ""), planSpoken = it.strOr("plan_spoken", ""), followUp = it.strOr("follow_up", ""),
                adviceSpoken = it.strOr("advice_spoken", ""), localDraft = it.bool("local_draft") ?: false
            )
        }
        return SavedSession(
            // Re-judged by the CURRENT catalogue and safety rules, never trusted from the file.
            case = Reducer.evaluate(state, c),
            mode = root.strOr("mode", "LIVE"),
            elapsedSec = root.int("elapsed_sec") ?: 0,
            heard = root.strs("heard").toSet(),
            doneTips = root.strs("done_tips").toSet(),
            soap = soap,
            savedAtMs = (root.dbl("saved_at_ms") ?: 0.0).toLong()
        )
    }
}
