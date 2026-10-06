package np.opd.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.opd.engine.AiDiagnosis
import np.opd.engine.AiLikelihood
import np.opd.engine.Catalogues
import np.opd.engine.DangerEntry
import np.opd.engine.DiagnosisEntry
import np.opd.engine.DiagnosisView
import np.opd.engine.Likelihood
import np.opd.engine.OpdSheet
import np.opd.engine.Trend
import np.opd.engine.Verification

/*
 * Provisional diagnosis + live OPD sheet.
 *
 * Deliberately free of Android classes: it takes plain data and callbacks, so it also runs on the JVM
 * where it is compiled and rendered in checks. Everything shown is built by the engine
 * (Differential, OpdSheet); this file only draws it.
 */

private fun likelihoodText(l: Likelihood) = when (l) {
    Likelihood.LIKELY -> "Likely"
    Likelihood.POSSIBLE -> "Possible"
    Likelihood.UNLIKELY -> "Less likely"
}

@Composable
fun LikelihoodPill(l: Likelihood) = when (l) {
    Likelihood.LIKELY -> Pill("Likely", Palette.doctor, Color.White)
    Likelihood.POSSIBLE -> Pill("Possible", Palette.greyBg, Palette.greyFg)
    Likelihood.UNLIKELY -> Pill("Less likely", Palette.greyBg, Palette.greyFg)
}

@Composable
fun TrendMark(t: Trend) {
    when (t) {
        Trend.NEW -> Pill("NEW", Palette.urgentBg, Palette.urgentFg)
        Trend.UP -> Pill("▲ rose", Palette.okBg, Palette.okFg)
        Trend.DOWN -> Pill("▼ fell", Palette.greyBg, Palette.greyFg)
        Trend.STEADY -> Unit
    }
}

/* ============================== The pinned strip ============================== */

/** Always-visible summary of the working diagnosis. Tap anywhere on it to open the full layer. */
@Composable
fun DiagnosisStrip(view: DiagnosisView, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val r = view.result
    val top = r.ranked.firstOrNull()
    val others = r.ranked.drop(1).take(2)
    val danger = r.danger.firstOrNull()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.infoBg)
            .clickable(onClickLabel = "Open provisional diagnosis and OPD sheet", role = Role.Button, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🩺 Provisional diagnosis", color = Palette.infoFg, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (view.hasUnseenChange) Pill("● updated", Palette.urgentBg, Palette.urgentFg)
            Text("Open ›", color = Palette.infoFg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        when {
            top != null -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(top.label, color = Palette.infoFg, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    LikelihoodPill(top.likelihood)
                    TrendMark(view.trend(top.code))
                }
                if (others.isNotEmpty()) {
                    Text(
                        "Also: " + others.joinToString(" · ") { "${it.label} (${likelihoodText(it.likelihood).lowercase()})" },
                        color = Palette.infoFg, fontSize = 13.sp
                    )
                }
            }
            r.ai.isNotEmpty() -> Text(
                "AI suggests: " + r.ai.take(2).joinToString(" · ") { it.name } + "  (unverified)",
                color = Palette.infoFg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            )
            r.outsideCatalogue.isNotEmpty() -> Text(
                "Other complaint: " + r.outsideCatalogue.joinToString(", ") + " (no protocol in this app)",
                color = Palette.infoFg, fontSize = 14.sp
            )
            else -> Text(
                "Listening for symptoms… the likely diagnosis appears here and updates as you talk.",
                color = Palette.infoFg, fontSize = 13.sp
            )
        }
        if (danger != null) {
            Text(
                "⚠ Don't miss: ${danger.implies} (${if (danger.confirmed) "present" else "possible"})" +
                    if (r.danger.size > 1) "  +${r.danger.size - 1} more" else "",
                color = Palette.emergencyFg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(Palette.emergencyBg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}

/* ============================== The layer (phones) ============================== */

/**
 * A panel that slides up over the screen, with a dimmed background; tap the background or "Close" to dismiss.
 * Plain Compose rather than a platform sheet, so it behaves the same everywhere.
 */
@Composable
fun BottomLayer(visible: Boolean, onDismiss: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(40.dp).height(4.dp).background(Palette.greyBg, RoundedCornerShape(2.dp)))
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Close ✕") }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content
                    )
                }
            }
        }
    }
}

/* ============================== The panel (tabs) ============================== */

/**
 * Diagnosis tab + OPD sheet tab. Both are views of the live case, so they stay current while open.
 * [onAdd] adds a diagnosis to the problem list (the doctor's decision); [onShare] shares the sheet as text.
 */
