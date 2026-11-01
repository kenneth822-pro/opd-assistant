package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.TaskCategory
import np.opd.assistant.data.local.entities.TaskEntity

@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM patient_tasks WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM patient_tasks WHERE encounterId = :encounterId ORDER BY isCompleted, priority DESC, createdAt DESC")
    fun observeForEncounter(encounterId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM patient_tasks WHERE patientId = :patientId AND isCompleted = 0 ORDER BY priority DESC, dueTimestamp")
    fun observeOpenForPatient(patientId: String): Flow<List<TaskEntity>>

    /** Open tasks across a section of the ward — the shift "to-do" list. */
    @Query(
        """
        SELECT t.* FROM patient_tasks t
        JOIN patients p ON p.id = t.patientId
        WHERE t.isCompleted = 0 AND p.status = 'ACTIVE'
        ORDER BY t.priority DESC, t.dueTimestamp
        """
    )
    fun observeAllOpen(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM patient_tasks WHERE patientId = :patientId AND isCompleted = 0 AND category = :category")
    suspend fun openByCategory(patientId: String, category: TaskCategory): List<TaskEntity>

    @Query("UPDATE patient_tasks SET isCompleted = 1, completedAt = :completedAt WHERE id = :id")
    suspend fun complete(id: String, completedAt: Long)

    @Query("DELETE FROM patient_tasks WHERE id = :id")
    suspend fun delete(id: String)
}
