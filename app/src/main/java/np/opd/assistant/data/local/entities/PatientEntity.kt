package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where a patient is currently being cared for. Ordered by escalation so the ward board can
 * sort the sickest first; DISCHARGED is terminal for the current encounter.
 */
enum class WardUnit {
    WARD_1,
    WARD_2,
    BUFFER,
    SEMI_ICU,
    ICU,
    DISCHARGED;

    val isCritical: Boolean get() = this == SEMI_ICU || this == ICU
}

/** Census lifecycle of a patient record (not of a single encounter). */
enum class PatientStatus {
    ACTIVE,
    DISCHARGED,
    TRANSFERRED
}

/**
 * A durable patient identity. A patient can have many encounters (OPD visits, admissions)
 * over time; [mrn] is the hospital number once known.
 */
@Entity(
    tableName = "patients",
    indices = [Index(value = ["mrn"], unique = false), Index("current_ward")]
)
data class PatientEntity(
    @PrimaryKey val id: String,
    val mrn: String? = null,
    val name: String,
    val age: Int? = null,
    val gender: String = "UNKNOWN",
    val currentWard: WardUnit = WardUnit.WARD_1,
    val bedNumber: String = "",
    val status: PatientStatus = PatientStatus.ACTIVE,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
