package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class GleoPresentationLedgerTest {
    private val ring = listOf(AdministrativePoint(126.0, 36.0), AdministrativePoint(128.0, 36.0),
        AdministrativePoint(128.0, 38.0), AdministrativePoint(126.0, 38.0), AdministrativePoint(126.0, 36.0))
    private val cards = listOf("quiz-a", "quiz-b").map { id -> AdministrativeConversationTopic(
        id, "quiz", id, "이 지역의 문제예요.", listOf(AdministrativeConversationOption("a", "첫 답"),
            AdministrativeConversationOption("b", "둘째 답")), sourceKind = "official-place-fact", correctChoiceId = "a", replyByChoice = mapOf("a" to "정답이에요.")) }
    private val area = AdministrativeConversationArea("test-area", "00000", "시험시", "시험도", "시험시", "", "city", listOf(listOf(ring)), cards)
    private fun catalog() = AdministrativeConversationCatalog(AdministrativeConversationDataset("test", "공개 자료", "https://example.gov", listOf(area)))

    @Test fun engineInventoryIncludesARecentlyDeliveredCardForALaterCommit() {
        val catalog = catalog()
        val first = checkNotNull(catalog.select(127.0, 37.0, true, "quiz"))
        catalog.recordPresented(first)
        assertFalse(catalog.availableForPresentation(area).any { it.id == first.topic.id })
        assertTrue(catalog.engineTopics(area).any { it.id == first.topic.id })
    }

    @Test fun rapidGleoCallbacksBetweenRefreshesShareEngineDeliveryHistory() {
        val catalog = catalog()
        val first = checkNotNull(catalog.select(127.0, 37.0, true, "quiz"))
        catalog.recordPresented(first)
        var state = HyundaehwaEngine.simulation().copy(regionId = area.id, regionLabel = area.label,
            pendingRegionId = area.id, enabled = true, gear = Gear.D, speedKph = 80.0)
        state = HyundaehwaEngine.reduce(state, ConversationEvent.RegionalTopicsChanged(area.id, catalog.engineTopics(area)))
        val selection = checkNotNull(catalog.select(127.0, 37.0, true, "quiz"))
        state = state.copy(regionStableMs = 3_000, silenceMs = state.silenceThresholdMs)
        state = HyundaehwaEngine.reduce(state, ConversationEvent.ExternalTopicPresented(area.id, selection.topic.id))
        assertEquals(selection.topic.id, state.lastTopicId)
        assertEquals("", state.pendingRegionId)
        val again = HyundaehwaEngine.reduce(state, ConversationEvent.ExternalTopicPresented(area.id, "quiz-a"))
        assertEquals(state, again)
        assertNull(HyundaehwaEngine.reduce(state, ConversationEvent.Tick(3_000)).output)
    }
}
