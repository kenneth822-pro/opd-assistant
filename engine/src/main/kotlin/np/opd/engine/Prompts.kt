package np.opd.engine

/**
 * Builds the AI instructions and the JSON response schema FROM THE CATALOGUES,
 * so condition codes, question IDs and red-flag IDs can never drift from the data.
 * Also parses the AI's JSON back into a LiveDelta.
 */
object Prompts {

    data class SoapNote(
        val chiefComplaint: String = "",
        val history: String = "",
        val examSpoken: String = "",
        val assessment: String = "",
        val planSpoken: String = "",
        val followUp: String = "",
        val adviceSpoken: String = ""
    )

    data class FinalResult(val soap: SoapNote, val delta: LiveDelta)

    /* ---------------------------- prompts ---------------------------- */

    private val GLOSSARY = """
        Colloquial phrases map to SYMPTOMS (never to a diagnosis):
        - "छाती पोल्यो", "पेट पोल्यो", "ग्यास्ट्रिक" -> burning chest/epigastric discomfort
        - "छाती भारी", "छाती कस्सियो" -> chest heaviness/tightness
        - "कालो दिसा", "अलकत्रा जस्तो दिसा" -> black stool (possible melena)
        - "रगत बान्ता" -> vomiting blood
        - "पखाला", "पातलो दिसा" -> diarrhoea; "आउँ" -> mucus in stool
        - "दम", "सास फेर्न गाह्रो", "घ्यारघ्यार" -> breathlessness / wheeze
        - "झमझम" -> tingling/numbness; "रिँगटा" -> dizziness
        - "पिसाब पोल्ने" -> burning urination; "ज्वरो" -> fever; "जाडो भएर काम्ने" -> rigors
        - "प्रेसर" -> blood pressure; "सुगर" -> diabetes / blood sugar
    """.trimIndent()

    private fun reference(c: Catalogues): String {
        val sb = StringBuilder()
        sb.appendLine("CONDITION CODES and their QUESTION IDS (use exactly these IDs):")
        for (cond in c.conditions.values) {
            sb.appendLine("- ${cond.code}: ${cond.displayName}" + if (cond.cues.isNotBlank()) " [heard as: ${cond.cues}]" else "")
            for ((vt, cl) in cond.visitTypes) {
                for (q in cl.questions) sb.appendLine("    ${q.id} ($vt): ${q.promptEn}")
            }
        }
        sb.appendLine("- ${Reducer.OTHER}: anything else (give a short free_label)")
        sb.appendLine()
        sb.appendLine("RED FLAG IDS:")
        for (f in c.redFlags.values) {
            val measured = if (f.derivationRule != null) " [MEASURED: needs a spoken number; never exclude from a vague statement]" else ""
            sb.appendLine("- ${f.id}: ${f.label}. Cue: ${f.cue}$measured")
        }
        sb.appendLine()
        sb.appendLine("COMORBIDITY CODES: " + c.comorbidities.entries.joinToString("; ") { "${it.key} = ${it.value}" })
        sb.appendLine()
        sb.appendLine(GLOSSARY)
        return sb.toString()
    }

    private val RULES = """
        RULES
        1. Report ONLY what is said in the audio you are given. Do not repeat facts already listed in CASE SO FAR. If nothing new was said, return empty arrays.
        2. new_symptoms / new_negatives: short English clinical names. evidence = short verbatim quote in the original script (max 15 words).
        3. explored_question_ids: include a question only if it was asked AND answered. Use only the listed IDs for that condition.
        4. red_flags_identified: status "confirmed" when the patient/attendant clearly affirms it; "suspected" when unclear or second-hand.
           red_flags_excluded: only when the doctor explicitly asked and the answer was clearly "no".
        5. vitals: only numbers explicitly spoken (e.g. "BP 150 by 95", "SpO2 96"). Never estimate. Temperature in °F if spoken that way.
        6. demographics: only if spoken. Omit anything not heard. Use pregnancy "not_pregnant" only if the patient explicitly says so.
        7. allergies: give the drug or group the patient named (in Latin letters, e.g. "amoxicillin") and the reaction. Set is_intolerance=true for side effects such as nausea or acidity; false for rash, swelling, breathing difficulty.
        8. current_meds: medicines the patient already takes (Latin letters, brand or generic) with total daily mg if stated.
        9. plan_cue.detected = true when the doctor signals moving to treatment ("अब औषधि लेखिदिन्छु", "I'll write your medicines"). dictation_cue.detected = true when the doctor says specific drug names/doses as a prescription; then list each drug in dictated_items exactly as said (Latin letters).
        10. exam_findings: only examination findings the doctor said aloud. Never invent examination findings.
        11. follow_up_visit = true if this is clearly a follow-up of a known chronic condition (e.g. "औषधि सकियो", "रिपोर्ट देखाउन आएँ").
        12. You never diagnose beyond the listed codes, never choose medicines and never write patient instructions. The app's safety engine does that.
        13. transcript: brief lines with speaker (doctor/patient/attendant/unknown) and slice_offset_ms from the start of the audio you received.
    """.trimIndent()

