package ai.hyundaehwa.core

/** A balance round starts with its name and goes straight to the question. */
object BalancePresentation {
    const val TITLE = "밸런스 게임!"
    fun questionBody(text: String): String = text.replace(
        Regex("^(?:\\s*(?:정답이 없는 질문이에요[~!！.。]*|밸런스\\s*게임[~!！.。]*))+"), "").trim()
    fun spokenPrompt(question: String, options: List<AdministrativeConversationOption>): String =
        "$TITLE ${questionBody(question)} ${SpokenChoiceCues.options(options)}"
    fun unansweredFeedback(format: String): String? =
        if (format == "balance") null else "정답이 없는 질문이에요. 편하게 이야기 나누세요."
}
