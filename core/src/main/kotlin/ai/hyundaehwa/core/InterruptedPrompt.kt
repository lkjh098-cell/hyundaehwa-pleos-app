package ai.hyundaehwa.core

/** The current sentence may repeat after an interruption; no unsupported word offset is inferred. */
object PromptSentences {
    fun forSpeech(text: String, maxChars: Int = 300): List<String> {
        require(maxChars >= 32)
        return split(text).flatMap { sentence ->
            val parts = mutableListOf<String>(); var rest = sentence
            while (rest.length > maxChars) {
                val boundary = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
                parts += rest.substring(0, boundary).trim(); rest = rest.substring(boundary).trimStart()
            }
            if (rest.isNotBlank()) parts += rest
            parts
        }
    }
    fun timeoutMs(text: String): Long = maxOf(90_000L, text.length * 700L + 15_000L)
    fun split(text: String): List<String> = text.split(Regex("(?<=[.!?。！？])\\s+|\\n+"))
        .map { it.trim() }.filter { it.isNotBlank() }
}
data class InterruptedPrompt(val utterance: Utterance, val remainingText: String, val destination: String, val cardId: String?)
data class PromptResumeConditions(
    val resumed: Boolean, val voiceEnabled: Boolean, val localAudioReady: Boolean,
    val otherVoiceActive: Boolean, val navigationVoiceActive: Boolean, val audioFocusBlocked: Boolean = false
)
/** A checkpoint belongs to this trip and displayed card, never to a region-change replacement. */
class PromptResumeQueue {
    var pending: InterruptedPrompt? = null
        private set
    var failureHeld: Boolean = false
        private set
    fun remember(utterance: Utterance, remainingText: String, destination: String, cardId: String?) {
        if (remainingText.isNotBlank()) { pending = InterruptedPrompt(utterance, remainingText, destination, cardId); failureHeld = false }
    }
    fun holdAfterFailure(id: String) { if (pending?.utterance?.id == id) failureHeld = true }
    fun retryAfterFailure(id: String): Boolean {
        if (!failureHeld || pending?.utterance?.id != id) return false
        failureHeld = false; return true
    }
    fun clear() { pending = null; failureHeld = false }
    fun holdsCard(cardId: String?): Boolean = cardId != null && pending?.utterance?.topicId == cardId
    fun discardStale(state: ConversationState, destination: String, cardId: String?) {
        val saved = pending ?: return
        val completingAnswer = saved.utterance.kind == "answer" && state.answerExplanationHeld
        if (!state.enabled || state.quiet || (state.arrived && !completingAnswer) || saved.destination != destination || saved.cardId != cardId) clear()
    }
    fun takeIfReady(state: ConversationState, destination: String, cardId: String?, conditions: PromptResumeConditions): InterruptedPrompt? {
        discardStale(state, destination, cardId)
        if (!conditions.resumed || !conditions.voiceEnabled) { clear(); return null }
        val saved = pending ?: return null
        if (failureHeld) return null
        if (conditions.otherVoiceActive ||
            conditions.navigationVoiceActive || conditions.audioFocusBlocked || state.guards.isNotEmpty() || !HyundaehwaEngine.audioClear(state) || state.speechActive ||
            (state.output != null && state.output.id != saved.utterance.id)) return null
        clear()
        return saved
    }
}
