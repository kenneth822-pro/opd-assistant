package np.opd.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import np.opd.assistant.ai.ModelInfo
import np.opd.assistant.ai.Provider
import np.opd.engine.J
import java.security.KeyStore
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App settings. API keys are encrypted with an AES key held in the Android Keystore
 * (hardware-backed on most phones); they never leave the device except to call the provider.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("opd_settings", Context.MODE_PRIVATE)

    /** Storage names; Gemini and OpenRouter keep the names used by earlier builds. */
    private fun keyName(p: Provider) = when (p) {
        Provider.GEMINI -> "gemini_key_enc"
        Provider.OPENROUTER -> "openrouter_key_enc"
        else -> "key_${p.name.lowercase()}_enc"
    }

    fun keyFor(p: Provider): String {
        val stored = prefs.getString(keyName(p), null)
            ?: if (p == Provider.GEMINI) prefs.getString("api_key_enc", null) else null
        return stored?.let { decrypt(it) } ?: ""
    }

    fun setKey(p: Provider, value: String) {
        val e = prefs.edit()
        if (value.isBlank()) e.remove(keyName(p)) else e.putString(keyName(p), encrypt(value.trim()))
        if (p == Provider.GEMINI) e.remove("api_key_enc")
        e.apply()
    }

    fun hasKey(p: Provider): Boolean = keyFor(p).isNotBlank()

    /** Server address for a custom OpenAI-compatible server (e.g. http://192.168.1.10:1234/v1). */
    var customBaseUrl: String
        get() = prefs.getString("custom_base_url", "") ?: ""
        set(value) = prefs.edit().putString("custom_base_url", normalizeBaseUrl(value)).apply()

    fun baseUrlFor(p: Provider): String = when (p) {
        Provider.CUSTOM -> customBaseUrl
        else -> p.defaultBaseUrl ?: ""
    }

    fun customServerAllowed(): Boolean = isAllowedServerUrl(customBaseUrl, allowInsecureLocalServer)

    /** Model that listens to the audio (must accept audio, or be a speech-to-text model). */
    var speechModel: ModelInfo?
        get() = readModel("speech_model")
        set(value) = writeModel("speech_model", value)

    /** Model that turns transcripts into structured notes. Null = the speech model does this too. */
    var analysisModel: ModelInfo?
        get() = readModel("analysis_model")
        set(value) = writeModel("analysis_model", value)

    /** Used automatically when a main model is busy (HTTP 503 / 429) or retired. */
    var backupModel: ModelInfo?
        get() = readModel("backup_model")
        set(value) = writeModel("backup_model", value)

    private fun modelsName(p: Provider) = when (p) {
        Provider.GEMINI -> "gemini_models"
        Provider.OPENROUTER -> "openrouter_models"
        else -> "models_${p.name.lowercase()}"
    }

    fun models(p: Provider): List<ModelInfo> = ModelInfo.listFromJson(prefs.getString(modelsName(p), null))

    fun setModels(p: Provider, list: List<ModelInfo>) =
        prefs.edit().putString(modelsName(p), ModelInfo.listToJson(list)).apply()

    var segmentSeconds: Int
        get() = prefs.getInt("segment_seconds", 15).coerceIn(8, 60)
        set(value) = prefs.edit().putInt("segment_seconds", value.coerceIn(8, 60)).apply()

    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean("disclaimer_v1", false)
        set(value) = prefs.edit().putBoolean("disclaimer_v1", value).apply()

    /** Clinical focus used to shape the AI instructions. */
    var persona: ClinicalPersona
        get() = try {
            ClinicalPersona.valueOf(prefs.getString("persona", null) ?: ClinicalPersona.GENERAL_OPD.name)
        } catch (e: Exception) {
            ClinicalPersona.GENERAL_OPD
        }
        set(value) = prefs.edit().putString("persona", value.name).apply()

    var consent: ConsentRecord
        get() = try {
            ConsentRecord(
                status = ConsentStatus.valueOf(prefs.getString("consent_status", ConsentStatus.NOT_REQUESTED.name)!!),
                externalAiAllowed = prefs.getBoolean("consent_external_ai", false),
                localOnly = prefs.getBoolean("consent_local_only", false),
                timestampMs = prefs.getLong("consent_timestamp", 0L).takeIf { it > 0L }
            )
        } catch (_: Exception) {
            ConsentRecord()
        }
        set(value) = prefs.edit()
            .putString("consent_status", value.status.name)
            .putBoolean("consent_external_ai", value.externalAiAllowed && !value.localOnly)
            .putBoolean("consent_local_only", value.localOnly && !value.externalAiAllowed)
            .putLong("consent_timestamp", value.timestampMs ?: 0L)
            .apply()

    var doctorIdentity: String
        get() = prefs.getString("doctor_identity", "")?.trim().orEmpty()
        set(value) = prefs.edit().putString("doctor_identity", value.trim().take(120)).apply()

    var audioRetentionPolicy: AudioRetentionPolicy
        get() = try {
            AudioRetentionPolicy.valueOf(prefs.getString("audio_retention", AudioRetentionPolicy.DELETE_AFTER_CONSULTATION.name)!!)
        } catch (_: Exception) {
            AudioRetentionPolicy.DELETE_AFTER_CONSULTATION
        }
        set(value) = prefs.edit().putString("audio_retention", value.name).apply()

    var audioRetentionHours: Int
        get() = prefs.getInt("audio_retention_hours", 24).coerceIn(1, 72)
        set(value) = prefs.edit().putInt("audio_retention_hours", value.coerceIn(1, 72)).apply()

    var developerMode: Boolean
        get() = prefs.getBoolean("developer_mode", false)
        set(value) = prefs.edit().putBoolean("developer_mode", value).apply()

    /** Enabled by default: identifiers are removed from text prompts before external requests. */
    var privacySafePrompts: Boolean
        get() = prefs.getBoolean("privacy_safe_prompts", true)
        set(value) = prefs.edit().putBoolean("privacy_safe_prompts", value).apply()

    /** Explicit opt-in required for HTTP custom servers. HTTPS remains the default. */
    var allowInsecureLocalServer: Boolean
        get() = prefs.getBoolean("allow_insecure_local_server", false)
        set(value) = prefs.edit().putBoolean("allow_insecure_local_server", value).apply()

    private fun readModel(key: String): ModelInfo? = try {
        prefs.getString(key, null)?.let { (J.parse(it) as? J.Obj)?.let(ModelInfo::fromJ) }
    } catch (e: Exception) {
        null
    }

    private fun writeModel(key: String, m: ModelInfo?) {
        if (m == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, m.toJ().toJson()).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ct)
    }

    private fun decrypt(stored: String): String? = try {
        val parts = stored.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, unb64(parts[0])))
        String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val ALIAS = "opd_settings_key"

        /**
         * Normalizes a user-entered local/LAN server address so it works without trial and error:
         *  - "127.0.0.1:8080"      -> "http://127.0.0.1:8080/v1"
         *  - "192.168.1.20:11434"  -> "http://192.168.1.20:11434/v1"
         *  - "localhost:1234/v1"   -> "http://localhost:1234/v1"
         * An empty string stays empty; an explicit scheme (http/https) is preserved.
         */
        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return ""
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
            u = u.trimEnd('/')
            if (!u.endsWith("/v1")) u += "/v1"
            return u
        }

        /** HTTPS is always allowed; cleartext is restricted to an explicit private/LAN opt-in. */
        fun isAllowedServerUrl(raw: String, allowInsecureLocalServer: Boolean): Boolean {
            val uri = try { URI(raw.trim()) } catch (_: Exception) { return false }
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme == "https") return true
            if (scheme != "http" || !allowInsecureLocalServer) return false
            val host = uri.host?.lowercase() ?: return false
            return host == "localhost" || host == "127.0.0.1" || host == "::1" ||
                host.startsWith("10.") || host.startsWith("192.168.") ||
                host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true
        }
    }
}
