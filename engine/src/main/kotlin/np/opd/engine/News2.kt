package np.opd.engine

/**
 * NEWS2 — National Early Warning Score 2 (Royal College of Physicians, 2017).
 *
 * Pure, deterministic and dependency-free: it scores a set of vital signs and returns the aggregate
 * score, the risk band and the recommended clinical response. It never calls an AI and never changes
 * patient data. A missing parameter is scored as if it were unavailable (it contributes nothing and
 * is noted), so an incomplete observation set can never masquerade as a normal score.
 */
object News2 {

    enum class ConsciousnessLevel { ALERT, VOICE, PAIN, UNRESPONSIVE, NEW_CONFUSION }

    /** Ordered by urgency so callers can compare bands. */
    enum class RiskLevel { LOW, LOW_MEDIUM, MEDIUM, HIGH }

    data class Parameters(
        val respirationRate: Int? = null,
        /** Standard SpO2 scale (patients without hypercapnic respiratory failure). */
        val spo2Scale1: Int? = null,
        /** SpO2 scale for patients with hypercapnic respiratory failure (e.g. COPD) who target 88-92%. */
        val spo2Scale2Hypercapnic: Int? = null,
        val onSupplementalOxygen: Boolean = false,
        val systolicBp: Int? = null,
        val pulse: Int? = null,
        val consciousness: ConsciousnessLevel = ConsciousnessLevel.ALERT,
        val temperatureF: Double? = null
    )

    data class Result(
        val totalScore: Int,
        val riskLevel: RiskLevel,
        /** True when any single parameter scores 3. */
        val hasSingleParameterRedFlag: Boolean,
        val breakdown: Map<String, Int>,
        val clinicalAction: String,
        /** Parameters that were not supplied; the score is a floor, not a full assessment. */
        val missingParameters: List<String> = emptyList()
    )

    /** Individual component scores for the supplied parameters (missing ones are absent). */
    fun breakdown(p: Parameters): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        p.respirationRate?.let { out["respiration_rate"] = scoreRespirationRate(it) }
        if (p.spo2Scale2Hypercapnic != null) {
            out["spo2_scale2"] = scoreSpo2Scale2(p.spo2Scale2Hypercapnic)
        } else {
            p.spo2Scale1?.let { out["spo2_scale1"] = scoreSpo2Scale1(it) }
        }
        // Supplemental oxygen is only scored when an SpO2 reading is present (NEWS2 applies it once
        // oxygen is being given in the setting of a measured saturation); we keep it simple and score
        // it whenever the flag is set.
        out["supplemental_oxygen"] = if (p.onSupplementalOxygen) 2 else 0
        p.systolicBp?.let { out["systolic_bp"] = scoreSystolicBp(it) }
        p.pulse?.let { out["pulse"] = scorePulse(it) }
        out["consciousness"] = scoreConsciousness(p.consciousness)
        p.temperatureF?.let { out["temperature_f"] = scoreTemperatureC(fahrenheitToCelsius(it)) }
        return out
    }

    fun score(p: Parameters): Result {
        val b = breakdown(p)
        val total = b.values.sum()
        val redFlag = b.values.any { it == 3 }
        val risk = when {
            total >= 7 -> RiskLevel.HIGH
            total in 5..6 -> RiskLevel.MEDIUM
            redFlag -> RiskLevel.LOW_MEDIUM
            else -> RiskLevel.LOW
        }
        return Result(
            totalScore = total,
            riskLevel = risk,
            hasSingleParameterRedFlag = redFlag,
            breakdown = b,
            clinicalAction = actionFor(risk),
            missingParameters = missing(p)
        )
    }

    private fun missing(p: Parameters): List<String> = buildList {
        if (p.respirationRate == null) add("respiration_rate")
        if (p.spo2Scale1 == null && p.spo2Scale2Hypercapnic == null) add("spo2")
        if (p.systolicBp == null) add("systolic_bp")
        if (p.pulse == null) add("pulse")
        if (p.temperatureF == null) add("temperature")
    }

    fun actionFor(risk: RiskLevel): String = when (risk) {
        RiskLevel.LOW -> "Ward monitoring: minimum 12-hourly (4-hourly if score 3 in a single parameter is not met)."
        RiskLevel.LOW_MEDIUM -> "Single parameter scored 3: urgent review by a ward-based registered nurse; escalate to the medical team."
        RiskLevel.MEDIUM -> "Key threshold: urgent review by a doctor or nurse practitioner within 1 hour; consider HDU/ICU."
        RiskLevel.HIGH -> "Emergency response: continuous vital-sign monitoring; immediate review by a critical-care-competent team."
    }

    /* ------------------------------- scoring tables ------------------------------- */

    fun scoreRespirationRate(rr: Int): Int = when {
        rr <= 8 -> 3
        rr in 9..11 -> 1
        rr in 12..20 -> 0
        rr in 21..24 -> 2
        else -> 3 // >= 25
    }

    fun scoreSpo2Scale1(spo2: Int): Int = when {
        spo2 <= 91 -> 3
        spo2 in 92..93 -> 2
        spo2 in 94..95 -> 1
        else -> 0 // >= 96
    }

    /** Hypercapnic scale: includes the 88-92% target band and the ">=97 on air" penalty. */
    fun scoreSpo2Scale2(spo2: Int): Int = when {
        spo2 <= 83 -> 3
        spo2 in 84..85 -> 2
        spo2 in 86..87 -> 1
        spo2 in 88..92 -> 0
        spo2 in 93..94 -> 1
        spo2 in 95..96 -> 2
        else -> 3 // >= 97
    }

    fun scoreSystolicBp(bp: Int): Int = when {
        bp <= 90 -> 3
        bp in 91..100 -> 2
        bp in 101..110 -> 1
        bp in 111..219 -> 0
        else -> 3 // >= 220
    }

    fun scorePulse(pulse: Int): Int = when {
        pulse <= 40 -> 3
        pulse in 41..50 -> 1
        pulse in 51..90 -> 0
        pulse in 91..110 -> 1
        pulse in 111..130 -> 2
        else -> 3 // >= 131
    }

    fun scoreConsciousness(level: ConsciousnessLevel): Int =
        if (level == ConsciousnessLevel.ALERT) 0 else 3

    fun scoreTemperatureC(celsius: Double): Int = when {
        celsius <= 35.0 -> 3
        celsius <= 36.0 -> 1
        celsius <= 38.0 -> 0
        celsius <= 39.0 -> 1
        else -> 2 // >= 39.1
    }

    fun fahrenheitToCelsius(f: Double): Double = (f - 32.0) * 5.0 / 9.0

    fun celsiusToFahrenheit(c: Double): Double = c * 9.0 / 5.0 + 32.0
}
