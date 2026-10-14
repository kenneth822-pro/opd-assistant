package np.opd.engine

/**
 * Minimal JSON model, parser and writer.
 *
 * The engine has no third-party dependencies on purpose: it must compile and run its
 * tests anywhere (Android app, JVM test harness, CI) and stay easy to audit.
 */
sealed class J {
    object Null : J()
    data class Bool(val v: Boolean) : J()
    data class Num(val v: Double) : J()
    data class Str(val v: String) : J()
    data class Arr(val items: List<J>) : J()
    data class Obj(val fields: Map<String, J>) : J() {
        operator fun get(key: String): J? = fields[key]
        fun str(key: String): String? = when (val x = fields[key]) {
            is Str -> x.v
            is Num -> if (x.v == Math.floor(x.v)) x.v.toLong().toString() else x.v.toString()
            is Bool -> x.v.toString()
            else -> null
        }
        fun strOr(key: String, default: String): String = str(key)?.takeIf { it.isNotBlank() } ?: default
        fun req(key: String): String = str(key) ?: throw JsonException("Missing field '$key'")
        fun dbl(key: String): Double? = when (val x = fields[key]) {
            is Num -> x.v
            is Str -> x.v.trim().toDoubleOrNull()
            else -> null
        }
        fun int(key: String): Int? = dbl(key)?.let { Math.round(it).toInt() }
        fun bool(key: String): Boolean? = when (val x = fields[key]) {
            is Bool -> x.v
            is Str -> when (x.v.trim().lowercase()) { "true" -> true; "false" -> false; else -> null }
            else -> null
        }
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun arr(key: String): List<J> = (fields[key] as? Arr)?.items ?: emptyList()
        fun objs(key: String): List<Obj> = arr(key).mapNotNull { it as? Obj }
        fun strs(key: String): List<String> = arr(key).mapNotNull { (it as? Str)?.v }
    }

    fun toJson(): String = StringBuilder().also { write(this, it) }.toString()

    companion object {
        fun parse(text: String): J = Parser(stripFences(text)).parseRoot()

        /** Models sometimes wrap JSON in ```json fences; tolerate that. */
        fun stripFences(text: String): String {
            var t = text.trim()
            if (t.startsWith("```")) {
                t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                val end = t.lastIndexOf("```")
                if (end >= 0) t = t.substring(0, end)
            }
            return t.trim()
        }

        /** Convert plain Kotlin values (maps, lists, strings, numbers, booleans, null, J) to J. */
        fun of(value: Any?): J = when (value) {
            null -> Null
            is J -> value
            is String -> Str(value)
            is Boolean -> Bool(value)
            is Number -> Num(value.toDouble())
            is Enum<*> -> Str(value.name)
            is Map<*, *> -> Obj(LinkedHashMap<String, J>().also { m ->
                value.forEach { (k, v) -> if (v != null) m[k.toString()] = of(v) }
            })
            is Iterable<*> -> Arr(value.map { of(it) })
            is Array<*> -> Arr(value.map { of(it) })
            else -> Str(value.toString())
        }

        fun obj(vararg pairs: Pair<String, Any?>): Obj =
            Obj(LinkedHashMap<String, J>().also { m -> pairs.forEach { (k, v) -> if (v != null) m[k] = of(v) } })

        private fun write(v: J, sb: StringBuilder) {
            when (v) {
                is Null -> sb.append("null")
                is Bool -> sb.append(if (v.v) "true" else "false")
                is Num -> {
                    val d = v.v
                    if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) sb.append(d.toLong())
                    else sb.append(d)
                }
                is Str -> writeString(v.v, sb)
                is Arr -> {
                    sb.append('[')
                    v.items.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(x, sb) }
                    sb.append(']')
                }
                is Obj -> {
                    sb.append('{')
                    var first = true
                    for ((k, x) in v.fields) {
                        if (!first) sb.append(',')
                        first = false
                        writeString(k, sb); sb.append(':'); write(x, sb)
                    }
                    sb.append('}')
                }
            }
        }

        private fun writeString(s: String, sb: StringBuilder) {
            sb.append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
                }
            }
            sb.append('"')
        }
    }
}

class JsonException(message: String) : RuntimeException(message)

private class Parser(private val s: String) {
    private var i = 0

    fun parseRoot(): J {
        skipWs()
        val v = value()
        skipWs()
        if (i != s.length) throw JsonException("Unexpected trailing content at $i")
        return v
    }

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): J {
        skipWs()
        if (i >= s.length) throw JsonException("Unexpected end of JSON")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> J.Str(str())
            't' -> literal("true", J.Bool(true))
            'f' -> literal("false", J.Bool(false))
            'n' -> literal("null", J.Null)
            else -> if (c == '-' || c.isDigit()) num() else throw JsonException("Unexpected '$c' at $i")
        }
    }

    private fun literal(word: String, v: J): J {
        if (!s.startsWith(word, i)) throw JsonException("Expected $word at $i")
        i += word.length
        return v
    }

    private fun obj(): J.Obj {
        i++ // {
        val m = LinkedHashMap<String, J>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return J.Obj(m) }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') throw JsonException("Expected key at $i")
            val k = str()
            skipWs()
            if (i >= s.length || s[i] != ':') throw JsonException("Expected ':' at $i")
            i++
            m[k] = value()
            skipWs()
            if (i >= s.length) throw JsonException("Unterminated object")
            when (s[i]) {
                ',' -> { i++; skipWs(); if (i < s.length && s[i] == '}') { i++; return J.Obj(m) } }
                '}' -> { i++; return J.Obj(m) }
                else -> throw JsonException("Expected ',' or '}' at $i")
            }
        }
    }

    private fun arr(): J.Arr {
        i++ // [
        val items = ArrayList<J>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return J.Arr(items) }
        while (true) {
            items.add(value())
            skipWs()
            if (i >= s.length) throw JsonException("Unterminated array")
            when (s[i]) {
                ',' -> { i++; skipWs(); if (i < s.length && s[i] == ']') { i++; return J.Arr(items) } }
                ']' -> { i++; return J.Arr(items) }
                else -> throw JsonException("Expected ',' or ']' at $i")
            }
        }
    }

    private fun str(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) throw JsonException("Bad escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw JsonException("Bad unicode escape")
                            sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                        }
                        else -> throw JsonException("Bad escape \\$e")
                    }
                }
                else -> sb.append(c)
            }
        }
        throw JsonException("Unterminated string")
    }

    private fun num(): J.Num {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        val raw = s.substring(start, i)
        return J.Num(raw.toDoubleOrNull() ?: throw JsonException("Bad number '$raw' at $start"))
    }
}
