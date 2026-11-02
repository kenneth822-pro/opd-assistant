package np.opd.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.opd.assistant.data.local.entities.LabResultEntity
import np.opd.assistant.ward.LabViewModel
import np.opd.assistant.ward.WardViewModel
import np.opd.engine.LabCatalogue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DAY_FMT = SimpleDateFormat("MM-dd HH:mm", Locale.US)

/**
 * Cumulative lab flowsheet: rows are tests (grouped by category), columns are collection times.
 * Values carry direction badges and panic highlighting. Supports manual entry and AI-assisted
 * extraction from a photographed/pasted paper slip.
 */
@Composable
fun LabFlowsheet(
    wardVm: WardViewModel,
    labVm: LabViewModel,
    patientId: String,
    encounterId: String?
) {
    val rows by remember(patientId) { wardVm.repository.labDao.getLabFlowsheet(patientId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val state by labVm.ui.collectAsStateWithLifecycle()
    var showManual by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }

    if (showManual) {
        ManualLabDialog(
            onDismiss = { showManual = false },
            onSave = { code, value, unit, ts ->
                if (encounterId != null) labVm.addManual(patientId, encounterId, code, value, unit, ts)
                else labVm.showError("No open admission for this patient.")
                showManual = false
            }
        )
    }
    if (showScan) {
        ScanLabDialog(
            labVm = labVm,
            patientId = patientId,
            encounterId = encounterId,
            onDismiss = { showScan = false; labVm.clearCandidates() }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showManual = true }, modifier = Modifier.weight(1f)) { Text("+ Manual entry") }
            OutlinedButton(onClick = { showScan = true }, modifier = Modifier.weight(1f)) { Text("📷 Scan slip") }
        }
        state.message?.let { Text(it, color = Palette.okFg, fontSize = 13.sp) }
        state.error?.let { Text(it, color = Palette.emergencyFg, fontSize = 13.sp) }

        if (rows.isEmpty()) {
            SectionCard("Cumulative Flowsheet") {
                Muted("No lab results recorded yet. Add a value manually or scan a paper slip.")
            }
        } else {
            SectionCard("Cumulative Flowsheet") {
                LabMatrix(rows)
            }
        }
    }
}

