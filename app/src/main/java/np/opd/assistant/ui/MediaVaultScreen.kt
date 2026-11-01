package np.opd.assistant.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.opd.assistant.data.local.entities.MediaCategory
import np.opd.assistant.data.local.entities.MediaEntity
import np.opd.assistant.data.local.entities.MediaSide
import np.opd.assistant.ward.MediaViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/**
 * Media vault for one patient: capture photos to app-private storage, tag them, browse by
 * category and date, compare wound photos over time, and anonymise before sharing.
 */
@Composable
fun MediaVaultScreen(vm: MediaViewModel, modifier: Modifier = Modifier) {
    val st by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var tagFor by remember { mutableStateOf<MediaEntity?>(null) }
    var viewerFor by remember { mutableStateOf<MediaEntity?>(null) }
    var showCompare by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            // The file is written; prompt for a category/label before registering it.
            tagFor = MediaEntity(id = "pending", patientId = "", filePath = "")
        } else {
            vm.captureFailed("Capture cancelled.")
        }
    }

    fun launchCapture() {
        val file = vm.prepareCapture() ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingUri = uri
        cameraLauncher.launch(uri)
    }

    tagFor?.let { media ->
        TagDialog(
            media = media,
            onDismiss = { tagFor = null },
            onSave = { cat, label, side, notes ->
                vm.registerCapture(cat, label, side, notes)
                tagFor = null
            }
        )
    }
    viewerFor?.let { media ->
        MediaViewerDialog(
            media = media,
            onDismiss = { viewerFor = null },
            onAnonymise = { vm.anonymise(media); viewerFor = null },
            onDelete = { vm.delete(media); viewerFor = null }
        )
    }
    if (showCompare) {
        val woundSeries = st.items.filter { it.category == MediaCategory.WOUND_POSTOP }.sortedBy { it.captureDatetime }
        if (woundSeries.size >= 2) {
            val first = woundSeries.first()
            val last = woundSeries.last()
            AlertDialog(
                onDismissRequest = { showCompare = false },
                title = { Text("Wound healing over time") },
                text = {
                    WoundComparison(
                        leftPath = first.filePath,
                        rightPath = last.filePath,
                        leftCaption = DATE_FMT.format(Date(first.captureDatetime)),
                        rightCaption = DATE_FMT.format(Date(last.captureDatetime))
                    )
                },
                confirmButton = { TextButton(onClick = { showCompare = false }) { Text("Close") } }
            )
        } else {
            showCompare = false
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        st.busy?.let { Text(it, fontWeight = FontWeight.SemiBold) }
        st.error?.let { Text(it, color = Palette.emergencyFg, fontSize = 13.sp) }
        st.message?.let { Text(it, color = Palette.okFg, fontSize = 13.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launchCapture() }) { Text("📷 Capture") }
            val wounds = st.items.count { it.category == MediaCategory.WOUND_POSTOP }
            OutlinedButton(onClick = { showCompare = true }, enabled = wounds >= 2) {
                Text("Compare wounds ($wounds)")
            }
        }

        // Category filters
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Choice("All", st.filter == null) { vm.setFilter(null) }
            MediaCategory.entries.forEach { c ->
                Choice(catLabel(c), st.filter == c) { vm.setFilter(c) }
            }
        }

        if (st.filtered.isEmpty()) {
            SectionCard("No media") { Muted("No photos for this patient yet. Tap Capture to add one.") }
        } else {
            // Group by date
            st.filtered.groupBy { DATE_FMT.format(Date(it.captureDatetime)) }.forEach { (date, list) ->
                Text(date, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    list.forEach { media ->
                        MediaThumb(media, onClick = { viewerFor = media })
                    }
                }
            }
        }

        SectionCard("Privacy", container = Palette.urgentBg) {
            Text("Photos are stored in app-private storage (files/patients/…/media) and are never written to the public gallery. Anonymise before sharing.", fontSize = 12.sp)
        }
    }
}

@Composable
private fun MediaThumb(media: MediaEntity, onClick: () -> Unit) {
    Column(Modifier.height(120.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier
                .height(88.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
        ) {
            MediaImage(media.filePath, Modifier.fillMaxWidth().height(88.dp))
            if (media.anonymised) {
                Pill("ANON", Palette.okBg, Palette.okFg)
            }
        }
        Text(catLabel(media.category), fontSize = 10.sp, maxLines = 1)
        media.label?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 10.sp, maxLines = 1) }
    }
}

@Composable
private fun MediaViewerDialog(
    media: MediaEntity,
    onDismiss: () -> Unit,
    onAnonymise: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete photo?") },
            text = { Text("This removes the photo from the vault permanently.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(catLabel(media.category)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaImage(media.filePath, Modifier.fillMaxWidth().height(280.dp))
                val meta = buildString {
                    append("Captured: "); append(DATE_FMT.format(Date(media.captureDatetime)))
                    media.label?.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                    if (media.side != MediaSide.NA) { append(" · "); append(media.side.name.lowercase()) }
                    if (media.anonymised) append(" · anonymised")
                }
                Muted(meta)
                media.notes?.takeIf { it.isNotBlank() }?.let { Muted(it) }
            }
        },
        confirmButton = { TextButton(onClick = onAnonymise) { Text("Anonymise copy") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun TagDialog(
    media: MediaEntity,
    onDismiss: () -> Unit,
    onSave: (MediaCategory, String, MediaSide, String?) -> Unit
) {
    var category by remember { mutableStateOf(media.category) }
    var label by remember { mutableStateOf(media.label ?: "") }
    var side by remember { mutableStateOf(media.side) }
    var notes by remember { mutableStateOf(media.notes ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag photo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Category", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaCategory.entries.forEach { c ->
                        Choice(catLabel(c), category == c) { category = c }
                    }
                }
                Text("Side", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaSide.entries.forEach { s ->
                        Choice(s.name.lowercase(), side == s) { side = s }
                    }
                }
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (e.g. POD 3 wound)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(category, label, side, notes.ifBlank { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun catLabel(c: MediaCategory): String = when (c) {
    MediaCategory.WOUND_POSTOP -> "Wound"
    MediaCategory.XRAY -> "X-Ray"
    MediaCategory.CT_SCAN -> "CT"
    MediaCategory.MRI -> "MRI"
    MediaCategory.ULTRASOUND -> "US"
    MediaCategory.ECG -> "ECG"
    MediaCategory.LAB_REPORT -> "Lab"
    MediaCategory.BEDSIDE_CHART -> "Chart"
    MediaCategory.OTHER -> "Other"
}
