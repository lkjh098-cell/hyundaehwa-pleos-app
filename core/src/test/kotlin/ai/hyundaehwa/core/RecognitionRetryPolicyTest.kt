package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class RecognitionRetryPolicyTest {
    @Test fun epdByteOffsetsAcceptZeroStartAndRejectDuplicateOrInvalidRanges() {
        val ranges = RecognitionSpeechRanges()
        assertFalse(ranges.observed(0, 0))
        assertFalse(ranges.observed(-1, 100))
        assertTrue(ranges.observed(0, 3200))
        assertFalse(ranges.observed(0, 3200))
        assertFalse(ranges.observed(0, 1600))
        assertTrue(ranges.observed(3200, 6400))
    }

    @Test fun terminalNoMatchCanReopenOnlyWithinOriginalWindow() {
        val deadline = CardAnswerVoiceDeadline(0)
        assertTrue(RecognitionRetryPolicy.canRestart(0, deadline.remainingMs(2_000)))
        assertTrue(RecognitionRetryPolicy.canRestart(1, deadline.remainingMs(4_000)))
        assertFalse(RecognitionRetryPolicy.canRestart(2, deadline.remainingMs(4_000)))
        assertFalse(RecognitionRetryPolicy.canRestart(0, deadline.remainingMs(7_000)))
        assertFalse(RecognitionRetryPolicy.canRestart(0, deadline.remainingMs(8_000)))
        assertEquals(8_000L, deadline.deadlineAt())
    }
    @Test fun reconnectIsVisibleAndNeverResetsSessionTokenOrTranscript() {
        var state = CardAnswerVoiceSession.reduce(CardAnswerVoiceState(), CardAnswerVoiceEvent.Begin(7, "card"))
        state = CardAnswerVoiceSession.reduce(state, CardAnswerVoiceEvent.Ready(7, "backend"))
        state = CardAnswerVoiceSession.reduce(state, CardAnswerVoiceEvent.Partial(7, "두 번째"))
        val reconnecting = CardAnswerVoiceSession.reduce(state, CardAnswerVoiceEvent.Reconnecting(7))
        assertEquals(CardAnswerVoicePhase.PREPARING, reconnecting.phase)
        assertEquals("두 번째", reconnecting.transcript)
        assertEquals(7L, reconnecting.token)
        assertEquals(reconnecting, CardAnswerVoiceSession.reduce(reconnecting, CardAnswerVoiceEvent.Ready(6, "old")))
        val resumed = CardAnswerVoiceSession.reduce(reconnecting, CardAnswerVoiceEvent.Ready(7, "new"))
        assertEquals(CardAnswerVoicePhase.LISTENING, resumed.phase)
    }
}
