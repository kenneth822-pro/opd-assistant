package np.opd.assistant.ui

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import np.opd.assistant.ai.ModelInfo
import np.opd.assistant.ai.Provider
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import np.opd.assistant.Mode
import np.opd.assistant.RxText
import np.opd.assistant.Screen
import np.opd.assistant.SessionViewModel
import np.opd.assistant.UiState
import np.opd.engine.Catalogues
import np.opd.engine.DoctorAction
import np.opd.engine.FlagStatus
import np.opd.engine.Gender
import np.opd.engine.Phase
import np.opd.engine.Pregnancy
import np.opd.engine.Problem
import np.opd.engine.Severity
import np.opd.engine.Sig
import np.opd.engine.Vitals

private val AppColors = lightColorScheme(
    primary = Color(0xFF0B5C66),
    secondary = Color(0xFF4A6366),
    background = Color(0xFFF7FAFA),
    surface = Color(0xFFF7FAFA)
)

@Composable
fun OpdApp(vm: SessionViewModel) {
    val st by vm.ui.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = AppColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (st.screen) {
                Screen.DISCLAIMER -> DisclaimerScreen(vm)
                Screen.HOME -> HomeScreen(st, vm)
                Screen.SETTINGS -> SettingsScreen(st, vm)
                Screen.SETUP -> SetupScreen(st, vm)
                Screen.RECORDING -> RecordingScreen(st, vm)
                Screen.REVIEW -> ReviewScreen(st, vm)
            }
        }
    }
}

/* ============================== Disclaimer ============================== */

