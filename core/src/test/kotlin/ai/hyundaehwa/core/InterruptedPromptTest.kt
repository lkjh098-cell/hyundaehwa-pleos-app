package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class InterruptedPromptTest {
    private val topic = Topic("question", TopicType.QUIZ, "region", "질문")
    private val output = Utterance("local-7", "topic", "지역 이야기예요. 어느 산일까요?", topic.id)
    private val state = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D, speedKph = 80.0,
        topic = topic, destination = "전주", output = null)
    private val safe = PromptResumeConditions(true, true, true, false, false)
    private fun queue() = PromptResumeQueue().apply { remember(output, "어느 산일까요?", "전주", topic.id) }
    @Test fun sentencesKeepPunctuationAndResumeAtASentenceBoundary() {
        val parts = PromptSentences.split("지역 이야기예요. 어느 산일까요?\n정답을 골라 주세요.")
        assertEquals(listOf("지역 이야기예요.", "어느 산일까요?", "정답을 골라 주세요."), parts)
        assertEquals("어느 산일까요? 정답을 골라 주세요.", parts.drop(1).joinToString(" "))
        assertTrue(PromptSentences.split(" ").isEmpty())
    }
    @Test fun decimalTemperatureAndDistanceRemainInsideTheirSentence() {
        assertEquals(listOf("지금은 18.5도예요.", "3.1km 뒤에 도착해요."), PromptSentences.split("지금은 18.5도예요. 3.1km 뒤에 도착해요."))
    }
    @Test fun backendFailureNeverAutomaticallyResumesAndEachRetryRequiresUserAction() {
        val q = queue()
        q.holdAfterFailure(output.id)
        var starts = 0
        repeat(100) { if (q.takeIfReady(state, "전주", topic.id, safe) != null) starts++ }
        assertEquals(0, starts)
        assertTrue(q.failureHeld)
        assertFalse(q.retryAfterFailure("another"))
        assertTrue(q.retryAfterFailure(output.id))
        assertNotNull(q.takeIfReady(state, "전주", topic.id, safe)); starts++
        assertEquals(1, starts)
        q.remember(output, "남은 문장", "전주", topic.id)
        q.holdAfterFailure(output.id)
        repeat(100) { if (q.takeIfReady(state, "전주", topic.id, safe) != null) starts++ }
        assertEquals(1, starts)
    }
    @Test fun failureCheckpointRetainsRegionIdentityUntilExplicitRetry() {
        val region = RegionEntryQueue()
        region.observe("region", "지역", "전주")
        val regionState = state.copy(regionId = "region", topic = null, output = null, gear = Gear.P, speedKph = 0.0)
        val conditions = RegionEntryConditions(true, true, true, false, false, false, false)
        val entry = assertNotNull(region.begin(regionState, conditions))
        region.failed(entry.speechId, "backend failed")
        assertFalse(region.resumeFailedCheckpoint("other"))
        assertTrue(region.resumeFailedCheckpoint(entry.speechId))
        assertEquals(entry.speechId, region.active?.speechId)
        assertTrue(region.complete(entry.speechId))
    }
    @Test fun focusStopsEngineOutputButCheckpointSurvivesAndResumesOnceWithSameIdentity() {
        val q = queue()
        val guarded = HyundaehwaEngine.reduce(state.copy(output = output), ConversationEvent.FocusChanged(FocusKind.BRANCH, true))
        assertNull(guarded.output)
        assertNull(q.takeIfReady(guarded, "전주", topic.id, safe))
        assertNotNull(q.pending)
        val cleared = HyundaehwaEngine.reduce(guarded, ConversationEvent.FocusChanged(FocusKind.BRANCH, false))
        val resumed = assertNotNull(q.takeIfReady(cleared, "전주", topic.id, safe))
        assertEquals(output.id, resumed.utterance.id)
        assertEquals("어느 산일까요?", resumed.remainingText)
        assertNull(q.takeIfReady(cleared, "전주", topic.id, safe))
    }
    @Test fun endingOneOfMultipleSafetyGuardsDoesNotResumeEarly() {
        val q = queue()
        assertNull(q.takeIfReady(state.copy(guards = setOf(FocusKind.JUNCTION, FocusKind.TUNNEL)), "전주", topic.id, safe))
        assertNull(q.takeIfReady(state.copy(guards = setOf(FocusKind.TUNNEL)), "전주", topic.id, safe))
        assertNotNull(q.takeIfReady(state, "전주", topic.id, safe))
    }
    @Test fun mediaConversationAndMissingAudioWaitRatherThanOverlapOrDropCheckpoint() {
        val q = queue()
        assertNull(q.takeIfReady(state.copy(mediaActive = true), "전주", topic.id, safe))
        assertNull(q.takeIfReady(state.copy(mediaActive = null), "전주", topic.id, safe))
        assertNull(q.takeIfReady(state, "전주", topic.id, safe.copy(otherVoiceActive = true)))
        assertNotNull(q.pending)
        assertNotNull(q.takeIfReady(state, "전주", topic.id, safe))
    }
    @Test fun navigationSpeechMustActuallyEndBeforeResume() {
        val q = queue()
        assertNull(q.takeIfReady(state, "전주", topic.id, safe.copy(navigationVoiceActive = true)))
        assertNull(q.takeIfReady(state, "전주", topic.id, safe.copy(audioFocusBlocked = true)))
        assertNotNull(q.pending)
        assertNotNull(q.takeIfReady(state, "전주", topic.id, safe))
    }
    @Test fun quietOffAndArrivalDiscardOldExplanation() {
        for (invalid in listOf(state.copy(quiet = true), state.copy(enabled = false), state.copy(arrived = true))) {
            val q = queue()
            assertNull(q.takeIfReady(invalid, "전주", topic.id, safe)); assertNull(q.pending)
            assertNull(q.takeIfReady(state, "전주", topic.id, safe))
        }
    }
    @Test fun changedDestinationOrCardCannotResurrectOldExplanation() {
        for ((destination, card) in listOf("부산" to topic.id, "전주" to "new-question", "전주" to null)) {
            val q = queue()
            assertNull(q.takeIfReady(state, destination, card, safe)); assertNull(q.pending)
        }
    }
    @Test fun voiceDisabledCancelsButLeavingScreenHoldsUntilReturn() {
        val disabled = queue()
        assertNull(disabled.takeIfReady(state, "전주", topic.id, safe.copy(voiceEnabled = false)))
        assertNull(disabled.pending)
        val background = queue()
        assertNull(background.takeIfReady(state, "전주", topic.id, safe.copy(resumed = false)))
        assertNotNull(background.pending)
        assertEquals("어느 산일까요?", background.takeIfReady(state, "전주", topic.id, safe)?.remainingText)
    }
    @Test fun pendingQuestionKeepsItsAnswerTimerOnHold() {
        val q = queue()
        assertTrue(q.holdsCard(topic.id)); assertFalse(q.holdsCard("other")); assertFalse(q.holdsCard(null))
        q.clear()
        assertFalse(q.holdsCard(topic.id)); assertNull(q.pending)
    }
    @Test fun anotherOutputHasPriorityAndCannotBeReplacedByOldSpeech() {
        val q = queue()
        assertNull(q.takeIfReady(state.copy(output = Utterance("rest-8", "rest", "휴게소예요")), "전주", topic.id, safe))
        assertNotNull(q.pending)
        assertNotNull(q.takeIfReady(state, "전주", topic.id, safe))
    }
}
