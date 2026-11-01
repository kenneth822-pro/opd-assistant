package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Kind of clinical encounter. OPD/ER are single-session; INPATIENT spans admission→discharge. */
enum class EncounterType { OPD, ER, INPATIENT }

/** Surgical specialty / service group used to filter the ward board. */
enum class ServiceUnit {
    UNIT_1,
    UNIT_2,
    NEUROSURGERY,
    ORTHO,
    MEDICINE,
    PEDIATRIC_SURGERY,
    CUSTOM
}

/**
 * One continuous episode of care for a [PatientEntity]. Deleting a patient cascades to their
 * encounters, and encounter deletion cascades to cardex/tasks/media/consults.
 */
@Entity(
    tableName = "encounters",
    foreignKeys = [
        ForeignKey(
            entity = PatientEntity::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("patientId"), Index("unit")]
)
data class EncounterEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterType: EncounterType = EncounterType.INPATIENT,
    val admissionDatetime: Long = 0L,
    val dischargeDatetime: Long? = null,
    val unit: ServiceUnit = ServiceUnit.UNIT_1,
    val consultantName: String = "",
    val finalDiagnosis: String? = null,
    /** Offline-first sync metadata. */
    val updatedAt: Long = 0L,
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
