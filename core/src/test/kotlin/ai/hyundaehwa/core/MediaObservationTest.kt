package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class MediaObservationTest {
    private val assistant = listOf(PlaybackUsage.ASSISTANT)
    private val media = listOf(PlaybackUsage.MEDIA)
    private val unknown = listOf(PlaybackUsage.UNKNOWN)
    @Test fun unknownExpiredOrFutureObservationNeverBecomesSilence() {
        assertNull(MediaObservation.resolve(null, 1_000, 2_000, false, false, playbackUsages = emptyList()))
        assertNull(MediaObservation.resolve(false, null, 2_000, false, false, playbackUsages = emptyList()))
        assertNull(MediaObservation.resolve(false, 1_000, 9_001, false, false, playbackUsages = emptyList()))
        assertNull(MediaObservation.resolve(false, 2_001, 2_000, false, false, playbackUsages = emptyList()))
        assertEquals(false, MediaObservation.resolve(false, 1_000, 9_000, false, null, playbackUsages = emptyList()))
    }
    @Test fun ownAssistantOnlyCanContinueFromKnownClearState() {
        assertEquals(false, MediaObservation.resolve(true, 2_000, 2_000, true, false, playbackUsages = assistant))
        assertEquals(false, MediaObservation.resolve(false, 2_000, 2_000, true, false, playbackUsages = assistant))
        assertNull(MediaObservation.resolve(true, 2_000, 2_000, false, false, playbackUsages = assistant))
        assertNull(MediaObservation.resolve(true, 2_000, 2_000, true, null, playbackUsages = assistant))
        assertNull(MediaObservation.resolve(true, 2_000, 2_000, true, true, playbackUsages = assistant))
    }
    @Test fun externalOrAmbiguousPlaybackNeverBecomesClearBecauseOfOwnSpeech() {
        assertEquals(true, MediaObservation.resolve(true, 2_000, 2_000, true, false, playbackUsages = media + assistant))
        assertEquals(true, MediaObservation.resolve(false, 2_000, 2_000, true, false, playbackUsages = media))
        assertNull(MediaObservation.resolve(true, 2_000, 2_000, true, false, playbackUsages = assistant + unknown))
        assertNull(MediaObservation.resolve(false, 2_000, 2_000, true, false, playbackUsages = unknown))
        assertNull(MediaObservation.resolve(true, 2_000, 2_000, true, false, playbackUsages = emptyList()))
        assertEquals(true, MediaObservation.resolve(true, 2_000, 2_000, true, false, playbackUsages = null))
        assertNull(MediaObservation.resolve(false, 2_000, 2_000, true, false, playbackUsages = null))
    }
    @Test fun manualExternalMediaOverridesAllObservations() {
        assertEquals(true, MediaObservation.resolve(false, 1_000, 1_000, false, false, true, emptyList()))
        assertEquals(true, MediaObservation.resolve(null, null, 1_000, true, false, true, null))
        assertEquals(false, MediaObservation.resolve(false, 1_000, 1_000, false, true, false, emptyList()))
    }
}
