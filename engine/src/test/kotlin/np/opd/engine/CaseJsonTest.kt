package np.opd.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

class CaseJsonTest {

    private lateinit var c: Catalogues

    @Before
    fun setUp() { c = Catalogues.loadBundled() }

    private fun richCase(): CaseState {
        var s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(58, Gender.FEMALE), c)
        s = Reducer.handle(s, DoctorAction.ConfirmPregnancy(Pregnancy.NOT_PREGNANT), c)
        s = Reducer.reduce(
            s,
            LiveDelta(
                transcript = listOf(
                    TranscriptLine("doctor", "के भयो? \"quoted\" and \\ backslash", 0),
                    TranscriptLine("patient", "ग्यास्ट्रिक छ, पेट पोल्छ।\nकालो दिसा भयो 😟", 1500)
                ),
                age = 40, // conflict with the doctor-set age
                problems = listOf(
                    ProblemInput("COND_APD", symptoms = listOf(SymptomInput("epigastric burning", "2 weeks", "patient", "पेट पोल्छ")), negatives = listOf(NegativeInput("vomiting")), exploredQuestionIds = listOf("q_apd_duration")),
                    ProblemInput("COND_MADE_UP", freeLabel = "Ear pain"),
                    ProblemInput("COND_HTN")
                ),
                vitals = Vitals(bpSystolic = 152, bpDiastolic = 96, pulse = 88, rr = 18, spo2 = 97, tempF = 98.6, bloodGlucose = 140.5),
                allergies = listOf(AllergyInput("amoxicillin", "rash"), AllergyInput("some injection", "swelling"), AllergyInput("amoxicillin", "nausea", isIntolerance = true)),
                currentMeds = listOf(MedInput("Telma 40"), MedInput("Aquazide"), MedInput("paracetamol", 2000.0), MedInput("herbal tonic")),
                comorbidities = listOf("COMORB_T2DM", "COMORB_HTN"),
                flagsIdentified = listOf(FlagInput("rf_gi_melena", false, "कालो दिसा")),
                flagsExcluded = listOf("rf_gi_dysphagia"),
                dictatedItems = listOf(DictatedInput("Tab Brufen 400", 1.0, "BD", 5), DictatedInput("Mystery pill")),
                examFindings = listOf("epigastric tenderness"),
                nextQuestions = listOf(AiQuestion("कालो दिसा भएको छ?", "Any black stool?", "rule out GI bleed")),
                differential = listOf(AiDiagnosis("Peptic ulcer", AiLikelihood.LIKELY, "burning", "COND_APD"), AiDiagnosis("Upper GI bleed", AiLikelihood.MUST_NOT_MISS))
            ),
            c
        )
        s = Reducer.handle(s, DoctorAction.ClearFlag("rf_gi_hematemesis", "asked: absent"), c)
        s = Reducer.handle(s, DoctorAction.AcceptRegimen("APD-01"), c)
        s = Reducer.handle(s, DoctorAction.DismissRegimen("HTN-01"), c)
        s = Reducer.handle(s, DoctorAction.MarkQuestionAsked(s.problems.first().problemId, "q_apd_food"), c)
        return s
    }

    private fun saved(s: CaseState) = SavedSession(
        case = s, mode = "LIVE", elapsedSec = 754, heard = setOf("age"), doneTips = setOf("allergy", "ai:Any black stool?"),
        soap = Prompts.SoapNote("cc", "hist \"q\"", "exam", "assess", "plan", "fu", "advice", localDraft = true), savedAtMs = 1_760_000_000_000L
    )

    @Test
    fun aRichCaseSurvivesARoundTripExactly() {
        val before = saved(richCase())
        val after = CaseJson.decode(CaseJson.encode(before), c)
        assertEquals(before.case, after.case)
        assertEquals(before.copy(case = after.case), after)
    }

    @Test
    fun theTextIsStableWhenSavedAgain() {
        val once = CaseJson.encode(saved(richCase()))
        val twice = CaseJson.encode(CaseJson.decode(once, c))
        assertEquals(once, twice)
    }

    @Test
    fun emptyCaseRoundTrips() {
        val before = SavedSession(CaseState())
        assertEquals(before.case, CaseJson.decode(CaseJson.encode(before), c).case)
    }

    @Test
    fun restoredCaseIsJudgedByTheCurrentRulesNotTheSavedOnes() {
        // Tamper with derived fields in the file: they must be ignored and recomputed.
        val text = CaseJson.encode(saved(richCase()))
        val tampered = text.replace("\"plan_cue\":false", "\"plan_cue\":true")
        val a = CaseJson.decode(text, c).case
        val b = CaseJson.decode(tampered, c).case
        // plan_cue is a fact the doctor/AI set, so it is honoured; derived suggestions follow from it.
        assertTrue(b.problems.all { it.planCue })
        assertFalse(a.problems.all { it.planCue })
        // and the safety findings are recomputed, never read from the file
        assertTrue(a.dictated.first().findings.isNotEmpty())
        assertFalse(text.contains("\"findings\""))   // derived safety findings are never stored (exam_findings is a fact)
        assertFalse(text.contains("suggested_regimen"))
    }

    @Test
    fun restoredStateKeepsWorkingWithNewInput() {
        val restored = CaseJson.decode(CaseJson.encode(saved(richCase())), c).case
        val next = Reducer.reduce(restored, LiveDelta(transcript = listOf(TranscriptLine("patient", "more", 99_000))), c)
        assertEquals(restored.transcript.size + 1, next.transcript.size)
        // ids continue from the saved counter, so new items never reuse an id
        val withNew = Reducer.reduce(next, LiveDelta(problems = listOf(ProblemInput("COND_UTI"))), c)
        val ids = withNew.problems.map { it.problemId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun unreadableFilesAreRejectedNotHalfLoaded() {
        val good = CaseJson.encode(saved(richCase()))
        for (bad in listOf("", "not json", "[]", "{}", good.take(good.length / 2), good.replace("\"version\":1", "\"version\":99"))) {
            var threw = false
            try { CaseJson.decode(bad, c) } catch (e: JsonException) { threw = true }
            assertTrue("should reject: ${bad.take(40)}", threw)
        }
    }

    @Test
    fun unknownEnumValuesFallBackSafely() {
        val text = CaseJson.encode(saved(richCase())).replace("\"FEMALE\"", "\"ALIEN\"").replace("\"NOT_PREGNANT\"", "\"MAYBE\"")
        val s = CaseJson.decode(text, c).case
        assertEquals(Gender.UNKNOWN, s.demographics.gender)
        // unknown pregnancy is never read as "not pregnant"
        assertEquals(Pregnancy.UNKNOWN, s.demographics.pregnancy)
    }

    @Test
    fun olderFilesWithMissingSectionsStillLoad() {
        val minimal = "{\"version\":1,\"case\":{\"session_id\":\"abc\",\"transcript\":[{\"speaker\":\"patient\",\"text\":\"hi\"}]}}"
        val s = CaseJson.decode(minimal, c)
        assertEquals("abc", s.case.sessionId)
        assertEquals(1, s.case.transcript.size)
        assertNull(s.soap)
        assertEquals("LIVE", s.mode)
    }

    @Test
    fun randomCasesRoundTrip() {
        val rnd = Random(77)
        val phrases = listOf("ग्यास्ट्रिक छ", "खोकी लाग्यो", "छातीमा दुख्छ", "प्रेसर छ", "कालो दिसा", "quote \" mark", "line\nbreak", "tab\there")
        val conds = c.conditions.keys.toList() + "COND_OTHER"
        val meds = listOf("Metformin", "Telma 40", "Brufen", "Pantop", "Warfarin", "herbal")
        repeat(200) {
            var s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(if (rnd.nextBoolean()) 1 + rnd.nextInt(90) else null, listOf(Gender.MALE, Gender.FEMALE, null)[rnd.nextInt(3)]), c)
            repeat(1 + rnd.nextInt(4)) { i ->
                s = Reducer.reduce(
                    s,
                    LiveDelta(
                        transcript = List(rnd.nextInt(3)) { j -> TranscriptLine(listOf("doctor", "patient", "attendant")[rnd.nextInt(3)], phrases[rnd.nextInt(phrases.size)], (i * 1000 + j).toLong()) },
                        problems = List(rnd.nextInt(3)) { ProblemInput(conds[rnd.nextInt(conds.size)], freeLabel = "x" + rnd.nextInt(9)) },
                        vitals = if (rnd.nextBoolean()) Vitals(rr = 10 + rnd.nextInt(30), spo2 = 80 + rnd.nextInt(20), tempF = 97.0 + rnd.nextInt(60) / 10.0) else null,
                        currentMeds = List(rnd.nextInt(2)) { MedInput(meds[rnd.nextInt(meds.size)], if (rnd.nextBoolean()) 500.0 * rnd.nextInt(5) else null) },
                        flagsIdentified = List(rnd.nextInt(2)) { FlagInput(c.redFlags.keys.toList()[rnd.nextInt(c.redFlags.size)], rnd.nextBoolean()) },
                        planCue = rnd.nextInt(3) == 0
                    ),
                    c
                )
                if (rnd.nextInt(3) == 0) s.problems.firstOrNull()?.suggestedRegimenId?.let { s = Reducer.handle(s, DoctorAction.AcceptRegimen(it), c) }
            }
            val back = CaseJson.decode(CaseJson.encode(SavedSession(s)), c).case
            assertEquals(s, back)
        }
    }
}
