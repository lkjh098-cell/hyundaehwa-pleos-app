package ai.hyundaehwa.core

import org.junit.Test
import kotlin.test.*

class VoicePlaybackTest {
    private fun requested(token: Long = 1) = VoicePlayback.reduce(VoicePlaybackState(), VoicePlaybackEvent.Request(token, "test-$token", VoicePurpose.TEST))
    @Test fun requestAcceptanceNeverClaimsAudiblePlayback() {
        val s = requested()
        assertEquals(VoicePhase.PREPARING, s.phase)
        assertNotEquals(VoicePhase.PLAYING, s.phase)
    }
    @Test fun onlyStartThenDoneCallbacksConfirmPlaybackCompletion() {
        val playing = VoicePlayback.reduce(requested(), VoicePlaybackEvent.Started(1))
        assertEquals(VoicePhase.PLAYING, playing.phase)
        assertEquals(VoicePhase.COMPLETED, VoicePlayback.reduce(playing, VoicePlaybackEvent.Done(1)).phase)
    }
    @Test fun doneWithoutStartIsFailureRatherThanFalseSuccess() {
        assertEquals(VoicePhase.FAILED, VoicePlayback.reduce(requested(), VoicePlaybackEvent.Done(1)).phase)
    }
    @Test fun cancellationCannotBeRevivedByLateCallbacks() {
        val stopped = VoicePlayback.reduce(requested(), VoicePlaybackEvent.Cancel(1, "drive"))
        assertEquals(VoicePhase.CANCELLED, stopped.phase)
        assertEquals(stopped, VoicePlayback.reduce(stopped, VoicePlaybackEvent.Started(1)))
        assertEquals(stopped, VoicePlayback.reduce(stopped, VoicePlaybackEvent.Done(1)))
    }
    @Test fun oldRequestCallbacksCannotCompleteOrFailNewRequest() {
        val next = VoicePlayback.reduce(requested(), VoicePlaybackEvent.Request(2, "test-2", VoicePurpose.TEST))
        assertEquals(next, VoicePlayback.reduce(next, VoicePlaybackEvent.Done(1)))
        assertEquals(next, VoicePlayback.reduce(next, VoicePlaybackEvent.Failed(1, "old failure")))
        assertEquals(next, VoicePlayback.reduce(next, VoicePlaybackEvent.Started(1)))
    }
    @Test fun focusFailureRequiresAnExplicitNewRequest() {
        val failed = VoicePlayback.reduce(requested(), VoicePlaybackEvent.Failed(1, "focus denied"))
        assertEquals(failed, VoicePlayback.reduce(failed, VoicePlaybackEvent.Started(1)))
        val retry = VoicePlayback.reduce(failed, VoicePlaybackEvent.Request(2, "manual-retry", VoicePurpose.TEST))
        assertEquals(VoicePhase.PREPARING, retry.phase)
    }
    @Test fun fallbackSourceIsExplicitAndNotAPleosSuccess() {
        val s = VoicePlayback.reduce(requested(), VoicePlaybackEvent.Backend(1, VoiceSource.ANDROID_FALLBACK, "local engine", "Pleos unavailable"))
        assertEquals(VoiceSource.ANDROID_FALLBACK, s.source)
        assertEquals("Pleos unavailable", s.fallbackReason)
        assertEquals(VoicePhase.PREPARING, s.phase)
    }
    @Test fun fallbackAfterAStartedEngineRequiresTheNewBackendToStartBeforeCompletion() {
        val prior = VoicePlayback.reduce(requested(), VoicePlaybackEvent.Started(1))
        val fallback = VoicePlayback.reduce(prior, VoicePlaybackEvent.Backend(1, VoiceSource.RECORDED_CACHE, "local recording", "synthesis failed"))
        assertEquals(VoicePhase.PREPARING, fallback.phase)
        assertEquals(VoicePhase.FAILED, VoicePlayback.reduce(fallback, VoicePlaybackEvent.Done(1)).phase)
        val playing = VoicePlayback.reduce(fallback, VoicePlaybackEvent.Started(1))
        assertEquals(VoicePhase.COMPLETED, VoicePlayback.reduce(playing, VoicePlaybackEvent.Done(1)).phase)
    }
    @Test fun onlyVerifiedRecordedCompletionUsesTheShorterEchoGuard() {
        for (source in VoiceSource.entries) for (phase in VoicePhase.entries) {
            val expected = if (source == VoiceSource.RECORDED_CACHE && phase == VoicePhase.COMPLETED) 300L else 650L
            assertEquals(expected, VoiceEchoDelay.milliseconds(VoicePlaybackState(source = source, phase = phase)), "$source/$phase")
        }
    }
    @Test fun replayedOrOlderRequestTokensCannotReplaceCurrentRequest() {
        val s = requested(3)
        assertEquals(s, VoicePlayback.reduce(s, VoicePlaybackEvent.Request(3, "duplicate", VoicePurpose.TEST)))
        assertEquals(s, VoicePlayback.reduce(s, VoicePlaybackEvent.Request(2, "old", VoicePurpose.TEST)))
    }
    private fun clearTestConditions() = VoiceTestConditions(true, true, false, emptySet(), false, false)
    @Test fun everyFocusGateBlocksParkedVoiceTest() {
        val clear = clearTestConditions()
        assertNull(VoiceTestPolicy.blockReason(clear))
        FocusKind.entries.forEach { kind ->
            assertNotNull(VoiceTestPolicy.blockReason(clear.copy(guards = setOf(kind))))
        }
    }
    @Test fun mediaBlocksParkedTestAndSuppliesCancellationDuringPreparationOrPlayback() {
        val blocked = clearTestConditions().copy(mediaActive = true)
        val reason = assertNotNull(VoiceTestPolicy.blockReason(blocked))
        listOf(requested(), VoicePlayback.reduce(requested(), VoicePlaybackEvent.Started(1))).forEach { active ->
            val stopped = VoicePlayback.reduce(active, VoicePlaybackEvent.Cancel(active.token, reason))
            assertEquals(VoicePhase.CANCELLED, stopped.phase)
            assertEquals(stopped, VoicePlayback.reduce(stopped, VoicePlaybackEvent.Done(active.token)))
        }
    }
    @Test fun sharedTestPolicyRetainsParkLifecycleQuietAndNavigationChecks() {
        val clear = clearTestConditions()
        listOf(clear.copy(parked = false), clear.copy(resumed = false), clear.copy(quiet = true), clear.copy(navigationVoiceActive = true))
            .forEach { assertNotNull(VoiceTestPolicy.blockReason(it)) }
        assertNull(VoiceTestPolicy.blockReason(clear))
    }

}
