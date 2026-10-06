package np.opd.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import np.opd.engine.J
import okhttp3.Request

enum class Provider(val label: String) { GEMINI("Google Gemini"), OPENROUTER("OpenRouter") }

/** One model the user can pick. free = null means "depends on your key/plan" (Gemini). */
data class ModelInfo(
    val provider: Provider,
    val id: String,
    val name: String,
    val audioInput: Boolean,
    val free: Boolean?,
    val structuredOutput: Boolean,
    val jsonMode: Boolean,
    val contextLength: Int? = null
) {
    val key: String get() = provider.name + "|" + id

    fun tags(): List<String> = listOfNotNull(
        if (audioInput) "AUDIO" else null,
        when (free) { true -> "FREE"; false -> "PAID"; null -> null },
        if (structuredOutput) "JSON" else if (jsonMode) "json" else null
    )

    fun toJ(): J.Obj = J.obj(
        "provider" to provider.name, "id" to id, "name" to name, "audio" to audioInput,
        "free" to free, "structured" to structuredOutput, "json_mode" to jsonMode, "context" to contextLength
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
                contextLength = o.int("context")
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
                    structuredOutput = true,
                    jsonMode = true,
                    contextLength = m.int("inputTokenLimit")
                )
            }
            pageToken = root.str("nextPageToken")?.takeIf { it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < 5)
        out.sortedWith(compareByDescending<ModelInfo> { geminiVersion(it.id) }.thenBy { rankGemini(it.id) }.thenBy { it.id })
    }

    /** OpenRouter's catalogue is public; the key is only needed to call models. */
    suspend fun openRouter(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://openrouter.ai/api/v1/models").get().build()
        val root = Http.json(req)
        root.objs("data").mapNotNull { m ->
            val id = m.str("id") ?: return@mapNotNull null
            val pricing = m.obj("pricing")
            val promptPrice = pricing?.dbl("prompt")
            val completionPrice = pricing?.dbl("completion")
            val free = id.endsWith(":free") || (promptPrice == 0.0 && completionPrice == 0.0)
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

    /** A sensible starting choice from the Gemini list: newest stable "flash" model that takes audio. */
    fun bestGeminiDefault(models: List<ModelInfo>): ModelInfo? {
        val flash = models.filter { it.audioInput && it.id.contains("flash") && !it.id.contains("lite") }
        return flash.sortedWith(
            compareByDescending<ModelInfo> { geminiVersion(it.id) }
                .thenBy { if (it.id.contains("preview") || it.id.contains("exp")) 1 else 0 }
                .thenBy { it.id.length }
        ).firstOrNull() ?: models.firstOrNull { it.audioInput }
    }

    private fun geminiVersion(id: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun rankGemini(id: String): Int = when {
        id.contains("flash") && !id.contains("lite") -> 0
        id.contains("pro") -> 1
        id.contains("lite") -> 2
        else -> 3
    }
}
