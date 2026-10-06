package np.opd.assistant

import np.opd.engine.CaseState
import np.opd.engine.Catalogues
import np.opd.engine.Gender
import np.opd.engine.Phase
import np.opd.engine.Prompts
import np.opd.engine.Severity
import np.opd.engine.Sig

/** Plain-text prescription slip + note, for sharing via WhatsApp, email, etc. */
object RxText {

    fun build(s: CaseState, soap: Prompts.SoapNote?, c: Catalogues): String {
        val sb = StringBuilder()
        sb.appendLine("*** PROTOTYPE - TEST DATA ONLY - NOT A VALID PRESCRIPTION ***")
        sb.appendLine()
        val d = s.demographics
        val sex = when (d.gender) { Gender.MALE -> "Male"; Gender.FEMALE -> "Female"; Gender.OTHER -> "Other"; Gender.UNKNOWN -> "Sex not recorded" }
        sb.appendLine("Patient: ${d.age?.let { "$it y" } ?: "age not recorded"}, $sex")
        if (s.vitals.summary().isNotBlank()) sb.appendLine("Vitals: ${s.vitals.summary()}")
        val allergyText = (s.allergies.map { c.allergies[it]?.label ?: it } + s.allergyNotes)
        sb.appendLine("Allergies: " + if (allergyText.isEmpty()) "none recorded" else allergyText.joinToString("; "))
        sb.appendLine()

        val alerts = s.sessionAlerts + s.scopeAlerts
        if (alerts.isNotEmpty()) {
            sb.appendLine("ALERTS:")
            alerts.forEach { sb.appendLine("  ! $it") }
            sb.appendLine()
        }

        sb.appendLine("Diagnosis / problems:")
        s.problems.forEach { p ->
            val icd = c.conditions[p.conditionCode]?.icd11?.takeIf { it.isNotBlank() }?.let { " (ICD-11 $it)" } ?: ""
            sb.appendLine("  - ${p.label}$icd" + if (p.phase == Phase.ESCALATED) "  [ESCALATED]" else "")
        }
        sb.appendLine()

        sb.appendLine("Rx:")
        var n = 1
        for (rid in s.accepted) {
            val r = c.regimens[rid] ?: continue
            for (comp in r.components) {
                val drug = c.drugs[comp.drugId] ?: continue
                sb.appendLine("  $n. ${Sig.pharmacyLine(comp, drug)}")
                sb.appendLine("     ${Sig.english(comp, drug)}")
                sb.appendLine("     ${Sig.nepali(comp, drug)}")
                val pic = Sig.pictogramText(comp)
                if (pic.isNotBlank()) sb.appendLine("     $pic")
                n++
            }
        }
        for (item in s.dictated) {
            val drug = item.drugId?.let { c.drugs[it] }
            val freq = item.frequency?.let { " $it" } ?: ""
            val dur = item.durationDays?.let { " x $it days" } ?: ""
            sb.appendLine("  $n. ${item.stated}$freq$dur" + (drug?.let { "  [${it.generic} ${it.strengthText()}]" } ?: "  [not in formulary]"))
            item.findings.filter { it.severity != Severity.CAUTION }.forEach { sb.appendLine("     ⚠ ${it.severity}: ${it.message}") }
            n++
        }
        if (n == 1) sb.appendLine("  (no medicines accepted)")
        sb.appendLine()

        val inv = s.problems.flatMap { c.conditions[it.conditionCode]?.linkedInvestigations ?: emptyList() }.distinct()
        if (inv.isNotEmpty()) {
            sb.appendLine("Investigations to consider:")
            inv.forEach { sb.appendLine("  - ${c.investigations[it]?.name ?: it}") }
            sb.appendLine()
        }

        if (soap != null) {
            sb.appendLine("NOTE")
            sb.appendLine("CC: ${soap.chiefComplaint}")
            sb.appendLine("History: ${soap.history}")
            sb.appendLine("Exam: ${soap.examSpoken}")
            sb.appendLine("Assessment: ${soap.assessment}")
            if (soap.planSpoken.isNotBlank()) sb.appendLine("Plan (as spoken): ${soap.planSpoken}")
            if (soap.adviceSpoken.isNotBlank()) sb.appendLine("Advice (as spoken): ${soap.adviceSpoken}")
            if (soap.followUp.isNotBlank()) sb.appendLine("Follow-up: ${soap.followUp}")
        }
        sb.appendLine()
        sb.appendLine("Regimens and safety rules are UNVERIFIED prototype content (catalogue v${c.version}).")
        return sb.toString()
    }
}
