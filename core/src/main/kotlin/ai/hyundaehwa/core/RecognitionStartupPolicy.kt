package ai.hyundaehwa.core

/** A cold on-device model may load after binding. Startup is separate from the answer window. */
object RecognitionStartupPolicy {
    private const val INITIALIZE_TIMEOUT_MS = 5_000L
    private const val CAPTURE_START_TIMEOUT_MS = 20_000L
    fun timeoutMs(waitingForCapture: Boolean, remainingAnswerMs: Long? = null): Long {
        val maximum = if (waitingForCapture) CAPTURE_START_TIMEOUT_MS else INITIALIZE_TIMEOUT_MS
        // Reconnection never gets a fresh 20-second response budget.
        return minOf(maximum, remainingAnswerMs?.coerceAtLeast(0) ?: maximum)
    }
}
