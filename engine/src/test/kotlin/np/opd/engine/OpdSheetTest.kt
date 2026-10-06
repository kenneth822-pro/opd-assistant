package np.opd.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpdSheetTest {

    private lateinit var c: Catalogues

    @Before
    fun setUp() { c = Catalogues.loadBundled() }

    private fun sheetFor(s: CaseState): OpdSheet {
        val h = LocalAssist.analyze(s.transcript, c)
        return OpdSheet.build(s, Differential.compute(s, h, c), c)
    }

    private fun case(): CaseState {
        var s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(45, Gender.MALE), c)
        s = Reducer.reduce(
            s,
            LiveDelta(
                transcript = listOf(
                    TranscriptLine("doctor", "के भयो?", 0),
                    TranscriptLine("patient", "ग्यास्ट्रिक छ, पेट पोल्छ, कालो दिसा पनि भयो", 1000)
                ),
                problems = listOf(ProblemInput("COND_APD", symptoms = listOf(SymptomInput("epigastric burning", "2 weeks")), negatives = listOf(NegativeInput("vomiting")))),
                vitals = Vitals(bpSystolic = 130, bpDiastolic = 85),
                allergies = listOf(AllergyInput("amoxicillin", "rash")),
                currentMeds = listOf(MedInput("Brufen 400")),
                comorbidities = listOf("COMORB_T2DM"),
                flagsIdentified = listOf(FlagInput("rf_gi_melena", false, "कालो दिसा")),
                examFindings = listOf("epigastric tenderness")
            ),
            c
        )
        return s
    }

    @Test
    fun sheetReflectsTheWholeCaseSoFar() {
        val sh = sheetFor(case())
        assertEquals("45 y · Male", sh.patient)
        assertEquals("BP 130/85", sh.vitals)
        assertTrue(sh.allergies.single().contains("Penicillin"))
        assertEquals(listOf("Diabetes"), sh.knownConditions)
        assertEquals(listOf("Brufen 400"), sh.currentMeds)
        assertEquals("epigastric burning (2 weeks)", sh.complaints.single().symptoms.single())
        assertEquals(listOf("vomiting"), sh.complaints.single().denies)
        assertEquals(listOf("epigastric tenderness"), sh.exam)
        assertTrue(sh.impression.first().startsWith("Likely: Acid peptic disease"))
        assertTrue(sh.danger.single().startsWith("Upper GI bleed"))
        assertTrue(sh.redFlags.first().let { it.label.contains("Black tarry") && it.status == "possible" })
        assertTrue(sh.toAsk.any { it.startsWith("Ask:") })
        assertTrue(sh.investigations.contains("Complete blood count"))
        assertTrue(sh.treatment.any { it.contains("Likely for Acid peptic disease") || it.contains("Suggested for Acid peptic disease") })
    }

    @Test
    fun emptyCaseIsSafeAndSaysSo() {
        val sh = sheetFor(CaseState())
        assertTrue(sh.complaints.isEmpty() && sh.impression.isEmpty() && sh.danger.isEmpty())
        val text = sh.toText(c)
        assertTrue(text.contains("nothing captured yet"))
        assertTrue(text.contains("not yet clear"))
        assertTrue(text.contains("none recorded"))
    }

    @Test
    fun beforeAnyProblemTheSheetQuotesWhatThePatientSaid() {
        val s = Reducer.reduce(CaseState(), LiveDelta(transcript = listOf(TranscriptLine("patient", "छाती दुख्छ", 0))), c)
        assertEquals("Patient said", sheetFor(s).complaints.single().title)
    }

    @Test
    fun textIsMarkedProvisionalAndHonestAboutVerification() {
        val text = sheetFor(case()).toText(c)
        assertTrue(text.startsWith("*** PROVISIONAL OPD SHEET"))
        assertTrue(text.contains("0 of 14 conditions checked against a guideline source"))
        assertTrue(text.contains("DON'T MISS"))
        assertTrue(text.contains("Upper GI bleed"))
    }

    @Test
    fun aiSuggestionsAreLabelledUnverified() {
        val s = Reducer.reduce(case(), LiveDelta(differential = listOf(AiDiagnosis("Peptic ulcer", AiLikelihood.LIKELY, "burning, relieved by food"))), c)
        val text = sheetFor(s).toText(c)
        assertTrue(text.contains("AI SUGGESTS (unverified)"))
        assertTrue(text.contains("Peptic ulcer (likely) - burning, relieved by food"))
    }

    @Test
    fun childAndPregnancyAlertsAppear() {
        var s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(8, Gender.MALE), c)
        assertTrue(sheetFor(s).alerts.any { it.startsWith("Under 18") })
        s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(25, Gender.FEMALE), c)
        assertTrue(sheetFor(s).alerts.any { it.contains("Pregnancy not confirmed") })
        assertTrue(sheetFor(s).patient.contains("pregnancy not confirmed"))
    }

    @Test
    fun redFlagsAreOrderedMostUrgentFirstAndRedirectsAreSkipped() {
        var s = Reducer.reduce(
            CaseState(),
            LiveDelta(
                problems = listOf(ProblemInput("COND_AGE")),
                flagsIdentified = listOf(FlagInput("rf_age_severe_dehydration", true), FlagInput("rf_gi_blood_in_stool", true))
            ),
            c
        )
        s = Reducer.handle(s, DoctorAction.ClearFlag("rf_age_unable_to_drink", "asked: absent"), c)
        val flags = sheetFor(s).redFlags
        assertEquals("PRESENT", flags.first().status)
        assertEquals("absent", flags.dropWhile { it.status != "absent" }.first().status)
        assertTrue(flags.none { it.label.contains("Blood or mucus") })
    }

    @Test
    fun dictatedBlockedDrugIsMarked() {
        var s = Reducer.reduce(CaseState(), LiveDelta(currentMeds = listOf(MedInput("Warfarin 5 mg"))), c)
        s = Reducer.handle(s, DoctorAction.SetDemographics(50, Gender.MALE), c)
        s = Reducer.reduce(s, LiveDelta(dictatedItems = listOf(DictatedInput("Tab Brufen 400", 1.0, "BD", 5))), c)
        assertTrue(sheetFor(s).treatment.single { it.startsWith("Dictated") }.contains("BLOCKED"))
    }

    @Test
    fun sheetIsAPureViewOfTheState() {
        val s = case()
        val before = s
        sheetFor(s)
        assertEquals(before, s)
        assertFalse(sheetFor(s).toText(c).isBlank())
    }
}
