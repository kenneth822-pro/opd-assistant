package np.opd.engine

/**
 * A plain note built on the phone from the facts already captured. Used whenever the AI note
 * is unavailable (error, overload, no key), so the review screen is never empty.
 */
object Notes {

    fun localDraft(s: CaseState, c: Catalogues, hints: LocalAssist.Hints? = null): Prompts.SoapNote {
        val problems = s.problems
        val cc = problems.flatMap { p -> p.symptoms.take(2).map { it.name + (it.duration?.let { d -> " ($d)" } ?: "") } }
            .ifEmpty { problems.map { it.label } }
            .joinToString(", ")

        val history = buildList {
            problems.forEach { p ->
                if (p.symptoms.isNotEmpty()) add("${p.label}: " + p.symptoms.joinToString("; ") { it.name + (it.duration?.let { d -> " for $d" } ?: "") })
                if (p.negatives.isNotEmpty()) add("Denies: " + p.negatives.joinToString("; ") { it.name })
            }
            val excluded = s.flags.values.filter { it.status == FlagStatus.EXCLUDED || it.status == FlagStatus.RESOLVED_BY_DOCTOR }
                .mapNotNull { c.redFlags[it.flagId]?.label }
            if (excluded.isNotEmpty()) add("No: " + excluded.joinToString("; "))
            val allergy = s.allergies.map { c.allergies[it]?.label ?: it } + s.allergyNotes
            add("Allergies: " + allergy.ifEmpty { listOf("none recorded") }.joinToString("; "))
            if (s.currentMeds.isNotEmpty()) add("Current medicines: " + s.currentMeds.joinToString(", ") { it.stated })
            if (s.comorbidities.isNotEmpty()) add("Known conditions: " + s.comorbidities.joinToString(", ") { c.comorbidities[it] ?: it })
            if (problems.isEmpty()) {
                val said = s.transcript.filter { it.speaker != "doctor" }.take(3).joinToString(" / ") { it.text.take(160) }
                if (said.isNotBlank()) add("Patient said: $said")
            }
        }.joinToString(". ")

        val exam = listOfNotNull(
            s.vitals.summary().takeIf { it.isNotBlank() },
            s.examFindings.takeIf { it.isNotEmpty() }?.joinToString("; ")
        ).joinToString(". ").ifBlank { "Not verbalized during consultation" }

        val assessment = buildList {
            problems.forEach { p ->
                add(p.label + when (p.phase) {
                    Phase.ESCALATED -> " - ESCALATED (" + p.escalationReasons.joinToString("; ") + ")"
                    Phase.HISTORY -> " - history incomplete"
                    else -> ""
                } + (p.scoreText?.let { " [$it]" } ?: ""))
            }
            s.flags.values.filter { it.status == FlagStatus.CONFIRMED }.mapNotNull { c.redFlags[it.flagId]?.label }
                .forEach { add("Red flag present: $it") }
            if (problems.isEmpty() && hints != null && hints.conditions.isNotEmpty())
                add("Possible (keyword match only, unconfirmed): " + hints.conditions.take(3).joinToString(", ") { c.conditions[it.code]?.displayName ?: it.code })
        }.joinToString("; ")

        val plan = buildList {
            s.accepted.mapNotNull { c.regimens[it]?.label }.forEach { add(it) }
            s.dictated.forEach { add("${it.stated} (dictated)") }
            problems.filter { it.phase == Phase.HISTORY && it.provisionalBlockers.isNotEmpty() }
                .forEach { p -> add("${p.label}: still to do - " + p.provisionalBlockers.take(3).joinToString("; ")) }
        }.joinToString("; ")

        return Prompts.SoapNote(
            chiefComplaint = cc.ifBlank { "Not captured" },
            history = history,
            examSpoken = exam,
            assessment = assessment.ifBlank { "Not captured" },
            planSpoken = plan,
            followUp = "",
            adviceSpoken = "",
            localDraft = true
        )
    }
}
