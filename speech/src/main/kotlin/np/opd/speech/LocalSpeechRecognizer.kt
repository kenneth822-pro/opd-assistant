package np.opd.speech

/** How the local model runs. The app never needs to know which one; it is shown to the doctor and benchmarked. */
enum class AsrBackend(val label: String) {
    /** Qualcomm AI Runtime on the Hexagon NPU (HTP). Reserved: see docs/ON_DEVICE_ASR.md. */
    QNN_HTP("Snapdragon NPU"),
    /** whisper.cpp / ggml on the ARM64 CPU cores. */
    CPU_GGML("CPU")
}

/** One recognised piece of speech, as the engine returns it (before filtering and conversion). */
data class RecognizedSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Probability the segment is not speech, if the engine reports it. */
    val noSpeechProb: Float? = null,
    /** Mean log-probability of the decoded tokens, if the engine reports it (closer to 0 = more confident). */
    val avgLogProb: Float? = null
)

/**
 * Decoding options. Defaults are for Nepali dictation: transcribe (never translate) in Devanagari.
 * [threads] and [audioContext] are tuning knobs measured on the device, not guessed.
 */
data class AsrConfig(
    val language: String = "ne",
    val translate: Boolean = false,
    val threads: Int = 6,
    /** 0 = full 30 s window (most accurate). A smaller value speeds up short segments at some risk to accuracy. */
    val audioContext: Int = 0,
    /** Condition on the previous transcript, only if the backend supports it. Off: prompting can cause loops. */
    val usePrompt: Boolean = false
)

/** Why local recognition failed, in terms the doctor can act on. */
enum class AsrErrorKind(val userMessage: String) {
    MODEL_MISSING("Local ASR model not installed"),
    RUNTIME_UNAVAILABLE("Local ASR runtime is not part of this build"),
    INIT_FAILED("Local ASR failed to initialize"),
    INFERENCE_FAILED("Local ASR inference failed"),
    TIMED_OUT("Local ASR took too long and was stopped"),
    BAD_AUDIO("Audio could not be read"),
    CANCELLED("Local ASR was stopped")
}

class AsrException(val kind: AsrErrorKind, detail: String? = null, cause: Throwable? = null) :
    Exception(kind.userMessage + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""), cause)

/**
 * A speech recogniser that runs entirely on the phone. Implementations contain NO clinical logic and
 * the clinical engine contains no model/runtime logic: the only thing crossing this boundary is audio in,
 * text out.
 *
 * Implementations need not be thread-safe: [GuardedRecognizer] serialises all calls.
 */
interface LocalSpeechRecognizer {
    val backend: AsrBackend

    /** True if [transcribe] can use a previous-transcript prompt. */
    val supportsPrompt: Boolean get() = false

    /** Loads the model. Called once per engine lifetime, never per segment. Throws [AsrException] on failure. */
    suspend fun initialize()

    /**
     * @param pcm 16 kHz mono samples in [-1, 1].
     * @param prompt previous transcript text; ignored unless [supportsPrompt] and [AsrConfig.usePrompt].
     */
    suspend fun transcribe(pcm: FloatArray, config: AsrConfig = AsrConfig(), prompt: String? = null): List<RecognizedSegment>

    fun isReady(): Boolean

    /** One or two lines describing what is actually running (library version, CPU features, last timings). For the developer area. */
    fun diagnostics(): String = ""

    /** Asks a running [transcribe] to stop soon. Must be safe to call from any thread at any time. */
    fun cancel() {}

    /** Frees the model. After this the recogniser may be initialised again. */
    fun release()
}
