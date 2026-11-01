package np.opd.assistant.ward

import np.opd.assistant.data.local.entities.PatientEntity
import np.opd.assistant.data.local.entities.WardUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WardModelTest {

    private fun patient(id: String, ward: WardUnit, bed: String) = PatientEntity(
        id = id, name = "P $id", currentWard = ward, bedNumber = bed
    )

    @Test
    fun allTabHasNoWardFilter() {
        assertNull(WardTab.ALL.ward)
    }

    @Test
    fun tabFiltersMatchWard() {
        val patients = listOf(
            patient("1", WardUnit.ICU, "1"),
            patient("2", WardUnit.WARD_1, "2"),
            patient("3", WardUnit.ICU, "3")
        )
        val icu = patients.filter { it.currentWard == WardTab.ICU.ward }
        assertEquals(2, icu.size)
    }

    @Test
    fun presetsHaveItemsAndLabels() {
        AdmissionChecklistPreset.entries.forEach { p ->
            assertTrue("${p.name} has a label", p.label.isNotBlank())
            assertTrue("${p.name} has items", p.items.isNotEmpty())
        }
    }

    @Test
    fun eachPresetMentionsIdentificationBand() {
        AdmissionChecklistPreset.entries.forEach { p ->
            assertTrue(
                "${p.name} should include identification band",
                p.items.any { it.contains("Identification band", ignoreCase = true) }
            )
        }
    }

    @Test
    fun criticalOnlyForIcuAndSemiIcu() {
        assertTrue(WardUnit.ICU.isCritical)
        assertTrue(WardUnit.SEMI_ICU.isCritical)
        assertTrue(!WardUnit.WARD_1.isCritical)
        assertTrue(!WardUnit.BUFFER.isCritical)
        assertTrue(!WardUnit.DISCHARGED.isCritical)
    }
}
