package ai.hyundaehwa.car.platform

import ai.hyundaehwa.car.MainActivity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioRecord
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mandatory real-recorder handoff probe, separate from first-answer acoustic acceptance.
 * Runs the actual CabinSoundMonitor and Pleos SDK backend in a foreground QA process.
 * Unavailable recording, missing SDK readiness and early SDK termination are failures, not skips.
 * No waveform, recognized text, recognition callback or answer selection is injected.
 * This composition does not claim to test Activity wiring, PCM fidelity, ASR accuracy, every
 * construction/start race, or physical hardware exclusivity inside the separate Pleos service.
 */
@RunWith(AndroidJUnit4::class)
class CabinRecognitionOwnershipIntegrationTest {
    @Test(timeout = 100_000L)
    fun availableCabinRecorderStopsBeforeRealPleosRequestAndCannotReacquire() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val target = context.packageName
        assertTrue("Use only an isolated preview or release QA application",
            target in setOf("ai.hyundaehwa.car.preview", "ai.hyundaehwa.car.releaseqa"))
        inst.uiAutomation.grantRuntimePermission(target, Manifest.permission.RECORD_AUDIO)
        val prefs = context.getSharedPreferences("conversation_profile", Context.MODE_PRIVATE)
        val keys = setOf("spoken_guidance", "audio_setup_seen", "on_device_card_answers", "cabin_sound_monitor")
        val original = prefs.all.filterKeys { it in keys }
        var activity: MainActivity? = null
        var monitor: CabinSoundMonitor? = null
        var backend: PleosRecognitionBackend? = null
        var primaryFailure: Throwable? = null
        val observations = LinkedBlockingQueue<CabinSoundState>()
        val handoffActive = AtomicBoolean(false)
        val lateAvailable = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val captureStarted = CountDownLatch(1)
        val backendFailure = AtomicReference<String?>(null)
        val backendEnded = AtomicBoolean(false)

