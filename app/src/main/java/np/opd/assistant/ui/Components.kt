package np.opd.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.opd.engine.Phase
import np.opd.engine.SafetyFinding
import np.opd.engine.SessionLevel
import np.opd.engine.Severity

object Palette {
    val emergencyBg = Color(0xFFFFDAD6)
    val emergencyFg = Color(0xFF8C1D18)
    val urgentBg = Color(0xFFFFEBB8)
    val urgentFg = Color(0xFF5E4400)
    val okBg = Color(0xFFDDF3E4)
    val okFg = Color(0xFF14532D)
    val infoBg = Color(0xFFE3EEF7)
    val infoFg = Color(0xFF123A5A)
    val greyBg = Color(0xFFEDEFF1)
    val greyFg = Color(0xFF3C4043)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back") } },
                actions = actions
            )
        },
        bottomBar = bottomBar
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Text(
        text,
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
fun PhasePill(phase: Phase) = when (phase) {
    Phase.HISTORY -> Pill("Taking history", Palette.greyBg, Palette.greyFg)
    Phase.PLAN -> Pill("Plan", Palette.okBg, Palette.okFg)
    Phase.DICTATION -> Pill("Doctor dictating", Palette.infoBg, Palette.infoFg)
    Phase.ESCALATED -> Pill("Escalated", Palette.emergencyBg, Palette.emergencyFg)
}

@Composable
fun Banner(lines: List<String>, level: SessionLevel) {
    if (lines.isEmpty()) return
    val (bg, fg) = when (level) {
        SessionLevel.EMERGENCY -> Palette.emergencyBg to Palette.emergencyFg
        SessionLevel.URGENT -> Palette.urgentBg to Palette.urgentFg
        SessionLevel.INFO -> Palette.infoBg to Palette.infoFg
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(if (level == SessionLevel.EMERGENCY) "⚠ EMERGENCY" else "⚠ ATTENTION", color = fg, fontWeight = FontWeight.Bold)
        lines.forEach { Text("• $it", color = fg) }
    }
}

@Composable
fun FindingLine(f: SafetyFinding) {
    val (bg, fg, label) = when (f.severity) {
        Severity.BLOCKED -> Triple(Palette.emergencyBg, Palette.emergencyFg, "BLOCKED")
        Severity.WARNING -> Triple(Palette.urgentBg, Palette.urgentFg, "WARNING")
        Severity.CAUTION -> Triple(Palette.greyBg, Palette.greyFg, "CAUTION")
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(label, bg, fg)
        Text(f.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SectionCard(title: String, container: Color = Color.Unspecified, content: @Composable ColumnScope.() -> Unit) {
    val colors = if (container == Color.Unspecified) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = container)
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.greyFg)

/** Asks the doctor for a short reason before an override (kept in the audit log). */
@Composable
fun ReasonDialog(title: String, hint: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(hint) }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifEmpty { "no reason given" }) }) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
