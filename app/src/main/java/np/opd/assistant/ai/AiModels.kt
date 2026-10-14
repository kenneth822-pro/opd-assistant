package np.opd.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.opd.engine.J
import okhttp3.Request

/**
 * AI providers. Everything except Gemini speaks the OpenAI "chat completions" format,
 * so one client covers OpenRouter, OpenCode Zen, Groq and any custom server.
 */
enum class Provider(val label: String, val defaultBaseUrl: String?, val keyHelp: String) {
    GEMINI("Google Gemini", null, "Free key at aistudio.google.com/apikey"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "Key at openrouter.ai/keys. Many models, some free."),
    OPENCODE("OpenCode Zen", "https://opencode.ai/zen/v1", "Key at opencode.ai/zen. Curated models, some free for a limited time."),
    GROQ("Groq", "https://api.groq.com/openai/v1", "Key at console.groq.com/keys. Very fast; Whisper speech-to-text and open models; free tier with limits."),
    CUSTOM("Custom (OpenAI-compatible)", null, "Any server with /models and /chat/completions (e.g. a local LLM server)."),
    LOCAL_WHISPER("Local Whisper (Sherpa-ONNX)", null, "Runs offline on device. Download model in Settings."),
    LOCAL_LLM("Local LLM (ExecuTorch)", null, "Runs offline on device. Download model in Settings.");

    val openAiCompatible: Boolean get() = this != GEMINI
    val isLocal: Boolean get() = this == LOCAL_WHISPER || this == LOCAL_LLM
}

