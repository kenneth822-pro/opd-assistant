package np.opd.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

class DifferentialTest {

    private lateinit var c: Catalogues

    @Before
    fun setUp() { c = Catalogues.loadBundled() }

    private fun lines(vararg l: Pair<String, String>) = l.mapIndexed { i, (sp, t) -> TranscriptLine(sp, t, i * 1000L) }

    /** Builds a case from a spoken transcript (plus any structured facts) and the on-phone hints for it. */
    private fun heard(vararg l: Pair<String, String>, extra: LiveDelta = LiveDelta()): Pair<CaseState, LocalAssist.Hints> {
        val s = Reducer.reduce(CaseState(), extra.copy(transcript = lines(*l)), c)
        return s to LocalAssist.analyze(s.transcript, c)
    }

    private fun dx(s: CaseState, h: LocalAssist.Hints) = Differential.compute(s, h, c)
    private fun DifferentialResult.entry(code: String) = ranked.firstOrNull { it.code == code }

    /* ---------------- ranking and evidence ---------------- */

    @Test
    fun emptyCaseHasNoDiagnosis() {
        val r = dx(CaseState(), LocalAssist.Hints())
        assertTrue(r.isEmpty)
        assertNull(r.working)
    }

    @Test
    fun oneKeywordIsPossibleThreeAreLikely() {
        val (s1, h1) = heard("patient" to "gastric छ")
        val apd1 = dx(s1, h1).entry("COND_APD")!!
        assertEquals(Likelihood.POSSIBLE, apd1.likelihood)
        assertTrue(apd1.supporting.any { it.kind == EvidenceKind.HEARD && it.text.contains("gastric") })

        val (s3, h3) = heard("patient" to "ग्यास्ट्रिक छ, पेट पोल्छ, अमिलो आउँछ")
        val r3 = dx(s3, h3)
        assertEquals(Likelihood.LIKELY, r3.entry("COND_APD")!!.likelihood)
        assertEquals("COND_APD", r3.working?.code)
    }

    @Test
    fun comorbiditiesAndMedicinesAloneNeverSurfaceADiagnosis() {
        val s = Reducer.reduce(
            CaseState(),
            LiveDelta(comorbidities = listOf("COMORB_T2DM", "COMORB_HTN"), currentMeds = listOf(MedInput("Metformin"), MedInput("Telma 40"))),
            c
        )
        assertTrue("a diabetic hypertensive with no complaint must not get diagnoses suggested", dx(s, LocalAssist.Hints()).ranked.isEmpty())
    }

    @Test
    fun historyRaisesADiagnosisThatWasAlreadyHeard() {
        val plain = heard("patient" to "प्रेसर छ")
        val withHistory = heard("patient" to "प्रेसर छ", extra = LiveDelta(comorbidities = listOf("COMORB_HTN"), currentMeds = listOf(MedInput("Telma 40"))))
        val a = dx(plain.first, plain.second).entry("COND_HTN")!!
        val b = dx(withHistory.first, withHistory.second).entry("COND_HTN")!!
        assertTrue(b.score > a.score)
        assertTrue(b.supporting.any { it.kind == EvidenceKind.HISTORY })
        assertTrue(b.supporting.any { it.kind == EvidenceKind.MEDICINE })
    }

    @Test
    fun abnormalMeasurementsAloneCanSurfaceADiagnosis() {
        val s = Reducer.reduce(CaseState(), LiveDelta(vitals = Vitals(bpSystolic = 160, bpDiastolic = 100)), c)
        val htn = dx(s, LocalAssist.Hints()).entry("COND_HTN")!!
        assertEquals(Likelihood.POSSIBLE, htn.likelihood)
        assertTrue(htn.supporting.single { it.points > 0 }.kind == EvidenceKind.MEASURED)
    }

