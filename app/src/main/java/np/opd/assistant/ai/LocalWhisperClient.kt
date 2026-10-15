package np.opd.assistant.ai

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import np.opd.engine.TranscriptLine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Local Whisper STT using Sherpa-ONNX.
 * Runs entirely on-device (16 kHz mono 16-bit PCM).
 *
 * The recognizer is loaded once per model directory and cached process-wide, because
 * SessionViewModel creates a fresh client for every audio segment; reloading a ~117-200 MB
 * ONNX model on each 15-second chunk would be unusable.
 */
class LocalWhisperClient(
    private val context: Context,
    private val modelDir: File
) : AiClient {

    private val TAG = "LocalWhisperClient"
    private val recognizer: OfflineRecognizer by lazy { getOrCreateRecognizer(modelDir) }

    override val model: ModelInfo = ModelInfo(
        provider = Provider.LOCAL_WHISPER,
        id = modelDir.name,
        name = "Whisper ${modelDir.name.replace("sherpa-onnx-", "").uppercase()} (Local)",
        audioInput = true,
        free = true,
        structuredOutput = false,
        jsonMode = false,
        transcriptionApi = true
    )

    override suspend fun generate(system: String?, parts: List<AiPart>, schema: np.opd.engine.J.Obj?): String {
        throw UnsupportedOperationException("LocalWhisperClient only supports transcribe(), not generate()")
    }

    override suspend fun transcribe(wav: ByteArray, contextHint: String): List<TranscriptLine> = withContext(Dispatchers.IO) {
        if (!isValidModelDir(modelDir)) throw ApiException("Local Whisper model files incomplete: ${modelDir.name}")
        val samples = wavBytesToFloatArray(wav)
        if (samples.isEmpty()) return@withContext emptyList()
        // Sherpa-ONNX OfflineRecognizer is not thread-safe: serialize decode() calls so a
        // suggestNow()/retryFailed() segment can never touch the native pointer concurrently.
        inferenceMutex.withLock {
            try {
                val stream = recognizer.createStream()
                stream.acceptWaveform(samples, RATE)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                stream.release()
                if (text.isEmpty()) emptyList() else listOf(TranscriptLine("unknown", text, 0L))
            } catch (e: UnsatisfiedLinkError) {
                throw ApiException("Sherpa-ONNX native error: ${e.message}", transient = true)
            } catch (e: Exception) {
                throw ApiException("Local Whisper failed: ${e.message}")
            }
        }
    }

    companion object {
        const val RATE = 16000

        /** Serializes all native decode() calls across LocalWhisperClient instances. */
        private val inferenceMutex = Mutex()

        /** Loaded recognizers, keyed by model directory (one ONNX graph per model). */
        private val cache = ConcurrentHashMap<String, OfflineRecognizer>()

        private fun getOrCreateRecognizer(dir: File): OfflineRecognizer {
            val key = dir.absolutePath
            cache[key]?.let { return it }
            synchronized(cache) {
                cache[key]?.let { return it }
                val whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    language = if (dir.name.endsWith(".en")) "en" else "",
                    task = "transcribe"
                )
                val modelConfig = OfflineModelConfig(
                    whisper = whisper,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 4,
                    provider = "cpu"
                )
                val config = OfflineRecognizerConfig(modelConfig = modelConfig)
                val created = OfflineRecognizer(config = config, assetManager = null)
                cache[key] = created
                return created
            }
        }

        /** Decodes a 16-bit PCM little-endian WAV (44-byte header) into normalized floats in [-1, 1]. */
        fun wavBytesToFloatArray(wav: ByteArray): FloatArray {
            if (wav.size <= WAV_HEADER) return FloatArray(0)
            val payload = wav.size - WAV_HEADER
            val n = payload / 2
            val buf = ByteBuffer.wrap(wav, WAV_HEADER, payload).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = buf.short / 32768.0f
            return out
        }

        private const val WAV_HEADER = 44

        /** Checks if a model directory has all required files. */
        fun isValidModelDir(dir: File): Boolean {
            return dir.isDirectory &&
                File(dir, "encoder.int8.onnx").exists() &&
                File(dir, "decoder.int8.onnx").exists() &&
                File(dir, "tokens.txt").exists()
        }

        /** Copies bundled asset model to internal storage for use. */
        suspend fun copyBundledModel(context: Context, assetName: String, targetDir: File): File {
            return withContext(Dispatchers.IO) {
                targetDir.mkdirs()
                val assets = context.assets
                val files = assets.list(assetName) ?: emptyArray()
                for (file in files) {
                    val input = assets.open("$assetName/$file")
                    val output = FileOutputStream(File(targetDir, file))
                    input.copyTo(output)
                    input.close()
                    output.close()
                }
                targetDir
            }
        }
    }
}
