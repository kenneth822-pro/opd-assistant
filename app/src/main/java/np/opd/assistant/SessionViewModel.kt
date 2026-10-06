package np.opd.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import np.opd.engine.CaseState
import np.opd.engine.CatalogueIntegrity
import np.opd.engine.Catalogues
import np.opd.engine.DoctorAction
import np.opd.engine.Gender
import np.opd.engine.J
import np.opd.engine.Prompts
import np.opd.engine.Reducer
import java.io.File

enum class Screen { DISCLAIMER, HOME, SETTINGS, SETUP, RECORDING, REVIEW }
enum class Mode { LIVE, SCRIBE }

data class UiState(
    val screen: Screen = Screen.HOME,
    val mode: Mode = Mode.LIVE,
    val case: CaseState = CaseState(),
    val recording: Boolean = false,
    val paused: Boolean = false,
    val elapsedSec: Int = 0,
    val segmentsRecorded: Int = 0,
    val segmentsProcessed: Int = 0,
    val busy: String? = null,
    val errors: List<String> = emptyList(),
    val soap: Prompts.SoapNote? = null,
    val finalFailed: Boolean = false,
    val aiLog: List<String> = emptyList(),
    val message: String? = null,
    val testResult: String? = null,
    val hasSession: Boolean = false
)

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)

    /** Clinical catalogues bundled in the app. Null if they failed to load (shown on Home). */
    val catalogues: Catalogues?
    val catalogueProblems: List<String>

    private val _ui = MutableStateFlow(UiState(screen = if (settings.disclaimerAccepted) Screen.HOME else Screen.DISCLAIMER))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var liveSystemPrompt = ""
    private var liveSchema: J.Obj? = null
    private var finalSystemPrompt = ""
    private var finalSchema: J.Obj? = null

    private data class Segment(val file: File, val startMs: Long)

    private var recorder: SegmentRecorder? = null
    private val segments = mutableListOf<Segment>()
    private var segmentStartSec = 0
    private var tickerJob: Job? = null
    private var liveJob: Job? = null
    private var liveQueue: Channel<Segment>? = null

    init {
        var cat: Catalogues? = null
        var problems: List<String>
        try {
            cat = Catalogues.loadBundled()
            problems = CatalogueIntegrity.check(cat)
            liveSystemPrompt = Prompts.liveSystemPrompt(cat)
            liveSchema = Prompts.liveSchema(cat)
            finalSystemPrompt = Prompts.finalSystemPrompt(cat)
            finalSchema = Prompts.finalSchema(cat)
        } catch (e: Exception) {
            problems = listOf("Could not load clinical catalogues: ${e.message}")
        }
        catalogues = cat
        catalogueProblems = problems
    }

    /* ---------------- navigation ---------------- */

    fun go(screen: Screen) = _ui.update { it.copy(screen = screen, message = null, testResult = null) }

    fun acceptDisclaimer() {
        settings.disclaimerAccepted = true
        go(Screen.HOME)
    }

    fun showMessage(msg: String?) = _ui.update { it.copy(message = msg) }

    fun saveSettings(apiKey: String?, liveModel: String, finalModel: String, segmentSeconds: Int) {
        if (apiKey != null && apiKey.isNotBlank()) settings.apiKey = apiKey
        settings.liveModel = liveModel
        settings.finalModel = finalModel
        settings.segmentSeconds = segmentSeconds
        _ui.update { it.copy(message = "Settings saved") }
    }

    fun testConnection() {
        val key = settings.apiKey
        if (key.isBlank()) { _ui.update { it.copy(testResult = "No API key saved yet.") }; return }
        _ui.update { it.copy(testResult = "Testing…") }
        viewModelScope.launch {
            val result = try {
                val reply = GeminiClient(key).generate(
                    settings.liveModel, null,
                    listOf(GeminiClient.Part.Text("Reply with exactly the word OK.")), null
                )
                "Connected. Model ${settings.liveModel} replied: ${reply.trim().take(40)}"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            _ui.update { it.copy(testResult = result) }
        }
    }

    fun deleteAllAudio() {
        if (_ui.value.recording) return
        File(getApplication<Application>().filesDir, "sessions").deleteRecursively()
        segments.clear()
        _ui.update { it.copy(message = "All recorded audio deleted from this phone.") }
    }

    /* ---------------- consultation ---------------- */

    fun startConsult(mode: Mode, age: Int?, gender: Gender?) {
        val cat = catalogues ?: return showMessage("Clinical catalogues failed to load.")
        if (settings.apiKey.isBlank()) return showMessage("Add your Gemini API key in Settings first.")

        // Previous consultation's audio is removed when a new one starts.
        File(getApplication<Application>().filesDir, "sessions").deleteRecursively()
        segments.clear()

        var case = CaseState()
        if (age != null || gender != null) case = Reducer.handle(case, DoctorAction.SetDemographics(age, gender), cat)
        val dir = File(getApplication<Application>().filesDir, "sessions/${case.sessionId}").apply { mkdirs() }
        val rec = SegmentRecorder(getApplication(), dir)
        try {
            rec.startNew()
        } catch (e: Exception) {
            return showMessage("Could not start the microphone: ${e.message}")
        }
        recorder = rec
        segmentStartSec = 0
        _ui.value = UiState(screen = Screen.RECORDING, mode = mode, case = case, recording = true, hasSession = true)

        if (mode == Mode.LIVE) startLiveWorker()
        startTicker()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val st = _ui.value
                if (!st.recording || st.paused) continue
                val elapsed = st.elapsedSec + 1
                _ui.update { it.copy(elapsedSec = elapsed) }
                if (elapsed - segmentStartSec >= settings.segmentSeconds) {
                    closeSegment()
                    try {
                        recorder?.startNew()
                    } catch (e: Exception) {
                        addError("Microphone stopped: ${e.message}")
                        _ui.update { it.copy(paused = true) }
                    }
                }
            }
        }
    }

    private fun closeSegment() {
        val rec = recorder ?: return
        val start = segmentStartSec * 1000L
        segmentStartSec = _ui.value.elapsedSec
        val file = rec.stop() ?: return
        val seg = Segment(file, start)
        segments += seg
        _ui.update { it.copy(segmentsRecorded = segments.size) }
        liveQueue?.trySend(seg)
    }

    fun pause() {
        if (!_ui.value.recording || _ui.value.paused) return
        closeSegment()
        _ui.update { it.copy(paused = true) }
    }

    fun resume() {
        if (!_ui.value.recording || !_ui.value.paused) return
        try {
            recorder?.startNew()
            segmentStartSec = _ui.value.elapsedSec
            _ui.update { it.copy(paused = false) }
        } catch (e: Exception) {
            showMessage("Could not restart the microphone: ${e.message}")
        }
    }

    private fun startLiveWorker() {
        val queue = Channel<Segment>(Channel.UNLIMITED)
        liveQueue = queue
        liveJob = viewModelScope.launch {
            for (seg in queue) processLiveSegment(seg)
        }
    }

    private suspend fun processLiveSegment(seg: Segment) {
        val cat = catalogues ?: return
        val number = segments.indexOf(seg) + 1
        try {
            val audio = withContext(Dispatchers.IO) { seg.file.readBytes() }
            val summary = Prompts.caseSummary(_ui.value.case, cat)
            val text = GeminiClient(settings.apiKey).generate(
                settings.liveModel,
                liveSystemPrompt,
                listOf(
                    GeminiClient.Part.Text(summary + "\nNow process the attached audio segment (#$number)."),
                    GeminiClient.Part.Audio("audio/aac", audio)
                ),
                liveSchema
            )
            val delta = Prompts.parseDelta(text)
            _ui.update {
                it.copy(
                    case = Reducer.reduce(it.case, delta, cat, seg.startMs),
                    segmentsProcessed = it.segmentsProcessed + 1,
                    aiLog = (it.aiLog + "Segment $number:\n$text").takeLast(40)
                )
            }
        } catch (e: Exception) {
            _ui.update { it.copy(segmentsProcessed = it.segmentsProcessed + 1) }
            addError("Segment $number not analysed live: ${e.message}")
        }
    }

    fun endConsult() {
        if (!_ui.value.recording) return
        viewModelScope.launch {
            if (!_ui.value.paused) closeSegment()
            tickerJob?.cancel()
            recorder = null
            _ui.update { it.copy(recording = false, paused = false, busy = "Finishing live analysis…") }
            liveQueue?.close()
            liveJob?.join()
            liveQueue = null
            runFinal()
        }
    }

    fun retryFinal() {
        if (_ui.value.busy != null) return
        viewModelScope.launch { runFinal() }
    }

    private suspend fun runFinal() {
        val cat = catalogues ?: return
        val segs = segments.toList()
        if (segs.isEmpty()) {
            _ui.update { it.copy(busy = null, screen = Screen.REVIEW, finalFailed = true) }
            addError("No audio was recorded.")
            return
        }
        _ui.update { it.copy(busy = "Writing the final note from ${segs.size} audio segment(s)…") }
        try {
            val parts = mutableListOf<GeminiClient.Part>(
                GeminiClient.Part.Text(
                    Prompts.caseSummary(_ui.value.case, cat, recentLines = 0) +
                        "\nThe complete consultation audio follows as ${segs.size} consecutive segments."
                )
            )
            withContext(Dispatchers.IO) {
                segs.forEachIndexed { i, s ->
                    parts += GeminiClient.Part.Text("Segment ${i + 1} of ${segs.size}:")
                    parts += GeminiClient.Part.Audio("audio/aac", s.file.readBytes())
                }
            }
            val text = GeminiClient(settings.apiKey).generate(settings.finalModel, finalSystemPrompt, parts, finalSchema)
            val result = Prompts.parseFinal(text)
            val live = _ui.value.mode == Mode.LIVE
            val delta = if (live) result.delta.copy(transcript = emptyList()) else result.delta
            _ui.update {
                it.copy(
                    case = Reducer.reduce(it.case, delta, cat),
                    soap = result.soap,
                    busy = null,
                    screen = Screen.REVIEW,
                    finalFailed = false,
                    aiLog = (it.aiLog + "Final:\n$text").takeLast(40)
                )
            }
        } catch (e: Exception) {
            _ui.update { it.copy(busy = null, screen = Screen.REVIEW, finalFailed = true) }
            addError("Final note failed: ${e.message}")
        }
    }

    fun act(action: DoctorAction) {
        val cat = catalogues ?: return
        _ui.update { it.copy(case = Reducer.handle(it.case, action, cat)) }
    }

    fun newConsultation() {
        if (_ui.value.recording) return
        _ui.update { UiState(screen = Screen.SETUP) }
    }

    private fun addError(msg: String) = _ui.update { it.copy(errors = (it.errors + msg).takeLast(20)) }

    override fun onCleared() {
        tickerJob?.cancel()
        recorder?.stop()
        super.onCleared()
    }
}
