package np.opd.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.opd.assistant.ward.WardViewModel

/** Tabs inside the patient chart. Later phases fill in rounds, tasks, media and discharge. */
enum class ChartTab(val label: String) {
    SUMMARY("Summary / Vitals"),
    CARDEX("Cardex"),
    ROUNDS("Senior Rounds"),
    TODO("To-Do & Orders"),
    MEDIA("Imaging & Media"),
    DISCHARGE("Discharge Planner")
}

@Composable
fun PatientChartScreen(wardVm: WardViewModel, patientId: String, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(ChartTab.SUMMARY) }
    var showDischarge by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    val patientFlow = remember(patientId) { wardVm.repository.observePatient(patientId) }
    val patient by patientFlow.collectAsStateWithLifecycle(initialValue = null)

    if (showDischarge) {
        ConfirmDialog(
            title = "Discharge patient",
            body = "Mark this patient as discharged? The open admission will be closed and the bed freed.",
            confirmLabel = "Discharge",
            onConfirm = {
                wardVm.discharge(patientId)
                showDischarge = false
                onBack()
            },
            onDismiss = { showDischarge = false }
        )
    }

    val name = patient?.name ?: "Patient"
    Page(
        title = name,
        onBack = onBack,
        actions = {
            TextButton(onClick = { showDischarge = true }) { Text("Discharge") }
        }
    ) {
        patient?.let { p ->
            val subtitle = buildString {
                append(p.currentWard.name.replace('_', ' '))
                if (p.bedNumber.isNotBlank()) append(" · Bed ${p.bedNumber}")
                append(" · ")
                append(p.age?.let { "$it y" } ?: "age ?")
                append(" · ")
                append(p.gender.lowercase().replaceFirstChar { it.uppercase() })
                p.mrn?.takeIf { it.isNotBlank() }?.let { append(" · MRN $it") }
            }
            Muted(subtitle)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChartTab.entries.forEach { t ->
                Choice(t.label, tab == t) { tab = t }
            }
        }

        when (tab) {
            ChartTab.SUMMARY -> SectionCard("Summary / Vitals") {
                Muted("Admission vitals, demographics and active problem summary will appear here.")
                Muted("Use the OPD flow to record a full consultation; this inpatient workspace is being built in phases.")
            }
            ChartTab.CARDEX -> SectionCard("Cardex (Treatment Sheet)") {
                Muted("Active medications, doses, routes and start/stop dates will be listed here (Phase 3).")
            }
            ChartTab.ROUNDS -> SectionCard("Senior Rounds & Notes") {
                Muted("Voice-captured consultant instructions and the cardex diff will land here (Phase 3).")
            }
            ChartTab.TODO -> SectionCard("To-Do & Orders") {
                Muted("STAT tasks, pending labs and unaddressed findings will be tracked here (Phase 4).")
            }
            ChartTab.MEDIA -> SectionCard("Imaging & Media") {
                Muted("Wound photos, X-ray/CT view-box captures and a healing comparison slider arrive in Phase 5.")
            }
            ChartTab.DISCHARGE -> SectionCard("Discharge Planner") {
                Muted("ER and inpatient discharge summaries with PC export are built in Phase 4.")
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
