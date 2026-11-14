package ai.hyundaehwa.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalFreshnessTest {
    @Test fun acceptsCurrentAndInclusiveExpiryBoundary() {
        assertTrue(SignalFreshness.isFresh(10_000L, 10_000L))
        assertTrue(SignalFreshness.isFresh(10_000L, 18_000L))
    }

    @Test fun rejectsMissingFutureAndExpiredObservations() {
        assertFalse(SignalFreshness.isFresh(null, 10_000L))
        assertFalse(SignalFreshness.isFresh(10_001L, 10_000L))
        assertFalse(SignalFreshness.isFresh(10_000L, 18_001L))
    }
}
