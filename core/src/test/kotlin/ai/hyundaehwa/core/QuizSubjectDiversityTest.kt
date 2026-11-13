package ai.hyundaehwa.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QuizSubjectDiversityTest {
    private fun area(i: Int): AdministrativeConversationArea {
        val x = i.toDouble()
        val ring = listOf(AdministrativePoint(x, 0.0), AdministrativePoint(x + .8, 0.0), AdministrativePoint(x + .8, .8), AdministrativePoint(x, .8), AdministrativePoint(x, 0.0))
        val cards = listOf("history", "science", "arts", "food", "society").flatMap { subject -> (1..3).map { level ->
            AdministrativeConversationTopic("$i-$subject-$level", "quiz", "지역 문제", "이 지역의 실제 대상은 무엇일까요?",
                listOf(AdministrativeConversationOption("a", "정답"), AdministrativeConversationOption("b", "다른 답"), AdministrativeConversationOption("c", "또 다른 답")),
                sourceKind = "official-place-fact", difficulty = level, subject = subject)
        } }
        return AdministrativeConversationArea("area-$i", "$i", "테스트 지역 $i", "테스트 도", "테스트 시", "", "city", listOf(listOf(ring)), cards)
    }
    private fun catalog() = AdministrativeConversationCatalog(AdministrativeConversationDataset("test", "test", "https://example.com", (1..40).map(::area)), Random(318))
    @Test fun actualPresentationControlsHistoryBudgetAcrossAreasAndPreservesDifficulty() {
        for (level in 1..3) {
            val c = catalog(); val subjects = mutableListOf<String>()
            for (i in 1..40) {
                val first = checkNotNull(c.select(i + .4, .4, true, "quiz", "adult", level))
                repeat(5) { assertEquals(first.topic.id, c.select(i + .4, .4, true, "quiz", "adult", level)?.topic?.id) }
                assertEquals("area-$i", first.area.id); assertEquals(level, first.topic.difficulty)
                subjects.add(first.topic.subject)
                assertTrue(subjects.takeLast(5).count { it == "history" } <= 1)
                c.recordPresented(first, "adult", level)
            }
            assertEquals(5, subjects.toSet().size)
        }
    }
    @Test fun sparseHistoryOnlyInventoryDoesNotInventAnotherFieldOrChangeLocality() {
        val only = area(1).copy(topics = area(1).topics.filter { it.subject == "history" })
        val c = AdministrativeConversationCatalog(AdministrativeConversationDataset("test", "test", "https://example.com", listOf(only)), Random(4))
        assertEquals("history", c.select(1.4, .4, true, "quiz")?.topic?.subject)
        assertNull(c.select(2.4, .4, true, "quiz"))
    }
}
