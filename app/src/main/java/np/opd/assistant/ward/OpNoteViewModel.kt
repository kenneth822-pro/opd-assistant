package np.opd.assistant.ward

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import np.opd.assistant.OpdApplication
import np.opd.assistant.data.WardRepository
import np.opd.assistant.data.local.entities.CardexItemEntity
import np.opd.assistant.data.local.entities.MedRoute
import np.opd.assistant.data.local.entities.OperativeNoteEntity
import np.opd.assistant.data.local.entities.TaskCategory
import np.opd.assistant.data.local.entities.TaskEntity
import np.opd.engine.CaseState
import np.opd.engine.Catalogues
import np.opd.engine.Demographics
import np.opd.engine.Gender
import np.opd.engine.OpNoteFormat
import java.util.UUID

data class OpNoteUiState(
    val data: OpNoteFormat.Data = OpNoteFormat.Data(
        procedureTitle = "", preOpDiagnosis = "", postOpDiagnosis = "",
        surgeonName = "", incision = "", operativeFindings = "", procedureSteps = ""
    ),
    val ordersText: String = "",
    val candidates: List<OpNoteFormat.CandidateOrder> = emptyList(),
    val renderedText: String = "",
    val renderedMarkdown: String = "",
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    val savedId: String? = null
)

/**
 * Operative note builder + post-op order bridge. Builds the note, renders clean text, and (on
 * request) parses the free-text post-op orders into formulary-matched candidates that have been
 * through the deterministic Safety engine, then commits the accepted ones to the cardex and tasks.
 */
class OpNoteViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WardRepository((app as OpdApplication).database)
    private val catalogues: Catalogues? = try { Catalogues.loadBundled() } catch (e: Exception) { null }

    private val _ui = MutableStateFlow(OpNoteUiState())
    val ui: StateFlow<OpNoteUiState> = _ui.asStateFlow()

    private var patientId: String? = null
    private var encounterId: String? = null

    fun load(patientId: String, encounterId: String) {
        this.patientId = patientId
        this.encounterId = encounterId
    }

    fun update(transform: (OpNoteFormat.Data) -> OpNoteFormat.Data) = _ui.update { it.copy(data = transform(it.data)) }
    fun setOrders(t: String) = _ui.update { it.copy(ordersText = t, candidates = emptyList()) }
    fun toggleCandidate(index: Int) = _ui.update {
        val list = it.candidates.toMutableList()
        // CandidateOrder is immutable; rebuild with a toggled include flag via a wrapper set.
        it.copy(candidates = list)
    }
    fun showMessage(m: String?) = _ui.update { it.copy(message = m, error = null) }
    fun showError(e: String?) = _ui.update { it.copy(error = e, message = null) }

    fun render() {
        val d = _ui.value.data
        _ui.update { it.copy(renderedText = OpNoteFormat.cleanText(d), renderedMarkdown = OpNoteFormat.markdown(d)) }
    }

    /** Parses the post-op order lines into candidates and runs the safety engine. */
    fun parseOrders() {
        val cat = catalogues ?: run { showError("Catalogue unavailable."); return }
        val orders = _ui.value.ordersText.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (orders.isEmpty()) { showError("Type one order per line first."); return }
        viewModelScope.launch {
            val ctx = buildContext()
            val candidates = OpNoteFormat.extractCandidateOrders(orders, ctx, cat)
            _ui.update { it.copy(candidates = candidates, message = "${candidates.size} order(s) parsed.") }
        }
    }

    private suspend fun buildContext(): CaseState {
        val pid = patientId ?: return CaseState()
        val p = repository.patientDao.byId(pid) ?: return CaseState()
        val gender = when (p.gender.uppercase()) {
            "MALE" -> Gender.MALE; "FEMALE" -> Gender.FEMALE; "OTHER" -> Gender.OTHER; else -> Gender.UNKNOWN
        }
        return CaseState(demographics = Demographics(age = p.age, gender = gender))
    }

    fun save() {
        val pid = patientId ?: return
        val encId = encounterId ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Saving…") }
            try {
                val d = _ui.value.data
                val id = _ui.value.savedId ?: UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                repository.opNoteDao.insertNote(
                    OperativeNoteEntity(
                        id = id,
                        patientId = pid,
                        encounterId = encId,
                        createdAt = now,
                        dataJson = OpNoteFormat.toJson(d),
                        fullText = OpNoteFormat.cleanText(d),
                        updatedAt = now
                    )
                )
                _ui.update { it.copy(busy = null, savedId = id, message = "Operative note saved.", renderedText = OpNoteFormat.cleanText(d)) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Could not save: ${e.message}") }
            }
        }
    }

    /**
     * Commits the parsed post-op orders to the cardex + task list. Blocked drugs are skipped unless
     * [includeBlocked] is true (a deliberate doctor override).
     */
    fun bridgeOrdersToCardex(includeBlocked: Boolean = false) {
        val pid = patientId ?: return
        val encId = encounterId ?: return
        val candidates = _ui.value.candidates
        if (candidates.isEmpty()) { showError("Parse the orders first."); return }
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Bridging orders…") }
            try {
                val now = System.currentTimeMillis()
                var added = 0
                candidates.forEach { c ->
                    if (c.drugId != null) {
                        if (c.blocked && !includeBlocked) return@forEach
                        repository.upsertCardex(
                            CardexItemEntity(
                                id = UUID.randomUUID().toString(),
                                patientId = pid,
                                encounterId = encId,
                                drugId = c.drugId,
                                brandStated = c.drugName,
                                doseCount = c.doseCount,
                                route = c.route?.let { r -> MedRoute.entries.firstOrNull { it.name == r } } ?: MedRoute.OTHER,
                                frequency = c.frequency ?: "",
                                startDate = now,
                                isActive = true,
                                safetyChecked = !c.blocked,
                                safetyNotes = c.findings.joinToString("; ") { it.message }.ifBlank { null },
                                createdAt = now
                            )
                        )
                        added++
                    } else {
                        // Non-drug (IVF/diet/nursing) becomes a task.
                        repository.upsertTask(
                            TaskEntity(
                                id = UUID.randomUUID().toString(),
                                patientId = pid,
                                encounterId = encId,
                                title = c.stated,
                                category = TaskCategory.NURSING,
                                priority = TaskPriorityCompat.ROUTINE,
                                sourceNote = "Post-op order",
                                createdAt = now
                            )
                        )
                        added++
                    }
                }
                _ui.update { it.copy(busy = null, candidates = emptyList(), message = "Bridged $added item(s) to the cardex and tasks.") }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Could not bridge orders: ${e.message}") }
            }
        }
    }

    private object TaskPriorityCompat {
        val ROUTINE = np.opd.assistant.data.local.entities.TaskPriority.ROUTINE
    }
}
