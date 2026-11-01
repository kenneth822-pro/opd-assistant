package np.opd.engine

/**
 * Cross-patient ward views. Everything here is pure: it turns a structured census into a compact
 * text snapshot for an AI query, and can assemble a deterministic SBAR handover as a fallback when
 * no model is available. The AI only ever reads this snapshot; it never mutates patient data.
 */

/** One patient's row in the census, flattened for handover and query use. */
data class CensusPatient(
    val patientId: String,
    val name: String,
    val age: Int? = null,
    val sex: String = "unknown",
    val ward: String = "",
    val bed: String = "",
    val unit: String = "",
    val mrn: String? = null,
    val admissionDate: String = "",
    val postOpDay: Int? = null,
    val workingDiagnosis: String = "",
    val activeMedications: List<String> = emptyList(),
    val pendingLabs: List<String> = emptyList(),
    val openTasks: List<CensusTask> = emptyList(),
    val outstandingConsults: List<CensusConsult> = emptyList(),
    val flags: List<String> = emptyList()
)

data class CensusTask(val title: String, val category: String, val priority: String)

data class CensusConsult(val specialty: String, val question: String, val urgency: String)

data class WardCensus(
    val generatedAt: String = "",
    val patients: List<CensusPatient> = emptyList()
) {
    val size: Int get() = patients.size

    /** Compact single-line-per-patient snapshot for a model prompt. Kept ASCII/plain. */
    fun snapshot(): String {
        if (patients.isEmpty()) return "WARD CENSUS: empty (no active inpatients)."
        val sb = StringBuilder("WARD CENSUS (${patients.size} active patients)")
        if (generatedAt.isNotBlank()) sb.append(" — as of $generatedAt")
        sb.appendLine()
        for (p in patients) {
            sb.appendLine("- ${p.name} | ${p.age ?: "age?"} y ${p.sex} | ${p.ward} Bed ${p.bed} | ${p.unit}")
            if (p.workingDiagnosis.isNotBlank()) sb.appendLine("    diagnosis: ${p.workingDiagnosis}")
            p.postOpDay?.let { sb.appendLine("    post-op day $it") }
            if (p.activeMedications.isNotEmpty()) sb.appendLine("    active meds: ${p.activeMedications.joinToString(", ")}")
            if (p.pendingLabs.isNotEmpty()) sb.appendLine("    pending labs: ${p.pendingLabs.joinToString(", ")}")
            if (p.openTasks.isNotEmpty()) sb.appendLine("    open tasks: ${p.openTasks.joinToString(", ") { "${it.priority}:${it.title}" }}")
            if (p.outstandingConsults.isNotEmpty()) sb.appendLine("    consults: ${p.outstandingConsults.joinToString(", ") { "${it.urgency} ${it.specialty}" }}")
            if (p.flags.isNotEmpty()) sb.appendLine("    flags: ${p.flags.joinToString(", ")}")
        }
        return sb.toString().trimEnd()
    }
}

object WardHandover {

