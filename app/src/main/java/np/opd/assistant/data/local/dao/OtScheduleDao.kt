package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.OtScheduleEntity

@Dao
interface OtScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schedule: OtScheduleEntity)

    @Update
    suspend fun update(schedule: OtScheduleEntity)

    @Query("SELECT * FROM ot_schedules WHERE id = :id")
    suspend fun byId(id: String): OtScheduleEntity?

    @Query("SELECT * FROM ot_schedules WHERE encounterId = :encounterId AND deletedAt IS NULL ORDER BY scheduledDate")
    fun observeForEncounter(encounterId: String): Flow<List<OtScheduleEntity>>

    @Query("SELECT * FROM ot_schedules WHERE patientId = :patientId AND deletedAt IS NULL ORDER BY scheduledDate")
    fun observeForPatient(patientId: String): Flow<List<OtScheduleEntity>>

    /** The next / current theatre list entry for a patient. */
    @Query("SELECT * FROM ot_schedules WHERE patientId = :patientId AND deletedAt IS NULL ORDER BY scheduledDate LIMIT 1")
    suspend fun nextForPatient(patientId: String): OtScheduleEntity?

    @Query("SELECT * FROM ot_schedules WHERE isSynced = 0")
    suspend fun unsynced(): List<OtScheduleEntity>

    @Query("UPDATE ot_schedules SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE ot_schedules SET deletedAt = :now, isSynced = 0, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM ot_schedules WHERE id = :id")
    suspend fun hardDelete(id: String)
}
