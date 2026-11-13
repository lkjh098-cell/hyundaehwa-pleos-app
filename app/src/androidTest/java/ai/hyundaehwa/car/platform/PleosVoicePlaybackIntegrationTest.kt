package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Emits actual speech for external loopback recording. Callbacks alone never certify audibility. */
@RunWith(AndroidJUnit4::class)
class PleosVoicePlaybackIntegrationTest {
    private val text = "맞아요. 정답은 2번 3·1운동입니다. 탑골공원은 1919년 독립선언문 낭독과 만세 시위가 이루어진 3·1운동의 출발지입니다."

    // These three exact sentences are individually present once in the packaged provenance manifest.
    private val recordedText = "맞아요. 정답은 2번입니다. 탑골공원은 1919년 독립선언문 낭독과 만세 시위가 이루어진 3·1운동의 출발지입니다."

    @Test fun coordinatorReadsAcknowledgementAnswerAndEntireExplanation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val terminal = CountDownLatch(1)
        val startedAt = AtomicLong(0)
        val finishedAt = AtomicLong(0)
        val last = AtomicReference(VoicePlaybackState())
        var output: AndroidSpeechOutput? = null
        try {
            instrumentation.runOnMainSync {
                output = AndroidSpeechOutput(context, onState = { state ->
                    last.set(state)
                    if (state.phase == VoicePhase.PLAYING) startedAt.compareAndSet(0, SystemClock.elapsedRealtime())
                    if (state.phase in setOf(VoicePhase.COMPLETED, VoicePhase.FAILED, VoicePhase.CANCELLED)) {
                        finishedAt.set(SystemClock.elapsedRealtime()); terminal.countDown()
                    }
                })
                assertTrue(output!!.speak("instrumented-full-explanation", text, VoicePurpose.TEST))
            }
            assertTrue("Actual speech did not finish within 60 seconds", terminal.await(60, TimeUnit.SECONDS))
            assertEquals("Actual backend did not complete: ${last.get().error}", VoicePhase.COMPLETED, last.get().phase)
            assertTrue("No actual start callback", startedAt.get() > 0)
            assertTrue("Premature completion before a full explanation could play", finishedAt.get() - startedAt.get() >= 3_000)
        } finally {
            instrumentation.runOnMainSync { output?.shutdown() }
            proof(context, "coordinator", JSONObject().apply {
                put("backend", last.get().source.name); put("phase", last.get().phase.name)
                put("actualStartObserved", startedAt.get() > 0)
                put("playbackMs", (finishedAt.get() - startedAt.get()).coerceAtLeast(0))
            })
        }
    }

    @Test fun exactPackagedRecordingPlaysAllFeedbackClipsForTheirMeasuredDuration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val catalog = CachedVoiceCatalog(context)
        val plan = checkNotNull(catalog.plan(recordedText)) { "Full exact-text recording coverage is required" }
        assertEquals(recordedText, plan.text)
        assertEquals(3, plan.segments.size)
        val expectedMs = (plan.segments.sumOf { it.entry.durationSeconds } * 1000).toLong()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ready = CountDownLatch(1); val terminal = CountDownLatch(1)
        val error = AtomicReference<String?>()
        val startedAt = AtomicLong(0); val finishedAt = AtomicLong(0)
        val completed = AtomicReference(false)
        var backend: CachedRecordedVoiceBackend? = null
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) {
                error.set("Audio focus was lost during verification"); terminal.countDown()
            } }.build()
        try {
            instrumentation.runOnMainSync {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(focus))
                backend = CachedRecordedVoiceBackend(context, attributes, catalog, recordedText)
                backend!!.prepare({ ready.countDown() }, { reason -> error.set(reason); ready.countDown(); terminal.countDown() })
            }
            assertTrue("Recording preparation timed out", ready.await(20, TimeUnit.SECONDS))
            assertNull(error.get())
            instrumentation.runOnMainSync {
                backend!!.speak(recordedText, object : PlaybackListener {
                    override fun onStarted() { startedAt.compareAndSet(0, SystemClock.elapsedRealtime()) }
                    override fun onCompleted() { completed.set(true); finishedAt.set(SystemClock.elapsedRealtime()); terminal.countDown() }
                    override fun onStopped() { error.set("Playback stopped"); terminal.countDown() }
                    override fun onFailed(reason: String) { error.set(reason); terminal.countDown() }
                })
            }
            assertTrue("Full recording playback timed out", terminal.await(45, TimeUnit.SECONDS))
            assertNull(error.get()); assertTrue(completed.get()); assertTrue(startedAt.get() > 0)
            assertTrue("Completion was earlier than complete recorded audio", finishedAt.get() - startedAt.get() >= expectedMs * 8 / 10)
            assertEquals("", backend!!.remainingText)
        } finally {
            instrumentation.runOnMainSync { backend?.release(); audio.abandonAudioFocusRequest(focus) }
            proof(context, "recorded-cache", JSONObject().apply {
                put("text", recordedText); put("allClipsCompleted", completed.get()); put("clipCount", plan.segments.size)
                put("expectedAudioMs", expectedMs); put("playbackMs", (finishedAt.get() - startedAt.get()).coerceAtLeast(0))
            })
        }
    }

    @Test fun actualPlaybackUsagesDoNotMisclassifyOwnAssistantOutputAsExternalMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val main = Handler(Looper.getMainLooper())
        val baselineClear = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val lingeringObserved = CountDownLatch(1)
        val last = AtomicReference(VoicePlaybackState())
        val samples = JSONArray()
        var previous: Boolean? = null
        var playingSamples = 0
        var assistantSamples = 0
        var blockedPlayingSamples = 0
        var monitorRunning = true
        var output: AndroidSpeechOutput? = null
        val origin = SystemClock.elapsedRealtime()
        val monitor = object : Runnable {
            override fun run() {
                if (!monitorRunning) return
                val now = SystemClock.elapsedRealtime()
                val active = runCatching { audio.isMusicActive }.getOrNull()
                // Use only configurations returned by this public active-playback API.
                // It exposes usages, not player ownership; do not infer a UID or invent activity flags.
                val raw = runCatching { audio.activePlaybackConfigurations.map { it.audioAttributes.usage } }.getOrNull()
                val usages = raw?.map { usage -> when (usage) {
                    AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME -> PlaybackUsage.MEDIA
                    AudioAttributes.USAGE_ASSISTANT -> PlaybackUsage.ASSISTANT
                    else -> PlaybackUsage.UNKNOWN
                } }
                val state = last.get()
                val resolved = MediaObservation.resolve(active, if (active != null) now else null, now,
                    state.active, previous, playbackUsages = usages)
                if (state.phase == VoicePhase.IDLE && resolved == false) baselineClear.countDown()
                if (state.phase == VoicePhase.PLAYING) {
                    playingSamples++
                    if (usages?.contains(PlaybackUsage.ASSISTANT) == true) assistantSamples++
                    if (resolved != false) blockedPlayingSamples++
                }
                samples.put(JSONObject().apply {
                    put("elapsedMs", now - origin); put("phase", state.phase.name)
                    put("backend", state.source.name); put("isMusicActive", active ?: JSONObject.NULL)
                    put("activeUsageValues", raw?.let { JSONArray(it) } ?: JSONObject.NULL)
                    put("classifiedUsages", usages?.let { JSONArray(it.map { value -> value.name }) } ?: JSONObject.NULL)
                    put("previousClear", previous == false); put("resolvedMedia", resolved ?: JSONObject.NULL)
                })
                previous = resolved
                main.postDelayed(this, 250)
            }
        }
        try {
            instrumentation.runOnMainSync { main.post(monitor) }
            assertTrue("A real clear playback baseline was not observed", baselineClear.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                output = AndroidSpeechOutput(context, onState = { state ->
                    last.set(state)
                    if (state.phase in setOf(VoicePhase.COMPLETED, VoicePhase.FAILED, VoicePhase.CANCELLED)) terminal.countDown()
                })
                assertTrue(output!!.speak("instrumented-media-classification", text, VoicePurpose.TEST))
            }
            assertTrue("Actual coordinator did not finish", terminal.await(60, TimeUnit.SECONDS))
            // Keep sampling for two seconds after completion to expose retained configurations.
            instrumentation.runOnMainSync { main.postDelayed({ lingeringObserved.countDown() }, 2_000) }
            assertTrue(lingeringObserved.await(3, TimeUnit.SECONDS))
            assertEquals(VoicePhase.COMPLETED, last.get().phase)
            assertTrue("No actual PLAYING samples", playingSamples > 0)
            assertTrue("No public ASSISTANT usage was observed while playing", assistantSamples > 0)
            assertEquals("Own output became blocked or unknown in the real playback observations", 0, blockedPlayingSamples)
        } finally {
            instrumentation.runOnMainSync {
                monitorRunning = false; main.removeCallbacks(monitor); output?.shutdown()
            }
            proof(context, "media-observation", JSONObject().apply {
                put("samples", samples); put("playingSamples", playingSamples)
                put("assistantUsageSamples", assistantSamples); put("blockedPlayingSamples", blockedPlayingSamples)
                put("playerIdentityVerified", false); put("classificationOnly", true)
            })
        }
    }

    private fun proof(context: Context, kind: String, result: JSONObject) {
        if (!result.has("text")) result.put("text", text)
        result.put("kind", kind).put("audibilityVerified", false)
            .put("externalLoopbackRecordingRequired", true).put("sensitiveInputRecorded", false)
        val output = File(context.cacheDir, "pleos-voice-$kind-proof.json")
        output.writeText(result.toString(2))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("pleosVoiceProof", result.toString()); putString("pleosVoiceProofPath", output.absolutePath)
        })
    }
}
