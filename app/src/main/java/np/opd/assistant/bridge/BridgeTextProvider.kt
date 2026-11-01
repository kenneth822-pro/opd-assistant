package np.opd.assistant.bridge

import android.content.Context
import kotlinx.coroutines.flow.first
import np.opd.assistant.data.WardRepository
import np.opd.assistant.data.local.entities.DischargeKind
import np.opd.assistant.data.local.entities.EncounterType
import np.opd.assistant.data.local.entities.PatientStatus
import np.opd.engine.DischargeFormat
import np.opd.engine.WardCensus
import np.opd.engine.WardHandover
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The kinds of copy-ready text the PC bridge can serve for a patient. */
enum class BridgeDoc(val label: String, val key: String) {
    ER_DISCHARGE("ER Discharge Note", "er_discharge"),
    IP_DISCHARGE("Inpatient Discharge Summary", "ip_discharge"),
    OP_NOTE("Operation Note & Cardex", "op_note"),
    SBAR("Ward SBAR Handover", "sbar")
}

/**
 * Builds the plain-text documents that the PC bridge serves, straight from the local database, using
 * the pure engine formatters. Read-only: the bridge never writes patient data.
 */
class BridgeTextProvider(private val repository: WardRepository) {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** Active patients as (id, label) for the web dropdown. */
    suspend fun patientList(): List<Pair<String, String>> =
        repository.patientDao.search("").filter { it.status == PatientStatus.ACTIVE }
            .map { it.id to "${it.name} (${it.currentWard.name.replace('_', ' ')} ${it.bedNumber})" }

    suspend fun document(patientId: String, doc: BridgeDoc): String {
        val patient = repository.patientDao.byId(patientId) ?: return "Patient not found."
        val encounters = repository.encounterDao.observeForPatient(patientId).first()
        val enc = encounters.firstOrNull { it.dischargeDatetime == null } ?: encounters.firstOrNull()
        val cardex = repository.cardexDao.activeForPatient(patientId)

        return when (doc) {
            BridgeDoc.SBAR -> sbarFor(patientId)
            BridgeDoc.ER_DISCHARGE, BridgeDoc.IP_DISCHARGE -> {
                val kind = if (doc == BridgeDoc.ER_DISCHARGE || enc?.encounterType == EncounterType.ER) DischargeFormat.Kind.ER else DischargeFormat.Kind.INPATIENT
                val input = DischargeFormat.DischargeInput(
                    kind = kind,
                    patientName = patient.name,
                    age = patient.age,
                    sex = patient.gender.lowercase().replaceFirstChar { it.uppercase() },
                    mrn = patient.mrn,
                    ward = patient.currentWard.name.replace('_', ' '),
                    bed = patient.bedNumber,
                    unit = enc?.unit?.name?.replace('_', ' ') ?: "",
                    consultant = enc?.consultantName ?: "",
                    admissionDate = enc?.admissionDatetime?.takeIf { it > 0 }?.let { fmt.format(Date(it)) } ?: "",
                    dischargeDate = fmt.format(Date()),
                    homeMedications = cardex.map { DischargeFormat.DischargeMed(name = it.brandStated, dose = it.doseCount?.let(::count) ?: "", route = if (it.route.name == "OTHER") "" else it.route.name, frequency = it.frequency) }
                )
                DischargeFormat.plainText(input)
            }
            BridgeDoc.OP_NOTE -> {
                val sb = StringBuilder()
                sb.appendLine("OPERATION NOTE & ACTIVE CARDEX (PROTOTYPE - UNVERIFIED)")
                sb.appendLine("Patient: ${patient.name}  ${patient.age ?: "?"} y  ${patient.gender}")
                sb.appendLine("Ward: ${patient.currentWard.name.replace('_', ' ')} Bed ${patient.bedNumber}")
                sb.appendLine("Encounter: ${enc?.encounterType ?: "-"}  Consultant: ${enc?.consultantName ?: "-"}")
                sb.appendLine()
                sb.appendLine("ACTIVE CARDEX:")
                if (cardex.isEmpty()) sb.appendLine("  (none)") else cardex.forEachIndexed { i, c ->
                    sb.appendLine("  ${i + 1}. ${c.brandStated} ${c.doseCount?.let(::count) ?: ""} ${c.route.name} ${c.frequency}".trimEnd())
                }
                sb.toString()
            }
        }
    }

    private suspend fun sbarFor(patientId: String): String {
        // Full-census-style single-patient SBAR (the ward board serves the whole census separately).
        val list = patientList()
        val rows = list.mapNotNull { (id, _) ->
            val p = repository.patientDao.byId(id) ?: return@mapNotNull null
            val enc = repository.encounterDao.observeForPatient(id).first().firstOrNull { it.dischargeDatetime == null }
            WardCensusPatient(p.name, p.age, p.gender.lowercase(), p.currentWard.name.replace('_', ' '), p.bedNumber, enc?.unit?.name?.replace('_', ' ') ?: "")
        }
        val census = WardCensus(
            generatedAt = fmt.format(Date()),
            patients = rows.map { r ->
                np.opd.engine.CensusPatient(
                    patientId = r.name, name = r.name, age = r.age, sex = r.sex,
                    ward = r.ward, bed = r.bed, unit = r.unit
                )
            }
        )
        val single = census.patients.firstOrNull { it.name == (repository.patientDao.byId(patientId)?.name) }
        val focused = if (single != null) WardCensus(generatedAt = census.generatedAt, patients = listOf(single)) else census
        return WardHandover.sbar(focused)
    }

    private data class WardCensusPatient(val name: String, val age: Int?, val sex: String, val ward: String, val bed: String, val unit: String)

    private fun count(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
