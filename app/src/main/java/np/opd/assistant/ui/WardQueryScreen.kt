package np.opd.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.opd.assistant.ward.WardQueryViewModel

/**
 * "Ask the Ward": free-text questions over the census plus a one-tap SBAR handover. Answers come
 * from the notes model reading the census snapshot; a deterministic SBAR is available offline.
 */
@Composable
fun WardQueryScreen(vm: WardQueryViewModel, onBack: () -> Unit) {
    val st by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler { onBack() }

    LaunchedEffect(Unit) { vm.refreshCensus() }

    Page(title = "Ask the Ward", onBack = onBack) {
        Muted("${st.censusSize} active inpatient(s) in the census. Answers use recorded ward data only.")

        SectionCard("Question") {
            OutlinedTextField(
                value = st.question,
                onValueChange = { vm.setQuestion(it) },
                label = { Text("e.g. Which patients in the Semi-ICU have haemoglobin below 8?") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.ask() }, modifier = Modifier.weight(1f)) { Text("Ask") }
                OutlinedButton(onClick = { vm.generateHandover() }, modifier = Modifier.weight(1f)) { Text("SBAR handover") }
            }
        }

        st.busy?.let {
            Text(it, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        st.error?.let { Text(it, color = Palette.urgentFg, fontSize = 13.sp) }

        if (st.answer.isNotBlank()) {
            SectionCard("Answer") {
                Text(st.answer, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copy(context, "Ward answer", st.answer) }, modifier = Modifier.weight(1f)) { Text("Copy") }
                OutlinedButton(onClick = { share(context, st.answer) }, modifier = Modifier.weight(1f)) { Text("Share") }
            }
            OutlinedButton(onClick = { vm.clearAnswer() }, modifier = Modifier.fillMaxWidth()) { Text("Clear") }
        }

        if (st.localSbar.isNotBlank()) {
            SectionCard("Offline SBAR (always available)") {
                Text(st.localSbar, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                OutlinedButton(
                    onClick = { copy(context, "SBAR handover", st.localSbar) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Copy offline SBAR") }
            }
        }
    }
}

private fun copy(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share ward answer"))
}
