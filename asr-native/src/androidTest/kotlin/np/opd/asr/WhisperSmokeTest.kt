package np.opd.asr

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import np.opd.speech.AsrConfig
import np.opd.speech.WavAudio
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

/**
 * Device smoke test for the REAL native engine: it needs a phone (arm64) and a GGML model file, so it is skipped
 * (not faked) when no model is present. It proves the library loads, the model initialises and inference runs, and
 * it logs the measured speed. It does not judge accuracy: use the Nepali phrase list in docs/ON_DEVICE_ASR.md.
 *
 *   adb push ggml-nepali-whisper-turbo-q5_0.bin /sdcard/Android/data/np.opd.asr.test/files/model.bin
 *   ./gradlew :asr-native:connectedDebugAndroidTest -PenableNativeAsr=true \
 *       -Pandroid.testInstrumentationRunnerArguments.wavPath=/sdcard/Android/data/np.opd.asr.test/files/sample.wav
 *   adb logcat -s OpdAsrSmoke OpdAsr
 *
 * Optional instrumentation arguments: modelPath (default <app external files>/model.bin), wavPath (a 16 kHz mono
 * WAV of speech), expect (text that must appear in the transcript).
 */
@RunWith(AndroidJUnit4::class)
class WhisperSmokeTest {

    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().context

    private fun model(): File {
        val given = args.getString("modelPath")
        val file = if (given != null) File(given) else File(context.getExternalFilesDir(null), "model.bin")
        assumeTrue("no model at $file: push one first (see docs/ON_DEVICE_ASR.md)", file.isFile)
        return file
    }

    @Test
    fun loadsTheModelAndRunsInferenceOnAbsoluteSilenceWithoutCrashing() = runBlocking {
        val r = WhisperCppRecognizer(model())
        try {
            val t0 = System.currentTimeMillis()
            r.initialize()
            val loadMs = System.currentTimeMillis() - t0
            assertTrue("model should be loaded", r.isReady())
            val t1 = System.currentTimeMillis()
            val segs = r.transcribe(FloatArray(5 * WavAudio.SAMPLE_RATE), AsrConfig(language = "ne"))
            val inferMs = System.currentTimeMillis() - t1
            Log.i(TAG, "load ${loadMs} ms; 5 s of silence in ${inferMs} ms (RTF ${"%.2f".format(inferMs / 5000.0)}); segments=${segs.size}")
            Log.i(TAG, "engine: ${r.diagnostics()}")
        } finally {
            r.release()
        }
    }

    @Test
    fun transcribesTheProvidedWavAndReportsSpeed() = runBlocking {
        val wavPath = args.getString("wavPath")
        assumeTrue("no wavPath argument: nothing to transcribe", wavPath != null)
        val wav = File(wavPath!!)
        assumeTrue("no file at $wav", wav.isFile)
        val pcm = WavAudio.decode(wav.readBytes())
        val r = WhisperCppRecognizer(model())
        try {
            r.initialize()
            val t = System.currentTimeMillis()
            val segs = r.transcribe(pcm, AsrConfig(language = "ne"))
            val ms = System.currentTimeMillis() - t
            val audioMs = WavAudio.durationMs(pcm.size)
            val text = segs.joinToString(" ") { it.text }
            Log.i(TAG, "heard: $text")
            Log.i(TAG, "${audioMs} ms of audio in ${ms} ms (RTF ${"%.2f".format(ms.toDouble() / audioMs)}); ${r.diagnostics()}")
            args.getString("expect")?.let { assertTrue("transcript '$text' should contain '$it'", text.contains(it)) }
        } finally {
            r.release()
        }
    }

    private companion object { const val TAG = "OpdAsrSmoke" }
}
