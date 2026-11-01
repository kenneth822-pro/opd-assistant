package np.opd.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WardCensusTest {

    private fun patient(
        name: String,
        ward: String = "Ward 1",
        diagnosis: String = "",
        pendingLabs: List<String> = emptyList(),
        tasks: List<CensusTask> = emptyList(),
        consults: List<CensusConsult> = emptyList()
    ) = CensusPatient(
        patientId = name,
        name = name,
        age = 50,
        sex = "male",
        ward = ward,
        bed = "3",
        unit = "Unit 1",
        workingDiagnosis = diagnosis,
        pendingLabs = pendingLabs,
        openTasks = tasks,
        outstandingConsults = consults
    )

    @Test
    fun emptyCensusSnapshotIsExplicit() {
        val s = WardCensus().snapshot()
        assertTrue(s.contains("empty"))
    }

    @Test
    fun snapshotListsPatientsOneBlockEach() {
        val census = WardCensus(patients = listOf(patient("Asha", diagnosis = "Appendicitis"), patient("Bikash")))
        val s = census.snapshot()
        assertTrue(s.contains("WARD CENSUS (2 active patients)"))
        assertTrue(s.contains("Asha"))
        assertTrue(s.contains("diagnosis: Appendicitis"))
        assertTrue(s.contains("Bikash"))
    }

    @Test
    fun snapshotIncludesTasksLabsConsults() {
        val p = patient(
            "Bikash",
            pendingLabs = listOf("Amylase"),
            tasks = listOf(CensusTask("Send stat ABG", "LAB", "STAT")),
            consults = listOf(CensusConsult("Cardiology", "pre-op risk", "URGENT"))
        )
        val s = WardCensus(patients = listOf(p)).snapshot()
        assertTrue(s.contains("pending labs: Amylase"))
        assertTrue(s.contains("STAT:Send stat ABG"))
        assertTrue(s.contains("URGENT Cardiology"))
    }

    @Test
    fun sbarHasFourPartBlocksPerPatient() {
        val census = WardCensus(patients = listOf(patient("Asha", diagnosis = "CAP")))
        val sbar = WardHandover.sbar(census)
        assertTrue(sbar.contains("SBAR WARD HANDOVER"))
        assertTrue(sbar.contains("S: "))
        assertTrue(sbar.contains("B: "))
        assertTrue(sbar.contains("A: "))
        assertTrue(sbar.contains("R: "))
        assertTrue(sbar.contains("Asha"))
    }

    @Test
    fun sbarListsCriticalPatientsFirst() {
        val census = WardCensus(patients = listOf(patient("WardGuy", ward = "Ward 1"), patient("IcuGuy", ward = "ICU")))
        val sbar = WardHandover.sbar(census)
        assertTrue(sbar.contains("CRITICAL (1)"))
        assertTrue(sbar.indexOf("IcuGuy") < sbar.indexOf("--- Patient"))
    }

    @Test
    fun sbarEmptyCensusIsGraceful() {
        val sbar = WardHandover.sbar(WardCensus())
        assertTrue(sbar.contains("No active inpatients"))
    }

    @Test
    fun sbarRecommendationMentionsOpenTasks() {
        val p = patient("Asha", tasks = listOf(CensusTask("Recheck vitals at 6 PM", "VITALS", "ROUTINE")))
        val sbar = WardHandover.sbar(WardCensus(patients = listOf(p)))
        assertTrue(sbar.contains("Recheck vitals at 6 PM"))
    }

    @Test
    fun promptsDoNotAskForInventedData() {
        val ask = WardPrompts.askWardSystemPrompt()
        val handover = WardPrompts.handoverSystemPrompt()
        assertTrue(ask.contains("ONLY the data"))
        assertTrue(ask.contains("Do not invent"))
        assertTrue(handover.contains("Do not invent"))
        assertFalse(handover.contains("markdown table"))
    }
}
