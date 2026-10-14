package np.opd.assistant.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import np.opd.engine.J
import np.opd.engine.Prompts
import np.opd.engine.TranscriptLine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * @param transient the provider was busy / rate-limited / unreachable even after retries (try a backup model).
 * @param modelMissing the model name is not available (retired or wrong).
 */
class ApiException(message: String, val transient: Boolean = false, val modelMissing: Boolean = false) : Exception(message)
internal class RetryableException(message: String, val retryAfterMs: Long?) : Exception(message)

sealed class AiPart {
    data class Text(val text: String) : AiPart()
    /** 16 kHz mono 16-bit WAV bytes. */
    class Wav(val bytes: ByteArray) : AiPart()
}

/** One model on one provider. */
interface AiClient {
    val model: ModelInfo
    suspend fun generate(system: String?, parts: List<AiPart>, schema: J.Obj?): String

    /** Direct speech-to-text (Whisper-style models). Returns null for chat models, which transcribe via [generate]. */
    suspend fun transcribe(wav: ByteArray, context: String): List<TranscriptLine>? = null
}

internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    /** Executes and returns the body text; maps failures to ApiException / RetryableException. */
    fun text(req: Request): String {
        val response = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw RetryableException("Network error: ${e.message}", null)
        }
        response.use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val msg = errorMessage(body)
                if (resp.code == 429 || resp.code >= 500) {
                    val retryAfter = resp.header("Retry-After")?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                    throw RetryableException("HTTP ${resp.code}: $msg", retryAfter)
                }
                throw ApiException("HTTP ${resp.code}: $msg", modelMissing = resp.code == 404)
            }
            return body
        }
    }

    fun json(req: Request): J.Obj {
        val body = try {
            text(req)
        } catch (e: RetryableException) {
            throw ApiException(e.message ?: "Request failed", transient = true)
        }
        return try { J.parse(body) as? J.Obj } catch (e: Exception) { null }
            ?: throw ApiException("Unreadable response from server")
    }

    fun errorMessage(body: String): String = try {
        val o = J.parse(body) as? J.Obj
        val e = o?.obj("error")
        e?.str("message")?.let { msg ->
            val raw = e.obj("metadata")?.str("raw") // OpenRouter: the upstream provider's message
            if (raw != null && raw != msg) "$msg (${raw.take(200)})" else msg
        } ?: body.take(300)
    } catch (e: Exception) {
        body.take(300)
    }

    /** Up to [attempts] tries with growing waits (2 s, 5 s, 10 s), honouring Retry-After (max 20 s). */
    suspend fun <T> withRetry(attempts: Int = 4, block: () -> T): T {
        val waits = longArrayOf(2000, 5000, 10000)
        val n = attempts.coerceIn(1, 4)
        var last: RetryableException? = null
        for (attempt in 0 until n) {
            try {
                return block()
            } catch (e: RetryableException) {
                last = e
                if (attempt < n - 1) delay(minOf(20_000L, maxOf(e.retryAfterMs ?: 0L, waits[attempt])))
            }
        }
        throw ApiException((last?.message ?: "Request failed") + " (still failing after $n tries)", transient = true)
    }
}

private val JSON = "application/json; charset=utf-8".toMediaType()

private fun schemaInstruction(schema: J.Obj): String =
    "\n\nOUTPUT FORMAT: reply with ONE JSON object only (no markdown, no commentary) that matches this JSON Schema:\n" +
        Prompts.toJsonSchema(schema).toJson()

/* ============================== Google Gemini ============================== */

/**
 * @param attempts tries per request on busy/network errors (fewer when a backup model is available).
 * @param fast live use: switch off "thinking" on Flash models, which cuts several seconds per request.
 */
