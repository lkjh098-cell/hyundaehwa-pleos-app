package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class DemoPlaybackClockTest {
    @Test fun supportedSpeedsAndDefaultAreExplicit() {
        assertEquals(listOf(1, 3, 6, 12, 24), DemoPlaybackClock.speeds)
        assertEquals(3, DemoPlaybackClock.DEFAULT_SPEED)
    }
    @Test fun changingSpeedDuringDemoDriveScalesOnlyNavigationTime() {
        var state = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D, speedKph = 80.0,
            destination = "", regionId = "", mediaActive = false)
        var navigation = 0L
        for (speed in listOf(1, 3, 6, 12, 24, 1)) {
            val delta = DemoPlaybackClock.navigationDelta(1000, true, true, speed)
            state = HyundaehwaEngine.reduce(state, ConversationEvent.Tick(delta, 1000))
            navigation += delta
            assertEquals(80.0, state.speedKph)
            assertEquals(Gear.D, state.gear)
            assertEquals(navigation, state.simElapsedMs)
        }
        assertEquals(6000L, state.wallElapsedMs)
        assertEquals(47000L, state.simElapsedMs)
        assertEquals(47000L, state.drivingMs)
    }
    @Test fun realVehicleAndReleaseBuildTimeNeverMultiply() {
        for (speed in DemoPlaybackClock.speeds) {
            assertEquals(1000L, DemoPlaybackClock.navigationDelta(1000, true, false, speed))
            assertEquals(1000L, DemoPlaybackClock.navigationDelta(1000, false, true, speed))
            assertEquals(1000L, DemoPlaybackClock.navigationDelta(1000, false, false, speed))
        }
    }
    @Test fun navigationFiveMinutesAccelerateButAnswerRevealStillNeedsEightWallSeconds() {
        val navigation = DemoPlaybackClock.navigationDelta(12_500, true, true, 24)
        assertEquals(300_000L, navigation)
        assertFalse(PinnedQuizTiming.shouldReveal(7_999, 0, false))
        assertTrue(PinnedQuizTiming.shouldReveal(8_000, 0, false))
    }
    @Test fun invalidRatesFailClosedAndNegativeTimeNeverMovesClockBackwards() {
        assertEquals(1000L, DemoPlaybackClock.navigationDelta(1000, true, true, 999))
        assertEquals(0L, DemoPlaybackClock.navigationDelta(-1000, true, true, 24))
        assertTrue(DemoPlaybackClock.navigationDelta(Long.MAX_VALUE, true, true, 24) > 0)
    }
}
