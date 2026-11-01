package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.EncounterEntity
import np.opd.assistant.data.local.entities.ServiceUnit

@Dao
interface EncounterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(encounter: EncounterEntity)

    @Update
    suspend fun update(encounter: EncounterEntity)

    @Query("SELECT * FROM encounters WHERE id = :id")
    suspend fun byId(id: String): EncounterEntity?

    @Query("SELECT * FROM encounters WHERE id = :id")
    fun observeById(id: String): Flow<EncounterEntity?>

    @Query("SELECT * FROM encounters WHERE patientId = :patientId ORDER BY admissionDatetime DESC")
    fun observeForPatient(patientId: String): Flow<List<EncounterEntity>>

    /** The most recent still-open admission for a patient, if any. */
    @Query(
        """
        SELECT * FROM encounters
        WHERE patientId = :patientId AND dischargeDatetime IS NULL
        ORDER BY admissionDatetime DESC
        LIMIT 1
        """
    )
    suspend fun activeForPatient(patientId: String): EncounterEntity?

    @Query("SELECT * FROM encounters WHERE unit = :unit ORDER BY admissionDatetime DESC")
    fun observeByUnit(unit: ServiceUnit): Flow<List<EncounterEntity>>

    @Query("DELETE FROM encounters WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM encounters WHERE isSynced = 0")
    suspend fun unsynced(): List<EncounterEntity>

    @Query("UPDATE encounters SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
