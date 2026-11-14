package ai.hyundaehwa.core

/** A terminated capture may restart only inside its original answer window. */
object RecognitionRetryPolicy {
    fun canRestart(restarts: Int, remainingMs: Long): Boolean = restarts < 2 && remainingMs >= 1_500L
}

/** Pleos EPD values are byte offsets, not clock times; accept only new nonempty ranges. */
class RecognitionSpeechRanges {
    private var lastEnd = -1L
    fun observed(startByte: Long, endByte: Long): Boolean {
        if (startByte < 0 || endByte <= startByte || endByte <= lastEnd) return false
        lastEnd = endByte
        return true
    }
}
