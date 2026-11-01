package np.opd.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.opd.assistant.data.local.entities.DischargeKind
import np.opd.assistant.data.local.entities.DischargeStatus
import np.opd.assistant.ward.DischargeViewModel

/**
 * Discharge builder. Shown inside the patient chart's Discharge tab. Produces a PC-clean plain
 * text export (copy) and a formatted markdown variant (share/print) from the doctor's entries.
 */
@Composable
fun DischargeScreen(vm: DischargeViewModel, modifier: Modifier = Modifier) {
    val st by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("Discharge type") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("ER discharge", st.kind == DischargeKind.ER) { vm.setKind(DischargeKind.ER) }
                Choice("Inpatient discharge", st.kind == DischargeKind.INPATIENT) { vm.setKind(DischargeKind.INPATIENT) }
            }
            Text(
                if (st.kind == DischargeKind.ER)
                    "Acute ambulatory: triage vitals, ER interventions, short-course medicines, return-to-ER triggers."
                else
                    "Multi-day admission: operative detail, hospital course, comprehensive home regimen, suture/OPD follow-up.",
                fontSize = 12.sp
            )
        }

        st.busy?.let { Text(it, color = Palette.infoFg, fontWeight = FontWeight.SemiBold) }
        st.error?.let { Text(it, color = Palette.emergencyFg) }
        st.message?.let { Text(it, color = Palette.okFg) }

        Field("Consultant / attending", st.consultant) { v -> vm.update { it.copy(consultant = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Admission date", st.admissionDate, Modifier.weight(1f)) { v -> vm.update { it.copy(admissionDate = v) } }
            Field("Discharge date", st.dischargeDate, Modifier.weight(1f)) { v -> vm.update { it.copy(dischargeDate = v) } }
        }

        if (st.kind == DischargeKind.INPATIENT) {
            SectionCard("Operative details (if surgical)") {
                Field("Procedure", st.procedureName) { v -> vm.update { it.copy(procedureName = v) } }
                Field("Pre-operative diagnosis", st.preoperativeDiagnosis) { v -> vm.update { it.copy(preoperativeDiagnosis = v) } }
                Field("Post-operative diagnosis", st.postoperativeDiagnosis) { v -> vm.update { it.copy(postoperativeDiagnosis = v) } }
                Field("Operative findings", st.operativeFindings, minLines = 2) { v -> vm.update { it.copy(operativeFindings = v) } }
            }
        }

        SectionCard("Clinical narrative") {
            Field("Presenting complaint", st.presentingComplaints, minLines = 2) { v -> vm.update { it.copy(presentingComplaints = v) } }
            Field(
                if (st.kind == DischargeKind.ER) "ER course / interventions" else "Hospital course",
                st.hospitalCourse, minLines = 3
            ) { v -> vm.update { it.copy(hospitalCourse = v) } }
            Field("Discharge vitals", st.dischargeVitals) { v -> vm.update { it.copy(dischargeVitals = v) } }
        }

        SectionCard("Medications on discharge") {
            if (st.medications.isEmpty()) {
                Muted("No medicines on the cardex. Add them on the Cardex tab before writing the summary.")
            } else {
                st.medications.forEachIndexed { i, m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${i + 1}. ${m.name} ${m.frequency}".trim(), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.removeMedication(i) }) { Text("Remove") }
                    }
                }
            }
        }

        if (st.kind == DischargeKind.INPATIENT) {
            Field("Wound & nursing advice", st.woundNursingAdvice, minLines = 2) { v -> vm.update { it.copy(woundNursingAdvice = v) } }
        }
        Field("Return to hospital if", st.redFlagsReturn, minLines = 2) { v -> vm.update { it.copy(redFlagsReturn = v) } }
        Field("Follow-up", st.followUp, minLines = 2) { v -> vm.update { it.copy(followUp = v) } }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save() }) { Text("Save summary") }
            OutlinedButton(onClick = { vm.render() }) { Text("Preview") }
        }

        if (st.renderedText.isNotBlank()) {
            SectionCard("Preview (plain text for PC EMR)") {
                Text(
                    st.renderedText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        copyToClipboard(context, "Discharge summary", st.renderedText)
                        vm.showMessage("Copied clean text to clipboard.")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Copy for PC") }
                OutlinedButton(
                    onClick = { share(context, st.renderedText) },
                    modifier = Modifier.weight(1f)
                ) { Text("Share") }
            }
            OutlinedButton(
                onClick = { copyToClipboard(context, "Discharge summary (markdown)", st.renderedMarkdown); vm.showMessage("Copied markdown.") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Copy markdown (print)") }
        }

        SectionCard("Governance", container = Palette.urgentBg) {
            Text("Status: ${st.status.name}", fontSize = 12.sp)
            Text("This is a prototype. The discharge text is UNVERIFIED and must be checked before it is used clinically.", fontSize = 12.sp)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    minLines: Int = 1,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = modifier
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share discharge summary"))
}