@Composable
private fun DisclaimerScreen(vm: SessionViewModel) {
    var agreed by remember { mutableStateOf(false) }
    Page("Before you start") {
        SectionCard("Prototype for internal testing", container = Palette.urgentBg) {
            Text("This app is an early prototype. Use it ONLY with role-played or simulated consultations.")
            Text("• Medicine suggestions, doses and safety rules are UNVERIFIED and may be wrong.")
            Text("• Audio is sent to Google's Gemini API using your own API key.")
            Text("• Do not record real patients until a clinician has verified the content and the required consent, privacy and ethics steps are in place.")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I understand and will use test conversations only.")
        }
        Button(onClick = { vm.acceptDisclaimer() }, enabled = agreed, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

/* ============================== Home ============================== */

@Composable
private fun HomeScreen(st: UiState, vm: SessionViewModel) {
    val ready = st.speechModel != null && vm.catalogues != null
    Page("OPD Assistant") {
        Text("Ambient clinical assistant — prototype", style = MaterialTheme.typography.titleMedium)
        Muted("Listens to a consultation, writes the note, and suggests protocol medicines only after safety checks.")
        if (st.speechModel == null) {
            SectionCard("Step 1: add a key and choose a model", container = Palette.urgentBg) {
                Text("Open Settings, paste a Gemini key (free from aistudio.google.com/apikey) and the app will load the current models.")
                Button(onClick = { vm.go(Screen.SETTINGS) }) { Text("Open Settings") }
            }
        } else {
            SectionCard("AI models") {
                Text("Speech: ${st.speechModel.provider.label} · ${st.speechModel.id}", fontSize = 13.sp)
                Text("Notes: " + (st.analysisModel?.let { "${it.provider.label} · ${it.id}" } ?: "same model (one step)"), fontSize = 13.sp)
            }
        }
        if (vm.catalogueProblems.isNotEmpty()) {
            SectionCard("Catalogue problems", container = Palette.emergencyBg) {
                vm.catalogueProblems.take(10).forEach { Text("• $it") }
            }
        }
        Button(
            onClick = { vm.newConsultation() },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("New consultation", fontSize = 18.sp) }
        if (st.hasSession && st.case.tick > 0) {
            OutlinedButton(onClick = { vm.go(Screen.REVIEW) }, modifier = Modifier.fillMaxWidth()) { Text("Open last consultation") }
        }
        OutlinedButton(onClick = { vm.go(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
        st.message?.let { Text(it, color = Palette.emergencyFg) }
        HorizontalDivider()
        vm.catalogues?.let { c ->
            Muted("Clinical catalogue v${c.version}: ${c.conditions.size} conditions, ${c.regimens.size} regimens, ${c.drugs.size} drugs, ${c.redFlags.size} red flags. All content is UNVERIFIED prototype data.")
        }
    }
}

/* ============================== Settings ============================== */

private enum class PickerTarget { SPEECH, ANALYSIS }

@Composable
private fun SettingsScreen(st: UiState, vm: SessionViewModel) {
    var geminiKey by remember { mutableStateOf("") }
    var orKey by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf(vm.settings.segmentSeconds.toString()) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    BackHandler { vm.go(Screen.HOME) }

    picker?.let { target ->
        ModelPickerDialog(
            title = if (target == PickerTarget.SPEECH) "Speech model (must hear audio)" else "Notes model (reads the transcript)",
            gemini = st.geminiModels,
            openRouter = st.openRouterModels,
            requireAudio = target == PickerTarget.SPEECH,
            onLoad = { vm.loadModels(it) },
            loading = st.modelsBusy,
            onPick = { m ->
                if (target == PickerTarget.SPEECH) vm.chooseSpeechModel(m) else vm.chooseAnalysisModel(m)
                picker = null
            },
            onDismiss = { picker = null }
        )
    }

    Page("Settings", onBack = { vm.go(Screen.HOME) }) {
        st.modelsBusy?.let { Text(it); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        st.message?.let { Text(it, color = Palette.infoFg, fontWeight = FontWeight.SemiBold) }

        // ---- Keys ----
        KeyCard(
            title = "Google Gemini key",
            saved = st.hasGeminiKey,
            help = "Free key: aistudio.google.com/apikey (Google account). The free tier is fine for role-play tests; it has rate limits and Google may use free-tier data to improve its products.",
            value = geminiKey,
            onChange = { geminiKey = it },
            onSave = { vm.saveKey(Provider.GEMINI, geminiKey); geminiKey = "" },
            onLoad = { vm.loadModels(Provider.GEMINI) },
            loaded = st.geminiModels.size
        )
        KeyCard(
            title = "OpenRouter key (optional)",
            saved = st.hasOpenRouterKey,
            help = "openrouter.ai/keys. Gives access to many models, including free ones (e.g. DeepSeek) for the notes step. Free models: about 20 requests/minute and 50/day (1,000/day after a \$10 credit top-up). Live mode uses ~3-6 requests per minute.",
            value = orKey,
            onChange = { orKey = it },
            onSave = { vm.saveKey(Provider.OPENROUTER, orKey); orKey = "" },
            onLoad = { vm.loadModels(Provider.OPENROUTER) },
            loaded = st.openRouterModels.size
        )

        // ---- Models ----
        SectionCard("1. Speech model — listens to the audio") {
            ModelSummary(st.speechModel, "Not chosen yet")
            Muted("Needs a model that accepts audio input (tag AUDIO). Gemini Flash models work well for Nepali.")
            Button(onClick = { picker = PickerTarget.SPEECH }) { Text("Choose speech model") }
        }
        SectionCard("2. Notes model — turns speech into structured notes") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = st.oneStep, onCheckedChange = { checked ->
                    if (!checked) picker = PickerTarget.ANALYSIS
                    if (checked) vm.chooseAnalysisModel(null)
                })
                Text("Use the speech model for this too (one step, fastest)")
            }
            if (!st.oneStep) {
                ModelSummary(st.analysisModel, "")
                Muted("Two steps: the speech model writes a transcript, then this model reads it. Any text model works, including free OpenRouter models.")
                OutlinedButton(onClick = { picker = PickerTarget.ANALYSIS }) { Text("Change notes model") }
            }
        }
        OutlinedButton(onClick = { vm.testModels() }, modifier = Modifier.fillMaxWidth()) { Text("Test selected models") }
        st.testResult?.let { Text(it) }

        SectionCard("Recording") {
            OutlinedTextField(
                value = seconds,
                onValueChange = {
                    seconds = it.filter { ch -> ch.isDigit() }.take(2)
                    seconds.toIntOrNull()?.let { s -> if (s in 10..60) vm.setSegmentSeconds(s) }
                },
                label = { Text("Segment length in seconds (10-60)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Muted("Audio is cut at the first pause after this many seconds. Shorter = faster suggestions, more requests.")
        }
        OutlinedButton(onClick = { vm.deleteAllAudio() }, modifier = Modifier.fillMaxWidth()) { Text("Delete all recorded audio on this phone") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun KeyCard(
    title: String,
    saved: Boolean,
    help: String,
    value: String,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    loaded: Int
) {
    var show by remember { mutableStateOf(false) }
    SectionCard(title) {
        Text(if (saved) "✓ Key saved (encrypted on this phone)" else "No key saved", color = if (saved) Palette.okFg else Palette.greyFg, fontSize = 13.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(if (saved) "Paste a new key to replace" else "Paste key") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSave, enabled = value.isNotBlank()) { Text("Save key") }
            OutlinedButton(onClick = onLoad) { Text("Load models") }
            TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
        }
        if (loaded > 0) Muted("$loaded models loaded")
        Muted(help)
    }
}

@Composable
private fun ModelSummary(m: ModelInfo?, empty: String) {
    if (m == null) {
        if (empty.isNotBlank()) Text(empty, color = Palette.urgentFg)
        return
    }
    Text(m.name, fontWeight = FontWeight.SemiBold)
    Text("${m.provider.label} · ${m.id}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { m.tags().forEach { TagPill(it) } }
}

@Composable
private fun TagPill(tag: String) = when (tag) {
    "FREE" -> Pill("FREE", Palette.okBg, Palette.okFg)
    "PAID" -> Pill("PAID", Palette.greyBg, Palette.greyFg)
    "AUDIO" -> Pill("AUDIO", Palette.infoBg, Palette.infoFg)
    else -> Pill(tag, Palette.greyBg, Palette.greyFg)
}

@Composable
private fun ModelPickerDialog(
    title: String,
    gemini: List<ModelInfo>,
    openRouter: List<ModelInfo>,
    requireAudio: Boolean,
    onLoad: (Provider) -> Unit,
    loading: String?,
    onPick: (ModelInfo) -> Unit,
    onDismiss: () -> Unit
) {
    var provider by remember { mutableStateOf(if (gemini.isNotEmpty() || openRouter.isEmpty()) Provider.GEMINI else Provider.OPENROUTER) }
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    val source = if (provider == Provider.GEMINI) gemini else openRouter
    val q = query.trim().lowercase()
    val list = source.filter { m ->
        (!requireAudio || m.audioInput) &&
            (!freeOnly || m.free == true) &&
            (q.isEmpty() || m.id.lowercase().contains(q) || m.name.lowercase().contains(q))
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Provider.values().forEach { p ->
                        if (p == provider) Button(onClick = { provider = p }) { Text(p.label, fontSize = 12.sp) }
                        else OutlinedButton(onClick = { provider = p }) { Text(p.label, fontSize = 12.sp) }
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search (e.g. flash, deepseek)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (provider == Provider.OPENROUTER) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = freeOnly, onCheckedChange = { freeOnly = it })
                    Text("Free models only")
                }
                if (requireAudio) Muted("Showing only models that accept audio.")
                if (provider == Provider.GEMINI) Muted("Gemini cost depends on your key: free tier (rate-limited) or paid.")
                loading?.let { Text(it, fontSize = 12.sp) }
                if (source.isEmpty()) {
                    Muted("No ${provider.label} models loaded yet.")
                    OutlinedButton(onClick = { onLoad(provider) }) { Text("Load ${provider.label} models") }
                } else if (list.isEmpty()) {
                    Muted("No models match." + if (requireAudio && provider == Provider.OPENROUTER) " Few OpenRouter models accept audio; free ones rarely do." else "")
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
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

/* ============================== Setup ============================== */

@Composable
private fun SetupScreen(st: UiState, vm: SessionViewModel) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Mode.LIVE) }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf<Gender?>(null) }
    val start = { vm.startConsult(mode, age.toIntOrNull()?.takeIf { it in 0..120 }, gender) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else vm.showMessage("Microphone permission is needed to record the consultation.")
    }
    BackHandler { vm.go(Screen.HOME) }
    Page("New consultation", onBack = { vm.go(Screen.HOME) }) {
        SectionCard("Mode") {
            ModeOption("Live assist", "Suggestions appear while you talk (questions to ask, red flags, protocol medicines).", mode == Mode.LIVE) { mode = Mode.LIVE }
            ModeOption("Scribe only", "Silent. The note and suggestions are produced when you end the consultation.", mode == Mode.SCRIBE) { mode = Mode.SCRIBE }
        }
        SectionCard("Patient (optional, entered by you)") {
            OutlinedTextField(
                value = age,
                onValueChange = { age = it.filter { ch -> ch.isDigit() }.take(3) },
                label = { Text("Age (years)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Female", gender == Gender.FEMALE) { gender = Gender.FEMALE }
                Choice("Male", gender == Gender.MALE) { gender = Gender.MALE }
                Choice("Not set", gender == null) { gender = null }
            }
            Muted("Age and sex let the safety engine check pregnancy and age rules from the start.")
        }
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("Start recording", fontSize = 18.sp) }
        Muted("Keep this app open on screen while recording. The screen will stay on.")
        st.message?.let { Text(it, color = Palette.emergencyFg) }
    }
}

@Composable
private fun ModeOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { Text("✓ $title", fontWeight = FontWeight.Bold); Text(desc, fontSize = 12.sp) }
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { Text(title, fontWeight = FontWeight.Bold); Text(desc, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

/* ============================== Recording ============================== */

@Composable
private fun RecordingScreen(st: UiState, vm: SessionViewModel) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { vm.showMessage("Use 'End consultation' to stop recording.") }
    val cat = vm.catalogues ?: return
    val mins = st.elapsedSec / 60
    val secs = st.elapsedSec % 60
    val clock = mins.toString().padStart(2, '0') + ":" + secs.toString().padStart(2, '0')

    Page(
        title = if (st.mode == Mode.LIVE) "Live assist" else "Scribe",
        bottomBar = {
            if (st.busy == null) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (st.paused) OutlinedButton(onClick = { vm.resume() }, modifier = Modifier.weight(1f)) { Text("Resume") }
                    else OutlinedButton(onClick = { vm.pause() }, modifier = Modifier.weight(1f)) { Text("Pause") }
                    Button(
                        onClick = { vm.endConsult() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.emergencyFg)
                    ) { Text("End consultation") }
                }
            }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(clock, fontSize = 40.sp, fontFamily = FontFamily.Monospace)
            Column {
                Text(if (st.paused) "⏸ Paused" else if (st.recording) "● Recording" else "Stopped", color = if (st.paused) Palette.urgentFg else Palette.emergencyFg, fontWeight = FontWeight.Bold)
                Muted("Segments processed: ${st.segmentsProcessed}/${st.segmentsRecorded}")
            }
        }
        if (st.recording && !st.paused) {
            LinearProgressIndicator(progress = { st.level }, modifier = Modifier.fillMaxWidth())
            Muted("Microphone level")
        }
        Muted(
            if (st.oneStep) "Pipeline: ${st.speechModel?.id ?: "?"} hears the audio and writes notes (one step)."
            else "Pipeline: ${st.speechModel?.id ?: "?"} transcribes → ${st.analysisModel?.id} writes notes."
        )
        st.busy?.let {
            SectionCard("Please wait") {
                Text(it)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Banner(st.case.sessionAlerts, st.case.sessionLevel)
        st.message?.let { Text(it, color = Palette.urgentFg) }

        if (st.mode == Mode.SCRIBE) {
            Muted("Scribe mode: audio is transcribed in the background; the note and suggestions are produced when you end the consultation.")
        } else {
            if (st.case.problems.isEmpty()) Muted("Listening… suggestions appear after each ${vm.settings.segmentSeconds}-second segment is analysed.")
            st.case.problems.forEach { p -> LiveProblemCard(p, cat, vm) }
            st.case.dictated.forEach { item ->
                SectionCard("Dictated: ${item.stated}", container = Palette.infoBg) {
                    if (item.findings.isEmpty()) Muted("No safety issues found.")
                    item.findings.forEach { FindingLine(it) }
                }
            }
        }
        if (st.errors.isNotEmpty()) {
            SectionCard("Problems", container = Palette.greyBg) { st.errors.takeLast(3).forEach { Muted(it) } }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun LiveProblemCard(p: Problem, cat: Catalogues, vm: SessionViewModel) {
    val def = cat.conditions[p.conditionCode]
    SectionCard(p.label) {
        PhasePill(p.phase)
        p.escalationReasons.forEach { Text("⚠ $it", color = Palette.emergencyFg, fontWeight = FontWeight.SemiBold) }
        p.scoreText?.let { Muted(it) }
        if (p.phase == Phase.HISTORY) {
            val questions = def?.checklist(p.visitType)?.questions?.filter { it.id in p.pendingQuestionIds }?.take(2) ?: emptyList()
            questions.forEach { q ->
                Text("Ask: ${q.promptNe}", fontWeight = FontWeight.SemiBold)
                Muted(q.promptEn)
            }
            val flags = p.unassessedFlagIds.take(2).mapNotNull { cat.redFlags[it] }
            if (flags.isNotEmpty()) Muted("Rule out: " + flags.joinToString("; ") { it.label })
        }
        if (p.phase == Phase.PLAN) {
            val rid = p.suggestedRegimenId
            val reg = rid?.let { cat.regimens[it] }
            if (reg != null) {
                val accepted = rid in vm.ui.value.case.accepted
                Text((if (accepted) "✓ " else "Suggest: ") + reg.label, color = Palette.okFg, fontWeight = FontWeight.SemiBold)
                p.suggestionFindings.filter { it.severity != Severity.CAUTION }.forEach { FindingLine(it) }
                if (!accepted) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.act(DoctorAction.AcceptRegimen(rid)) }) { Text("Accept") }
                    OutlinedButton(onClick = { vm.act(DoctorAction.DismissRegimen(rid)) }) { Text("Dismiss") }
                }
            } else if (p.noSafeRegimen) {
                Text("No protocol medicine passed the safety checks — doctor's decision.", color = Palette.urgentFg)
            } else if (def == null || def.linkedRegimens.isEmpty()) {
                Muted("No protocol medicines for this problem.")
            }
        }
    }
}

/* ============================== Review ============================== */

@Composable
private fun ReviewScreen(st: UiState, vm: SessionViewModel) {
    val cat = vm.catalogues ?: return
    val s = st.case
    val context = LocalContext.current
    var reasonFor by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var showVitals by remember { mutableStateOf(false) }
    var showTranscript by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    BackHandler { vm.go(Screen.HOME) }

    reasonFor?.let { (title, action) ->
        ReasonDialog(title, "Reason (kept in the audit log)", onConfirm = { action(it); reasonFor = null }, onDismiss = { reasonFor = null })
    }
    if (showVitals) VitalsDialog(s.vitals, onSave = { vm.act(DoctorAction.SetVitals(it)); showVitals = false }, onDismiss = { showVitals = false })

    Page("Review", onBack = { vm.go(Screen.HOME) }) {
        st.busy?.let {
            SectionCard("Please wait") { Text(it); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        Banner(s.sessionAlerts, s.sessionLevel)
        if (st.finalFailed) {
            SectionCard("The final note could not be written", container = Palette.urgentBg) {
                st.errors.takeLast(2).forEach { Muted(it) }
                Button(onClick = { vm.retryFinal() }, enabled = st.busy == null) { Text("Try again") }
            }
        }

        // ---------- Patient ----------
        SectionCard("Patient") {
            val d = s.demographics
            Text("Age: ${d.age ?: "not recorded"}   Sex: ${d.gender.name.lowercase()}   Pregnancy: ${d.pregnancy.name.lowercase().replace('_', ' ')}")
            s.conflicts.forEach { cf ->
                Text("Heard a different ${cf.field}: ${cf.proposed} (kept ${cf.existing})", color = Palette.urgentFg)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.act(DoctorAction.SettleConflict(cf.field, cf.existing)) }) { Text("Keep ${cf.existing}") }
                    OutlinedButton(onClick = { vm.act(DoctorAction.SettleConflict(cf.field, cf.proposed)) }) { Text("Use ${cf.proposed}") }
                }
            }
            if (s.pregnancyCheckNeeded) {
                Text("Pregnancy not confirmed — some medicines are held back.", color = Palette.urgentFg)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.act(DoctorAction.ConfirmPregnancy(Pregnancy.NOT_PREGNANT)) }) { Text("Not pregnant") }
                    OutlinedButton(onClick = { vm.act(DoctorAction.ConfirmPregnancy(Pregnancy.PREGNANT)) }) { Text("Pregnant") }
                }
            }
            if (d.gender == Gender.UNKNOWN) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.act(DoctorAction.SetDemographics(null, Gender.FEMALE)) }) { Text("Set female") }
                OutlinedButton(onClick = { vm.act(DoctorAction.SetDemographics(null, Gender.MALE)) }) { Text("Set male") }
            }
            Text("Vitals: " + s.vitals.summary().ifBlank { "none spoken" })
            TextButton(onClick = { showVitals = true }) { Text("Enter / edit vitals") }
            val allergyText = s.allergies.map { cat.allergies[it]?.label ?: it } + s.allergyNotes
            Text("Allergies: " + if (allergyText.isEmpty()) "none recorded" else allergyText.joinToString("; "))
            if (s.comorbidities.isNotEmpty()) Text("Known conditions: " + s.comorbidities.joinToString(", ") { cat.comorbidities[it] ?: it })
            if (s.currentMeds.isNotEmpty()) Text("Already taking: " + s.currentMeds.joinToString(", ") { it.stated + (it.dailyDoseMg?.let { mg -> " (${mg.toInt()} mg/day)" } ?: "") })
            if (s.examFindings.isNotEmpty()) Text("Exam (spoken): " + s.examFindings.joinToString("; "))
        }

        // ---------- Problems ----------
        if (s.problems.isEmpty()) Muted("No problems were identified.")
        s.problems.forEach { p ->
            ReviewProblemCard(p, s.accepted, s.flags, cat, vm, askReason = { title, action -> reasonFor = title to action })
        }

        // ---------- Dictated ----------
        if (s.dictated.isNotEmpty()) SectionCard("Dictated by doctor") {
            s.dictated.forEach { item ->
                val drug = item.drugId?.let { cat.drugs[it] }
                Text(item.stated + (item.frequency?.let { " · $it" } ?: "") + (item.durationDays?.let { " · $it days" } ?: ""), fontWeight = FontWeight.SemiBold)
                Muted(drug?.let { "Matched: ${it.generic} ${it.strengthText()}" } ?: "Not in formulary")
                item.findings.forEach { FindingLine(it) }
                TextButton(onClick = { vm.act(DoctorAction.RemoveDictated(item.id)) }) { Text("Remove") }
                HorizontalDivider()
            }
        }

        // ---------- Note ----------
        st.soap?.let { n ->
            SectionCard("Note (AI draft — check it)") {
                LabelValue("Chief complaint", n.chiefComplaint)
                LabelValue("History", n.history)
                LabelValue("Examination", n.examSpoken)
                LabelValue("Assessment", n.assessment)
                if (n.planSpoken.isNotBlank()) LabelValue("Plan (as spoken)", n.planSpoken)
                if (n.adviceSpoken.isNotBlank()) LabelValue("Advice (as spoken)", n.adviceSpoken)
                if (n.followUp.isNotBlank()) LabelValue("Follow-up", n.followUp)
            }
        }

        // ---------- Slip ----------
        val slip = RxText.build(s, st.soap, cat)
        SectionCard("Prescription slip (preview)") {
            Text(slip, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, slip)
                }
                context.startActivity(Intent.createChooser(send, "Share slip"))
            }) { Text("Share") }
        }

        // ---------- Technical ----------
        TextButton(onClick = { showTranscript = !showTranscript }) { Text(if (showTranscript) "Hide transcript" else "Show transcript (${s.transcript.size} lines)") }
        if (showTranscript) SectionCard("Transcript") {
            s.transcript.forEach { Text("${it.speaker}: ${it.text}", fontSize = 13.sp) }
        }
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide technical log" else "Show technical log") }
        if (showLog) {
            SectionCard("Audit log") { s.audit.takeLast(80).forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } }
            SectionCard("Errors") { if (st.errors.isEmpty()) Muted("none") else st.errors.forEach { Muted(it) } }
            SectionCard("Raw AI responses") { st.aiLog.takeLast(10).forEach { Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace) } }
        }
        Button(onClick = { vm.newConsultation() }, enabled = st.busy == null, modifier = Modifier.fillMaxWidth()) { Text("New consultation") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(value.ifBlank { "—" })
    }
}

