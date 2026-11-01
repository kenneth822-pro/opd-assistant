package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.FluidIoEntity

@Dao
interface FluidIoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FluidIoEntity)

    @Query("SELECT * FROM fluid_io_entries WHERE patientId = :patientId AND deletedAt IS NULL ORDER BY timestamp DESC")
    fun observeForPatient(patientId: String): Flow<List<FluidIoEntity>>

    @Query("SELECT * FROM fluid_io_entries WHERE patientId = :patientId AND deletedAt IS NULL AND timestamp >= :since")
    suspend fun since(patientId: String, since: Long): List<FluidIoEntity>

    /** Soft delete (tombstone) so the removal can sync. */
    @Query("UPDATE fluid_io_entries SET deletedAt = :now, isSynced = 0, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM fluid_io_entries WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT * FROM fluid_io_entries WHERE isSynced = 0")
    suspend fun unsynced(): List<FluidIoEntity>

    @Query("UPDATE fluid_io_entries SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
