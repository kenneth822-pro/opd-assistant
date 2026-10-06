package np.opd.asr

import np.opd.speech.LocalSpeechRecognizer
import java.io.File

/**
 * Entry point the app finds by name (reflection), so the app compiles and runs with or without this module:
 * a build made without -PenableNativeAsr=true simply has no [NativeRecognizers] and reports that clearly.
 */
object NativeRecognizers {
    @JvmStatic
    fun create(modelFile: File): LocalSpeechRecognizer = WhisperCppRecognizer(modelFile)
}
