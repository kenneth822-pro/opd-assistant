package np.opd.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.opd.assistant.Mode
import np.opd.assistant.SegState
import np.opd.assistant.SessionViewModel
import np.opd.assistant.UiState
import np.opd.engine.Catalogues
import np.opd.engine.DoctorAction
import np.opd.engine.FlagStatus
import np.opd.engine.Gender
import np.opd.engine.Phase
import np.opd.engine.Pregnancy
import np.opd.engine.Problem
import np.opd.engine.QuestionDef

/* ============================== Live consultation ============================== */

@Composable
internal fun RecordingScreen(st: UiState, vm: SessionViewModel) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { vm.showMessage("Use 'End' to stop recording.") }
    val cat = vm.catalogues ?: return
    var showVitals by remember { mutableStateOf(false) }
    var showDemo by remember { mutableStateOf(false) }
    if (showVitals) VitalsDialog(st.case.vitals, onSave = { vm.act(DoctorAction.SetVitals(it)); showVitals = false }, onDismiss = { showVitals = false })
    if (showDemo) DemographicsDialog(st.case.demographics, onSave = { a, g -> vm.act(DoctorAction.SetDemographics(a, g)); showDemo = false }, onDismiss = { showDemo = false })

    Page(
        title = if (st.mode == Mode.LIVE) "Live assist" else "Scribe",
        bottomBar = {
            if (st.busy == null) Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (st.paused) Button(onClick = { vm.resume() }, modifier = Modifier.weight(1f)) { Text("▶ Resume") }
                    else OutlinedButton(onClick = { vm.pause() }, modifier = Modifier.weight(1f)) { Text("⏸ Pause") }
                    if (st.mode == Mode.LIVE) OutlinedButton(onClick = { vm.suggestNow() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("💡 Suggest")
                    }
                    Button(
                        onClick = { vm.endConsult() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.emergencyFg)
                    ) { Text("■ End") }
                }
            }
        }
    ) {
        StatusHeader(st)
        st.busy?.let {
            SectionCard("Please wait") {
                Text(it)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Banner(st.case.sessionAlerts, st.case.sessionLevel)
        st.message?.let { Text(it, color = Palette.urgentFg) }
        PatientStrip(st, cat, vm, onEdit = { showDemo = true }, onVitals = { showVitals = true })

        if (st.mode == Mode.LIVE) {
            if (st.paused) {
                val catching = st.pendingSegments > 0 || st.analysing
                SectionCard("Paused", container = Palette.infoBg) {
                    Text(
                        if (catching) "Catching up with what was said…" else "Up to date. Below: what to ask next and the likely prescription so far.",
                        fontWeight = FontWeight.SemiBold
                    )
                    if (catching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Muted("Resume to continue recording, or End to write the note.")
                }
            }
            AskNextCard(st, cat, vm, onVitals = { showVitals = true })
            st.case.problems.forEach { p -> LiveProblemCard(p, st, cat, vm) }
            st.case.dictated.forEach { item ->
                SectionCard("Dictated: ${item.stated}", container = Palette.infoBg) {
                    if (item.findings.isEmpty()) Muted("No safety issues found.")
                    item.findings.forEach { FindingLine(it) }
                }
            }
        } else {
            Muted("Scribe mode: no live suggestions. The note and protocol suggestions are produced when you end the consultation.")
        }

        TranscriptCard("Live transcript", st.case.transcript, maxLines = 14) {
            val working = st.segments.firstOrNull { it.state == SegState.TRANSCRIBING }
            if (working != null) Text("⏳ writing down part ${working.number}…", color = Palette.greyFg, fontSize = 13.sp, fontStyle = FontStyle.Italic)
            if (st.recording && !st.paused) Text(
                "● listening… ${st.currentSegmentSec} s" + if (st.pendingSegments > 1) " (${st.pendingSegments} parts waiting)" else "",
                color = Palette.emergencyFg, fontSize = 13.sp, fontStyle = FontStyle.Italic
            )
        }
        SegmentIssues(st, vm)
        if (st.errors.isNotEmpty()) {
            SectionCard("Recent problems", container = Palette.greyBg) { st.errors.takeLast(3).forEach { Muted(it) } }
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun StatusHeader(st: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(SessionViewModel.clock(st.elapsedSec), fontSize = 36.sp, fontFamily = FontFamily.Monospace)
        Column(Modifier.weight(1f)) {
            Text(
                if (st.paused) "⏸ Paused" else if (st.recording) "● Recording" else "Stopped",
                color = if (st.paused) Palette.urgentFg else Palette.emergencyFg,
                fontWeight = FontWeight.Bold
            )
            val speech = st.speechModel?.id ?: "?"
            val notes = st.analysisModel?.id
            Muted(if (notes == null) "AI: $speech" else "Speech: $speech · Notes: $notes")
        }
    }
    if (st.recording && !st.paused) LinearProgressIndicator(progress = { st.level }, modifier = Modifier.fillMaxWidth().height(6.dp))
    if (st.mode == Mode.LIVE) {
        if (st.analysing) Text("💡 Updating suggestions…", color = Palette.infoFg, fontSize = 13.sp)
        else st.analysisNote?.let { Text(it, color = if (st.analysisFailed) Palette.urgentFg else Palette.greyFg, fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatientStrip(st: UiState, cat: Catalogues, vm: SessionViewModel, onEdit: () -> Unit, onVitals: () -> Unit) {
    val s = st.case
    val d = s.demographics
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val ageHeard = "age" in st.heard
        ChipButton(
            d.age?.let { "Age $it" + if (ageHeard) " (heard)" else "" } ?: "Age ?",
            if (d.age == null || ageHeard) Palette.urgentBg else Palette.greyBg,
            if (d.age == null || ageHeard) Palette.urgentFg else Palette.greyFg,
            onEdit
        )
        val sexHeard = "gender" in st.heard
        val sex = when (d.gender) { Gender.FEMALE -> "Female"; Gender.MALE -> "Male"; Gender.OTHER -> "Other"; Gender.UNKNOWN -> "Sex ?" }
        ChipButton(
            sex + if (sexHeard) " (heard)" else "",
            if (d.gender == Gender.UNKNOWN || sexHeard) Palette.urgentBg else Palette.greyBg,
            if (d.gender == Gender.UNKNOWN || sexHeard) Palette.urgentFg else Palette.greyFg,
            onEdit
        )
        if (d.pregnancy == Pregnancy.PREGNANT) Pill("Pregnant", Palette.emergencyBg, Palette.emergencyFg)
        ChipButton(s.vitals.summary().ifBlank { "Vitals +" }, Palette.infoBg, Palette.infoFg, onVitals)
        val allergy = s.allergies.map { cat.allergies[it]?.label ?: it }
        if (allergy.isNotEmpty()) Pill("Allergy: " + allergy.joinToString(", "), Palette.emergencyBg, Palette.emergencyFg)
        if (s.currentMeds.isNotEmpty()) Pill("Taking: " + s.currentMeds.joinToString(", ") { it.stated }, Palette.greyBg, Palette.greyFg)
    }
    s.conflicts.forEach { cf ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Heard ${cf.field} ${cf.proposed} (kept ${cf.existing})", color = Palette.urgentFg, fontSize = 13.sp, modifier = Modifier.weight(1f))
            SmallAction("Keep") { vm.act(DoctorAction.SettleConflict(cf.field, cf.existing)) }
            SmallAction("Use ${cf.proposed}") { vm.act(DoctorAction.SettleConflict(cf.field, cf.proposed)) }
        }
    }
}

private data class Basic(val key: String, val ne: String, val en: String)

/** What to ask next: rule-outs first, then clarifications, checklist, AI ideas, basics, and possible conditions. */
@Composable
private fun AskNextCard(st: UiState, cat: Catalogues, vm: SessionViewModel, onVitals: () -> Unit) {
    val s = st.case
    fun status(id: String) = s.flags[id]?.status ?: FlagStatus.UNASSESSED
    val open = setOf(FlagStatus.UNASSESSED, FlagStatus.SUSPECTED)

    val mentioned = st.hints.flagMentions.filter { status(it.id) in open && cat.redFlags[it.id] != null }
    val mentionedIds = mentioned.map { it.id }.toSet()
    val ruleOut = s.problems.flatMap { it.unassessedFlagIds }.distinct()
        .filter { it !in mentionedIds && status(it) == FlagStatus.UNASSESSED }
        .mapNotNull { cat.redFlags[it] }
    val clarify = s.flags.values.filter { it.status == FlagStatus.SUSPECTED && it.flagId !in mentionedIds }.mapNotNull { cat.redFlags[it.flagId] }
    val questions: List<Pair<Problem, QuestionDef>> = s.problems
        .filter { it.phase == Phase.HISTORY || it.phase == Phase.PLAN }
        .flatMap { p ->
            val qs = cat.conditions[p.conditionCode]?.checklist(p.visitType)?.questions ?: emptyList()
            qs.filter { it.id in p.pendingQuestionIds }.take(3).map { p to it }
        }
    val aiQs = s.aiNextQuestions.filter { "ai:" + it.en !in st.doneTips }
    val basics = buildList<Basic> {
        if (s.problems.isNotEmpty() && s.allergies.isEmpty() && s.allergyNotes.isEmpty())
            add(Basic("allergy", "कुनै औषधि खाँदा एलर्जी (चिलाउने, सुन्निने) भएको छ?", "Any drug allergy?"))
        if (s.problems.isNotEmpty() && s.currentMeds.isEmpty())
            add(Basic("meds", "अहिले कुनै औषधि खाइरहनुभएको छ? प्रेसर, सुगरको?", "Taking any medicines now (BP, sugar)?"))
        if (s.demographics.age == null) add(Basic("age", "उमेर कति भयो?", "Age?"))
    }.filter { it.key !in st.doneTips }
    val possible = st.hints.conditions
        .filter { h -> s.problems.none { it.conditionCode == h.code } && "cond:" + h.code !in st.doneTips && cat.conditions[h.code] != null }
        .take(3)

    val nothing = mentioned.isEmpty() && ruleOut.isEmpty() && clarify.isEmpty() && questions.isEmpty() && aiQs.isEmpty() &&
        basics.isEmpty() && possible.isEmpty() && !s.pregnancyCheckNeeded
    SectionCard("Ask next") {
        if (nothing) {
            if (s.transcript.isEmpty()) {
                Text("के समस्या लिएर आउनुभयो? कहिलेदेखि?", fontWeight = FontWeight.SemiBold)
                Muted("Start with the main complaint and since when. Questions and rule-outs appear as the conversation is understood.")
            } else {
                Muted("Nothing pending from the protocol checklist. Tap 💡 Suggest for AI ideas.")
            }
        }
        if (mentioned.isNotEmpty()) {
            SubHeading("⚠ Patient may have mentioned", Palette.emergencyFg)
            mentioned.forEach { m ->
                val f = cat.redFlags[m.id] ?: return@forEach
                TipRow(f.label, "“${m.line.take(90)}”", Palette.emergencyFg) {
                    SmallAction("Present") { vm.act(DoctorAction.ConfirmFlag(f.id)) }
                    SmallAction("Absent") { vm.act(DoctorAction.ClearFlag(f.id, "doctor: not present on asking")) }
                }
            }
        }
        if (s.pregnancyCheckNeeded) {
            SubHeading("Pregnancy", Palette.urgentFg)
            TipRow("महिनावारी कहिले भएको थियो? गर्भवती हुनुहुन्छ कि?", "Some medicines are held back until pregnancy is ruled out.", Palette.urgentFg) {
                SmallAction("Not pregnant") { vm.act(DoctorAction.ConfirmPregnancy(Pregnancy.NOT_PREGNANT)) }
                SmallAction("Pregnant") { vm.act(DoctorAction.ConfirmPregnancy(Pregnancy.PREGNANT)) }
            }
        }
        if (ruleOut.isNotEmpty()) {
            SubHeading("Rule out (red flags)", Palette.urgentFg)
            ruleOut.take(6).forEach { f ->
                if (f.derivationRule != null) {
                    TipRow("Measure: ${f.label}", f.labelNe) { SmallAction("Vitals", onVitals) }
                } else {
                    TipRow(f.labelNe.ifBlank { f.label }, f.label) {
                        SmallAction("Absent") { vm.act(DoctorAction.ClearFlag(f.id, "asked: absent")) }
                        SmallAction("Present") { vm.act(DoctorAction.ConfirmFlag(f.id)) }
                    }
                }
            }
            if (ruleOut.size > 6) Muted("+ ${ruleOut.size - 6} more on the review screen")
        }
        if (clarify.isNotEmpty()) {
            SubHeading("Clarify", Palette.urgentFg)
            clarify.forEach { f ->
                TipRow("Possible: ${f.label}", f.labelNe) {
                    SmallAction("Present") { vm.act(DoctorAction.ConfirmFlag(f.id)) }
                    SmallAction("Not present") { vm.act(DoctorAction.ClearFlag(f.id, "doctor: not present on asking")) }
                }
            }
        }
        if (questions.isNotEmpty()) {
            SubHeading("Protocol questions", Palette.infoFg)
            questions.forEach { (p, q) ->
                TipRow(q.promptNe.ifBlank { q.promptEn }, q.promptEn + if (s.problems.size > 1) " · ${p.label}" else "") {
                    SmallAction("Asked") { vm.act(DoctorAction.MarkQuestionAsked(p.problemId, q.id)) }
                }
            }
        }
        if (aiQs.isNotEmpty()) {
            SubHeading("AI suggests asking", Palette.infoFg)
            aiQs.forEach { q ->
                TipRow(q.ne.ifBlank { q.en }, listOf(q.en.takeIf { q.ne.isNotBlank() }, q.reason.takeIf { it.isNotBlank() }?.let { "why: $it" }).filterNotNull().joinToString(" · ")) {
                    SmallAction("Done") { vm.markTipDone("ai:" + q.en) }
                }
            }
        }
        if (basics.isNotEmpty()) {
            SubHeading("Don't forget", Palette.greyFg)
            basics.forEach { b -> TipRow(b.ne, b.en) { SmallAction("Done") { vm.markTipDone(b.key) } } }
        }
        if (possible.isNotEmpty()) {
            SubHeading("Sounds like (from keywords)", Palette.greyFg)
            possible.forEach { h ->
                val def = cat.conditions[h.code] ?: return@forEach
                TipRow(def.displayName, "heard: " + h.matched.take(3).joinToString(", ")) {
                    SmallAction("Add") { vm.act(DoctorAction.AddProblem(h.code)) }
                    SmallAction("No") { vm.markTipDone("cond:" + h.code) }
                }
            }
            Muted("Adding a problem shows its protocol questions, red flags and likely prescription straight away.")
        }
    }
}

@Composable
private fun LiveProblemCard(p: Problem, st: UiState, cat: Catalogues, vm: SessionViewModel) {
    val def = cat.conditions[p.conditionCode]
    SectionCard(p.label, container = if (p.phase == Phase.ESCALATED) Palette.emergencyBg else Color.Unspecified) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhasePill(p.phase)
            if (p.visitType == "FOLLOW_UP") Pill("Follow-up", Palette.greyBg, Palette.greyFg)
        }
        if (p.symptoms.isNotEmpty()) Text(p.symptoms.joinToString("; ") { it.name + (it.duration?.let { d -> " ($d)" } ?: "") }, fontSize = 13.sp)
        if (p.negatives.isNotEmpty()) Muted("Denies: " + p.negatives.joinToString("; ") { it.name })
        p.escalationReasons.forEach { Text("⚠ $it", color = Palette.emergencyFg, fontWeight = FontWeight.SemiBold) }
        p.scoreText?.let { Muted(it) }
        HorizontalDivider()
        RxBlock(p, st.case.accepted, cat, vm, detailed = false)
        val inv = def?.linkedInvestigations?.mapNotNull { cat.investigations[it]?.name } ?: emptyList()
        if (inv.isNotEmpty()) Muted("Investigations to consider: " + inv.joinToString(", "))
    }
}

@Composable
private fun SegmentIssues(st: UiState, vm: SessionViewModel) {
    if (st.failedSegments > 0) {
        SectionCard("${st.failedSegments} part(s) of the audio not written down", container = Palette.urgentBg) {
            st.segments.filter { it.state == SegState.FAILED }.takeLast(3).forEach {
                Muted("Part ${it.number} (at ${SessionViewModel.clock((it.startMs / 1000).toInt())}): ${it.error?.take(200)}")
            }
            Button(onClick = { vm.retryFailed() }) { Text("Retry now") }
            Muted("They are also retried automatically when you end the consultation. If a model is busy, choose a backup model in Settings.")
        }
    }
    if (st.silentSegments > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("${st.silentSegments} quiet part(s) skipped to save requests.")
            TextButton(onClick = { vm.retryFailed(includeSilent = true) }) { Text("Transcribe anyway", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SubHeading(text: String, color: Color) {
    Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun TipRow(title: String, sub: String?, color: Color = Color.Unspecified, actions: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = color, fontSize = 15.sp)
            if (!sub.isNullOrBlank()) Muted(sub)
        }
        actions()
    }
}

@Composable
internal fun SmallAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Text(label, fontSize = 13.sp) }
}
