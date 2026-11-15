package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class RecognitionStartupPolicyTest {
    @Test fun observedColdLoadFitsStartupBudgetAndGetsAFullAnswerWindowAfterReady() {
        val requestAt = 0L
        val actualReadyAt = 10_314L // Observed cold model load exceeded the old 10-second limit.
        val startupExpiresAt = requestAt + RecognitionStartupPolicy.timeoutMs(true)
        assertTrue(actualReadyAt < startupExpiresAt)
        assertEquals(20_000L, startupExpiresAt)
        // No answer time is spent loading; the production input creates this only on Ready.
        val answer = CardAnswerVoiceDeadline(actualReadyAt)
        assertEquals(8_000L, answer.remainingMs(actualReadyAt))
        assertEquals(18_314L, answer.deadlineAt())
    }
    @Test fun reconnectCannotSpendColdStartAllowanceBeyondTheOriginalAnswerDeadline() {
        val answer = CardAnswerVoiceDeadline(1_000)
        val now = 6_500L
        val remaining = answer.remainingMs(now)
        assertEquals(2_500L, RecognitionStartupPolicy.timeoutMs(true, remaining))
        assertEquals(2_500L, RecognitionStartupPolicy.timeoutMs(false, remaining))
        assertEquals(9_000L, answer.deadlineAt())
        assertEquals(0L, RecognitionStartupPolicy.timeoutMs(true, answer.remainingMs(9_000)))
    }
    @Test fun absentServiceIsStillBoundedSeparatelyFromColdModelStartup() {
        assertEquals(5_000L, RecognitionStartupPolicy.timeoutMs(false))
        assertEquals(0L, RecognitionStartupPolicy.timeoutMs(true, -1))
        assertEquals(20_000L, RecognitionStartupPolicy.timeoutMs(true, 50_000))
    }
}
