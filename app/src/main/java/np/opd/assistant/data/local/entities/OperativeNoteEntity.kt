package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A surgeon's operative note. The structured fields are kept as serialized JSON ([dataJson]) so the
 * note can be re-opened and edited, and the rendered plain text is cached in [fullText] for fast
 * copy/share and PC bridge serving.
 */
@Entity(
    tableName = "operative_notes",
    foreignKeys = [
        ForeignKey(
            entity = EncounterEntity::class,
            parentColumns = ["id"],
            childColumns = ["encounterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("encounterId"), Index("patientId"), Index("createdAt"), Index("updatedAt")]
)
data class OperativeNoteEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val createdAt: Long = 0L,
    /** Serialized np.opd.engine.OpNoteFormat.Data. */
    val dataJson: String = "",
    val fullText: String = "",
    val updatedAt: Long = 0L,
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
