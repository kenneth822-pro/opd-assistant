package np.opd.engine

/**
 * Evaluates catalogue rules against vitals and demographics.
 * Returns true / false, or null when a needed value is unknown (unknown is never treated as "normal").
 */
object RuleEvaluator {

    fun valueOf(field: String, v: Vitals, d: Demographics): Double? =
        if (field == "age") d.age?.toDouble() else v.value(field)

    fun evaluate(rule: Rule, v: Vitals, d: Demographics): Boolean? {
        if (rule.compositeOp != null) {
            val results = rule.rules.map { evaluate(it, v, d) }
            return if (rule.compositeOp == "OR") {
                when {
                    results.any { it == true } -> true
                    results.any { it == null } -> null
                    else -> false
                }
            } else {
                when {
                    results.any { it == false } -> false
                    results.any { it == null } -> null
                    else -> true
                }
            }
        }
        val actual = valueOf(rule.field ?: return null, v, d) ?: return null
        val target = rule.value ?: return null
        return when (rule.op) {
            ">=" -> actual >= target
            "<=" -> actual <= target
            ">" -> actual > target
            "<" -> actual < target
            "==" -> actual == target
            else -> null
        }
    }
}
