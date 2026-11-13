package ai.hyundaehwa.car.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the actual microphone; a failed measurement is explicitly distinct from silence. */
@RunWith(AndroidJUnit4::class)
class CabinSoundMonitorIntegrationTest {
    @Test(timeout = 15_000L)
    fun actualInputIsUnavailableDuringRecognitionAndResumesWithoutLateCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("ai.hyundaehwa.car.preview", context.packageName)
        assumeTrue("Microphone permission must already be granted for this integration test",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val events = LinkedBlockingQueue<CabinSoundState>()
        val callbackCount = AtomicInteger()
        var monitor: CabinSoundMonitor? = null
        fun nextMeasurement(phase: String): CabinSoundState {
            val state = events.poll(4, TimeUnit.SECONDS)
            assertNotNull("No microphone status received during $phase", state)
            val observed = state!!
            if (observed.available) {
                val level = observed.rms
                assertNotNull("Available measurement must have an RMS", level)
                assertTrue("RMS must be finite and normalized", level!!.isFinite() && level in 0.0..1.0)
            } else {
                assertNull("Unavailable input must not be represented as silence", observed.rms)
                assertTrue("Unavailable input needs an explicit reason", !observed.error.isNullOrBlank())
            }
            return observed
        }
        try {
            instrumentation.runOnMainSync {
                monitor = CabinSoundMonitor(context) { state -> callbackCount.incrementAndGet(); events.offer(state) }
                monitor!!.setEnabled(true)
            }
            val initial = nextMeasurement("initial capture")
            instrumentation.runOnMainSync { monitor!!.setRecognitionActive(true) }
            var during: CabinSoundState? = null
            val pauseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < pauseDeadline) {
                val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!event.available && event.error == "답변을 듣고 있어요.") { during = event; break }
            }
            assertNotNull("Recognition must release the microphone and publish the listening state", during)
            assertNull("Recognition pause is unavailable, not silence", during!!.rms)
            events.clear()
            instrumentation.runOnMainSync { monitor!!.setRecognitionActive(false) }
            val resumed = nextMeasurement("resumed capture")
            instrumentation.runOnMainSync { monitor!!.close() }
            val countAtClose = callbackCount.get()
            Thread.sleep(750)
            instrumentation.waitForIdleSync()
            assertEquals("No stale microphone callbacks may arrive after close", countAtClose, callbackCount.get())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("cabin_sound_monitor_observation", "initial=${if (initial.available) "available" else "unavailable"}; " +
                    "recognition=unavailable; resumed=${if (resumed.available) "available" else "unavailable"}; " +
                    "initialReason=${initial.error.orEmpty()}; resumedReason=${resumed.error.orEmpty()}; " +
                    "microphone level accuracy and speech recognition were not evaluated")
            })
        } finally {
            instrumentation.runOnMainSync { monitor?.close() }
        }
    }
}
