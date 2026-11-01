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
import np.opd.assistant.data.local.entities.ConsultStatus
import np.opd.assistant.model.LocalModelManager
import np.opd.engine.CensusConsult
import np.opd.engine.CensusPatient
import np.opd.engine.CensusTask
import np.opd.engine.WardCensus
import np.opd.engine.WardHandover
import np.opd.engine.WardPrompts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WardQueryUiState(
    val question: String = "",
    val answer: String = "",
    val localSbar: String = "",
    val busy: String? = null,
    val error: String? = null,
    val censusSize: Int = 0
)

/**
 * "Ask the Ward": builds a census snapshot from the ward database and answers a clinician's
 * free-text question with the notes model, or assembles a deterministic SBAR handover offline.
 * The AI only reads the snapshot; it never changes patient data.
 */
class WardQueryViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val localModels = LocalModelManager(app)
    private val repository = WardRepository((app as OpdApplication).database)

    private val _ui = MutableStateFlow(WardQueryUiState())
    val ui: StateFlow<WardQueryUiState> = _ui.asStateFlow()

    private var census: WardCensus = WardCensus()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun setQuestion(q: String) = _ui.update { it.copy(question = q) }

    /** Rebuilds the census snapshot from the local database. Safe to call repeatedly. */
    fun refreshCensus() {
        viewModelScope.launch {
            try {
                val c = withContext(Dispatchers.IO) { buildCensus() }
                census = c
                val sbar = withContext(Dispatchers.Default) { WardHandover.sbar(c) }
                _ui.update { it.copy(censusSize = c.size, localSbar = sbar) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Could not read the census: ${e.message}") }
            }
        }
    }

    private suspend fun buildCensus(): WardCensus {
        // search("") matches every active patient (the query already filters status = 'ACTIVE').
        val active = repository.patientDao.search("")
        val rows = active.map { p ->
            val encounters = kotlinx.coroutines.flow.first(repository.encounterDao.observeForPatient(p.id))
            val enc = encounters.firstOrNull { it.dischargeDatetime == null } ?: encounters.firstOrNull()
            val cardex = if (enc != null) repository.cardexDao.activeForPatient(p.id) else emptyList()
            val tasks = if (enc != null) kotlinx.coroutines.flow.first(repository.taskDao.observeForEncounter(enc.id)) else emptyList()
            val consults = if (enc != null) kotlinx.coroutines.flow.first(repository.consultDao.observeForEncounter(enc.id)) else emptyList()
            CensusPatient(
                patientId = p.id,
                name = p.name,
                age = p.age,
                sex = p.gender.lowercase(),
                ward = p.currentWard.name.replace('_', ' '),
                bed = p.bedNumber,
                unit = enc?.unit?.name?.replace('_', ' ') ?: "",
                mrn = p.mrn,
                admissionDate = enc?.admissionDatetime?.takeIf { it > 0 }?.let { dateFmt.format(Date(it)) } ?: "",
                workingDiagnosis = enc?.finalDiagnosis ?: "",
                activeMedications = cardex.map { it.brandStated },
                pendingLabs = tasks.filter { !it.isCompleted && it.category.name == "LAB" }.map { it.title },
                openTasks = tasks.filter { !it.isCompleted }.map { CensusTask(it.title, it.category.name, it.priority.name) },
                outstandingConsults = consults.filter { it.status != ConsultStatus.DONE }.map { CensusConsult(it.targetSpecialty, it.clinicalQuestion, it.urgency.name) },
                flags = flagList(enc?.unit?.name, p.currentWard.name)
            )
        }
        return WardCensus(
            generatedAt = dateFmt.format(Date(System.currentTimeMillis())),
            patients = rows
        )
    }

    private fun flagList(unit: String?, ward: String): List<String> = buildList {
        if (ward == "ICU") add("Ventilated")
        if (ward == "SEMI_ICU") add("HDU")
    }

    /** Answers the current question using the notes model over the census snapshot. */
    fun ask() {
        val q = _ui.value.question.trim()
        if (q.isBlank()) { _ui.update { it.copy(error = "Type a question first.") }; return }
        val notes = settings.analysisModel ?: settings.speechModel
        if (notes == null) { _ui.update { it.copy(error = "Choose a notes model in Settings first.") }; return }
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Thinking…", error = null, answer = "") }
            try {
                if (census.size == 0 && _ui.value.censusSize == 0) {
                    census = withContext(Dispatchers.IO) { buildCensus() }
                }
                val snapshot = census.snapshot()
                val client = AiClientFactory.create(getApplication(), settings, localModels, notes)
                val userText = "WARD CENSUS SNAPSHOT:\n$snapshot\n\nCLINICIAN QUESTION:\n$q"
                val reply = withContext(Dispatchers.IO) {
                    client.generate(WardPrompts.askWardSystemPrompt(), listOf(AiPart.Text(userText)), null)
                }
                _ui.update { it.copy(busy = null, answer = reply.trim()) }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        busy = null,
                        error = "Could not answer: ${e.message}. Showing the offline census summary instead.",
                        answer = WardHandover.sbar(census)
                    )
                }
            }
        }
    }

    /** Generates the SBAR handover with the notes model, falling back to the offline version. */
    fun generateHandover() {
        val notes = settings.analysisModel ?: settings.speechModel
        if (notes == null) {
            _ui.update { it.copy(answer = WardHandover.sbar(census), error = "No model configured; showing the offline SBAR handover.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Writing handover…", error = null) }
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    if (census.size == 0) buildCensus().also { census = it } else census
                }.snapshot()
                val client = AiClientFactory.create(getApplication(), settings, localModels, notes)
                val reply = withContext(Dispatchers.IO) {
                    client.generate(WardPrompts.handoverSystemPrompt(), listOf(AiPart.Text(snapshot)), null)
                }
                _ui.update { it.copy(busy = null, answer = reply.trim()) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Handover model failed: ${e.message}. Showing the offline SBAR.", answer = WardHandover.sbar(census)) }
            }
        }
    }

    fun clearAnswer() = _ui.update { it.copy(answer = "", error = null) }
}
