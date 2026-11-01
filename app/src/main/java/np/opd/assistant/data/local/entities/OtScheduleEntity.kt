package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Operational list of a patient planned for, or in, the operating theatre. */
@Entity(
    tableName = "ot_schedules",
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
data class OtScheduleEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val plannedProcedure: String = "",
    val scheduledDate: Long = 0L,
    /** ELECTIVE, URGENT or EMERGENCY. */
    val urgency: String = "ELECTIVE",
    val pacCleared: Boolean = false,
    val consentSigned: Boolean = false,
    val bloodArrangedUnits: Int = 0,
    val npoSinceTimestamp: Long? = null,
    val siteMarked: Boolean = false,
    /** SCHEDULED, IN_OT, COMPLETED or POSTPONED. */
    val status: String = "SCHEDULED",
    val updatedAt: Long = 0L,
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
