package ai.hyundaehwa.core

/** Only one unambiguous answer from the displayed options may mark a card as answered. */
object CardAnswer {
    private fun normalized(value: String) = value.lowercase().replace(Regex("[^가-힣a-z0-9]"), "")
    private val ending = "(?:이요|예요|이에요|이죠|같아요|같은데요|할게요|으로|로|요|이야|이거|아닌가|말고|인|을|가|$|\\s|[.!?])"
    private val numbers = listOf(listOf("1", "일", "첫"), listOf("2", "이", "두"), listOf("3", "삼", "세"), listOf("4", "사", "네"))
    /** Actual spoken wording takes precedence; a timeout/reveal alone never invents a user turn. */
    fun userMessage(options: List<AdministrativeConversationOption>, selectedChoiceId: String?, heard: String?): String? {
        heard?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val index = options.indexOfFirst { it.id == selectedChoiceId }
        if (index < 0) return null
        return "${index + 1}번 ${options[index].label}"
    }
    fun match(answer: String, options: List<AdministrativeConversationOption>): AdministrativeConversationOption? {
        if (answer.isBlank() || answer.length > 120) return null
        val text = normalized(answer)
        if (Regex("(?:(?:에는?|으?로)?안(?:갈|가|먹|할|해)|아니에요|아니예요|아닌것|말고|하지않|싫어|안할|안먹|안해)").containsMatchIn(text)) return null
        val hits = options.filterIndexed { index, choice ->
            val labels = (choice.keywords + choice.label).map(::normalized).filter { it.length >= 2 }
            val numeric = numbers.getOrNull(index).orEmpty().any { n ->
                val ordinal = if (n.all { it.isDigit() }) "(?:번(?:째)?)?" else "번(?:째)?"
                val tail = if (n == "이") "(?:$|이요|이에요|예요|같아요|같은데요|으로|요|이야|인)" else ending
                val start = if (n.all { it.isDigit() }) "(?<![0-9])" else "(?:^|\\s)"
                Regex(start + n + "\\s*" + ordinal + "(?=" + tail + ")").containsMatchIn(answer.trim())
            }
            numeric || labels.any { text.contains(it) } || text == normalized(choice.label)
        }
        return hits.singleOrNull()
    }
}
