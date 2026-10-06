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

    var geminiKey: String
        get() = (prefs.getString("gemini_key_enc", null) ?: prefs.getString("api_key_enc", null))?.let { decrypt(it) } ?: ""
        set(value) = prefs.edit().putString("gemini_key_enc", encrypt(value.trim())).remove("api_key_enc").apply()

    var openRouterKey: String
        get() = prefs.getString("openrouter_key_enc", null)?.let { decrypt(it) } ?: ""
        set(value) = prefs.edit().putString("openrouter_key_enc", encrypt(value.trim())).apply()

    fun keyFor(p: Provider): String = when (p) {
        Provider.GEMINI -> geminiKey
        Provider.OPENROUTER -> openRouterKey
    }

    /** Model that listens to the audio (must accept audio input). */
    var speechModel: ModelInfo?
        get() = readModel("speech_model")
        set(value) = writeModel("speech_model", value)

    /** Model that turns transcripts into structured notes. Null = the speech model does everything in one step. */
    var analysisModel: ModelInfo?
        get() = readModel("analysis_model")
        set(value) = writeModel("analysis_model", value)

    var geminiModels: List<ModelInfo>
        get() = ModelInfo.listFromJson(prefs.getString("gemini_models", null))
        set(value) = prefs.edit().putString("gemini_models", ModelInfo.listToJson(value)).apply()

    var openRouterModels: List<ModelInfo>
        get() = ModelInfo.listFromJson(prefs.getString("openrouter_models", null))
        set(value) = prefs.edit().putString("openrouter_models", ModelInfo.listToJson(value)).apply()

    var segmentSeconds: Int
        get() = prefs.getInt("segment_seconds", 20).coerceIn(10, 60)
        set(value) = prefs.edit().putInt("segment_seconds", value.coerceIn(10, 60)).apply()

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
