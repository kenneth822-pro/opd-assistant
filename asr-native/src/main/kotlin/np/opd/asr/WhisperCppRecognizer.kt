package np.opd.asr

import np.opd.speech.AsrBackend
import np.opd.speech.AsrConfig
import np.opd.speech.AsrErrorKind
import np.opd.speech.AsrException
import np.opd.speech.LocalSpeechRecognizer
import np.opd.speech.RecognizedSegment
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * whisper.cpp on the ARM64 CPU. Implements only what the app needs from a speech engine: audio in, text out.
 * It contains no clinical logic and does not know about the app, the UI or any cloud provider.
 *
 * Calls are serialised by [lock]; [cancel] and [release] are safe from any thread (release waits for a
 * running inference to stop before freeing the model).
 */
class WhisperCppRecognizer(
    private val modelFile: File,
    private val native: WhisperNative = WhisperNative(),
    private val libraryName: String = "opd_asr"
) : LocalSpeechRecognizer {

    override val backend: AsrBackend = AsrBackend.CPU_GGML
    override val supportsPrompt: Boolean = true

    private val lock = ReentrantLock()
    @Volatile private var handle = 0L
    @Volatile private var lastTimings: FloatArray? = null
    @Volatile private var systemInfo: String = ""

    override suspend fun initialize() = lock.withLock {
        if (handle != 0L) return@withLock
        if (!modelFile.isFile) throw AsrException(AsrErrorKind.MODEL_MISSING, modelFile.name)
        try {
            WhisperNative.load(libraryName)
        } catch (e: UnsatisfiedLinkError) {
            throw AsrException(AsrErrorKind.RUNTIME_UNAVAILABLE, "libopd_asr.so could not be loaded (${e.message})", e)
        }
        val h = try {
            native.nativeInit(modelFile.absolutePath, false)
        } catch (e: UnsatisfiedLinkError) {
            throw AsrException(AsrErrorKind.RUNTIME_UNAVAILABLE, "native bridge does not match this app version (${e.message})", e)
        }
        if (h == 0L) {
            throw AsrException(AsrErrorKind.INIT_FAILED, "${modelFile.name} could not be loaded: the file may be damaged, not a GGML Whisper model, or too large for the free memory")
        }
        handle = h
        systemInfo = runCatching { native.nativeSystemInfo() }.getOrDefault("")
    }

    override suspend fun transcribe(pcm: FloatArray, config: AsrConfig, prompt: String?): List<RecognizedSegment> = lock.withLock {
        val h = handle
        if (h == 0L) throw AsrException(AsrErrorKind.INIT_FAILED, "the model is not loaded")
        val n = native.nativeTranscribe(h, pcm, config.language, config.translate, config.threads, config.audioContext, prompt)
        when (n) {
            -3 -> throw AsrException(AsrErrorKind.CANCELLED)
            -4 -> throw AsrException(AsrErrorKind.BAD_AUDIO, "no samples")
            -5 -> throw AsrException(AsrErrorKind.INFERENCE_FAILED, "native exception (low memory?)")
            -1 -> throw AsrException(AsrErrorKind.INIT_FAILED, "the model is not loaded")
        }
        if (n < 0) throw AsrException(AsrErrorKind.INFERENCE_FAILED, "whisper.cpp error code $n")
        lastTimings = native.nativeLastTimings(h)
        List(n) { i ->
            RecognizedSegment(
                // raw bytes decoded here: an invalid sequence becomes U+FFFD instead of aborting the process
                text = String(native.nativeSegmentTextBytes(h, i), Charsets.UTF_8).trim(),
                startMs = native.nativeSegmentStartMs(h, i),
                endMs = native.nativeSegmentEndMs(h, i),
                noSpeechProb = native.nativeSegmentNoSpeech(h, i),
                avgLogProb = native.nativeSegmentAvgLogProb(h, i)
            )
        }
    }

    override fun isReady(): Boolean = handle != 0L

    override fun diagnostics(): String {
        val t = lastTimings
        val timing = if (t == null || t.size < 3) "" else " | last run: encoder ${t[1].toLong()} ms, decoder ${t[2].toLong()} ms"
        return systemInfo + timing
    }

    override fun cancel() {
        val h = handle
        if (h != 0L) native.nativeCancel(h)
    }

    override fun release() {
        cancel()
        lock.withLock {
            val h = handle
            if (h != 0L) {
                handle = 0L
                native.nativeFree(h)
            }
        }
    }
}
