package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.VitalsLogEntity

@Dao
interface VitalsLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VitalsLogEntity)

    @Query("SELECT * FROM vitals_logs WHERE id = :id")
    suspend fun byId(id: String): VitalsLogEntity?

    @Query("SELECT * FROM vitals_logs WHERE patientId = :patientId AND deletedAt IS NULL ORDER BY timestamp DESC")
    fun observeForPatient(patientId: String): Flow<List<VitalsLogEntity>>

    /** Most recent observation for a patient — used for the bedside NEWS2 badge. */
    @Query("SELECT * FROM vitals_logs WHERE patientId = :patientId AND deletedAt IS NULL ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(patientId: String): Flow<VitalsLogEntity?>

    @Query("SELECT * FROM vitals_logs WHERE patientId = :patientId AND deletedAt IS NULL ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(patientId: String): VitalsLogEntity?

    @Query("UPDATE vitals_logs SET deletedAt = :now, isSynced = 0, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM vitals_logs WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT * FROM vitals_logs WHERE isSynced = 0")
    suspend fun unsynced(): List<VitalsLogEntity>

    @Query("UPDATE vitals_logs SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
