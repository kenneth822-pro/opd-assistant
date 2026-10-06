package np.opd.speech

import kotlin.math.sqrt

/**
 * Reads the app's recorder output (RIFF/WAVE, 16 kHz, mono, 16-bit PCM) into float samples.
 * Tolerant of extra chunks (LIST, etc.) and of a header that claims more data than the file holds
 * (a recording cut short by a crash), but strict about the format: a wrong sample rate would silently
 * turn speech into garbage, so it is rejected instead.
 */
object WavAudio {

    const val SAMPLE_RATE = 16_000

    fun decode(wav: ByteArray): FloatArray {
        if (wav.size < 12 || tag(wav, 0) != "RIFF" || tag(wav, 8) != "WAVE") throw AsrException(AsrErrorKind.BAD_AUDIO, "not a WAV file")
        var pos = 12
        var channels = -1
        var rate = -1
        var bits = -1
        var format = -1
        while (pos + 8 <= wav.size) {
            val id = tag(wav, pos)
            val declared = u32(wav, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                if (body + 16 > wav.size) throw AsrException(AsrErrorKind.BAD_AUDIO, "truncated header")
                format = u16(wav, body)
                channels = u16(wav, body + 2)
                rate = u32(wav, body + 4).toInt()
                bits = u16(wav, body + 14)
            } else if (id == "data") {
                if (format != 1 && format != 0xFFFE) throw AsrException(AsrErrorKind.BAD_AUDIO, "not PCM")
                if (channels != 1 || rate != SAMPLE_RATE || bits != 16)
                    throw AsrException(AsrErrorKind.BAD_AUDIO, "expected 16 kHz mono 16-bit, got $rate Hz, $channels ch, $bits bit")
                val available = (wav.size - body).toLong()
                val bytes = minOf(declared, available).toInt() and 1.inv()   // whole samples only
                val out = FloatArray(bytes / 2)
                var p = body
                for (i in out.indices) {
                    val lo = wav[p].toInt() and 0xFF
                    val hi = wav[p + 1].toInt()
                    out[i] = ((hi shl 8) or lo).toShort() / 32768f
                    p += 2
                }
                return out
            }
            // chunks are word-aligned
            val next = body.toLong() + declared + (declared and 1L)
            if (next > wav.size) break
            pos = next.toInt()
        }
        throw AsrException(AsrErrorKind.BAD_AUDIO, "no audio data")
    }

    fun durationMs(samples: Int): Long = samples * 1000L / SAMPLE_RATE

    /**
     * Splits long audio into windows of at most [windowSamples] (Whisper's window is 30 s), cutting at the
     * quietest 100 ms inside the last [searchSamples] of each window so a word is not cut in half.
     * Backends that process arbitrary lengths themselves (whisper.cpp) do not need this.
     * No window ever exceeds [windowSamples]. If a cut would leave a sliver shorter than [minTailSamples],
     * the cut moves earlier instead, so a fraction of a second is never transcribed on its own.
     */
    fun split(pcm: FloatArray, windowSamples: Int = 30 * SAMPLE_RATE, searchSamples: Int = 2 * SAMPLE_RATE, minTailSamples: Int = SAMPLE_RATE): List<FloatArray> {
        require(windowSamples > searchSamples && searchSamples >= FRAME) { "bad window sizes" }
        if (pcm.size <= windowSamples) return listOf(pcm)
        val out = ArrayList<FloatArray>()
        var start = 0
        while (pcm.size - start > windowSamples) {
            val from = start + windowSamples - searchSamples
            val to = start + windowSamples
            var best = to
            var bestRms = Double.MAX_VALUE
            var f = from
            while (f + FRAME <= to) {
                val rms = rms(pcm, f, FRAME)
                if (rms < bestRms) { bestRms = rms; best = f + FRAME / 2 }
                f += FRAME
            }
            if (pcm.size - best < minTailSamples && pcm.size - minTailSamples > start) best = pcm.size - minTailSamples
            out += pcm.copyOfRange(start, best)
            start = best
        }
        out += pcm.copyOfRange(start, pcm.size)
        return out
    }

    private const val FRAME = 1600 // 100 ms

    private fun rms(a: FloatArray, from: Int, n: Int): Double {
        var s = 0.0
        for (i in from until from + n) s += a[i].toDouble() * a[i]
        return sqrt(s / n)
    }

    private fun tag(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}
