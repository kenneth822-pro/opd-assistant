package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Clinical media categories stored in the isolated vault. */
enum class MediaCategory {
    WOUND_POSTOP,
    XRAY,
    CT_SCAN,
    MRI,
    ULTRASOUND,
    ECG,
    LAB_REPORT,
    BEDSIDE_CHART,
    OTHER
}

/** Left/right laterality for paired anatomical imaging and wounds. */
enum class MediaSide { LEFT, RIGHT, BILATERAL, NA }

/**
 * A photo captured in-app and kept in private scoped storage under
 * `files/patients/{patientId}/media/`. [filePath] is app-internal and never written to the
 * public gallery.
 */
@Entity(
    tableName = "patient_media",
    foreignKeys = [
        ForeignKey(
            entity = PatientEntity::class,
            parentColumns = ["id"],
            childColumns = ["patientId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("patientId"), Index("category"), Index("captureDatetime")]
)
data class MediaEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val filePath: String,
    val category: MediaCategory = MediaCategory.OTHER,
    val captureDatetime: Long = 0L,
    val notes: String? = null,
    /** Free-text orientation/extent, e.g. "POD 3 wound", "Axial CT brain, bone window". */
    val label: String? = null,
    val side: MediaSide = MediaSide.NA,
    /** True once a black-bar anonymisation pass has been applied. */
    val anonymised: Boolean = false
)
