package np.opd.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import np.opd.assistant.ai.ModelInfo
import np.opd.assistant.ai.Provider
import np.opd.engine.J
import java.security.KeyStore
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
        set(value) = prefs.edit().putString("custom_base_url", value.trim()).apply()

    fun baseUrlFor(p: Provider): String = when (p) {
        Provider.CUSTOM -> customBaseUrl
        else -> p.defaultBaseUrl ?: ""
    }

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
    }
}
