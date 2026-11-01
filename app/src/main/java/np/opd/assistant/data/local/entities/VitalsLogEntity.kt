package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A timestamped set of vital signs with the NEWS2 score computed at capture time. Storing the score
 * and risk means the ward board can show a deterioration badge without recomputing history, and the
 * recorded score stays reproducible even if the scoring rules ever change.
 */
@Entity(
    tableName = "vitals_logs",
    foreignKeys = [
        ForeignKey(
            entity = PatientEntity::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("patientId"), Index("timestamp")]
)
data class VitalsLogEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val timestamp: Long = 0L,
    val pulse: Int? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val rr: Int? = null,
    val spo2: Int? = null,
    val tempF: Double? = null,
    val onOxygen: Boolean = false,
    /** News2.ConsciousnessLevel name; stored as TEXT. */
    val consciousness: String = "ALERT",
    val news2Score: Int = 0,
    /** News2.RiskLevel name; stored as TEXT. */
    val news2Risk: String = "LOW",
    val updatedAt: Long = 0L,
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
