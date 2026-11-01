package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.MediaCategory
import np.opd.assistant.data.local.entities.MediaEntity

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(media: MediaEntity)

    @Update
    suspend fun update(media: MediaEntity)

    @Query("SELECT * FROM patient_media WHERE id = :id")
    suspend fun byId(id: String): MediaEntity?

    @Query("SELECT * FROM patient_media WHERE patientId = :patientId ORDER BY captureDatetime DESC")
    fun observeForPatient(patientId: String): Flow<List<MediaEntity>>

    /** Chronological series (oldest first) — used by the wound-healing comparison slider. */
    @Query("SELECT * FROM patient_media WHERE patientId = :patientId AND category = :category ORDER BY captureDatetime ASC")
    fun observeSeries(patientId: String, category: MediaCategory): Flow<List<MediaEntity>>

    @Query("SELECT * FROM patient_media WHERE patientId = :patientId AND category = :category ORDER BY captureDatetime ASC")
    suspend fun series(patientId: String, category: MediaCategory): List<MediaEntity>

    @Query("DELETE FROM patient_media WHERE id = :id")
    suspend fun delete(id: String)
}
