package np.opd.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.opd.assistant.bridge.LocalPcBridgeService

/**
 * Start/stop dialog for the local PC bridge. Shows the Wi-Fi address, port and session PIN the
 * doctor types into the hospital desktop browser.
 */
@Composable
fun PcBridgeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by LocalPcBridgeService.state.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PC Bridge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Serve discharge notes, operation notes/cardex and the SBAR handover to a hospital desktop on the same Wi-Fi. Read-only; copy straight into the desktop EMR clipboard.", fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Bridge", fontWeight = FontWeight.SemiBold)
                    Switch(checked = state.running, onCheckedChange = { on ->
                        if (on) LocalPcBridgeService.start(context) else LocalPcBridgeService.stop(context)
                    })
                }
                if (state.running) {
                    Text("Open in the desktop browser:", fontSize = 12.sp)
                    Text("http://${state.ip}:${state.port}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Session PIN: ${state.pin}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Muted("Note: cleartext HTTP on your local network only. Do not expose to the internet.")
                } else {
                    Muted("Off. Turn on to generate a local URL and PIN.")
                }
                state.error?.let { Text(it, color = Palette.emergencyFg, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
