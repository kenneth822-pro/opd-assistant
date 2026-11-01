package np.opd.assistant.data

import kotlinx.coroutines.flow.Flow
import np.opd.assistant.data.local.ClinicalDatabase
import np.opd.assistant.data.local.entities.CardexItemEntity
import np.opd.assistant.data.local.entities.ConsultEntity
import np.opd.assistant.data.local.entities.DischargeEntity
import np.opd.assistant.data.local.entities.EncounterEntity
import np.opd.assistant.data.local.entities.EncounterType
import np.opd.assistant.data.local.entities.MediaEntity
import np.opd.assistant.data.local.entities.PatientEntity
import np.opd.assistant.data.local.entities.PatientStatus
import np.opd.assistant.data.local.entities.ServiceUnit
import np.opd.assistant.data.local.entities.TaskEntity
import np.opd.assistant.data.local.entities.WardUnit
import java.util.UUID

/**
 * Thin façade over the Room DAOs. View models depend on this instead of the database directly so
 * that multi-table operations (admit a patient, discharge, close a task) have one home and can be
 * unit-reasoned about independently of Android.
 */
class WardRepository(private val db: ClinicalDatabase) {

    val patientDao get() = db.patientDao()
    val encounterDao get() = db.encounterDao()
    val cardexDao get() = db.cardexDao()
    val taskDao get() = db.taskDao()
    val mediaDao get() = db.mediaDao()
    val consultDao get() = db.consultDao()
    val dischargeDao get() = db.dischargeDao()
    val fluidIoDao get() = db.fluidIoDao()
    val vitalsLogDao get() = db.vitalsLogDao()
    val otScheduleDao get() = db.otScheduleDao()

    // ---- reads ----

    fun census(): Flow<List<PatientEntity>> = patientDao.observeCensus(PatientStatus.ACTIVE)
    fun activeCount(): Flow<Int> = patientDao.observeActiveCount(PatientStatus.ACTIVE)
    fun criticalCount(): Flow<Int> = patientDao.observeCriticalCount(PatientStatus.ACTIVE)

    fun observePatient(id: String): Flow<PatientEntity?> = patientDao.observeById(id)
    fun observeEncounters(patientId: String): Flow<List<EncounterEntity>> = encounterDao.observeForPatient(patientId)
    fun observeCardex(encounterId: String): Flow<List<CardexItemEntity>> = cardexDao.observeForEncounter(encounterId)
    fun observeTasks(encounterId: String): Flow<List<TaskEntity>> = taskDao.observeForEncounter(encounterId)
    fun observeMedia(patientId: String): Flow<List<MediaEntity>> = mediaDao.observeForPatient(patientId)
    fun observeConsults(encounterId: String): Flow<List<ConsultEntity>> = consultDao.observeForEncounter(encounterId)
    fun observeOpenTasks(): Flow<List<TaskEntity>> = taskDao.observeAllOpen()
    fun observeOutstandingConsults(): Flow<List<ConsultEntity>> = consultDao.observeOutstanding()

    // ---- admission / census writes ----

    /**
     * Admits a new patient into a bed and opens an encounter in one transaction-like sequence.
     * Returns the new patient id.
     */
    suspend fun admit(
        name: String,
        age: Int?,
        gender: String,
        ward: WardUnit,
        bedNumber: String,
        unit: ServiceUnit,
        consultantName: String,
        mrn: String? = null,
        encounterType: EncounterType = EncounterType.INPATIENT,
        now: Long = System.currentTimeMillis()
    ): String {
        val patientId = UUID.randomUUID().toString()
        patientDao.upsert(
            PatientEntity(
                id = patientId,
                mrn = mrn,
                name = name,
                age = age,
                gender = gender,
                currentWard = ward,
                bedNumber = bedNumber,
                status = PatientStatus.ACTIVE,
                createdAt = now,
                updatedAt = now
            )
        )
        encounterDao.upsert(
            EncounterEntity(
                id = UUID.randomUUID().toString(),
                patientId = patientId,
                encounterType = encounterType,
                admissionDatetime = now,
                unit = unit,
                consultantName = consultantName
            )
        )
        return patientId
    }

    suspend fun updatePatient(patient: PatientEntity) =
        patientDao.update(patient.copy(updatedAt = System.currentTimeMillis()))

    /** Moves a patient to another ward/bed (transfer between units). */
    suspend fun transfer(patientId: String, ward: WardUnit, bedNumber: String) {
        val patient = patientDao.byId(patientId) ?: return
        patientDao.update(patient.copy(currentWard = ward, bedNumber = bedNumber, updatedAt = System.currentTimeMillis()))
    }

    /** Discharges a patient: closes the open encounter and marks the census record discharged. */
    suspend fun discharge(patientId: String, finalDiagnosis: String? = null, now: Long = System.currentTimeMillis()) {
        val patient = patientDao.byId(patientId) ?: return
        patientDao.update(
            patient.copy(
                status = PatientStatus.DISCHARGED,
                currentWard = WardUnit.DISCHARGED,
                updatedAt = now
            )
        )
        encounterDao.activeForPatient(patientId)?.let { enc ->
            encounterDao.update(enc.copy(dischargeDatetime = now, finalDiagnosis = finalDiagnosis ?: enc.finalDiagnosis))
        }
    }

    // ---- cardex / tasks / consults (used from Phase 3 onward) ----

    suspend fun upsertCardex(item: CardexItemEntity) = cardexDao.upsert(item)
    suspend fun stopCardex(id: String) = cardexDao.stop(id, System.currentTimeMillis())

    suspend fun upsertTask(task: TaskEntity) = taskDao.upsert(task)
    suspend fun completeTask(id: String) = taskDao.complete(id, System.currentTimeMillis())

    suspend fun upsertConsult(consult: ConsultEntity) = consultDao.upsert(consult)

    // ---- discharges ----

    fun observeDischarges(patientId: String): Flow<List<DischargeEntity>> = dischargeDao.observeForPatient(patientId)
    suspend fun latestDischarge(encounterId: String): DischargeEntity? = dischargeDao.latestForEncounter(encounterId)
    suspend fun upsertDischarge(discharge: DischargeEntity) = dischargeDao.upsert(discharge)
}
