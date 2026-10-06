package np.opd.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrOutputTest {

    private fun seg(text: String, start: Long = 0, noSpeech: Float? = null, logProb: Float? = null) =
        RecognizedSegment(text, start, start + 1000, noSpeech, logProb)

    private fun kept(vararg segs: RecognizedSegment, voiced: Float? = null) = AsrOutput.toLines(segs.toList(), voiced).map { it.text }

    /* ---------------- real speech is never lost ---------------- */

    @Test
    fun yourDeviceTestPhrasesSurviveUntouched() {
        val phrases = listOf(
            "नमस्ते, मलाई तीन दिनदेखि ज्वरो आएको छ।",
            "तीन दिनदेखि टाउको दुखिरहेको छ र बेलाबेलामा वान्ता हुन्छ।",
            "BP एक सय पचास बाइ पन्चानब्बे छ।",
            "SpO2 छयानब्बे प्रतिशत छ।",
            "मलाई cough पनि भइरहेको छ।",
            "No history of diabetes, hypertension छैन।",
            "Penicillin बाट allergy हुन्छ।",
            "आज बिहानदेखि छाती दुखेको छ।"
        )
        // even with poor confidence numbers that would remove a thank-you: real sentences stay
        val out = AsrOutput.toLines(phrases.map { seg(it, noSpeech = 0.4f, logProb = -1.2f) }, voicedFraction = 0.3f)
        assertEquals(phrases, out.map { it.text })
    }

    @Test
    fun shortRealAnswersAreKept() {
        assertEquals(listOf("हो", "छैन", "ज्वरो", "ठीक", "yes"), kept(seg("हो"), seg("छैन"), seg("ज्वरो"), seg("ठीक"), seg("yes")))
    }

    @Test
    fun linesAreUnknownSpeakerWithTheEngineTimestamps() {
        val out = AsrOutput.toLines(listOf(seg("पहिलो", 1500), seg("दोस्रो", 4200)))
        assertTrue(out.all { it.speaker == "unknown" })
        assertEquals(listOf(1500L, 4200L), out.map { it.offsetMs })
    }

    @Test
    fun textIsNormalisedNotTranslated() {
        assertEquals(listOf("तीन दिनदेखि ज्वरो"), kept(seg("  तीन   दिनदेखि \n ज्वरो  ")))
        // decomposed Latin letters are composed (NFC), so keyword matching later sees one form
        assertEquals(listOf("caf\u00e9"), kept(seg("cafe\u0301")))
        // Devanagari stays Devanagari: nothing is transliterated or translated
        assertEquals(listOf("मलाई ज्वरो छ"), kept(seg("मलाई ज्वरो छ")))
    }

    /* ---------------- what is dropped ---------------- */

    @Test
    fun emptyPunctuationAndEngineAnnotationsAreDropped() {
        assertTrue(kept(seg(""), seg("   "), seg("।"), seg("..."), seg("[BLANK_AUDIO]"), seg("(संगीत)"), seg("[ Silence ]"), seg("（音楽）")).isEmpty())
    }

    @Test
    fun runawayRepetitionLoopsAreDroppedInNepaliAndEnglish() {
        assertTrue(kept(seg("छ छ छ छ छ छ छ छ छ छ")).isEmpty())
        assertTrue(kept(seg("the the the the the the the the")).isEmpty())
        assertTrue(kept(seg("मलाई ज्वरो छ मलाई ज्वरो छ मलाई ज्वरो छ मलाई ज्वरो छ मलाई ज्वरो छ")).isEmpty())
    }

    @Test
    fun normalRepetitionInSpeechIsKept() {
        assertEquals(1, kept(seg("छैन छैन, मलाई केही छैन भनेको")).size)
        assertEquals(1, kept(seg("बिस्तारै बिस्तारै खाने, दिनमा तीन पटक")).size)
    }

    @Test
    fun stockOutroPhrasesAreDroppedEvenWithoutConfidenceInfo() {
        assertTrue(kept(seg("भिडियो हेर्नु भएकोमा धन्यवाद।"), seg("Thanks for watching!"), seg("च्यानल सब्सक्राइब गर्नुहोस्"), seg("Subtitles by the Amara.org community")).isEmpty())
    }

    @Test
    fun aRealThankYouIsKeptUnlessTheAudioWasSilence() {
        // a doctor or patient really can say "धन्यवाद" or "thank you"
        assertEquals(listOf("धन्यवाद"), kept(seg("धन्यवाद"), voiced = 0.4f))
        assertEquals(listOf("thank you"), kept(seg("thank you", noSpeech = 0.1f, logProb = -0.3f), voiced = 0.5f))
        // ... but not when the engine says no speech and the audio was silent
        assertTrue(kept(seg("धन्यवाद", noSpeech = 0.8f, logProb = -1.5f), voiced = 0.01f).isEmpty())
        assertTrue(kept(seg("thank you"), voiced = 0.0f).isEmpty())
    }

    @Test
    fun whisperStyleSilenceRuleDropsNoSpeechWithAPoorDecode() {
        assertTrue(kept(seg("कुनै पाठ", noSpeech = 0.9f, logProb = -2.0f)).isEmpty())
        assertEquals("a confident decode is kept even if the no-speech score is high", 1, kept(seg("ज्वरो आएको छ", noSpeech = 0.9f, logProb = -0.2f)).size)
    }

    @Test
    fun repetitionDetectorOnlyFiresOnLoops() {
        assertTrue(!AsrOutput.isRepetitionLoop("मलाई तीन दिनदेखि ज्वरो आएको छ"))
        assertTrue(!AsrOutput.isRepetitionLoop("a b c d e f"))
        assertTrue(AsrOutput.isRepetitionLoop("a a a a a a a a"))
    }
}
