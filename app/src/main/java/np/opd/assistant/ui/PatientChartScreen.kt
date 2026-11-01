package np.opd.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.viewmodel.compose.viewModel
import np.opd.engine.ChangeAction
import np.opd.engine.DiffItem
import np.opd.engine.OrderKind
import np.opd.engine.Severity
import np.opd.assistant.ward.DischargeViewModel
import np.opd.assistant.ward.RoundViewModel
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
fun PatientChartScreen(
    wardVm: WardViewModel,
    roundVm: RoundViewModel,
    patientId: String,
    onBack: () -> Unit
) {
    var tab by remember { mutableStateOf(ChartTab.SUMMARY) }
    var showDischarge by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    val patient by remember(patientId) { wardVm.repository.observePatient(patientId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val encounters by remember(patientId) { wardVm.repository.observeEncounters(patientId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val activeEncounter = encounters.firstOrNull { it.dischargeDatetime == null } ?: encounters.firstOrNull()

    if (showDischarge) {
        ConfirmDialog(
            title = "Discharge patient",
            body = "Mark this patient as discharged? The open admission will be closed and the bed freed.",
            confirmLabel = "Discharge",
            onConfirm = { wardVm.discharge(patientId); showDischarge = false; onBack() },
            onDismiss = { showDischarge = false }
        )
    }

    val name = patient?.name ?: "Patient"
    Page(
        title = name,
        onBack = onBack,
        actions = { TextButton(onClick = { showDischarge = true }) { Text("Discharge") } }
    ) {
        patient?.let { p ->
            Muted(
                buildString {
                    append(p.currentWard.name.replace('_', ' '))
                    if (p.bedNumber.isNotBlank()) append(" · Bed ${p.bedNumber}")
                    append(" · "); append(p.age?.let { "$it y" } ?: "age ?")
                    append(" · "); append(p.gender.lowercase().replaceFirstChar { it.uppercase() })
                    p.mrn?.takeIf { it.isNotBlank() }?.let { append(" · MRN $it") }
                }
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChartTab.entries.forEach { t -> Choice(t.label, tab == t) { tab = t } }
        }

        when (tab) {
            ChartTab.SUMMARY -> SectionCard("Summary / Vitals") {
                Muted("Admission vitals, demographics and active problem summary will appear here.")
                Muted("Use the OPD flow for a full consultation; this inpatient workspace is built in phases.")
            }

            ChartTab.CARDEX -> {
                CardexTab(wardVm, activeEncounter?.id)
            }

            ChartTab.ROUNDS -> {
                RoundsTab(roundVm, patientId, activeEncounter?.id)
            }

            ChartTab.TODO -> SectionCard("To-Do & Orders") {
                Muted("STAT tasks, pending labs and unaddressed findings will be tracked here (Phase 4).")
            }
            ChartTab.MEDIA -> SectionCard("Imaging & Media") {
                Muted("Wound photos, X-ray/CT view-box captures and a healing comparison slider arrive in Phase 5.")
            }
            ChartTab.DISCHARGE -> {
                val dischargeVm: DischargeViewModel = viewModel()
                androidx.compose.runtime.LaunchedEffect(patientId, activeEncounter?.id) {
                    activeEncounter?.let { dischargeVm.load(patientId, it.id) }
                }
                if (activeEncounter == null) {
                    SectionCard("Discharge Planner") { Muted("No open admission for this patient.") }
                } else {
                    DischargeScreen(dischargeVm)
                }
            }
        }
    }
}

@Composable
private fun CardexTab(wardVm: WardViewModel, encounterId: String?) {
    if (encounterId == null) {
        SectionCard("Cardex (Treatment Sheet)") { Muted("No open admission for this patient.") }
        return
    }
    val items by remember(encounterId) { wardVm.repository.observeCardex(encounterId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val active = items.filter { it.isActive }
    SectionCard("Cardex (Treatment Sheet)") {
        if (active.isEmpty()) {
            Muted("No active medicines. Record a senior round to add treatment changes.")
        } else {
            active.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(row.brandStated, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        val meta = buildString {
                            row.doseCount?.let { append("${if (it == it.toLong().toDouble()) it.toInt().toString() else it.toString()} ") }
                            append(row.route.name)
                            if (row.frequency.isNotBlank()) { append(" · "); append(row.frequency) }
                        }
                        Muted(meta)
                    }
                    if (row.safetyNotes != null) Pill("CHECK", Palette.urgentBg, Palette.urgentFg)
                }
            }
        }
    }
}

@Composable
private fun RoundsTab(roundVm: RoundViewModel, patientId: String, encounterId: String?) {
    val state by roundVm.ui.collectAsStateWithLifecycle()
    var showDiff by remember { mutableStateOf(false) }

    // Surface the review dialog as soon as a diff (or an in-progress capture) exists.
    if (state.recording || state.busy != null || state.diff != null) showDiff = true

    if (showDiff) {
        RoundReviewDialog(
            roundVm = roundVm,
            onDismiss = { showDiff = false; roundVm.clearDiff() }
        )
    }

    SectionCard("Senior Rounds & Notes") {
        if (encounterId == null) {
            Muted("No open admission for this patient.")
            return@SectionCard
        }
        Muted("Record what the consultant says during the round. The app drafts the treatment changes, runs safety checks, and asks you to approve them before anything is saved.")
        state.error?.let { Text(it, color = Palette.emergencyFg, fontSize = 13.sp) }
        state.message?.let { Text(it, color = Palette.okFg, fontSize = 13.sp) }
        Spacer(Modifier.height(4.dp))
        if (!state.recording && state.busy == null) {
            Button(onClick = { roundVm.startRound(patientId, encounterId) }, modifier = Modifier.fillMaxWidth()) {
                Text("● Start round recording")
            }
        }
    }
}

@Composable
private fun RoundReviewDialog(roundVm: RoundViewModel, onDismiss: () -> Unit) {
    val state by roundVm.ui.collectAsStateWithLifecycle()
    val diff = state.diff

    AlertDialog(
        onDismissRequest = { if (!state.recording && state.busy == null) onDismiss() },
        title = { Text(if (state.recording) "Recording round…" else "Review round changes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.recording) {
                    Text("Recording… ${state.elapsedSec}s", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Muted("Speak close to the phone. Tap Stop when the round is done.")
                }
                state.busy?.let {
                    Text(it, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.error?.let { Text(it, color = Palette.emergencyFg, fontSize = 13.sp) }
                diff?.let { CardexDiffView(it, state.skippedStartIndices, { i -> roundVm.skipStart(i) }, { i -> roundVm.unskipStart(i) }) }
                if (state.transcript.isNotEmpty() && diff == null) {
                    Muted("Transcript:")
                    state.transcript.takeLast(6).forEach { Muted(it) }
                }
            }
        },
        confirmButton = {
            when {
                state.recording -> TextButton(onClick = { roundVm.stopAndAnalyse() }) { Text("Stop") }
                state.busy != null -> TextButton(onClick = {}) { Text("Working…") }
                diff != null -> TextButton(onClick = { roundVm.approve(); onDismiss() }) { Text("Approve changes") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            when {
                state.recording -> TextButton(onClick = { roundVm.cancelRound(); onDismiss() }) { Text("Cancel") }
                diff != null -> TextButton(onClick = { roundVm.clearDiff() }) { Text("Discard") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun CardexDiffView(
    diff: np.opd.engine.CardexDiff,
    skipped: Set<Int>,
    onSkip: (Int) -> Unit,
    onUnskip: (Int) -> Unit
) {
    if (diff.items.isEmpty()) {
        Muted("No treatment changes were detected in this round.")
        return
    }
    diff.items.forEachIndexed { index, item ->
        DiffRow(item, skipped.contains(index), onSkip = { onSkip(index) }, onUnskip = { onUnskip(index) })
    }
}

@Composable
private fun DiffRow(item: DiffItem, skipped: Boolean, onSkip: () -> Unit, onUnskip: () -> Unit) {
    val (chipLabel, bg, fg) = when {
        item.kind != OrderKind.MED -> Triple(kindLabel(item.kind), Palette.infoBg, Palette.infoFg)
        item.action == ChangeAction.START -> Triple("ADD", Palette.okBg, Palette.okFg)
        item.action == ChangeAction.STOP -> Triple("STOP", Palette.emergencyBg, Palette.emergencyFg)
        else -> Triple("ADJUST", Palette.urgentBg, Palette.urgentFg)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(chipLabel, bg, fg)
            Text(item.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (item.kind == OrderKind.MED && item.action == ChangeAction.START) {
                if (skipped) TextButton(onClick = onUnskip) { Text("Include") }
                else TextButton(onClick = onSkip) { Text("Skip") }
            }
        }
        item.findings.forEach { f ->
            val c = when (f.severity) {
                Severity.BLOCKED -> Palette.emergencyFg
                Severity.WARNING -> Palette.urgentFg
                Severity.CAUTION -> Palette.greyFg
            }
            Text("• ${f.message}", color = c, fontSize = 12.sp)
        }
        if (item.blocked && !skipped) {
            Text("Blocked — will not be added unless you change it.", color = Palette.emergencyFg, fontSize = 12.sp)
        }
    }
}

private fun kindLabel(kind: OrderKind): String = when (kind) {
    OrderKind.INVESTIGATION -> "LAB"
    OrderKind.NURSING -> "NURSING"
    OrderKind.DIET -> "DIET"
    OrderKind.REFERRAL -> "CONSULT"
    OrderKind.MED -> "MED"
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
