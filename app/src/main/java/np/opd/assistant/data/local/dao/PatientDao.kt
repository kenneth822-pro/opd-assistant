package np.opd.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.entities.PatientEntity
import np.opd.assistant.data.local.entities.PatientStatus
import np.opd.assistant.data.local.entities.WardUnit

@Dao
interface PatientDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(patient: PatientEntity)

    @Update
    suspend fun update(patient: PatientEntity)

    @Query("SELECT * FROM patients WHERE id = :id")
    suspend fun byId(id: String): PatientEntity?

    @Query("SELECT * FROM patients WHERE id = :id")
    fun observeById(id: String): Flow<PatientEntity?>

    /** Whole active census, sickest ward first, then by bed label. */
    @Query(
        """
        SELECT * FROM patients
        WHERE status = :status
        ORDER BY
            CASE currentWard
                WHEN 'ICU' THEN 0
                WHEN 'SEMI_ICU' THEN 1
                WHEN 'BUFFER' THEN 2
                WHEN 'WARD_1' THEN 3
                WHEN 'WARD_2' THEN 4
                ELSE 5
            END,
            bedNumber
        """
    )
    fun observeCensus(status: PatientStatus = PatientStatus.ACTIVE): Flow<List<PatientEntity>>

    @Query("SELECT * FROM patients WHERE currentWard = :ward AND status = :status ORDER BY bedNumber")
    fun observeByWard(ward: WardUnit, status: PatientStatus = PatientStatus.ACTIVE): Flow<List<PatientEntity>>

    @Query("SELECT COUNT(*) FROM patients WHERE status = :status")
    fun observeActiveCount(status: PatientStatus = PatientStatus.ACTIVE): Flow<Int>

    @Query("SELECT COUNT(*) FROM patients WHERE currentWard IN ('ICU', 'SEMI_ICU') AND status = :status")
    fun observeCriticalCount(status: PatientStatus = PatientStatus.ACTIVE): Flow<Int>

    @Query(
        """
        SELECT * FROM patients
        WHERE status = 'ACTIVE' AND (
            name LIKE '%' || :query || '%' OR (mrn IS NOT NULL AND mrn LIKE '%' || :query || '%')
        )
        ORDER BY bedNumber
        """
    )
    suspend fun search(query: String): List<PatientEntity>

    @Query("DELETE FROM patients WHERE id = :id")
    suspend fun delete(id: String)
}
