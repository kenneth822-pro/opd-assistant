package np.opd.speech

import np.opd.engine.TranscriptLine
import java.text.Normalizer

/**
 * Turns what the recogniser returned into the app's [TranscriptLine]s, dropping only what is clearly not speech.
 *
 * The filters are careful about Nepali: nothing is removed because it is Devanagari, short or unusual. A segment is
 * dropped only if it is (a) empty or an engine annotation such as "[BLANK_AUDIO]", (b) a runaway repetition loop,
 * (c) a stock "thanks for watching / subscribe" phrase Whisper invents on noise, or (d) very low confidence AND
 * very likely silence. Bare "thank you" / "धन्यवाद" can be real speech, so those are dropped only when the
 * engine's own confidence says the audio held no speech.
 *
 * Speaker is always "unknown": Whisper does not label speakers and the existing AI analysis infers roles.
 */
object AsrOutput {

    /** Phrases Whisper invents on silence or noise (YouTube-style outros). Always dropped when they are the whole segment. */
    private val outros = listOf(
        "thanks for watching", "thank you for watching", "please subscribe", "subscribe to my channel", "like and subscribe",
        "subtitles by the amara.org community", "subtitles by", "amara.org",
        "भिडियो हेर्नु भएकोमा धन्यवाद", "भिडियो हेर्नुभएकोमा धन्यवाद", "हेर्नु भएकोमा धन्यवाद", "हेर्नुभएकोमा धन्यवाद",
        "च्यानल सब्सक्राइब गर्नुहोस्", "च्यानललाई सब्सक्राइब गर्नुहोस्", "सब्सक्राइब गर्नुहोला", "सबटाइटल"
    )

    /** Plausible real speech, so dropped only when the engine's confidence says there was none. */
    private val shortPleasantries = listOf("thank you", "thanks", "you", "bye", "धन्यवाद", "ठीक छ")

    private val annotation = Regex("^[\\[(（【].*[\\])）】]$")
    private val punctuation = Regex("[\\p{Punct}\\u0964\\u0965\\u2018-\\u201F\\u2026\\s]+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()

    /** Lower-cased, punctuation-free form used only for comparisons; the original text is what is returned. */
    private fun key(text: String): String = punctuation.replace(normalize(text).lowercase(), " ").trim()

    /**
     * @param voicedFraction share of the audio that was louder than silence (0..1), if known. Low values make the
     *                       "invented phrase" check stricter.
     */
    fun toLines(segments: List<RecognizedSegment>, voicedFraction: Float? = null): List<TranscriptLine> =
        segments.mapNotNull { s ->
            val text = normalize(s.text)
            if (shouldDrop(text, s, voicedFraction)) null else TranscriptLine("unknown", text, s.startMs.coerceAtLeast(0))
        }

    fun shouldDrop(text: String, s: RecognizedSegment, voicedFraction: Float?): Boolean {
        if (text.isEmpty()) return true
        val k = key(text)
        if (k.isEmpty()) return true                       // punctuation only
        if (annotation.matches(text)) return true          // [BLANK_AUDIO], (music), [संगीत] ...
        if (isRepetitionLoop(k)) return true

        val noSpeech = s.noSpeechProb
        val logProb = s.avgLogProb
        val likelySilence = (noSpeech != null && noSpeech >= 0.6f && (logProb == null || logProb < -1.0f)) ||
            (voicedFraction != null && voicedFraction < 0.05f)

        if (outros.any { k == key(it) }) return true
        if (shortPleasantries.any { k == key(it) } && likelySilence) return true
        // Whisper's own rule for silence: a no-speech segment with a poor decode.
        if (noSpeech != null && noSpeech >= 0.6f && logProb != null && logProb < -1.0f) return true
        return false
    }

    /**
     * A decoder stuck in a loop repeats a word or short phrase many times. Real speech does not repeat the same
     * token 8+ times in a row or the same 2-5 word phrase 5+ times in a row.
     */
    fun isRepetitionLoop(k: String): Boolean {
        val w = k.split(' ').filter { it.isNotEmpty() }
        if (w.size < 6) return false
        var run = 1
        for (i in 1 until w.size) {
            run = if (w[i] == w[i - 1]) run + 1 else 1
            if (run >= 8) return true
        }
        for (n in 2..5) {
            if (w.size < n * 5) continue
            for (start in 0..w.size - n * 5) {
                var reps = 1
                var at = start + n
                while (at + n <= w.size && w.subList(at, at + n) == w.subList(start, start + n)) { reps++; at += n }
                if (reps >= 5) return true
            }
        }
        return false
    }
}