    @Test
    fun oneNormalLookingMeasurementDoesNotSurfaceAnything() {
        val s = Reducer.reduce(CaseState(), LiveDelta(vitals = Vitals(tempF = 101.0)), c)
        // fever alone is only +2 for enteric fever / pneumonia: below the measured-only threshold
        assertTrue(dx(s, LocalAssist.Hints()).ranked.isEmpty())
    }

    @Test
    fun deniedSymptomLowersADiagnosisButNeverHidesAFiledProblem() {
        val (s, h) = heard("patient" to "खोकी छैन", extra = LiveDelta(problems = listOf(ProblemInput("COND_CAP"))))
        val cap = dx(s, h).entry("COND_CAP")!!
        assertTrue(cap.onProblemList)
        assertTrue(cap.against.any { it.kind == EvidenceKind.DENIED })
        assertEquals(Differential.PROBLEM_POINTS + Differential.DENIED_POINTS, cap.score)
    }

    @Test
    fun aFiledProblemWithOnlyEvidenceAgainstIsShownAsLessLikely() {
        val (s, h) = heard(
            "patient" to "खोकी छैन", "patient" to "no cough", "patient" to "no phlegm",
            extra = LiveDelta(problems = listOf(ProblemInput("COND_CAP")))
        )
        val cap = dx(s, h).entry("COND_CAP")!!
        assertTrue(cap.score < Differential.PROBLEM_POINTS)
        assertTrue(cap.onProblemList)
    }

    @Test
    fun affirmedWinsOverDeniedForTheSameWord() {
        val (_, h) = heard("patient" to "खोकी छैन", "patient" to "तर आज खोकी लाग्यो")
        assertTrue(h.deniedConditions.none { it.code == "COND_CAP" })
        assertTrue(h.conditions.any { it.code == "COND_CAP" })
    }

