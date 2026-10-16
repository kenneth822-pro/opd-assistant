package np.opd.assistant.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import np.opd.engine.J
import java.io.File

/**
 * Local LLM using ExecuTorch (LlmModule).
 * Runs entirely on-device for note generation and live suggestions.
 *
 * The LlmModule holds a 1.1-2.4 GB parameter buffer, so it is cached process-wide and keyed by
 * model path: SessionViewModel creates a new client per call, and reloading the .pte every time
 * would exhaust memory. Only one local LLM is kept resident at a time. The native library ships
 * only for arm64-v8a and x86_64; a load failure is surfaced as a transient error so the caller
 * falls back to a backup model or the on-phone draft.
 */
class LocalLlmClient(
    private val modelFile: File,
    private val tokenizerFile: File
) : AiClient {

    private val TAG = "LocalLlmClient"

    override val model: ModelInfo = ModelInfo(
        provider = Provider.LOCAL_LLM,
        id = modelFile.parentFile?.name ?: modelFile.nameWithoutExtension,
        name = "LLM ${modelFile.parentFile?.name ?: modelFile.nameWithoutExtension} (Local)",
        audioInput = false,
        free = true,
        structuredOutput = true,
        jsonMode = true,
        contextLength = 4096
    )

    override suspend fun transcribe(wav: ByteArray, context: String): List<np.opd.engine.TranscriptLine>? = null

    override suspend fun generate(system: String?, parts: List<AiPart>, schema: J.Obj?): String =
        withContext(Dispatchers.IO) {
            val prompt = buildLlamaPrompt(system, parts)
            val module = getOrCreateModule(modelFile, tokenizerFile)
            // ExecuTorch LlmModule is not thread-safe: serialize generate() calls so a
            // suggestNow()/retryFailed() run can never touch the native pointer concurrently.
            inferenceMutex.withLock {
                try {
                    val sb = StringBuilder()
                    val callback = object : org.pytorch.executorch.extension.llm.LlmCallback {
                        override fun onResult(result: String?) {
                            if (result != null) sb.append(result)
                        }

                        override fun onStats(stats: String?) {
                            // Optional progress/telemetry: ignored.
                        }
                    }
                    module.generate(prompt, MAX_NEW_TOKENS, callback)
                    val out = sb.toString()
                    if (out.isBlank()) {
                        throw ApiException("Local LLM returned an empty answer (${model.id}).", transient = true)
                    }
                    out
                } catch (e: UnsatisfiedLinkError) {
                    throw ApiException("ExecuTorch native error: ${e.message}", transient = true)
                } catch (e: ApiException) {
                    throw e
                } catch (e: Exception) {
                    throw ApiException("ExecuTorch native error: ${e.message}", transient = true)
                }
            }
        }

    companion object {
        private const val MAX_NEW_TOKENS = 512

        /** Serializes all native generate() calls across LocalLlmClient instances. */
        private val inferenceMutex = Mutex()

        /** The single loaded LlmModule, together with the path it was loaded from. */
        @Volatile private var loadedPath: String? = null
        @Volatile private var module: org.pytorch.executorch.extension.llm.LlmModule? = null
        private val lock = Any()

        private fun getOrCreateModule(modelFile: File, tokenizerFile: File): org.pytorch.executorch.extension.llm.LlmModule {
            val path = modelFile.absolutePath
            synchronized(lock) {
                if (loadedPath == path) module?.let { return it }
                // A different model is requested: free the previous one before loading the new one.
                module?.let {
                    try { it.resetNative() } catch (e: Exception) { Log.w("LocalLlmClient", "resetNative() failed: ${e.message}") }
                }
                module = null
                loadedPath = null
                try {
                    val created = org.pytorch.executorch.extension.llm.LlmModule(path, tokenizerFile.absolutePath, 0.1f)
                    module = created
                    loadedPath = path
                    return created
                } catch (e: UnsatisfiedLinkError) {
                    throw ApiException("ExecuTorch native error: ${e.message}", transient = true)
                } catch (e: Exception) {
                    throw ApiException("Failed to load local LLM ${modelFile.name}: ${e.message}", transient = true)
                }
            }
        }

        /** Builds a Llama-3.2 Instruct prompt from the system instruction and message parts. */
        fun buildLlamaPrompt(system: String?, parts: List<AiPart>): String {
            val user = parts.joinToString("\n") { p ->
                when (p) {
                    is AiPart.Text -> p.text
                    is AiPart.Wav -> "[audio not supported by the local text model]"
                }
            }
            return buildString {
                append("<|begin_of_text|>")
                append("<|start_header_id|>system<|end_header_id|>\n\n")
                append(system ?: "You are a careful clinical scribe.")
                append("<|eot_id|>")
                append("<|start_header_id|>user<|end_header_id|>\n\n")
                append(user)
                append("<|eot_id|>")
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
        }

        /** Checks if model files exist and are valid. */
        fun isValidModelDir(dir: File): Boolean {
            return findModelFiles(dir) != null
        }

        /** Finds the .pte model file and tokenizer in a directory. */
        fun findModelFiles(dir: File): Pair<File, File>? {
            if (!dir.isDirectory) return null
            val files = dir.listFiles() ?: return null
            val pte = files.firstOrNull { it.isFile && it.extension == "pte" }
            val tokenizer = files.firstOrNull { it.isFile && it.name == "tokenizer.model" }
            if (pte != null && tokenizer != null) return pte to tokenizer
            for (child in files) {
                if (child.isDirectory) findModelFiles(child)?.let { return it }
            }
            return null
        }
    }
}