@Composable
private fun ReviewProblemCard(
    p: Problem,
    accepted: Set<String>,
    flags: Map<String, np.opd.engine.FlagRecord>,
    cat: Catalogues,
    vm: SessionViewModel,
    askReason: (String, (String) -> Unit) -> Unit
) {
    val def = cat.conditions[p.conditionCode]
    val container = if (p.phase == Phase.ESCALATED) Palette.emergencyBg else Color.Unspecified
    SectionCard(p.label + (def?.icd11?.takeIf { it.isNotBlank() }?.let { "  ($it)" } ?: ""), container = container) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhasePill(p.phase)
            if (def?.visitTypes?.containsKey("FOLLOW_UP") == true) {
                val next = if (p.visitType == "FOLLOW_UP") "INITIAL" else "FOLLOW_UP"
                TextButton(onClick = { vm.act(DoctorAction.SetVisitType(p.problemId, next)) }) {
                    Text(if (p.visitType == "FOLLOW_UP") "Follow-up visit ⇄" else "New visit ⇄", fontSize = 12.sp)
                }
            }
        }
        if (p.symptoms.isNotEmpty()) Text("Symptoms: " + p.symptoms.joinToString("; ") { it.name + (it.duration?.let { d -> " ($d)" } ?: "") })
        if (p.negatives.isNotEmpty()) Text("Denies: " + p.negatives.joinToString("; ") { it.name })

        // Escalation
        p.escalationReasons.forEach { Text("⚠ $it", color = Palette.emergencyFg, fontWeight = FontWeight.SemiBold) }
        p.scoreText?.let { Text(it, color = if (p.scoreIncomplete) Palette.urgentFg else Palette.greyFg, fontSize = 13.sp) }
        if (p.phase == Phase.ESCALATED) {
            OutlinedButton(onClick = {
                askReason("Manage ${p.label} in OPD?") { r -> vm.act(DoctorAction.OverrideEscalation(p.problemId, r)) }
            }) { Text("Manage in OPD anyway…") }
        }
        if (p.escalationOverride != null) Muted("Escalation overridden: ${p.escalationOverride}")

        // Checklist
        if (def != null) {
            val qs = def.checklist(p.visitType)?.questions ?: emptyList()
            if (p.pendingQuestionIds.isNotEmpty()) {
                Text("Not yet asked:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                qs.filter { it.id in p.pendingQuestionIds }.forEach { q ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(q.promptEn, fontSize = 13.sp); Muted(q.promptNe) }
                        TextButton(onClick = { vm.act(DoctorAction.MarkQuestionAsked(p.problemId, q.id)) }) { Text("Asked") }
                    }
                }
            }
            if (def.mandatoryRedFlags.isNotEmpty()) {
                Text("Red flags:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                def.mandatoryRedFlags.mapNotNull { cat.redFlags[it] }.forEach { fd ->
                    val status = flags[fd.id]?.status ?: FlagStatus.UNASSESSED
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fd.label, fontSize = 13.sp)
                            val (txt, col) = when (status) {
                                FlagStatus.UNASSESSED -> "not assessed" to Palette.urgentFg
                                FlagStatus.EXCLUDED -> "absent" to Palette.okFg
                                FlagStatus.SUSPECTED -> "possible" to Palette.urgentFg
                                FlagStatus.CONFIRMED -> "PRESENT" to Palette.emergencyFg
                                FlagStatus.RESOLVED_BY_DOCTOR -> "cleared by doctor" to Palette.okFg
                            }
                            Text(txt, color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        when (status) {
                            FlagStatus.UNASSESSED -> {
                                TextButton(onClick = { vm.act(DoctorAction.ClearFlag(fd.id, "asked: absent")) }) { Text("Absent") }
                                TextButton(onClick = { vm.act(DoctorAction.ConfirmFlag(fd.id)) }) { Text("Present") }
                            }
                            FlagStatus.SUSPECTED, FlagStatus.CONFIRMED -> TextButton(onClick = {
                                askReason("Clear '${fd.label}'?") { r -> vm.act(DoctorAction.ClearFlag(fd.id, r)) }
                            }) { Text("Clear…") }
                            else -> Unit
                        }
                    }
                }
            }
        }

        // Plan
        when (p.phase) {
            Phase.HISTORY -> OutlinedButton(onClick = { vm.act(DoctorAction.UnlockPlan(p.problemId)) }) { Text("Show protocol options now") }
            Phase.PLAN -> {
                val rid = p.suggestedRegimenId
                val reg = rid?.let { cat.regimens[it] }
                if (reg != null) {
                    val isAccepted = rid in accepted
                    Text(
                        (if (isAccepted) "✓ Accepted: " else "Suggested: ") + reg.label,
                        color = Palette.okFg, fontWeight = FontWeight.Bold
                    )
                    Muted("Line: ${reg.lineOfTherapy.lowercase().replace('_', ' ')} · ${reg.verificationStatus}")
                    reg.components.forEach { comp ->
                        val drug = cat.drugs[comp.drugId]
                        if (drug != null) Column(Modifier.padding(start = 8.dp)) {
                            Text(Sig.pharmacyLine(comp, drug), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(Sig.english(comp, drug), fontSize = 13.sp)
                            Text(Sig.nepali(comp, drug), fontSize = 13.sp)
                            val pic = Sig.pictogramText(comp)
                            if (pic.isNotBlank()) Text(pic, fontSize = 15.sp)
                        }
                    }
                    p.suggestionFindings.forEach { FindingLine(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isAccepted) Button(onClick = { vm.act(DoctorAction.AcceptRegimen(rid)) }) { Text("Accept") }
                        OutlinedButton(onClick = { vm.act(DoctorAction.DismissRegimen(rid)) }) { Text(if (isAccepted) "Remove" else "Dismiss") }
                    }
                } else if (p.noSafeRegimen) {
                    Text("No protocol medicine passed the safety checks — doctor's decision.", color = Palette.urgentFg, fontWeight = FontWeight.SemiBold)
                } else {
                    Muted("No protocol medicines for this problem.")
                }
                if (p.skipReasons.isNotEmpty()) {
                    Text("Skipped:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    p.skipReasons.forEach { Muted("• $it") }
                }
            }
            Phase.DICTATION -> Muted("Doctor is dictating — AI suggestions are hidden. Dictated items are checked below.")
            Phase.ESCALATED -> Muted("Protocol OPD medicines are hidden while escalated.")
        }
        val inv = def?.linkedInvestigations?.mapNotNull { cat.investigations[it]?.name } ?: emptyList()
        if (inv.isNotEmpty()) Muted("Investigations to consider: " + inv.joinToString(", "))
        TextButton(onClick = { vm.act(DoctorAction.RemoveProblem(p.problemId)) }) { Text("Remove this problem", color = Palette.greyFg) }
    }
}

