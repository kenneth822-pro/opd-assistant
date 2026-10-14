package np.opd.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
import np.opd.assistant.ai.ApiException
import np.opd.assistant.ai.GeminiClient
import np.opd.assistant.ai.LocalLlmClient
import np.opd.assistant.ai.LocalWhisperClient
import np.opd.assistant.ai.ModelDirectory
import np.opd.assistant.ai.ModelInfo
import np.opd.assistant.ai.OpenAiCompatClient
import np.opd.assistant.ai.Provider
import np.opd.assistant.model.LocalModelManager
import np.opd.assistant.audio.WavSegmentRecorder
import np.opd.engine.CaseState
import np.opd.engine.CatalogueIntegrity
import np.opd.engine.Catalogues
import np.opd.engine.DoctorAction
import np.opd.engine.Gender
import np.opd.engine.J
import np.opd.engine.LiveDelta
import np.opd.engine.LocalAssist
import np.opd.engine.Notes
import np.opd.engine.Prompts
import np.opd.engine.Reducer
import np.opd.engine.TranscriptLine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

enum class Screen { DISCLAIMER, HOME, SETTINGS, SETUP, RECORDING, REVIEW }
enum class Mode { LIVE, SCRIBE }
enum class SegState { QUEUED, TRANSCRIBING, DONE, SILENT, FAILED }

