package np.opd.engine

import java.util.UUID

/* ------------------------------------------------------------------
 * Case state (immutable) and the inputs that change it:
 *  - LiveDelta: what the AI heard in one audio segment
 *  - DoctorAction: what the clinician tapped
 * ------------------------------------------------------------------ */

enum class Phase { HISTORY, PLAN, DICTATION, ESCALATED }
enum class FlagStatus { UNASSESSED, EXCLUDED, SUSPECTED, CONFIRMED, RESOLVED_BY_DOCTOR }
enum class Source { AI, DERIVED, DOCTOR }
enum class Gender { MALE, FEMALE, OTHER, UNKNOWN }
enum class Pregnancy { UNKNOWN, PREGNANT, NOT_PREGNANT }

data class Demographics(
    val age: Int? = null,
    val gender: Gender = Gender.UNKNOWN,
    val pregnancy: Pregnancy = Pregnancy.UNKNOWN,
    val lactating: Boolean = false
)

data class Vitals(
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val pulse: Int? = null,
    val rr: Int? = null,
    val spo2: Int? = null,
    val tempF: Double? = null,
    val bloodGlucose: Double? = null
) {
    fun value(field: String): Double? = when (field) {
        "bp_systolic" -> bpSystolic?.toDouble()
        "bp_diastolic" -> bpDiastolic?.toDouble()
        "pulse" -> pulse?.toDouble()
        "rr" -> rr?.toDouble()
        "spo2" -> spo2?.toDouble()
        "temp_f" -> tempF
        "blood_glucose" -> bloodGlucose
        else -> null
    }

    fun summary(): String = listOfNotNull(
        if (bpSystolic != null && bpDiastolic != null) "BP $bpSystolic/$bpDiastolic" else null,
        pulse?.let { "Pulse $it" },
        rr?.let { "RR $it" },
        spo2?.let { "SpO2 $it%" },
        tempF?.let { "Temp ${it}°F" },
        bloodGlucose?.let { "Glucose $it" }
    ).joinToString(", ")
}

data class Conflict(val field: String, val existing: String, val proposed: String, val tick: Int)

data class FlagRecord(
    val flagId: String,
    val status: FlagStatus,
    val source: Source,
    val evidence: String? = null,
    val reason: String? = null,
    val tick: Int = 0
)

data class Symptom(val id: String, val name: String, val duration: String?, val source: String?, val evidence: String?)
data class Negative(val id: String, val name: String, val evidence: String?)

data class SafetyFinding(val severity: Severity, val message: String)

data class Problem(
    val problemId: String,
    val conditionCode: String,
    val label: String,
    val visitType: String = "INITIAL",
    val visitTypeSetByDoctor: Boolean = false,
    val phase: Phase = Phase.HISTORY,
    val planCue: Boolean = false,
    val dictation: Boolean = false,
    val escalationOverride: String? = null,
    val symptoms: List<Symptom> = emptyList(),
    val negatives: List<Negative> = emptyList(),
    val exploredQuestions: Set<String> = emptySet(),
    // ---- computed by Reducer.evaluate ----
    val pendingQuestionIds: List<String> = emptyList(),
    val unassessedFlagIds: List<String> = emptyList(),
    val escalationReasons: List<String> = emptyList(),
    val scoreText: String? = null,
    val scoreIncomplete: Boolean = false,
    val suggestedRegimenId: String? = null,
    val suggestionFindings: List<SafetyFinding> = emptyList(),
    val skipReasons: List<String> = emptyList(),
    val noSafeRegimen: Boolean = false,
    /** While still taking history: what the app WOULD suggest once history is complete, and what is still missing. */
    val provisionalRegimenId: String? = null,
    val provisionalFindings: List<SafetyFinding> = emptyList(),
    val provisionalBlockers: List<String> = emptyList()
)

data class CurrentMed(
    val key: String,
    val drugId: String?,
    val stated: String,
    val dailyDoseMg: Double? = null,
    val source: String? = null
)

data class DictatedItem(
    val id: String,
    val stated: String,
    val drugId: String?,
    val doseCount: Double? = null,
    val frequency: String? = null,
    val durationDays: Int? = null,
    val targetCondition: String? = null,
    val findings: List<SafetyFinding> = emptyList()
)

data class TranscriptLine(val speaker: String, val text: String, val offsetMs: Long)

/** A follow-up question proposed by the AI (display only; never changes safety state). */
data class AiQuestion(val ne: String, val en: String, val reason: String = "")

