package ai.hyundaehwa.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Direct PCM probe, with no recognizer or recognized-text injection. Not an ASR pass. */
@RunWith(AndroidJUnit4::class)
class MicrophonePcmCaptureIntegrationTest {
    @Test(timeout = 90_000L)
    fun captureInjectedPcm() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("external_audio") == "true")
        val context = inst.targetContext
        val target = args.getString("target_package") ?: "ai.hyundaehwa.car.releaseqa"
        assertTrue(target in setOf("ai.hyundaehwa.car.releaseqa", "ai.hyundaehwa.car.preview"))
        assertEquals(target, context.packageName)
        val source = (args.getString("audio_source") ?: "6").toInt()
        assertTrue("Only public MIC / VOICE_RECOGNITION / UNPROCESSED sources", source in setOf(1, 6, 9))
        val durationMs = (args.getString("capture_ms") ?: "8000").toLong()
        assertTrue(durationMs in 4000..20000)
        val readerMode = args.getString("reader_mode") ?: "blocking"
        assertTrue(readerMode in setOf("blocking", "legacy"))
        fun emit(event: String, vararg values: Pair<String, Any>) {
            val json = JSONObject().put("event", event).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            values.forEach { json.put(it.first, it.second) }
            inst.sendStatus(0, Bundle().apply { putString("pcm_capture_observation", json.toString()) })
        }
        inst.uiAutomation.grantRuntimePermission(target, Manifest.permission.RECORD_AUDIO)
        val prefs = context.getSharedPreferences("conversation_profile", Context.MODE_PRIVATE)
        val keys = setOf("spoken_guidance", "audio_setup_seen", "on_device_card_answers", "cabin_sound_monitor")
        val original = prefs.all.filterKeys { it in keys }
        var activity: MainActivity? = null
        var recorder: AudioRecord? = null
        try {
            assertTrue(prefs.edit().putBoolean("spoken_guidance", false).putBoolean("audio_setup_seen", true)
                .putBoolean("on_device_card_answers", false).putBoolean("cabin_sound_monitor", false).commit())
            activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            inst.waitForIdleSync()
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            assertTrue("Supported 16 kHz mono capture required", minimum > 0)
            val bufferSize = maxOf(minimum * 2, if (readerMode == "blocking") 64000 else 3200)
            recorder = AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(bufferSize).build()
            val capture = requireNotNull(recorder)
            assertEquals(AudioRecord.STATE_INITIALIZED, capture.state)
            capture.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, capture.recordingState)
            fun timestamp(): JSONObject {
                val value = AudioTimestamp()
                val status = capture.getTimestamp(value, AudioTimestamp.TIMEBASE_MONOTONIC)
                return JSONObject().put("status", status).put("observedAtNs", System.nanoTime()).apply {
                    if (status == AudioRecord.SUCCESS) put("framePosition", value.framePosition).put("nanoTime", value.nanoTime)
                }
            }
            val initialTimestamp = timestamp()
            val initialConfig = capture.activeRecordingConfiguration
            val started = SystemClock.elapsedRealtime()
            var reads = 0
            var silenced = initialConfig?.isClientSilenced == true
            var firstReadMs = -1L
            var finalTimestamp = JSONObject()
            var finalConfig = initialConfig
            var maxReadDurationNs = 0L
            var maxBetweenReadsNs = 0L
            val traceStart = LongArray(8192)
            val traceDuration = LongArray(8192)
            val traceBytes = IntArray(8192)
            var traceCount = 0
            var traceTruncated = false
            var readerStopped = true
            var actualReaderPriority: Int? = null
            val pcm: ByteArray
            // The blocking path has no Binder/config queries, JSON, or growing output buffer on its reader thread.
            if (readerMode == "blocking") {
                val output = ByteArray(((durationMs + 3000L) * 32L).toInt())
                val stopping = AtomicBoolean(false)
                val readerFailure = AtomicReference<Throwable?>(null)
                var used = 0
                val reader = Thread({
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                        actualReaderPriority = Process.getThreadPriority(Process.myTid())
                        var previousEnd = 0L
                        while (!stopping.get()) {
                            check(used < output.size) { "Preallocated PCM capacity exhausted" }
                            val begin = System.nanoTime()
                            if (previousEnd != 0L) maxBetweenReadsNs = maxOf(maxBetweenReadsNs, begin - previousEnd)
                            val count = capture.read(output, used, minOf(3200, output.size - used), AudioRecord.READ_BLOCKING)
                            val end = System.nanoTime()
                            maxReadDurationNs = maxOf(maxReadDurationNs, end - begin)
                            if (traceCount < traceStart.size) {
                                traceStart[traceCount] = begin
                                traceDuration[traceCount] = end - begin
                                traceBytes[traceCount++] = count
                            } else traceTruncated = true
                            previousEnd = end
                            if (count < 0) {
                                if (stopping.get()) break
                                error("AudioRecord read failed: $count")
                            }
                            if (count > 0) {
                                if (firstReadMs < 0) firstReadMs = SystemClock.elapsedRealtime() - started
                                used += count
                                reads++
                            } else if (!stopping.get()) Thread.sleep(1)
                        }
                    } catch (failure: Throwable) { readerFailure.set(failure) }
                }, "PcmProbeAudioReader").apply { isDaemon = true }
                reader.start()
                try {
                    emit("capture_ready", "audioSource" to source, "sampleRate" to capture.sampleRate,
                        "channels" to capture.channelCount, "bufferSize" to bufferSize,
                        "actualBufferFrames" to capture.bufferSizeInFrames, "durationMs" to durationMs,
                        "readerMode" to readerMode, "targetPackage" to target, "sessionId" to capture.audioSessionId)
                    while (SystemClock.elapsedRealtime() - started < durationMs && readerFailure.get() == null) Thread.sleep(20)
                    finalTimestamp = timestamp()
                    finalConfig = capture.activeRecordingConfiguration
                    silenced = silenced || finalConfig?.isClientSilenced == true
                } finally {
                    stopping.set(true)
                    capture.stop()
                    reader.join(3000)
                    readerStopped = !reader.isAlive
                    if (!readerStopped) reader.interrupt()
                }
                assertTrue("Blocking reader did not exit within 3 seconds of stop", readerStopped)
                readerFailure.get()?.let { throw AssertionError("PCM reader failed", it) }
                pcm = output.copyOf(used)
            } else {
                emit("capture_ready", "audioSource" to source, "sampleRate" to capture.sampleRate,
                    "channels" to capture.channelCount, "bufferSize" to bufferSize, "durationMs" to durationMs,
                    "actualBufferFrames" to capture.bufferSizeInFrames, "readerMode" to readerMode,
                    "targetPackage" to target, "sessionId" to capture.audioSessionId)
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(3200)
                while (SystemClock.elapsedRealtime() - started < durationMs) {
                    if (capture.activeRecordingConfiguration?.isClientSilenced == true) silenced = true
                    val read = capture.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    assertTrue("AudioRecord read failed: $read", read >= 0)
                    if (read > 0) {
                        if (firstReadMs < 0) firstReadMs = SystemClock.elapsedRealtime() - started
                        bytes.write(buffer, 0, read)
                        reads++
                    } else Thread.sleep(5)
                }
                finalTimestamp = timestamp()
                finalConfig = capture.activeRecordingConfiguration
                capture.stop()
                pcm = bytes.toByteArray()
            }
            val captureWallDurationMs = SystemClock.elapsedRealtime() - started
            emit("capture_reader_diagnostics", "readerMode" to readerMode,
                "timestampStart" to initialTimestamp, "timestampEnd" to finalTimestamp,
                "initialConfigAvailable" to (initialConfig != null), "finalConfigAvailable" to (finalConfig != null),
                "initialClientSilenced" to (initialConfig?.isClientSilenced ?: JSONObject.NULL),
                "finalClientSilenced" to (finalConfig?.isClientSilenced ?: JSONObject.NULL),
                "actualReaderPriority" to (actualReaderPriority ?: JSONObject.NULL),
                "readerStopped" to readerStopped, "maxReadDurationNs" to maxReadDurationNs,
                "maxBetweenReadsNs" to maxBetweenReadsNs, "readTraceCount" to traceCount,
                "readTraceTruncated" to traceTruncated,
                "policySampling" to if (readerMode == "blocking") "start/end snapshots; transient changes not ruled out" else "legacy loop polling",
                "timestampNote" to "Frame position is an available capture-pipeline observation, not a per-read loss counter")
            for (from in 0 until traceCount step 64) {
                val trace = JSONArray()
                for (i in from until minOf(from + 64, traceCount))
                    trace.put(JSONArray().put(traceStart[i]).put(traceDuration[i]).put(traceBytes[i]))
                emit("capture_read_trace", "fromRead" to from, "columns" to "startMonotonicNs,durationNs,bytes", "rows" to trace)
            }
            assertTrue("Capture was silenced by Android policy", !silenced)
            assertTrue("No active recording configuration observed", initialConfig != null || finalConfig != null)
            assertTrue("No PCM frames captured", pcm.isNotEmpty())
            assertEquals("Unaligned PCM", 0, pcm.size % 2)
            var offset = 0
            var sequence = 0
            while (offset < pcm.size) {
                val size = minOf(4096, pcm.size - offset)
                val chunk = JSONObject().put("sequence", sequence)
                    .put("base64", Base64.encodeToString(pcm, offset, size, Base64.NO_WRAP))
                inst.sendStatus(0, Bundle().apply { putString("pcm_capture_chunk", chunk.toString()) })
                offset += size
                sequence++
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it.toInt() and 255) }
            emit("capture_complete", "audioSource" to source, "bytes" to pcm.size, "chunks" to sequence,
                "sha256" to sha, "reads" to reads, "firstReadAfterStartMs" to firstReadMs,
                "wallDurationMs" to captureWallDurationMs, "clientSilenced" to silenced, "readerMode" to readerMode)
        } finally {
            recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop(); it.release() }
            inst.runOnMainSync { activity?.finish() }
            val edit = prefs.edit()
            keys.forEach { key ->
                if (original.containsKey(key)) edit.putBoolean(key, original[key] as Boolean) else edit.remove(key)
            }
            assertTrue(edit.commit())
        }
    }
}
