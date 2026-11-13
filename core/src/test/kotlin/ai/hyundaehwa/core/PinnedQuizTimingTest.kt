package ai.hyundaehwa.core

import org.junit.Assert.*
import org.junit.Test

class PinnedQuizTimingTest {
    @Test fun unansweredDisclosureNeverPretendsTheUserChoseCorrectly() {
        assertEquals("아쉽네요. 정답은 2번이에요. 지역 이야기", PinnedQuizTiming.answerDisclosure(2, "지역 이야기", true))
        assertEquals("정답은 2번이에요. 지역 이야기", PinnedQuizTiming.answerDisclosure(2, "지역 이야기", false))
    }
    @Test fun revealUsesEightSecondsAndNeverRepeatsAfterAnswer() {
        assertFalse(PinnedQuizTiming.shouldReveal(8_999, 1_000, false))
        assertTrue(PinnedQuizTiming.shouldReveal(9_000, 1_000, false))
        assertFalse(PinnedQuizTiming.shouldReveal(10_000, 1_000, true))
        assertFalse(PinnedQuizTiming.shouldReveal(500, 1_000, false))
    }
    @Test fun feedbackRemainsFourSecondsAndUnknownOrSameAreaDoesNotAdvance() {
        assertFalse(PinnedQuizTiming.shouldAdvance(4_999, 1_000, "b", "a"))
        assertTrue(PinnedQuizTiming.shouldAdvance(5_000, 1_000, "b", "a"))
        assertFalse(PinnedQuizTiming.shouldAdvance(5_000, 1_000, "", "a"))
        assertFalse(PinnedQuizTiming.shouldAdvance(5_000, 1_000, "a", "a"))
    }
}
