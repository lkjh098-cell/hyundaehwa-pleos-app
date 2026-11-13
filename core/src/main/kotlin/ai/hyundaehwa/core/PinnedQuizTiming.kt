package ai.hyundaehwa.core

/** Caller supplies monotonic foreground elapsed time; simulated driving time is never used. */
object PinnedQuizTiming {
    const val REVEAL_MS = 8_000L
    const val FEEDBACK_MS = 4_000L
    fun answerDisclosure(number: Int, explanation: String, unanswered: Boolean): String =
        (if (unanswered) "아쉽네요. " else "") + "정답은 ${number}번이에요. $explanation"
    fun shouldReveal(now: Long, shownAt: Long, answered: Boolean): Boolean =
        !answered && now >= shownAt && now - shownAt >= REVEAL_MS
    fun shouldAdvance(now: Long, revealedAt: Long, region: String, pinnedRegion: String?): Boolean =
        revealedAt > 0 && now >= revealedAt && now - revealedAt >= FEEDBACK_MS &&
            region.isNotBlank() && region != pinnedRegion
}