    /**
     * Deterministic SBAR handover, used when the AI is unavailable or the doctor wants an
     * offline version. One SBAR block per sickest patient (ICU/HDU first), then a summary line.
     */
    fun sbar(census: WardCensus): String {
        val sb = StringBuilder()
        sb.appendLine("SBAR WARD HANDOVER")
        if (census.generatedAt.isNotBlank()) sb.appendLine("Generated: ${census.generatedAt}")
        sb.appendLine("Patients: ${census.size}")
        sb.appendLine()

        if (census.patients.isEmpty()) {
            sb.appendLine("No active inpatients to hand over.")
            return sb.toString()
        }

        val critical = census.patients.filter { it.ward.contains("ICU", ignoreCase = true) || it.ward.contains("HDU", ignoreCase = true) }
        if (critical.isNotEmpty()) {
            sb.appendLine("CRITICAL (${critical.size}):")
            critical.forEach { sb.appendLine("  - ${it.name}, ${it.ward} Bed ${it.bed}${diagSuffix(it)}") }
            sb.appendLine()
        }

        census.patients.forEachIndexed { index, p ->
            sb.appendLine("--- Patient ${index + 1}: ${p.name} (${p.ward} Bed ${p.bed}) ---")
            sb.appendLine("S: ${situation(p)}")
            sb.appendLine("B: ${background(p)}")
            sb.appendLine("A: ${assessment(p)}")
            sb.appendLine("R: ${recommendation(p)}")
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    private fun situation(p: CensusPatient): String = buildString {
        append("${p.name}, ${p.age ?: "age?"} y ${p.sex}")
        append(", ${p.ward} Bed ${p.bed}")
        if (p.unit.isNotBlank()) { append(" ("); append(p.unit); append(")") }
        append(". ")
        if (p.workingDiagnosis.isNotBlank()) append("Working diagnosis: ${p.workingDiagnosis}. ")
        val problems = p.flags + p.openTasks.filter { it.priority.equals("STAT", ignoreCase = true) }.map { it.title }
        if (problems.isNotEmpty()) append("Active concerns: ${problems.joinToString("; ")}.")
    }

    private fun background(p: CensusPatient): String = buildString {
        if (p.admissionDate.isNotBlank()) append("Admitted ${p.admissionDate}. ")
        p.postOpDay?.let { append("Post-operative day $it. ") }
        if (p.activeMedications.isNotEmpty()) append("Active medicines: ${p.activeMedications.joinToString(", ")}.")
        if (isBlank()) append("No background recorded.")
    }

    private fun assessment(p: CensusPatient): String = buildString {
        val reactor = buildList {
            if (p.pendingLabs.isNotEmpty()) add("pending labs (${p.pendingLabs.joinToString(", ")})")
            if (p.outstandingConsults.isNotEmpty()) add("outstanding consults (${p.outstandingConsults.joinToString(", ") { it.specialty }})")
        }
        if (reactor.isEmpty()) append("No outstanding investigations or consults recorded.") else append("Awaiting ${reactor.joinToString("; ")}.")
    }

    private fun recommendation(p: CensusPatient): String = buildString {
        val actions = buildList {
            p.openTasks.filter { !it.priority.equals("STAT", ignoreCase = true) }.take(3).forEach { add(it.title) }
            p.outstandingConsults.take(2).forEach { add("chase ${it.specialty} consult") }
        }
        if (actions.isEmpty()) append("Continue current plan; review next round.") else append(actions.joinToString("; ")).append(".")
    }

    private fun diagSuffix(p: CensusPatient): String = if (p.workingDiagnosis.isBlank()) "" else " — ${p.workingDiagnosis}"
}

/** Stable, minimal prompt for the cross-patient query. The AI reads the snapshot only. */
object WardPrompts {

    fun askWardSystemPrompt(): String = """
        You are a ward assistant answering a clinician's question about the current inpatient census.
        You are given a WARD CENSUS snapshot (one block per patient). Answer the question using ONLY the
        data in the snapshot. Do not invent patients, findings or numbers.
        - If the question names a filter (ward, unit, diagnosis, drug, post-op day, lab), list exactly the
          patients that match, one per line, with their location.
        - If the question asks for a summary, answer in 1-3 sentences.
        - If the snapshot does not contain the answer, say so plainly and suggest what is missing.
        - Keep it short and factual. Never give clinical advice beyond the recorded data.
    """.trimIndent()

    fun handoverSystemPrompt(): String = """
        You are writing an SBAR (Situation, Background, Assessment, Recommendation) ward handover for the
        incoming night medical officer. You are given a WARD CENSUS snapshot.
        - Produce one short SBAR block per patient, sickest (ICU/HDU) first.
        - Use ONLY recorded data. Do not invent findings, doses or numbers.
        - Highlight patients with STAT tasks, pending labs, outstanding consults or critical location.
        - End with a one-line "Priorities for the night" list.
        - Plain text only, no markdown tables.
    """.trimIndent()
}
