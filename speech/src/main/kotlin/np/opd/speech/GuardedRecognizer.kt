package np.opd.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What the engine is doing right now. */
sealed class EngineState {
    object Idle : EngineState()
    object Initializing : EngineState()
    object Ready : EngineState()
    object Processing : EngineState()
    data class Failed(val message: String) : EngineState()
}

/**
 * Wraps a [LocalSpeechRecognizer] with the rules the app relies on:
 *  - the model is loaded ONCE (single-flight), never per segment;
 *  - only ONE inference runs at a time (the native engine is not assumed to be concurrent);
 *  - inference never runs on the caller's (main) thread;
 *  - a hung inference is stopped by a watchdog instead of blocking the consultation forever;
 *  - every failure becomes an [AsrException] with a message the doctor can read.
 */
class GuardedRecognizer(
    private val delegate: LocalSpeechRecognizer,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** An inference gets at least this long, or [timeoutPerAudio] x its audio length, whichever is longer. */
    private val minTimeoutMs: Long = 30_000,
    private val timeoutPerAudio: Double = 6.0,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Lets the owner observe the engine without collecting it (see [LocalAsrService]). */
    private val stateSink: MutableStateFlow<EngineState> = MutableStateFlow(EngineState.Idle)
) {
    val backend: AsrBackend get() = delegate.backend
    val stats = AsrStats()

    private val _state: MutableStateFlow<EngineState> get() = stateSink
    val state: StateFlow<EngineState> = stateSink.asStateFlow()

    private val initLock = Mutex()
    private val runLock = Mutex()

    fun isReady(): Boolean = _state.value is EngineState.Ready || _state.value is EngineState.Processing

    /** Loads the model if it is not loaded yet. Concurrent callers share one load. Failed loads are retried on the next call. */
    suspend fun ensureReady() {
        if (isReady() && delegate.isReady()) return
        initLock.withLock {
            if (isReady() && delegate.isReady()) return
            _state.value = EngineState.Initializing
            val t0 = clock()
            try {
                withContext(dispatcher) { delegate.initialize() }
                stats.recordInit(clock() - t0, delegate.backend)
                _state.value = EngineState.Ready
            } catch (e: CancellationException) {
                _state.value = EngineState.Idle
                throw e
            } catch (e: Throwable) {
                val ex = e as? AsrException ?: AsrException(AsrErrorKind.INIT_FAILED, e.message, e)
                _state.value = EngineState.Failed(ex.message ?: ex.kind.userMessage)
                throw ex
            }
        }
    }

    /** Transcribes one segment. Calls queue up and run one after another. */
    suspend fun transcribe(pcm: FloatArray, config: AsrConfig = AsrConfig(), prompt: String? = null): List<RecognizedSegment> {
        ensureReady()
        return runLock.withLock {
            val audioMs = WavAudio.durationMs(pcm.size)
            val limit = maxOf(minTimeoutMs, (audioMs * timeoutPerAudio).toLong())
            val usedPrompt = if (config.usePrompt && delegate.supportsPrompt) prompt else null
            _state.value = EngineState.Processing
            val t0 = clock()
            val timedOut = AtomicBoolean(false)
            try {
                val result = coroutineScope {
                    // Native inference cannot be interrupted by coroutine cancellation, so a watchdog asks the engine to stop.
                    val watchdog = launch {
                        delay(limit)
                        timedOut.set(true)
                        delegate.cancel()
                    }
                    try {
                        withContext(dispatcher) { delegate.transcribe(pcm, config, usedPrompt) }
                    } finally {
                        watchdog.cancel()
                    }
                }
                if (timedOut.get()) throw AsrException(AsrErrorKind.TIMED_OUT, "more than ${limit / 1000} s for ${audioMs / 1000} s of audio")
                stats.record(SegmentTiming(audioMs, clock() - t0))
                _state.value = EngineState.Ready
                result
            } catch (e: CancellationException) {
                delegate.cancel()
                _state.value = EngineState.Ready
                throw e
            } catch (e: Throwable) {
                stats.record(SegmentTiming(audioMs, clock() - t0, ok = false))
                val ex = when {
                    e is AsrException -> e
                    timedOut.get() -> AsrException(AsrErrorKind.TIMED_OUT, "more than ${limit / 1000} s for ${audioMs / 1000} s of audio", e)
                    else -> AsrException(AsrErrorKind.INFERENCE_FAILED, e.message, e)
                }
                // A failed segment does not make the engine unusable; the next one gets a fresh try.
                _state.value = EngineState.Ready
                throw ex
            }
        }
    }

    /** Frees the model (e.g. when the doctor deletes it). The next [ensureReady] loads it again. */
    fun release() {
        delegate.cancel()
        try { delegate.release() } finally { _state.value = EngineState.Idle }
    }
}
