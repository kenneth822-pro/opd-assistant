package np.opd.engine

/* ------------------------------------------------------------------
 * Catalogue model. Everything clinical lives in JSON under
 * resources/catalogues/v1/ so it can be reviewed without reading code.
 * ------------------------------------------------------------------ */

enum class FlagKind { RED_FLAG, REDIRECT, SCORE_COMPONENT, ALERT }

/** Ordered by urgency: INFO < URGENT < EMERGENCY. */
enum class SessionLevel { INFO, URGENT, EMERGENCY }

enum class Disposition { OPD_TREAT, STAT_INVESTIGATE, REFERRAL, EMERGENCY_TRANSFER }

/** Ordered by severity: CAUTION < WARNING < BLOCKED. */
enum class Severity { CAUTION, WARNING, BLOCKED }

/** A declarative rule over vitals/demographics, e.g. rr >= 30, or an AND/OR of rules. */
data class Rule(
    val field: String? = null,
    val op: String? = null,
    val value: Double? = null,
    val compositeOp: String? = null,
    val rules: List<Rule> = emptyList()
) {
    fun fields(): Set<String> =
        if (compositeOp != null) rules.flatMap { it.fields() }.toSet() else setOfNotNull(field)

    companion object {
        fun from(o: J.Obj?): Rule? {
            if (o == null) return null
            return Rule(
                field = o.str("field"),
                op = o.str("op"),
                value = o.dbl("value"),
                compositeOp = o.str("composite_op"),
                rules = o.objs("rules").mapNotNull { from(it) }
            )
        }
    }
}

data class QuestionDef(val id: String, val promptEn: String, val promptNe: String)
data class VisitChecklist(val minQuestions: Int, val questions: List<QuestionDef>)

data class ScoreComponent(
    val id: String,
    val label: String,
    val points: Int,
    val flagRef: String? = null,
    val rule: Rule? = null
)

data class ScoredCriteria(val scoreName: String, val escalateThreshold: Int, val components: List<ScoreComponent>)

data class ConditionDef(
    val code: String,
    val displayName: String,
    val icd11: String,
    val cues: String,
    val visitTypes: Map<String, VisitChecklist>,
    val mandatoryRedFlags: List<String>,
    val score: ScoredCriteria?,
    val linkedRegimens: List<String>,
    val linkedInvestigations: List<String>,
    /** Nepali/English words that suggest this condition in a transcript (on-device hints). */
    val keywords: List<String> = emptyList()
) {
    fun allQuestionIds(): Set<String> = visitTypes.values.flatMap { v -> v.questions.map { it.id } }.toSet()
    fun checklist(visitType: String): VisitChecklist? = visitTypes[visitType] ?: visitTypes["INITIAL"]
}

data class RedFlagDef(
    val id: String,
    val kind: FlagKind,
    val label: String,
    val labelNe: String,
    val cue: String,
    val sessionLevel: SessionLevel,
    val disposition: Disposition,
    val derivationRule: Rule?,
    val redirectTo: String?,
    val keywords: List<String> = emptyList()
)

data class DrugDef(
    val id: String,
    val generic: String,
    val brands: List<String>,
    val classes: Set<String>,
    val form: String,
    val strengthValue: Double,
    val strengthUnit: String,
    val pregnancyRule: String,
    val lactationRule: String,
    val renalRule: String,
    val paracetamolMgPerUnit: Double,
    val elderlyCaution: String?
) {
    fun strengthText(): String {
        val v = if (strengthValue == Math.floor(strengthValue)) strengthValue.toLong().toString() else strengthValue.toString()
        return "$v $strengthUnit"
    }
}

data class RegimenComponent(
    val drugId: String,
    val doseCount: Double,
    val route: String,
    val frequency: String,
    val timing: String,
    val durationDays: Int?,
    val maxDosesPerDay: Int?,
    val prnIndication: String?,
    val noteEn: String?,
    val noteNe: String?
)

data class Eligibility(
    val genders: List<String> = emptyList(),
    val minAge: Int? = null,
    val maxAge: Int? = null,
    val requiresAny: List<String> = emptyList()
)

data class RegimenDef(
    val id: String,
    val conditionCode: String,
    val lineOfTherapy: String,
    val label: String,
    val components: List<RegimenComponent>,
    val eligibility: Eligibility,
    val verificationStatus: String
)

data class AllergyRule(
    val code: String,
    val label: String,
    val blockedClasses: Set<String>,
    val cautionClasses: Set<String>,
    val keywords: List<String>
)

/** a/b are either a drug id (DRUG_...) or a class name (NSAID, STATIN, ...). */
data class InteractionRule(val a: String, val b: String, val severity: Severity, val message: String)

data class InvestigationDef(val id: String, val name: String)