    @Test
    fun ageAndSexClueAreContextNotComplaints() {
        var s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(55, Gender.FEMALE), c)
        s = Reducer.reduce(s, LiveDelta(transcript = lines("patient" to "छातीमा दुख्छ")), c)
        val cp = dx(s, LocalAssist.analyze(s.transcript, c)).entry("COND_CHEST_PAIN")!!
        assertTrue(cp.supporting.any { it.kind == EvidenceKind.PATIENT && it.text.startsWith("Age 40") })
    }

    @Test
    fun maleIsEvidenceAgainstCystitis() {
        var s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(30, Gender.MALE), c)
        s = Reducer.reduce(s, LiveDelta(problems = listOf(ProblemInput("COND_UTI"))), c)
        val uti = dx(s, LocalAssist.Hints()).entry("COND_UTI")!!
        assertTrue(uti.against.any { it.points < 0 })
    }

    @Test
    fun rankedBestFirstAndCapped() {
        val (s, h) = heard("patient" to "ग्यास्ट्रिक छ, पेट पोल्छ, अमिलो आउँछ, छाती दुख्छ, खोकी छ, प्रेसर छ, सुगर छ, पखाला भयो, टाउको दुख्छ, कम्मर दुख्छ")
        val r = dx(s, h)
        assertTrue(r.ranked.size <= Differential.MAX_ENTRIES)
        assertEquals(r.ranked.sortedByDescending { it.score }.map { it.code }, r.ranked.map { it.code })
        assertEquals("COND_APD", r.ranked.first().code) // three distinct keywords beat single mentions
    }

    /* ---------------- danger, rule-outs, outside the catalogue ---------------- */

    @Test
    fun suspectedRedFlagShowsTheDangerousDiagnosisItImplies() {
        val s = Reducer.reduce(CaseState(), LiveDelta(flagsIdentified = listOf(FlagInput("rf_gi_melena", false, "कालो दिसा"))), c)
        val d = dx(s, LocalAssist.Hints()).danger.single()
        assertEquals("Upper GI bleed", d.implies)
        assertFalse(d.confirmed)
        assertEquals("कालो दिसा", d.evidence)
    }

    @Test
    fun confirmedDangerComesBeforeSuspected() {
        val s = Reducer.reduce(
            CaseState(),
            LiveDelta(flagsIdentified = listOf(FlagInput("rf_meningism", false), FlagInput("rf_cp_rest_pain_20m", true))),
            c
        )
        val d = dx(s, LocalAssist.Hints()).danger
        assertEquals(listOf("rf_cp_rest_pain_20m", "rf_meningism"), d.map { it.flagId })
        assertTrue(d.first().confirmed)
    }

    @Test
    fun measuredRedFlagAppearsAsDanger() {
        val s = Reducer.reduce(CaseState(), LiveDelta(vitals = Vitals(spo2 = 84)), c)
        assertTrue(dx(s, LocalAssist.Hints()).danger.any { it.implies.startsWith("Respiratory failure") })
    }

    @Test
    fun clearedRedFlagIsNoLongerADanger() {
        var s = Reducer.reduce(CaseState(), LiveDelta(flagsIdentified = listOf(FlagInput("rf_gi_melena", false))), c)
        s = Reducer.handle(s, DoctorAction.ClearFlag("rf_gi_melena", "iron tablets"), c)
        assertTrue(dx(s, LocalAssist.Hints()).danger.isEmpty())
    }

    @Test
    fun redirectFlagIsNeverADanger() {
        val s = Reducer.reduce(CaseState(), LiveDelta(flagsIdentified = listOf(FlagInput("rf_gi_blood_in_stool", true))), c)
        assertTrue(dx(s, LocalAssist.Hints()).danger.isEmpty())
    }

    @Test
    fun leadingDiagnosesListTheirUnaskedRedFlagsToRuleOut() {
        val (s, h) = heard("patient" to "छातीमा दुख्छ, छाती भारी भयो")
        val r = dx(s, h)
        assertEquals("COND_CHEST_PAIN", r.ranked.first().code)
        assertTrue(r.ruleOut.any { it.id == "rf_cp_rest_pain_20m" })
        assertTrue(r.entry("COND_CHEST_PAIN")!!.toClarify.isNotEmpty())
    }

    @Test
    fun alreadyAskedRedFlagIsNotListedAgain() {
        var (s, h) = heard("patient" to "छातीमा दुख्छ, छाती भारी भयो")
        s = Reducer.handle(s, DoctorAction.ClearFlag("rf_cp_syncope", "asked: absent"), c)
        assertTrue(dx(s, h).ruleOut.none { it.id == "rf_cp_syncope" })
    }

    @Test
    fun problemOutsideTheCatalogueIsListedWithoutAProtocol() {
        val s = Reducer.reduce(CaseState(), LiveDelta(problems = listOf(ProblemInput("COND_MADE_UP", freeLabel = "Ear pain"))), c)
        assertEquals(listOf("Ear pain"), dx(s, LocalAssist.Hints()).outsideCatalogue)
    }

    /* ---------------- the AI's differential is display-only ---------------- */

    @Test
    fun aiDifferentialIsParsedNormalisedAndKept() {
        val d = Prompts.parseDelta(
            "{\"differential\":[{\"name\":\"Peptic ulcer\",\"likelihood\":\"Likely\",\"reason\":\"burning, relieved by food\",\"condition_code\":\"COND_APD\"}," +
                "{\"name\":\"Upper GI bleed\",\"likelihood\":\"must not miss\"},{\"name\":\"Gallstones\",\"likelihood\":\"maybe\"}]}"
        )
        assertEquals(listOf(AiLikelihood.LIKELY, AiLikelihood.MUST_NOT_MISS, AiLikelihood.POSSIBLE), d.differential.map { it.likelihood })
        var s = Reducer.reduce(CaseState(), d, c)
        assertEquals(3, s.aiDifferential.size)
        assertEquals("COND_APD", s.aiDifferential.first().conditionCode)
        s = Reducer.reduce(s, LiveDelta(), c)
        assertEquals("an update with nothing new keeps the last differential", 3, s.aiDifferential.size)
        s = Reducer.reduce(s, LiveDelta(differential = listOf(AiDiagnosis("Pneumonia", AiLikelihood.POSSIBLE))), c)
        assertEquals(listOf("Pneumonia"), s.aiDifferential.map { it.name })
    }

    @Test
    fun aiDifferentialDropsUnknownConditionCodesAndDuplicates() {
        val s = Reducer.reduce(
            CaseState(),
            LiveDelta(differential = listOf(
                AiDiagnosis("Thing", AiLikelihood.POSSIBLE, conditionCode = "COND_FAKE"),
                AiDiagnosis("thing", AiLikelihood.LIKELY),
                AiDiagnosis("  ", AiLikelihood.LIKELY)
            )),
            c
        )
        assertEquals(1, s.aiDifferential.size)
        assertNull(s.aiDifferential.single().conditionCode)
    }

    @Test
    fun theAiCanNeverMoveTheOnPhoneScore() {
        val (s, h) = heard("patient" to "ग्यास्ट्रिक छ")
        val withAi = s.copy(aiDifferential = listOf(AiDiagnosis("Pneumonia", AiLikelihood.LIKELY, conditionCode = "COND_CAP"), AiDiagnosis("APD", AiLikelihood.LIKELY, conditionCode = "COND_APD")))
        val a = dx(s, h)
        val b = dx(withAi, h)
        assertEquals(a.ranked.map { it.code to it.score }, b.ranked.map { it.code to it.score })
        assertTrue(b.entry("COND_APD")!!.aiAgrees)
        assertFalse(a.entry("COND_APD")!!.aiAgrees)
        assertNull("the AI cannot add a diagnosis the evidence does not support", b.entry("COND_CAP"))
    }

    @Test
    fun computingNeverChangesTheCaseOrItsSuggestions() {
        val (s, h) = heard("patient" to "ग्यास्ट्रिक छ, पेट पोल्छ", extra = LiveDelta(problems = listOf(ProblemInput("COND_APD")), planCue = true))
        val before = s
        dx(s, h)
        assertEquals(before, s)
    }

    /* ---------------- tracking: the diagnosis changes as more is said ---------------- */

    @Test
    fun trendsPersistUntilTheDoctorHasSeenThem() {
        var v = DiagnosisView()
        val (s1, h1) = heard("patient" to "gastric छ")
        v = Differential.track(v, dx(s1, h1), atSec = 10)
        assertEquals(Trend.NEW, v.trend("COND_APD"))
        assertTrue(v.hasUnseenChange)

        v = Differential.acknowledge(v)
        assertEquals(Trend.STEADY, v.trend("COND_APD"))
        assertFalse(v.hasUnseenChange)

        val (s2, h2) = heard("patient" to "ग्यास्ट्रिक छ, पेट पोल्छ, अमिलो आउँछ")
        v = Differential.track(v, dx(s2, h2), atSec = 40)
        assertEquals(Trend.UP, v.trend("COND_APD"))
        assertTrue(v.hasUnseenChange)
        assertTrue(v.changes.first().text, v.changes.first().text.contains("rose to likely"))
        assertEquals(40, v.changes.first().atSec)
    }

    @Test
    fun anUnchangedResultReturnsTheSameViewSoTheScreenDoesNotRedraw() {
        val (s, h) = heard("patient" to "gastric छ")
        val r = dx(s, h)
        val v = Differential.track(DiagnosisView(), r, 5)
        assertSame(v, Differential.track(v, r, 6))
    }

    @Test
    fun aDiagnosisThatDisappearsIsLogged() {
        val (s, h) = heard("patient" to "gastric छ")
        var v = Differential.track(DiagnosisView(), dx(s, h), 5)
        v = Differential.track(v, DifferentialResult(), 30)
        assertTrue(v.changes.first().text.contains("no longer suggested"))
    }

    @Test
    fun newDangerIsLoggedAndMarkedUnseen() {
        var v = Differential.track(DiagnosisView(), DifferentialResult(), 1)
        v = Differential.acknowledge(v)
        val s = Reducer.reduce(CaseState(), LiveDelta(flagsIdentified = listOf(FlagInput("rf_gi_hematemesis", true))), c)
        v = Differential.track(v, dx(s, LocalAssist.Hints()), 50)
        assertTrue(v.hasUnseenChange)
        assertTrue(v.changes.first().text.startsWith("⚠ Don't miss: Upper GI bleed"))
        assertFalse(Differential.acknowledge(v).hasUnseenChange)
    }

    @Test
    fun logIsBounded() {
        var v = DiagnosisView()
        val (s, h) = heard("patient" to "gastric छ")
        val base = dx(s, h)
        repeat(30) { i ->
            val r = if (i % 2 == 0) base else DifferentialResult()
            v = Differential.track(v, r, i)
        }
        assertTrue(v.changes.size <= Differential.MAX_CHANGES)
    }

    /* ---------------- randomized invariants ---------------- */

    @Test
    fun invariantsHoldOnRandomCases() {
        val rnd = Random(20261006)
        val phrases = listOf("ग्यास्ट्रिक छ", "खोकी छैन", "खोकी लाग्यो", "छातीमा दुख्छ", "प्रेसर छ", "सुगर छ", "पखाला भयो", "ज्वरो आयो", "कालो दिसा", "पिसाब पोल्छ", "no cough", "back pain", "wheezing")
        val codes = c.conditions.keys.toList() + "COND_OTHER"
        repeat(300) {
            val spoken = List(rnd.nextInt(5)) { "patient" to phrases[rnd.nextInt(phrases.size)] }
            val delta = LiveDelta(
                transcript = lines(*spoken.toTypedArray()),
                problems = List(rnd.nextInt(3)) { ProblemInput(codes[rnd.nextInt(codes.size)], freeLabel = "x$it") },
                vitals = if (rnd.nextBoolean()) Vitals(rr = 10 + rnd.nextInt(30), spo2 = 80 + rnd.nextInt(20), bpSystolic = 80 + rnd.nextInt(120), bpDiastolic = 50 + rnd.nextInt(80), tempF = 97.0 + rnd.nextInt(8)) else null,
                comorbidities = if (rnd.nextBoolean()) listOf("COMORB_T2DM", "COMORB_HTN", "COMORB_IHD").take(rnd.nextInt(4)) else emptyList(),
                currentMeds = List(rnd.nextInt(2)) { MedInput(listOf("Metformin", "Telma 40", "Brufen", "Pantop")[rnd.nextInt(4)]) }
            )
            var s = Reducer.handle(CaseState(), DoctorAction.SetDemographics(if (rnd.nextBoolean()) 1 + rnd.nextInt(90) else null, listOf(Gender.MALE, Gender.FEMALE, null)[rnd.nextInt(3)]), c)
            s = Reducer.reduce(s, delta, c)
            val r = dx(s, LocalAssist.analyze(s.transcript, c))
            assertTrue(r.ranked.size <= Differential.MAX_ENTRIES)
            assertEquals(r.ranked.map { it.score }, r.ranked.map { it.score }.sortedDescending())
            assertEquals(r.ranked.size, r.ranked.map { it.code }.toSet().size)
            for (e in r.ranked) {
                assertEquals(e.score, e.supporting.sumOf { it.points } + e.against.sumOf { it.points })
                if (e.likelihood == Likelihood.LIKELY) assertTrue(e.score >= Differential.LIKELY_AT)
                if (!e.onProblemList) assertTrue("unfiled entry below the threshold: ${e.code}", e.score >= Differential.POSSIBLE_AT)
            }
            for (p in s.problems.filter { it.conditionCode != "COND_OTHER" }) assertNotNull("filed problem ${p.conditionCode} was hidden", r.entry(p.conditionCode))
        }
    }
}
