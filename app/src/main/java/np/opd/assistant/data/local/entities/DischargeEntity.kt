package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Whether the discharge document was written for an ER attendance or an inpatient admission. */
enum class DischargeKind { ER, INPATIENT }

/** Lifecycle of the discharge document (independent of the encounter status). */
enum class DischargeStatus { DRAFT, FINAL }

/**
 * A discharge summary for an encounter. The clinical narrative and the regenerated text exports
 * are stored as plain text so a saved document is reproducible and reviewable without code.
 */
@Entity(
    tableName = "discharges",
    foreignKeys = [
        ForeignKey(
            entity = EncounterEntity::class,
            parentColumns = ["id"],
            childColumns = ["encounterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("encounterId"), Index("patientId"), Index("status")]
)
data class DischargeEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val kind: DischargeKind = DischargeKind.INPATIENT,
    val status: DischargeStatus = DischargeStatus.DRAFT,
    // Header
    val consultantName: String = "",
    val admissionDate: Long? = null,
    val dischargeDate: Long? = null,
    // Inpatient surgical detail (optional for ER)
    val procedureName: String? = null,
    val preoperativeDiagnosis: String? = null,
    val postoperativeDiagnosis: String? = null,
    val operativeFindings: String? = null,
    // Narrative
    val presentingComplaints: String = "",
    val hospitalCourse: String = "",
    // Disposition
    val dischargeVitals: String = "",
    val homeMedications: String = "",
    val woundNursingAdvice: String = "",
    val redFlagsReturn: String = "",
    val followUp: String = "",
    // Rendered exports
    val renderedText: String = "",
    val renderedMarkdown: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Offline-first sync metadata. */
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