    fun liveSystemPrompt(c: Catalogues): String = """
        You are a careful clinical scribe listening to an outpatient (OPD) consultation in Nepal.
        Speech may be Nepali, English, code-switched, or occasionally Maithili/Bhojpuri. Attendants often answer for the patient.
        You receive ONE short consecutive audio segment plus a summary of the case so far.
        Return ONLY JSON matching the schema, describing what is NEW in this segment.

    """.trimIndent() + "\n" + RULES + "\n\n" + reference(c)

    fun finalSystemPrompt(c: Catalogues): String = """
        You are a careful clinical scribe. You receive the COMPLETE audio of an outpatient consultation in Nepal,
        split into consecutive segments in order, plus a summary of what the app captured live (it may be empty or incomplete).
        Return ONLY JSON with:
        (a) soap: a concise note in English. exam_spoken must be "Not verbalized during consultation" unless the doctor said findings aloud.
            plan_spoken/advice_spoken: only what the doctor actually said. Do not add your own plan.
        (b) case_delta: everything clinically relevant from the WHOLE consultation that is missing from the live summary, using the same rules below.

    """.trimIndent() + "\n" + RULES + "\n\n" + reference(c)

    fun caseSummary(s: CaseState, c: Catalogues, recentLines: Int = 10): String {
        val sb = StringBuilder("CASE SO FAR\n")
        val d = s.demographics
        sb.appendLine("Demographics: age ${d.age ?: "unknown"}, sex ${d.gender.name.lowercase()}, pregnancy ${d.pregnancy.name.lowercase()}")
        if (s.vitals.summary().isNotBlank()) sb.appendLine("Vitals: ${s.vitals.summary()}")
        if (s.problems.isEmpty()) sb.appendLine("Problems: none yet")
        for (p in s.problems) {
            sb.appendLine("- ${p.conditionCode} (${p.label})")
            if (p.symptoms.isNotEmpty()) sb.appendLine("    symptoms: " + p.symptoms.joinToString("; ") { it.name + (it.duration?.let { du -> " ($du)" } ?: "") })
            if (p.negatives.isNotEmpty()) sb.appendLine("    negatives: " + p.negatives.joinToString("; ") { it.name })
            if (p.exploredQuestions.isNotEmpty()) sb.appendLine("    answered: " + p.exploredQuestions.joinToString(", "))
        }
        val flagLines = s.flags.values.filter { it.status != FlagStatus.UNASSESSED }.map { "${it.flagId}=${it.status.name.lowercase()}" }
        if (flagLines.isNotEmpty()) sb.appendLine("Red flags: " + flagLines.joinToString(", "))
        if (s.allergies.isNotEmpty() || s.allergyNotes.isNotEmpty())
            sb.appendLine("Allergies: " + (s.allergies.map { c.allergies[it]?.label ?: it } + s.allergyNotes).joinToString("; "))
        if (s.comorbidities.isNotEmpty()) sb.appendLine("Comorbidities: " + s.comorbidities.joinToString(", "))
        if (s.currentMeds.isNotEmpty()) sb.appendLine("Current medicines: " + s.currentMeds.joinToString(", ") { it.stated })
        if (s.dictated.isNotEmpty()) sb.appendLine("Dictated so far: " + s.dictated.joinToString(", ") { it.stated })
        val recent = s.transcript.takeLast(recentLines)
        if (recent.isNotEmpty()) {
            sb.appendLine("Recent transcript:")
            recent.forEach { sb.appendLine("  ${it.speaker}: ${it.text}") }
        }
        return sb.toString()
    }

