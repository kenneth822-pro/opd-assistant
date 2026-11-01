package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a cross-specialty referral is in its lifecycle. */
enum class ConsultStatus { REQUESTED, SEEN, DONE }

/** How soon the specialist needs to see the patient. */
enum class ConsultUrgency { EMERGENCY, URGENT, ROUTINE }

/**
 * An intra-hospital referral from one service to another (Cardiology, Nephrology, …) together
 * with the specialist's reply, which can be ingested to auto-create follow-up tasks.
 */
@Entity(
    tableName = "consult_requests",
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
data class ConsultEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val targetSpecialty: String,
    val clinicalQuestion: String,
    val status: ConsultStatus = ConsultStatus.REQUESTED,
    val urgency: ConsultUrgency = ConsultUrgency.ROUTINE,
    val replyNotes: String? = null,
    val requestedAt: Long = 0L,
    val repliedAt: Long? = null,
    /** Offline-first sync metadata. */
    val updatedAt: Long = 0L,
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