@Composable
fun DiagnosisPanel(
    view: DiagnosisView,
    sheet: OpdSheet,
    verification: Catalogues.VerificationSummary,
    onAdd: (String) -> Unit,
    onShare: () -> Unit,
    initialTab: Int = 0
) {
    var tab by remember { mutableStateOf(initialTab) }
    TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Diagnosis") })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("OPD sheet") })
    }
    if (tab == 0) DiagnosisTab(view, verification, onAdd) else SheetTab(sheet, onShare)
}

@Composable
private fun Heading(text: String, color: Color = Palette.greyFg) {
    Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun DiagnosisTab(view: DiagnosisView, verification: Catalogues.VerificationSummary, onAdd: (String) -> Unit) {
    val r = view.result
    Muted("Built from what has been said and measured, and updated as the conversation continues. It is decision support, not a final diagnosis: you decide.")
    if (r.isEmpty) Text("Nothing to suggest yet. As soon as a symptom is heard, the likely diagnoses appear here.", fontWeight = FontWeight.SemiBold)

    if (r.danger.isNotEmpty()) {
        Heading("⚠ Don't miss", Palette.emergencyFg)
        r.danger.forEach { DangerCard(it, isNew = view.isNewDanger(it.flagId)) }
    }
    if (r.ranked.isNotEmpty()) {
        Heading("Most likely first")
        r.ranked.forEach { DiagnosisCard(it, view.trend(it.code), onAdd) }
    }
    if (r.ruleOut.isNotEmpty()) {
        Heading("Rule out next", Palette.urgentFg)
        r.ruleOut.forEach { f ->
            Column {
                Text(f.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (f.labelNe.isNotBlank()) Muted(f.labelNe)
            }
        }
    }
    if (r.outsideCatalogue.isNotEmpty()) {
        Heading("Also on the list (no protocol in this app)")
        r.outsideCatalogue.forEach { Text("• $it", fontSize = 14.sp) }
    }
    if (r.ai.isNotEmpty()) {
        Heading("AI also suggests (unverified)", Palette.infoFg)
        r.ai.forEach { AiRow(it) }
    }
    if (view.changes.isNotEmpty()) {
        Heading("Recent changes")
        view.changes.forEach { Text("• ${clock(it.atSec)}  ${it.text}", fontSize = 13.sp) }
    }
    HorizontalDivider()
    Muted(
        "Protocol content (catalogue): ${verification.conditions.sourceMatched + verification.conditions.clinicianVerified} of " +
            "${verification.conditions.total} conditions checked against a guideline source. Unverified prototype data."
    )
}

private fun clock(sec: Int) = (sec / 60).toString().padStart(2, '0') + ":" + (sec % 60).toString().padStart(2, '0')

@Composable
private fun DangerCard(d: DangerEntry, isNew: Boolean) {
    Column(
        Modifier.fillMaxWidth().background(Palette.emergencyBg, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.implies, color = Palette.emergencyFg, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (isNew) Pill("NEW", Palette.urgentBg, Palette.urgentFg)
            Pill(if (d.confirmed) "PRESENT" else "POSSIBLE", Palette.emergencyFg, Color.White)
        }
        Text(d.label, color = Palette.emergencyFg, fontSize = 13.sp)
        d.evidence?.takeIf { it.isNotBlank() }?.let { Text("“${it.take(90)}”", color = Palette.emergencyFg, fontSize = 12.sp) }
        Text("→ ${d.action}", color = Palette.emergencyFg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DiagnosisCard(e: DiagnosisEntry, trend: Trend, onAdd: (String) -> Unit) {
    SectionCard("", container = if (e.likelihood == Likelihood.LIKELY) Palette.infoBg else Color.Unspecified) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(e.label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (e.icd11.isNotBlank()) Muted("ICD-11 ${e.icd11}")
            }
            LikelihoodPill(e.likelihood)
            TrendMark(trend)
        }
        val support = e.supporting.filter { it.points > 0 || it.text.startsWith("Symptoms") }
        if (support.isNotEmpty()) {
            Text("For", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Palette.okFg)
            support.forEach { Text("• ${it.text}", fontSize = 13.sp) }
        }
        if (e.against.isNotEmpty()) {
            Text("Against", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Palette.urgentFg)
            e.against.forEach { Text("• ${it.text}", fontSize = 13.sp) }
        }
        if (e.toClarify.isNotEmpty()) {
            Text("To confirm or exclude", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Palette.infoFg)
            e.toClarify.forEach { q ->
                Column {
                    Text("• ${q.en}", fontSize = 13.sp)
                    if (q.ne.isNotBlank()) Muted("   ${q.ne}")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.onProblemList) Pill("✓ On problem list", Palette.okBg, Palette.okFg)
            else OutlinedButton(onClick = { onAdd(e.code) }) { Text("Add as problem") }
            if (e.aiAgrees) Pill("AI agrees", Palette.infoBg, Palette.infoFg)
            if (e.verification == Verification.UNVERIFIED) Pill("unverified protocol", Palette.greyBg, Palette.greyFg)
        }
    }
}

@Composable
private fun AiRow(a: AiDiagnosis) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(a.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
            when (a.likelihood) {
                AiLikelihood.LIKELY -> Pill("likely", Palette.greyBg, Palette.greyFg)
                AiLikelihood.POSSIBLE -> Pill("possible", Palette.greyBg, Palette.greyFg)
                AiLikelihood.MUST_NOT_MISS -> Pill("do not miss", Palette.emergencyBg, Palette.emergencyFg)
            }
        }
        if (a.reason.isNotBlank()) Muted(a.reason)
    }
}

/* ============================== The OPD sheet tab ============================== */

@Composable
private fun SheetTab(sh: OpdSheet, onShare: () -> Unit) {
    Muted("Live OPD sheet: everything captured so far. A provisional draft that updates as the consultation goes on.")
    Button(onClick = onShare) { Text("Share sheet as text") }

    SheetBlock("Patient") {
        Text(sh.patient, fontWeight = FontWeight.SemiBold)
        if (sh.vitals.isNotBlank()) Text("Vitals: ${sh.vitals}")
        Text("Allergies: " + sh.allergies.ifEmpty { listOf("none recorded") }.joinToString("; "))
        if (sh.knownConditions.isNotEmpty()) Text("Known conditions: " + sh.knownConditions.joinToString(", "))
        if (sh.currentMeds.isNotEmpty()) Text("Already taking: " + sh.currentMeds.joinToString(", "))
    }
    if (sh.alerts.isNotEmpty()) SheetBlock("Alerts", Palette.urgentBg) { sh.alerts.forEach { Text("• $it", color = Palette.urgentFg) } }
    SheetBlock("Complaints and history") {
        if (sh.complaints.isEmpty()) Muted("Nothing captured yet.")
        sh.complaints.forEach { cpl ->
            Text(cpl.title, fontWeight = FontWeight.SemiBold)
            if (cpl.symptoms.isNotEmpty()) Text(cpl.symptoms.joinToString("; "), fontSize = 13.sp)
            if (cpl.denies.isNotEmpty()) Muted("Denies: " + cpl.denies.joinToString("; "))
        }
    }
    if (sh.exam.isNotEmpty()) SheetBlock("Examination (as spoken)") { sh.exam.forEach { Text("• $it", fontSize = 13.sp) } }
    SheetBlock("Provisional diagnosis") {
        if (sh.impression.isEmpty()) Muted("Not yet clear.")
        sh.impression.forEach { Text("• $it", fontSize = 14.sp) }
        if (sh.aiImpression.isNotEmpty()) {
            Text("AI suggests (unverified)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Palette.infoFg)
            sh.aiImpression.forEach { Text("• $it", fontSize = 13.sp) }
        }
    }
    if (sh.danger.isNotEmpty()) SheetBlock("Don't miss", Palette.emergencyBg) { sh.danger.forEach { Text("• $it", color = Palette.emergencyFg, fontSize = 13.sp) } }
    if (sh.redFlags.isNotEmpty()) SheetBlock("Red flags") {
        sh.redFlags.forEach { f ->
            val color = when (f.status) {
                "PRESENT" -> Palette.emergencyFg
                "possible", "not asked" -> Palette.urgentFg
                else -> Palette.okFg
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(f.status, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (sh.toAsk.isNotEmpty()) SheetBlock("Still to cover") { sh.toAsk.forEach { Text("• $it", fontSize = 13.sp) } }
    if (sh.investigations.isNotEmpty()) SheetBlock("Investigations to consider") { Text(sh.investigations.joinToString(", "), fontSize = 13.sp) }
    SheetBlock("Treatment so far") {
        if (sh.treatment.isEmpty()) Muted("Nothing accepted or dictated yet.")
        sh.treatment.forEach { Text("• $it", fontSize = 13.sp) }
    }
}

@Composable
private fun SheetBlock(title: String, container: Color = Color.Unspecified, content: @Composable ColumnScope.() -> Unit) {
    SectionCard(title, container = container, content = content)
}

/* ============================== Wide-screen header ============================== */

/** Title above the panel when it is shown beside the content (the layer has its own title bar). */
@Composable
fun SidePanelTitle() {
    Text("🩺 Provisional diagnosis & OPD sheet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    Spacer(Modifier.height(2.dp))
}