    /* ---------------------------- schema ---------------------------- */

    private fun str(enum: List<String>? = null): J.Obj =
        if (enum == null) J.obj("type" to "STRING") else J.obj("type" to "STRING", "enum" to enum)
    private fun int() = J.obj("type" to "INTEGER")
    private fun num() = J.obj("type" to "NUMBER")
    private fun bool() = J.obj("type" to "BOOLEAN")
    private fun arr(items: J.Obj) = J.obj("type" to "ARRAY", "items" to items)
    private fun obj(props: Map<String, J.Obj>, required: List<String> = emptyList()): J.Obj =
        if (required.isEmpty()) J.obj("type" to "OBJECT", "properties" to props)
        else J.obj("type" to "OBJECT", "properties" to props, "required" to required)

    fun liveSchema(c: Catalogues): J.Obj {
        val conditionCodes = c.conditions.keys.toList() + Reducer.OTHER
        return obj(
            linkedMapOf(
                "transcript" to arr(obj(linkedMapOf(
                    "speaker" to str(listOf("doctor", "patient", "attendant", "unknown")),
                    "text" to str(),
                    "slice_offset_ms" to int()
                ), listOf("speaker", "text"))),
                "problems" to arr(obj(linkedMapOf(
                    "condition_code" to str(conditionCodes),
                    "free_label" to str(),
                    "new_symptoms" to arr(obj(linkedMapOf(
                        "name" to str(),
                        "duration" to str(),
                        "source" to str(listOf("patient", "attendant", "doctor")),
                        "evidence" to str()
                    ), listOf("name"))),
                    "new_negatives" to arr(obj(linkedMapOf("name" to str(), "evidence" to str()), listOf("name"))),
                    "explored_question_ids" to arr(str())
                ), listOf("condition_code"))),
                "red_flags_identified" to arr(obj(linkedMapOf(
                    "flag_id" to str(),
                    "status" to str(listOf("suspected", "confirmed")),
                    "evidence" to str()
                ), listOf("flag_id", "status"))),
                "red_flags_excluded" to arr(str()),
                "demographics" to obj(linkedMapOf(
                    "age" to int(),
                    "gender" to str(listOf("male", "female", "other")),
                    "pregnancy" to str(listOf("pregnant", "not_pregnant")),
                    "lactating" to bool()
                )),
                "vitals" to obj(linkedMapOf(
                    "bp_systolic" to int(), "bp_diastolic" to int(), "pulse" to int(), "rr" to int(),
                    "spo2" to int(), "temp_f" to num(), "blood_glucose" to num()
                )),
                "allergies" to arr(obj(linkedMapOf("stated" to str(), "reaction" to str(), "is_intolerance" to bool()), listOf("stated"))),
                "comorbidities" to arr(str(c.comorbidities.keys.toList())),
                "current_meds" to arr(obj(linkedMapOf("stated" to str(), "daily_dose_mg" to num()), listOf("stated"))),
                "plan_cue" to obj(linkedMapOf("detected" to bool(), "condition_code" to str()), listOf("detected")),
                "dictation_cue" to obj(linkedMapOf("detected" to bool(), "condition_code" to str()), listOf("detected")),
                "dictated_items" to arr(obj(linkedMapOf(
                    "drug_stated" to str(),
                    "dose_count" to num(),
                    "frequency" to str(listOf("OD", "BD", "TDS", "QID", "HS", "PRN", "STAT")),
                    "duration_days" to int(),
                    "condition_code" to str()
                ), listOf("drug_stated"))),
                "exam_findings" to arr(str()),
                "follow_up_visit" to bool()
            ),
            listOf("transcript", "problems", "red_flags_identified", "red_flags_excluded")
        )
    }

    fun finalSchema(c: Catalogues): J.Obj = obj(
        linkedMapOf(
            "soap" to obj(linkedMapOf(
                "chief_complaint" to str(),
                "history" to str(),
                "exam_spoken" to str(),
                "assessment" to str(),
                "plan_spoken" to str(),
                "advice_spoken" to str(),
                "follow_up" to str()
            ), listOf("chief_complaint", "history", "exam_spoken", "assessment")),
            "case_delta" to liveSchema(c)
        ),
        listOf("soap", "case_delta")
    )

