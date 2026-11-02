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
import np.opd.assistant.data.WardRepository
import np.opd.assistant.data.local.entities.LabResultEntity
import np.opd.engine.J
import np.opd.engine.LabCatalogue
import np.opd.engine.Prompts
import java.util.UUID

/** A parsed candidate lab result awaiting confirmation before it is stored. */
data class LabCandidate(
    val testCode: String,
    val displayName: String,
    val category: String,
    val numericValue: Double,
    val unit: String,
    val collectedAt: Long,
    val direction: String,
    val isPanic: Boolean,
    val reference: String,
    val include: Boolean = true
)

data class LabUiState(
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** Pending candidates from a scan, shown for confirmation. */
    val candidates: List<LabCandidate> = emptyList(),
    val scanText: String = ""
)

/**
 * Lab flowsheet writes: manual numeric entry, and AI-assisted extraction from a scanned paper slip
 * (image) or a spoken/typed reading. Extraction is only a proposal — results are stored after the
 * doctor confirms the checklist.
 */
class LabViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val localModels = np.opd.assistant.model.LocalModelManager(app)
    private val repository = WardRepository((app as OpdApplication).database)

    private val _ui = MutableStateFlow(LabUiState())
    val ui: StateFlow<LabUiState> = _ui.asStateFlow()

    fun showMessage(m: String?) = _ui.update { it.copy(message = m, error = null) }
    fun showError(e: String?) = _ui.update { it.copy(error = e, message = null) }
    fun setScanText(t: String) = _ui.update { it.copy(scanText = t) }
    fun clearCandidates() = _ui.update { it.copy(candidates = emptyList()) }
    fun toggleCandidate(index: Int) = _ui.update {
        val list = it.candidates.toMutableList()
        if (index in list.indices) list[index] = list[index].copy(include = !list[index].include)
        it.copy(candidates = list)
    }

    /** Stores one numeric result, evaluating direction/panic from the catalogue. */
    fun addManual(patientId: String, encounterId: String, code: String, value: Double, unit: String, timestamp: Long) {
        val def = LabCatalogue.definition(code)
        if (def == null) { showError("Unknown test code: $code"); return }
        viewModelScope.launch {
            try {
                repository.labDao.insert(build(patientId, encounterId, def, value, unit.ifBlank { def.unit }, timestamp))
                _ui.update { it.copy(message = "Saved ${def.displayName} $value ${unit.ifBlank { def.unit }}.") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Could not save lab: ${e.message}") }
            }
        }
    }

    /** Extracts candidates from a photo (WAV not applicable) — audio is transcribed first. */
    fun extractFromImage(patientId: String, encounterId: String, imageBytes: ByteArray, mime: String) {
        val notes = settings.analysisModel ?: settings.speechModel
        if (notes == null) { showError("Choose a notes model in Settings first."); return }
        if (!notes.audioInput) { showError("The selected model cannot read images. Pick a multimodal (AUDIO) model, or type/paste the slip text instead."); return }
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Reading the lab slip…", error = null, candidates = emptyList()) }
            try {
                val client = AiClientFactory.create(getApplication(), settings, localModels, notes)
                val reply = withContext(Dispatchers.IO) {
                    client.generate(
                        Prompts.labExtractionSystemPrompt(),
                        listOf(AiPart.Image(imageBytes, mime)),
                        Prompts.labExtractionSchema()
                    )
                }
                parseCandidates(reply)
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Could not read the slip: ${e.message}") }
            }
        }
    }

    /** Extracts candidates from typed/transcribed text (no image needed). */
    fun extractFromText(patientId: String, encounterId: String, text: String) {
        if (text.isBlank()) { showError("Type or paste the lab slip text first."); return }
        val notes = settings.analysisModel ?: settings.speechModel ?: run { showError("Choose a notes model in Settings first."); return }
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Reading the lab text…", error = null, candidates = emptyList()) }
            try {
                val client = AiClientFactory.create(getApplication(), settings, localModels, notes)
                val reply = withContext(Dispatchers.IO) {
                    client.generate(Prompts.labExtractionSystemPrompt(), listOf(AiPart.Text(text)), Prompts.labExtractionSchema())
                }
                parseCandidates(reply)
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Could not read the lab text: ${e.message}") }
            }
        }
    }

    private fun parseCandidates(reply: String) {
        val root = try { J.parse(Prompts.extractJson(reply)) as? J.Obj } catch (e: Exception) { null }
        val list = root?.objs("results")?.mapNotNull { o ->
            val name = o.strOr("test_name", "")
            val value = o.dbl("value") ?: return@mapNotNull null
            val def = LabCatalogue.findByText(name)
            val eval = LabCatalogue.evaluateOrUnknown(def, value)
            LabCandidate(
                testCode = def?.code ?: "UNKNOWN",
                displayName = def?.displayName ?: name,
                category = def?.category?.name ?: "OTHER",
                numericValue = value,
                unit = o.strOr("unit", def?.unit ?: ""),
                collectedAt = System.currentTimeMillis(),
                direction = eval.direction.name,
                isPanic = eval.isPanic,
                reference = eval.reference,
                include = def != null
            )
        } ?: emptyList()
        _ui.update {
            it.copy(
                busy = null,
                candidates = list,
                message = if (list.isEmpty()) "No numeric results found." else "Found ${list.size} result(s). Confirm before saving."
            )
        }
    }

    /** Stores the confirmed candidates for the patient. */
    fun confirmCandidates(patientId: String, encounterId: String) {
        val accepted = _ui.value.candidates.filter { it.include }
        if (accepted.isEmpty()) { showError("Nothing selected to save."); return }
        viewModelScope.launch {
            try {
                val rows = accepted.map { c ->
                    LabResultEntity(
                        id = UUID.randomUUID().toString(),
                        patientId = patientId,
                        encounterId = encounterId,
                        timestamp = c.collectedAt,
                        testCode = c.testCode,
                        displayName = c.displayName,
                        category = c.category,
                        numericValue = c.numericValue,
                        unit = c.unit,
                        isAbnormal = c.direction != "NORMAL",
                        abnormalDirection = c.direction,
                        isPanic = c.isPanic,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                repository.labDao.insertLabs(rows)
                _ui.update { it.copy(candidates = emptyList(), message = "Saved ${rows.size} lab result(s).") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Could not save results: ${e.message}") }
            }
        }
    }

    private fun build(patientId: String, encounterId: String, def: LabCatalogue.Definition, value: Double, unit: String, timestamp: Long): LabResultEntity {
        val eval = LabCatalogue.evaluate(def, value)
        return LabResultEntity(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            encounterId = encounterId,
            timestamp = timestamp,
            testCode = def.code,
            displayName = def.displayName,
            category = def.category.name,
            numericValue = value,
            unit = unit,
            isAbnormal = eval.isAbnormal,
            abnormalDirection = eval.direction.name,
            isPanic = eval.isPanic,
            updatedAt = System.currentTimeMillis()
        )
    }
}
