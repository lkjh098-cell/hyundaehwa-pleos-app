package ai.hyundaehwa.car

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Clean-engine baseline using official ON_DEVICE sendAudio/completeAudioSend APIs.
 * No microphone, request(), parser, answer submission or recognized-text injection.
 * A passing test means a real final callback was collected, NOT accurate recognition or mic acceptance.
 */
@RunWith(AndroidJUnit4::class)
class DirectPcmRecognitionDiagnosticTest {
    @Test(timeout = 120_000L)
    fun collectOriginalPcmRecognition() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit diagnostic opt-in required", args.getString("direct_pcm") == "true")
        val context = inst.targetContext
        val target = args.getString("target_package") ?: "ai.hyundaehwa.car.releaseqa"
        assertTrue(target in setOf("ai.hyundaehwa.car.preview", "ai.hyundaehwa.car.releaseqa"))
        assertEquals(target, context.packageName)
        if (target.endsWith(".releaseqa")) {
            assertFalse(BuildConfig.DEBUG)
            assertFalse((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            assertFalse(BuildConfig.ENABLE_GEMINI_RELAY)
        }
        val answerCase = args.getString("answer_case") ?: "number"
        require(answerCase in setOf("number", "label", "short_label"))
        val wav = inst.context.assets.open("direct_pcm_baseline/$answerCase.wav").use { it.readBytes() }
        val pcm = readMono16kPcm(wav)
        // Preserve original speech samples; fixed padding is the same diagnostic lead/tail for both cases.
        val payload = ByteArray(32_000 + pcm.size + 128_000)
        pcm.copyInto(payload, 32_000)
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val observations = CopyOnWriteArrayList<String>()
        val closed = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val initialized = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val finals = CopyOnWriteArrayList<String>()
        fun observe(event: String, vararg values: Pair<String, Any>) {
            if (closed.get()) return
            val json = JSONObject().put("event", event).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("answerCase", answerCase).put("targetPackage", target)
                .put("scope", "direct PCM engine diagnostic; not microphone acceptance")
            values.forEach { json.put(it.first, it.second) }
            observations += json.toString()
            Log.i("HyundaehwaPcmBaseline", json.toString())
        }
        val listener = object : ResultListener {
            override fun onReady() = observe("sdk_ready")
            override fun onStartedRecognition() = observe("speech_started")
            override fun onUpdated(stt: String, completed: Boolean) {
                observe("transcript", "text" to stt, "completed" to completed)
                if (completed) { finals += stt; terminal.countDown() }
            }
            override fun onUpdatedEpdData(on: Long, off: Long) = observe("epd", "on" to on, "off" to off)
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onEndedRecognition() = observe("speech_ended")
            override fun onError(errorType: ErrorType) {
                observe("sdk_error", "error" to errorType.name)
                failure.compareAndSet(null, errorType.name)
                terminal.countDown()
            }
        }
        inst.uiAutomation.grantRuntimePermission(target, Manifest.permission.RECORD_AUDIO)
        val prefs = context.getSharedPreferences("conversation_profile", Context.MODE_PRIVATE)
        val keys = setOf("spoken_guidance", "audio_setup_seen", "on_device_card_answers", "cabin_sound_monitor")
        val original = prefs.all.filterKeys { it in keys }
        var activity: MainActivity? = null
        var engine: SpeechToText? = null
        try {
            assertTrue(prefs.edit().putBoolean("spoken_guidance", false).putBoolean("audio_setup_seen", true)
                .putBoolean("on_device_card_answers", false).putBoolean("cabin_sound_monitor", false).commit())
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
                engine = stt
                stt.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        if (closed.get()) return
                        runCatching { stt.setLocale(Locale.KOREA); stt.addListener(listener) }
                            .onSuccess { observe("initialized") }
                            .onFailure { failure.set(it.toString()) }
                        initialized.countDown()
                    }
                    override fun onFailure(errorType: ErrorType) {
                        failure.set("initialize: ${errorType.name}")
                        observe("initialize_error", "error" to errorType.name)
                        initialized.countDown()
                    }
                })
            }
            assertTrue("SDK initialization timed out", initialized.await(30, TimeUnit.SECONDS))
            assertNull("SDK initialization failed", failure.get())
            observe("payload", "wavSha256" to sha(wav), "speechPcmSha256" to sha(pcm),
                "payloadSha256" to sha(payload), "speechBytes" to pcm.size, "payloadBytes" to payload.size,
                "leadInMs" to 1000, "tailMs" to 4000, "sampleRate" to 16000, "channels" to 1)
            // Official whole-buffer flow; do not call request(), which would open the service microphone.
            inst.runOnMainSync {
                requireNotNull(engine).sendAudio(payload)
                observe("send_audio_returned")
                requireNotNull(engine).completeAudioSend()
                observe("complete_audio_send_returned")
            }
            val received = terminal.await(60, TimeUnit.SECONDS)
            // Retain follow-up final/error callbacks; EPD end is not itself a final transcript.
            if (received) Thread.sleep(2_000)
            observe("diagnostic_complete", "finalCallbackCount" to finals.size,
                "finalTexts" to org.json.JSONArray(finals), "error" to (failure.get() ?: ""),
                "resultReceived" to received, "recognitionAccuracyAsserted" to false)
            assertTrue("No final/error callback from direct PCM input", received)
            assertNull("SDK direct PCM failure", failure.get())
            assertTrue("A completed SDK transcript callback is required; its text is not forced", finals.isNotEmpty())
        } finally {
            closed.set(true)
            inst.runOnMainSync {
                engine?.let { stt ->
                    runCatching { stt.removeListener(listener) }
                    runCatching { stt.stop() }
                    runCatching { stt.release() }
                }
                activity?.finish()
            }
            observations.forEach { row ->
                inst.sendStatus(0, Bundle().apply { putString("direct_pcm_observation", row) })
            }
            val edit = prefs.edit()
            keys.forEach { key ->
                if (original.containsKey(key)) edit.putBoolean(key, original[key] as Boolean) else edit.remove(key)
            }
            assertTrue(edit.commit())
        }
    }

    private fun readMono16kPcm(wav: ByteArray): ByteArray {
        require(wav.size >= 12 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF"
            && String(wav, 8, 4, Charsets.US_ASCII) == "WAVE")
        val bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var formatVerified = false
        var pcm: ByteArray? = null
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = bytes.getInt(offset + 4)
            require(size >= 0 && size <= wav.size - offset - 8)
            val start = offset + 8
            if (id == "fmt ") {
                require(size >= 16 && bytes.getShort(start).toInt() == 1
                    && bytes.getShort(start + 2).toInt() == 1 && bytes.getInt(start + 4) == 16000
                    && bytes.getShort(start + 14).toInt() == 16)
                formatVerified = true
            } else if (id == "data") {
                require(pcm == null && size > 0 && size % 2 == 0)
                pcm = wav.copyOfRange(start, start + size)
            }
            offset = start + size + size % 2
        }
        require(formatVerified)
        return requireNotNull(pcm)
    }
}
