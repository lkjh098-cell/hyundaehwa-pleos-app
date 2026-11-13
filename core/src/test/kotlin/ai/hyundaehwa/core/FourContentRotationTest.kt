package ai.hyundaehwa.core

import kotlin.random.Random
import kotlin.test.*
import org.junit.Test

class FourContentRotationTest {
    @Test fun everySeedProducesThreeQuizzesAndOneBalanceInEveryBagWithoutAdjacentBalances() {
        for (seed in 0..199) {
            val random = Random(seed)
            var bag = FourContentRotation.newBag(random)
            var last: String? = null
            val delivered = mutableListOf<String>()
            repeat(80) {
                val next = assertNotNull(FourContentRotation.next(bag, last, setOf("quiz", "balance")))
                assertFalse(last == "balance" && next == "balance")
                delivered += next
                bag = FourContentRotation.consume(bag, next, random); last = next
            }
            for (cycle in delivered.chunked(4)) {
                assertEquals(3, cycle.count { it == "quiz" }); assertEquals(1, cycle.count { it == "balance" })
            }
        }
    }
    @Test fun drawIsSeededAndReadDoesNotRedrawOrConsume() {
        val bag = FourContentRotation.newBag(Random(9))
        assertEquals(bag, FourContentRotation.newBag(Random(9)))
        assertTrue((0..30).map { FourContentRotation.newBag(Random(it)) }.toSet().size > 1)
        repeat(50) { assertEquals(bag.first(), FourContentRotation.next(bag, null, setOf("quiz", "balance"))) }
        assertEquals(4, bag.size)
        assertTrue(Relationship.entries.all(FourContentRotation::enabled))
    }
    @Test fun oneActualQuizConsumesOnlyOneOfItsThreeSlotsAndMissingInventoryWaits() {
        assertEquals(listOf("quiz", "quiz", "balance"), FourContentRotation.consume(FourContentRotation.order, "quiz"))
        assertNull(FourContentRotation.next(listOf("balance"), "quiz", setOf("quiz")))
        assertEquals(listOf("balance"), FourContentRotation.consume(listOf("balance"), "quiz"))
        assertEquals(FourContentRotation.order, FourContentRotation.consume(FourContentRotation.order, "weather"))
        for (type in listOf(TopicType.TRIVIA, TopicType.QUESTION, TopicType.WEATHER))
            assertEquals("", FourContentRotation.key(Topic("outside", type, "a", "제외")))
    }
    @Test fun onlyVisibleAutomaticCardConsumesAndRepeatedDisplayCommitsOnce() {
        var state = HyundaehwaEngine.simulation(random = Random(2)).copy(enabled = true, gear = Gear.D,
            speedKph = 80.0, rotationRemaining = FourContentRotation.order, silenceThresholdMs = 3_000)
        val region = state.regionId
        val before = state.rotationRemaining
        state = HyundaehwaEngine.reduce(state, ConversationEvent.RegionalTopicsChanged(region,
            listOf(Topic("actual-quiz", TopicType.QUIZ, region, "퀴즈"))))
        state = HyundaehwaEngine.reduce(state, ConversationEvent.Tick(3_000, 1_000))
        assertEquals("actual-quiz", state.topic?.id)
        assertEquals(before, state.rotationRemaining) // Prepared output has not rendered yet.
        assertEquals(state, HyundaehwaEngine.reduce(state, ConversationEvent.LocalTopicDisplayed("wrong")))
        state = HyundaehwaEngine.reduce(state, ConversationEvent.LocalTopicDisplayed("actual-quiz"))
        assertEquals(listOf("quiz", "quiz", "balance"), state.rotationRemaining)
        assertEquals(state, HyundaehwaEngine.reduce(state, ConversationEvent.LocalTopicDisplayed("actual-quiz")))
    }
    @Test fun guardedAndFailedExternalDeliveriesDoNotConsumeAndGpsReadsKeepBag() {
        var state = HyundaehwaEngine.simulation(random = Random(1)).copy(enabled = true, gear = Gear.D, speedKph = 70.0)
        val bag = state.rotationRemaining
        state = HyundaehwaEngine.reduce(state, ConversationEvent.FocusChanged(FocusKind.BRANCH, true))
        state = HyundaehwaEngine.reduce(state, ConversationEvent.RegionalTopicsChanged(state.regionId,
            listOf(Topic("q", TopicType.QUIZ, state.regionId, "퀴즈"))))
        state = HyundaehwaEngine.reduce(state, ConversationEvent.Tick(30_000))
        assertNull(state.output); assertEquals(bag, state.rotationRemaining)
        assertEquals(state, HyundaehwaEngine.reduce(state, ConversationEvent.ExternalTopicPresented("wrong", "q")))
        for (rel in Relationship.entries) {
            state = state.copy(gear = Gear.P)
            state = HyundaehwaEngine.reduce(state, ConversationEvent.RelationshipChanged(rel))
            state = HyundaehwaEngine.reduce(state, ConversationEvent.RegionChanged("new", "새 지역", "새 목적지"))
            assertEquals(bag, state.rotationRemaining)
        }
    }
}
