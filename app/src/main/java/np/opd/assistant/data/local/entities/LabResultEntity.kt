package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One numeric laboratory result for a patient. Abnormal direction and panic flag are computed at
 * capture time from the engine [np.opd.engine.LabCatalogue] so the flowsheet can render trend
 * badges without recomputing history.
 */
@Entity(
    tableName = "lab_results",
    foreignKeys = [
        ForeignKey(
            entity = PatientEntity::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("patientId"), Index("encounterId"), Index("timestamp"), Index("updatedAt")]
)
data class LabResultEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val timestamp: Long = 0L,
    val testCode: String = "",
    val displayName: String = "",
    /** LabCatalogue.Category name; stored as TEXT. */
    val category: String = "OTHER",
    val numericValue: Double = 0.0,
    val unit: String = "",
    val isAbnormal: Boolean = false,
    /** LabCatalogue.Direction name (HIGH, LOW, NORMAL). */
    val abnormalDirection: String = "NORMAL",
    val isPanic: Boolean = false,
    val updatedAt: Long = 0L,
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
