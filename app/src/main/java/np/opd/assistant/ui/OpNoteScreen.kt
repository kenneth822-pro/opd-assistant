package np.opd.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.opd.assistant.ward.OpNoteViewModel

/**
 * Operative note builder. Fill the surgical fields, then copy clean text for the desktop EMR,
 * share, save, or bridge the immediate post-op orders into the active cardex/task list (with the
 * deterministic safety checks shown before anything is committed).
 */
@Composable
fun OpNoteScreen(vm: OpNoteViewModel, modifier: Modifier = Modifier) {
    val st by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showBridgeConfirm by remember { mutableStateOf(false) }

    if (showBridgeConfirm) {
        BridgeConfirmDialog(
            candidates = st.candidates,
            onConfirm = { includeBlocked -> vm.bridgeOrdersToCardex(includeBlocked); showBridgeConfirm = false },
            onDismiss = { showBridgeConfirm = false }
        )
    }

    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        st.busy?.let { Text(it, fontWeight = FontWeight.SemiBold) }
        st.error?.let { Text(it, color = Palette.emergencyFg, fontSize = 13.sp) }
        st.message?.let { Text(it, color = Palette.okFg, fontSize = 13.sp) }

        SectionCard("Procedure") {
            F("Procedure title", st.data.procedureTitle) { v -> vm.update { it.copy(procedureTitle = v) } }
            F("Pre-op diagnosis", st.data.preOpDiagnosis) { v -> vm.update { it.copy(preOpDiagnosis = v) } }
            F("Post-op diagnosis", st.data.postOpDiagnosis) { v -> vm.update { it.copy(postOpDiagnosis = v) } }
        }
        SectionCard("Team & anesthesia") {
            F("Operating surgeon", st.data.surgeonName) { v -> vm.update { it.copy(surgeonName = v) } }
            F("Assistant", st.data.assistantName ?: "") { v -> vm.update { it.copy(assistantName = v) } }
            F("Anesthetist", st.data.anesthetistName ?: "") { v -> vm.update { it.copy(anesthetistName = v) } }
            Text("Anesthesia", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("GA", "SA", "EA", "LA", "MAC").forEach { a ->
                    Choice(a, st.data.anesthesiaType == a) { vm.update { it.copy(anesthesiaType = a) } }
                }
            }
        }
        SectionCard("Operative detail") {
            F("Incision", st.data.incision) { v -> vm.update { it.copy(incision = v) } }
            F("Operative findings", st.data.operativeFindings, minLines = 2) { v -> vm.update { it.copy(operativeFindings = v) } }
            F("Procedure â€” step by step", st.data.procedureSteps, minLines = 3) { v -> vm.update { it.copy(procedureSteps = v) } }
            F("Blood loss (mL)", st.data.bloodLossMl?.toString() ?: "") { v -> vm.update { it.copy(bloodLossMl = v.filter { c -> c.isDigit() }.toIntOrNull()) } }
            F("Drains", st.data.drains ?: "") { v -> vm.update { it.copy(drains = v) } }
            F("Specimen sent", st.data.specimenSent ?: "") { v -> vm.update { it.copy(specimenSent = v) } }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = st.data.spongeCountCorrect, onCheckedChange = { c -> vm.update { it.copy(spongeCountCorrect = c) } })
                Text("Sponge / instrument count correct")
            }
        }
        SectionCard("Immediate post-op orders") {
            Muted("One order per line, e.g. \"IV Fluid RL 100 ml/hr\", \"Inj Ceftriaxone 1g BD\", \"Tab Pantop 40mg OD\".")
            OutlinedTextField(
                value = st.ordersText,
                onValueChange = { vm.setOrders(it); vm.update { d -> d.copy(immediatePostOpOrders = it.split('\n').map { s -> s.trim() }.filter { s -> s.isNotBlank() }) } },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.parseOrders() }, modifier = Modifier.weight(1f)) { Text("Parse & check") }
                Button(onClick = { vm.parseOrders(); showBridgeConfirm = true }, modifier = Modifier.weight(1f)) { Text("âš¡ Bridge to cardex") }
            }
            st.candidates.forEach { c ->
                val tone = when {
                    c.blocked -> Palette.emergencyFg
                    c.findings.isNotEmpty() -> Palette.urgentFg
                    else -> Palette.okFg
                }
                Text(
                    "${if (c.inFormulary) "â€¢" else "â—‹"} ${c.stated} â†’ ${c.drugName}" + (if (c.blocked) "  BLOCKED" else ""),
                    color = tone, fontSize = 13.sp
                )
                c.findings.take(2).forEach { f -> Text("    ${f.message}", color = tone, fontSize = 11.sp) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.render() }, modifier = Modifier.weight(1f)) { Text("Preview") }
            Button(onClick = { vm.save() }, modifier = Modifier.weight(1f)) { Text("Save note") }
        }
        if (st.renderedText.isNotBlank()) {
            SectionCard("Preview (clean text)") {
                Text(st.renderedText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copyToClipboard(context, "Operative note", st.renderedText); vm.showMessage("Copied clean text.") }, modifier = Modifier.weight(1f)) { Text("ðŸ“‹ Copy for PC") }
                OutlinedButton(onClick = { shareText(context, st.renderedText) }, modifier = Modifier.weight(1f)) { Text("ðŸ–¨ Share") }
            }
        }
    }
}

@Composable
private fun BridgeConfirmDialog(
    candidates: List<np.opd.engine.OpNoteFormat.CandidateOrder>,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val blockedCount = candidates.count { it.blocked }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bridge to cardex?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (candidates.isEmpty()) {
                    Muted("Nothing parsed yet. Tap \"Parse & check\" first.")
                } else {
                    Text("${candidates.size} order(s) will be added to the cardex and task list.")
                    candidates.forEach { c ->
                        Text("â€¢ ${c.drugName}" + (if (c.blocked) "  BLOCKED" else ""), fontSize = 13.sp, color = if (c.blocked) Palette.emergencyFg else Palette.greyFg)
                    }
                    if (blockedCount > 0) {
                        Text("$blockedCount blocked item(s) will be skipped unless you override (audited).", color = Palette.emergencyFg, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (blockedCount > 0) {
                TextButton(onClick = { onConfirm(false) }) { Text("Add safe only") }
            } else {
                TextButton(onClick = { onConfirm(false) }) { Text("Confirm") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (blockedCount > 0) TextButton(onClick = { onConfirm(true) }) { Text("Override & add all") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun F(label: String, value: String, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth()
    )
}



private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(intent, "Share operative note"))
}