data class CaseState(
    val sessionId: String = UUID.randomUUID().toString(),
    val tick: Int = 0,
    val demographics: Demographics = Demographics(),
    val conflicts: List<Conflict> = emptyList(),
    val vitals: Vitals = Vitals(),
    val problems: List<Problem> = emptyList(),
    val flags: Map<String, FlagRecord> = emptyMap(),
    val allergies: Set<String> = emptySet(),
    val allergyNotes: List<String> = emptyList(),
    val comorbidities: Set<String> = emptySet(),
    val currentMeds: List<CurrentMed> = emptyList(),
    val dictated: List<DictatedItem> = emptyList(),
    val accepted: Set<String> = emptySet(),
    val dismissed: Set<String> = emptySet(),
    val transcript: List<TranscriptLine> = emptyList(),
    val examFindings: List<String> = emptyList(),
    val aiNextQuestions: List<AiQuestion> = emptyList(),
    // ---- computed by Reducer.evaluate ----
    val sessionLevel: SessionLevel = SessionLevel.INFO,
    val sessionAlerts: List<String> = emptyList(),
    val pregnancyCheckNeeded: Boolean = false,
    // ---- bookkeeping ----
    val counter: Int = 0,
    val audit: List<String> = emptyList()
) {
    fun problem(id: String): Problem? = problems.firstOrNull { it.problemId == id }
}

/* ---------------- AI input (one audio segment) ---------------- */

data class LiveDelta(
    val transcript: List<TranscriptLine> = emptyList(),
    val age: Int? = null,
    val gender: String? = null,
    val pregnancy: String? = null,
    val lactating: Boolean? = null,
    val vitals: Vitals? = null,
    val planCue: Boolean = false,
    val planCueCondition: String? = null,
    val dictationCue: Boolean = false,
    val dictationCueCondition: String? = null,
    val followUpVisit: Boolean = false,
    val dictatedItems: List<DictatedInput> = emptyList(),
    val problems: List<ProblemInput> = emptyList(),
    val flagsIdentified: List<FlagInput> = emptyList(),
    val flagsExcluded: List<String> = emptyList(),
    val allergies: List<AllergyInput> = emptyList(),
    val comorbidities: List<String> = emptyList(),
    val currentMeds: List<MedInput> = emptyList(),
    val examFindings: List<String> = emptyList(),
    val nextQuestions: List<AiQuestion> = emptyList()
)

data class ProblemInput(
    val conditionCode: String,
    val freeLabel: String? = null,
    val symptoms: List<SymptomInput> = emptyList(),
    val negatives: List<NegativeInput> = emptyList(),
    val exploredQuestionIds: List<String> = emptyList()
)

data class SymptomInput(val name: String, val duration: String? = null, val source: String? = null, val evidence: String? = null)
data class NegativeInput(val name: String, val evidence: String? = null)
data class FlagInput(val flagId: String, val confirmed: Boolean, val evidence: String? = null)
data class AllergyInput(val stated: String, val reaction: String? = null, val isIntolerance: Boolean = false)
data class MedInput(val stated: String, val dailyDoseMg: Double? = null)
data class DictatedInput(
    val stated: String,
    val doseCount: Double? = null,
    val frequency: String? = null,
    val durationDays: Int? = null,
    val conditionCode: String? = null
)

/* ---------------- Doctor input ---------------- */

sealed class DoctorAction {
    data class SetDemographics(val age: Int?, val gender: Gender?) : DoctorAction()
    data class SettleConflict(val field: String, val value: String) : DoctorAction()
    data class ConfirmPregnancy(val status: Pregnancy) : DoctorAction()
    data class SetVitals(val vitals: Vitals) : DoctorAction()
    /** Doctor states a flag is absent (EXCLUDED) or, if it was raised, resolved. */
    data class ClearFlag(val flagId: String, val reason: String) : DoctorAction()
    data class ConfirmFlag(val flagId: String) : DoctorAction()
    data class MarkQuestionAsked(val problemId: String, val questionId: String) : DoctorAction()
    data class OverrideEscalation(val problemId: String, val reason: String) : DoctorAction()
    data class UnlockPlan(val problemId: String) : DoctorAction()
    data class SetVisitType(val problemId: String, val visitType: String) : DoctorAction()
    data class RemoveProblem(val problemId: String) : DoctorAction()
    data class AddProblem(val conditionCode: String) : DoctorAction()
    data class AddAllergy(val code: String) : DoctorAction()
    data class RemoveAllergy(val code: String, val reason: String) : DoctorAction()
    data class AddComorbidity(val code: String) : DoctorAction()
    data class RemoveComorbidity(val code: String) : DoctorAction()
    data class AcceptRegimen(val regimenId: String) : DoctorAction()
    data class DismissRegimen(val regimenId: String) : DoctorAction()
    data class RemoveDictated(val itemId: String) : DoctorAction()
}
