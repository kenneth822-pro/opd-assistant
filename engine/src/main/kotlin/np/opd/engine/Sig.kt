package np.opd.engine

/**
 * Builds prescription instructions from structured fields only.
 * The AI never writes patient instructions; every word below comes from fixed templates.
 */
object Sig {

    data class Slot(val labelNe: String, val icon: String, val count: Double)

    private val devanagari = charArrayOf('०', '१', '२', '३', '४', '५', '६', '७', '८', '९')

    fun toDevanagari(s: String): String = s.map { ch -> if (ch in '0'..'9') devanagari[ch - '0'] else ch }.joinToString("")

    private fun countEn(x: Double): String = when {
        x == 0.5 -> "half"
        x == Math.floor(x) -> x.toLong().toString()
        else -> x.toString()
    }

    fun countNe(x: Double): String = when {
        x == 0.5 -> "आधा"
        x == 1.5 -> "डेढ"
        x == Math.floor(x) -> toDevanagari(x.toLong().toString())
        else -> toDevanagari(x.toString())
    }

    private fun unitEn(form: String, n: Double): String {
        val plural = n > 1.0
        return when (form.uppercase()) {
            "TAB" -> if (plural) "tablets" else "tablet"
            "CAP" -> if (plural) "capsules" else "capsule"
            "INHALER" -> if (plural) "puffs" else "puff"
            "SACHET" -> if (plural) "sachets" else "sachet"
            "DROP" -> if (plural) "drops" else "drop"
            else -> "dose"
        }
    }

    private fun unitNe(form: String): String = when (form.uppercase()) {
        "TAB" -> "चक्की"
        "CAP" -> "क्याप्सुल"
        "INHALER" -> "पफ"
        "SACHET" -> "प्याकेट"
        "DROP" -> "थोपा"
        else -> "मात्रा"
    }

    private fun verbEn(route: String, form: String): String = if (form.uppercase() == "SACHET") "Dissolve and drink" else when (route.uppercase()) {
        "INHALATION" -> "Inhale"
        "TOPICAL" -> "Apply"
        "EYE" -> "Instil"
        else -> "Take"
    }

    private fun verbNe(route: String, form: String): String = if (form.uppercase() == "SACHET") "पानीमा घोलेर पिउने" else when (route.uppercase()) {
        "INHALATION" -> "तान्ने"
        "TOPICAL" -> "लगाउने"
        "EYE" -> "हाल्ने"
        else -> "खाने"
    }

    private fun timingEn(t: String): String? = when (t.uppercase()) {
        "AC" -> "30 minutes before food"
        "PC" -> "after food"
        "HS" -> "at bedtime"
        else -> null
    }

    private fun timingNe(t: String): String? = when (t.uppercase()) {
        "AC" -> "खाना खानुभन्दा ३० मिनेट अगाडि"
        "PC" -> "खाना खाएपछि"
        "HS" -> "राति सुत्ने बेलामा"
        else -> null
    }

    private fun prnEn(code: String?): String = when (code) {
        "PAIN" -> "when in pain"
        "FEVER" -> "when there is fever"
        "BREATHLESS" -> "when short of breath"
        "LOOSE_STOOL" -> "after each loose stool"
        else -> "when needed"
    }

    private fun prnNe(code: String?): String = when (code) {
        "PAIN" -> "दुखेको बेला मात्र"
        "FEVER" -> "ज्वरो आएको बेला मात्र"
        "BREATHLESS" -> "सास फेर्न गाह्रो भएको बेला मात्र"
        "LOOSE_STOOL" -> "हरेक पटक पातलो दिसा भएपछि"
        else -> "आवश्यक परेको बेला मात्र"
    }

    private fun freqEn(f: String): String = when (f.uppercase()) {
        "OD" -> "once a day"
        "BD" -> "twice a day (morning and evening)"
        "TDS" -> "3 times a day (morning, afternoon, evening)"
        "QID" -> "4 times a day"
        "HS" -> "once a day"
        "STAT" -> "once, now"
        else -> f
    }

    private fun freqNe(f: String): String = when (f.uppercase()) {
        "OD", "HS" -> "दिनमा १ पटक"
        "BD" -> "दिनमा २ पटक (बिहान र बेलुका)"
        "TDS" -> "दिनमा ३ पटक (बिहान, दिउँसो र बेलुका)"
        "QID" -> "दिनमा ४ पटक"
        "STAT" -> "अहिले १ पटक मात्र"
        else -> f
    }

