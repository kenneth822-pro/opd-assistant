package np.opd.speech

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import np.opd.engine.TranscriptLine
import java.io.File

/** One value the UI can show for the whole local speech path ("Local ASR · Ready (CPU)"). */
sealed class LocalAsrStatus(val label: String) {
    /** The app was built without an on-device engine. */
    object RuntimeMissing : LocalAsrStatus("Engine not included in this build")
    object NotInstalled : LocalAsrStatus("Model not installed")
    data class NeedsFile(val reason: String) : LocalAsrStatus("Model not installed (import a file)")
    data class Downloading(val percent: Int?) : LocalAsrStatus("Downloading model" + (percent?.let { " $it%" } ?: ""))
    object Verifying : LocalAsrStatus("Verifying model")
    object Installing : LocalAsrStatus("Installing model")
    data class ModelError(val message: String) : LocalAsrStatus("Model error")
    object Loading : LocalAsrStatus("Loading model")
    data class Ready(val backend: AsrBackend, val loaded: Boolean) : LocalAsrStatus("Ready (${backend.label})")
    data class Processing(val backend: AsrBackend) : LocalAsrStatus("Processing (${backend.label})")
    data class EngineError(val message: String) : LocalAsrStatus("Engine error")

    val isReady: Boolean get() = this is Ready || this is Processing

    companion object {
        fun from(install: InstallState, engine: EngineState, backend: AsrBackend, runtimePresent: Boolean): LocalAsrStatus = when {
            !runtimePresent -> RuntimeMissing
            install is InstallState.Downloading -> Downloading(install.percent)
            install is InstallState.Verifying -> Verifying
            install is InstallState.Installing -> Installing
            install is InstallState.Failed -> ModelError(install.message)
            install is InstallState.NeedsFile -> NeedsFile(install.reason)
            install is InstallState.NotInstalled -> NotInstalled
            engine is EngineState.Failed -> EngineError(engine.message)
            engine is EngineState.Initializing -> Loading
            engine is EngineState.Processing -> Processing(backend)
            engine is EngineState.Ready -> Ready(backend, loaded = true)
            else -> Ready(backend, loaded = false) // installed, loads on first use
        }
    }
}

/**
 * The single entry point the app uses for local speech: model on disk -> engine -> [TranscriptLine]s.
 * Nothing here knows about clinical state, and nothing clinical knows about models or runtimes.
 *
 * @param recognizerFactory builds the engine for an installed model file; null means this build has no on-device
 *                          engine (then [readiness] says so and routing never selects the local path).
 */
class LocalAsrService(
    val installer: ModelInstaller,
    private val recognizerFactory: ((File) -> LocalSpeechRecognizer)?,
    private val config: () -> AsrConfig = { AsrConfig() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    private var guarded: GuardedRecognizer? = null
    /** Identity of the model the engine was built for: path AND checksum, so a replaced file is never run on old weights. */
    private var loadedKey: String? = null
    private val lock = Any()

    private val runtimePresent: Boolean get() = recognizerFactory != null
    private val backend: AsrBackend get() = guarded?.backend ?: installer.spec.backend

    fun status(): LocalAsrStatus = LocalAsrStatus.from(installer.state.value, engineState.value, backend, runtimePresent)

    /** A flow for the UI. */
    val statusFlow: Flow<LocalAsrStatus> =
        combine(installer.state, engineState) { i, e -> LocalAsrStatus.from(i, e, backend, runtimePresent) }

    /** What routing needs: can a segment go to the local engine right now? */
    fun readiness(): LocalReadiness {
        val s = status()
        return when {
            s.isReady || s is LocalAsrStatus.Loading -> LocalReadiness.Ready
            else -> LocalReadiness.Unavailable(s.label + (when (s) {
                is LocalAsrStatus.EngineError -> ": ${s.message}"
                is LocalAsrStatus.ModelError -> ": ${s.message}"
                is LocalAsrStatus.NeedsFile -> ": ${s.reason}"
                else -> ""
            }))
        }
    }

    /** The engine for the currently installed model; rebuilt if the model was replaced, freed if it was deleted. */
    private fun engine(): GuardedRecognizer {
        val ready = installer.state.value as? InstallState.Ready
        synchronized(lock) {
            if (ready == null) {
                dropEngine()
                throw AsrException(AsrErrorKind.MODEL_MISSING)
            }
            val factory = recognizerFactory ?: throw AsrException(AsrErrorKind.RUNTIME_UNAVAILABLE)
            val key = ready.sha256 + "|" + ready.file.path
            guarded?.let { if (loadedKey == key) return it }
            dropEngine()
            val g = GuardedRecognizer(factory(ready.file), dispatcher, stateSink = engineState)
            guarded = g
            loadedKey = key
            return g
        }
    }

    private fun dropEngine() {
        guarded?.release()
        guarded = null
        loadedKey = null
        engineState.value = EngineState.Idle
    }

    /** Loads the model now (call when a consultation starts) so the first segment is not delayed. Throws [AsrException]. */
    suspend fun prepare() = engine().ensureReady()

    /**
     * @param wav a recorder segment (16 kHz mono 16-bit WAV).
     * @param voicedFraction share of the segment that was not silence, if known (stricter hallucination filtering when low).
     * @param prompt previous transcript text; used only if the engine supports it and the config enables it.
     */
    suspend fun transcribeWav(wav: ByteArray, voicedFraction: Float? = null, prompt: String? = null): List<TranscriptLine> {
        val pcm = WavAudio.decode(wav)
        val segments = engine().transcribe(pcm, config(), prompt)
        return AsrOutput.toLines(segments, voicedFraction)
    }

    fun statsSummary(): String = guarded?.stats?.summary() ?: "Local ASR: not used yet"

    /** Frees the loaded model (the files stay). */
    fun release() {
        synchronized(lock) { dropEngine() }
    }

    /** Frees the model and deletes its files. */
    fun deleteModel() {
        release()
        installer.delete()
    }
}
