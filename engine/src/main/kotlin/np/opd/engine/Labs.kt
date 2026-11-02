package np.opd.engine

/**
 * Laboratory reference catalogue and evaluation. Pure, deterministic and dependency-free.
 *
 * The catalogue holds the reference ranges and panic (critical) thresholds used across the ward.
 * [LabCatalogue.evaluate] classifies a numeric value as normal/high/low and flags panic values.
 * These ranges are prototype values for a Nepali adult inpatient setting and must be confirmed
 * against the local laboratory's own ranges before clinical use.
 */
object LabCatalogue {

    enum class Category { HEMATOLOGY, RENAL, ELECTROLYTES, LIVER, COAGULATION, ABG, URINE, OTHER }

    enum class Direction { HIGH, LOW, NORMAL }

    data class Definition(
        val code: String,
        val displayName: String,
        val category: Category,
        val unit: String,
        val minNormal: Double?,
        val maxNormal: Double?,
        val panicLow: Double? = null,
        val panicHigh: Double? = null,
        /** Common spoken/written aliases to help match free text (Latin + Devanagari). */
        val aliases: List<String> = emptyList()
    )

    data class Evaluation(
        val code: String,
        val value: Double,
        val direction: Direction,
        val isAbnormal: Boolean,
        val isPanic: Boolean,
        val reference: String
    )

    val definitions: List<Definition> = listOf(
        Definition("CBC_HB", "Hemoglobin", Category.HEMATOLOGY, "g/dL", 12.0, 16.0, panicLow = 7.0, aliases = listOf("hb", "haemoglobin", "hemoglobin", "हिमोग्लोबिन")),
        Definition("CBC_TLC", "Total Leukocyte Count", Category.HEMATOLOGY, "/µL", 4000.0, 11000.0, panicLow = 2000.0, panicHigh = 25000.0, aliases = listOf("tlc", "wbc", "leucocyte", "total count", "श्वेत रक्त कोष")),
        Definition("CBC_PLT", "Platelets", Category.HEMATOLOGY, "/µL", 150000.0, 450000.0, panicLow = 50000.0, aliases = listOf("plt", "platelet", "प्लेटलेट")),
        Definition("RFT_CR", "Creatinine", Category.RENAL, "mg/dL", 0.6, 1.2, panicHigh = 3.0, aliases = listOf("creatinine", "s creatinine", "cr", "क्रिएटिनिन")),
        Definition("RFT_UREA", "Urea", Category.RENAL, "mg/dL", 15.0, 45.0, aliases = listOf("urea", "blood urea", "युरिया")),
        Definition("LYTE_NA", "Sodium", Category.ELECTROLYTES, "mmol/L", 135.0, 145.0, panicLow = 125.0, panicHigh = 155.0, aliases = listOf("sodium", "na", "na+", "सोडियम")),
        Definition("LYTE_K", "Potassium", Category.ELECTROLYTES, "mmol/L", 3.5, 5.0, panicLow = 2.8, panicHigh = 6.0, aliases = listOf("potassium", "k", "k+", "पोटासियम")),
        Definition("LFT_TBIL", "Total Bilirubin", Category.LIVER, "mg/dL", 0.2, 1.2, aliases = listOf("bilirubin", "t bili", "total bilirubin", "बिलिरुबिन")),
        Definition("LFT_ALT", "ALT (SGPT)", Category.LIVER, "U/L", 5.0, 45.0, aliases = listOf("alt", "sgpt", "alt/sgpt")),
        Definition("COAG_PT", "Prothrombin Time", Category.COAGULATION, "seconds", 11.0, 14.0, aliases = listOf("pt", "prothrombin", "prothrombin time")),
        Definition("COAG_INR", "INR", Category.COAGULATION, "ratio", 0.8, 1.2, panicHigh = 3.5, aliases = listOf("inr")),
        Definition("GLUC_RBS", "Random Blood Sugar", Category.OTHER, "mg/dL", 70.0, 140.0, panicLow = 54.0, panicHigh = 350.0, aliases = listOf("rbs", "blood sugar", "glucose", "sugar", "सुगर"))
    )

    private val byCode: Map<String, Definition> = definitions.associateBy { it.code }

    fun definition(code: String): Definition? = byCode[code]

    /** Finds a definition by code, display name or alias (case-insensitive, word-aware). */
    fun findByText(text: String?): Definition? {
        if (text.isNullOrBlank()) return null
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9\u0900-\u097F+]+"), " ").trim() + " "
        var best: Definition? = null
        var bestLen = 0
        for (d in definitions) {
            val names = listOf(d.code) + d.displayName + d.aliases
            for (n in names) {
                val key = " " + n.lowercase().replace(Regex("[^a-z0-9\u0900-\u097F+]+"), " ").trim() + " "
                if (key.isBlank()) continue
                if (t.contains(key) && key.length > bestLen) { best = d; bestLen = key.length }
            }
        }
        return best
    }

    fun evaluate(code: String, value: Double): Evaluation? {
        val d = byCode[code] ?: return null
        return evaluate(d, value)
    }

    fun evaluate(d: Definition, value: Double): Evaluation {
        val low = d.minNormal
        val high = d.maxNormal
        val direction = when {
            low != null && value < low -> Direction.LOW
            high != null && value > high -> Direction.HIGH
            else -> Direction.NORMAL
        }
        val panic = (d.panicLow != null && value < d.panicLow) || (d.panicHigh != null && value > d.panicHigh)
        val ref = when {
            low != null && high != null -> "${fmt(low)}–${fmt(high)}"
            high != null -> "≤ ${fmt(high)}"
            low != null -> "≥ ${fmt(low)}"
            else -> "—"
        }
        return Evaluation(
            code = d.code,
            value = value,
            direction = direction,
            isAbnormal = direction != Direction.NORMAL,
            isPanic = panic,
            reference = "$ref ${d.unit}".trim()
        )
    }

    /** Evaluates a result and returns nulls gracefully when the test is unknown. */
    fun evaluateOrUnknown(definition: Definition?, value: Double): Evaluation {
        if (definition == null) {
            return Evaluation("UNKNOWN", value, Direction.NORMAL, isAbnormal = false, isPanic = false, reference = "no reference loaded")
        }
        return evaluate(definition, value)
    }

    private fun fmt(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
}
