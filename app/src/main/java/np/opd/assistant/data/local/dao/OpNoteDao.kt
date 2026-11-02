package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.OperativeNoteEntity

@Dao
interface OpNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: OperativeNoteEntity)

    @Query("SELECT * FROM operative_notes WHERE id = :id")
    suspend fun byId(id: String): OperativeNoteEntity?

    @Query("SELECT * FROM operative_notes WHERE patientId = :patientId AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun getNotesForPatient(patientId: String): Flow<List<OperativeNoteEntity>>

    @Query("SELECT * FROM operative_notes WHERE encounterId = :encounterId AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeForEncounter(encounterId: String): Flow<List<OperativeNoteEntity>>

    @Query("UPDATE operative_notes SET deletedAt = :now, isSynced = 0, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM operative_notes WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT * FROM operative_notes WHERE isSynced = 0")
    suspend fun getUnsyncedNotes(): List<OperativeNoteEntity>

    @Query("UPDATE operative_notes SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
