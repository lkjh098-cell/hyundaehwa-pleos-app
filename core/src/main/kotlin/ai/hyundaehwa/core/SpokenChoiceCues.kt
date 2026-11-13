package ai.hyundaehwa.core

/** Shared exact sentence boundaries keep shuffled choices compatible with local recordings. */
object SpokenChoiceCues {
    fun sentence(text: String): String = text.trim().let { if (it.lastOrNull() in listOf('.', '!', '?', '。', '！', '？')) it else "$it." }
    fun options(options: List<AdministrativeConversationOption>): String = options.mapIndexed { index, option ->
        "${index + 1}번. ${sentence(option.label)}"
    }.joinToString(" ")
}
