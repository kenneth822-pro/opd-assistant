package np.opd.assistant

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import np.opd.engine.J
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Direct HTTPS calls to the Gemini API with the user's own key (no middle server).
 * Uses JSON-mode with a response schema so answers are machine-readable.
 */
class GeminiClient(private val apiKey: String) {

    sealed class Part {
        data class Text(val text: String) : Part()
        class Audio(val mimeType: String, val bytes: ByteArray) : Part()
    }

    class ApiException(message: String) : Exception(message)
    private class RetryableException(message: String) : Exception(message)

    suspend fun generate(model: String, system: String?, parts: List<Part>, schema: J.Obj?): String =
        withContext(Dispatchers.IO) {
            val body = buildBody(system, parts, schema)
            var last: Exception? = null
            for (attempt in 0 until 3) {
                try {
                    return@withContext execute(model, body)
                } catch (e: RetryableException) {
                    last = e
                    delay(2000L * (attempt + 1))
                }
            }
            throw ApiException(last?.message ?: "Request failed")
        }

    private fun buildBody(system: String?, parts: List<Part>, schema: J.Obj?): String {
        val partsJ = parts.map {
            when (it) {
                is Part.Text -> J.obj("text" to it.text)
                is Part.Audio -> J.obj(
                    "inlineData" to J.obj(
                        "mimeType" to it.mimeType,
                        "data" to Base64.encodeToString(it.bytes, Base64.NO_WRAP)
                    )
                )
            }
        }
        val generation = LinkedHashMap<String, Any?>()
        generation["temperature"] = 0.1
        if (schema != null) {
            generation["responseMimeType"] = "application/json"
            generation["responseSchema"] = schema
        }
        return J.obj(
            "systemInstruction" to system?.let { J.obj("parts" to listOf(J.obj("text" to it))) },
            "contents" to listOf(J.obj("role" to "user", "parts" to partsJ)),
            "generationConfig" to generation
        ).toJson()
    }

    private fun execute(model: String, body: String): String {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw RetryableException("Network error: ${e.message}")
        }
        response.use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val msg = errorMessage(text)
                if (resp.code == 429 || resp.code >= 500) throw RetryableException("HTTP ${resp.code}: $msg")
                throw ApiException("HTTP ${resp.code}: $msg")
            }
            val root = try { J.parse(text) as? J.Obj } catch (e: Exception) { null }
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
            return out
        }
    }

    private fun errorMessage(body: String): String = try {
        (J.parse(body) as? J.Obj)?.obj("error")?.str("message") ?: body.take(300)
    } catch (e: Exception) {
        body.take(300)
    }

    companion object {
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
    }
}
