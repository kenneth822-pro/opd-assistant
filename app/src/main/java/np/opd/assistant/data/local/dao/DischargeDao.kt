package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.DischargeEntity
import np.opd.assistant.data.local.entities.DischargeStatus

@Dao
interface DischargeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(discharge: DischargeEntity)

    @Update
    suspend fun update(discharge: DischargeEntity)

    @Query("SELECT * FROM discharges WHERE id = :id")
    suspend fun byId(id: String): DischargeEntity?

    @Query("SELECT * FROM discharges WHERE encounterId = :encounterId ORDER BY createdAt DESC")
    fun observeForEncounter(encounterId: String): Flow<List<DischargeEntity>>

    @Query("SELECT * FROM discharges WHERE encounterId = :encounterId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestForEncounter(encounterId: String): DischargeEntity?

    @Query("SELECT * FROM discharges WHERE patientId = :patientId ORDER BY createdAt DESC")
    fun observeForPatient(patientId: String): Flow<List<DischargeEntity>>

    @Query("SELECT COUNT(*) FROM discharges WHERE status = :status")
    fun observeCountByStatus(status: DischargeStatus): Flow<Int>

    @Query("DELETE FROM discharges WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM discharges WHERE isSynced = 0")
    suspend fun unsynced(): List<DischargeEntity>

    @Query("UPDATE discharges SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
