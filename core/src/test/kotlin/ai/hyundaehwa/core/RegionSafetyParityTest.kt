package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class RegionSafetyParityTest {
    private val a = Topic("a-q", TopicType.QUIZ, "a", "첫 지역 질문")
    private val b = Topic("b-q", TopicType.QUIZ, "b", "다음 지역 질문")
    private fun send(s: ConversationState, e: ConversationEvent) = HyundaehwaEngine.reduce(s, e)
    private fun start(): ConversationState {
        val s = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D, speedKph = 60.0,
            regionId = "a", regionLabel = "첫 지역", destination = "목적지", pendingRegionId = "a",
            requireEntryDelivery = true, rotationRemaining = FourContentRotation.order, rms = null, soundObservedAtMs = null)
        return send(s, ConversationEvent.RegionalTopicsChanged("a", listOf(a)))
    }
    @Test fun entryMustFinishBeforeCardAndDoesNotDependOnQuiet() {
        var s = send(start(), ConversationEvent.Tick(10_000))
        assertNull(s.topic)
        s = send(s, ConversationEvent.RegionEntryStarted("a", "entry-1"))
        assertNull(s.topic)
        s = send(s, ConversationEvent.RegionEntryFinished("a", "entry-1"))
        assertEquals(a, s.topic)
        assertTrue(s.usedTopicIds.isEmpty()); assertTrue(s.presentedRegionIds.isEmpty())
        s = send(s, ConversationEvent.LocalTopicDisplayed("a-q"))
        assertEquals(setOf("a-q"), s.usedTopicIds)
        assertEquals(setOf("a"), s.presentedRegionIds)
        assertEquals(listOf("quiz", "quiz", "balance"), s.rotationRemaining)
    }
    @Test fun completedSpokenEntryPinsItsRegionAcrossBoundaryUntilItsCardIsDisplayed() {
        var s = send(start(), ConversationEvent.RegionEntryStarted("a", "entry-1"))
        s = send(s, ConversationEvent.RegionChanged("b", "다음 지역", "목적지"))
        s = send(s, ConversationEvent.RegionalTopicsChanged("b", listOf(b)))
        s = send(s, ConversationEvent.RegionEntryFinished("a", "entry-1"))
        assertEquals(a, s.topic); assertEquals("b", s.pendingRegionId)
        s = send(s, ConversationEvent.LocalTopicDisplayed("a-q"))
        assertEquals(setOf("a"), s.presentedRegionIds); assertEquals("b", s.pendingRegionId)
        assertEquals(a, send(s, ConversationEvent.Tick(5000)).topic)
    }
    @Test fun staleCompletionCannotReleaseNewEntryAndSafetyMustClear() {
        var s = send(start(), ConversationEvent.RegionEntryStarted("a", "entry-1"))
        s = send(s, ConversationEvent.FocusChanged(FocusKind.TUNNEL, true))
        s = send(s, ConversationEvent.RegionEntryFinished("a", "stale"))
        assertFalse(s.pinnedEntryFinished)
        s = send(s, ConversationEvent.RegionEntryFinished("a", "entry-1"))
        assertNull(s.topic)
        s = send(s, ConversationEvent.MediaChanged(null))
        s = send(s, ConversationEvent.FocusChanged(FocusKind.TUNNEL, false))
        assertNull(s.topic)
        s = send(s, ConversationEvent.MediaChanged(false))
        assertEquals(a, s.topic)
    }
    @Test fun locationLossDoesNotReplaceAnInProgressExplanation() {
        var s = start().copy(topic = a, answerExplanationHeld = true,
            output = Utterance("answer", "answer", "전체 해설", a.id), speechActive = true)
        s = send(s, ConversationEvent.RegionChanged("", "위치 미확인", "목적지"))
        assertEquals(a, s.topic); assertEquals("전체 해설", s.output?.text); assertTrue(s.speechActive)
    }
    @Test fun firstQuotaSlotDoesNotSkipToAnotherType() {
        val bag = listOf("quiz", "balance", "quiz", "quiz")
        assertNull(FourContentRotation.next(bag, null, setOf("balance")))
        assertEquals(bag, FourContentRotation.consume(bag, "balance"))
    }
    @Test fun exhaustedEntryIsReleasedAfterMovingToNewRegionWithoutConsumingQuota() {
        var s = start().copy(usedTopicIds = setOf("a-q"))
        s = send(s, ConversationEvent.RegionEntryStarted("a", "entry-1"))
        s = send(s, ConversationEvent.RegionChanged("b", "다음 지역", "목적지"))
        s = send(s, ConversationEvent.RegionalTopicsChanged("b", listOf(b)))
        s = send(s, ConversationEvent.RegionEntryFinished("a", "entry-1"))
        assertNull(s.topic); assertEquals("", s.pinnedEntryRegionId)
        assertEquals("b", s.pendingRegionId); assertEquals(FourContentRotation.order, s.rotationRemaining)
        s = send(s, ConversationEvent.RegionEntryStarted("b", "entry-2"))
        s = send(s, ConversationEvent.RegionEntryFinished("b", "entry-2"))
        assertEquals(b, s.topic)
    }

    @Test fun refreshAfterEntryStartedCannotReplacePinnedInventory() {
        var s = send(start(), ConversationEvent.RegionEntryStarted("a", "entry-1"))
        val refreshed = Topic("new-a", TopicType.QUIZ, "a", "설정 변경 후 질문")
        s = send(s, ConversationEvent.RegionalTopicsChanged("a", listOf(refreshed)))
        assertEquals(listOf(a), s.pinnedEntryTopics)
        assertEquals(listOf(refreshed), s.regionalTopics)
        s = send(s, ConversationEvent.RegionEntryFinished("a", "entry-1"))
        assertEquals(a, s.topic)
    }

}
