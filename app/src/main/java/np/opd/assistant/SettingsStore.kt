package np.opd.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App settings. The Gemini API key is encrypted with an AES key that lives in the
 * Android Keystore (hardware-backed on most phones) and never leaves the device.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("opd_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, null)?.let { decrypt(it) } ?: ""
        set(value) {
            prefs.edit().putString(KEY_API, encrypt(value.trim())).apply()
        }

    var liveModel: String
        get() = prefs.getString("live_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("live_model", value.trim()).apply()

    var finalModel: String
        get() = prefs.getString("final_model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("final_model", value.trim()).apply()

    var segmentSeconds: Int
        get() = prefs.getInt("segment_seconds", 20).coerceIn(10, 60)
        set(value) = prefs.edit().putInt("segment_seconds", value.coerceIn(10, 60)).apply()

    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean("disclaimer_v1", false)
        set(value) = prefs.edit().putBoolean("disclaimer_v1", value).apply()

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
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val KEY_API = "api_key_enc"
        private const val ALIAS = "opd_settings_key"
    }
}
