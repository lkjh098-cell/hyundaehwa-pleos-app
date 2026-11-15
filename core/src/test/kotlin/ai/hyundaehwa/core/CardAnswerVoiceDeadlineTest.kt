package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class CardAnswerVoiceDeadlineTest {
    @Test fun silenceStillEndsAtEightRealSeconds() {
        val deadline = CardAnswerVoiceDeadline(1000)
        assertEquals(9000L, deadline.deadlineAt()); assertEquals(1L, deadline.remainingMs(8999)); assertEquals(0L, deadline.remainingMs(9000))
        deadline.partial(" ", 8500); assertEquals(9000L, deadline.deadlineAt())
    }
    @Test fun answerBeginningNearEightSecondsGetsTimeForActualFinalResult() {
        val deadline = CardAnswerVoiceDeadline(0)
        deadline.speechStarted(7000)
        assertEquals(11000L, deadline.deadlineAt()); assertTrue(deadline.remainingMs(8000) > 0)
        deadline.partial("두 번째가", 8500)
        assertEquals(12500L, deadline.deadlineAt()); assertTrue(deadline.remainingMs(10000) > 0)
    }
    @Test fun partialKeepsWaitingForFinalInsteadOfInventingAnAnswer() {
        val card = AdministrativeConversationTopic("q", "quiz", "제목", "질문", listOf(
            AdministrativeConversationOption("a", "첫 보기"), AdministrativeConversationOption("b", "둘째 보기")))
        var state = CardAnswerVoiceSession.reduce(CardAnswerVoiceState(), CardAnswerVoiceEvent.Begin(1, card.id))
        state = CardAnswerVoiceSession.reduce(state, CardAnswerVoiceEvent.Ready(1, "기기 내 음성"))
        val deadline = CardAnswerVoiceDeadline(0); deadline.partial("2번이 정답", 7900)
        state = CardAnswerVoiceSession.reduce(state, CardAnswerVoiceEvent.Partial(1, "2번이 정답"))
        assertTrue(state.active); assertNull(state.answer); assertTrue(deadline.remainingMs(8000) > 0)
        state = CardAnswerVoiceSession.reduce(state, CardAnswerVoiceEvent.Final(1, "2번이 정답이야", card))
        assertEquals(CardAnswerVoicePhase.RESOLVED, state.phase); assertEquals("b", state.answer?.choiceId)
    }
    @Test fun continuousOrRepeatedPartialCannotExtendPastThirtySeconds() {
        val deadline = CardAnswerVoiceDeadline(0)
        for (now in 1000L..29000L step 1000) deadline.partial("말하는 중", now)
        assertEquals(30000L, deadline.deadlineAt()); assertEquals(0L, deadline.remainingMs(30000))
        deadline.partial("늦은 콜백", 40000); assertEquals(30000L, deadline.deadlineAt())
    }
    @Test fun detectedSpeechWithoutTranscriptIsFailureRatherThanInventedNoResponse() {
        val deadline = CardAnswerVoiceDeadline(0)
        assertTrue(deadline.expiryEvent(1, "") is CardAnswerVoiceEvent.Timeout)
        deadline.speechStarted(7000)
        assertTrue(deadline.expiryEvent(1, "") is CardAnswerVoiceEvent.Fail)
        assertTrue(deadline.expiryEvent(1, "두 번째") is CardAnswerVoiceEvent.Timeout)
    }
    @Test fun separateQuestionDeadlinesDoNotShareOldSpeechActivity() {
        val old = CardAnswerVoiceDeadline(0); old.partial("1번", 7900)
        val next = CardAnswerVoiceDeadline(20000)
        old.partial("늦은 결과", 25000)
        assertFalse(next.activitySeen); assertEquals(28000L, next.deadlineAt())
    }
    @Test fun epdEndNearNoResponseLimitAllowsFinalWithoutPretendingSpeechWasDetected() {
        val deadline = CardAnswerVoiceDeadline(0)
        deadline.awaitingFinal(7_900)
        assertEquals(11_900L, deadline.deadlineAt())
        assertFalse(deadline.activitySeen)
        assertTrue(deadline.expiryEvent(1, "") is CardAnswerVoiceEvent.Timeout)
        // Duplicate pending notices cannot extend an empty recognizer forever.
        deadline.awaitingFinal(11_000)
        assertEquals(11_900L, deadline.deadlineAt())
        deadline.partial("1번", 8_050)
        assertTrue(deadline.activitySeen)
        assertTrue(deadline.remainingMs(8_050) > 0)
    }
    @Test fun pendingFinalNeverExceedsTheOriginalHardMaximum() {
        val deadline = CardAnswerVoiceDeadline(0)
        deadline.awaitingFinal(29_900)
        assertEquals(30_000L, deadline.deadlineAt())
        assertEquals(0L, deadline.remainingMs(30_000))
    }

}
