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
import np.opd.assistant.data.WardRepository
import np.opd.assistant.data.local.entities.CardexItemEntity
import np.opd.assistant.data.local.entities.DischargeEntity
import np.opd.assistant.data.local.entities.DischargeKind
import np.opd.assistant.data.local.entities.DischargeStatus
import np.opd.assistant.data.local.entities.EncounterEntity
import np.opd.assistant.data.local.entities.PatientEntity
import np.opd.engine.Catalogues
import np.opd.engine.DischargeFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class DischargeUiState(
    val kind: DischargeKind = DischargeKind.INPATIENT,
    val status: DischargeStatus = DischargeStatus.DRAFT,
    val ward: String = "",
    val bed: String = "",
    val unit: String = "",
    val consultant: String = "",
    val admissionDate: String = "",
    val dischargeDate: String = "",
    val procedureName: String = "",
    val preoperativeDiagnosis: String = "",
    val postoperativeDiagnosis: String = "",
    val operativeFindings: String = "",
    val presentingComplaints: String = "",
    val hospitalCourse: String = "",
    val dischargeVitals: String = "",
    val woundNursingAdvice: String = "",
    val redFlagsReturn: String = "",
    val followUp: String = "",
    val medications: List<DischargeFormat.DischargeMed> = emptyList(),
    val renderedText: String = "",
    val renderedMarkdown: String = "",
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    val savedId: String? = null
) {
    fun toInput(patient: PatientEntity?): DischargeFormat.DischargeInput =
        DischargeFormat.DischargeInput(
            kind = if (kind == DischargeKind.ER) DischargeFormat.Kind.ER else DischargeFormat.Kind.INPATIENT,
            patientName = patient?.name ?: "",
            age = patient?.age,
            sex = patient?.gender.lowercase().replaceFirstChar { it.uppercase() },
            mrn = patient?.mrn,
            ward = ward,
            bed = bed,
            unit = unit,
            consultant = consultant,
            admissionDate = admissionDate,
            dischargeDate = dischargeDate,
            procedureName = procedureName.ifBlank { null },
            preoperativeDiagnosis = preoperativeDiagnosis.ifBlank { null },
            postoperativeDiagnosis = postoperativeDiagnosis.ifBlank { null },
            operativeFindings = operativeFindings.ifBlank { null },
            presentingComplaints = presentingComplaints,
            hospitalCourse = hospitalCourse,
            dischargeVitals = dischargeVitals,
            homeMedications = medications,
            woundNursingAdvice = woundNursingAdvice,
            redFlagsReturn = redFlagsReturn,
            followUp = followUp
        )
}

/**
 * Builds, edits and saves a discharge summary for an encounter, prefilled from the ward data
 * (patient, encounter, active cardex). The rendered plain text and markdown are produced by
 * [DischargeFormat] so the export is reproducible.
 */
class DischargeViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WardRepository((app as OpdApplication).database)
    private val catalogues: Catalogues? = try { Catalogues.loadBundled() } catch (e: Exception) { null }

    private val _ui = MutableStateFlow(DischargeUiState())
    val ui: StateFlow<DischargeUiState> = _ui.asStateFlow()

    private var patientId: String? = null
    private var encounterId: String? = null
    private var patient: PatientEntity? = null
    private var encounter: EncounterEntity? = null

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** Loads an existing draft/summary if present, otherwise prefills from ward data. */
    fun load(patientId: String, encounterId: String) {
        this.patientId = patientId
        this.encounterId = encounterId
        _ui.update { it.copy(busy = "Loading…", error = null, message = null) }
        viewModelScope.launch {
            try {
                patient = repository.patientDao.byId(patientId)
                encounter = repository.encounterDao.byId(encounterId)
                val existing = repository.dischargeDao.latestForEncounter(encounterId)
                val activeCardex = repository.cardexDao.activeForPatient(patientId)
                if (existing != null) {
                    _ui.value = existing.toUi().copy(
                        ward = patient?.currentWard?.name?.replace('_', ' ') ?: "",
                        bed = patient?.bedNumber ?: "",
                        unit = encounter?.unit?.name?.replace('_', ' ') ?: ""
                    )
                    render()
                } else {
                    _ui.value = prefill(patient, encounter, activeCardex)
                    _ui.update { it.copy(busy = null) }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Could not load discharge: ${e.message}") }
            }
        }
    }

    private fun prefill(p: PatientEntity?, e: EncounterEntity?, cardex: List<CardexItemEntity>): DischargeUiState {
        val kind = if (e?.encounterType == np.opd.assistant.data.local.entities.EncounterType.ER) DischargeKind.ER else DischargeKind.INPATIENT
        return DischargeUiState(
            kind = kind,
            ward = p?.currentWard?.name?.replace('_', ' ') ?: "",
            bed = p?.bedNumber ?: "",
            unit = e?.unit?.name?.replace('_', ' ') ?: "",
            consultant = e?.consultantName ?: "",
            admissionDate = e?.admissionDatetime?.takeIf { it > 0 }?.let { fmt.format(Date(it)) } ?: "",
            dischargeDate = fmt.format(Date(System.currentTimeMillis())),
            presentingComplaints = "",
            hospitalCourse = "",
            medications = cardex.map { it.toDischargeMed() },
            busy = null
        )
    }

    private fun CardexItemEntity.toDischargeMed(): DischargeFormat.DischargeMed {
        val drug = drugId?.let { catalogues?.drugs?.get(it) }
        return DischargeFormat.DischargeMed(
            name = brandStated.ifBlank { drug?.generic ?: "Unknown" },
            dose = doseCount?.let { formatCount(it) } ?: "",
            route = if (route.name == "OTHER") "" else route.name,
            frequency = frequency,
            instructionNe = ""
        )
    }

    private fun formatCount(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    fun setKind(kind: DischargeKind) = _ui.update { it.copy(kind = kind) }
    fun update(transform: (DischargeUiState) -> DischargeUiState) = _ui.update(transform)

    fun addMedication(med: DischargeFormat.DischargeMed) = _ui.update { it.copy(medications = it.medications + med) }
    fun removeMedication(index: Int) = _ui.update { it.copy(medications = it.medications.filterIndexed { i, _ -> i != index }) }

    /** Renders the current form to plain text + markdown using the pure formatter. */
    fun render() {
        val st = _ui.value
        val input = st.toInput(patient)
        val text = DischargeFormat.plainText(input)
        val md = DischargeFormat.markdown(input)
        _ui.update { it.copy(renderedText = text, renderedMarkdown = md) }
    }

    fun save() {
        val pid = patientId ?: return
        val encId = encounterId ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Saving…") }
            try {
                val st = _ui.value
                val input = st.toInput(patient)
                val text = withContext(Dispatchers.Default) { DischargeFormat.plainText(input) }
                val md = withContext(Dispatchers.Default) { DischargeFormat.markdown(input) }
                val id = st.savedId ?: UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                repository.upsertDischarge(
                    DischargeEntity(
                        id = id,
                        patientId = pid,
                        encounterId = encId,
                        kind = st.kind,
                        status = st.status,
                        consultantName = st.consultant,
                        admissionDate = encounter?.admissionDatetime,
                        dischargeDate = now,
                        procedureName = st.procedureName.ifBlank { null },
                        preoperativeDiagnosis = st.preoperativeDiagnosis.ifBlank { null },
                        postoperativeDiagnosis = st.postoperativeDiagnosis.ifBlank { null },
                        operativeFindings = st.operativeFindings.ifBlank { null },
                        presentingComplaints = st.presentingComplaints,
                        hospitalCourse = st.hospitalCourse,
                        dischargeVitals = st.dischargeVitals,
                        homeMedications = st.medications.joinToString("\n") { it.name },
                        woundNursingAdvice = st.woundNursingAdvice,
                        redFlagsReturn = st.redFlagsReturn,
                        followUp = st.followUp,
                        renderedText = text,
                        renderedMarkdown = md,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                _ui.update { it.copy(busy = null, message = "Discharge summary saved.", savedId = id, renderedText = text, renderedMarkdown = md) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Could not save: ${e.message}") }
            }
        }
    }

    fun markFinal() = _ui.update { it.copy(status = DischargeStatus.FINAL) }

    /** Convenience: render then return the plain text for the clipboard. */
    fun plainTextNow(): String {
        val st = _ui.value
        return DischargeFormat.plainText(st.toInput(patient))
    }

    fun markdownNow(): String {
        val st = _ui.value
        return DischargeFormat.markdown(st.toInput(patient))
    }

    fun showMessage(m: String?) = _ui.update { it.copy(message = m) }

    private fun DischargeEntity.toUi(): DischargeUiState = DischargeUiState(
        kind = kind,
        status = status,
        consultant = consultantName,
        admissionDate = admissionDate?.takeIf { it > 0 }?.let { fmt.format(Date(it)) } ?: "",
        dischargeDate = dischargeDate?.takeIf { it > 0 }?.let { fmt.format(Date(it)) } ?: "",
        procedureName = procedureName ?: "",
        preoperativeDiagnosis = preoperativeDiagnosis ?: "",
        postoperativeDiagnosis = postoperativeDiagnosis ?: "",
        operativeFindings = operativeFindings ?: "",
        presentingComplaints = presentingComplaints,
        hospitalCourse = hospitalCourse,
        dischargeVitals = dischargeVitals,
        woundNursingAdvice = woundNursingAdvice,
        redFlagsReturn = redFlagsReturn,
        followUp = followUp,
        renderedText = renderedText,
        renderedMarkdown = renderedMarkdown,
        busy = null,
        savedId = id
    )

    private fun DischargeKind.label() = name
}