    fun english(comp: RegimenComponent, drug: DrugDef): String {
        val n = comp.doseCount
        val dose = "${countEn(n)} ${unitEn(drug.form, n)}"
        val parts = mutableListOf<String>()
        if (comp.frequency.uppercase() == "PRN") {
            parts += "${verbEn(comp.route, drug.form)} $dose ${prnEn(comp.prnIndication)}"
            timingEn(comp.timing)?.let { parts += it }
            if (comp.prnIndication != "LOOSE_STOOL") comp.maxDosesPerDay?.let { parts += "no more than $it times a day" }
        } else {
            parts += "${verbEn(comp.route, drug.form)} $dose ${freqEn(comp.frequency)}"
            timingEn(comp.timing)?.let { parts += it }
        }
        comp.durationDays?.let { parts += "for $it days" }
        val main = parts.joinToString(", ") + "."
        return if (comp.noteEn != null) "$main ${comp.noteEn}." else main
    }

    fun nepali(comp: RegimenComponent, drug: DrugDef): String {
        val dose = "${countNe(comp.doseCount)} ${unitNe(drug.form)}"
        val verb = verbNe(comp.route, drug.form)
        val parts = mutableListOf<String>()
        if (comp.frequency.uppercase() == "PRN") {
            parts += prnNe(comp.prnIndication)
            timingNe(comp.timing)?.let { parts += it }
            parts += "एक पटकमा $dose $verb"
            if (comp.prnIndication != "LOOSE_STOOL") comp.maxDosesPerDay?.let { parts += "दिनमा ${toDevanagari(it.toString())} पटकभन्दा बढी नखाने" }
            comp.durationDays?.let { parts += "${toDevanagari(it.toString())} दिनसम्म" }
        } else {
            timingNe(comp.timing)?.let { parts += it }
            parts += "एक पटकमा $dose"
            parts += freqNe(comp.frequency)
            parts += (comp.durationDays?.let { "${toDevanagari(it.toString())} दिनसम्म " } ?: "") + verb
        }
        val main = parts.joinToString(", ") + "।"
        return if (comp.noteNe != null) "$main ${comp.noteNe}।" else main
    }

    /** Morning / midday / evening / night slots for low-literacy pictograms. Empty list for PRN/STAT. */
    fun pictogram(comp: RegimenComponent): List<Slot> {
        val n = comp.doseCount
        val m = Slot("बिहान", "☀", n)
        val d = Slot("दिउँसो", "◐", n)
        val e = Slot("बेलुका", "◑", n)
        val night = Slot("राति", "☾", n)
        return when (comp.frequency.uppercase()) {
            "OD" -> if (comp.timing.uppercase() == "HS") listOf(night) else listOf(m)
            "HS" -> listOf(night)
            "BD" -> listOf(m, e)
            "TDS" -> listOf(m, d, e)
            "QID" -> listOf(m, d, e, night)
            else -> emptyList()
        }
    }

    fun pictogramText(comp: RegimenComponent): String {
        val slots = pictogram(comp)
        if (slots.isEmpty()) return if (comp.frequency.uppercase() == "PRN") "⚠ " + prnNe(comp.prnIndication) else ""
        return slots.joinToString("   ") { "${it.icon} ${it.labelNe}: ${countNe(it.count)}" }
    }

    /** Short pharmacist line, e.g. "Tab Pantoprazole 40 mg - 1 OD AC x 14 days". */
    fun pharmacyLine(comp: RegimenComponent, drug: DrugDef): String {
        val form = when (drug.form.uppercase()) { "TAB" -> "Tab"; "CAP" -> "Cap"; "INHALER" -> "Inh"; "SACHET" -> "Sachet"; else -> drug.form }
        val timing = if (comp.timing.uppercase() == "ANY") "" else " ${comp.timing.uppercase()}"
        val dur = comp.durationDays?.let { " x $it days" } ?: ""
        return "$form ${drug.generic} ${drug.strengthText()} - ${countEn(comp.doseCount)} ${comp.frequency.uppercase()}$timing$dur"
    }
}