class GeminiClient(
    private val apiKey: String,
    override val model: ModelInfo,
    private val attempts: Int = 4,
    private val fast: Boolean = false
) : AiClient {

    override suspend fun generate(system: String?, parts: List<AiPart>, schema: J.Obj?): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw ApiException("No Gemini API key saved (Settings).")
            // Some models (e.g. Gemma, some special-purpose models) reject system instructions or JSON mode.
            // We learn that from the error, retry in a compatible way, and remember it for this model.
            var useSystem = system != null && model.id !in noSystem && !model.id.startsWith("gemma")
            var useSchema = schema != null && model.id !in noJson && model.structuredOutput
            var thinkingOff = fast && model.id.startsWith("gemini") && model.id.contains("flash") && model.id !in noThinkingOff
            for (round in 0 until 4) {
                try {
                    return@withContext call(system, parts, schema, useSystem, useSchema, thinkingOff)
                } catch (e: ApiException) {
                    if (e.transient) throw e
                    val msg = (e.message ?: "").lowercase()
                    if (thinkingOff && (msg.contains("thinking") || msg.contains("budget"))) {
                        thinkingOff = false; noThinkingOff += model.id; continue
                    }
                    if (useSystem && (msg.contains("developer instruction") || msg.contains("system instruction") || msg.contains("system_instruction"))) {
                        useSystem = false; noSystem += model.id; continue
                    }
                    if (useSchema && (msg.contains("json mode") || msg.contains("response_schema") || msg.contains("responseschema") ||
                            msg.contains("response_mime_type") || msg.contains("responsemimetype") || msg.contains("structured output"))) {
                        useSchema = false; noJson += model.id; continue
                    }
                    throw e
                }
            }
            throw ApiException("Model ${model.id} could not be used with this app.")
        }

    private suspend fun call(system: String?, parts: List<AiPart>, schema: J.Obj?, useSystem: Boolean, useSchema: Boolean, thinkingOff: Boolean): String {
        // Instructions that cannot go in systemInstruction are placed at the top of the message instead.
        var inline = ""
        if (system != null && !useSystem) inline += "INSTRUCTIONS:\n$system"
        if (schema != null && !useSchema) inline += schemaInstruction(schema)
        val partsJ = mutableListOf<J>()
        if (inline.isNotBlank()) partsJ += J.obj("text" to inline.trim() + "\n\n---\n")
        parts.forEach {
            partsJ += when (it) {
                is AiPart.Text -> J.obj("text" to it.text)
                is AiPart.Wav -> J.obj("inlineData" to J.obj("mimeType" to "audio/wav", "data" to Base64.encodeToString(it.bytes, Base64.NO_WRAP)))
            }
        }
        val generation = LinkedHashMap<String, Any?>()
        generation["temperature"] = 0.1
        if (thinkingOff) generation["thinkingConfig"] = mapOf("thinkingBudget" to 0)
        if (schema != null && useSchema) {
            generation["responseMimeType"] = "application/json"
            generation["responseSchema"] = schema
        }
        val body = J.obj(
            "systemInstruction" to if (useSystem && system != null) J.obj("parts" to listOf(J.obj("text" to system))) else null,
            "contents" to listOf(J.obj("role" to "user", "parts" to partsJ)),
            "generationConfig" to generation
        ).toJson()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${model.id}:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .post(body.toRequestBody(JSON))
            .build()
        return Http.withRetry(attempts) {
            val text = Http.text(req)
            val root = try { J.parse(text) as? J.Obj } catch (e: Exception) { null }
                ?: throw ApiException("Unreadable response from Gemini")
            val candidate = root.objs("candidates").firstOrNull()
            val out = candidate?.obj("content")?.objs("parts")
                ?.filter { it.bool("thought") != true }
                ?.mapNotNull { it.str("text") }
                ?.joinToString("") ?: ""
            if (out.isBlank()) {
                val reason = candidate?.str("finishReason") ?: root.obj("promptFeedback")?.str("blockReason") ?: "unknown"
                throw ApiException("Empty answer from ${model.id} (reason: $reason)")
            }
            out
        }
    }

    companion object {
        private val noSystem: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        private val noJson: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        private val noThinkingOff: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        fun resetModelCapabilities(modelId: String? = null) {
            if (modelId != null) {
                noSystem.remove(modelId)
                noJson.remove(modelId)
                noThinkingOff.remove(modelId)
            } else {
                noSystem.clear()
                noJson.clear()
                noThinkingOff.clear()
            }
        }
    }
}

/* ============================== OpenAI-compatible (OpenRouter, OpenCode Zen, Groq, custom) ============================== */

