package np.opd.assistant.ai

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.opd.engine.TranscriptLine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Local Whisper STT using Sherpa-ONNX.
 * Runs entirely on-device with VAD (Voice Activity Detection) and ASR.
 */
class LocalWhisperClient(
    private val context: Context,
    private val modelDir: File
) : AiClient {

    private val TAG = "LocalWhisperClient"
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var initialized = false

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

    /** Initializes the recognizer and VAD from model files. */
    private fun ensureInitialized() {
        if (initialized) return
        try {
            // VAD config (Silero VAD) - use the correct API for sherpa-onnx v1.13.8
            val vadConfig = Vad.VadModelConfig()
            vadConfig.sileroVad = Vad.SileroVadModelConfig().apply {
                model = File(modelDir, "silero_vad.onnx").absolutePath
                threshold = 0.5f
                minSpeechDuration = 0.25f
                minSilenceDuration = 0.5f
                windowSize = 512
            }
            vadConfig.numThreads = 2
            vadConfig.debug = false
            vadConfig.provider = "cpu"
            vad = Vad(context.assets, vadConfig)

            // Whisper offline recognizer config
            val recognizerConfig = OfflineRecognizerConfig()
            recognizerConfig.featConfig = com.k2fsa.sherpa.onnx.FeatureConfig().apply {
                sampleRate = 16000
                featureDim = 80
            }
            recognizerConfig.modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig().apply {
                tokens = File(modelDir, "tokens.txt").absolutePath
                numThreads = 2
                debug = false
                provider = "cpu"
                modelType = "whisper"
                whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig().apply {
                    encoder = File(modelDir, "encoder.int8.onnx").absolutePath
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath
                    language = "auto"
                    task = "transcribe"
                    tailPaddings = 1000
                }
            }
            
            recognizer = OfflineRecognizer(context.assets, recognizerConfig)
            
            // Create VAD instance
            vad = Vad(context.assets, vadConfig)
            
            initialized = true
            Log.i(TAG, "Initialized Sherpa-ONNX Whisper from $modelDir")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize local Whisper", e)
            throw ApiException("Failed to initialize local Whisper model: ${e.message}")
        }
    }

    override suspend fun generate(system: String?, parts: List<AiPart>, schema: np.opd.engine.J.Obj?): String {
        throw UnsupportedOperationException("LocalWhisperClient only supports transcribe(), not generate()")
    }

    override suspend fun transcribe(wav: ByteArray, contextHint: String): List<TranscriptLine> = withContext(Dispatchers.IO) {
        ensureInitialized()
        
        val recognizer = this.recognizer ?: throw ApiException("Recognizer not initialized")
        val vad = this.vad ?: throw ApiException("VAD not initialized")

        // Convert WAV bytes to FloatArray (16kHz mono, 16-bit PCM)
        val samples = wavToFloatArray(wav)
        if (samples.isEmpty()) return@withContext emptyList()

        // Run VAD to get speech segments
        val segments = mutableListOf<FloatArray>()
        vad.acceptWaveform(samples)
        while (!vad.empty()) {
            val segment = vad.front()
            segments.add(segment.samples.copyOf())
            vad.pop()
        }

        if (segments.isEmpty()) return@withContext emptyList()

        // Run ASR on each segment
        val results = mutableListOf<TranscriptLine>()
        var offsetMs = 0L
        
        for (segmentSamples in segments) {
            val stream: OfflineStream = recognizer.createStream()
            try {
                stream.acceptWaveform(segmentSamples, 16000)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                val text = result.text.trim()
                if (text.isNotEmpty()) {
                    results.add(TranscriptLine("unknown", text, offsetMs.toLong()))
                }
                offsetMs += (segmentSamples.size * 1000L / 16000)
            } finally {
                stream.release()
            }
        }

        if (results.isEmpty()) {
            // Fallback: try to transcribe the whole audio without VAD
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                val text = result.text.trim()
                if (text.isNotEmpty()) {
                    return@withContext listOf(TranscriptLine("unknown", text, 0))
                }
            } finally {
                stream.release()
            }
        }

        results
    }

    /** Converts 16-bit PCM WAV bytes to FloatArray (-1.0 to 1.0). Assumes 16kHz mono. */
    private fun wavToFloatArray(wav: ByteArray): FloatArray {
        // Skip WAV header (44 bytes for standard 16kHz mono 16-bit)
        val dataStart = findDataChunk(wav)
        val pcmData = wav.copyOfRange(dataStart, wav.size)
        val sampleCount = pcmData.size / 2
        val floatArray = FloatArray(sampleCount)
        
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val sample = buffer.getShort()
            floatArray[i] = sample.toFloat() / 32768.0f
        }
        return floatArray
    }

    private fun findDataChunk(wav: ByteArray): Int {
        var pos = 12 // Skip RIFF header
        while (pos + 8 <= wav.size) {
            val chunkId = String(wav.copyOfRange(pos, pos + 4))
            val chunkSize = ByteBuffer.wrap(wav, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
            if (chunkId == "data") {
                return pos + 8
            }
            pos += 8 + chunkSize
        }
        return 44 // Default fallback
    }

    companion object {
        /** Checks if a model directory has all required files. */
        fun isValidModelDir(dir: File): Boolean {
            return dir.isDirectory &&
                File(dir, "encoder.int8.onnx").exists() &&
                File(dir, "decoder.int8.onnx").exists() &&
                File(dir, "tokens.txt").exists() &&
                File(dir, "silero_vad.onnx").exists()
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