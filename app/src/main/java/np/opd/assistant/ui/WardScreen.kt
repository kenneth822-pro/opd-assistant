package np.opd.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.opd.assistant.data.local.entities.ServiceUnit
import np.opd.assistant.data.local.entities.WardUnit
import np.opd.assistant.ward.ClinicalViewModel
import np.opd.assistant.ward.WardTab
import np.opd.assistant.ward.WardUiState
import np.opd.assistant.ward.WardViewModel

/** Number of beds shown per ward on the board. */
private const val BEDS_PER_WARD = 12

@Composable
fun WardScreen(wardVm: WardViewModel, clinicalVm: ClinicalViewModel, onBack: () -> Unit, onOpenPatient: (String) -> Unit, onAskWard: () -> Unit) {
    val state by wardVm.ui.collectAsStateWithLifecycle()
    val latestVitals by wardVm.latestVitals.collectAsStateWithLifecycle()
    val latestOt by wardVm.latestOt.collectAsStateWithLifecycle()
    val clinical by clinicalVm.ui.collectAsStateWithLifecycle()
    var showAdmit by remember { mutableStateOf(false) }
    var showBridge by remember { mutableStateOf(false) }
    var vitalsFor by remember { mutableStateOf<String?>(null) }
    BackHandler { onBack() }

    if (showBridge) PcBridgeDialog(onDismiss = { showBridge = false })

    vitalsFor?.let { pid ->
        val name = state.patients.firstOrNull { it.id == pid }?.name ?: "Patient"
        VitalsEntryDialog(
            patientName = name,
            onDismiss = { vitalsFor = null },
            onSave = { entry, result ->
                clinicalVm.saveVitals(pid, entry) { }
                vitalsFor = null
            }
        )
    }

    if (showAdmit) {
        AdmissionDialog(
            defaultWard = state.tab.ward ?: WardUnit.WARD_1,
            onDismiss = { showAdmit = false },
            onAdmit = { name, age, gender, ward, bed, unit, consultant, mrn ->
                wardVm.admit(name, age, gender, ward, bed, unit, consultant, mrn)
                showAdmit = false
            }
        )
    }

    Page(
        title = "Ward Board",
        onBack = onBack,
        actions = {
            TextButton(onClick = { showBridge = true }) { Text("PC Bridge") }
            TextButton(onClick = onAskWard) { Text("Ask") }
            TextButton(onClick = { showAdmit = true }) { Text("+ Admit") }
        }
    ) {
        MetricStrip(state)

        // Ward tabs
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WardTab.entries.forEach { t ->
                Choice(t.label, state.tab == t) { wardVm.selectTab(t) }
            }
        }

        // Secondary filters
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Choice("All units", state.unit == null) { wardVm.selectUnit(null) }
            ServiceUnit.entries.filter { it != ServiceUnit.CUSTOM }.forEach { u ->
                Choice(prettyUnit(u), state.unit == u) { wardVm.selectUnit(u) }
            }
            Choice("My patients", state.myPatientsOnly) { wardVm.toggleMyPatients() }
        }

        state.message?.let { Text(it, color = Palette.infoFg, fontSize = 13.sp) }
        clinical.message?.let { Text(it, color = Palette.okFg, fontSize = 13.sp) }
        clinical.error?.let { Text(it, color = Palette.emergencyFg, fontSize = 13.sp) }

        val shown = wardVm.forTab(state.patients, state.tab)

        if (shown.isEmpty()) {
            SectionCard("No patients") {
                Muted("No active patients in ${state.tab.label}. Tap “+ Admit” to add one, or pick another ward tab.")
            }
        } else {
            shown.forEach { p ->
                val vitals = latestVitals[p.id]
                val ot = latestOt[p.id]
                val chips = buildList {
                    if (p.currentWard == WardUnit.ICU) add("Ventilated")
                    if (p.currentWard == WardUnit.SEMI_ICU) add("HDU")
                    if (p.age != null && p.age >= 65) add("Elderly")
                    ot?.let { s ->
                        if (s.status == "SCHEDULED" || s.status == "IN_OT") {
                            val done = listOf(s.pacCleared, s.consentSigned, s.bloodArrangedUnits > 0, s.npoSinceTimestamp != null, s.siteMarked).count { it }
                            add(if (done >= 5) "CLEARED FOR OT" else "PRE-OP ($done/5)")
                        }
                    }
                }
                BedCard(
                    patient = p,
                    bedNumber = p.bedNumber,
                    pod = null,
                    chips = chips,
                    news2Score = vitals?.news2Score,
                    news2Risk = vitals?.news2Risk,
                    onClick = { onOpenPatient(p.id) }
                )
            }
        }

        SectionCard("Free beds in ${state.tab.label}") {
            val occupied = shown.map { it.bedNumber }.toSet()
            val free = (1..BEDS_PER_WARD).map { it.toString() }.filter { it !in occupied }
            if (free.isEmpty()) {
                Muted("All $BEDS_PER_WARD beds occupied in this view.")
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    free.forEach { bed ->
                        OutlinedButton(onClick = { showAdmit = true }) { Text("Bed $bed") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricStrip(state: WardUiState) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricTile("Inpatients", state.activeCount.toString(), WardPalette.occupiedBg, WardPalette.occupiedFg)
        MetricTile("ICU / HDU", state.criticalCount.toString(), WardPalette.criticalBg, WardPalette.criticalFg)
        MetricTile("Open tasks", state.openTaskCount.toString(), Palette.urgentBg, Palette.urgentFg)
        MetricTile("Consults", state.outstandingConsultCount.toString(), Palette.infoBg, Palette.infoFg)
    }
}

private fun prettyUnit(u: ServiceUnit): String = when (u) {
    ServiceUnit.UNIT_1 -> "Unit 1 (Gen Surg/GI)"
    ServiceUnit.UNIT_2 -> "Unit 2 (Colorectal/Lap)"
    ServiceUnit.NEUROSURGERY -> "Neurosurgery"
    ServiceUnit.ORTHO -> "Ortho"
    ServiceUnit.MEDICINE -> "Medicine"
    ServiceUnit.PEDIATRIC_SURGERY -> "Paediatric Surgery"
    ServiceUnit.CUSTOM -> "Custom"
}

/** Fast intake form for a new admission. Only name is required; everything else can be filled later. */
@Composable
private fun AdmissionDialog(
    defaultWard: WardUnit,
    onDismiss: () -> Unit,
    onAdmit: (String, Int?, String, WardUnit, String, ServiceUnit, String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var mrn by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("UNKNOWN") }
    var ward by remember { mutableStateOf(defaultWard) }
    var bed by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(ServiceUnit.UNIT_1) }
    var consultant by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New admission") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Patient name *") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mrn, onValueChange = { mrn = it },
                        label = { Text("MRN / Hospital no.") }, singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = age, onValueChange = { age = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Age") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Female", gender == "FEMALE") { gender = "FEMALE" }
                    Choice("Male", gender == "MALE") { gender = "MALE" }
                    Choice("Other", gender == "OTHER") { gender = "OTHER" }
                }
                Spacer(Modifier.height(2.dp))
                Text("Ward", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WardUnit.entries.filter { it != WardUnit.DISCHARGED }.forEach { w ->
                        Choice(w.name.replace('_', ' '), ward == w) { ward = w }
                    }
                }
                OutlinedTextField(
                    value = bed, onValueChange = { bed = it.take(6) },
                    label = { Text("Bed number") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("Service unit", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServiceUnit.entries.filter { it != ServiceUnit.CUSTOM }.forEach { u ->
                        Choice(prettyUnit(u), unit == u) { unit = u }
                    }
                }
                OutlinedTextField(
                    value = consultant, onValueChange = { consultant = it },
                    label = { Text("Consultant / admitting doctor") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Muted("Admission checklist items are added to the patient's task list after admission (Phase 4).")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdmit(name, age.trim().toIntOrNull()?.takeIf { it in 0..120 }, gender, ward, bed, unit, consultant, mrn.ifBlank { null })
                },
                enabled = name.isNotBlank()
            ) { Text("Admit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