        // Inspect the original recorder, not a fake ownership callback. The same gate lock
        // provides a consistent snapshot; no private state is modified by this probe.
        fun owner(): AudioRecord? {
            val m = requireNotNull(monitor)
            val gate = CabinSoundMonitor::class.java.getDeclaredField("captureGate")
                .apply { isAccessible = true }.get(m)
            return synchronized(gate) {
                gate.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(gate) as? AudioRecord
            }
        }
        fun emit(event: String, vararg values: Pair<String, Any>) {
            val json = JSONObject().put("event", event).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("targetPackage", target)
            values.forEach { json.put(it.first, it.second) }
            inst.sendStatus(0, Bundle().apply { putString("capture_ownership_observation", json.toString()) })
        }
        fun requireAvailable(phase: String): CabinSoundState {
            val until = SystemClock.elapsedRealtime() + 8_000L
            var last: CabinSoundState? = null
            while (SystemClock.elapsedRealtime() < until) {
                val next = observations.poll(100, TimeUnit.MILLISECONDS) ?: continue
                last = next
                if (next.available) {
                    val level = next.rms
                    assertTrue("$phase requires a finite actual PCM measurement", level != null && level.isFinite() && level in 0.0..1.0)
                    return next
                }
            }
            throw AssertionError("$phase must produce available PCM; unavailable is not a pass: $last")
        }
        fun requireRevoked(originalRecorder: AudioRecord) {
            assertNull("Cabin recorder must not own the gate during Pleos recognition", owner())
            assertNotEquals("Original AudioRecord must actually be stopped or released", AudioRecord.RECORDSTATE_RECORDING,
                originalRecorder.recordingState)
            assertFalse("No late available cabin measurement may cross handoff", lateAvailable.get())
        }
        try {
            assertTrue(prefs.edit().putBoolean("spoken_guidance", false).putBoolean("audio_setup_seen", true)
                .putBoolean("on_device_card_answers", false).putBoolean("cabin_sound_monitor", false).commit())
            // Foreground lifecycle only. The Activity's own recorder/answers stay disabled;
            // this probe explicitly composes the real recorder and real SDK backend below.
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            inst.runOnMainSync {
                monitor = CabinSoundMonitor(context) { observed ->
                    if (handoffActive.get() && observed.available) lateAvailable.set(true)
                    observations.offer(observed)
                }
                monitor!!.setEnabled(true)
            }
            val initial = requireAvailable("Initial cabin recording")
            var recorder: AudioRecord? = null
            inst.runOnMainSync {
                recorder = owner()
                assertNotNull("Available status must belong to the original real AudioRecord", recorder)
                assertEquals(AudioRecord.STATE_INITIALIZED, recorder!!.state)
                assertEquals(AudioRecord.RECORDSTATE_RECORDING, recorder!!.recordingState)
                emit("cabin_available", "rms" to initial.rms!!, "audioSessionId" to recorder!!.audioSessionId)
                monitor!!.setRecognitionActive(true)
                handoffActive.set(true)
                requireRevoked(recorder!!)
                backend = PleosRecognitionBackend(context)
                assertEquals("Pleos 기기 내 음성 인식 · ko-KR", backend!!.source)
                backend!!.prepare({ ready.countDown() }, { reason -> backendFailure.set(reason); ready.countDown() })
            }
            assertTrue("Actual Pleos initialization must complete", ready.await(30, TimeUnit.SECONDS))
            assertNull("Pleos initialization failed", backendFailure.get())
            inst.runOnMainSync {
                requireRevoked(recorder!!)
                backend!!.start(object : RecognitionEvents {
                    override fun started() { captureStarted.countDown() }
                    override fun text(text: String, final: Boolean) = Unit // Observed SDK data; never submitted as an answer.
                    override fun ended() { backendEnded.set(true) }
                    override fun noSpeech() { backendEnded.set(true) }
                    override fun failed(reason: String) {
                        backendFailure.set(reason); captureStarted.countDown()
                    }
                })
                emit("pleos_request_issued", "source" to backend!!.source)
            }
            assertTrue("Actual Pleos capture readiness callback is mandatory", captureStarted.await(30, TimeUnit.SECONDS))
            assertNull("Pleos request failed", backendFailure.get())
            assertFalse("Pleos request ended before readiness", backendEnded.get())
            emit("pleos_ready_without_cabin_owner", "source" to backend!!.source)
            val until = SystemClock.elapsedRealtime() + 1_000L
            while (SystemClock.elapsedRealtime() < until) {
                inst.runOnMainSync { requireRevoked(recorder!!) }
                assertNull("Pleos request failed during ownership observation", backendFailure.get())
                assertFalse("Pleos request ended during ownership observation", backendEnded.get())
                Thread.sleep(20)
            }
            inst.runOnMainSync {
                backend!!.release(); backend = null
                requireRevoked(recorder!!)
                handoffActive.set(false)
                observations.clear()
                monitor!!.setRecognitionActive(false)
            }
            val resumed = requireAvailable("Resumed cabin recording")
            inst.runOnMainSync {
                val fresh = owner()
                assertNotNull("Resumed PCM must have a real recorder", fresh)
                assertNotSame("A retired recorder must not be reused", recorder, fresh)
                assertEquals(AudioRecord.RECORDSTATE_RECORDING, fresh!!.recordingState)
                emit("handoff_verified", "initialAvailable" to true, "resumedAvailable" to resumed.available,
                    "lateAvailableDuringRecognition" to lateAvailable.get(),
                    "scope" to "Original app AudioRecord recording state and gate ownership; actual Pleos request/readiness; no text or callback injection; no acoustic-answer or service-internal hardware exclusivity claim")
            }
        } catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally {
            var cleanupFailure: Throwable? = null
            try { inst.runOnMainSync { backend?.release(); monitor?.close(); activity?.finish() } }
            catch (failure: Throwable) { cleanupFailure = failure }
            try {
                val edit = prefs.edit()
                keys.forEach { key -> val value = original[key]; if (value is Boolean) edit.putBoolean(key, value) else edit.remove(key) }
                assertTrue(edit.commit())
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
            }
            cleanupFailure?.let { if (primaryFailure == null) throw it else primaryFailure!!.addSuppressed(it) }
        }
    }
}
