package np.opd.assistant.data.local

import np.opd.assistant.data.local.entities.EncounterEntity
import np.opd.assistant.data.local.entities.EncounterType
import np.opd.assistant.data.local.entities.PatientEntity
import np.opd.assistant.data.local.entities.PatientStatus
import np.opd.assistant.data.local.entities.ServiceUnit
import np.opd.assistant.data.local.entities.WardUnit
import np.opd.engine.CaseState
import np.opd.engine.J
import java.util.UUID

/**
 * Bridge from the legacy single-session JSON snapshot (`last_session.json`) into the relational
 * store. The old file held exactly one in-progress OPD consultation, so the migration produces one
 * OPD [EncounterEntity] under one [PatientEntity].
 *
 * Pure parsing only — no file or DB access — so it is unit-testable on the JVM. The caller reads
 * the JSON text and writes the result through the DAOs.
 */
object SessionMigration {

    /** Result of parsing a legacy snapshot; null pieces mean there was nothing usable to migrate. */
    data class Migrated(
        val patient: PatientEntity,
        val encounter: EncounterEntity
    )

    /**
     * Parses a legacy snapshot document and maps it to a patient + OPD encounter. Returns null if
     * the document is empty, malformed, or holds no real consultation (tick <= 0), mirroring the
     * guard in the old `loadSessionSnapshot`.
     */
    fun fromLegacyJson(json: String, nowMs: Long = System.currentTimeMillis()): Migrated? {
        val root = J.parse(json) as? J.Obj ?: return null
        val caseObj = root.obj("case") ?: return null
        val case = CaseState.fromJ(caseObj)
        if (case.tick <= 0) return null

        val demographics = case.demographics
        val patientId = case.sessionId.ifBlank { UUID.randomUUID().toString() }

        val patient = PatientEntity(
            id = patientId,
            mrn = null,
            name = legacyLabel(case),
            age = demographics.age,
            gender = demographics.gender.name,
            // A legacy OPD session is not a ward admission; keep it out of the census board.
            currentWard = WardUnit.DISCHARGED,
            bedNumber = "",
            status = PatientStatus.DISCHARGED,
            createdAt = nowMs,
            updatedAt = nowMs
        )

        val encounter = EncounterEntity(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            encounterType = EncounterType.OPD,
            admissionDatetime = nowMs,
            dischargeDatetime = nowMs,
            unit = ServiceUnit.MEDICINE,
            consultantName = case.review.doctorIdentity ?: "",
            finalDiagnosis = case.problems.firstOrNull()?.label
        )

        return Migrated(patient, encounter)
    }

    private fun legacyLabel(case: CaseState): String {
        val condition = case.problems.firstOrNull()?.label?.takeIf { it.isNotBlank() }
        return condition?.let { "Legacy OPD — $it" } ?: "Legacy OPD consultation"
    }
}
