package np.opd.assistant.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.opd.assistant.SessionViewModel
import np.opd.assistant.UiState
import np.opd.speech.InstallState
import np.opd.speech.LocalAsrStatus
import np.opd.speech.SpeechEngine

/**
 * "Speech recognition" settings: where the audio becomes text. Separate from the API-key provider cards on purpose:
 * on-device speech has no key, no rate limit and no server.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeechSettingsCard(st: UiState, vm: SessionViewModel) {
    val spec = vm.onDeviceAsr.spec
    val install = st.localInstall
    val busy = install is InstallState.Downloading || install is InstallState.Verifying || install is InstallState.Installing
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) vm.importLocalModel(uri) }

    SectionCard("Speech recognition — turns the audio into text") {
        Muted("On-device keeps the audio on this phone: no API key, no rate limit, works offline. The notes/analysis model below still reads the transcript text, as before.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Choice("On-device Nepali", st.speechEngine == SpeechEngine.ON_DEVICE) { vm.chooseSpeechEngine(SpeechEngine.ON_DEVICE) }
            Choice("Automatic", st.speechEngine == SpeechEngine.AUTO) { vm.chooseSpeechEngine(SpeechEngine.AUTO) }
            Choice("Cloud", st.speechEngine == SpeechEngine.CLOUD) { vm.chooseSpeechEngine(SpeechEngine.CLOUD) }
        }
        Muted(
            when (st.speechEngine) {
                SpeechEngine.ON_DEVICE -> "Audio is never sent to a cloud provider. If the on-device engine is not ready, recording does not start and tells you why."
                SpeechEngine.AUTO -> "Uses the on-device engine whenever it is ready. A cloud speech model (below) is used only if the on-device engine is missing or fails on a segment. With no model installed this is the same as Cloud."
                SpeechEngine.CLOUD -> "The cloud speech model chosen below transcribes the audio (the previous behaviour)."
            }
        )
        HorizontalDivider()

        Text("On-device Nepali ASR", fontWeight = FontWeight.SemiBold)
        val (bg, fg) = statusColors(st.localAsr)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(st.localAsr.label, bg, fg)
            Pill("offline", Palette.greyBg, Palette.greyFg)
            Pill("no API key", Palette.greyBg, Palette.greyFg)
            Pill("audio stays on phone", Palette.greyBg, Palette.greyFg)
        }
        when (val s = st.localAsr) {
            is LocalAsrStatus.ModelError -> Text(s.message, color = Palette.emergencyFg, fontSize = 13.sp)
            is LocalAsrStatus.EngineError -> Text(s.message, color = Palette.emergencyFg, fontSize = 13.sp)
            is LocalAsrStatus.NeedsFile -> Muted(s.reason)
            LocalAsrStatus.RuntimeMissing -> Muted("This build was made without the on-device engine. Build with -PenableNativeAsr=true (see docs/ON_DEVICE_ASR.md).")
            else -> Unit
        }

        if (spec != null) {
            Text(spec.displayName, fontSize = 13.sp)
            Text("${spec.source.repo} · ${spec.format}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Palette.greyFg)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill("licence: ${spec.license.name}", if (spec.license.nonCommercial) Palette.urgentBg else Palette.greyBg, if (spec.license.nonCommercial) Palette.urgentFg else Palette.greyFg)
                if (spec.license.nonCommercial) Pill("non-commercial use only", Palette.urgentBg, Palette.urgentFg)
                if (!spec.license.verified) Pill("licence not verified", Palette.urgentBg, Palette.urgentFg)
                if (!spec.source.verified) Pill("source not verified", Palette.urgentBg, Palette.urgentFg)
            }
        }

        when (install) {
            is InstallState.Downloading -> {
                val pct = install.percent
                Text("Downloading ${install.fileName}" + (pct?.let { " — $it%" } ?: ""), fontSize = 13.sp)
                if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            InstallState.Verifying -> { Text("Checking the file…", fontSize = 13.sp); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            InstallState.Installing -> { Text("Installing…", fontSize = 13.sp); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            is InstallState.Ready -> {
                Text(
                    if (install.pinnedMatch) "Installed and matches the pinned checksum." else "Installed from a file you provided (checksum not pinned in this app).",
                    fontSize = 13.sp, color = if (install.pinnedMatch) Palette.okFg else Palette.urgentFg
                )
                Text("sha256 ${install.sha256}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Palette.greyFg)
                if (install.updateAvailable) Text("A newer model is listed in this app. Delete and install again to update.", color = Palette.urgentFg, fontSize = 13.sp)
            }
            else -> Unit
        }

        val canDownload = spec?.downloadable == true && !busy && (install is InstallState.NotInstalled || install is InstallState.Failed)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { vm.installLocalModel() }, enabled = canDownload) { Text("Download model") }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy && spec != null) { Text("Import model file…") }
            OutlinedButton(onClick = { vm.testLocalAsr() }, enabled = st.localAsr.isReady && !st.recording) { Text("Test (speak 6 s)") }
            if (install is InstallState.Ready) {
                TextButton(onClick = { vm.verifyLocalModel() }, enabled = !busy) { Text("Verify file") }
                TextButton(onClick = { vm.deleteLocalModel() }, enabled = !busy && !st.recording) { Text("Delete model", color = Palette.emergencyFg) }
            }
        }
        if (spec != null && !spec.downloadable && install !is InstallState.Ready) {
            Muted("Download is off for this model until its checksum is pinned (a download is never installed unchecked). Convert the model and copy it to the phone, then use 'Import model file…'. Steps: docs/ON_DEVICE_ASR.md.")
        }
        st.localAsrTest?.let { Text(it, fontSize = 13.sp, color = Color.Unspecified) }
    }
}

private fun statusColors(s: LocalAsrStatus): Pair<Color, Color> = when (s) {
    is LocalAsrStatus.Ready, is LocalAsrStatus.Processing -> Palette.okBg to Palette.okFg
    is LocalAsrStatus.ModelError, is LocalAsrStatus.EngineError -> Palette.emergencyBg to Palette.emergencyFg
    is LocalAsrStatus.Downloading, LocalAsrStatus.Verifying, LocalAsrStatus.Installing, LocalAsrStatus.Loading -> Palette.infoBg to Palette.infoFg
    else -> Palette.urgentBg to Palette.urgentFg
}
