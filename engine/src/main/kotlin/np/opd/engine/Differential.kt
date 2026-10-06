package np.opd.engine

/* ------------------------------------------------------------------
 * Live provisional diagnosis.
 *
 * A pure function of the case so far (problems, keyword hints, vitals, history, medicines), so it can be
 * recomputed on every utterance, works offline and can be explained line by line.
 *
 * It is DISPLAY-ONLY decision support: it never changes the case state, never unlocks or selects a
 * medicine (the doctor adds a problem to do that), and says "Likely / Possible / Less likely", never a
 * made-up probability. Every item lists the evidence for and against it.
 * ------------------------------------------------------------------ */

enum class Likelihood { UNLIKELY, POSSIBLE, LIKELY }

enum class EvidenceKind { PROBLEM, HEARD, DENIED, MEASURED, HISTORY, MEDICINE, PATIENT }

/** [points] > 0 supports the diagnosis, < 0 argues against it, 0 is context only. */
data class Evidence(val kind: EvidenceKind, val text: String, val points: Int)

/** Something that would help confirm or exclude a diagnosis, in English and Nepali. */
data class Clarify(val en: String, val ne: String)

data class DiagnosisEntry(
    val code: String,
    val label: String,
    val icd11: String,
    val score: Int,
    val likelihood: Likelihood,
    /** Already a problem on the case (so its protocol, red flags and prescription are active). */
    val onProblemList: Boolean,
    val supporting: List<Evidence>,
    val against: List<Evidence>,
    val toClarify: List<Clarify>,
    val verification: Verification,
    /** The AI's own differential also names this condition. */
    val aiAgrees: Boolean
)

/** A suspected or confirmed red flag, with the dangerous diagnosis it points to. */
data class DangerEntry(
    val flagId: String,
    val label: String,
    val implies: String,
    val confirmed: Boolean,
    val evidence: String?,
    val action: String
)

data class DifferentialResult(
    val ranked: List<DiagnosisEntry> = emptyList(),
    val danger: List<DangerEntry> = emptyList(),
    /** Red flags of the leading diagnoses that nobody has asked about yet. */
    val ruleOut: List<RedFlagDef> = emptyList(),
    /** Problems outside the catalogue (e.g. "Ear pain"): listed, but there is no protocol for them. */
    val outsideCatalogue: List<String> = emptyList(),
    val ai: List<AiDiagnosis> = emptyList()
) {
    /** The leading diagnosis, only when the evidence reaches "likely". */
    val working: DiagnosisEntry? get() = ranked.firstOrNull { it.likelihood == Likelihood.LIKELY }
    val isEmpty: Boolean get() = ranked.isEmpty() && danger.isEmpty() && outsideCatalogue.isEmpty() && ai.isEmpty()
}

enum class Trend { NEW, UP, DOWN, STEADY }

data class DiagnosisSnap(val likelihood: Likelihood, val score: Int)

data class DiagnosisChange(val atSec: Int, val text: String)

/**
 * The result plus what changed. Trends are measured against [baseline], the state the doctor last
 * looked at (see [Differential.acknowledge]), so a "new" or "rose" marker stays until it has been seen.
 */
data class DiagnosisView(
    val result: DifferentialResult = DifferentialResult(),
    val baseline: Map<String, DiagnosisSnap> = emptyMap(),
    /** Red-flag ids whose "don't miss" entry the doctor has already seen. */
    val seenDanger: Set<String> = emptySet(),
    /** What changed, newest first (a log, independent of the baseline). */
    val changes: List<DiagnosisChange> = emptyList()
) {
    fun trend(code: String): Trend {
        val now = result.ranked.firstOrNull { it.code == code } ?: return Trend.STEADY
        val before = baseline[code] ?: return Trend.NEW
        return when {
            now.likelihood > before.likelihood || now.score - before.score >= Differential.TREND_STEP -> Trend.UP
            now.likelihood < before.likelihood || now.score - before.score <= -Differential.TREND_STEP -> Trend.DOWN
            else -> Trend.STEADY
        }
    }

    fun isNewDanger(flagId: String): Boolean = flagId !in seenDanger

    /** True while something changed since the doctor last looked (drives the dot on the strip). */
    val hasUnseenChange: Boolean
        get() = result.ranked.any { trend(it.code) != Trend.STEADY } || result.danger.any { isNewDanger(it.flagId) }
}

object Differential {

    const val LIKELY_AT = 6
    const val POSSIBLE_AT = 2
    /** Points for a condition that is already on the problem list. */
    const val PROBLEM_POINTS = 5
    const val KEYWORD_POINTS = 2
    const val KEYWORD_CAP = 6
    const val DENIED_POINTS = -2
    const val DENIED_CAP = -4
    /** Measured evidence alone (no complaint heard yet) must add up to this to surface a diagnosis. */
    const val MEASURED_SURFACES_AT = 3
    const val MAX_ENTRIES = 6
    const val MAX_CHANGES = 8
    const val TREND_STEP = 2

