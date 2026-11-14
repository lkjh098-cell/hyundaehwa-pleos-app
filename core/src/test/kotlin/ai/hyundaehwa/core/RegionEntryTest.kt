package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class RegionEntryTest {
    private val ready = RegionEntryConditions(true, true, true, false, false, false, false)
    private fun state(id: String = "b") = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D, speedKph = 70.0,
        destination = "목적지", regionId = id, regionLabel = "성동구", regionEntryHeld = true, output = null, topic = null,
        silenceMs = ConversationLimits.SILENCE_MS, rotationRemaining = FourContentRotation.order)
    private fun queue(id: String = "b") = RegionEntryQueue().also { it.observe(id, "성동구", "목적지") }
    @Test fun visualChangesImmediatelyButCurrentQuestionAndSpeechRemainPinned() {
        val output = Utterance("old-question", "topic", "질문을 계속 읽습니다.", "old")
        val old = state("a").copy(topic = Topic("old", TopicType.QUIZ, "a", "옛 질문"), output = output, speechActive = true)
        val next = HyundaehwaEngine.reduce(old, ConversationEvent.RegionChanged("b", "성동구", "목적지"))
        val queue = queue()
        assertEquals("성동구에 진입했습니다", queue.visual?.message)
        assertEquals(output, next.output); assertTrue(next.speechActive); assertEquals("old", next.topic?.id)
        assertNull(queue.begin(next, ready)); assertTrue(queue.blocksContent)
    }
    @Test fun entryVoiceMustActuallyCompleteBeforeRegionCardOrRotationIsConsumed() {
        val queue = queue(); var s = state().copy(pendingRegionId = "b", regionStableMs = 3000,
            rotationRemaining = FourContentRotation.order)
        val card = Topic("b-question", TopicType.QUIZ, "b", "새 지역 질문")
        s = HyundaehwaEngine.reduce(s, ConversationEvent.RegionalTopicsChanged("b", listOf(card)))
        assertNull(s.topic); assertTrue(s.usedTopicIds.isEmpty()); assertTrue(s.presentedRegionIds.isEmpty())
        val entry = assertNotNull(queue.begin(s, ready)); assertEquals("성동구에 진입했습니다.", entry.spokenText)
        assertFalse(queue.complete("unrelated")); assertTrue(queue.blocksContent)
        assertNull(HyundaehwaEngine.reduce(s, ConversationEvent.ExternalTopicPresented("b", card.id)).topic)
        assertTrue(queue.complete(entry.speechId)); assertFalse(queue.blocksContent)
        s = HyundaehwaEngine.reduce(s.copy(regionEntryHeld = queue.blocksContent), ConversationEvent.OutputDone())
        assertEquals(card.id, s.topic?.id); assertEquals(card.text, s.output?.text)
        assertEquals(setOf("b"), s.presentedRegionIds)
    }
    @Test fun unresolvedAnswerAndFullExplanationIncludingFeedbackWindowDelayEntry() {
        val queue = queue(); val old = Topic("old", TopicType.QUIZ, "a", "기존 질문")
        assertNull(queue.begin(state().copy(topic = old), ready))
        assertNull(queue.begin(state().copy(answerListening = true), ready))
        assertNull(queue.begin(state().copy(answerExplanationHeld = true), ready))
        assertNull(queue.begin(state(), ready.copy(cardHeld = true))) // Adapter also holds the completed explanation's four-second feedback window.
        assertNotNull(queue.begin(state(), ready))
    }
    @Test fun latestPendingRegionReplacesIntermediateRegionsWithoutCardsOrDuplicateNotices() {
        val queue = queue(); val first = queue.visual
        queue.observe("b", "성동구", "목적지"); assertEquals(first, queue.visual)
        queue.observe("c", "광진구", "목적지"); queue.observe("d", "강동구", "목적지")
        assertEquals("강동구에 진입했습니다", queue.visual?.message)
        val last = assertNotNull(queue.begin(state("d"), ready)); assertEquals("d", last.regionId)
        assertTrue(queue.complete(last.speechId)); assertFalse(queue.blocksContent)
    }
    @Test fun currentEntryCanFinishButItsCompletionCannotUnlockARecentlyEnteredRegion() {
        val queue = queue(); val first = assertNotNull(queue.begin(state(), ready))
        queue.observe("c", "광진구", "목적지", first.speechId)
        assertEquals(first, queue.active); assertEquals("c", queue.pending?.regionId)
        assertTrue(queue.complete(first.speechId)); assertTrue(queue.blocksContent)
        val next = assertNotNull(queue.begin(state("c"), ready)); assertEquals("광진구에 진입했습니다.", next.spokenText)
        assertTrue(queue.complete(next.speechId)); assertFalse(queue.blocksContent)
    }
    @Test fun pausedObsoleteEntryIsCoalescedRatherThanResumedIntoWrongRegion() {
        val queue = queue(); val first = assertNotNull(queue.begin(state(), ready))
        queue.cancelled(first.speechId, true); assertEquals(first, queue.active)
        queue.observe("c", "광진구", "목적지", null)
        assertNull(queue.active); assertFalse(queue.complete(first.speechId))
        assertEquals("c", queue.begin(state("c"), ready)?.regionId)
    }
    @Test fun mediaNavigationFocusConversationAndOtherVoicesHavePriority() {
        val blocked = listOf(state().copy(mediaActive = true), state().copy(mediaActive = null), 
            state().copy(quiet = true), state().copy(gear = Gear.R), state().copy(enabled = false), state().copy(speechActive = true),
            state().copy(arrived = true), state().copy(restDue = true, restAnnounced = true)) + FocusKind.entries.map { state().copy(guards = setOf(it)) }
        blocked.forEach { assertNull(queue().begin(it, ready)) }
        listOf(ready.copy(resumed = false), ready.copy(voiceEnabled = false), ready.copy(otherVoiceActive = true),
            ready.copy(checkpointHeld = true), ready.copy(navigationVoiceActive = true)).forEach { assertNull(queue().begin(state(), it)) }
    }
    @Test fun noFakeCompletionOnFailureAndExplicitRetryKeepsSameEntry() {
        val queue = queue(); val first = assertNotNull(queue.begin(state(), ready))
        queue.failed(first.speechId, "기기 음성 실패")
        assertTrue(queue.blocksContent); assertEquals("기기 음성 실패", queue.failure); assertNull(queue.begin(state(), ready))
        queue.retry(); val retried = assertNotNull(queue.begin(state(), ready))
        assertNotEquals(first.speechId, retried.speechId)
        assertFalse(queue.complete(first.speechId)); assertTrue(queue.complete(retried.speechId))
    }
    @Test fun guardInterruptionWithSentenceCheckpointKeepsEntryHoldUntilActualResumeCompletion() {
        val queue = queue(); val entry = assertNotNull(queue.begin(state(), ready))
        queue.cancelled(entry.speechId, true)
        assertTrue(queue.blocksContent); assertEquals(entry, queue.active); assertNull(queue.begin(state(), ready))
        assertTrue(queue.complete(entry.speechId)); assertFalse(queue.blocksContent)
    }
    @Test fun routeChangeOrLostLocationInvalidatesStaleEntryCompletion() {
        val queue = queue(); val old = assertNotNull(queue.begin(state(), ready))
        queue.observe("c", "새 지역", "새 목적지", old.speechId)
        assertNull(queue.active); assertFalse(queue.complete(old.speechId)); assertTrue(queue.blocksContent)
        queue.observe("", "위치 미확인", "새 목적지")
        assertNull(queue.visual); assertFalse(queue.blocksContent)
    }
    @Test fun silenceDestinationAndWeatherCannotSkipPendingRegionEntry() {
        val s = state().copy(destinationSilenceMs = 300000, silenceMs = 300000)
        assertNull(HyundaehwaEngine.reduce(s, ConversationEvent.Tick(1000)).topic)
        assertNull(HyundaehwaEngine.reduce(s, ConversationEvent.WeatherReady("날씨 안내")).output)
    }
    @Test fun explicitVoiceOffAcknowledgesVisibleEntryAndAllowsTextContentWithoutSpeechCompletion() {
        val queue = queue(); val notice = assertNotNull(queue.visual)
        val textMode = ready.copy(voiceEnabled = false, localAudioReady = false)
        val card = Topic("b-question", TopicType.QUIZ, "b", "새 지역 질문")
        var s = state().copy(pendingRegionId = "b", regionStableMs = 3000)
        s = HyundaehwaEngine.reduce(s, ConversationEvent.RegionalTopicsChanged("b", listOf(card)))
        assertNull(s.topic); assertTrue(s.usedTopicIds.isEmpty())
        assertNull(queue.begin(s, textMode))
        assertTrue(queue.acknowledgeText(s, textMode))
        assertEquals(notice, queue.visual); assertNull(queue.active); assertFalse(queue.blocksContent)
        assertFalse(queue.complete(notice.speechId))
        assertFalse(queue.acknowledgeText(s, textMode))
        // The adapter only clears the hold; the normal tick selects content without a fabricated completion.
        s = HyundaehwaEngine.reduce(s.copy(regionEntryHeld = queue.blocksContent), ConversationEvent.Tick(0))
        assertEquals(card, s.topic); assertEquals(setOf(card.id), s.usedTopicIds)
        assertEquals(setOf("b"), s.presentedRegionIds)
    }
    @Test fun voiceFailureStillRequiresRetryUnlessUserExplicitlySwitchesToTextMode() {
        val queue = queue(); val entry = assertNotNull(queue.begin(state(), ready))
        queue.failed(entry.speechId, "기기 음성 실패")
        assertFalse(queue.acknowledgeText(state(), ready)); assertTrue(queue.blocksContent)
        assertEquals("기기 음성 실패", queue.failure)
        assertTrue(queue.acknowledgeText(state(), ready.copy(voiceEnabled = false)))
        assertFalse(queue.blocksContent); assertNull(queue.failure); assertEquals(entry, queue.visual)
        assertFalse(queue.complete(entry.speechId))
    }
    @Test fun textAcknowledgementKeepsAllSafetyVisibilityAndConversationGuards() {
        val textMode = ready.copy(voiceEnabled = false)
        val blockedStates = listOf(
            state().copy(mediaActive = true), state().copy(mediaActive = null), state().copy(quiet = true),
            state().copy(gear = Gear.R), state().copy(gear = Gear.N), state().copy(enabled = false),
            state().copy(arrived = true), state().copy(speechActive = true), state().copy(answerListening = true),
            state().copy(answerExplanationHeld = true), state().copy(restDue = true, restAnnounced = true),
            state().copy(topic = Topic("old", TopicType.QUIZ, "a", "기존 질문")),
            state().copy(output = Utterance("other", "topic", "기존 출력")),
            state("other"), state().copy(destination = "다른 목적지")
        ) + FocusKind.entries.map { state().copy(guards = setOf(it)) }
        blockedStates.forEach { blocked ->
            val queue = queue()
            assertFalse(queue.acknowledgeText(blocked, textMode)); assertTrue(queue.blocksContent)
            assertTrue(queue.acknowledgeText(state(), textMode))
        }
        listOf(textMode.copy(resumed = false), textMode.copy(cardHeld = true),
            textMode.copy(otherVoiceActive = true), textMode.copy(checkpointHeld = true),
            textMode.copy(navigationVoiceActive = true), textMode.copy(audioFocusBlocked = true)).forEach { blocked ->
            val queue = queue()
            assertFalse(queue.acknowledgeText(state(), blocked)); assertTrue(queue.blocksContent)
            assertTrue(queue.acknowledgeText(state(), textMode))
        }
        assertNull(queue().begin(state(), ready.copy(audioFocusBlocked = true)))
    }
    @Test fun switchingOffAfterInterruptedSpeechDiscardsItsHoldOnlyAfterCheckpointIsCleared() {
        val queue = queue(); val entry = assertNotNull(queue.begin(state(), ready))
        queue.cancelled(entry.speechId, checkpointRetained = true)
        val textMode = ready.copy(voiceEnabled = false)
        assertFalse(queue.acknowledgeText(state(), textMode.copy(checkpointHeld = true)))
        assertTrue(queue.blocksContent); assertEquals(entry, queue.active)
        assertTrue(queue.acknowledgeText(state(), textMode))
        assertNull(queue.active); assertFalse(queue.blocksContent)
        assertFalse(queue.complete(entry.speechId))
    }
    @Test fun switchingOffDuringEntryWaitsForCancelledOutputCleanupBeforeTextAcknowledgement() {
        val queue = queue(); val entry = assertNotNull(queue.begin(state(), ready))
        val textMode = ready.copy(voiceEnabled = false)
        val card = Topic("b-question", TopicType.QUIZ, "b", "새 지역 질문")
        var s = state().copy(output = Utterance(entry.speechId, "region-entry", entry.spokenText), speechActive = true,
            pendingRegionId = "b", regionStableMs = 3000, regionalTopicsRegionId = "b", regionalTopics = listOf(card))
        assertFalse(queue.acknowledgeText(s, textMode))
        queue.cancelled(entry.speechId, checkpointRetained = false)
        assertTrue(queue.blocksContent); assertNull(queue.active)
        // This is the real cancellation cleanup used by the adapter, not a speech-success callback.
        s = HyundaehwaEngine.reduce(s, ConversationEvent.OutputDone(entry.speechId))
        assertNull(s.output); assertFalse(s.speechActive); assertNull(s.topic)
        assertFalse(queue.acknowledgeText(s, ready))
        assertTrue(queue.acknowledgeText(s, textMode))
        assertFalse(queue.complete(entry.speechId))
        s = HyundaehwaEngine.reduce(s.copy(regionEntryHeld = queue.blocksContent), ConversationEvent.Tick(0))
        assertEquals(card, s.topic)
    }
    @Test fun blockedTextModeCanSwitchBackToVoiceWithoutLosingThePendingEntry() {
        val queue = queue(); val notice = queue.visual
        assertFalse(queue.acknowledgeText(state().copy(quiet = true), ready.copy(voiceEnabled = false)))
        assertEquals(notice, queue.begin(state(), ready))
        assertTrue(queue.blocksContent)
    }
    @Test fun textModeCoalescesLatestRegionAndRejectsStaleRouteOrSpeechCompletion() {
        val queue = queue(); val old = assertNotNull(queue.begin(state(), ready))
        queue.observe("c", "광진구", "새 목적지", old.speechId)
        val textMode = ready.copy(voiceEnabled = false)
        assertFalse(queue.acknowledgeText(state(), textMode))
        assertTrue(queue.blocksContent)
        assertTrue(queue.acknowledgeText(state("c").copy(destination = "새 목적지"), textMode))
        assertEquals("c", queue.visual?.regionId); assertFalse(queue.blocksContent)
        assertFalse(queue.complete(old.speechId))
    }
    @Test fun oneRealRetryThenTextContinuationNeverClaimsPlaybackOrReleasesTwice() {
        val q = queue(); val first = assertNotNull(q.begin(state(), ready))
        q.failed(first.speechId, "failed")
        assertEquals(RegionEntryRecovery.RETRY, q.recover(state(), ready))
        val second = assertNotNull(q.begin(state(), ready))
        assertNotEquals(first.speechId, second.speechId)
        assertFalse(q.complete(first.speechId))
        q.failed(first.speechId, "late error")
        assertEquals(second, q.active)
        q.failed(second.speechId, "failed again")
        assertEquals(RegionEntryRecovery.TEXT, q.recover(state(), ready))
        assertEquals(RegionEntryDelivery.TEXT, q.delivery)
        assertEquals("성동구에 진입했습니다", q.visual?.message)
        assertFalse(q.blocksContent)
        assertFalse(q.complete(second.speechId))
        assertEquals(RegionEntryRecovery.NONE, q.recover(state(), ready))
    }
    @Test fun automaticRecoveryWaitsForEveryExistingSafetyAndOutputGuard() {
        val states = listOf(state().copy(quiet = true), state().copy(mediaActive = true), state().copy(mediaActive = null),
            state().copy(gear = Gear.R), state().copy(enabled = false), state().copy(answerListening = true),
            state().copy(answerExplanationHeld = true), state().copy(output = Utterance("failed", "region-entry", "안내")),
            state().copy(speechActive = true), state().copy(arrived = true)) +
            FocusKind.entries.map { state().copy(guards = setOf(it)) }
        val conditions = listOf(ready.copy(resumed = false), ready.copy(cardHeld = true), ready.copy(otherVoiceActive = true),
            ready.copy(checkpointHeld = true), ready.copy(navigationVoiceActive = true), ready.copy(audioFocusBlocked = true))
        for (attempt in 1..2) {
            fun failedQueue(): RegionEntryQueue {
                val q = queue()
                repeat(attempt) { index ->
                    val notice = assertNotNull(q.begin(state(), ready)); q.failed(notice.speechId, "failed")
                    if (index + 1 < attempt) assertEquals(RegionEntryRecovery.RETRY, q.recover(state(), ready))
                }
                return q
            }
            states.forEach { blocked -> val q = failedQueue(); assertEquals(RegionEntryRecovery.NONE, q.recover(blocked, ready)); assertTrue(q.blocksContent) }
            conditions.forEach { blocked -> val q = failedQueue(); assertEquals(RegionEntryRecovery.NONE, q.recover(state(), blocked)); assertTrue(q.blocksContent) }
        }
    }
    @Test fun replacedRegionResetsFailureBudgetAndRejectsOldRecoveryCallbacks() {
        val q = queue(); val old = assertNotNull(q.begin(state(), ready)); q.failed(old.speechId, "failed")
        q.observe("c", "광진구", "목적지")
        assertEquals(RegionEntryRecovery.NONE, q.recover(state(), ready))
        assertFalse(q.complete(old.speechId))
        val next = assertNotNull(q.begin(state("c"), ready)); q.failed(old.speechId, "late")
        assertEquals(next, q.active)
        q.failed(next.speechId, "new region failure")
        assertEquals(RegionEntryRecovery.RETRY, q.recover(state("c"), ready))
    }
}
