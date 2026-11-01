package np.opd.assistant.ward

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import np.opd.assistant.OpdApplication
import np.opd.assistant.SettingsStore
import np.opd.assistant.ai.AiClientFactory
import np.opd.assistant.ai.AiPart
import np.opd.assistant.audio.RecordingService
import np.opd.assistant.audio.WavSegmentRecorder
import np.opd.assistant.data.WardRepository
import np.opd.assistant.data.local.entities.CardexItemEntity
import np.opd.assistant.data.local.entities.ConsultEntity
import np.opd.assistant.data.local.entities.ConsultUrgency
import np.opd.assistant.data.local.entities.MedRoute
import np.opd.assistant.data.local.entities.TaskCategory
import np.opd.assistant.data.local.entities.TaskEntity
import np.opd.assistant.data.local.entities.TaskPriority
import np.opd.assistant.model.LocalModelManager
import np.opd.engine.CardexDiff
import np.opd.engine.CardexLine
import np.opd.engine.CaseState
import np.opd.engine.Catalogues
import np.opd.engine.ChangeAction
import np.opd.engine.Demographics
import np.opd.engine.Gender
import np.opd.engine.OrderKind
import np.opd.engine.Prompts
import np.opd.engine.VerbalOrder
import np.opd.engine.VerbalOrders
import java.io.File
import java.util.UUID

data class RoundUiState(
    val recording: Boolean = false,
    val elapsedSec: Int = 0,
    val busy: String? = null,
    val diff: CardexDiff? = null,
    val transcript: List<String> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    /** START meds the doctor chose to skip (blocked lines are never auto-added). */
    val skippedStartIndices: Set<Int> = emptySet()
)

/**
 * Records a senior/consultant ward round and turns it into a reviewable cardex diff.
 *
 * Flow: record → (audio or speech-to-text) → [VerbalOrders.parse] → [VerbalOrders.diff] with a
 * patient context → the doctor reviews the diff → [approve] commits START/STOP to the cardex and
 * creates tasks/consults. Deterministic [np.opd.engine.Safety] checks run inside the diff; the AI
 * never chooses drugs or doses.
 */
class RoundViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val localModels = LocalModelManager(app)
    private val repository = WardRepository((app as OpdApplication).database)
    private val catalogues: Catalogues? = try { Catalogues.loadBundled() } catch (e: Exception) { null }

    private val _ui = MutableStateFlow(RoundUiState())
    val ui: StateFlow<RoundUiState> = _ui.asStateFlow()

    private var recorder: WavSegmentRecorder? = null
    private var tickerJob: kotlinx.coroutines.Job? = null
    private var roundDir: File? = null
    private var segmentFiles: MutableList<File> = mutableListOf()
    private var patientId: String? = null
    private var encounterId: String? = null

    /** Raw lines captured alongside the structured order, for the review screen. */
    private var rawLines: MutableList<String> = mutableListOf()

    fun startRound(patientId: String, encounterId: String) {
        if (_ui.value.recording) return
        if (catalogues == null) { _ui.update { it.copy(error = "Clinical catalogue unavailable.") }; return }
        this.patientId = patientId
        this.encounterId = encounterId
        segmentFiles = mutableListOf()
        rawLines = mutableListOf()
        val dir = File(getApplication<Application>().cacheDir, "round_${System.currentTimeMillis()}")
        dir.mkdirs()
        roundDir = dir
        try {
            val rec = WavSegmentRecorder(
                dir = dir,
                targetSeconds = { 3600 },
                onSegment = { file, _, _, _ -> segmentFiles.add(file) },
                onRecorderError = { msg -> _ui.update { it.copy(error = msg, recording = false) } }
            )
            rec.start()
            recorder = rec
            RecordingService.start(getApplication())
            _ui.update { it.copy(recording = true, error = null, message = null, diff = null) }
            startTicker()
        } catch (e: Exception) {
            _ui.update { it.copy(recording = false, error = "Could not start recording: ${e.message}") }
        }
    }

    fun stopAndAnalyse() {
        val rec = recorder ?: return
        _ui.update { it.copy(recording = false, busy = "Stopping recording…") }
        viewModelScope.launch {
            rec.stop()
            RecordingService.stop(getApplication())
            tickerJob?.cancel()
            // The single long segment is emitted during stop(); fall back to whatever files exist.
            if (segmentFiles.isEmpty() && roundDir != null) {
                roundDir?.listFiles()?.filter { it.extension == "wav" }?.let { segmentFiles.addAll(it) }
            }
            analyse()
        }
    }

    fun cancelRound() {
        recorder?.stop()
        RecordingService.stop(getApplication())
        tickerJob?.cancel()
        recorder = null
        roundDir?.deleteRecursively()
        _ui.update { RoundUiState() }
    }

    fun skipStart(index: Int) = _ui.update { it.copy(skippedStartIndices = it.skippedStartIndices + index) }
    fun unskipStart(index: Int) = _ui.update { it.copy(skippedStartIndices = it.skippedStartIndices - index) }
    fun clearDiff() = _ui.update { it.copy(diff = null, message = null, error = null) }
    fun showMessage(m: String?) = _ui.update { it.copy(message = m) }

    // ---- transcription + extraction ----

    private fun wavBytes(): ByteArray? {
        val files = segmentFiles.sortedBy { it.name }
        if (files.isEmpty()) return null
        return try {
            val datas = files.map { it.readBytes() }
            // All parts share the same format/header (16 kHz mono PCM16); keep the first header
            // and concatenate every part's data section into one WAV.
            val header = datas.first().copyOfRange(0, 44)
            val bodySize = datas.sumOf { it.size - 44 }
            val out = ByteArray(44 + bodySize)
            var pos = 44
            for (d in datas) {
                val n = d.size - 44
                if (n <= 0) continue
                System.arraycopy(d, 44, out, pos, n)
                pos += n
            }
            System.arraycopy(header, 0, out, 0, 44)
            putIntLE(out, 4, 36 + bodySize) // RIFF chunk size
            putIntLE(out, 40, bodySize) // data chunk size
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun putIntLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private suspend fun analyse() {
        val cat = catalogues ?: run { _ui.update { it.copy(busy = null, error = "Catalogue unavailable.") }; return }
        _ui.update { it.copy(busy = "Reading the round…", error = null) }
        try {
            val order = withContext(Dispatchers.IO) { extractOrder(cat) }
            val active = buildCardexLines()
            val context = buildContext()
            val diff = VerbalOrders.diff(order, active, context, cat)
            rawLines = order.transcript.map { "${it.speaker}: ${it.text}" }.toMutableList()
            // Auto-skip blocked START meds so they are never accidentally committed.
            val blockedStarts = diff.items.withIndex()
                .filter { (_, it) -> it.action == ChangeAction.START && it.kind == OrderKind.MED && it.blocked }
                .map { it.index }
                .toSet()
            _ui.update {
                it.copy(busy = null, diff = diff, transcript = rawLines.toList(), skippedStartIndices = blockedStarts)
            }
        } catch (e: Exception) {
            _ui.update { it.copy(busy = null, error = "Could not read the round: ${e.message}") }
        }
    }

    private suspend fun extractOrder(cat: Catalogues): VerbalOrder {
        val notes = settings.analysisModel ?: settings.speechModel
            ?: throw IllegalStateException("Choose a notes model in Settings first.")
        val system = Prompts.verbalOrderSystemPrompt() + "\n\n" + verbalOrderReference(cat)
        val schema = Prompts.verbalOrderSchema()
        val wav = wavBytes() ?: throw IllegalStateException("No audio was captured.")

        return if (notes.audioInput) {
            val client = AiClientFactory.create(getApplication(), settings, localModels, notes, fast = false)
            val reply = client.generate(system, listOf(AiPart.Wav(wav)), schema)
            VerbalOrders.parse(reply)
        } else {
            // Text-only notes model: transcribe with the speech model, then extract.
            val speech = settings.speechModel ?: notes
            val client = AiClientFactory.create(getApplication(), settings, localModels, speech)
            val lines = client.transcribe(wav, "Ward round")
                ?: throw IllegalStateException("The speech model could not transcribe this round.")
            val transcript = Prompts.transcriptText(lines)
            val notesClient = AiClientFactory.create(getApplication(), settings, localModels, notes)
            val reply = notesClient.generate(system, listOf(AiPart.Text(transcript)), schema)
            VerbalOrders.parse(reply).copy(transcript = lines)
        }
    }

    private fun verbalOrderReference(cat: Catalogues): String = buildString {
        appendLine("FORMULARY DRUG NAMES (use to recognise medicines):")
        cat.drugs.values.take(60).forEach { appendLine("- ${it.generic}") }
    }

    private suspend fun buildCardexLines(): List<CardexLine> {
        val encId = encounterId ?: return emptyList()
        return repository.cardexDao.activeForPatient(patientId ?: return emptyList()).map { row ->
            CardexLine(row.id, row.drugId, row.brandStated, row.doseCount, row.route.name, row.frequency)
        }
    }

    private suspend fun buildContext(): CaseState {
        val pid = patientId ?: return CaseState()
        val patient = repository.patientDao.byId(pid) ?: return CaseState()
        val gender = when (patient.gender.uppercase()) {
            "MALE" -> Gender.MALE
            "FEMALE" -> Gender.FEMALE
            "OTHER" -> Gender.OTHER
            else -> Gender.UNKNOWN
        }
        return CaseState(demographics = Demographics(age = patient.age, gender = gender))
    }

    // ---- commit ----

    fun approve() {
        val diff = _ui.value.diff ?: return
        val pid = patientId ?: return
        val encId = encounterId ?: return
        val skipped = _ui.value.skippedStartIndices
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Saving…") }
            try {
                val now = System.currentTimeMillis()
                diff.items.forEachIndexed { index, item ->
                    when {
                        item.kind == OrderKind.MED && item.action == ChangeAction.START && index !in skipped -> {
                            repository.upsertCardex(
                                CardexItemEntity(
                                    id = UUID.randomUUID().toString(),
                                    patientId = pid,
                                    encounterId = encId,
                                    drugId = item.matchedDrugId,
                                    brandStated = item.label,
                                    doseCount = null,
                                    route = MedRoute.OTHER,
                                    frequency = "",
                                    startDate = now,
                                    isActive = true,
                                    safetyChecked = !item.blocked,
                                    safetyNotes = item.findings.joinToString("; ") { it.message }.ifBlank { null },
                                    createdAt = now
                                )
                            )
                        }
                        item.kind == OrderKind.MED && item.action == ChangeAction.STOP && item.targetLineId != null -> {
                            val target = item.targetLineId
                            if (target != null) repository.stopCardex(target)
                        }
                        item.kind == OrderKind.INVESTIGATION -> addTask(pid, encId, item.label, TaskCategory.LAB, now)
                        item.kind == OrderKind.NURSING -> addTask(pid, encId, item.label, TaskCategory.NURSING, now)
                        item.kind == OrderKind.DIET -> addTask(pid, encId, item.label, TaskCategory.DIET, now)
                        item.kind == OrderKind.REFERRAL -> repository.upsertConsult(
                            ConsultEntity(
                                id = UUID.randomUUID().toString(),
                                patientId = pid,
                                encounterId = encId,
                                targetSpecialty = item.label,
                                clinicalQuestion = "",
                                urgency = ConsultUrgency.ROUTINE,
                                requestedAt = now
                            )
                        )
                    }
                }
                _ui.update { it.copy(busy = null, diff = null, message = "Round changes applied.", transcript = emptyList()) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Could not save changes: ${e.message}") }
            }
        }
    }

    private suspend fun addTask(patientId: String, encounterId: String, title: String, category: TaskCategory, now: Long) {
        repository.upsertTask(
            TaskEntity(
                id = UUID.randomUUID().toString(),
                patientId = patientId,
                encounterId = encounterId,
                title = title,
                category = category,
                priority = TaskPriority.ROUTINE,
                sourceNote = "Ward round",
                createdAt = now
            )
        )
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            var sec = 0
            while (_ui.value.recording) {
                kotlinx.coroutines.delay(1000)
                sec++
                _ui.update { it.copy(elapsedSec = sec) }
            }
        }
    }

    override fun onCleared() {
        recorder?.stop()
        RecordingService.stop(getApplication())
    }
}
