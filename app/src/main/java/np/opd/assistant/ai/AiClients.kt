package np.opd.assistant.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import np.opd.engine.J
import np.opd.engine.Prompts
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)
internal class RetryableException(message: String) : Exception(message)

sealed class AiPart {
    data class Text(val text: String) : AiPart()
    /** 16 kHz mono 16-bit WAV bytes. */
    class Wav(val bytes: ByteArray) : AiPart()
}

/** One model on one provider. */
interface AiClient {
    val model: ModelInfo
    suspend fun generate(system: String?, parts: List<AiPart>, schema: J.Obj?): String
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
            throw RetryableException("Network error: ${e.message}")
        }
        response.use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val msg = errorMessage(body)
                if (resp.code == 429 || resp.code >= 500) throw RetryableException("HTTP ${resp.code}: $msg")
                throw ApiException("HTTP ${resp.code}: $msg")
            }
            return body
        }
    }

    fun json(req: Request): J.Obj {
        val body = try {
            text(req)
        } catch (e: RetryableException) {
            throw ApiException(e.message ?: "Request failed")
        }
        return try { J.parse(body) as? J.Obj } catch (e: Exception) { null }
            ?: throw ApiException("Unreadable response from server")
    }

    fun errorMessage(body: String): String = try {
        val o = J.parse(body) as? J.Obj
        val e = o?.obj("error")
        e?.str("message")?.let { msg ->
            // OpenRouter puts the upstream provider's message in metadata.raw
            val raw = e.obj("metadata")?.str("raw")
            if (raw != null && raw != msg) "$msg (${raw.take(200)})" else msg
        } ?: body.take(300)
    } catch (e: Exception) {
        body.take(300)
    }

    suspend fun <T> withRetry(block: () -> T): T {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return block()
            } catch (e: RetryableException) {
                last = e
                delay(2500L * (attempt + 1))
            }
        }
        throw ApiException(last?.message ?: "Request failed")
    }
}

/* ============================== Google Gemini ============================== */

class GeminiClient(private val apiKey: String, override val model: ModelInfo) : AiClient {

    override suspend fun generate(system: String?, parts: List<AiPart>, schema: J.Obj?): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw ApiException("No Gemini API key saved (Settings).")
            val partsJ = parts.map {
                when (it) {
                    is AiPart.Text -> J.obj("text" to it.text)
                    is AiPart.Wav -> J.obj("inlineData" to J.obj("mimeType" to "audio/wav", "data" to Base64.encodeToString(it.bytes, Base64.NO_WRAP)))
                }
            }
            val generation = LinkedHashMap<String, Any?>()
            generation["temperature"] = 0.1
            if (schema != null) {
                generation["responseMimeType"] = "application/json"
                generation["responseSchema"] = schema
            }
            val body = J.obj(
                "systemInstruction" to system?.let { J.obj("parts" to listOf(J.obj("text" to it))) },
                "contents" to listOf(J.obj("role" to "user", "parts" to partsJ)),
                "generationConfig" to generation
            ).toJson()
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/${model.id}:generateContent")
                .header("x-goog-api-key", apiKey.trim())
                .post(body.toRequestBody(JSON))
                .build()
            Http.withRetry {
                val root = try { J.parse(Http.text(req)) as? J.Obj } catch (e: RetryableException) { throw e } catch (e: ApiException) { throw e } catch (e: Exception) { null }
                    ?: throw ApiException("Unreadable response from Gemini")
                val candidate = root.objs("candidates").firstOrNull()
                val out = candidate?.obj("content")?.objs("parts")
                    ?.filter { it.bool("thought") != true }
                    ?.mapNotNull { it.str("text") }
                    ?.joinToString("") ?: ""
                if (out.isBlank()) {
                    val reason = candidate?.str("finishReason") ?: root.obj("promptFeedback")?.str("blockReason") ?: "unknown"
                    throw ApiException("Empty answer from Gemini (reason: $reason)")
                }
                out
            }
        }
}

/* ============================== OpenRouter ============================== */

class OpenRouterClient(private val apiKey: String, override val model: ModelInfo) : AiClient {

    override suspend fun generate(system: String?, parts: List<AiPart>, schema: J.Obj?): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw ApiException("No OpenRouter API key saved (Settings).")
            val std = schema?.let { Prompts.toJsonSchema(it) }
            // Many free models ignore response_format, so the schema is always repeated in the instructions too.
            val sys = buildString {
                if (system != null) append(system)
                if (std != null) {
                    append("\n\nOUTPUT FORMAT: reply with ONE JSON object only (no markdown, no commentary) that matches this JSON Schema:\n")
                    append(std.toJson())
                }
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
            val messages = mutableListOf<J>()
            if (sys.isNotBlank()) messages += J.obj("role" to "system", "content" to sys)
            messages += J.obj("role" to "user", "content" to content)

            val responseFormat: J.Obj? = when {
                std == null -> null
                model.structuredOutput -> J.obj("type" to "json_schema", "json_schema" to J.obj("name" to "output", "strict" to false, "schema" to std))
                model.jsonMode -> J.obj("type" to "json_object")
                else -> null
            }

            fun request(withFormat: Boolean): Request {
                val body = J.obj(
                    "model" to model.id,
                    "messages" to messages,
                    "temperature" to 0.1,
                    "response_format" to if (withFormat) responseFormat else null
                ).toJson()
                return Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .header("HTTP-Referer", "https://github.com/kenneth822-pro/opd-assistant")
                    .header("X-Title", "OPD Assistant prototype")
                    .post(body.toRequestBody(JSON))
                    .build()
            }

            Http.withRetry {
                val text = try {
                    Http.text(request(withFormat = responseFormat != null))
                } catch (e: ApiException) {
                    // Some providers reject response_format with HTTP 400: try once without it.
                    if (responseFormat != null && e.message?.startsWith("HTTP 400") == true) Http.text(request(withFormat = false)) else throw e
                }
                val root = try { J.parse(text) as? J.Obj } catch (e: Exception) { null }
                    ?: throw ApiException("Unreadable response from OpenRouter")
                root.obj("error")?.let { throw ApiException("OpenRouter: " + (it.str("message") ?: "error")) }
                val msg = root.objs("choices").firstOrNull()?.obj("message")
                val c = msg?.get("content")
                val out = when (c) {
                    is J.Str -> c.v
                    is J.Arr -> c.items.mapNotNull { (it as? J.Obj)?.str("text") }.joinToString("")
                    else -> ""
                }
                if (out.isBlank()) throw ApiException("Empty answer from ${model.id}")
                out
            }
        }
}

private val JSON = "application/json; charset=utf-8".toMediaType()