@Composable
private fun VitalsDialog(current: Vitals, onSave: (Vitals) -> Unit, onDismiss: () -> Unit) {
    var sys by remember { mutableStateOf(current.bpSystolic?.toString() ?: "") }
    var dia by remember { mutableStateOf(current.bpDiastolic?.toString() ?: "") }
    var pulse by remember { mutableStateOf(current.pulse?.toString() ?: "") }
    var rr by remember { mutableStateOf(current.rr?.toString() ?: "") }
    var spo2 by remember { mutableStateOf(current.spo2?.toString() ?: "") }
    var temp by remember { mutableStateOf(current.tempF?.toString() ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vitals") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("BP sys", sys, Modifier.weight(1f)) { sys = it }
                    NumField("BP dia", dia, Modifier.weight(1f)) { dia = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Pulse", pulse, Modifier.weight(1f)) { pulse = it }
                    NumField("Resp. rate", rr, Modifier.weight(1f)) { rr = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("SpO2 %", spo2, Modifier.weight(1f)) { spo2 = it }
                    NumField("Temp °F", temp, Modifier.weight(1f)) { temp = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Vitals(
                        bpSystolic = sys.toIntOrNull(), bpDiastolic = dia.toIntOrNull(), pulse = pulse.toIntOrNull(),
                        rr = rr.toIntOrNull(), spo2 = spo2.toIntOrNull(), tempF = temp.toDoubleOrNull()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(5)) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}
