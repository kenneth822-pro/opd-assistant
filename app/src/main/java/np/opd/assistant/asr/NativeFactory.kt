package np.opd.assistant.asr

import np.opd.speech.LocalSpeechRecognizer
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Finds the native engine by name, so the app compiles and runs whether or not the optional :asr-native module
 * was part of the build (-PenableNativeAsr=true). Without it the app says so plainly and never offers local speech.
 */
internal object NativeFactory {

    /** A function that builds the engine for a model file, or null if this build has no native engine. */
    fun get(): ((File) -> LocalSpeechRecognizer)? = try {
        val create = Class.forName("np.opd.asr.NativeRecognizers").getMethod("create", File::class.java)
        val factory: (File) -> LocalSpeechRecognizer = { file ->
            try {
                create.invoke(null, file) as LocalSpeechRecognizer
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
        factory
    } catch (e: ClassNotFoundException) {
        null
    } catch (e: NoSuchMethodException) {
        null
    } catch (e: LinkageError) {
        null
    }
}
