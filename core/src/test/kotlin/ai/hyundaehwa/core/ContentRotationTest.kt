package ai.hyundaehwa.core

import kotlin.random.Random
import kotlin.test.*
import org.junit.Test

class ContentRotationTest {
    private fun step(s: ConversationState, e: ConversationEvent) = HyundaehwaEngine.reduce(s, e, Random(5))
    private fun enter(s: ConversationState, id: String): ConversationState {
        val changed = step(s, ConversationEvent.RegionChanged(id, id, "목적지"))
        return step(changed, ConversationEvent.RegionalTopicsChanged(id, listOf(
            Topic("$id-quiz", TopicType.QUIZ, id, "지역 퀴즈"), Topic("$id-balance", TopicType.BALANCE, id, "지역 취향"))))
    }
    @Test fun allRelationshipsUseTheSameThreeToOneBagAcrossRegionsAndNeverCarousel() {
        for (relationship in Relationship.entries) {
            var s = HyundaehwaEngine.simulation(random = Random(7)).copy(enabled = true,
                gear = Gear.D, speedKph = 80.0, relationship = relationship, silenceThresholdMs = 3_000)
            val delivered = mutableListOf<TopicType>()
            repeat(12) { index ->
                s = enter(s, "region-$index")
                s = step(s, ConversationEvent.SoundLevel(0.01))
                s = step(s, ConversationEvent.Tick(3_000, 1_000))
                val topic = assertNotNull(s.topic); delivered += topic.type
                s = step(s, ConversationEvent.LocalTopicDisplayed(topic.id))
                s = step(s, ConversationEvent.OutputDone()); s = step(s, ConversationEvent.ReleaseTopic)
                s = step(s, ConversationEvent.Tick(30_000))
                assertNull(s.output); assertNull(s.topic)
                assertEquals(index + 1, s.presentedRegionIds.size)
            }
            delivered.chunked(4).forEach { assertEquals(3,it.count { t -> t == TopicType.QUIZ }); assertEquals(1,it.count { t -> t == TopicType.BALANCE }) }
            assertFalse(delivered.zipWithNext().any { it.first == TopicType.BALANCE && it.second == TopicType.BALANCE })
        }
    }
    @Test fun gleoSuccessfulDeliveryConsumesActualKindOnce() {
        var s = enter(HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D, speedKph = 70.0, rotationRemaining = listOf("balance", "quiz", "quiz", "quiz")), "a")
        s = s.copy(regionStableMs = 3_000, silenceMs = s.silenceThresholdMs)
        s = step(s, ConversationEvent.ExternalTopicPresented("a", "a-balance"))
        assertEquals(listOf("quiz", "quiz", "quiz"), s.rotationRemaining)
        assertEquals("balance", s.lastRotationKey)
        assertEquals(s, step(s, ConversationEvent.ExternalTopicPresented("a", "a-balance")))
    }
    @Test fun strictContentGateRejectsEveryGenericOrWrongAreaFallback() {
        assertTrue(LocalContentEligibility.exactCode("11110", "11110"))
        for (code in listOf("", "1111", "21110", " 11110")) assertFalse(LocalContentEligibility.exactCode(code, "11110"))
        assertTrue(LocalContentEligibility.accepts("balance", LocalContentEligibility.BALANCE_SOURCE, true, false))
        for (source in listOf("editorial-preference", "editorial-local-preference", "official-place-fact"))
            assertFalse(LocalContentEligibility.accepts("balance", source, true, true))
        assertFalse(LocalContentEligibility.accepts("conversation", "editorial-local-story", true, true))
        assertFalse(LocalContentEligibility.accepts("quiz", "official-place-fact", true, false))
        val fact = AdministrativeConversationTopic("q", "quiz", "제목", "질문", listOf(
            AdministrativeConversationOption("a", "하나"), AdministrativeConversationOption("b", "둘")),
            sourceKind = "official-place-fact", placeName = "실제 장소")
        val generic = fact.copy(id = "generic", format = "balance", sourceKind = "editorial-local-preference")
        val local = generic.copy(id = "local", sourceKind = LocalContentEligibility.BALANCE_SOURCE)
        val area = AdministrativeConversationArea("a", "11110", "지역", "도", "시", "", "city", emptyList(), listOf(fact, generic, local))
        val catalog = AdministrativeConversationCatalog(AdministrativeConversationDataset("2026", "자료", "https://example.gov", listOf(area)), Random(1))
        assertEquals(listOf(fact.id, local.id).toSet(), catalog.available(area).map { it.id }.toSet())
        assertTrue(catalog.available(area.copy(topics = listOf(fact))).none { it.format == "balance" })
    }
}
