package np.opd.assistant.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.opd.engine.J
import np.opd.engine.Prompts
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig
import org.pytorch.executorch.extension.llm.LlmCallback
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Local LLM using ExecuTorch (LlmModule).
 * Runs entirely on-device for note generation and live suggestions.
 */
class LocalLlmClient(
    private val modelFile: File,
    private val tokenizerFile: File
) : AiClient {

    private val TAG = "LocalLlmClient"
    private var module: LlmModule? = null
    private var initialized = false

    override val model: ModelInfo = ModelInfo(
        provider = Provider.LOCAL_LLM,
        id = "executorch-${modelFile.nameWithoutExtension}",
        name = "LLM ${modelFile.nameWithoutExtension} (Local)",
        audioInput = false,
        free = true,
        structuredOutput = true,
        jsonMode = true,
        contextLength = 4096
    )

    /** Initializes the ExecuTorch LlmModule. */
    private fun ensureInitialized() {
        if (initialized) return
        try {
            val config = LlmModuleConfig()
                .modulePath(modelFile.absolutePath)
                .tokenizerPath(tokenizerFile.absolutePath)
                .temperature(0.1f)
                .build()
            
            module = LlmModule(config)
            initialized = true
            Log.i(TAG, "Initialized ExecuTorch LLM from ${modelFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize local LLM", e)
            throw ApiException("Failed to initialize local LLM: ${e.message}")
        }
    }

    override suspend fun transcribe(wav: ByteArray, context: String): List<np.opd.engine.TranscriptLine>? {
        return null // Local LLM doesn't do transcription
    }

    override suspend fun generate(system: String?, parts: List<AiPart>, schema: J.Obj?): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        val module = this.module ?: throw ApiException("LLM module not initialized")

        // Build prompt from system + parts
        val prompt = buildPrompt(system, parts, schema)
        
        // Use CountDownLatch to bridge callback-based API to suspend function
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<String>()
        val errorRef = AtomicReference<Exception>()

        val callback = object : LlmCallback {
            override fun onResult(result: String) {
                resultRef.set(result)
                latch.countDown()
            }
        }

        module!!.generate(prompt, callback)
        
        // Wait for generation to complete
        val completed = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            throw ApiException("LLM generation timed out")
        }

        val error = errorRef.get()
        if (error != null) throw error

        val result = resultRef.get() ?: ""
        if (result.isBlank()) throw ApiException("LLM returned empty response")

        extractJson(result)
    }

    /** Builds a prompt from system instructions, parts, and optional JSON schema. */
    private fun buildPrompt(system: String?, parts: List<AiPart>, schema: J.Obj?): String {
        val sb = StringBuilder()
        
        if (system != null && system.isNotBlank()) {
            sb.append("SYSTEM:\n").append(system).append("\n\n")
        }
        
        if (schema != null) {
            sb.append("OUTPUT FORMAT: Reply with ONE JSON object only (no markdown, no commentary) matching this schema:\n")
            sb.append(Prompts.toJsonSchema(schema).toJson()).append("\n\n")
        }
        
        sb.append("USER:\n")
        for (part in parts) {
            when (part) {
                is AiPart.Text -> sb.append(part.text).append("\n")
                is AiPart.Wav -> sb.append("[AUDIO DATA - not supported by local LLM]\n")
            }
        }
        
        return sb.toString()
    }

    /** Extracts JSON from model output, handling reasoning tags and markdown. */
    private fun extractJson(text: String): String {
        var t = text.replace(Regex("(?s)style=\"display: none;\">.*?style=\"display: none;\">"), "")
        t = t.replace(Regex("(?s)```(?:json)?\n(.*?)\n```"), "$1")
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        return if (start >= 0 && end > start) t.substring(start, end + 1) else t
    }

    companion object {
        /** Checks if model files exist and are valid. */
        fun isValidModelDir(dir: File): Boolean {
            return dir.isDirectory &&
                dir.listFiles()?.any { it.extension == "pte" } == true &&
                File(dir, "tokenizer.model").exists()
        }

        /** Finds the .pte model file and tokenizer in a directory. */
        fun findModelFiles(dir: File): Pair<File, File>? {
            val pte = dir.listFiles()?.firstOrNull { it.extension == "pte" }
            val tokenizer = File(dir, "tokenizer.model")
            if (pte != null && tokenizer.exists()) {
                return pte to tokenizer
            }
            return null
        }
    }
}