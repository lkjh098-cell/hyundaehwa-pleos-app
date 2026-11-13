package ai.hyundaehwa.core
import kotlin.test.*
import org.junit.Test
class BalancePresentationTest {
    private val options = listOf(AdministrativeConversationOption("a", "산책"), AdministrativeConversationOption("b", "사진"))
    @Test fun directIntroPrecedesQuestionAndOptions() {
        assertEquals("밸런스 게임! 산책과 사진 중 어느 쪽이 좋으세요? 1번. 산책. 2번. 사진.", BalancePresentation.spokenPrompt("산책과 사진 중 어느 쪽이 좋으세요?", options))
    }
    @Test fun oldPreamblesNeverRepeatTheIntroOrLecture() {
        val value = BalancePresentation.spokenPrompt("정답이 없는 질문이에요~ 밸런스 게임. 밸런스 게임! 어느 쪽이 좋아요?", options)
        assertEquals("밸런스 게임! 어느 쪽이 좋아요? 1번. 산책. 2번. 사진.", value)
        assertFalse(value.contains("정답이 없는"))
    }
    @Test fun balanceTimeoutHasNoDisclaimerOrUnrequestedSpeech() {
        assertNull(BalancePresentation.unansweredFeedback("balance"))
        assertNotNull(BalancePresentation.unansweredFeedback("conversation"))
    }
    @Test fun topicSpeechUsesTheIntroOnlyForBalance() {
        val quiz = AdministrativeConversationTopic("q", "quiz", "지역 퀴즈", "어디일까요?", options)
        assertFalse(quiz.spokenPrompt.startsWith("밸런스 게임!"))
        assertTrue(quiz.copy(format = "balance").spokenPrompt.startsWith("밸런스 게임! 어디일까요?"))
    }
}
