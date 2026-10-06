package np.opd.engine

/**
 * On-device hints from the transcript, using the keyword lists in the catalogues.
 * Works instantly and offline, and keeps giving guidance when an AI call fails.
 *
 * Hints are SUGGESTIONS for the doctor to confirm with one tap; they never change
 * the case state by themselves.
 */
object LocalAssist {

    data class Mention(val id: String, val keyword: String, val line: String)
    data class ConditionHint(val code: String, val matched: List<String>)

    data class Hints(
        val conditions: List<ConditionHint> = emptyList(),
        val flagMentions: List<Mention> = emptyList(),
        val deniedFlags: List<Mention> = emptyList(),
        val ageGuess: Int? = null,
        val genderGuess: Gender? = null,
        val genderEvidence: List<String> = emptyList()
    )

    private val devanagariDigits = "०१२३४५६७८९"
    private val postpositions = listOf("लाई", "को", "की", "ले", "सँग", "हरू", "जी", "मा", "बाट")
    private val nepaliNegation = Regex("छैन|भएन|होइन|हैन|नाइँ|[\\u0900-\\u097F]दैन|\\sन(?:भ|आ|हु|गर|दे|सक|ला)")
    private val englishNegation = Regex("\\b(no|not|never|without|denies|deny|don't|dont|doesn't|didn't|isn't|wasn't|nothing|any)\\b")
    private val clauseEnd = charArrayOf(',', '।', '?', '!', '.', ';')
    private val rxCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private val ageRegexes = listOf(
        Regex("(\\d{1,3})\\s*(?:वर्ष|बर्ष|बर्स|वर्स|साल)\\s*(?:को|की|पुगें|पुगेँ|पुग्यो|पुगे|लाग्यो)"),
        Regex("(?:उमेर|age|aged)\\D{0,12}?(\\d{1,3})"),
        Regex("(\\d{1,3})\\s*(?:years?|yrs?)[ -]?old"),
        Regex("(\\d{1,3})\\s*(?:y/o|yo)\\b")
    )

    private val femaleCues = listOf("दिदी", "बहिनी", "आमा", "आमै", "हजुरआमा", "बज्यै", "श्रीमान", "महिनावारी", "गर्भवती",
        "madam", "ma'am", "she", "her", "pregnant", "periods", "menstruation")
    private val maleCues = listOf("दाइ", "दाजु", "भाइ", "बुबा", "बाबा", "हजुरबुबा", "बाजे", "श्रीमती", "sir", "he", "his", "mister")

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            val i = devanagariDigits.indexOf(ch)
            sb.append(if (i >= 0) ('0' + i) else ch)
        }
        return sb.toString().replace(Regex("\\s+"), " ")
    }

    private fun isDevanagariLetter(ch: Char) = ch in 'ऀ'..'ॿ' && ch !in '।'..'९'
    private fun isAscii(s: String) = s.all { it.code < 128 }

    /** Positions where [keyword] occurs as a word (or word stem) in [text]. */
    fun occurrences(text: String, keyword: String, wholeWord: Boolean = false): List<IntRange> {
        if (keyword.isBlank()) return emptyList()
        if (isAscii(keyword)) {
            val tail = if (wholeWord) "(?![a-z])" else ""
            val rx = rxCache.getOrPut(keyword + tail) { Regex("(?<![a-z])" + Regex.escape(keyword) + tail) }
            return rx.findAll(text).map { it.range }.toList()
        }
        val out = mutableListOf<IntRange>()
        var from = 0
        val strict = wholeWord || keyword.length <= 3
        while (true) {
            val i = text.indexOf(keyword, from)
            if (i < 0) break
            val end = i + keyword.length
            var ok = true
            if (strict) {
                // A word may end with a halant (्) before its postposition, e.g. "श्रीमान्ले".
                val e = if (end < text.length && text[end] == '्') end + 1 else end
                if (i > 0 && isDevanagariLetter(text[i - 1])) ok = false
                if (e < text.length && isDevanagariLetter(text[e]) && postpositions.none { text.startsWith(it, e) }) ok = false
            }
            if (ok) out += i until end
            from = i + 1
        }
        return out
    }

    fun isNegated(text: String, range: IntRange): Boolean {
        val afterEnd = minOf(text.length, range.last + 1 + 28)
        var after = text.substring(range.last + 1, afterEnd)
        val cut = after.indexOfAny(clauseEnd)
        if (cut >= 0) after = after.substring(0, cut)
        if (nepaliNegation.containsMatchIn(after)) return true
        val before = text.substring(maxOf(0, range.first - 24), range.first)
        return englishNegation.containsMatchIn(before)
    }

    fun analyze(lines: List<TranscriptLine>, c: Catalogues): Hints {
        if (lines.isEmpty()) return Hints()
        val normalized = lines.map { it.speaker to normalize(it.text) }

        // Conditions: any speaker, affirmed mentions only
        val condHits = LinkedHashMap<String, MutableSet<String>>()
        for ((_, text) in normalized) {
            for (cond in c.conditions.values) for (k in cond.keywords) {
                if (occurrences(text, k).any { !isNegated(text, it) }) condHits.getOrPut(cond.code) { linkedSetOf() } += k
            }
        }
        val conditions = condHits.entries
            .sortedByDescending { it.value.size }
            .map { ConditionHint(it.key, it.value.toList()) }

        // Red flags: patient / attendant / unknown speakers (the doctor's questions are not mentions)
        val affirmed = LinkedHashMap<String, Mention>()
        val denied = LinkedHashMap<String, Mention>()
        for ((idx, pair) in normalized.withIndex()) {
            val (speaker, text) = pair
            if (speaker == "doctor") continue
            for (f in c.redFlags.values) for (k in f.keywords) {
                for (r in occurrences(text, k)) {
                    val m = Mention(f.id, k, lines[idx].text)
                    if (isNegated(text, r)) denied[f.id] = m else affirmed[f.id] = m
                }
            }
        }
        affirmed.keys.forEach { denied.remove(it) }

        // Age: last clearly stated age
        var age: Int? = null
        for ((_, text) in normalized) for (rx in ageRegexes) {
            rx.findAll(text).forEach { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 1..110 }?.let { age = it } }
        }

        // Sex: forms of address and pronouns; reported only when clearly one-sided
        val fEvidence = mutableListOf<String>()
        val mEvidence = mutableListOf<String>()
        for ((_, text) in normalized) {
            femaleCues.forEach { k -> if (occurrences(text, k, wholeWord = true).isNotEmpty()) fEvidence += k }
            maleCues.forEach { k -> if (occurrences(text, k, wholeWord = true).isNotEmpty()) mEvidence += k }
        }
        val f = fEvidence.size
        val mCount = mEvidence.size
        val gender = when {
            f >= 2 && f >= 2 * mCount -> Gender.FEMALE
            mCount >= 2 && mCount >= 2 * f -> Gender.MALE
            else -> null
        }
        val evidence = when (gender) {
            Gender.FEMALE -> fEvidence.distinct()
            Gender.MALE -> mEvidence.distinct()
            else -> emptyList()
        }
        return Hints(conditions, affirmed.values.toList(), denied.values.toList(), age, gender, evidence)
    }
}
