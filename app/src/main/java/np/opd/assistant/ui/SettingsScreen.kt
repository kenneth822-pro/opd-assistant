package np.opd.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import np.opd.assistant.Screen
import np.opd.assistant.SessionViewModel
import np.opd.assistant.UiState
import np.opd.assistant.ai.ModelInfo
import np.opd.assistant.ai.Provider

private enum class PickerTarget { SPEECH, NOTES, BACKUP }

private fun limitsNote(p: Provider): String = when (p) {
    Provider.GEMINI -> "The free tier is fine for role-play tests but has per-minute and per-day limits; Google may use free-tier data to improve its products. If a model says 'high demand' (HTTP 503), the backup model takes over."
    Provider.OPENROUTER -> "Free models: about 20 requests/minute and 50/day (1,000/day after a \$10 credit top-up). Live mode uses about 4-8 requests per minute. Few free models accept audio, so use them for notes."
    Provider.OPENCODE -> "OpenCode Zen gives one key for a curated set of coding/agent models; some are free for a limited time. They are text models: use them for notes, not for speech."
    Provider.GROQ -> "Whisper (speech-to-text) here is fast and handles Nepali reasonably; pair it with a notes model. Speakers are not labelled by Whisper; the notes model infers them."
    Provider.CUSTOM -> "For a server on your network, e.g. LM Studio or Ollama (address like http://192.168.1.20:1234/v1). Leave the key empty if the server needs none."
}

