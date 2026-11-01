package np.opd.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VerbalOrderTest {

    private lateinit var c: Catalogues

    @Before
    fun setUp() {
        c = Catalogues.loadBundled()
    }

    /** A synthetic ward-patient context: adult, no allergies unless given. */
    private fun context(
        age: Int? = 40,
        gender: Gender = Gender.MALE,
        allergies: Set<String> = emptySet(),
        comorbidities: Set<String> = emptySet(),
        currentMeds: List<CurrentMed> = emptyList()
    ): CaseState = CaseState(
        demographics = Demographics(age = age, gender = gender),
        allergies = allergies,
        comorbidities = comorbidities,
        currentMeds = currentMeds
    )

    private fun line(drugId: String, stated: String) = CardexLine(
        lineId = "L-$drugId", drugId = drugId, stated = stated
    )

    @Test
    fun startsNewDrugAndMatchesFormulary() {
        val order = VerbalOrder(
            medicationChanges = listOf(MedChange(ChangeAction.START, "Cefixime 200mg", doseCount = 1.0, route = "PO", frequency = "BD"))
        )
        val diff = VerbalOrders.diff(order, emptyList(), context(), c)
        assertEquals(1, diff.startItems.size)
        assertNotNull("cefixime should match the formulary", diff.startItems.first().matchedDrugId)
    }

    @Test
    fun unmatchedDrugGetsCautionNotSilence() {
        val order = VerbalOrder(medicationChanges = listOf(MedChange(ChangeAction.START, "Tab Mysterydrug")))
        val diff = VerbalOrders.diff(order, emptyList(), context(), c)
        val item = diff.startItems.first()
        assertEquals(null, item.matchedDrugId)
        assertTrue("unmatched drug must be flagged, never silent", item.findings.isNotEmpty())
        assertTrue(item.hasWarning || item.findings.any { it.severity == Severity.CAUTION })
    }

    @Test
    fun stopMatchesActiveLineByDrug() {
        val active = listOf(line("DRUG_AMOXICILLIN", "Amoxicillin 500mg"))
        // Use the actual generic name so findDrug resolves it.
        val drug = c.drugs["DRUG_AMOXICILLIN"]
        val stated = drug?.generic ?: "amoxicillin"
        val order = VerbalOrder(medicationChanges = listOf(MedChange(ChangeAction.STOP, stated)))
        val diff = VerbalOrders.diff(order, active, context(), c)
        assertEquals(1, diff.stopItems.size)
        assertEquals("L-DRUG_AMOXICILLIN", diff.stopItems.first().targetLineId)
    }

    @Test
    fun stopKeepsStatedNameWhenNoMatch() {
        val order = VerbalOrder(medicationChanges = listOf(MedChange(ChangeAction.STOP, "some unlisted drug")))
        val diff = VerbalOrders.diff(order, emptyList(), context(), c)
        assertEquals(1, diff.stopItems.size)
        assertEquals(null, diff.stopItems.first().targetLineId)
        assertTrue(diff.stopItems.first().label.isNotBlank())
    }

    @Test
    fun penicillinAllergyBlocksStartofAmoxicillin() {
        val order = VerbalOrder(medicationChanges = listOf(MedChange(ChangeAction.START, "Amoxicillin 500mg capsule")))
        val diff = VerbalOrders.diff(order, emptyList(), context(allergies = setOf("ALLERGY_PENICILLIN_CLASS")), c)
        assertTrue("amoxicillin must be blocked with penicillin allergy", diff.blocked)
    }

    @Test
    fun underEighteenIsBlockedForAdultCatalogue() {
        val order = VerbalOrder(medicationChanges = listOf(MedChange(ChangeAction.START, "Crocin")))
        val diff = VerbalOrders.diff(order, emptyList(), context(age = 12), c)
        assertTrue(diff.blocked)
    }

    @Test
    fun missingAgeBlocksProtocolMedicine() {
        val order = VerbalOrder(medicationChanges = listOf(MedChange(ChangeAction.START, "Crocin")))
        val diff = VerbalOrders.diff(order, emptyList(), context(age = null), c)
        assertTrue(diff.blocked)
    }

    @Test
    fun nonMedicationOrdersAreNotSafetyGated() {
        val order = VerbalOrder(
            investigations = listOf("serum potassium in the evening"),
            nursingOrders = listOf("intake/output chart"),
            dietOrders = listOf("clear fluids"),
            referrals = listOf("Cardiology")
        )
        val diff = VerbalOrders.diff(order, emptyList(), context(), c)
        assertEquals(4, diff.nonMedItems.size)
        assertFalse(diff.blocked)
        assertTrue(diff.items.any { it.kind == OrderKind.INVESTIGATION })
        assertTrue(diff.items.any { it.kind == OrderKind.NURSING })
        assertTrue(diff.items.any { it.kind == OrderKind.DIET })
        assertTrue(diff.items.any { it.kind == OrderKind.REFERRAL })
    }

    @Test
    fun parsesVerboseJsonWithFences() {
        val json = """
        ```json
        {"medication_changes":[{"action":"STOP","drug_stated":"Ceftriaxone"},
        {"action":"START","drug_stated":"Meropenem","dose_count":1,"route":"IV","frequency":"TDS"}],
        "investigations":["Serum potassium (evening)"],"diet_orders":["oral sips allowed"],
        "nursing_orders":[],"referrals":[],"transcript":[{"speaker":"doctor","text":"stop ceftriaxone","slice_offset_ms":0}]}
        ```
        """.trimIndent()
        val order = VerbalOrders.parse(json)
        assertEquals(2, order.medicationChanges.size)
        assertEquals(ChangeAction.STOP, order.medicationChanges[0].action)
        assertEquals(ChangeAction.START, order.medicationChanges[1].action)
        assertEquals("Meropenem", order.medicationChanges[1].stated)
        assertEquals("IV", order.medicationChanges[1].route)
        assertEquals(1, order.investigations.size)
        assertEquals(1, order.dietOrders.size)
        assertEquals(1, order.transcript.size)
    }

    @Test
    fun parsesBareJson() {
        val order = VerbalOrders.parse("""{"medication_changes":[{"action":"START","drug_stated":"Paracetamol"}]}""")
        assertEquals(1, order.medicationChanges.size)
        assertEquals(ChangeAction.START, order.medicationChanges[0].action)
    }
}
