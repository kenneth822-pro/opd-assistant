package np.opd.assistant.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import np.opd.assistant.ward.VitalsEntry
import np.opd.engine.News2

/**
 * Quick bedside vitals entry with a live NEWS2 score and the recommended clinical action. Saving
 * stores the observation (and its score) as a vitals log.
 */
@Composable
fun VitalsEntryDialog(
    patientName: String,
    onDismiss: () -> Unit,
    onSave: (VitalsEntry, News2.Result) -> Unit
) {
    var entry by remember { mutableStateOf(VitalsEntry()) }
    val result = News2.score(entry.toNews2Params())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vitals — $patientName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("Resp rate", entry.rr, Modifier.weight(1f)) { entry = entry.copy(rr = it) }
                    Num("SpO2 %", entry.spo2, Modifier.weight(1f)) { entry = entry.copy(spo2 = it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = entry.onOxygen, onCheckedChange = { entry = entry.copy(onOxygen = it) })
                    Text("On supplemental oxygen (+2)")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("BP sys", entry.bpSystolic, Modifier.weight(1f)) { entry = entry.copy(bpSystolic = it) }
                    Num("BP dia", entry.bpDiastolic, Modifier.weight(1f)) { entry = entry.copy(bpDiastolic = it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("Pulse", entry.pulse, Modifier.weight(1f)) { entry = entry.copy(pulse = it) }
                    Num("Temp °F", entry.tempF, Modifier.weight(1f), allowDecimal = true) { entry = entry.copy(tempF = it) }
                }
                Text("Consciousness", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    News2.ConsciousnessLevel.entries.forEach { c ->
                        Choice(c.name.lowercase().replace('_', ' '), entry.consciousness == c) { entry = entry.copy(consciousness = c) }
                    }
                }
                val (bg, fg) = when {
                    result.totalScore >= 7 -> Palette.emergencyBg to Palette.emergencyFg
                    result.totalScore >= 5 -> Palette.urgentBg to Palette.urgentFg
                    else -> Palette.okBg to Palette.okFg
                }
                Text("NEWS2 ${result.totalScore} · ${result.riskLevel.name}", color = fg, fontWeight = FontWeight.Bold)
                Text(result.clinicalAction, fontSize = 12.sp, color = fg)
                if (result.missingParameters.isNotEmpty()) {
                    Muted("Not entered: ${result.missingParameters.joinToString(", ")} (score is a floor).")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(entry, result) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Num(
    label: String,
    value: String,
    modifier: Modifier,
    allowDecimal: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            val filtered = if (allowDecimal) v.filter { it.isDigit() || it == '.' } else v.filter { it.isDigit() }
            onChange(filtered.take(5))
        },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
    )
}
