package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.LabResultEntity

@Dao
interface LabDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lab: LabResultEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabs(labs: List<LabResultEntity>)

    @Query("SELECT * FROM lab_results WHERE id = :id")
    suspend fun byId(id: String): LabResultEntity?

    /** Cumulative flowsheet: all results for a patient, chronological. */
    @Query("SELECT * FROM lab_results WHERE patientId = :patientId AND deletedAt IS NULL ORDER BY timestamp ASC")
    fun getLabFlowsheet(patientId: String): Flow<List<LabResultEntity>>

    @Query("SELECT * FROM lab_results WHERE encounterId = :encounterId AND deletedAt IS NULL ORDER BY timestamp ASC")
    fun observeForEncounter(encounterId: String): Flow<List<LabResultEntity>>

    @Query("UPDATE lab_results SET deletedAt = :now, isSynced = 0, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM lab_results WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT * FROM lab_results WHERE isSynced = 0")
    suspend fun getUnsyncedLabs(): List<LabResultEntity>

    @Query("UPDATE lab_results SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
