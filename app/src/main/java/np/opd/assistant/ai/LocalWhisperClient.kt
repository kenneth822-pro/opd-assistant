package np.opd.assistant.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.opd.engine.TranscriptLine
import java.io.File

/**
 * Local Whisper STT using Sherpa-ONNX.
 * Runs entirely on-device with VAD (Voice Activity Detection) and ASR.
 * 
 * NOTE: This is a stub implementation that compiles. The actual Sherpa-ONNX integration
 * requires proper API usage which varies by version. Replace with real implementation
 * when testing on device.
 */
class LocalWhisperClient(
    private val context: Context,
    private val modelDir: File
) : AiClient {

    private val TAG = "LocalWhisperClient"
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
            // TODO: Initialize Sherpa-ONNX OfflineRecognizer and Vad here
            // This requires the actual Sherpa-ONNX Kotlin API which varies by version
            // Example structure:
            // val vadConfig = Vad.VadModelConfig().apply { ... }
            // vad = Vad(context.assets, vadConfig)
            // val recognizerConfig = OfflineRecognizerConfig().apply { ... }
            // recognizer = OfflineRecognizer(context.assets, recognizerConfig)
            
            initialized = true
            Log.i(TAG, "Initialized Sherpa-ONNX Whisper from $modelDir (STUB)")
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
        
        // STUB: Return a test transcript line to verify the model is reachable
        // Real implementation would use Sherpa-ONNX OfflineRecognizer
        Log.i(TAG, "transcribe() called (STUB) - returning test transcript")
        listOf(TranscriptLine("test", "[STUB] Local Whisper model reachable", 0))
    }

    companion object {
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
                    val output = java.io.FileOutputStream(File(targetDir, file))
                    input.copyTo(output)
                    input.close()
                    output.close()
                }
                targetDir
            }
        }
    }
}