/** One model the user can pick. free = null means "depends on your key/plan". */
data class ModelInfo(
    val provider: Provider,
    val id: String,
    val name: String,
    val audioInput: Boolean,
    val free: Boolean?,
    val structuredOutput: Boolean,
    val jsonMode: Boolean,
    val contextLength: Int? = null,
    /** Speech-to-text model used through /audio/transcriptions (e.g. Whisper). */
    val transcriptionApi: Boolean = false
) {
    val key: String get() = provider.name + "|" + id

    fun tags(): List<String> = listOfNotNull(
        if (transcriptionApi) "SPEECH-TO-TEXT" else if (audioInput) "AUDIO" else null,
        when (free) { true -> "FREE"; false -> "PAID"; null -> null },
        if (structuredOutput) "JSON" else null
    )

    fun toJ(): J.Obj = J.obj(
        "provider" to provider.name, "id" to id, "name" to name, "audio" to audioInput,
        "free" to free, "structured" to structuredOutput, "json_mode" to jsonMode, "context" to contextLength,
        "transcription" to transcriptionApi
    )

    companion object {
        fun fromJ(o: J.Obj): ModelInfo? = try {
            ModelInfo(
                provider = Provider.valueOf(o.req("provider")),
                id = o.req("id"),
                name = o.strOr("name", o.req("id")),
                audioInput = o.bool("audio") ?: false,
                free = o.bool("free"),
                structuredOutput = o.bool("structured") ?: false,
                jsonMode = o.bool("json_mode") ?: false,
                contextLength = o.int("context"),
                transcriptionApi = o.bool("transcription") ?: false
            )
        } catch (e: Exception) {
            null
        }

        fun listToJson(list: List<ModelInfo>): String = J.Arr(list.map { it.toJ() }).toJson()

        fun listFromJson(text: String?): List<ModelInfo> = try {
            if (text.isNullOrBlank()) emptyList()
            else (J.parse(text) as? J.Arr)?.items?.mapNotNull { (it as? J.Obj)?.let(::fromJ) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Downloads the live model lists, so the app never depends on hard-coded model names. */
object ModelDirectory {

    private val geminiExclude = listOf("embedding", "imagen", "veo", "aqa", "tts", "live", "native-audio", "image", "robotics", "computer-use")

    suspend fun gemini(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ApiException("Add your Gemini API key first.")
        val out = mutableListOf<ModelInfo>()
        var pageToken: String? = null
        var pages = 0
        do {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            val req = Request.Builder().url(url).header("x-goog-api-key", apiKey.trim()).get().build()
            val root = Http.json(req)
            for (m in root.objs("models")) {
                val id = m.strOr("name", "").removePrefix("models/")
                if (id.isEmpty()) continue
                if ("generateContent" !in m.strs("supportedGenerationMethods")) continue
                if (geminiExclude.any { id.contains(it) }) continue
                out += ModelInfo(
                    provider = Provider.GEMINI,
                    id = id,
                    name = m.strOr("displayName", id),
                    audioInput = id.startsWith("gemini"),
                    free = null,
                    structuredOutput = !id.startsWith("gemma"),
                    jsonMode = !id.startsWith("gemma"),
                    contextLength = m.int("inputTokenLimit")
                )
            }
            pageToken = root.str("nextPageToken")?.takeIf { it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < 5)
        out.sortedWith(compareByDescending<ModelInfo> { geminiVersion(it.id) }.thenBy { rankGemini(it.id) }.thenBy { it.id })
    }

    /** OpenRouter's catalogue is public and rich (prices, input types, supported parameters). */
    suspend fun openRouter(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://openrouter.ai/api/v1/models").get().build()
        val root = Http.json(req)
        root.objs("data").mapNotNull { m ->
            val id = m.str("id") ?: return@mapNotNull null
            val pricing = m.obj("pricing")
            val free = id.endsWith(":free") || (pricing != null && pricing.dbl("prompt") == 0.0 && pricing.dbl("completion") == 0.0)
            val inputs = m.obj("architecture")?.strs("input_modalities") ?: emptyList()
            val outputs = m.obj("architecture")?.strs("output_modalities") ?: listOf("text")
            if ("text" !in outputs) return@mapNotNull null
            val params = m.strs("supported_parameters")
            ModelInfo(
                provider = Provider.OPENROUTER,
                id = id,
                name = m.strOr("name", id),
                audioInput = "audio" in inputs,
                free = free,
                structuredOutput = "structured_outputs" in params,
                jsonMode = "response_format" in params,
                contextLength = m.int("context_length")
            )
        }.sortedWith(compareByDescending<ModelInfo> { it.free == true }.thenBy { it.name.lowercase() })
    }

    /** Standard OpenAI-style GET /models (OpenCode Zen, Groq, custom servers). */
    suspend fun openAiCompatible(provider: Provider, baseUrl: String, apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw ApiException("Enter the server address first.")
        val b = Request.Builder().url(baseUrl.trimEnd('/') + "/models").get()
        if (apiKey.isNotBlank()) b.header("Authorization", "Bearer ${apiKey.trim()}")
        val root = Http.json(b.build())
        root.objs("data").mapNotNull { m ->
            val id = m.str("id") ?: return@mapNotNull null
            if (m.bool("active") == false) return@mapNotNull null
            val lower = id.lowercase()
            if (listOf("tts", "playai", "orpheus", "embed", "guard", "image").any { lower.contains(it) }) return@mapNotNull null
            val stt = listOf("whisper", "transcribe", "speech-to-text", "distil").any { lower.contains(it) }
            val name = m.str("name") ?: m.str("display_name") ?: id
            ModelInfo(
                provider = provider,
                id = id,
                name = name,
                audioInput = stt || lower.contains("audio"),
                free = if (lower.contains("free") || name.lowercase().contains("free")) true else null,
                structuredOutput = false,
                jsonMode = provider == Provider.GROQ,
                contextLength = m.int("context_window") ?: m.int("context_length"),
                transcriptionApi = stt
            )
        }.sortedWith(compareByDescending<ModelInfo> { it.transcriptionApi }.thenByDescending { it.free == true }.thenBy { it.id })
    }

    /** A sensible starting choice from the Gemini list: newest stable "flash" model that takes audio. */
    fun bestGeminiDefault(models: List<ModelInfo>, excludeId: String? = null): ModelInfo? {
        val pool = models.filter { it.audioInput && it.id != excludeId }
        val flash = pool.filter { it.id.contains("flash") }
        return flash.sortedWith(
            compareBy<ModelInfo> { if (it.id.contains("lite")) 1 else 0 }
                .thenByDescending { geminiVersion(it.id) }
                .thenBy { if (it.id.contains("preview") || it.id.contains("exp")) 1 else 0 }
                .thenBy { it.id.length }
        ).firstOrNull() ?: pool.firstOrNull()
    }

    private fun geminiVersion(id: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun rankGemini(id: String): Int = when {
        id.contains("flash") && !id.contains("lite") -> 0
        id.contains("pro") -> 1
        id.contains("lite") -> 2
        else -> 3
    }

    /** Local models available on device (downloaded or bundled). */
    suspend fun localModels(context: android.content.Context): List<ModelInfo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ModelInfo>()
        val modelsDir = File(context.filesDir, "models")
        
        // Local Whisper models (Sherpa-ONNX)
        val sttDir = File(modelsDir, "stt")
        if (sttDir.exists()) {
            sttDir.listFiles()?.forEach { modelDir ->
                if (modelDir.isDirectory) {
                    val encoder = File(modelDir, "encoder.int8.onnx")
                    val decoder = File(modelDir, "decoder.int8.onnx")
                    val tokens = File(modelDir, "tokens.txt")
                    if (encoder.exists() && decoder.exists() && tokens.exists()) {
                        val isMultilingual = !modelDir.name.contains(".en")
                        out += ModelInfo(
                            provider = Provider.LOCAL_WHISPER,
                            id = "sherpa-onnx-${modelDir.name}",
                            name = "Whisper ${modelDir.name.replace("sherpa-onnx-", "").uppercase()} (Local)",
                            audioInput = true,
                            free = true,
                            structuredOutput = false,
                            jsonMode = false,
                            transcriptionApi = true,
                            contextLength = null
                        )
                    }
                }
            }
        }
        
        // Bundled Whisper tiny.en (if present in assets)
        try {
            val assets = context.assets.list("sherpa-onnx-whisper-tiny.en") ?: emptyArray()
            if (assets.isNotEmpty()) {
                out += ModelInfo(
                    provider = Provider.LOCAL_WHISPER,
                    id = "sherpa-onnx-whisper-tiny.en-bundled",
                    name = "Whisper Tiny.en (Bundled, Local)",
                    audioInput = true,
                    free = true,
                    structuredOutput = false,
                    jsonMode = false,
                    transcriptionApi = true,
                    contextLength = null
                )
            }
        } catch (e: Exception) {
            // Asset not bundled
        }

        // Local LLM models (ExecuTorch .pte files)
        val llmDir = File(modelsDir, "llm")
        if (llmDir.exists()) {
            llmDir.listFiles()?.filter { it.extension == "pte" }?.forEach { file ->
                val modelName = file.nameWithoutExtension
                out += ModelInfo(
                    provider = Provider.LOCAL_LLM,
                    id = "executorch-$modelName",
                    name = "LLM $modelName (Local)",
                    audioInput = false,
                    free = true,
                    structuredOutput = true,
                    jsonMode = true,
                    contextLength = 4096
                )
            }
        }

        out.sortedBy { it.id }
    }
}
