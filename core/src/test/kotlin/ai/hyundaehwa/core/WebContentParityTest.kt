package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class WebContentParityTest {
    @Test fun webDestinationUsesItsAuthoredCategoryChoicesAndSequence() {
        val beach = assertNotNull(DestinationConversation.card("부산 해운대", Relationship.FRIEND, 0))
        assertEquals("부산 해운대에 도착하면 친구들과 이용 가능한 곳에서 무엇부터 즐길까요?", beach.question)
        assertEquals(listOf("모래사장을 천천히 걸으며 바다를 가까이 봐요", "편하게 앉아서 바다를 바라보며 쉬어요"), beach.options.map { it.label })
        val next = assertNotNull(DestinationConversation.card("부산 해운대", Relationship.FRIEND, 1))
        assertEquals("바다를 배경으로 사진부터 남겨요", next.options[0].label)
        val museum = assertNotNull(DestinationConversation.card("국립중앙박물관", Relationship.FAMILY, 0))
        assertEquals("국립중앙박물관에 도착하면 가족과 관람이 가능한지 확인한 뒤 무엇부터 할까요?", museum.question)
        assertTrue(museum.contentNote.contains("실제 이용 가능 여부"))
    }
    @Test fun webStoryUsesSourcedActualLocalPreferenceAndFailsClosed() {
        val fact = AdministrativeConversationTopic("quiz", "quiz", "질문", "질문", listOf(
            AdministrativeConversationOption("a", "A"), AdministrativeConversationOption("b", "B")),
            sourceUrl = "https://example.org/fact", sourceKind = "official-place-fact", difficulty = 2)
        // Fixture copied from the actual 11110 local balance bank and its source fields.
        val pref = AdministrativeConversationTopic("lifestyle-11110-seoul-11110-tongin-tray", "balance", "통인시장 밸런스게임",
            "종로구 통인시장에는 엽전으로 음식을 고르는 도시락 체험이 있어요. 통인시장 도시락을 딱 한 번 채운다면?",
            listOf(AdministrativeConversationOption("first", "좋아하는 반찬 두 가지로 꽉 채우기"), AdministrativeConversationOption("second", "처음 보는 반찬 여섯 가지를 조금씩 담기")),
            mapOf("first" to "두 가지에 집중하는 도시락이군요. 마지막 한 칸도 같은 반찬으로 채울 건가요?", "second" to "여섯 가지를 맛보는 도시락이군요. 처음 보는 반찬은 누가 먼저 골라 줄까요?"),
            sourceTitle = "서울관광재단 · 통인시장", sourceUrl = "https://korean.visitseoul.net/attractions/%ED%86%B5%EC%9D%B8%EC%8B%9C%EC%9E%A5_/281",
            sourceKind = LocalContentEligibility.BALANCE_SOURCE, placeName = "통인시장",
            factualIntro = "종로구 통인시장에는 엽전으로 음식을 고르는 도시락 체험이 있어요.")
        val area = AdministrativeConversationArea("kr-11110", "11110", "서울특별시 종로구", "서울특별시", "", "종로구", "district", emptyList(), listOf(fact, pref))
        val story = assertNotNull(LocationStoryCards.create(area, "adult", 2))
        assertEquals("story-${pref.id}", story.id)
        assertEquals(pref.question, story.question)
        assertEquals(pref.options, story.options)
        assertEquals(pref.sourceUrl, story.sourceUrl)
        assertEquals(pref.factualIntro, story.explanation)
        assertNull(LocationStoryCards.create(area.copy(topics = listOf(fact)), "adult", 2))
        assertNull(LocationStoryCards.create(area.copy(topics = listOf(fact, pref.copy(factualIntro = ""))), "adult", 2))
    }
}
