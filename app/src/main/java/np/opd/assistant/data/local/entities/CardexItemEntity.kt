package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Medication route, restricted to what the ward protocol set actually uses. */
enum class MedRoute { PO, IV, IM, SC, PR, NG, INH, TOPICAL, OTHER }

/**
 * A single active or historic line on a patient's treatment sheet (Cardex).
 * [drugId] references a formulary entry in the engine catalogue when the drug was recognised;
 * [brandStated] always keeps what was actually said so nothing is silently lost.
 */
@Entity(
    tableName = "cardex_items",
    foreignKeys = [
        ForeignKey(
            entity = EncounterEntity::class,
            parentColumns = ["id"],
            childColumns = ["encounterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("encounterId"), Index("patientId"), Index("isActive")]
)
data class CardexItemEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val drugId: String? = null,
    val brandStated: String,
    val doseCount: Double? = null,
    val doseUnit: String = "",
    val route: MedRoute = MedRoute.PO,
    val frequency: String = "",
    val timing: String = "",
    val startDate: Long = 0L,
    val stopDate: Long? = null,
    val isActive: Boolean = true,
    /** True when the change came from a senior round and has passed the deterministic safety gate. */
    val safetyChecked: Boolean = false,
    val safetyNotes: String? = null,
    val createdAt: Long = 0L,
    /** Offline-first sync metadata. */
    val updatedAt: Long = 0L,
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
