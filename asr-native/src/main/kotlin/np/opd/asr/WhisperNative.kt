package np.opd.asr

/**
 * The JNI surface of libopd_asr.so (see src/main/cpp/whisper_jni.cpp). Nothing else touches native code.
 * Not thread-safe by itself: [WhisperCppRecognizer] serialises every call.
 *
 * Error codes returned by [nativeTranscribe] (negative): -1 bad handle, -2 inference failed, -3 cancelled,
 * -4 bad audio, -5 native exception.
 */
class WhisperNative {
    /** Loads the model; returns an opaque handle, or 0 if it could not be loaded. */
    external fun nativeInit(modelPath: String, useGpu: Boolean): Long
    external fun nativeFree(handle: Long)
    /** Safe from any thread: asks a running [nativeTranscribe] to stop. */
    external fun nativeCancel(handle: Long)
    /** Returns the number of segments (>= 0) or a negative error code. */
    external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, translate: Boolean, threads: Int, audioCtx: Int, prompt: String?): Int
    /** Raw UTF-8 bytes: decoded in Kotlin because JNI string functions can abort on invalid UTF-8. */
    external fun nativeSegmentTextBytes(handle: Long, index: Int): ByteArray
    external fun nativeSegmentStartMs(handle: Long, index: Int): Long
    external fun nativeSegmentEndMs(handle: Long, index: Int): Long
    external fun nativeSegmentNoSpeech(handle: Long, index: Int): Float
    external fun nativeSegmentAvgLogProb(handle: Long, index: Int): Float
    /** [sample, encode, decode, batch-decode, prompt] ms of the last transcription. */
    external fun nativeLastTimings(handle: Long): FloatArray
    external fun nativeSystemInfo(): String

    companion object {
        @Volatile private var loaded = false

        /** Loads libopd_asr.so once. Throws [UnsatisfiedLinkError] if it is not part of the APK. */
        @Synchronized
        fun load(libraryName: String = "opd_asr") {
            if (loaded) return
            System.loadLibrary(libraryName)
            loaded = true
        }
    }
}