class OpenAiCompatClient(
    private val baseUrl: String,
    private val apiKey: String,
    override val model: ModelInfo,
    private val attempts: Int = 4
) : AiClient {

    private val base = baseUrl.trimEnd('/')

    override suspend fun generate(system: String?, parts: List<AiPart>, schema: J.Obj?): String =
        withContext(Dispatchers.IO) {
            if (model.transcriptionApi) throw ApiException("${model.id} is a speech-to-text model: use it only as the speech model.")
            if (base.isBlank()) throw ApiException("No server address set for ${model.provider.label}.")
            // Many models ignore response_format, so the schema is always repeated in the instructions too.
            val sys = buildString {
                if (system != null) append(system)
                if (schema != null) append(schemaInstruction(schema))
            }
            val content = parts.map {
                when (it) {
                    is AiPart.Text -> J.obj("type" to "text", "text" to it.text)
                    is AiPart.Wav -> J.obj(
                        "type" to "input_audio",
                        "input_audio" to J.obj("data" to Base64.encodeToString(it.bytes, Base64.NO_WRAP), "format" to "wav")
                    )
                }
            }
            val responseFormat: J.Obj? = when {
                schema == null -> null
                model.structuredOutput -> J.obj("type" to "json_schema", "json_schema" to J.obj("name" to "output", "strict" to false, "schema" to Prompts.toJsonSchema(schema)))
                model.jsonMode -> J.obj("type" to "json_object")
                else -> null
            }

            fun request(withFormat: Boolean, systemAsUser: Boolean): Request {
                val messages = mutableListOf<J>()
                if (sys.isNotBlank() && !systemAsUser) messages += J.obj("role" to "system", "content" to sys)
                val userContent: List<J> = if (sys.isNotBlank() && systemAsUser) listOf(J.obj("type" to "text", "text" to "INSTRUCTIONS:\n$sys\n\n---\n")) + content else content
                messages += J.obj("role" to "user", "content" to userContent)
                val body = J.obj(
                    "model" to model.id,
                    "messages" to messages,
                    "temperature" to 0.1,
                    "response_format" to if (withFormat) responseFormat else null
                ).toJson()
                val b = Request.Builder()
                    .url("$base/chat/completions")
                    .post(body.toRequestBody(JSON))
                if (apiKey.isNotBlank()) b.header("Authorization", "Bearer ${apiKey.trim()}")
                if (model.provider == Provider.OPENROUTER) {
                    b.header("HTTP-Referer", "https://github.com/kenneth822-pro/opd-assistant")
                    b.header("X-Title", "OPD Assistant prototype")
                }
                return b.build()
            }

            Http.withRetry(attempts) {
                var withFormat = responseFormat != null
                var systemAsUser = false
                var text: String? = null
                for (round in 0 until 3) {
                    try {
                        text = Http.text(request(withFormat, systemAsUser))
                        break
                    } catch (e: ApiException) {
                        if (e.transient || e.modelMissing) throw e
                        val msg = (e.message ?: "").lowercase()
                        if (msg.startsWith("http 401") || msg.startsWith("http 403")) throw e
                        val isFormatError = msg.startsWith("http 400") || msg.startsWith("http 422") ||
                            msg.contains("response_format") || msg.contains("json_schema") ||
                            msg.contains("schema") || msg.contains("structured output") ||
                            msg.contains("format")
                        // Fall back step by step: drop response_format, then move the system prompt into the message.
                        if (withFormat && isFormatError) { withFormat = false; continue }
                        if (!systemAsUser && (msg.contains("system") || msg.contains("developer instruction"))) { systemAsUser = true; continue }
                        throw e
                    }
                }
                val root = try { J.parse(text ?: "") as? J.Obj } catch (e: Exception) { null }
                    ?: throw ApiException("Unreadable response from ${model.provider.label}")
                root.obj("error")?.let { throw ApiException("${model.provider.label}: " + (it.str("message") ?: "error")) }
                val c = root.objs("choices").firstOrNull()?.obj("message")?.get("content")
                val out = when (c) {
                    is J.Str -> c.v
                    is J.Arr -> c.items.mapNotNull { (it as? J.Obj)?.str("text") }.joinToString("")
                    else -> ""
                }
                if (out.isBlank()) throw ApiException("Empty answer from ${model.id}")
                out
            }
        }

    /** Phrases Whisper is known to invent on silence or noise. */
    private val hallucination = Regex("^\\W*(thank you|thanks)( very much)?( for watching)?\\W*$|subscribe|subtitles by|amara\\.org|ご視聴|字幕")

    override suspend fun transcribe(wav: ByteArray, context: String): List<TranscriptLine>? {
        if (!model.transcriptionApi) return null
        return withContext(Dispatchers.IO) {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "segment.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", model.id)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
            if (context.isNotBlank()) form.addFormDataPart("prompt", context.takeLast(400))
            val b = Request.Builder().url("$base/audio/transcriptions").post(form.build())
            if (apiKey.isNotBlank()) b.header("Authorization", "Bearer ${apiKey.trim()}")
            val req = b.build()
            Http.withRetry(attempts) {
                val root = try { J.parse(Http.text(req)) as? J.Obj } catch (e: ApiException) { throw e } catch (e: RetryableException) { throw e } catch (e: Exception) { null }
                    ?: throw ApiException("Unreadable transcription from ${model.id}")
                val segs = root.objs("segments")
                val lines = if (segs.isNotEmpty()) {
                    segs.filter { (it.dbl("no_speech_prob") ?: 0.0) < 0.6 }
                        .map { TranscriptLine("unknown", it.strOr("text", "").trim(), ((it.dbl("start") ?: 0.0) * 1000).toLong()) }
                } else {
                    listOfNotNull(root.str("text")?.trim()?.let { TranscriptLine("unknown", it, 0) })
                }
                lines.filter { it.text.isNotEmpty() && !hallucination.containsMatchIn(it.text.lowercase()) }
            }
        }
    }
}
