package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class CardAnswerVoiceStartTest {
    private val card = AdministrativeConversationTopic("first", "quiz", "제목", "질문", listOf(
        AdministrativeConversationOption("a", "황포묵"), AdministrativeConversationOption("b", "도토리묵")))

    @Test fun freshQuestionCompletionClearsAnEarlyFailedAttemptForTheSameCard() {
        val automatic = CardAnswerVoiceAutoStart()
        automatic.attemptedCardId = card.id
        var input = CardAnswerVoiceSession.reduce(CardAnswerVoiceState(), CardAnswerVoiceEvent.Begin(1, card.id))
        input = CardAnswerVoiceSession.reduce(input, CardAnswerVoiceEvent.Fail(1, "아직 준비 중"))
        assertTrue(automatic.questionCompleted(card.id, card.id, false, input))
        assertTrue(automatic.questionReady)
        assertNull(automatic.attemptedCardId)
        // A new first-answer capture accepts short labels, numbers, and number-plus-label forms.
        for (text in listOf("1", "1번", "황포묵", "1번 황포묵이요")) {
            var fresh = CardAnswerVoiceSession.reduce(input, CardAnswerVoiceEvent.Begin(2, card.id))
            assertFalse(fresh.retryWhenSafe)
            assertEquals("", fresh.transcript)
            fresh = CardAnswerVoiceSession.reduce(fresh, CardAnswerVoiceEvent.Ready(2, "기기 내 음성"))
            fresh = CardAnswerVoiceSession.reduce(fresh, CardAnswerVoiceEvent.Final(2, text, card))
            assertEquals("a", fresh.answer?.choiceId, text)
        }
    }

    @Test fun staleOtherPromptAndRevealedCardCannotArmAutomaticCapture() {
        val automatic = CardAnswerVoiceAutoStart()
        automatic.attemptedCardId = card.id
        for ((finished, visible, revealed) in listOf(
            Triple(null, card.id, false), Triple("old", card.id, false),
            Triple(card.id, null, false), Triple(card.id, card.id, true))) {
            assertFalse(automatic.questionCompleted(finished, visible, revealed, CardAnswerVoiceState()))
            assertFalse(automatic.questionReady)
            assertEquals(card.id, automatic.attemptedCardId)
        }
    }

    @Test fun duplicateCompletionDoesNotResetAnActiveAttempt() {
        for (phase in listOf(CardAnswerVoicePhase.PREPARING, CardAnswerVoicePhase.LISTENING)) {
            val automatic = CardAnswerVoiceAutoStart()
            automatic.attemptedCardId = card.id
            assertTrue(automatic.questionCompleted(card.id, card.id, false, CardAnswerVoiceState(1, card.id, phase)))
            assertEquals(card.id, automatic.attemptedCardId)
        }
    }

    @Test fun startupAndReconnectNeverTellUserToAnswerBeforeCaptureIsReady() {
        var input = CardAnswerVoiceSession.reduce(CardAnswerVoiceState(), CardAnswerVoiceEvent.Begin(1, card.id))
        assertTrue(input.active) // The turn stays reserved against other prompts.
        assertEquals("답변 마이크 연결 중이에요", input.captureStatusTitle)
        assertEquals("마이크가 준비되면 답변을 들어요.", input.captureStatusDetail)
        input = CardAnswerVoiceSession.reduce(input, CardAnswerVoiceEvent.Ready(1, "기기 내 음성"))
        assertEquals("답변을 듣고 있어요", input.captureStatusTitle)
        input = CardAnswerVoiceSession.reduce(input, CardAnswerVoiceEvent.Reconnecting(1))
        assertEquals("답변 마이크 연결 중이에요", input.captureStatusTitle)
        input = CardAnswerVoiceSession.reduce(input, CardAnswerVoiceEvent.Ready(0, "늦은 콜백"))
        assertEquals("답변 마이크 연결 중이에요", input.captureStatusTitle)
        input = CardAnswerVoiceSession.reduce(input, CardAnswerVoiceEvent.Ready(1, "기기 내 음성"))
        assertEquals("답변을 듣고 있어요", input.captureStatusTitle)
    }
}