data class SegmentStatus(
    val number: Int,
    val startMs: Long,
    val durationMs: Long,
    val state: SegState,
    val model: String? = null,
    val error: String? = null,
    val lines: Int = 0
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val mode: Mode = Mode.LIVE,
    val case: CaseState = CaseState(),
    val recording: Boolean = false,
    val paused: Boolean = false,
    val elapsedSec: Int = 0,
    val level: Float = 0f,
    val currentSegmentSec: Int = 0,
    val segments: List<SegmentStatus> = emptyList(),
    /** Live suggestions (notes model) */
    val analysing: Boolean = false,
    val analysisNote: String? = null,
    val analysisFailed: Boolean = false,
    /** On-device keyword hints from the transcript */
    val hints: LocalAssist.Hints = LocalAssist.Hints(),
    /** Demographic fields filled from the conversation rather than typed ("age", "gender") */
    val heard: Set<String> = emptySet(),
    /** Reminders the doctor ticked off on the live screen */
    val doneTips: Set<String> = emptySet(),
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
    val backupModel: ModelInfo? = null,
    val models: Map<Provider, List<ModelInfo>> = emptyMap(),
    val keys: Set<Provider> = emptySet(),
    val customBaseUrl: String = "",
    val modelsBusy: String? = null
) {
    val notesModel: ModelInfo? get() = analysisModel ?: speechModel
    val pendingSegments: Int get() = segments.count { it.state == SegState.QUEUED || it.state == SegState.TRANSCRIBING }
    val failedSegments: Int get() = segments.count { it.state == SegState.FAILED }
    val silentSegments: Int get() = segments.count { it.state == SegState.SILENT }
}

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)
    val localModels = LocalModelManager(app)

    val catalogues: Catalogues?
    val catalogueProblems: List<String>

    private val _ui: MutableStateFlow<UiState>
    val ui: StateFlow<UiState>

    private var liveTranscriptPrompt = ""
    private var liveSchema: J.Obj? = null
    private var finalPrompt = ""
    private var finalSchema: J.Obj? = null
    private val transcribePrompt = Prompts.transcriptionSystemPrompt()
    private val transcribeSchema = Prompts.transcriptionSchema()

    private class Segment(
        val file: File,
        val startMs: Long,
        val durationMs: Long,
        val voiced: Float,
        val number: Int,
        val force: Boolean = false
    ) {
        fun withForce(force: Boolean) = Segment(file, startMs, durationMs, voiced, number, force)
    }

    private var recorder: WavSegmentRecorder? = null
    private val segments = mutableListOf<Segment>()
    private var tickerJob: Job? = null
    private var transcribeJob: Job? = null
    private var analysisJob: Job? = null
    private var queue: Channel<Segment>? = null
    private var analysisTrigger: Channel<Unit>? = null
    @Volatile private var analyseImmediately = false
    @Volatile private var forceAnalysis = false
    /** Transcript lines already sent to the notes model (main thread only). */
    private val analyzed = HashSet<TranscriptLine>()
    private var lastAnalysisAt = 0L
    /** Models that recently failed with "busy"/"missing" are skipped for a minute when a backup exists. */
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val keyCache = ConcurrentHashMap<Provider, String>()
    private var sessionSpeech: ModelInfo? = null
    private var sessionNotes: ModelInfo? = null

    init {
        var cat: Catalogues? = null
        var problems: List<String>
        try {
            cat = Catalogues.loadBundled()
            problems = CatalogueIntegrity.check(cat)
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

    private fun key(p: Provider): String = keyCache.getOrPut(p) { settings.keyFor(p) }

    private fun withSettings(s: UiState) = s.copy(
        speechModel = settings.speechModel,
        analysisModel = settings.analysisModel,
        backupModel = settings.backupModel,
        models = Provider.entries.associateWith { settings.models(it) },
        keys = Provider.entries.filter { key(it).isNotBlank() }.toSet(),
        customBaseUrl = settings.customBaseUrl
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

    fun saveKey(provider: Provider, value: String) {
        if (value.isBlank()) return
        settings.setKey(provider, value)
        keyCache.remove(provider)
        if (provider == Provider.GEMINI) GeminiClient.resetModelCapabilities()
        refreshSettings()
        showMessage("${provider.label} key saved.")
        loadModels(provider)
    }

    fun removeKey(provider: Provider) {
        settings.setKey(provider, "")
        keyCache.remove(provider)
        if (provider == Provider.GEMINI) GeminiClient.resetModelCapabilities()
        refreshSettings()
        showMessage("${provider.label} key removed.")
    }

    fun setCustomBaseUrl(url: String) {
        settings.customBaseUrl = url
        refreshSettings()
    }

    fun loadModels(provider: Provider) {
        viewModelScope.launch {
            _ui.update { it.copy(modelsBusy = "Loading ${provider.label} models…", message = null) }
            try {
                val list = when (provider) {
                    Provider.GEMINI -> ModelDirectory.gemini(key(Provider.GEMINI))
                    Provider.OPENROUTER -> ModelDirectory.openRouter()
                    Provider.LOCAL_WHISPER, Provider.LOCAL_LLM -> ModelDirectory.localModels(getApplication<Application>())
                    else -> ModelDirectory.openAiCompatible(provider, settings.baseUrlFor(provider), key(provider))
                }
                if (list.isEmpty()) {
                    showMessage("${provider.label} returned no usable models.")
                } else {
                    settings.setModels(provider, list)
                    var note = ""
                    fun stale(m: ModelInfo?) = m != null && m.provider == provider && list.none { it.id == m.id }
                    if (stale(settings.speechModel)) { settings.speechModel = null; note += " Your speech model is no longer offered." }
                    if (stale(settings.analysisModel)) { settings.analysisModel = null; note += " Your notes model is no longer offered." }
                    if (stale(settings.backupModel)) { settings.backupModel = null; note += " Your backup model is no longer offered." }
                    if (provider == Provider.GEMINI) {
                        if (settings.speechModel == null) ModelDirectory.bestGeminiDefault(list)?.let { settings.speechModel = it; note += " Speech model set to ${it.id}." }
                        if (settings.backupModel == null) {
                            ModelDirectory.bestGeminiDefault(list, excludeId = settings.speechModel?.id)?.let { settings.backupModel = it; note += " Backup model set to ${it.id}." }
                        }
                    }
                    val parts = listOfNotNull(
                        list.count { it.free == true }.takeIf { it > 0 }?.let { "$it free" },
                        list.count { it.audioInput && !it.transcriptionApi }.takeIf { it > 0 }?.let { "$it accept audio" },
                        list.count { it.transcriptionApi }.takeIf { it > 0 }?.let { "$it speech-to-text" }
                    )
                    showMessage("Loaded ${list.size} ${provider.label} models" + (if (parts.isEmpty()) "." else " (${parts.joinToString(", ")}).") + note)
                }
                refreshSettings()
            } catch (e: Exception) {
                showMessage("Could not load ${provider.label} models: ${e.message}")
            }
            _ui.update { it.copy(modelsBusy = null) }
        }
    }

    fun chooseSpeechModel(m: ModelInfo) {
        settings.speechModel = m
        refreshSettings()
        if (m.transcriptionApi && settings.analysisModel == null) {
            showMessage("${m.id} only turns speech into text. Now choose a notes model (any text model) below.")
        }
    }

    /** Null = the speech model also writes the notes. */
    fun chooseAnalysisModel(m: ModelInfo?) {
        settings.analysisModel = m
        refreshSettings()
    }

    fun chooseBackupModel(m: ModelInfo?) {
        settings.backupModel = m
        refreshSettings()
    }

    fun setSegmentSeconds(sec: Int) {
        settings.segmentSeconds = sec
    }

    fun testModels() {
        val speech = settings.speechModel ?: return run { _ui.update { it.copy(testResult = "Choose a speech model first.") } }
        val roles = listOfNotNull(
            "Speech" to speech,
            settings.analysisModel?.let { "Notes" to it },
            settings.backupModel?.let { "Backup" to it }
        )
        _ui.update { it.copy(testResult = "Testing ${roles.size} model(s)…") }
        viewModelScope.launch {
            val lines = mutableListOf<String>()
            for ((role, m) in roles) {
                val started = System.currentTimeMillis()
                lines += try {
                    val client = clientFor(m, attempts = 2)
                    val reply = if (m.transcriptionApi) {
                        val heard = client.transcribe(silentWav(1000), "") ?: emptyList()
                        "reachable (heard ${heard.size} line(s) in 1 s of silence)"
                    } else {
                        "replied \"" + client.generate(null, listOf(AiPart.Text("Reply with exactly the word OK.")), null).trim().take(30) + "\""
                    }
                    "✓ $role · ${m.id}: $reply in ${(System.currentTimeMillis() - started) / 100 / 10.0} s"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "✗ $role · ${m.id}: ${e.message}"
                }
                _ui.update { it.copy(testResult = lines.joinToString("\n")) }
            }
        }
    }

    fun deleteAllAudio() {
        if (_ui.value.recording) return
        File(getApplication<Application>().filesDir, "sessions").deleteRecursively()
        synchronized(segments) { segments.clear() }
        showMessage("All recorded audio deleted from this phone.")
    }

    private fun clientFor(m: ModelInfo, attempts: Int = 4, fast: Boolean = false): AiClient = when (m.provider) {
        Provider.GEMINI -> GeminiClient(key(Provider.GEMINI), m, attempts, fast)
        Provider.LOCAL_WHISPER -> {
            val modelDir = localModels.getModelDir(m.id, np.opd.assistant.model.ModelType.STT)
                ?: throw ApiException("Local Whisper model not found: ${m.id}")
            LocalWhisperClient(getApplication<Application>(), modelDir)
        }
        Provider.LOCAL_LLM -> {
            val modelDir = localModels.getModelDir(m.id, np.opd.assistant.model.ModelType.LLM)
                ?: throw ApiException("Local LLM model not found: ${m.id}")
            val (pteFile, tokenizerFile) = np.opd.assistant.ai.LocalLlmClient.findModelFiles(modelDir)
                ?: throw ApiException("Local LLM model files incomplete: ${m.id}")
            LocalLlmClient(pteFile, tokenizerFile)
        }
        else -> OpenAiCompatClient(settings.baseUrlFor(m.provider), key(m.provider), m, attempts)
    }

    private fun missingAccess(m: ModelInfo): String? = when {
        m.provider == Provider.CUSTOM && settings.customBaseUrl.isBlank() -> "Enter the custom server address in Settings."
        m.provider.isLocal -> {
            val modelDir = localModels.getModelDir(m.id, if (m.provider == Provider.LOCAL_WHISPER) np.opd.assistant.model.ModelType.STT else np.opd.assistant.model.ModelType.LLM)
            if (modelDir == null || !modelDir.exists()) "Local model not downloaded: ${m.name}" else null
        }
        m.provider != Provider.CUSTOM && key(m.provider).isBlank() -> "Add your ${m.provider.label} key in Settings."
        else -> null
    }

    private enum class Need { AUDIO, TEXT }

    private fun usable(m: ModelInfo, need: Need): Boolean =
        (if (need == Need.AUDIO) m.audioInput || m.transcriptionApi else !m.transcriptionApi) && missingAccess(m) == null

    /**
     * Runs [block] with the main model; if that fails (busy, retired, bad answer) it tries the backup model.
     * A model that was busy is skipped for a minute while a backup is available.
     */
    private suspend fun <T> withFallback(primary: ModelInfo, need: Need, live: Boolean, block: suspend (AiClient) -> T): Pair<T, ModelInfo> {
        val backup = settings.backupModel?.takeIf { it.key != primary.key && usable(it, need) }
        val now = System.currentTimeMillis()
        fun cooling(m: ModelInfo) = (cooldownUntil[m.key] ?: 0L) > now
        val order = if (backup != null && cooling(primary) && !cooling(backup)) listOf(backup, primary) else listOfNotNull(primary, backup)
        val attempts = when {
            backup != null && live -> 2
            live -> 3
            else -> 4
        }
        val failures = mutableListOf<String>()
        var last: Exception? = null
        for (m in order) {
            try {
                return block(clientFor(m, attempts, fast = live)) to m
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                failures += "${m.id}: ${e.message}"
                if (e is ApiException && (e.transient || e.modelMissing)) cooldownUntil[m.key] = System.currentTimeMillis() + 60_000
            }
        }
        if (failures.size > 1) throw ApiException(failures.joinToString(" | "))
        throw last ?: ApiException("Request failed")
    }

    /* ---------------- consultation ---------------- */

    fun startConsult(mode: Mode, age: Int?, gender: Gender?) {
        val cat = catalogues ?: return showMessage("Clinical catalogues failed to load.")
        val speech = settings.speechModel ?: return showMessage("Choose a speech model in Settings first.")
        if (!speech.audioInput && !speech.transcriptionApi) return showMessage("The speech model must accept audio. Choose another in Settings.")
        val notes = settings.analysisModel ?: speech
        if (notes.transcriptionApi) return showMessage("${speech.id} only turns speech into text. Choose a notes model in Settings (any text model).")
        missingAccess(speech)?.let { return showMessage(it) }
        missingAccess(notes)?.let { return showMessage(it) }

        File(getApplication<Application>().filesDir, "sessions").deleteRecursively()
        synchronized(segments) { segments.clear() }
        analyzed.clear()
        lastAnalysisAt = 0L

        var case = CaseState()
        if (age != null || gender != null) case = Reducer.handle(case, DoctorAction.SetDemographics(age, gender), cat)
        val dir = File(getApplication<Application>().filesDir, "sessions/${case.sessionId}").apply { mkdirs() }
        val rec = WavSegmentRecorder(dir, { settings.segmentSeconds }) { file, startMs, durMs, voiced -> onSegment(file, startMs, durMs, voiced) }
        try {
            rec.start()
        } catch (e: Exception) {
            return showMessage("Could not start the microphone: ${e.message}")
        }
        recorder = rec
        sessionSpeech = speech
        sessionNotes = notes
        _ui.value = withSettings(UiState(screen = Screen.RECORDING, mode = mode, case = case, recording = true, hasSession = true))
        startWorkers()
        startTicker()
    }

    /** Called on the recording thread whenever a segment file is closed. */
    private fun onSegment(file: File, startMs: Long, durationMs: Long, voiced: Float) {
        val seg = synchronized(segments) {
            Segment(file, startMs, durationMs, voiced, segments.size + 1).also { segments += it }
        }
        _ui.update { it.copy(segments = it.segments + SegmentStatus(seg.number, startMs, durationMs, SegState.QUEUED)) }
        queue?.trySend(seg)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (!_ui.value.recording) continue
                val r = recorder
                _ui.update {
                    it.copy(
                        elapsedSec = if (it.paused) it.elapsedSec else it.elapsedSec + 1,
                        level = r?.level ?: 0f,
                        currentSegmentSec = ((r?.currentSegmentMs ?: 0L) / 1000).toInt()
                    )
                }
            }
        }
    }

    private fun startWorkers() {
        val q = Channel<Segment>(Channel.UNLIMITED)
        queue = q
        transcribeJob = viewModelScope.launch {
            for (seg in q) transcribeSegment(seg)
        }
        val trigger = Channel<Unit>(Channel.CONFLATED)
        analysisTrigger = trigger
        analysisJob = viewModelScope.launch {
            for (u in trigger) {
                if (!analyseImmediately) {
                    val wait = lastAnalysisAt + MIN_ANALYSIS_GAP_MS - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                }
                analyseImmediately = false
                runAnalysis()
            }
        }
    }

    private fun requestAnalysis(immediate: Boolean) {
        if (_ui.value.mode != Mode.LIVE) return
        if (immediate) analyseImmediately = true
        analysisTrigger?.trySend(Unit)
    }

    /** "Suggest now": cut the current audio and update suggestions straight away. */
    fun suggestNow() {
        val st = _ui.value
        if (!st.recording) return
        analyseImmediately = true
        forceAnalysis = true
        if (!st.paused && recorder != null && st.currentSegmentSec >= 1) {
            recorder?.cutNow() // the new segment triggers the analysis once transcribed
        } else {
            requestAnalysis(true)
        }
        _ui.update { it.copy(analysisNote = "Updating suggestions…") }
    }

    fun pause() {
        if (!_ui.value.recording || _ui.value.paused) return
        _ui.update { it.copy(paused = true, analysisNote = "Paused — catching up with what was said…") }
        analyseImmediately = true
        forceAnalysis = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recorder?.stop() } // emits the last part as a segment
            if (_ui.value.pendingSegments == 0) requestAnalysis(true)
        }
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

    private fun setSeg(n: Int, f: (SegmentStatus) -> SegmentStatus) =
        _ui.update { st -> st.copy(segments = st.segments.map { if (it.number == n) f(it) else it }) }

    private suspend fun transcribeSegment(seg: Segment) {
        val cat = catalogues ?: return
        val speech = sessionSpeech ?: settings.speechModel ?: return
        if (!seg.force && seg.voiced < MIN_VOICED) {
            setSeg(seg.number) { it.copy(state = SegState.SILENT) }
            if (forceAnalysis) requestAnalysis(true)
            return
        }
        setSeg(seg.number) { it.copy(state = SegState.TRANSCRIBING, error = null) }
        try {
            val wav = withContext(Dispatchers.IO) { seg.file.readBytes() }
            val context = _ui.value.case.transcript.takeLast(6).joinToString("\n") { "${it.speaker}: ${it.text}" }
            val (result, used) = withFallback(speech, Need.AUDIO, live = true) { client ->
                val direct = client.transcribe(wav, context)
                if (direct != null) direct to Prompts.transcriptText(direct)
                else {
                    val raw = client.generate(
                        transcribePrompt,
                        listOf(
                            AiPart.Text("Previous lines, for speaker context only (do not repeat them):\n${context.ifBlank { "(start of consultation)" }}\n\nTranscribe audio segment #${seg.number}."),
                            AiPart.Wav(wav)
                        ),
                        transcribeSchema
                    )
                    Prompts.parseTranscription(raw) to raw
                }
            }
            val (lines, raw) = result
            _ui.update { st ->
                st.copy(
                    case = Reducer.reduce(st.case, LiveDelta(transcript = lines), cat, seg.startMs),
                    aiLog = (st.aiLog + "Transcript ${seg.number} (${used.id}):\n$raw").takeLast(40)
                )
            }
            setSeg(seg.number) { it.copy(state = SegState.DONE, model = used.id, lines = lines.size, error = null) }
            if (lines.isNotEmpty()) refreshHints()
            if (lines.isNotEmpty() || forceAnalysis) requestAnalysis(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setSeg(seg.number) { it.copy(state = SegState.FAILED, error = e.message) }
            addError("Segment ${seg.number}: ${e.message}")
            if (forceAnalysis) requestAnalysis(true)
        }
    }

    /** Keyword hints on the phone; also fills age/sex from the conversation when not entered. */
    private suspend fun refreshHints() {
        val cat = catalogues ?: return
        val lines = _ui.value.case.transcript
        val hints = withContext(Dispatchers.Default) { LocalAssist.analyze(lines, cat) }
        _ui.update { st ->
            var c = st.case
            var heard = st.heard
            val age = hints.ageGuess
            if (c.demographics.age == null && age != null) {
                c = Reducer.reduce(c, LiveDelta(age = age), cat)
                heard = heard + "age"
            }
            val g = hints.genderGuess
            if (c.demographics.gender == Gender.UNKNOWN && g != null) {
                c = Reducer.reduce(c, LiveDelta(gender = g.name.lowercase()), cat)
                heard = heard + "gender"
            }
            st.copy(case = c, hints = hints, heard = heard)
        }
    }

    private fun heardFields(before: CaseState, after: CaseState): Set<String> = buildSet {
        if (before.demographics.age == null && after.demographics.age != null) add("age")
        if (before.demographics.gender == Gender.UNKNOWN && after.demographics.gender != Gender.UNKNOWN) add("gender")
    }

    private fun hintText(h: LocalAssist.Hints): String? {
        val parts = mutableListOf<String>()
        if (h.conditions.isNotEmpty()) parts += "Keyword matches (may be wrong): " + h.conditions.take(4).joinToString("; ") { "${it.code} (${it.matched.take(3).joinToString(", ")})" }
        if (h.flagMentions.isNotEmpty()) parts += "Possible red-flag mentions to check: " + h.flagMentions.joinToString(", ") { it.id }
        return if (parts.isEmpty()) null else "ON-DEVICE HINTS\n" + parts.joinToString("\n")
    }

    /** Sends the transcript lines not yet analysed to the notes model and applies its update. */
    private suspend fun runAnalysis() {
        val cat = catalogues ?: return
        val model = sessionNotes ?: return
        val st = _ui.value
        val fresh = st.case.transcript.filter { it !in analyzed }
        val force = forceAnalysis
        forceAnalysis = false
        if (fresh.isEmpty() && !force) return
        if (st.case.transcript.isEmpty()) {
            _ui.update { it.copy(analysisNote = "Nothing transcribed yet.") }
            return
        }
        val earlier = st.case.transcript.filter { it in analyzed }.takeLast(8)
        val prompt = buildString {
            append(Prompts.caseSummary(st.case, cat, recentLines = 0))
            hintText(st.hints)?.let { append("\n").append(it).append("\n") }
            if (earlier.isNotEmpty()) append("\nEARLIER LINES (context only, already processed):\n").append(Prompts.transcriptText(earlier)).append("\n")
            if (fresh.isEmpty()) append("\nNEW TRANSCRIPT: (nothing new; the consultation is paused). Return empty arrays except next_questions: what should the doctor ask next given the case so far?")
            else append("\nNEW TRANSCRIPT (report what is new here):\n").append(Prompts.transcriptText(fresh))
        }
        _ui.update { it.copy(analysing = true) }
        try {
            val (result, used) = withFallback(model, Need.TEXT, live = true) { client ->
                val raw = client.generate(liveTranscriptPrompt, listOf(AiPart.Text(prompt)), liveSchema)
                Prompts.parseDelta(raw) to raw
            }
            val (delta, raw) = result
            analyzed += fresh
            lastAnalysisAt = System.currentTimeMillis()
            _ui.update { s ->
                val c = Reducer.reduce(s.case, delta.copy(transcript = emptyList()), cat)
                s.copy(
                    case = c,
                    heard = s.heard + heardFields(s.case, c),
                    analysing = false,
                    analysisFailed = false,
                    analysisNote = "Suggestions updated at ${clock(s.elapsedSec)}" + if (used.key != model.key) " (backup ${used.id})" else "",
                    aiLog = (s.aiLog + "Analysis (${used.id}):\n$raw").takeLast(40)
                )
            }
        } catch (e: CancellationException) {
            _ui.update { it.copy(analysing = false) }
            throw e
        } catch (e: Exception) {
            lastAnalysisAt = System.currentTimeMillis()
            _ui.update { it.copy(analysing = false, analysisFailed = true, analysisNote = "AI suggestions not updated (${e.message?.take(160)}). On-phone hints still work; they will retry with the next segment.") }
            addError("Live suggestions: ${e.message}")
        }
    }

    fun endConsult() {
        if (!_ui.value.recording || _ui.value.busy != null) return
        _ui.update { it.copy(busy = "Stopping the microphone…") }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recorder?.stop() }
            recorder = null
            tickerJob?.cancel()
            _ui.update { it.copy(recording = false, paused = false, level = 0f, currentSegmentSec = 0, busy = "Transcribing the last ${it.pendingSegments} segment(s)…") }
            queue?.close()
            transcribeJob?.join()
            queue = null
            analysisTrigger?.close()
            analysisJob?.cancelAndJoin()
            analysisTrigger = null
            _ui.update { it.copy(analysing = false) }
            // Second chance for failed segments: the provider may have recovered by now.
            val failed = failedSegmentList()
            if (failed.isNotEmpty()) {
                _ui.update { it.copy(busy = "Retrying ${failed.size} failed segment(s)…") }
                failed.forEach { transcribeSegment(it) }
            }
            runFinal()
        }
    }

    private fun failedSegmentList(states: Set<SegState> = setOf(SegState.FAILED)): List<Segment> {
        val numbers = _ui.value.segments.filter { it.state in states }.map { it.number }.toSet()
        return synchronized(segments) { segments.filter { it.number in numbers } }
    }

    /** Retries failed segments (and, after the consultation, rewrites the note). */
    fun retryFailed(includeSilent: Boolean = false) {
        val states = if (includeSilent) setOf(SegState.SILENT) else setOf(SegState.FAILED)
        val list = failedSegmentList(states)
        if (list.isEmpty()) return
        val q = queue
        if (q != null) {
            list.forEach { seg ->
                setSeg(seg.number) { it.copy(state = SegState.QUEUED, error = null) }
                q.trySend(seg.withForce(includeSilent))
            }
        } else {
            if (_ui.value.busy != null) return
            viewModelScope.launch {
                _ui.update { it.copy(busy = "Retrying ${list.size} segment(s)…") }
                list.forEach { transcribeSegment(it.withForce(includeSilent)) }
                runFinal()
            }
        }
    }

    fun retryFinal() {
        if (_ui.value.busy != null) return
        viewModelScope.launch { runFinal() }
    }

    /** The final note is written from the transcript, so it works with any text model. */
    private suspend fun runFinal() {
        val cat = catalogues ?: return
        val model = sessionNotes ?: settings.analysisModel ?: settings.speechModel?.takeIf { !it.transcriptionApi }
        val transcript = _ui.value.case.transcript
        if (model == null || transcript.isEmpty()) {
            _ui.update { it.copy(busy = null, screen = Screen.REVIEW, finalFailed = true, soap = Notes.localDraft(it.case, cat, it.hints)) }
            addError(
                if (model == null) "No notes model selected."
                else "No speech was transcribed, so the AI could not write a note. Check the segment errors and the speech model in Settings."
            )
            return
        }
        _ui.update { it.copy(busy = "Writing the final note with ${model.id}…") }
        try {
            val (result, used) = withFallback(model, Need.TEXT, live = false) { client ->
                val raw = client.generate(
                    finalPrompt,
                    listOf(AiPart.Text(Prompts.caseSummary(_ui.value.case, cat, recentLines = 0) + "\n\nFULL TRANSCRIPT:\n" + Prompts.transcriptText(transcript))),
                    finalSchema
                )
                Prompts.parseFinal(raw) to raw
            }
            val (fin, raw) = result
            _ui.update {
                val c = Reducer.reduce(it.case, fin.delta.copy(transcript = emptyList()), cat)
                it.copy(
                    case = c,
                    heard = it.heard + heardFields(it.case, c),
                    soap = fin.soap,
                    busy = null,
                    screen = Screen.REVIEW,
                    finalFailed = false,
                    aiLog = (it.aiLog + "Final (${used.id}):\n$raw").takeLast(40)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(busy = null, screen = Screen.REVIEW, finalFailed = true, soap = Notes.localDraft(it.case, cat, it.hints)) }
            addError("AI note failed: ${e.message}")
        }
    }

    /* ---------------- doctor actions ---------------- */

    fun act(action: DoctorAction) {
        val cat = catalogues ?: return
        _ui.update {
            val heard = when (action) {
                is DoctorAction.SetDemographics -> it.heard - setOfNotNull(action.age?.let { "age" }, action.gender?.let { "gender" })
                is DoctorAction.SettleConflict -> it.heard - action.field
                else -> it.heard
            }
            it.copy(case = Reducer.handle(it.case, action, cat), heard = heard)
        }
    }

    fun markTipDone(key: String) = _ui.update { it.copy(doneTips = it.doneTips + key) }

    fun newConsultation() {
        if (_ui.value.recording) return
        _ui.value = withSettings(UiState(screen = Screen.SETUP))
    }

    private fun addError(msg: String) = _ui.update { it.copy(errors = (it.errors + msg).takeLast(30)) }

    override fun onCleared() {
        tickerJob?.cancel()
        recorder?.stop()
        super.onCleared()
    }

    companion object {
        /** Segments with less speech than this share of their length are skipped (saves requests). */
        private const val MIN_VOICED = 0.02f
        private const val MIN_ANALYSIS_GAP_MS = 8_000L

        fun clock(sec: Int): String = (sec / 60).toString().padStart(2, '0') + ":" + (sec % 60).toString().padStart(2, '0')

        /** A short silent WAV (16 kHz mono 16-bit), used to test speech-to-text models. */
        fun silentWav(ms: Int): ByteArray {
            val samples = WavSegmentRecorder.RATE * ms / 1000
            val data = samples * 2
            val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + data)
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII)); b.putInt(16)
            b.putShort(1.toShort()); b.putShort(1.toShort())
            b.putInt(WavSegmentRecorder.RATE); b.putInt(WavSegmentRecorder.RATE * 2)
            b.putShort(2.toShort()); b.putShort(16.toShort())
            b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(data)
            return b.array()
        }
    }
}
