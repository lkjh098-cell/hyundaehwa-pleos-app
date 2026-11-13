package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class CardAnswerPresentationTest {
    private val choices = listOf(AdministrativeConversationOption("a", "초안산"), AdministrativeConversationOption("b", "불암산"))
    @Test fun spokenAnswerKeepsActualWordsRatherThanRewritingTheChoice() {
        assertEquals("2번 불암산 같아요", CardAnswer.userMessage(choices, "b", " 2번 불암산 같아요 "))
    }
    @Test fun touchChoiceProducesOneVisibleUserTurn() {
        assertEquals("1번 초안산", CardAnswer.userMessage(choices, "a", null))
        assertEquals("2번 불암산", CardAnswer.userMessage(choices, "b", " "))
    }
    @Test fun explicitDontKnowIsStillARealUserTurn() {
        assertEquals("잘 모르겠어요", CardAnswer.userMessage(choices, null, "잘 모르겠어요"))
    }
    @Test fun timeoutOrAnswerRevealDoesNotInventAUserTurn() {
        assertNull(CardAnswer.userMessage(choices, null, null))
        assertNull(CardAnswer.userMessage(choices, null, " "))
        assertNull(CardAnswer.userMessage(choices, "stale-choice", null))
    }
}
