package np.opd.assistant.ward

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import np.opd.assistant.OpdApplication
import np.opd.assistant.data.WardRepository
import np.opd.assistant.data.local.entities.PatientEntity
import np.opd.assistant.data.local.entities.ServiceUnit
import np.opd.assistant.data.local.entities.WardUnit

/** Which ward tab is selected on the census board. ALL shows every active patient. */
enum class WardTab(val label: String, val ward: WardUnit?) {
    ALL("All Beds", null),
    WARD_1("1st Ward", WardUnit.WARD_1),
    WARD_2("2nd Ward", WardUnit.WARD_2),
    BUFFER("Buffer", WardUnit.BUFFER),
    SEMI_ICU("Semi-ICU", WardUnit.SEMI_ICU),
    ICU("ICU", WardUnit.ICU)
}

data class WardUiState(
    val patients: List<PatientEntity> = emptyList(),
    val activeCount: Int = 0,
    val criticalCount: Int = 0,
    val openTaskCount: Int = 0,
    val outstandingConsultCount: Int = 0,
    val tab: WardTab = WardTab.ALL,
    val unit: ServiceUnit? = null,
    val myPatientsOnly: Boolean = false,
    val message: String? = null,
    val busy: Boolean = false
)

/**
 * View model for the ward census board and admission flow. Reads/writes through [WardRepository]
 * (Room). Kept separate from [np.opd.assistant.SessionViewModel] so the OPD consultation flow is
 * untouched.
 */
class WardViewModel(app: Application) : AndroidViewModel(app) {

    val repository: WardRepository =
        WardRepository((app as OpdApplication).database)

    private val _tab = MutableStateFlow(WardTab.ALL)
    private val _unit = MutableStateFlow<ServiceUnit?>(null)
    private val _myPatientsOnly = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    private val _busy = MutableStateFlow(false)
    private val _openPatientId = MutableStateFlow<String?>(null)

    val openPatientId: StateFlow<String?> get() = _openPatientId

    /**
     * Latest vitals log per active patient, for the NEWS2 badge on the ward board. Built by
     * flat-mapping the census ids into per-patient latest-vitals flows.
     */
    val latestVitals: StateFlow<Map<String, np.opd.assistant.data.local.entities.VitalsLogEntity?>> =
        repository.census()
            .flatMapLatest { list ->
                if (list.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap())
                else kotlinx.coroutines.flow.combine(
                    list.map { p -> repository.vitalsLogDao.observeLatest(p.id).map { p.id to it } }
                ) { pairs -> pairs.toMap() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Latest OT slate per active patient, for the bed-card pre-op chip. */
    val latestOt: StateFlow<Map<String, np.opd.assistant.data.local.entities.OtScheduleEntity>> =
        repository.census()
            .flatMapLatest { list ->
                if (list.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap())
                else kotlinx.coroutines.flow.combine(
                    list.map { p -> repository.otScheduleDao.observeForPatient(p.id).map { p.id to it.firstOrNull() } }
                ) { pairs -> pairs.filter { it.second != null }.associate { it.first to it.second!! } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun openPatient(id: String) { _openPatientId.value = id }
    fun closePatient() { _openPatientId.value = null }

    val ui: StateFlow<WardUiState> = combine(
        repository.census(),
        repository.activeCount(),
        repository.criticalCount(),
        repository.observeOpenTasks(),
        repository.observeOutstandingConsults()
    ) { census, active, critical, tasks, consults ->
        CensusSnapshot(census, active, critical, tasks.size, consults.size)
    }.combine(
        combine(_tab, _unit, _myPatientsOnly, _message, _busy) { tab, unit, mine, msg, busy ->
            FilterSnapshot(tab, unit, mine, msg, busy)
        }
    ) { census, filters ->
        WardUiState(
            patients = census.patients,
            activeCount = census.active,
            criticalCount = census.critical,
            openTaskCount = census.openTasks,
            outstandingConsultCount = census.outstandingConsults,
            tab = filters.tab,
            unit = filters.unit,
            myPatientsOnly = filters.mine,
            message = filters.message,
            busy = filters.busy
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WardUiState())

    private data class CensusSnapshot(
        val patients: List<PatientEntity>,
        val active: Int,
        val critical: Int,
        val openTasks: Int,
        val outstandingConsults: Int
    )

    private data class FilterSnapshot(
        val tab: WardTab,
        val unit: ServiceUnit?,
        val mine: Boolean,
        val message: String?,
        val busy: Boolean
    )

    val tab: StateFlow<WardTab> get() = _tab

    fun selectTab(t: WardTab) { _tab.value = t }
    fun selectUnit(u: ServiceUnit?) { _unit.value = u }
    fun toggleMyPatients() { _myPatientsOnly.value = !_myPatientsOnly.value }
    fun showMessage(m: String?) { _message.value = m }

    /** Patients visible for the current tab (ward filter), before unit/my-patient filters. */
    fun forTab(all: List<PatientEntity>, tab: WardTab): List<PatientEntity> =
        if (tab.ward == null) all else all.filter { it.currentWard == tab.ward }

    fun admit(
        name: String,
        age: Int?,
        gender: String,
        ward: WardUnit,
        bedNumber: String,
        unit: ServiceUnit,
        consultantName: String,
        mrn: String?
    ) {
        if (name.isBlank()) { _message.value = "Patient name is required."; return }
        viewModelScope.launch {
            _busy.value = true
            try {
                repository.admit(name.trim(), age, gender, ward, bedNumber.trim(), unit, consultantName.trim(), mrn?.trim())
                _message.value = "Admitted $name to ${ward.name.replace('_', ' ')} $bedNumber."
            } catch (e: Exception) {
                _message.value = "Could not admit patient: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun discharge(patientId: String, finalDiagnosis: String? = null) {
        viewModelScope.launch {
            try {
                repository.discharge(patientId, finalDiagnosis)
                _message.value = "Patient discharged."
            } catch (e: Exception) {
                _message.value = "Could not discharge patient: ${e.message}"
            }
        }
    }

    fun transfer(patientId: String, ward: WardUnit, bedNumber: String) {
        viewModelScope.launch {
            try {
                repository.transfer(patientId, ward, bedNumber)
                _message.value = "Patient moved to ${ward.name.replace('_', ' ')} $bedNumber."
            } catch (e: Exception) {
                _message.value = "Could not transfer patient: ${e.message}"
            }
        }
    }
}
