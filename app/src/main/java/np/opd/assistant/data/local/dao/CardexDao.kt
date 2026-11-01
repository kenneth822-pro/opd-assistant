package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.CardexItemEntity

@Dao
interface CardexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CardexItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CardexItemEntity>)

    @Update
    suspend fun update(item: CardexItemEntity)

    @Query("SELECT * FROM cardex_items WHERE id = :id")
    suspend fun byId(id: String): CardexItemEntity?

    /** All lines (active and stopped) for an encounter, newest first, for the treatment sheet. */
    @Query("SELECT * FROM cardex_items WHERE encounterId = :encounterId ORDER BY createdAt DESC")
    fun observeForEncounter(encounterId: String): Flow<List<CardexItemEntity>>

    @Query("SELECT * FROM cardex_items WHERE patientId = :patientId AND isActive = 1 ORDER BY createdAt DESC")
    fun observeActiveForPatient(patientId: String): Flow<List<CardexItemEntity>>

    @Query("SELECT * FROM cardex_items WHERE patientId = :patientId AND isActive = 1")
    suspend fun activeForPatient(patientId: String): List<CardexItemEntity>

    /** Stop a line without deleting it, preserving the treatment history. */
    @Query("UPDATE cardex_items SET isActive = 0, stopDate = :stopDate WHERE id = :id")
    suspend fun stop(id: String, stopDate: Long)

    @Query("DELETE FROM cardex_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM cardex_items WHERE isSynced = 0")
    suspend fun unsynced(): List<CardexItemEntity>

    @Query("UPDATE cardex_items SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