@Composable
internal fun SettingsScreen(st: UiState, vm: SessionViewModel) {
    var seconds by remember { mutableStateOf(vm.settings.segmentSeconds.toString()) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    BackHandler { vm.go(Screen.HOME) }

    picker?.let { target ->
        ModelPickerDialog(
            title = when (target) {
                PickerTarget.SPEECH -> "Speech model (hears the audio)"
                PickerTarget.NOTES -> "Notes model (reads the transcript)"
                PickerTarget.BACKUP -> "Backup model (used when a model is busy)"
            },
            models = st.models,
            accept = { m ->
                when (target) {
                    PickerTarget.SPEECH -> m.audioInput || m.transcriptionApi
                    PickerTarget.NOTES -> !m.transcriptionApi
                    PickerTarget.BACKUP -> true
                }
            },
            filterNote = when (target) {
                PickerTarget.SPEECH -> "Showing models that accept audio or convert speech to text."
                PickerTarget.NOTES -> "Speech-to-text-only models are hidden."
                PickerTarget.BACKUP -> "Tip: an audio model (e.g. another Gemini Flash) can stand in for both speech and notes."
            },
            preferred = when (target) {
                PickerTarget.SPEECH -> st.speechModel?.provider
                PickerTarget.NOTES -> st.analysisModel?.provider ?: st.speechModel?.provider
                PickerTarget.BACKUP -> st.backupModel?.provider ?: st.speechModel?.provider
            },
            loading = st.modelsBusy,
            onLoad = { vm.loadModels(it) },
            onPick = { m ->
                when (target) {
                    PickerTarget.SPEECH -> vm.chooseSpeechModel(m)
                    PickerTarget.NOTES -> vm.chooseAnalysisModel(m)
                    PickerTarget.BACKUP -> vm.chooseBackupModel(m)
                }
                picker = null
            },
            onDismiss = { picker = null }
        )
    }

    Page("Settings", onBack = { vm.go(Screen.HOME) }) {
        st.modelsBusy?.let { Text(it); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        st.message?.let { Text(it, color = Palette.infoFg, fontWeight = FontWeight.SemiBold) }

        Text("AI providers", style = MaterialTheme.typography.titleMedium)
        Muted("Add a key for at least one provider. Keys are encrypted on this phone and only sent to that provider.")
        Provider.entries.forEach { p -> ProviderCard(p, st, vm) }

        Text("Models", style = MaterialTheme.typography.titleMedium)
        SectionCard("1. Speech model — listens to the audio") {
            ModelSummary(st.speechModel, "Not chosen yet")
            Muted("Needs AUDIO or SPEECH-TO-TEXT. Gemini Flash models work well for Nepali; Groq Whisper is a fast alternative.")
            Button(onClick = { picker = PickerTarget.SPEECH }) { Text("Choose speech model") }
        }
        SectionCard("2. Notes model — turns the transcript into suggestions and the note") {
            val sttOnly = st.speechModel?.transcriptionApi == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = st.analysisModel == null,
                    enabled = !sttOnly || st.analysisModel == null,
                    onCheckedChange = { checked -> if (checked) vm.chooseAnalysisModel(null) else picker = PickerTarget.NOTES }
                )
                Text("Use the speech model for this too")
            }
            if (st.analysisModel != null) {
                ModelSummary(st.analysisModel, "")
                OutlinedButton(onClick = { picker = PickerTarget.NOTES }) { Text("Change notes model") }
            } else if (sttOnly) {
                Text("${st.speechModel?.id} can only transcribe — choose a notes model.", color = Palette.urgentFg)
                Button(onClick = { picker = PickerTarget.NOTES }) { Text("Choose notes model") }
            }
            Muted("Any text model works, including free OpenRouter or OpenCode models. A bigger model gives better suggestions; a fast one gives quicker ones.")
        }
        SectionCard("3. Backup model (recommended)") {
            ModelSummary(st.backupModel, "None — if a model is busy (HTTP 503/429) that step waits and retries.")
            Muted("Used automatically when the speech or notes model is busy, rate-limited or retired. Pick a model from a different family or provider.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker = PickerTarget.BACKUP }) { Text(if (st.backupModel == null) "Choose backup" else "Change") }
                if (st.backupModel != null) TextButton(onClick = { vm.chooseBackupModel(null) }) { Text("Remove") }
            }
        }
        OutlinedButton(onClick = { vm.testModels() }, modifier = Modifier.fillMaxWidth()) { Text("Test selected models") }
        st.testResult?.let { Text(it, fontSize = 13.sp) }

        SectionCard("Recording") {
            OutlinedTextField(
                value = seconds,
                onValueChange = {
                    seconds = it.filter { ch -> ch.isDigit() }.take(2)
                    seconds.toIntOrNull()?.let { s -> if (s in 8..60) vm.setSegmentSeconds(s) }
                },
                label = { Text("Segment length in seconds (8-60)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Muted("Audio is cut at the first pause after this many seconds. Shorter = faster live text and suggestions, but more requests (free tiers have daily limits). 12-20 s is a good start.")
        }
        OutlinedButton(onClick = { vm.deleteAllAudio() }, modifier = Modifier.fillMaxWidth()) { Text("Delete all recorded audio on this phone") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProviderCard(p: Provider, st: UiState, vm: SessionViewModel) {
    val hasKey = p in st.keys
    val loaded = st.models[p]?.size ?: 0
    var open by remember { mutableStateOf(p == Provider.GEMINI && !hasKey) }
    var keyText by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(st.customBaseUrl) }
    var show by remember { mutableStateOf(false) }
    SectionCard(title = "") {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(p.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (hasKey) Pill("key ✓", Palette.okBg, Palette.okFg)
            if (loaded > 0) Pill("$loaded models", Palette.greyBg, Palette.greyFg)
            Text(if (open) "▾" else "▸", fontSize = 18.sp)
        }
        if (open) {
            Muted(p.keyHelp)
            if (p == Provider.CUSTOM) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("Server address (ends with /v1)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { vm.setCustomBaseUrl(url) }, enabled = url != st.customBaseUrl) { Text("Save address") }
            }
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it.trim() },
                label = { Text(if (hasKey) "Paste a new key to replace" else "Paste API key") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.saveKey(p, keyText); keyText = "" }, enabled = keyText.isNotBlank()) { Text("Save") }
                OutlinedButton(onClick = { vm.loadModels(p) }) { Text("Load models") }
                TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
            }
            if (hasKey) TextButton(onClick = { vm.removeKey(p) }) { Text("Remove key", color = Palette.greyFg) }
            Muted(limitsNote(p))
        }
    }
}

@Composable
internal fun ModelSummary(m: ModelInfo?, empty: String) {
    if (m == null) {
        if (empty.isNotBlank()) Text(empty, color = Palette.urgentFg, fontSize = 13.sp)
        return
    }
    Text(m.name, fontWeight = FontWeight.SemiBold)
    Text("${m.provider.label} · ${m.id}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { m.tags().forEach { TagPill(it) } }
}

@Composable
internal fun TagPill(tag: String) = when (tag) {
    "FREE" -> Pill("FREE", Palette.okBg, Palette.okFg)
    "PAID" -> Pill("PAID", Palette.greyBg, Palette.greyFg)
    "AUDIO" -> Pill("AUDIO", Palette.infoBg, Palette.infoFg)
    "SPEECH-TO-TEXT" -> Pill("SPEECH-TO-TEXT", Palette.infoBg, Palette.infoFg)
    else -> Pill(tag, Palette.greyBg, Palette.greyFg)
}

@Composable
private fun ModelPickerDialog(
    title: String,
    models: Map<Provider, List<ModelInfo>>,
    accept: (ModelInfo) -> Boolean,
    filterNote: String,
    preferred: Provider?,
    loading: String?,
    onLoad: (Provider) -> Unit,
    onPick: (ModelInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val initial = preferred?.takeIf { models[it]?.any(accept) == true }
        ?: Provider.entries.firstOrNull { p -> models[p]?.any(accept) == true }
        ?: Provider.GEMINI
    var provider by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    val source = models[provider] ?: emptyList()
    val q = query.trim().lowercase()
    val list = source.filter { m ->
        accept(m) && (!freeOnly || m.free == true) &&
            (q.isEmpty() || m.id.lowercase().contains(q) || m.name.lowercase().contains(q))
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Provider.entries.forEach { p ->
                        val n = models[p]?.count(accept) ?: 0
                        val label = p.label.substringBefore(" (") + if (n > 0) " ($n)" else ""
                        if (p == provider) Button(onClick = { provider = p }) { Text(label, fontSize = 12.sp) }
                        else OutlinedButton(onClick = { provider = p }) { Text(label, fontSize = 12.sp) }
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search (e.g. flash, whisper, deepseek)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (provider != Provider.GEMINI) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = freeOnly, onCheckedChange = { freeOnly = it })
                    Text("Free models only")
                }
                Muted(filterNote)
                if (provider == Provider.GEMINI) Muted("Gemini cost depends on your key: free tier (rate-limited) or paid.")
                loading?.let { Text(it, fontSize = 12.sp) }
                if (source.isEmpty()) {
                    Muted("No ${provider.label} models loaded yet. Save a key in Settings first.")
                    OutlinedButton(onClick = { onLoad(provider) }) { Text("Load ${provider.label.substringBefore(" (")} models") }
                } else if (list.isEmpty()) {
                    Muted("No models here match.")
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(list, key = { it.key }) { m ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(m) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(m.id, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Palette.greyFg)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { m.tags().forEach { TagPill(it) } }
                        }
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