class Catalogues(
    val version: String,
    val conditions: Map<String, ConditionDef>,
    val redFlags: Map<String, RedFlagDef>,
    val regimens: Map<String, RegimenDef>,
    val drugs: Map<String, DrugDef>,
    val allergies: Map<String, AllergyRule>,
    val comorbidities: Map<String, String>,
    val interactions: List<InteractionRule>,
    val investigations: Map<String, InvestigationDef>
) {
    /** Find a formulary drug from free text ("Tab Pantop 40", "ibuprofen 400mg"). Longest match wins. */
    fun findDrug(text: String?): DrugDef? {
        if (text.isNullOrBlank()) return null
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9+]+"), " ") + " "
        var best: DrugDef? = null
        var bestLen = 0
        for (d in drugs.values) {
            val names = listOf(d.generic) + d.brands
            for (n in names) {
                val key = " " + n.lowercase().replace(Regex("[^a-z0-9+]+"), " ").trim() + " "
                if (key.isBlank()) continue
                if (t.contains(key) && key.length > bestLen) { best = d; bestLen = key.length }
            }
        }
        return best
    }

    fun drugMatches(token: String, drug: DrugDef): Boolean = token == drug.id || token in drug.classes

    companion object {
        const val DIR = "/catalogues/v1/"
        val FILES = listOf("conditions.json", "red_flags.json", "regimens.json", "formulary.json", "vocabularies.json")

        /** Loads the catalogues bundled as Java resources (works on JVM and Android). */
        fun loadBundled(): Catalogues {
            fun read(name: String): String {
                val stream = Catalogues::class.java.getResourceAsStream(DIR + name)
                    ?: throw IllegalStateException("Catalogue resource missing: $name")
                return stream.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            return parse(read(FILES[0]), read(FILES[1]), read(FILES[2]), read(FILES[3]), read(FILES[4]))
        }

        fun parse(conditionsJson: String, redFlagsJson: String, regimensJson: String, formularyJson: String, vocabJson: String): Catalogues {
            val cj = J.parse(conditionsJson) as J.Obj
            val rj = J.parse(redFlagsJson) as J.Obj
            val gj = J.parse(regimensJson) as J.Obj
            val fj = J.parse(formularyJson) as J.Obj
            val vj = J.parse(vocabJson) as J.Obj

            val conditions = LinkedHashMap<String, ConditionDef>()
            for (c in cj.objs("conditions")) {
                val visits = LinkedHashMap<String, VisitChecklist>()
                c.obj("visit_types")?.fields?.forEach { (name, v) ->
                    val vo = v as? J.Obj ?: return@forEach
                    visits[name] = VisitChecklist(
                        minQuestions = vo.int("min_questions") ?: 0,
                        questions = vo.objs("required_questions").map {
                            QuestionDef(it.req("id"), it.strOr("prompt_en", it.req("id")), it.strOr("prompt_ne", ""))
                        }
                    )
                }
                val score = c.obj("scored_criteria")?.let { s ->
                    ScoredCriteria(
                        scoreName = s.req("score_name"),
                        escalateThreshold = s.int("escalate_threshold") ?: 99,
                        components = s.objs("components").map {
                            ScoreComponent(
                                id = it.req("id"),
                                label = it.strOr("label", it.req("id")),
                                points = it.int("points") ?: 1,
                                flagRef = it.str("flag_ref"),
                                rule = Rule.from(it.obj("rule"))
                            )
                        }
                    )
                }
                val code = c.req("condition_code")
                conditions[code] = ConditionDef(
                    code = code,
                    displayName = c.strOr("display_name", code),
                    icd11 = c.strOr("icd11_code", ""),
                    cues = c.strOr("cues", ""),
                    visitTypes = visits,
                    mandatoryRedFlags = c.strs("mandatory_red_flags"),
                    score = score,
                    linkedRegimens = c.strs("linked_regimens"),
                    linkedInvestigations = c.strs("linked_investigations"),
                    keywords = c.strs("keywords").map { it.lowercase() }
                )
            }

            val flags = LinkedHashMap<String, RedFlagDef>()
            for (f in rj.objs("red_flags")) {
                val id = f.req("id")
                flags[id] = RedFlagDef(
                    id = id,
                    kind = FlagKind.valueOf(f.strOr("kind", "RED_FLAG")),
                    label = f.strOr("label", id),
                    labelNe = f.strOr("label_ne", ""),
                    cue = f.strOr("cue", ""),
                    sessionLevel = SessionLevel.valueOf(f.strOr("session_level", "INFO")),
                    disposition = Disposition.valueOf(f.strOr("disposition", "OPD_TREAT")),
                    derivationRule = Rule.from(f.obj("derivation_rule")),
                    redirectTo = f.str("redirect_to_condition"),
                    keywords = f.strs("keywords").map { it.lowercase() }
                )
            }

            val regimens = LinkedHashMap<String, RegimenDef>()
            for (r in gj.objs("regimens")) {
                val id = r.req("regimen_id")
                val el = r.obj("eligibility")
                regimens[id] = RegimenDef(
                    id = id,
                    conditionCode = r.req("condition_code"),
                    lineOfTherapy = r.strOr("line_of_therapy", "FIRST_LINE"),
                    label = r.strOr("label", id),
                    components = r.objs("components").map {
                        RegimenComponent(
                            drugId = it.req("drug_id"),
                            doseCount = it.dbl("dose_count") ?: 1.0,
                            route = it.strOr("route", "PO"),
                            frequency = it.strOr("frequency", "OD"),
                            timing = it.strOr("timing", "ANY"),
                            durationDays = it.int("duration_days"),
                            maxDosesPerDay = it.int("max_doses_per_day"),
                            prnIndication = it.str("prn_indication"),
                            noteEn = it.str("note_en"),
                            noteNe = it.str("note_ne")
                        )
                    },
                    eligibility = if (el == null) Eligibility() else Eligibility(
                        genders = el.strs("genders"),
                        minAge = el.int("min_age"),
                        maxAge = el.int("max_age"),
                        requiresAny = el.strs("requires_any")
                    ),
                    verificationStatus = r.strOr("verification_status", "UNVERIFIED")
                )
            }

            val drugs = LinkedHashMap<String, DrugDef>()
            for (d in fj.objs("drugs")) {
                val id = d.req("id")
                drugs[id] = DrugDef(
                    id = id,
                    generic = d.strOr("generic", id),
                    brands = d.strs("brands"),
                    classes = d.strs("classes").toSet(),
                    form = d.strOr("form", "TAB"),
                    strengthValue = d.dbl("strength_value") ?: 0.0,
                    strengthUnit = d.strOr("strength_unit", ""),
                    pregnancyRule = d.strOr("pregnancy_rule", "UNKNOWN"),
                    lactationRule = d.strOr("lactation_rule", "UNKNOWN"),
                    renalRule = d.strOr("renal_rule", "UNKNOWN"),
                    paracetamolMgPerUnit = d.dbl("paracetamol_mg_per_unit") ?: 0.0,
                    elderlyCaution = d.str("elderly_caution")
                )
            }

            val allergies = LinkedHashMap<String, AllergyRule>()
            for (a in vj.objs("allergies")) {
                val code = a.req("code")
                allergies[code] = AllergyRule(
                    code = code,
                    label = a.strOr("label", code),
                    blockedClasses = a.strs("blocked_classes").toSet(),
                    cautionClasses = a.strs("caution_classes").toSet(),
                    keywords = a.strs("keywords").map { it.lowercase() }
                )
            }
            val comorbidities = LinkedHashMap<String, String>()
            for (c in vj.objs("comorbidities")) comorbidities[c.req("code")] = c.strOr("label", c.req("code"))
            val interactions = vj.objs("interactions").map {
                InteractionRule(it.req("a"), it.req("b"), Severity.valueOf(it.strOr("severity", "WARNING")), it.strOr("message", ""))
            }
            val investigations = LinkedHashMap<String, InvestigationDef>()
            for (i in vj.objs("investigations")) investigations[i.req("id")] = InvestigationDef(i.req("id"), i.strOr("name", i.req("id")))

            return Catalogues(
                version = cj.strOr("version", "?"),
                conditions = conditions,
                redFlags = flags,
                regimens = regimens,
                drugs = drugs,
                allergies = allergies,
                comorbidities = comorbidities,
                interactions = interactions,
                investigations = investigations
            )
        }
    }
}

