package np.opd.assistant

import np.opd.engine.CatalogueApprovalStatus
import np.opd.engine.ReviewStatus

enum class AppMode(val label: String) {
    SIMULATION("Simulation mode"),
    CLINICAL_PENDING_REVIEW("Clinical mode — pending review"),
    CLINICAL_APPROVED("Clinical-approved mode")
}

enum class ConsentStatus {
    NOT_REQUESTED,
    OBTAINED,
    DECLINED,
    WITHDRAWN
}

data class ConsentRecord(
    val status: ConsentStatus = ConsentStatus.NOT_REQUESTED,
    val externalAiAllowed: Boolean = false,
    val localOnly: Boolean = false,
    val timestampMs: Long? = null
)

enum class AudioRetentionPolicy(val label: String) {
    DELETE_AFTER_TRANSCRIPTION("Delete after each successful transcription"),
    DELETE_AFTER_CONSULTATION("Delete after consultation"),
    KEEP_FOR_SHORT_PERIOD("Keep for a short configurable period"),
    DELETE_IMMEDIATELY("Delete immediately after processing")
}

object GovernancePolicy {
    fun appMode(status: CatalogueApprovalStatus?): AppMode = when (status) {
        CatalogueApprovalStatus.CLINICIAN_APPROVED -> AppMode.CLINICAL_APPROVED
        CatalogueApprovalStatus.CLINICIAN_REVIEW_REQUIRED -> AppMode.CLINICAL_PENDING_REVIEW
        else -> AppMode.SIMULATION
    }

    fun externalAiAllowed(consent: ConsentRecord): Boolean =
        consent.status == ConsentStatus.OBTAINED && consent.externalAiAllowed && !consent.localOnly

    fun localOnly(consent: ConsentRecord): Boolean =
        consent.status == ConsentStatus.OBTAINED && consent.localOnly && !consent.externalAiAllowed

    fun canShare(catalogueStatus: CatalogueApprovalStatus?, review: np.opd.engine.DoctorReview): Boolean =
        catalogueStatus == CatalogueApprovalStatus.CLINICIAN_APPROVED &&
            review.status in setOf(ReviewStatus.DOCTOR_CONFIRMED, ReviewStatus.SIGNED) &&
            !review.doctorIdentity.isNullOrBlank() &&
            !review.catalogueVersion.isNullOrBlank() &&
            !review.modelMetadata.isNullOrBlank()

    fun shareBlockReason(catalogueStatus: CatalogueApprovalStatus?, review: np.opd.engine.DoctorReview): String = when {
        catalogueStatus != CatalogueApprovalStatus.CLINICIAN_APPROVED ->
            "Sharing is disabled: the catalogue is not clinician-approved."
        review.status !in setOf(ReviewStatus.DOCTOR_CONFIRMED, ReviewStatus.SIGNED) ->
            "Sharing is disabled until the doctor confirms the document."
        review.doctorIdentity.isNullOrBlank() ->
            "Sharing is disabled until a clinician identity is recorded in Settings."
        review.catalogueVersion.isNullOrBlank() || review.modelMetadata.isNullOrBlank() ->
            "Sharing is disabled until catalogue and model metadata are recorded."
        else -> ""
    }

    fun modelMetadata(vararg models: String?): String = models.filterNotNull().joinToString("; ")
}

/** Redacts common identifiers from text prompts before a cloud request. Audio is not altered. */
object PrivacySafeText {
    private val phone = Regex("(?<!\\d)(?:\\+?977[- ]?)?9\\d{9}(?!\\d)")
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val labelledIdentifier = Regex(
        "(?im)^(\\s*(?:patient\\s+name|name|नाम|फोन|phone|mobile|address|ठेगाना)\\s*[:=-]).*$"
    )

    fun redact(text: String): String = text
        .replace(labelledIdentifier) { "${it.groupValues[1]} [REDACTED]" }
        .replace(phone, "[PHONE]")
        .replace(email, "[EMAIL]")
}
