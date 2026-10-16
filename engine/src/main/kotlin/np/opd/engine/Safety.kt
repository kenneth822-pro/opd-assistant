package np.opd.engine

/**
 * Deterministic safety checks. Never calls the AI.
 *
 * Every candidate (a protocol regimen or a dictated drug) is checked against the whole case:
 * allergies, pregnancy/lactation, kidney/liver disease, age, other drugs on the same slip,
 * the patient's current medicines, and the cumulative paracetamol dose. All findings are
 * collected (the engine never stops at the first one) and sorted most severe first.
 */
object Safety {

    /** One drug being taken or prescribed, with enough dose information for cumulative checks. */
    data class DrugUse(
        val key: String,
        val drug: DrugDef?,
        val label: String,
        val unitsPerDose: Double?,
        val dosesPerDay: Double?,
        val dailyMgOverride: Double? = null
    ) {
        fun paracetamolMgPerDay(): Double? {
            val d = drug ?: return 0.0 // not in formulary: flagged separately as "safety checks could not run"
            if (d.paracetamolMgPerUnit <= 0.0) return 0.0
            dailyMgOverride?.let { return it }
            val u = unitsPerDose ?: return null
            val n = dosesPerDay ?: return null
            return u * n * d.paracetamolMgPerUnit
        }
    }

    fun dosesPerDay(frequency: String?, maxPerDay: Int?): Double? = when (frequency?.uppercase()) {
        "OD", "HS" -> 1.0
        "BD" -> 2.0
        "TDS" -> 3.0
        "QID" -> 4.0
        "STAT" -> 1.0
        "PRN" -> (maxPerDay ?: 4).toDouble()
        else -> null
    }

    fun regimenUses(r: RegimenDef, c: Catalogues): List<DrugUse> = r.components.mapIndexed { i, comp ->
        val d = c.drugs[comp.drugId]
        DrugUse(
            key = "regimen:${r.id}:$i",
            drug = d,
            label = d?.generic ?: comp.drugId,
            unitsPerDose = comp.doseCount,
            dosesPerDay = dosesPerDay(comp.frequency, comp.maxDosesPerDay)
        )
    }

    fun dictatedUse(item: DictatedItem, c: Catalogues): DrugUse {
        val d = item.drugId?.let { c.drugs[it] }
        return DrugUse(
            key = "dictated:${item.id}",
            drug = d,
            label = d?.generic ?: item.stated,
            unitsPerDose = item.doseCount ?: 1.0,
            dosesPerDay = dosesPerDay(item.frequency, null)
        )
    }

    /** Drugs already in play: current medicines, accepted regimens, dictated items. */
    fun contextUses(s: CaseState, c: Catalogues): List<DrugUse> {
        val out = mutableListOf<DrugUse>()
        for (m in s.currentMeds) {
            val d = m.drugId?.let { c.drugs[it] }
            out += DrugUse("current:${m.key}", d, "${d?.generic ?: m.stated} (patient already taking)", null, null, m.dailyDoseMg)
        }
        for (rid in s.accepted) {
            val r = c.regimens[rid] ?: continue
            regimenUses(r, c).forEach { out += it.copy(label = "${it.label} (accepted on this slip)") }
        }
        for (item in s.dictated) {
            val u = dictatedUse(item, c)
            out += u.copy(label = "${u.label} (dictated)")
        }
        return out
    }

    fun evaluateRegimen(r: RegimenDef, s: CaseState, c: Catalogues): List<SafetyFinding> {
        val candidate = regimenUses(r, c)
        val context = contextUses(s, c).filterNot { it.key.startsWith("regimen:${r.id}:") }
        return evaluate(candidate, context, s, c)
    }

    fun evaluateDictated(item: DictatedItem, s: CaseState, c: Catalogues): List<SafetyFinding> {
        val candidate = listOf(dictatedUse(item, c))
        val context = contextUses(s, c).filterNot { it.key == "dictated:${item.id}" }
        return evaluate(candidate, context, s, c)
    }

