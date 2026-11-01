package np.opd.engine

/**
 * Verbal order handling for ward rounds.
 *
 * A consultant walking the ward speaks treatment changes ("stop ceftriaxone, start meropenem
 * 1 g TDS IV, allow oral sips, repeat potassium in the evening"). The AI only *reports what was
 * said*; this file turns that report into a proposed [CardexDiff], and the diff is checked by the
 * deterministic [Safety] engine before anything is committed to the patient's treatment sheet.
 *
 * Nothing here calls an AI and nothing here mutates clinical data: it is a pure reducer from
 * (current cardex + parsed order + case context) to a reviewable proposal.
 */

/** One active line already on the cardex, in engine terms (mirrors the Room cardex row). */
data class CardexLine(
    val lineId: String,
    val drugId: String?,
    val stated: String,
    val doseCount: Double? = null,
    val route: String? = null,
    val frequency: String? = null
)

enum class OrderKind { MED, INVESTIGATION, NURSING, DIET, REFERRAL }

enum class ChangeAction { START, STOP, ADJUST }

/** What the AI extracted from the round audio, before any deterministic processing. */
data class VerbalOrder(
    val medicationChanges: List<MedChange> = emptyList(),
    val investigations: List<String> = emptyList(),
    val nursingOrders: List<String> = emptyList(),
    val dietOrders: List<String> = emptyList(),
    val referrals: List<String> = emptyList(),
    val transcript: List<TranscriptLine> = emptyList()
)

data class MedChange(
    val action: ChangeAction,
    /** What was said, exactly (kept so nothing is silently lost). */
    val stated: String,
    val doseCount: Double? = null,
    val route: String? = null,
    val frequency: String? = null,
    val durationDays: Int? = null,
    /** True when the drug name did not match the formulary. */
    val unmatched: Boolean = false
)

/** A single proposed line on the review screen. */
data class DiffItem(
    val kind: OrderKind,
    val action: ChangeAction,
    val label: String,
    val matchedDrugId: String? = null,
    /** For STOP/ADJUST: the cardex line this applies to. */
    val targetLineId: String? = null,
    val findings: List<SafetyFinding> = emptyList()
) {
    /** A line that must not be committed without an explicit override. */
    val blocked: Boolean get() = findings.any { it.severity == Severity.BLOCKED }
    val hasWarning: Boolean get() = findings.any { it.severity == Severity.WARNING }
}

data class CardexDiff(
    val items: List<DiffItem> = emptyList()
) {
    val startItems: List<DiffItem> get() = items.filter { it.action == ChangeAction.START }
    val stopItems: List<DiffItem> get() = items.filter { it.action == ChangeAction.STOP }
    val adjustItems: List<DiffItem> get() = items.filter { it.action == ChangeAction.ADJUST }
    val nonMedItems: List<DiffItem> get() = items.filter { it.kind != OrderKind.MED }
    val blocked: Boolean get() = items.any { it.blocked }

    companion object {
        val EMPTY = CardexDiff()
    }
}

object VerbalOrders {

