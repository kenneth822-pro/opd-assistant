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
import np.opd.assistant.ai.AiClient
import np.opd.assistant.ai.AiPart
import np.opd.assistant.ai.GeminiClient
import np.opd.assistant.ai.ModelDirectory
import np.opd.assistant.ai.ModelInfo
import np.opd.assistant.ai.OpenRouterClient
import np.opd.assistant.ai.Provider
import np.opd.assistant.audio.WavSegmentRecorder
import np.opd.engine.CaseState
import np.opd.engine.CatalogueIntegrity
import np.opd.engine.Catalogues
import np.opd.engine.DoctorAction
import np.opd.engine.Gender
import np.opd.engine.J
import np.opd.engine.LiveDelta
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
    val level: Float = 0f,
    val segmentsRecorded: Int = 0,
    val segmentsProcessed: Int = 0,
    val busy: String? = null,
    val errors: List<String> = emptyList(),
    val soap: Prompts.SoapNote? = null,
    val finalFailed: Boolean = false,
    val aiLog: List<String> = emptyList(),
    val message: String? = null,
    val testResult: String? = null,
    val hasSession: Boolean = false,
    // settings mirrored here so the UI recomposes when they change
    val speechModel: ModelInfo? = null,
    val analysisModel: ModelInfo? = null,
    val geminiModels: List<ModelInfo> = emptyList(),
    val openRouterModels: List<ModelInfo> = emptyList(),
    val hasGeminiKey: Boolean = false,
    val hasOpenRouterKey: Boolean = false,
    val modelsBusy: String? = null
) {
    val oneStep: Boolean get() = analysisModel == null
}

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)

    val catalogues: Catalogues?
    val catalogueProblems: List<String>

    private val _ui: MutableStateFlow<UiState>
    val ui: StateFlow<UiState>

    private var liveAudioPrompt = ""
    private var liveTranscriptPrompt = ""
    private var liveSchema: J.Obj? = null
    private var finalPrompt = ""
    private var finalSchema: J.Obj? = null
    private val transcribePrompt = Prompts.transcriptionSystemPrompt()
    private val transcribeSchema = Prompts.transcriptionSchema()

    private data class Segment(val file: File, val startMs: Long, val number: Int)

    private var recorder: WavSegmentRecorder? = null
    private val segments = mutableListOf<Segment>()
    private var tickerJob: Job? = null
    private var workerJob: Job? = null
    private var queue: Channel<Segment>? = null
    private var sessionSpeech: ModelInfo? = null
    private var sessionAnalysis: ModelInfo? = null

    init {
        var cat: Catalogues? = null
        var problems: List<String>
        try {
            cat = Catalogues.loadBundled()
            problems = CatalogueIntegrity.check(cat)
            liveAudioPrompt = Prompts.liveSystemPrompt(cat, fromTranscript = false)
            liveTranscriptPrompt = Prompts.liveSystemPrompt(cat, fromTranscript = true)
            liveSchema = Prompts.liveSchema(cat)
            finalPrompt = Prompts.finalSystemPrompt(cat)
            finalSchema = Prompts.finalSchema(cat)
        } catch (e: Exception) {
            problems = listOf("Could not load clinical catalogues: ${e.message}")
        }
        catalogues = cat
        catalogueProblems = problems
        _ui = MutableStateFlow(withSettings(UiState(screen = if (settings.disclaimerAccepted) Screen.HOME else Screen.DISCLAIMER)))
        ui = _ui.asStateFlow()
    }

    private fun withSettings(s: UiState) = s.copy(
        speechModel = settings.speechModel,
        analysisModel = settings.analysisModel,
        geminiModels = settings.geminiModels,
        openRouterModels = settings.openRouterModels,
        hasGeminiKey = settings.geminiKey.isNotBlank(),
        hasOpenRouterKey = settings.openRouterKey.isNotBlank()
    )

    private fun refreshSettings() = _ui.update { withSettings(it) }

    /* ---------------- navigation ---------------- */

    fun go(screen: Screen) = _ui.update { withSettings(it).copy(screen = screen, message = null, testResult = null) }

    fun acceptDisclaimer() {
        settings.disclaimerAccepted = true
        go(Screen.HOME)
    }

    fun showMessage(msg: String?) = _ui.update { it.copy(message = msg) }

    /* ---------------- settings ---------------- */

    fun saveKey(provider: Provider, key: String) {
        if (key.isBlank()) return
        when (provider) {
            Provider.GEMINI -> settings.geminiKey = key
            Provider.OPENROUTER -> settings.openRouterKey = key
        }
        refreshSettings()
        showMessage("${provider.label} key saved.")
        if (provider == Provider.GEMINI) loadModels(Provider.GEMINI)
    }

    fun loadModels(provider: Provider) {
        viewModelScope.launch {
            _ui.update { it.copy(modelsBusy = "Loading ${provider.label} models…", message = null) }
            try {
                when (provider) {
                    Provider.GEMINI -> {
                        val list = ModelDirectory.gemini(settings.geminiKey)
                        settings.geminiModels = list
                        val current = settings.speechModel
                        val stale = current?.provider == Provider.GEMINI && list.none { it.id == current.id }
                        if (current == null || stale) {
                            ModelDirectory.bestGeminiDefault(list)?.let { settings.speechModel = it }
                        }
                        val an = settings.analysisModel
                        if (an?.provider == Provider.GEMINI && list.none { it.id == an.id }) settings.analysisModel = null
                        refreshSettings()
                        showMessage("Loaded ${list.size} Gemini models." + if (stale) " Your previous model is no longer offered, so a current one was selected." else "")
                    }
                    Provider.OPENROUTER -> {
                        val list = ModelDirectory.openRouter()
                        settings.openRouterModels = list
                        refreshSettings()
                        showMessage("Loaded ${list.size} OpenRouter models (${list.count { it.free == true }} free, ${list.count { it.audioInput }} accept audio).")
                    }
                }
            } catch (e: Exception) {
                showMessage("Could not load ${provider.label} models: ${e.message}")
            }
            _ui.update { it.copy(modelsBusy = null) }
        }
    }

    fun chooseSpeechModel(m: ModelInfo) {
        settings.speechModel = m
        refreshSettings()
    }

    /** Null = one step (the speech model also writes the structured notes). */
    fun chooseAnalysisModel(m: ModelInfo?) {
        settings.analysisModel = m
        refreshSettings()
    }

    fun setSegmentSeconds(sec: Int) {
        settings.segmentSeconds = sec
    }

    fun testModels() {
        val speech = settings.speechModel ?: return run { _ui.update { it.copy(testResult = "Choose a speech model first.") } }
        val analysis = settings.analysisModel
        _ui.update { it.copy(testResult = "Testing…") }
        viewModelScope.launch {
            val lines = mutableListOf<String>()
            for ((role, m) in listOfNotNull("Speech" to speech, analysis?.let { "Analysis" to it })) {
                lines += try {
                    val reply = clientFor(m).generate(null, listOf(AiPart.Text("Reply with exactly the word OK.")), null)
                    "✓ $role model ${m.id} replied: ${reply.trim().take(30)}"
                } catch (e: Exception) {
                    "✗ $role model ${m.id}: ${e.message}"
                }
            }
            _ui.update { it.copy(testResult = lines.joinToString("\n")) }
        }
    }

    fun deleteAllAudio() {
        if (_ui.value.recording) return
        File(getApplication<Application>().filesDir, "sessions").deleteRecursively()
        synchronized(segments) { segments.clear() }
        showMessage("All recorded audio deleted from this phone.")
    }

    private fun clientFor(m: ModelInfo): AiClient = when (m.provider) {
        Provider.GEMINI -> GeminiClient(settings.geminiKey, m)
        Provider.OPENROUTER -> OpenRouterClient(settings.openRouterKey, m)
    }

    /* ---------------- consultation ---------------- */

    fun startConsult(mode: Mode, age: Int?, gender: Gender?) {
        val cat = catalogues ?: return showMessage("Clinical catalogues failed to load.")
        val speech = settings.speechModel ?: return showMessage("Choose a speech model in Settings first.")
        if (!speech.audioInput) return showMessage("The speech model must accept audio. Choose another in Settings.")
        if (settings.keyFor(speech.provider).isBlank()) return showMessage("Add your ${speech.provider.label} key in Settings.")
        val analysis = settings.analysisModel
        if (analysis != null && settings.keyFor(analysis.provider).isBlank()) return showMessage("Add your ${analysis.provider.label} key in Settings.")

        File(getApplication<Application>().filesDir, "sessions").deleteRecursively()
        synchronized(segments) { segments.clear() }

        var case = CaseState()
        if (age != null || gender != null) case = Reducer.handle(case, DoctorAction.SetDemographics(age, gender), cat)
        val dir = File(getApplication<Application>().filesDir, "sessions/${case.sessionId}").apply { mkdirs() }
        val rec = WavSegmentRecorder(dir, { settings.segmentSeconds }) { file, startMs, _ -> onSegment(file, startMs) }
        try {
            rec.start()
        } catch (e: Exception) {
            return showMessage("Could not start the microphone: ${e.message}")
        }
        recorder = rec
        sessionSpeech = speech
        sessionAnalysis = analysis
        _ui.value = withSettings(UiState(screen = Screen.RECORDING, mode = mode, case = case, recording = true, hasSession = true))
        startWorker()
        startTicker()
    }

    /** Called on the recording thread whenever a segment file is closed. */
    private fun onSegment(file: File, startMs: Long) {
        val seg = synchronized(segments) {
            Segment(file, startMs, segments.size + 1).also { segments += it }
        }
        _ui.update { it.copy(segmentsRecorded = it.segmentsRecorded + 1) }
        queue?.trySend(seg)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val st = _ui.value
                if (!st.recording) continue
                _ui.update {
                    it.copy(
                        elapsedSec = if (it.paused) it.elapsedSec else it.elapsedSec + 1,
                        level = recorder?.level ?: 0f
                    )
                }
            }
        }
    }

    fun pause() {
        if (!_ui.value.recording || _ui.value.paused) return
        _ui.update { it.copy(paused = true) }
        viewModelScope.launch { withContext(Dispatchers.IO) { recorder?.stop() } }
    }

    fun resume() {
        if (!_ui.value.recording || !_ui.value.paused) return
        try {
            recorder?.start()
            _ui.update { it.copy(paused = false) }
        } catch (e: Exception) {
            showMessage("Could not restart the microphone: ${e.message}")
        }
    }

    private fun startWorker() {
        val q = Channel<Segment>(Channel.UNLIMITED)
        queue = q
        workerJob = viewModelScope.launch {
            for (seg in q) processSegment(seg)
        }
    }

    private suspend fun processSegment(seg: Segment) {
        val cat = catalogues ?: return
        val speech = sessionSpeech ?: return
        val analysis = sessionAnalysis
        val live = _ui.value.mode == Mode.LIVE
        try {
            val wav = withContext(Dispatchers.IO) { seg.file.readBytes() }
            if (live && analysis == null) {
                // One step: the audio model hears the segment and returns the structured update directly.
                val summary = Prompts.caseSummary(_ui.value.case, cat)
                val text = clientFor(speech).generate(
                    liveAudioPrompt,
                    listOf(AiPart.Text(summary + "\nNow process the attached audio segment (#${seg.number})."), AiPart.Wav(wav)),
                    liveSchema
                )
                val delta = Prompts.parseDelta(text)
                _ui.update { it.copy(case = Reducer.reduce(it.case, delta, cat, seg.startMs), aiLog = (it.aiLog + "Segment ${seg.number}:\n$text").takeLast(40)) }
            } else {
                // Two steps: 1) the speech model transcribes, 2) the analysis model structures the text.
                val before = _ui.value.case
                val context = before.transcript.takeLast(6).joinToString("\n") { "${it.speaker}: ${it.text}" }.ifBlank { "(start of consultation)" }
                val tText = clientFor(speech).generate(
                    transcribePrompt,
                    listOf(AiPart.Text("Previous lines, for speaker context only (do not repeat them):\n$context\n\nTranscribe audio segment #${seg.number}."), AiPart.Wav(wav)),
                    transcribeSchema
                )
                val lines = Prompts.parseTranscription(tText)
                _ui.update { it.copy(case = Reducer.reduce(it.case, LiveDelta(transcript = lines), cat, seg.startMs), aiLog = (it.aiLog + "Transcript ${seg.number}:\n$tText").takeLast(40)) }

                if (live && analysis != null && lines.isNotEmpty()) {
                    val summary = Prompts.caseSummary(before, cat, recentLines = 6)
                    val text = clientFor(analysis).generate(
                        liveTranscriptPrompt,
                        listOf(AiPart.Text(summary + "\n\nNEW SEGMENT TRANSCRIPT (#${seg.number}):\n" + Prompts.transcriptText(lines))),
                        liveSchema
                    )
                    val delta = Prompts.parseDelta(text).copy(transcript = emptyList())
                    _ui.update { it.copy(case = Reducer.reduce(it.case, delta, cat), aiLog = (it.aiLog + "Analysis ${seg.number}:\n$text").takeLast(40)) }
                }
            }
        } catch (e: Exception) {
            addError("Segment ${seg.number}: ${e.message}")
        } finally {
            _ui.update { it.copy(segmentsProcessed = it.segmentsProcessed + 1) }
        }
    }

    fun endConsult() {
        if (!_ui.value.recording) return
        _ui.update { it.copy(busy = "Stopping the microphone…") }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recorder?.stop() }
            recorder = null
            tickerJob?.cancel()
            _ui.update { it.copy(recording = false, paused = false, level = 0f, busy = "Finishing ${it.segmentsRecorded - it.segmentsProcessed} remaining segment(s)…") }
            queue?.close()
            workerJob?.join()
            queue = null
            runFinal()
        }
    }

    fun retryFinal() {
        if (_ui.value.busy != null) return
        viewModelScope.launch { runFinal() }
    }

    /** The final note is written from the transcript, so it works with any text model. */
    private suspend fun runFinal() {
        val cat = catalogues ?: return
        val model = sessionAnalysis ?: sessionSpeech ?: settings.analysisModel ?: settings.speechModel
        val transcript = _ui.value.case.transcript
        if (model == null || transcript.isEmpty()) {
            _ui.update { it.copy(busy = null, screen = Screen.REVIEW, finalFailed = true) }
            addError(if (model == null) "No model selected." else "No speech was transcribed, so no note could be written. Check the errors above and your model choice.")
            return
        }
        _ui.update { it.copy(busy = "Writing the final note with ${model.id}…") }
        try {
            val text = clientFor(model).generate(
                finalPrompt,
                listOf(AiPart.Text(Prompts.caseSummary(_ui.value.case, cat, recentLines = 0) + "\n\nFULL TRANSCRIPT:\n" + Prompts.transcriptText(transcript))),
                finalSchema
            )
            val result = Prompts.parseFinal(text)
            _ui.update {
                it.copy(
                    case = Reducer.reduce(it.case, result.delta.copy(transcript = emptyList()), cat),
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
        _ui.value = withSettings(UiState(screen = Screen.SETUP))
    }

    private fun addError(msg: String) = _ui.update { it.copy(errors = (it.errors + msg).takeLast(20)) }

    override fun onCleared() {
        tickerJob?.cancel()
        recorder?.stop()
        super.onCleared()
    }
}