    fun evaluate(candidate: List<DrugUse>, context: List<DrugUse>, s: CaseState, c: Catalogues): List<SafetyFinding> {
        val out = LinkedHashMap<String, SafetyFinding>()
        fun add(sev: Severity, msg: String) {
            val existing = out[msg]
            if (existing == null || existing.severity < sev) out[msg] = SafetyFinding(sev, msg)
        }

        val patientAge = s.demographics.age
        when {
            patientAge == null -> add(Severity.BLOCKED, "Adult-only catalogue: record the patient's age before using this medicine.")
            patientAge < 18 -> add(Severity.BLOCKED, "Adult-only catalogue: age $patientAge is under 18; paediatric dosing is not loaded.")
        }

        val demo = s.demographics
        val possiblyPregnant = demo.pregnancy == Pregnancy.PREGNANT ||
            (demo.pregnancy == Pregnancy.UNKNOWN && demo.gender != Gender.MALE &&
                (demo.age == null || demo.age in 12..50))
        val hasApdProblem = s.problems.any { it.conditionCode == "COND_APD" }

        for (u in candidate) {
            val d = u.drug
            if (d == null) {
                add(Severity.CAUTION, "'${u.label}' is not in the formulary: safety checks could not run.")
                continue
            }
            val name = d.generic

            // Allergies
            for (code in s.allergies) {
                val rule = c.allergies[code] ?: continue
                if (d.classes.any { it in rule.blockedClasses }) add(Severity.BLOCKED, "${rule.label}: $name is in the same drug class.")
                else if (d.classes.any { it in rule.cautionClasses }) add(Severity.CAUTION, "${rule.label}: small cross-reactivity risk with $name.")
            }

            // Pregnancy
            when {
                demo.pregnancy == Pregnancy.PREGNANT && d.pregnancyRule == "BLOCK" ->
                    add(Severity.BLOCKED, "Pregnant: $name should not be used in pregnancy.")
                demo.pregnancy == Pregnancy.PREGNANT && d.pregnancyRule == "WARN" ->
                    add(Severity.WARNING, "Pregnant: check whether $name is appropriate in pregnancy.")
                possiblyPregnant && d.pregnancyRule == "BLOCK" ->
                    add(Severity.BLOCKED, "Pregnancy not ruled out (${pregnancyContext(demo)}): confirm before $name.")
                possiblyPregnant && d.pregnancyRule == "WARN" ->
                    add(Severity.CAUTION, "Pregnancy not ruled out: $name needs caution in pregnancy.")
                possiblyPregnant && d.pregnancyRule == "UNKNOWN" ->
                    add(Severity.CAUTION, "Pregnancy safety data for $name not loaded.")
            }

            // Lactation
            if (demo.lactating) {
                if (d.lactationRule == "BLOCK") add(Severity.BLOCKED, "Breastfeeding: $name should be avoided.")
                else if (d.lactationRule == "WARN") add(Severity.WARNING, "Breastfeeding: check $name.")
            }

            // Kidney
            if ("COMORB_CKD" in s.comorbidities) {
                when (d.renalRule) {
                    "BLOCK" -> add(Severity.BLOCKED, "Kidney disease: avoid $name.")
                    "ADJUST" -> add(Severity.WARNING, "Kidney disease: $name may need dose change; check creatinine/eGFR.")
                    "UNKNOWN" -> add(Severity.CAUTION, "Kidney safety data for $name not loaded.")
                }
            }

            // Age
            val age = demo.age
            if (age != null && age >= 65 && d.elderlyCaution != null) add(Severity.WARNING, "Age $age: ${d.elderlyCaution} ($name).")

            // NSAID context
            if ("NSAID" in d.classes) {
                if ("COMORB_PUD" in s.comorbidities || hasApdProblem)
                    add(Severity.WARNING, "Acid peptic disease / ulcer history: $name can cause GI bleeding; prefer paracetamol or add PPI.")
                if ("COMORB_ASTHMA_COPD" in s.comorbidities)
                    add(Severity.CAUTION, "Asthma/COPD: $name can worsen bronchospasm in some patients.")
            }

            // Pairwise interactions: with context drugs and with other components of the same candidate
            val others = context + candidate.filter { it !== u }
            for (o in others) {
                val od = o.drug ?: continue
                if (od.id == d.id) {
                    add(Severity.WARNING, "Duplicate: $name is already listed (${o.label}).")
                    continue
                }
                for (ix in c.interactions) {
                    val hit = (c.drugMatches(ix.a, d) && c.drugMatches(ix.b, od)) || (c.drugMatches(ix.b, d) && c.drugMatches(ix.a, od))
                    if (hit) add(ix.severity, "Interaction with ${o.label}: ${ix.message}")
                }
            }
        }

        // Triple whammy: NSAID + (ARB or ACE inhibitor) + diuretic
        val all = candidate + context
        fun has(cls: String, uses: List<DrugUse>) = uses.any { it.drug?.classes?.contains(cls) == true }
        if (has("NSAID", all) && (has("ARB", all) || has("ACE_INHIBITOR", all)) && has("DIURETIC", all)) {
            val candClasses = candidate.flatMap { it.drug?.classes ?: emptySet() }.toSet()
            if (candClasses.any { it in setOf("NSAID", "ARB", "ACE_INHIBITOR", "DIURETIC") })
                add(Severity.BLOCKED, "'Triple whammy': NSAID + ARB/ACE inhibitor + diuretic together can cause acute kidney injury.")
        }

        // Cumulative paracetamol
        val candidateHasParacetamol = candidate.any { (it.drug?.paracetamolMgPerUnit ?: 0.0) > 0.0 }
        if (candidateHasParacetamol) {
            var total = 0.0
            var unknownDose = false
            for (u in all) {
                val mg = u.paracetamolMgPerDay()
                if (mg == null) unknownDose = true else total += mg
            }
            val hepatic = "COMORB_HEPATIC" in s.comorbidities
            val limit = if (hepatic) 2000.0 else 4000.0
            if (total > limit) {
                add(Severity.BLOCKED, "Total paracetamol would be ${total.toInt()} mg/day (limit ${limit.toInt()} mg${if (hepatic) " in liver disease" else ""}).")
            } else if (unknownDose) {
                add(Severity.WARNING, "Patient already takes a paracetamol-containing medicine at an unknown dose: check the total daily amount.")
            }
        }

        return out.values.sortedByDescending { it.severity.ordinal }
    }

    private fun pregnancyContext(d: Demographics): String = when {
        d.gender == Gender.UNKNOWN -> "sex not recorded"
        d.age == null -> "age not recorded"
        else -> "status not confirmed"
    }
}
