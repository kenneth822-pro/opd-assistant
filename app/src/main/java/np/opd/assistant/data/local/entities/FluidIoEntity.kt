package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A single fluid intake or output event in the intake/output chart. */
@Entity(
    tableName = "fluid_io_entries",
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
data class FluidIoEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val timestamp: Long = 0L,
    /** e.g. "IV NS", "Oral sips", "Ryle's Tube". */
    val intakeType: String = "",
    val intakeMl: Int = 0,
    /** e.g. "Urine", "Drain 1", "Drain 2", "Vomitus", "Stoma". */
    val outputType: String = "",
    val outputMl: Int = 0,
    val notes: String? = null,
    val updatedAt: Long = 0L,
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
