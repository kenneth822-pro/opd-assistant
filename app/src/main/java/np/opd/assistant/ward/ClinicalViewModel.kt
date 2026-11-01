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
import np.opd.assistant.data.local.entities.FluidIoEntity
import np.opd.assistant.data.local.entities.OtScheduleEntity
import np.opd.assistant.data.local.entities.VitalsLogEntity
import np.opd.engine.News2
import java.util.UUID

/** Transient form state for the quick vitals entry dialog. */
data class VitalsEntry(
    val rr: String = "",
    val spo2: String = "",
    val onOxygen: Boolean = false,
    val bpSystolic: String = "",
    val bpDiastolic: String = "",
    val pulse: String = "",
    val tempF: String = "",
    val consciousness: News2.ConsciousnessLevel = News2.ConsciousnessLevel.ALERT
) {
    fun toNews2Params() = News2.Parameters(
        respirationRate = rr.toIntOrNull(),
        spo2Scale1 = spo2.toIntOrNull(),
        onSupplementalOxygen = onOxygen,
        systolicBp = bpSystolic.toIntOrNull(),
        pulse = pulse.toIntOrNull(),
        consciousness = consciousness,
        temperatureF = tempF.toDoubleOrNull()
    )
}

data class ClinicalUiState(
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * Writes for the inpatient clinical enhancements: vitals logs (with the NEWS2 score computed and
 * stored at capture time), the fluid I/O chart, and the OT slate / pre-op checklist.
 */
class ClinicalViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WardRepository((app as OpdApplication).database)

    private val _ui = MutableStateFlow(ClinicalUiState())
    val ui: StateFlow<ClinicalUiState> = _ui.asStateFlow()

    fun showMessage(m: String?) = _ui.update { it.copy(message = m, error = null) }
    fun showError(e: String?) = _ui.update { it.copy(error = e, message = null) }

    /** Scores the entry and stores it as a vitals log. Returns the NEWS2 result for immediate display. */
    fun saveVitals(patientId: String, entry: VitalsEntry, onSaved: (News2.Result) -> Unit = {}) {
        val params = entry.toNews2Params()
        val result = News2.score(params)
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                repository.vitalsLogDao.upsert(
                    VitalsLogEntity(
                        id = UUID.randomUUID().toString(),
                        patientId = patientId,
                        timestamp = now,
                        pulse = params.pulse,
                        bpSystolic = params.systolicBp,
                        bpDiastolic = entry.bpDiastolic.toIntOrNull(),
                        rr = params.respirationRate,
                        spo2 = params.spo2Scale1,
                        tempF = params.temperatureF,
                        onOxygen = params.onSupplementalOxygen,
                        consciousness = params.consciousness.name,
                        news2Score = result.totalScore,
                        news2Risk = result.riskLevel.name,
                        updatedAt = now
                    )
                )
                onSaved(result)
                _ui.update { it.copy(message = "Vitals saved — NEWS2 ${result.totalScore} (${result.riskLevel.name}).") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Could not save vitals: ${e.message}") }
            }
        }
    }

    // ---- fluid I/O ----

    fun addFluid(patientId: String, intakeType: String, intakeMl: Int, outputType: String, outputMl: Int, notes: String? = null) {
        if (intakeType.isBlank() && outputType.isBlank()) return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                repository.fluidIoDao.upsert(
                    FluidIoEntity(
                        id = UUID.randomUUID().toString(),
                        patientId = patientId,
                        timestamp = now,
                        intakeType = intakeType,
                        intakeMl = intakeMl,
                        outputType = outputType,
                        outputMl = outputMl,
                        notes = notes,
                        updatedAt = now
                    )
                )
                _ui.update { it.copy(message = "Fluid entry added.") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Could not add fluid entry: ${e.message}") }
            }
        }
    }

    fun deleteFluid(id: String) {
        viewModelScope.launch {
            try { repository.fluidIoDao.softDelete(id, System.currentTimeMillis()) } catch (e: Exception) { }
        }
    }

    // ---- OT slate ----

    fun saveOt(schedule: OtScheduleEntity) {
        viewModelScope.launch {
            try {
                repository.otScheduleDao.upsert(schedule.copy(updatedAt = System.currentTimeMillis()))
                _ui.update { it.copy(message = "OT slate saved.") }
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Could not save OT slate: ${e.message}") }
            }
        }
    }
}
