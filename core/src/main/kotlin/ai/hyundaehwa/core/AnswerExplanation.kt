package ai.hyundaehwa.core

/** The pinned card is the only source, even after a GPS boundary change. */
object AnswerExplanation {
    fun text(card: AdministrativeConversationTopic, selectedChoiceId: String?, unanswered: Boolean = false): String? {
        if (card.format != "quiz") return selectedChoiceId?.let { card.replyByChoice[it] }
            ?: if (unanswered) BalancePresentation.unansweredFeedback(card.format) else null
        val index = card.options.indexOfFirst { it.id == card.correctChoiceId }
        if (index < 0 || card.explanation.isBlank()) return null
        val prefix = when {
            unanswered -> "아쉽네요. "
            selectedChoiceId == card.correctChoiceId -> "맞아요. "
            selectedChoiceId != null -> "아쉽네요. "
            else -> ""
        }
        return "${prefix}정답은 ${index + 1}번입니다. ${SpokenChoiceCues.sentence(card.options[index].label)} ${card.explanation}"
    }
}
enum class AnswerSpeechPhase { WAITING, PLAYING, PAUSED, COMPLETED, FAILED, CANCELLED }
data class AnswerSpeech(val id: String, val cardId: String, val text: String, val destination: String,
    val phase: AnswerSpeechPhase = AnswerSpeechPhase.WAITING)
/** Reveal callbacks cannot enqueue a second reading; a retry must be an explicit user action. */
class AnswerSpeechQueue {
    private var sequence = 0L
    var current: AnswerSpeech? = null
        private set
    fun enqueue(cardId: String, text: String?, destination: String): Boolean {
        if (text.isNullOrBlank() || current?.cardId == cardId) return false
        current = AnswerSpeech("answer-${++sequence}-$cardId", cardId, text, destination)
        return true
    }
    fun unfinished(cardId: String?): Boolean = cardId != null && current?.cardId == cardId &&
        current?.phase in setOf(AnswerSpeechPhase.WAITING, AnswerSpeechPhase.PLAYING, AnswerSpeechPhase.PAUSED, AnswerSpeechPhase.FAILED)
    fun start(id: String) { current?.takeIf { it.id == id && it.phase in setOf(AnswerSpeechPhase.WAITING, AnswerSpeechPhase.PAUSED) }?.let { current = it.copy(phase = AnswerSpeechPhase.PLAYING) } }
    fun pause(id: String) { current?.takeIf { it.id == id && it.phase == AnswerSpeechPhase.PLAYING }?.let { current = it.copy(phase = AnswerSpeechPhase.PAUSED) } }
    fun complete(id: String): Boolean {
        val value = current?.takeIf { it.id == id && it.phase == AnswerSpeechPhase.PLAYING } ?: return false
        current = value.copy(phase = AnswerSpeechPhase.COMPLETED); return true
    }
    fun fail(id: String) { current?.takeIf { it.id == id && unfinished(it.cardId) }?.let { current = it.copy(phase = AnswerSpeechPhase.FAILED) } }
    fun retry(): Boolean {
        val value = current ?: return false
        if (value.phase in setOf(AnswerSpeechPhase.WAITING, AnswerSpeechPhase.PLAYING, AnswerSpeechPhase.PAUSED)) return false
        current = value.copy(id = "answer-${++sequence}-${value.cardId}", phase = AnswerSpeechPhase.WAITING)
        return true
    }
    fun cancel() { current?.takeIf { it.phase != AnswerSpeechPhase.COMPLETED }?.let { current = it.copy(phase = AnswerSpeechPhase.CANCELLED) } }
    fun reset() { current = null }
}
