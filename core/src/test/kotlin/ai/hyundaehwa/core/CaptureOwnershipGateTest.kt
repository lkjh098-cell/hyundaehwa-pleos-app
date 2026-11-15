package ai.hyundaehwa.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.Test

class CaptureOwnershipGateTest {
    @Test fun recorderConstructedAfterRevocationNeverStarts() {
        val gate = CaptureOwnershipGate<Any>()
        val oldToken = requireNotNull(gate.request(true))
        // Worker is constructing AudioRecord while the UI yields to answer recognition.
        gate.request(false)
        var starts = 0
        var stops = 0
        val lateRecorder = Any()
        assertFalse(gate.start(oldToken, lateRecorder, { starts++ }, { stops++ }))
        gate.finish(lateRecorder)
        assertEquals(0, starts)
        assertEquals(0, stops)
        assertFalse(gate.isCurrent(oldToken))
    }

    @Test(timeout = 5_000) fun revokeWaitsForInFlightStartAndStopsBeforeHandoffReturns() {
        val gate = CaptureOwnershipGate<Any>()
        val token = requireNotNull(gate.request(true))
        val enteredStart = CountDownLatch(1)
        val finishStart = CountDownLatch(1)
        val requestedRevoke = CountDownLatch(1)
        val finishedRevoke = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val starter = thread {
            gate.start(token, Any(), {
                events += "start-enter"
                enteredStart.countDown()
                check(finishStart.await(3, TimeUnit.SECONDS))
                events += "start-done"
            }, { events += "stop" })
        }
        assertTrue(enteredStart.await(2, TimeUnit.SECONDS))
        val revoker = thread {
            requestedRevoke.countDown()
            gate.request(false)
            events += "handoff-return"
            finishedRevoke.countDown()
        }
        try {
            assertTrue(requestedRevoke.await(2, TimeUnit.SECONDS))
            assertEquals(1L, finishedRevoke.count) // Start still holds ownership lock.
        } finally { finishStart.countDown() }
        starter.join(2_000)
        revoker.join(2_000)
        assertFalse(starter.isAlive)
        assertFalse(revoker.isAlive)
        assertEquals(listOf("start-enter", "start-done", "stop", "handoff-return"), events.toList())
        assertFalse(gate.isCurrent(token))
    }

    @Test fun oldWorkerCleanupCannotStopANewGeneration() {
        val gate = CaptureOwnershipGate<Any>()
        val old = Any()
        val fresh = Any()
        var oldStops = 0
        var freshStops = 0
        assertTrue(gate.start(requireNotNull(gate.request(true)), old, {}, { oldStops++ }))
        gate.request(false)
        assertEquals(1, oldStops)
        val currentToken = requireNotNull(gate.request(true))
        assertTrue(gate.start(currentToken, fresh, {}, { freshStops++ }))
        gate.finish(old)
        assertEquals(0, freshStops)
        assertTrue(gate.isCurrent(currentToken))
        gate.close()
        gate.finish(fresh)
        gate.close()
        assertEquals(1, freshStops)
        assertEquals(1, oldStops)
    }

    @Test fun closeRevokesUnstartedWorkerPermanently() {
        val gate = CaptureOwnershipGate<Any>()
        val token = requireNotNull(gate.request(true))
        gate.close()
        assertNull(gate.request(true))
        assertFalse(gate.start(token, Any(), { fail("A late worker started after close") }, {}))
    }

    @Test fun failedStartStopsItsPartiallyStartedRecorderAndDoesNotLeaveAnOwner() {
        val gate = CaptureOwnershipGate<Any>()
        val token = requireNotNull(gate.request(true))
        var stops = 0
        assertFailsWith<IllegalStateException> {
            gate.start(token, Any(), { error("AudioRecord start failed") }, { stops++ })
        }
        assertEquals(1, stops)
        assertTrue(gate.start(token, Any(), {}, { stops++ }))
        gate.request(false)
        assertEquals(2, stops)
    }
}
