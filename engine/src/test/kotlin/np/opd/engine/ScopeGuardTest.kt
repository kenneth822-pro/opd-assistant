package np.opd.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The catalogue is adult-only (README). The engine must enforce that, not just document it. */
class ScopeGuardTest {

    private lateinit var c: Catalogues

    @Before
    fun setUp() { c = Catalogues.loadBundled() }

    private fun child(age: Int, code: String): CaseState {
        var s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(age, Gender.MALE), c)
        s = Reducer.reduce(s, LiveDelta(problems = listOf(ProblemInput(code)), planCue = true), c)
        return s
    }

    @Test
    fun noAdultRegimenIsSuggestedToAYoungChild() {
        for (code in c.conditions.values.filter { it.linkedRegimens.isNotEmpty() }.map { it.code }) {
            val s = child(5, code)
            val p = s.problems.first { it.conditionCode == code }
            assertNull("$code: adult regimen ${p.suggestedRegimenId} suggested to a 5-year-old", p.suggestedRegimenId)
        }
    }

    @Test
    fun childGetsAnExplicitScopeAlertNotSilence() {
        val s = child(5, "COND_LOW_BACK_PAIN")
        assertTrue(s.scopeAlerts.single().contains("under 18", ignoreCase = true))
        assertTrue(s.problems.single().noSafeRegimen)
    }

    @Test
    fun adultsAreUnaffected() {
        val s = child(30, "COND_LOW_BACK_PAIN")
        assertEquals("LBP-01", s.problems.single().suggestedRegimenId)
        assertTrue(s.scopeAlerts.isEmpty())
    }

    @Test
    fun unknownAgeIsNotBlockedButIsCautioned() {
        val s = Reducer.reduce(CaseState(), LiveDelta(problems = listOf(ProblemInput("COND_LOW_BACK_PAIN")), planCue = true), c)
        assertEquals("LBP-01", s.problems.single().suggestedRegimenId)
        assertTrue(s.problems.single().suggestionFindings.any { it.message.contains("Age not recorded") })
    }

    @Test
    fun regimensThatDeclareAnExplicitMinimumAgeStillWork() {
        // CAP-01 / UTI-02 / DYS-01 declare their own minimum ages in the catalogue; those are respected.
        val s17 = child(17, "COND_CAP")
        assertEquals("CAP-01", s17.problems.single().suggestedRegimenId)
        val s15 = child(15, "COND_CAP")
        assertNull(s15.problems.single().suggestedRegimenId)
    }

    @Test
    fun dictatedDrugForAChildGetsAPaediatricDoseCaution() {
        var s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(6, Gender.FEMALE), c)
        s = Reducer.reduce(s, LiveDelta(dictatedItems = listOf(DictatedInput("Tab Paracetamol 500", 1.0, "TDS", 3))), c)
        assertTrue(s.dictated.single().findings.any { it.message.contains("paediatric", ignoreCase = true) })
    }
}
