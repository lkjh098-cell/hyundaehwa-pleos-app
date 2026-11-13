package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class SilencePolicyTest {
    private fun send(s: ConversationState, e: ConversationEvent) = HyundaehwaEngine.reduce(s, e)
    private fun ready(): ConversationState {
        var s = HyundaehwaEngine.simulation().copy(rotationRemaining = FourContentRotation.order)
        s = send(s, ConversationEvent.RegionalTopicsChanged(s.regionId, listOf(Topic("q", TopicType.QUIZ, s.regionId, "질문"))))
        s = send(s, ConversationEvent.SilenceThresholdChanged(5_000))
        s = send(s, ConversationEvent.Start)
        return send(s, ConversationEvent.GearChanged(Gear.D, 80.0))
    }
    private fun quietTick(s: ConversationState, sim: Long = 1_000): ConversationState =
        send(send(s, ConversationEvent.SoundLevel(0.01)), ConversationEvent.Tick(sim, 1_000))

    @Test fun silenceUsesSimulationTimeAndFreshQuietInput() {
        var s = ready()
        repeat(4) { s = quietTick(s) }
        assertNull(s.output)
        s = quietTick(s)
        assertEquals("q", s.topic?.id)
    }
    @Test fun unknownOrStaleMicrophoneCannotAccumulate() {
        var s = send(ready(), ConversationEvent.SoundUnavailable())
        s = send(s, ConversationEvent.Tick(60_000, 1_000))
        assertEquals(0L, s.silenceMs)
        s = send(s, ConversationEvent.SoundLevel(0.01))
        s = send(s, ConversationEvent.Tick(60_000, 3_000))
        assertEquals(0L, s.silenceMs)
        assertNull(s.output)
    }
    @Test fun conversationResetsAndUnknownMediaBlocks() {
        var s = ready()
        repeat(3) { s = quietTick(s) }
        s = send(s, ConversationEvent.SoundLevel(0.1))
        assertEquals(0L, s.silenceMs)
        s = send(s, ConversationEvent.MediaChanged(null))
        repeat(8) { s = quietTick(s) }
        assertEquals(0L, s.silenceMs)
        assertNull(s.output)
    }
    @Test fun weatherIsFirstAndPendingRegionWaitsForAnotherQuietInterval() {
        var s = send(ready(), ConversationEvent.WeatherReady("목적지에 비가 와요"))
        repeat(5) { s = quietTick(s) }
        assertEquals("weather", s.output?.kind)
        assertEquals(s.regionId, s.pendingRegionId)
        s = send(s, ConversationEvent.OutputDone(s.output!!.id))
        repeat(5) { s = quietTick(s) }
        assertEquals("q", s.topic?.id)
    }
    @Test fun pendingRegionDoesNotReplaceAnActiveQuestion() {
        var s = ready()
        repeat(5) { s = quietTick(s) }
        val question = s.topic
        s = send(s, ConversationEvent.AnswerExplanationHold(true))
        s = send(s, ConversationEvent.RegionChanged("next", "다음 지역", s.destination))
        s = send(s, ConversationEvent.RegionalTopicsChanged("next", listOf(Topic("next-q", TopicType.QUIZ, "next", "다음 질문"))))
        repeat(8) { s = quietTick(s) }
        assertEquals(question, s.topic)
        assertEquals("next", s.pendingRegionId)
    }
    @Test fun guardAndMediaPreventDeliveryAndRestartSilence() {
        var s = ready()
        repeat(4) { s = quietTick(s) }
        s = send(s, ConversationEvent.FocusChanged(FocusKind.MERGE, true))
        repeat(6) { s = quietTick(s) }
        assertNull(s.output)
        s = send(s, ConversationEvent.FocusChanged(FocusKind.MERGE, false))
        repeat(4) { s = quietTick(s) }
        assertNull(s.output)
        s = send(s, ConversationEvent.MediaChanged(true))
        s = send(s, ConversationEvent.MediaChanged(false))
        repeat(5) { s = quietTick(s) }
        assertNotNull(s.topic)
    }
    @Test fun restPromptUsesOnlyObservedCandidatesAndCancellationDoesNotResetDriving() {
        var s = ready().copy(drivingMs = ConversationLimits.REST_MS - 1000)
        s = send(s, ConversationEvent.RestOptionsChanged(listOf("실제 후보 A", "실제 후보 B")))
        s = quietTick(s)
        assertEquals("rest", s.output?.kind)
        assertTrue(s.output!!.text.contains("실제 후보 A와 실제 후보 B"))
        s = send(s, ConversationEvent.RestCancelled)
        assertEquals(ConversationLimits.REST_MS, s.drivingMs)
        assertTrue(s.restRequested)
    }

}