    fun compute(s: CaseState, hints: LocalAssist.Hints, c: Catalogues): DifferentialResult {
        val aiCodes = s.aiDifferential.mapNotNull { it.conditionCode }.toSet()
        val entries = ArrayList<DiagnosisEntry>()

        for (cond in c.conditions.values) {
            val problem = s.problems.firstOrNull { it.conditionCode == cond.code }
            val support = ArrayList<Evidence>()
            val against = ArrayList<Evidence>()

            if (problem != null) {
                support += Evidence(EvidenceKind.PROBLEM, "On the problem list", PROBLEM_POINTS)
                if (problem.symptoms.isNotEmpty())
                    support += Evidence(EvidenceKind.HEARD, "Symptoms: " + problem.symptoms.take(4).joinToString("; ") { it.name }, 0)
            }

            val heard = hints.conditions.firstOrNull { it.code == cond.code }?.matched?.distinct().orEmpty()
            if (heard.isNotEmpty())
                support += Evidence(EvidenceKind.HEARD, "Heard: " + quoted(heard), minOf(heard.size * KEYWORD_POINTS, KEYWORD_CAP))

            val denied = hints.deniedConditions.firstOrNull { it.code == cond.code }?.matched?.distinct().orEmpty()
            if (denied.isNotEmpty())
                against += Evidence(EvidenceKind.DENIED, "Denied: " + quoted(denied), maxOf(denied.size * DENIED_POINTS, DENIED_CAP))

            var measured = 0
            for (clue in cond.clues) {
                if (!holds(clue, s, c)) continue
                val kind = kindOf(clue)
                if (kind == EvidenceKind.MEASURED) measured += clue.points
                (if (clue.points > 0) support else against) += Evidence(kind, clue.label, clue.points)
            }

            // Comorbidities, medicines, sex and age alone never surface a diagnosis: there must be a complaint
            // (on the problem list or heard in the conversation) or enough abnormal measurements.
            val surfaces = problem != null || heard.isNotEmpty() || measured >= MEASURED_SURFACES_AT
            if (!surfaces) continue

            val score = support.sumOf { it.points } + against.sumOf { it.points }
            val likelihood = when {
                score >= LIKELY_AT -> Likelihood.LIKELY
                score >= POSSIBLE_AT -> Likelihood.POSSIBLE
                else -> Likelihood.UNLIKELY
            }
            // A problem the doctor or AI filed is never hidden, even if the evidence against outweighs it.
            if (problem == null && likelihood == Likelihood.UNLIKELY) continue

            entries += DiagnosisEntry(
                code = cond.code,
                label = cond.displayName,
                icd11 = cond.icd11,
                score = score,
                likelihood = likelihood,
                onProblemList = problem != null,
                supporting = support,
                against = against,
                toClarify = toClarify(cond, problem, s, c),
                verification = cond.verification,
                aiAgrees = cond.code in aiCodes
            )
        }

        val ranked = entries.sortedByDescending { it.score }.take(MAX_ENTRIES)

        val danger = s.flags.values.mapNotNull { f ->
            val def = c.redFlags[f.flagId] ?: return@mapNotNull null
            if (def.kind == FlagKind.REDIRECT || def.sessionLevel == SessionLevel.INFO && def.kind != FlagKind.ALERT) return@mapNotNull null
            if (f.status != FlagStatus.CONFIRMED && f.status != FlagStatus.SUSPECTED) return@mapNotNull null
            DangerEntry(def.id, def.label, def.implies ?: def.label, f.status == FlagStatus.CONFIRMED, f.evidence, Reducer.dispositionText(def.disposition))
        }.sortedWith(compareByDescending<DangerEntry> { it.confirmed }.thenByDescending { c.redFlags[it.flagId]?.sessionLevel?.ordinal ?: 0 }.thenBy { it.label })

        val dangerIds = danger.map { it.flagId }.toSet()
        val ruleOut = ranked.filter { it.likelihood != Likelihood.UNLIKELY }.take(3)
            .flatMap { e -> c.conditions[e.code]?.mandatoryRedFlags.orEmpty() }
            .distinct()
            .filter { id -> id !in dangerIds && (s.flags[id]?.status ?: FlagStatus.UNASSESSED) == FlagStatus.UNASSESSED }
            .mapNotNull { c.redFlags[it] }
            .filter { it.kind != FlagKind.REDIRECT }
            .take(6)

        return DifferentialResult(
            ranked = ranked,
            danger = danger,
            ruleOut = ruleOut,
            outsideCatalogue = s.problems.filter { it.conditionCode == Reducer.OTHER }.map { it.label }.distinct(),
            ai = s.aiDifferential
        )
    }