@Composable
private fun LabMatrix(rows: List<LabResultEntity>) {
    // Distinct collection times become columns.
    val times = rows.map { it.timestamp }.distinct().sorted()
    val byTest = rows.groupBy { it.testCode }
    val testOrder = LabCatalogue.definitions.map { it.code }.filter { byTest.containsKey(it) } +
        byTest.keys.filter { code -> LabCatalogue.definitions.none { it.code == code } }

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
    ) {
        // Header row
        Row {
            HeaderCell("Test", 150.dp)
            times.forEach { t -> HeaderCell(DAY_FMT.format(Date(t)), 96.dp) }
        }
        var lastCategory = ""
        testOrder.forEach { code ->
            val cells = byTest[code] ?: return@forEach
            val def = LabCatalogue.definition(code)
            val category = def?.category?.name ?: cells.firstOrNull()?.category ?: "OTHER"
            if (category != lastCategory) {
                lastCategory = category
                Row {
                    Box(
                        Modifier
                            .width(150.dp + 96.dp * times.size)
                            .background(Palette.infoBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text(category, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.infoFg) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderCell(def?.displayName ?: cells.first().displayName, 150.dp)
                times.forEach { t ->
                    val cell = cells.firstOrNull { it.timestamp == t }
                    ValueCell(cell)
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .width(width)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, fontFamily = FontFamily.SansSerif) }
}

@Composable
private fun ValueCell(cell: LabResultEntity?) {
    var detail by remember { mutableStateOf(false) }
    if (detail && cell != null) {
        AlertDialog(
            onDismissRequest = { detail = false },
            title = { Text(cell.displayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Value: ${cell.numericValue} ${cell.unit}")
                    Text("Category: ${cell.category}")
                    Text("Direction: ${cell.abnormalDirection}")
                    LabCatalogue.definition(cell.testCode)?.let { d ->
                        Text("Reference: ${LabCatalogue.evaluate(d, cell.numericValue).reference}")
                    }
                    if (cell.isPanic) Text("PANIC VALUE — act now", color = Palette.emergencyFg, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = { TextButton(onClick = { detail = false }) { Text("Close") } }
        )
    }
    val (bg, fg) = when {
        cell == null -> Color.Transparent to WardPalette.freeFg
        cell.isPanic -> Palette.emergencyBg to Palette.emergencyFg
        cell.abnormalDirection == "HIGH" -> Palette.urgentBg to Palette.urgentFg
        cell.abnormalDirection == "LOW" -> Palette.infoBg to Palette.infoFg
        else -> Color.Transparent to Palette.greyFg
    }
    val label = when {
        cell == null -> "—"
        cell.isPanic -> "⚠ ${fmtNum(cell.numericValue)}"
        cell.abnormalDirection == "HIGH" -> "↑ ${fmtNum(cell.numericValue)}"
        cell.abnormalDirection == "LOW" -> "↓ ${fmtNum(cell.numericValue)}"
        else -> fmtNum(cell.numericValue)
    }
    Box(
        Modifier
            .width(96.dp)
            .height(30.dp)
            .padding(2.dp)
            .background(bg)
            .border(0.5.dp, Palette.greyBg)
            .clickable(enabled = cell != null) { detail = true },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = if (cell?.isPanic == true) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun fmtNum(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

@Composable
private fun ManualLabDialog(
    onDismiss: () -> Unit,
    onSave: (String, Double, String, Long) -> Unit
) {
    var selected by remember { mutableStateOf(LabCatalogue.definitions.first()) }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add lab result") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabCatalogue.definitions.forEach { d ->
                        Choice(d.displayName, selected.code == d.code) { selected = d }
                    }
                }
                OutlinedTextField(
                    value = value, onValueChange = { value = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Value (${selected.unit})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = unit.ifBlank { selected.unit }, onValueChange = { unit = it },
                    label = { Text("Unit") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                val v = value.toDoubleOrNull()
                if (v != null) {
                    val eval = LabCatalogue.evaluate(selected, v)
                    Text("${eval.direction} · ref ${eval.reference}" + if (eval.isPanic) " · PANIC" else "", color = if (eval.isPanic) Palette.emergencyFg else Palette.greyFg, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { value.toDoubleOrNull()?.let { onSave(selected.code, it, unit.ifBlank { selected.unit }, System.currentTimeMillis()) } },
                enabled = value.toDoubleOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ScanLabDialog(
    labVm: LabViewModel,
    patientId: String,
    encounterId: String?,
    onDismiss: () -> Unit
) {
    val state by labVm.ui.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = { if (state.busy == null) onDismiss() },
        title = { Text("Scan / paste lab slip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("Type or paste the slip text, or paste results below. The model extracts numeric values for you to confirm.")
                OutlinedTextField(
                    value = state.scanText,
                    onValueChange = { labVm.setScanText(it) },
                    label = { Text("Lab slip text (e.g. 'Hb 8.4 g/dL, TLC 12000, Na 132')") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { if (encounterId != null) labVm.extractFromText(patientId, encounterId, state.scanText) else labVm.showError("No open admission.") },
                    enabled = state.busy == null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Extract results") }
                state.busy?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                state.error?.let { Text(it, color = Palette.emergencyFg, fontSize = 12.sp) }
                if (state.candidates.isNotEmpty()) {
                    Text("Confirm results", fontWeight = FontWeight.SemiBold)
                    state.candidates.forEachIndexed { i, c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.include, onCheckedChange = { labVm.toggleCandidate(i) })
                            Text(
                                "${c.displayName}: ${fmtNum(c.numericValue)} ${c.unit}" + (if (c.isPanic) "  ⚠ PANIC" else if (c.direction != "NORMAL") "  ${c.direction}" else ""),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.candidates.isNotEmpty()) {
                TextButton(onClick = { if (encounterId != null) labVm.confirmCandidates(patientId, encounterId) }) { Text("Save confirmed") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = { TextButton(onClick = { labVm.clearCandidates(); onDismiss() }) { Text("Cancel") } }
    )
}
