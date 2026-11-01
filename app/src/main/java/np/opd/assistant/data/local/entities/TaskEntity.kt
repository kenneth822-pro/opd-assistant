package np.opd.assistant.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What kind of work a task represents on the ward board. */
enum class TaskCategory { LAB, NURSING, MED, DIET, IMAGING, CONSULT, VITALS, OTHER }

/** How time-critical a task is; STAT sorts to the top of every shift list. */
enum class TaskPriority { STAT, URGENT, ROUTINE }

/**
 * A unit of clinical work owed to a patient: "send stat ABG", "re-check vitals at 6 PM",
 * "chase the amylase result". Tasks are also how "lost updates" are surfaced — an abnormal
 * finding creates a task that stays open until explicitly closed.
 */
@Entity(
    tableName = "patient_tasks",
    foreignKeys = [
        ForeignKey(
            entity = EncounterEntity::class,
            parentColumns = ["id"],
            childColumns = ["encounterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("encounterId"), Index("patientId"), Index("isCompleted"), Index("priority")]
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val title: String,
    val category: TaskCategory = TaskCategory.OTHER,
    val isCompleted: Boolean = false,
    val priority: TaskPriority = TaskPriority.ROUTINE,
    val dueTimestamp: Long? = null,
    val completedAt: Long? = null,
    /** Set when a round/consult created this task, so the source can be traced. */
    val sourceNote: String? = null,
    val createdAt: Long = 0L,
    /** Offline-first sync metadata. */
    val updatedAt: Long = 0L,
    val isSynced: Boolean = false,
    val deletedAt: Long? = null
)
