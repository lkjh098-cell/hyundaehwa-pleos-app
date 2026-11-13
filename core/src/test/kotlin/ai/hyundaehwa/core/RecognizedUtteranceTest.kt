package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class RecognizedUtteranceTest {
    @Test fun completedSegmentsPreserveOneWholeRelationshipSentence() {
        val u = RecognizedUtterance()
        u.update("친구랑", true)
        assertEquals("친구랑", u.finalText)
        u.update("가고", false)
        assertEquals("친구랑 가고", u.preview)
        assertEquals("", u.finalText) // a trailing partial can still negate/correct the earlier segment
        u.update("가고 있어요", true)
        assertEquals("친구랑 가고 있어요", u.finalText)
        assertEquals(Relationship.FRIEND, RelationshipSpeech.parse(u.finalText))
    }
    @Test fun cumulativeAndOverlappingUpdatesDoNotRepeatWords() {
        val u = RecognizedUtterance()
        u.update("가족이랑", true)
        u.update("가족이랑 같이", true)
        u.update("같이 가요", true)
        u.update("가족이랑 같이 가요", true)
        assertEquals("가족이랑 같이 가요", u.finalText)
        assertEquals(Relationship.FAMILY, RelationshipSpeech.parse(u.finalText))
    }
    @Test fun laterNegationCannotCommitAnEarlierPositiveSegment() {
        val u = RecognizedUtterance()
        u.update("친구랑", true)
        u.update("가는 게 아니에요", true)
        assertNull(RelationshipSpeech.parse(u.finalText))
        val card = AdministrativeConversationTopic("b", "balance", "제목", "질문", listOf(
            AdministrativeConversationOption("a", "바다"), AdministrativeConversationOption("b", "산")), emptyMap())
        val answer = RecognizedUtterance()
        answer.update("전자", true); answer.update("말고 후자", true)
        assertEquals("b", SpokenCardAnswerParser.parse(answer.finalText, card)?.choiceId)
    }
    @Test fun unfinishedTrailingNegationInvalidatesEvenAnEarlierCompleteLabel() {
        val u = RecognizedUtterance(); u.update("가족", true); u.update("이 아니", false)
        assertEquals("가족 이 아니", u.preview); assertEquals("", u.finalText)
        assertNull(RelationshipSpeech.parse(u.finalText))
    }
    @Test fun uncompletedSpeechNeverProducesAFinalAndNewSessionStartsEmpty() {
        val u = RecognizedUtterance(); u.update("광화문으로 가고 싶어", false)
        assertEquals("", u.finalText)
        assertEquals("", RecognizedUtterance().preview)
    }
}