    /**
     * Turns a parsed [VerbalOrder] plus the current cardex into a reviewable [CardexDiff].
     *
     * START items are matched to the formulary and run through [Safety] using [context] (age, sex,
     * allergies, comorbidities, current medicines). STOP items are matched to existing active lines
     * by drug identity (formulary id) or by name text. Investigations/diet/nursing/referrals become
     * non-medication items and are shown but never safety-gated.
     */
    fun diff(order: VerbalOrder, activeCardex: List<CardexLine>, context: CaseState, c: Catalogues): CardexDiff {
        val items = mutableListOf<DiffItem>()

        for (mc in order.medicationChanges) {
            val drug = c.findDrug(mc.stated)
            val matchedId = drug?.id
            val label = drug?.generic ?: mc.stated

            when (mc.action) {
                ChangeAction.START -> {
                    val findings = if (drug != null) {
                        val use = Safety.DrugUse(
                            key = "verbal:${matchedId}",
                            drug = drug,
                            label = drug.generic,
                            unitsPerDose = mc.doseCount ?: 1.0,
                            dosesPerDay = Safety.dosesPerDay(mc.frequency, null)
                        )
                        val contextUses = Safety.contextUses(context, c)
                        Safety.evaluate(listOf(use), contextUses, context, c)
                    } else {
                        listOf(SafetyFinding(Severity.CAUTION, "'${mc.stated}' is not in the formulary: safety checks could not run."))
                    }
                    items += DiffItem(OrderKind.MED, ChangeAction.START, label, matchedId, null, findings)
                }
                ChangeAction.STOP -> {
                    val target = matchLine(activeCardex, drug?.id, mc.stated)
                    items += DiffItem(
                        OrderKind.MED,
                        ChangeAction.STOP,
                        target?.let { line -> c.drugs[line.drugId]?.generic ?: line.stated } ?: mc.stated,
                        drug?.id,
                        target?.lineId
                    )
                }
                ChangeAction.ADJUST -> {
                    val target = matchLine(activeCardex, drug?.id, mc.stated)
                    items += DiffItem(
                        OrderKind.MED,
                        ChangeAction.ADJUST,
                        label + " (dose/route change)",
                        matchedId,
                        target?.lineId
                    )
                }
            }
        }

        order.investigations.forEach { items += DiffItem(OrderKind.INVESTIGATION, ChangeAction.START, it.trim()) }
        order.nursingOrders.forEach { items += DiffItem(OrderKind.NURSING, ChangeAction.START, it.trim()) }
        order.dietOrders.forEach { items += DiffItem(OrderKind.DIET, ChangeAction.START, it.trim()) }
        order.referrals.forEach { items += DiffItem(OrderKind.REFERRAL, ChangeAction.START, it.trim()) }

        return CardexDiff(items.filter { it.label.isNotBlank() })
    }

    private fun matchLine(active: List<CardexLine>, drugId: String?, stated: String): CardexLine? {
        if (drugId != null) active.firstOrNull { it.drugId == drugId }?.let { return it }
        val needle = stated.lowercase().trim()
        if (needle.isBlank()) return null
        return active.firstOrNull { it.stated.lowercase().contains(needle) || needle.contains(it.stated.lowercase().trim()) }
    }

    /* ---------------------------- parsing ---------------------------- */

    fun parse(text: String): VerbalOrder {
        val o = J.parse(Prompts.extractJson(text)) as? J.Obj ?: throw JsonException("Verbal order is not a JSON object")
        return fromJ(o)
    }

    fun fromJ(o: J.Obj): VerbalOrder = VerbalOrder(
        medicationChanges = o.objs("medication_changes").map {
            MedChange(
                action = parseAction(it.strOr("action", "START")),
                stated = it.strOr("drug_stated", "").trim(),
                doseCount = it.dbl("dose_count"),
                route = it.str("route"),
                frequency = it.str("frequency"),
                durationDays = it.int("duration_days"),
                unmatched = false
            )
        }.filter { it.stated.isNotBlank() },
        investigations = o.strs("investigations").map { it.trim() }.filter { it.isNotBlank() },
        nursingOrders = o.strs("nursing_orders").map { it.trim() }.filter { it.isNotBlank() },
        dietOrders = o.strs("diet_orders").map { it.trim() }.filter { it.isNotBlank() },
        referrals = o.strs("referrals").map { it.trim() }.filter { it.isNotBlank() },
        transcript = o.objs("transcript").map {
            TranscriptLine(it.strOr("speaker", "doctor"), it.strOr("text", ""), (it.dbl("slice_offset_ms") ?: 0.0).toLong())
        }.filter { it.text.isNotBlank() }
    )

    private fun parseAction(s: String): ChangeAction = when (s.trim().uppercase()) {
        "STOP", "DISCONTINUE", "STOPPED", "CANCEL" -> ChangeAction.STOP
        "ADJUST", "CHANGE", "MODIFY", "SWITCH" -> ChangeAction.ADJUST
        else -> ChangeAction.START
    }
}