    /* ---------------------------- parsing ---------------------------- */

    fun parseDelta(text: String): LiveDelta = deltaFrom(J.parse(text) as? J.Obj ?: throw JsonException("Response is not a JSON object"))

    fun parseFinal(text: String): FinalResult {
        val o = J.parse(text) as? J.Obj ?: throw JsonException("Response is not a JSON object")
        val s = o.obj("soap")
        val soap = SoapNote(
            chiefComplaint = s?.str("chief_complaint") ?: "",
            history = s?.str("history") ?: "",
            examSpoken = s?.str("exam_spoken") ?: "Not verbalized during consultation",
            assessment = s?.str("assessment") ?: "",
            planSpoken = s?.str("plan_spoken") ?: "",
            followUp = s?.str("follow_up") ?: "",
            adviceSpoken = s?.str("advice_spoken") ?: ""
        )
        return FinalResult(soap, o.obj("case_delta")?.let { deltaFrom(it) } ?: LiveDelta())
    }

    fun deltaFrom(o: J.Obj): LiveDelta {
        val demo = o.obj("demographics")
        val v = o.obj("vitals")
        val plan = o.obj("plan_cue")
        val dict = o.obj("dictation_cue")
        return LiveDelta(
            transcript = o.objs("transcript").map {
                TranscriptLine(it.strOr("speaker", "unknown"), it.strOr("text", ""), (it.dbl("slice_offset_ms") ?: 0.0).toLong())
            },
            age = demo?.int("age"),
            gender = demo?.str("gender"),
            pregnancy = demo?.str("pregnancy"),
            lactating = demo?.bool("lactating"),
            vitals = v?.let {
                Vitals(
                    bpSystolic = it.int("bp_systolic"), bpDiastolic = it.int("bp_diastolic"), pulse = it.int("pulse"),
                    rr = it.int("rr"), spo2 = it.int("spo2"), tempF = it.dbl("temp_f"), bloodGlucose = it.dbl("blood_glucose")
                )
            },
            planCue = plan?.bool("detected") ?: false,
            planCueCondition = plan?.str("condition_code")?.takeIf { it.isNotBlank() },
            dictationCue = dict?.bool("detected") ?: false,
            dictationCueCondition = dict?.str("condition_code")?.takeIf { it.isNotBlank() },
            followUpVisit = o.bool("follow_up_visit") ?: false,
            dictatedItems = o.objs("dictated_items").map {
                DictatedInput(
                    stated = it.strOr("drug_stated", ""),
                    doseCount = it.dbl("dose_count"),
                    frequency = it.str("frequency"),
                    durationDays = it.int("duration_days"),
                    conditionCode = it.str("condition_code")?.takeIf { s -> s.isNotBlank() }
                )
            },
            problems = o.objs("problems").map { p ->
                ProblemInput(
                    conditionCode = p.strOr("condition_code", Reducer.OTHER),
                    freeLabel = p.str("free_label"),
                    symptoms = p.objs("new_symptoms").map { SymptomInput(it.strOr("name", ""), it.str("duration"), it.str("source"), it.str("evidence")) },
                    negatives = p.objs("new_negatives").map { NegativeInput(it.strOr("name", ""), it.str("evidence")) },
                    exploredQuestionIds = p.strs("explored_question_ids")
                )
            },
            flagsIdentified = o.objs("red_flags_identified").map {
                FlagInput(it.strOr("flag_id", ""), it.str("status")?.lowercase() == "confirmed", it.str("evidence"))
            },
            flagsExcluded = o.strs("red_flags_excluded"),
            allergies = o.objs("allergies").map { AllergyInput(it.strOr("stated", ""), it.str("reaction"), it.bool("is_intolerance") ?: false) },
            comorbidities = o.strs("comorbidities"),
            currentMeds = o.objs("current_meds").map { MedInput(it.strOr("stated", ""), it.dbl("daily_dose_mg")) },
            examFindings = o.strs("exam_findings")
        )
    }
}
