package np.opd.assistant.data.local

import np.opd.assistant.data.local.entities.EncounterType
import np.opd.assistant.data.local.entities.PatientStatus
import np.opd.assistant.data.local.entities.WardUnit
import np.opd.engine.CaseState
import np.opd.engine.Demographics
import np.opd.engine.Gender
import np.opd.engine.J
import np.opd.engine.toJ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMigrationTest {

    @Test
    fun migratesRealConsultationIntoOpdEncounter() {
        val case = CaseState(
            tick = 5,
            demographics = Demographics(age = 45, gender = Gender.FEMALE)
        )
        val json = J.obj("mode" to "LIVE", "case" to case.toJ()).toJson()

        val migrated = SessionMigration.fromLegacyJson(json, nowMs = 1000L)

        assertNotNull(migrated)
        val patient = migrated!!.patient
        val encounter = migrated.encounter
        assertEquals(45, patient.age)
        assertEquals("FEMALE", patient.gender)
        assertEquals(PatientStatus.DISCHARGED, patient.status)
        assertEquals(WardUnit.DISCHARGED, patient.currentWard)
        assertEquals(case.sessionId, patient.id)
        assertEquals(EncounterType.OPD, encounter.encounterType)
        assertEquals(patient.id, encounter.patientId)
        assertEquals(1000L, encounter.admissionDatetime)
        assertEquals(1000L, encounter.dischargeDatetime)
    }

    @Test
    fun emptyTickIsNotMigrated() {
        val case = CaseState(tick = 0)
        val json = J.obj("mode" to "LIVE", "case" to case.toJ()).toJson()
        assertNull(SessionMigration.fromLegacyJson(json))
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(SessionMigration.fromLegacyJson("not json at all"))
    }

    @Test
    fun missingCaseObjectReturnsNull() {
        assertNull(SessionMigration.fromLegacyJson("""{"mode":"LIVE"}"""))
    }
}
