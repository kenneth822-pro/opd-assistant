package np.opd.assistant.ui

import android.Manifest
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import np.opd.engine.Demographics
import np.opd.engine.DoctorAction
import np.opd.engine.FlagRecord
import np.opd.engine.FlagStatus
import np.opd.engine.Gender
import np.opd.engine.Phase
import np.opd.engine.Pregnancy
import np.opd.engine.Problem
import np.opd.engine.RegimenDef
import np.opd.engine.Severity
import np.opd.engine.Sig
import np.opd.engine.TranscriptLine
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
            Text("• Audio and transcripts are sent to the AI provider you choose (Google Gemini, OpenRouter, OpenCode, Groq or your own server) using your own API key.")
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
        Muted("Listens to a consultation, shows what to ask next and what to rule out, suggests protocol medicines after safety checks, and writes the note.")
        if (st.speechModel == null) {
            SectionCard("Step 1: add a key and choose a model", container = Palette.urgentBg) {
                Text("Open Settings, paste a Gemini key (free from aistudio.google.com/apikey) and the app will load the current models and pick sensible defaults.")
                Button(onClick = { vm.go(Screen.SETTINGS) }) { Text("Open Settings") }
            }
        } else {
            SectionCard("AI models") {
                Text("Speech: ${st.speechModel.provider.label} · ${st.speechModel.id}", fontSize = 13.sp)
                Text("Notes: " + (st.analysisModel?.let { "${it.provider.label} · ${it.id}" } ?: "same as speech"), fontSize = 13.sp)
                Text("Backup: " + (st.backupModel?.let { "${it.provider.label} · ${it.id}" } ?: "none (recommended: add one)"), fontSize = 13.sp)
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
            Muted("Built-in clinical catalogue v${c.version}: ${c.conditions.size} common conditions with question checklists, red flags and keywords, ${c.regimens.size} regimens, ${c.drugs.size} drugs, ${c.redFlags.size} red flags. Works on the phone without the AI. All content is UNVERIFIED prototype data.")
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
            ModeOption("Live assist", "Live transcript, what to ask next, red flags to rule out and the likely prescription while you talk.", mode == Mode.LIVE) { mode = Mode.LIVE }
            ModeOption("Scribe only", "Live transcript only. The note and suggestions are produced when you end the consultation (fewer requests).", mode == Mode.SCRIBE) { mode = Mode.SCRIBE }
        }
        SectionCard("Patient (optional)") {
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
            Muted("If you leave these blank, the app fills them from the conversation (e.g. \"४५ वर्ष\", \"दिदी\", \"बुबा\") and marks them as heard so you can check them.")
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
internal fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

/* ============================== Review ============================== */

@Composable
private fun ReviewScreen(st: UiState, vm: SessionViewModel) {
    val cat = vm.catalogues ?: return
    val s = st.case
    val context = LocalContext.current
    var reasonFor by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var showVitals by remember { mutableStateOf(false) }
    var showDemo by remember { mutableStateOf(false) }
    var showTranscript by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    BackHandler { vm.go(Screen.HOME) }

    reasonFor?.let { (title, action) ->
        ReasonDialog(title, "Reason (kept in the audit log)", onConfirm = { action(it); reasonFor = null }, onDismiss = { reasonFor = null })
    }
    if (showVitals) VitalsDialog(s.vitals, onSave = { vm.act(DoctorAction.SetVitals(it)); showVitals = false }, onDismiss = { showVitals = false })
    if (showDemo) DemographicsDialog(s.demographics, onSave = { a, g -> vm.act(DoctorAction.SetDemographics(a, g)); showDemo = false }, onDismiss = { showDemo = false })

    Page("Review", onBack = { vm.go(Screen.HOME) }) {
        st.busy?.let {
            SectionCard("Please wait") { Text(it); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        Banner(s.sessionAlerts, s.sessionLevel)
        if (st.finalFailed) {
            SectionCard("The AI note could not be written", container = Palette.urgentBg) {
                st.errors.takeLast(2).forEach { Muted(it) }
                Muted("A note built on the phone from what was captured is shown below instead.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.retryFinal() }, enabled = st.busy == null) { Text("Try AI note again") }
                }
            }
        }
        if (st.failedSegments > 0 || st.silentSegments > 0) {
            SectionCard("Audio not transcribed", container = Palette.greyBg) {
                if (st.failedSegments > 0) {
                    Text("${st.failedSegments} part(s) failed.")
                    st.segments.filter { it.state == np.opd.assistant.SegState.FAILED }.takeLast(2).forEach { Muted("Part ${it.number}: ${it.error?.take(160)}") }
                    OutlinedButton(onClick = { vm.retryFailed() }, enabled = st.busy == null) { Text("Retry them and rewrite the note") }
                }
                if (st.silentSegments > 0) {
                    Muted("${st.silentSegments} quiet part(s) were skipped.")
                    TextButton(onClick = { vm.retryFailed(includeSilent = true) }, enabled = st.busy == null) { Text("Transcribe quiet parts and rewrite") }
                }
            }
        }

        // ---------- Patient ----------
        SectionCard("Patient") {
            val d = s.demographics
            val heardNote = if (st.heard.isNotEmpty()) "  (${st.heard.joinToString(" & ")} heard in the conversation — check)" else ""
            Text("Age: ${d.age ?: "not recorded"}   Sex: ${d.gender.name.lowercase()}   Pregnancy: ${d.pregnancy.name.lowercase().replace('_', ' ')}$heardNote")
            TextButton(onClick = { showDemo = true }) { Text("Edit age / sex") }
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
            Text("Vitals: " + s.vitals.summary().ifBlank { "none spoken" })
            TextButton(onClick = { showVitals = true }) { Text("Enter / edit vitals") }
            val allergyText = s.allergies.map { cat.allergies[it]?.label ?: it } + s.allergyNotes
            Text("Allergies: " + if (allergyText.isEmpty()) "none recorded" else allergyText.joinToString("; "))
            if (s.comorbidities.isNotEmpty()) Text("Known conditions: " + s.comorbidities.joinToString(", ") { cat.comorbidities[it] ?: it })
            if (s.currentMeds.isNotEmpty()) Text("Already taking: " + s.currentMeds.joinToString(", ") { it.stated + (it.dailyDoseMg?.let { mg -> " (${mg.toInt()} mg/day)" } ?: "") })
            if (s.examFindings.isNotEmpty()) Text("Exam (spoken): " + s.examFindings.joinToString("; "))
        }

        // ---------- Problems ----------
        if (s.problems.isEmpty()) {
            Muted("No problems were identified.")
            val possible = st.hints.conditions.filter { cat.conditions[it.code] != null }.take(3)
            if (possible.isNotEmpty()) SectionCard("Possible from keywords") {
                possible.forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cat.conditions[h.code]?.displayName ?: h.code, fontWeight = FontWeight.SemiBold)
                            Muted("heard: " + h.matched.take(3).joinToString(", "))
                        }
                        SmallAction("Add") { vm.act(DoctorAction.AddProblem(h.code)) }
                    }
                }
            }
        }
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
            SectionCard(
                if (n.localDraft) "Note (built on the phone — AI note unavailable)" else "Note (AI draft — check it)",
                container = if (n.localDraft) Palette.greyBg else Color.Unspecified
            ) {
                LabelValue("Chief complaint", n.chiefComplaint)
                LabelValue("History", n.history)
                LabelValue("Examination", n.examSpoken)
                LabelValue("Assessment", n.assessment)
                if (n.planSpoken.isNotBlank()) LabelValue(if (n.localDraft) "Plan" else "Plan (as spoken)", n.planSpoken)
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
        if (showTranscript) TranscriptCard("Transcript", s.transcript, maxLines = null)
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide technical log" else "Show technical log") }
        if (showLog) {
            SectionCard("Audio parts") {
                st.segments.forEach {
                    Muted("#${it.number} ${SessionViewModel.clock((it.startMs / 1000).toInt())} ${it.durationMs / 1000}s ${it.state.name.lowercase()} ${it.model ?: ""} ${it.lines} lines ${it.error?.take(120) ?: ""}")
                }
            }
            SectionCard("Audit log") { s.audit.takeLast(80).forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } }
            SectionCard("Errors") { if (st.errors.isEmpty()) Muted("none") else st.errors.forEach { Muted(it) } }
            SectionCard("Raw AI responses") { st.aiLog.takeLast(12).forEach { Text(it.take(3000), fontSize = 10.sp, fontFamily = FontFamily.Monospace) } }
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
    flags: Map<String, FlagRecord>,
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

        HorizontalDivider()
        RxBlock(p, accepted, cat, vm, detailed = true)
        val inv = def?.linkedInvestigations?.mapNotNull { cat.investigations[it]?.name } ?: emptyList()
        if (inv.isNotEmpty()) Muted("Investigations to consider: " + inv.joinToString(", "))
        TextButton(onClick = { vm.act(DoctorAction.RemoveProblem(p.problemId)) }) { Text("Remove this problem", color = Palette.greyFg) }
    }
}

/* ============================== Shared pieces ============================== */

/** The prescription part of a problem: suggestion in PLAN, "likely prescription" + what is missing in HISTORY. */
@Composable
internal fun RxBlock(p: Problem, accepted: Set<String>, cat: Catalogues, vm: SessionViewModel, detailed: Boolean) {
    val def = cat.conditions[p.conditionCode]
    val hasProtocol = def != null && def.linkedRegimens.isNotEmpty()
    when (p.phase) {
        Phase.PLAN -> {
            val rid = p.suggestedRegimenId
            val reg = rid?.let { cat.regimens[it] }
            if (reg != null) {
                val isAccepted = rid in accepted
                Text((if (isAccepted) "✓ Accepted: " else "Suggested prescription: ") + reg.label, color = Palette.okFg, fontWeight = FontWeight.Bold)
                if (detailed) Muted("Line: ${reg.lineOfTherapy.lowercase().replace('_', ' ')} · ${reg.verificationStatus}")
                RegimenLines(reg, cat, detailed, dim = false)
                p.suggestionFindings.filter { detailed || it.severity != Severity.CAUTION }.forEach { FindingLine(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isAccepted) Button(onClick = { vm.act(DoctorAction.AcceptRegimen(rid)) }) { Text("Accept") }
                    OutlinedButton(onClick = { vm.act(DoctorAction.DismissRegimen(rid)) }) { Text(if (isAccepted) "Remove" else "Dismiss") }
                }
            } else if (p.noSafeRegimen) {
                Text("No protocol medicine passed the safety checks — doctor's decision.", color = Palette.urgentFg, fontWeight = FontWeight.SemiBold)
            } else if (!hasProtocol) {
                Muted("No protocol medicines for this problem.")
            }
            if (detailed && p.skipReasons.isNotEmpty()) {
                Text("Skipped:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                p.skipReasons.forEach { Muted("• $it") }
            }
        }
        Phase.HISTORY -> {
            val reg = p.provisionalRegimenId?.let { cat.regimens[it] }
            if (reg != null) {
                Text("Likely prescription: ${reg.label}", color = Palette.greyFg, fontWeight = FontWeight.SemiBold)
                RegimenLines(reg, cat, detailed, dim = true)
                p.provisionalFindings.filter { it.severity != Severity.CAUTION }.forEach { FindingLine(it) }
            } else if (hasProtocol) {
                Muted("No protocol medicine fits the details so far.")
            } else {
                Muted("No protocol medicines for this problem.")
            }
            if (p.provisionalBlockers.isNotEmpty()) {
                Text("Before prescribing:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                p.provisionalBlockers.take(if (detailed) 12 else 4).forEach { Muted("• $it") }
            }
            if (hasProtocol) OutlinedButton(onClick = { vm.act(DoctorAction.UnlockPlan(p.problemId)) }) { Text("Show prescription now") }
        }
        Phase.DICTATION -> Muted("Doctor is dictating — protocol suggestions are hidden. Dictated items are checked separately.")
        Phase.ESCALATED -> Muted("Protocol OPD medicines are hidden while escalated.")
    }
}

@Composable
private fun RegimenLines(reg: RegimenDef, cat: Catalogues, detailed: Boolean, dim: Boolean) {
    reg.components.forEach { comp ->
        val drug = cat.drugs[comp.drugId] ?: return@forEach
        Column(Modifier.padding(start = 8.dp)) {
            Text(Sig.pharmacyLine(comp, drug), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (dim) Palette.greyFg else Color.Unspecified)
            if (detailed) {
                Text(Sig.english(comp, drug), fontSize = 13.sp)
                Text(Sig.nepali(comp, drug), fontSize = 13.sp)
                val pic = Sig.pictogramText(comp)
                if (pic.isNotBlank()) Text(pic, fontSize = 15.sp)
            }
        }
    }
}

private fun speakerStyle(speaker: String): Pair<String, Color> = when (speaker.lowercase()) {
    "doctor" -> "Doctor" to Palette.doctor
    "patient" -> "Patient" to Palette.patient
    "attendant" -> "Attendant" to Palette.attendant
    else -> "Speaker" to Palette.unknownSpeaker
}

@Composable
internal fun TranscriptCard(title: String, lines: List<TranscriptLine>, maxLines: Int?, footer: @Composable () -> Unit = {}) {
    SectionCard(title) {
        val shown = if (maxLines != null) lines.takeLast(maxLines) else lines
        if (lines.isEmpty()) Muted("Nothing written down yet. Text appears a few seconds after each pause in speech.")
        if (maxLines != null && lines.size > maxLines) Muted("… ${lines.size - maxLines} earlier lines (full transcript on the review screen)")
        shown.forEach { l ->
            val (label, color) = speakerStyle(l.speaker)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(68.dp).padding(top = 2.dp))
                Text(l.text, fontSize = 14.sp, color = if (l.speaker == "doctor") Palette.doctor else Color.Unspecified, modifier = Modifier.weight(1f))
            }
        }
        footer()
    }
}

@Composable
internal fun DemographicsDialog(current: Demographics, onSave: (Int?, Gender?) -> Unit, onDismiss: () -> Unit) {
    var age by remember { mutableStateOf(current.age?.toString() ?: "") }
    var gender by remember { mutableStateOf(current.gender.takeIf { it != Gender.UNKNOWN }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Patient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Age (years)", age, Modifier.fillMaxWidth(), allowDecimal = false, maxLen = 3) { age = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Female", gender == Gender.FEMALE) { gender = Gender.FEMALE }
                    Choice("Male", gender == Gender.MALE) { gender = Gender.MALE }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(age.trim().toIntOrNull()?.takeIf { it in 0..120 }, gender) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun VitalsDialog(current: Vitals, onSave: (Vitals) -> Unit, onDismiss: () -> Unit) {
    var sys by remember { mutableStateOf(current.bpSystolic?.toString() ?: "") }
    var dia by remember { mutableStateOf(current.bpDiastolic?.toString() ?: "") }
    var pulse by remember { mutableStateOf(current.pulse?.toString() ?: "") }
    var rr by remember { mutableStateOf(current.rr?.toString() ?: "") }
    var spo2 by remember { mutableStateOf(current.spo2?.toString() ?: "") }
    var temp by remember { mutableStateOf(current.tempF?.toString() ?: "") }
    AlertDialog(
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
                    NumField("Temp °F", temp, Modifier.weight(1f), allowDecimal = true) { temp = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Vitals(
                        bpSystolic = sys.trim().toIntOrNull(),
                        bpDiastolic = dia.trim().toIntOrNull(),
                        pulse = pulse.trim().toIntOrNull(),
                        rr = rr.trim().toIntOrNull(),
                        spo2 = spo2.trim().toIntOrNull(),
                        tempF = temp.trim().toDoubleOrNull(),
                        bloodGlucose = current.bloodGlucose
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NumField(
    label: String,
    value: String,
    modifier: Modifier,
    allowDecimal: Boolean = false,
    maxLen: Int = 5,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            val filtered = if (allowDecimal) {
                var dotSeen = false
                v.filter { ch ->
                    if (ch.isDigit()) true
                    else if (ch == '.' && !dotSeen) { dotSeen = true; true }
                    else false
                }
            } else {
                v.filter { it.isDigit() }
            }
            onChange(filtered.take(maxLen))
        },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
    )
}