    private fun quoted(words: List<String>) = words.take(4).joinToString(", ") { "“$it”" }

    private fun holds(cl: DiagnosticClue, s: CaseState, c: Catalogues): Boolean = when {
        cl.rule != null -> RuleEvaluator.evaluate(cl.rule, s.vitals, s.demographics) == true
        cl.comorbidity != null -> cl.comorbidity in s.comorbidities
        cl.medClass != null -> s.currentMeds.any { m -> m.drugId?.let { c.drugs[it] }?.let { d -> c.drugMatches(cl.medClass, d) } == true }
        cl.gender != null -> s.demographics.gender.name == cl.gender
        else -> false
    }

    private fun kindOf(cl: DiagnosticClue): EvidenceKind = when {
        cl.rule != null -> if (cl.rule.fields().all { it == "age" }) EvidenceKind.PATIENT else EvidenceKind.MEASURED
        cl.comorbidity != null -> EvidenceKind.HISTORY
        cl.medClass != null -> EvidenceKind.MEDICINE
        else -> EvidenceKind.PATIENT
    }

    /** What to ask or check next to confirm or exclude this diagnosis (at most 4). */
    private fun toClarify(cond: ConditionDef, problem: Problem?, s: CaseState, c: Catalogues): List<Clarify> {
        val questions = if (problem != null)
            cond.checklist(problem.visitType)?.questions.orEmpty().filter { it.id in problem.pendingQuestionIds }
        else
            cond.checklist("INITIAL")?.questions.orEmpty().take(2)
        val flags = cond.mandatoryRedFlags.mapNotNull { c.redFlags[it] }
            .filter { it.kind != FlagKind.REDIRECT && (s.flags[it.id]?.status ?: FlagStatus.UNASSESSED) == FlagStatus.UNASSESSED }
            .map { f ->
                if (f.derivationRule != null) Clarify("Measure: ${f.label}", f.labelNe)
                else Clarify("Rule out: ${f.label}", f.labelNe.ifBlank { f.label })
            }
        return (questions.map { Clarify(it.promptEn, it.promptNe) } + flags).take(4)
    }

    /* ------------------------- tracking ------------------------- */

    /**
     * Folds a new result into the view: records what changed (for the "recent changes" log) and keeps the
     * baseline, so markers show change since the doctor last looked. Returns [prev] if nothing changed.
     */
    fun track(prev: DiagnosisView, now: DifferentialResult, atSec: Int): DiagnosisView {
        if (now == prev.result) return prev
        val before = prev.result
        val log = ArrayList<DiagnosisChange>()

        for (e in now.ranked) {
            val old = before.ranked.firstOrNull { it.code == e.code }
            if (old == null) {
                log += DiagnosisChange(atSec, "${e.label}: now ${band(e.likelihood)}" + reason(e, null))
            } else if (e.likelihood != old.likelihood) {
                val verb = if (e.likelihood > old.likelihood) "rose" else "fell"
                log += DiagnosisChange(atSec, "${e.label}: $verb to ${band(e.likelihood)}" + reason(e, old))
            }
        }
        for (old in before.ranked) if (now.ranked.none { it.code == old.code }) log += DiagnosisChange(atSec, "${old.label}: no longer suggested")
        for (d in now.danger) if (before.danger.none { it.flagId == d.flagId })
            log += DiagnosisChange(atSec, "⚠ Don't miss: ${d.implies} (${d.label})")

        return prev.copy(result = now, changes = (log.reversed() + prev.changes).take(MAX_CHANGES))
    }

    /** Marks everything currently shown as seen: trends reset until something changes again. */
    fun acknowledge(v: DiagnosisView): DiagnosisView = v.copy(
        baseline = v.result.ranked.associate { it.code to DiagnosisSnap(it.likelihood, it.score) },
        seenDanger = v.result.danger.map { it.flagId }.toSet()
    )

    private fun band(l: Likelihood) = when (l) {
        Likelihood.LIKELY -> "likely"
        Likelihood.POSSIBLE -> "possible"
        Likelihood.UNLIKELY -> "less likely"
    }

    /** The strongest evidence that appeared since [old] (or, for a new entry, the strongest overall). */
    private fun reason(e: DiagnosisEntry, old: DiagnosisEntry?): String {
        val known = old?.supporting?.map { it.text }?.toSet().orEmpty()
        val top = e.supporting.filter { it.points > 0 && it.text !in known && it.kind != EvidenceKind.PROBLEM }.maxByOrNull { it.points }
            ?: e.supporting.firstOrNull { it.kind == EvidenceKind.PROBLEM && old == null }
        return top?.let { " (${it.text.replaceFirstChar { ch -> ch.lowercase() }})" } ?: ""
    }
}