/** Referential-integrity check used by tests and at app start-up. Returns a list of problems (empty = OK). */
object CatalogueIntegrity {
    private val validFields = setOf("bp_systolic", "bp_diastolic", "pulse", "rr", "spo2", "temp_f", "blood_glucose", "age")
    private val validOps = setOf(">=", "<=", ">", "<", "==")
    private val validFrequencies = setOf("OD", "BD", "TDS", "QID", "PRN", "STAT")
    private val validTimings = setOf("AC", "PC", "HS", "ANY")

    fun check(c: Catalogues): List<String> {
        val errors = mutableListOf<String>()
        fun ruleErrors(where: String, r: Rule?) {
            if (r == null) return
            if (r.compositeOp != null) {
                if (r.compositeOp !in setOf("AND", "OR")) errors += "$where: bad composite_op ${r.compositeOp}"
                if (r.rules.isEmpty()) errors += "$where: composite rule has no sub-rules"
                r.rules.forEach { ruleErrors(where, it) }
            } else {
                if (r.field !in validFields) errors += "$where: unknown rule field '${r.field}'"
                if (r.op !in validOps) errors += "$where: unknown rule op '${r.op}'"
                if (r.value == null) errors += "$where: rule has no value"
            }
        }

        val allQuestionIds = mutableMapOf<String, String>()
        for (cond in c.conditions.values) {
            if (cond.visitTypes["INITIAL"] == null) errors += "${cond.code}: missing INITIAL visit type"
            for ((vt, cl) in cond.visitTypes) {
                if (cl.minQuestions > cl.questions.size) errors += "${cond.code}/$vt: min_questions exceeds question count"
                for (q in cl.questions) {
                    val prev = allQuestionIds.put(q.id, cond.code)
                    if (prev != null && prev != cond.code) errors += "Question id ${q.id} used by both $prev and ${cond.code}"
                }
            }
            for (f in cond.mandatoryRedFlags) {
                val def = c.redFlags[f]
                if (def == null) errors += "${cond.code}: missing red flag $f"
                else if (def.kind == FlagKind.ALERT) errors += "${cond.code}: ALERT flag $f should not be mandatory"
            }
            cond.score?.components?.forEach { comp ->
                if (comp.flagRef == null && comp.rule == null) errors += "${cond.code}/${comp.id}: score component needs flag_ref or rule"
                if (comp.flagRef != null && c.redFlags[comp.flagRef] == null) errors += "${cond.code}/${comp.id}: missing flag ${comp.flagRef}"
                ruleErrors("${cond.code}/${comp.id}", comp.rule)
            }
            for (r in cond.linkedRegimens) {
                val reg = c.regimens[r]
                if (reg == null) errors += "${cond.code}: missing regimen $r"
                else if (reg.conditionCode != cond.code) errors += "${cond.code}: regimen $r belongs to ${reg.conditionCode}"
            }
            for (inv in cond.linkedInvestigations) if (c.investigations[inv] == null) errors += "${cond.code}: missing investigation $inv"
            if (cond.keywords.isEmpty()) errors += "${cond.code}: needs keywords for on-device hints"
        }
        for (f in c.redFlags.values) {
            ruleErrors("flag ${f.id}", f.derivationRule)
            if (f.kind == FlagKind.REDIRECT && (f.redirectTo == null || c.conditions[f.redirectTo] == null))
                errors += "flag ${f.id}: redirect target '${f.redirectTo}' missing"
            if (f.kind != FlagKind.REDIRECT && f.redirectTo != null) errors += "flag ${f.id}: only REDIRECT flags may redirect"
            if (f.derivationRule == null && f.keywords.isEmpty()) errors += "flag ${f.id}: conversational flag needs keywords"
        }
        for (r in c.regimens.values) {
            if (c.conditions[r.conditionCode] == null) errors += "regimen ${r.id}: unknown condition ${r.conditionCode}"
            if (c.conditions.values.none { r.id in it.linkedRegimens }) errors += "regimen ${r.id}: not linked from any condition"
            if (r.components.isEmpty()) errors += "regimen ${r.id}: no components"
            for (comp in r.components) {
                if (c.drugs[comp.drugId] == null) errors += "regimen ${r.id}: unknown drug ${comp.drugId}"
                if (comp.frequency !in validFrequencies) errors += "regimen ${r.id}: bad frequency ${comp.frequency}"
                if (comp.timing !in validTimings) errors += "regimen ${r.id}: bad timing ${comp.timing}"
                if (comp.frequency == "PRN" && comp.maxDosesPerDay == null) errors += "regimen ${r.id}: PRN needs max_doses_per_day"
                if (comp.frequency == "PRN" && comp.prnIndication == null) errors += "regimen ${r.id}: PRN needs prn_indication"
            }
            for (req in r.eligibility.requiresAny) {
                if (c.comorbidities[req] == null && c.conditions[req] == null) errors += "regimen ${r.id}: requires unknown code $req"
            }
        }
        val allClasses = c.drugs.values.flatMap { it.classes }.toSet()
        for (a in c.allergies.values) {
            for (cl in a.blockedClasses + a.cautionClasses) {
                if (cl !in allClasses && cl != "SULFONAMIDE_ANTIBIOTIC") errors += "allergy ${a.code}: class $cl not used by any drug"
            }
        }
        for (ix in c.interactions) {
            for (tok in listOf(ix.a, ix.b)) {
                if (tok.startsWith("DRUG_")) { if (c.drugs[tok] == null) errors += "interaction: unknown drug $tok" }
                else if (tok !in allClasses) errors += "interaction: unknown class $tok"
            }
        }
        for (d in c.drugs.values) {
            for (rule in listOf(d.pregnancyRule, d.lactationRule)) if (rule !in setOf("OK", "WARN", "BLOCK", "UNKNOWN")) errors += "drug ${d.id}: bad rule $rule"
            if (d.renalRule !in setOf("OK", "ADJUST", "BLOCK", "UNKNOWN")) errors += "drug ${d.id}: bad renal rule ${d.renalRule}"
        }
        return errors
    }
}
