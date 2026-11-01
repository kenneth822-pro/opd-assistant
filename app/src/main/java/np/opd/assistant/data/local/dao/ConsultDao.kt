package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.ConsultEntity
import np.opd.assistant.data.local.entities.ConsultStatus

@Dao
interface ConsultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(consult: ConsultEntity)

    @Update
    suspend fun update(consult: ConsultEntity)

    @Query("SELECT * FROM consult_requests WHERE id = :id")
    suspend fun byId(id: String): ConsultEntity?

    @Query("SELECT * FROM consult_requests WHERE encounterId = :encounterId ORDER BY requestedAt DESC")
    fun observeForEncounter(encounterId: String): Flow<List<ConsultEntity>>

    @Query("SELECT * FROM consult_requests WHERE patientId = :patientId ORDER BY requestedAt DESC")
    fun observeForPatient(patientId: String): Flow<List<ConsultEntity>>

    /** Outstanding referrals that have not been completed — chased on each shift. */
    @Query("SELECT * FROM consult_requests WHERE status != :done ORDER BY urgency, requestedAt")
    fun observeOutstanding(done: ConsultStatus = ConsultStatus.DONE): Flow<List<ConsultEntity>>

    @Query("DELETE FROM consult_requests WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM consult_requests WHERE isSynced = 0")
    suspend fun unsynced(): List<ConsultEntity>

    @Query("UPDATE consult_requests SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
