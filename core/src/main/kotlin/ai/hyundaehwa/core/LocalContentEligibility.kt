package ai.hyundaehwa.core

/** Strict shared-bank gate; administrative labels never widen an explicit code scope. */
object LocalContentEligibility {
    const val BALANCE_SOURCE = "editorial-local-experience-preference"
    fun exactCode(ruleCode: String, areaCode: String): Boolean =
        ruleCode.isNotBlank() && areaCode.isNotBlank() && ruleCode == areaCode
    fun accepts(format: String, sourceKind: String, reviewed: Boolean, verified: Boolean): Boolean =
        reviewed && when (format) {
            "quiz" -> verified && sourceKind == "official-place-fact"
            "balance" -> sourceKind == BALANCE_SOURCE
            else -> false
        }
}
