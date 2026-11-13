package ai.hyundaehwa.car.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.sqrt

data class CabinSoundState(
    val available: Boolean = false,
    val rms: Double? = null,
    val echoCancellation: Boolean = false,
    val error: String? = null,
    val source: String = "차 안 소리 크기",
)

/** Measures short PCM windows in memory. No file, transcript, network or background service. */
class CabinSoundMonitor(context: Context, private val changed: (CabinSoundState) -> Unit) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cabin-sound-level").apply { isDaemon = true } }
    private val audio = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var enabled = false
    @Volatile private var recognitionActive = false
    @Volatile private var ownSpeech = false
    @Volatile private var closed = false
    @Volatile private var epoch = 0L
    @Volatile private var recorder: AudioRecord? = null
    private var state = CabinSoundState()
    private var lastRequested = false
    private fun emit(value: CabinSoundState, token: Long = epoch) {
        main.post {
            if (closed || token != epoch) return@post
            state = value
            changed(value)
        }
    }
    fun setEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value; reconcile()
    }
    fun setRecognitionActive(value: Boolean) {
        if (closed || recognitionActive == value) return
        recognitionActive = value; reconcile()
    }
    fun setOwnSpeechActive(value: Boolean) {
        if (closed || ownSpeech == value) return
        ownSpeech = value
        // The worker keeps AEC running, but never treats uncancelled local playback as a person.
        if (value && !state.echoCancellation) emit(CabinSoundState(error = "안내 음성이 끝나면 소리 감지를 이어가요."))
    }
    private fun unavailable(): String? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "마이크 사용을 허용해 주세요."
        if (audio.isMicrophoneMute) return "기기의 마이크가 꺼져 있어요."
        return null
    }
    private fun reconcile() {
        val run = enabled && !recognitionActive && !closed
        if (lastRequested == run) return
        lastRequested = run
        val token = ++epoch
        // Stop an in-flight blocking read before handing the microphone to speech recognition.
        runCatching { recorder?.stop() }
        if (!run) {
            emit(CabinSoundState(error = if (recognitionActive) "답변을 듣고 있어요." else "소리 감지 꺼짐"), token)
            return
        }
        worker.execute { capture(token) }
    }
    @SuppressLint("MissingPermission")
    private fun capture(token: Long) {
        var input: AudioRecord? = null
        var echo: AcousticEchoCanceler? = null
        var noise: NoiseSuppressor? = null
        try {
            unavailable()?.let { emit(CabinSoundState(error = it), token); return }
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) { emit(CabinSoundState(error = "기기의 소리 입력을 확인하지 못했어요."), token); return }
            input = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 2, 8192)).build()
            if (input.state != AudioRecord.STATE_INITIALIZED) { emit(CabinSoundState(error = "소리 크기 측정을 시작하지 못했어요."), token); return }
            recorder = input
            if (AcousticEchoCanceler.isAvailable()) echo = runCatching { AcousticEchoCanceler.create(input.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            if (NoiseSuppressor.isAvailable()) noise = runCatching { NoiseSuppressor.create(input.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            val cancelledEcho = echo?.enabled == true
            input.startRecording()
            if (input.recordingState != AudioRecord.RECORDSTATE_RECORDING) { emit(CabinSoundState(error = "마이크 입력이 시작되지 않았어요."), token); return }
            val samples = ShortArray(2048)
            var squares = 0.0; var count = 0
            while (!closed && enabled && !recognitionActive && token == epoch) {
                val n = input.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (token == epoch) emit(CabinSoundState(error = "소리 입력이 중단됐어요. 설정에서 다시 켜 주세요."), token)
                    break
                }
                for (i in 0 until n) { val value = samples[i] / 32768.0; squares += value * value }
                count += n
                samples.fill(0) // Discard each window immediately after the level calculation.
                if (count >= 6144) {
                    val level = (sqrt(squares / count) * 4.0).coerceIn(0.0, 1.0)
                    squares = 0.0; count = 0
                    val silenced = Build.VERSION.SDK_INT >= 29 && runCatching { input.activeRecordingConfiguration?.isClientSilenced }.getOrNull() == true
                    val problem = unavailable() ?: if (silenced) "기기의 마이크 접근이 차단됐어요." else null
                    if (problem != null) { emit(CabinSoundState(error = problem), token); break }
                    if (ownSpeech && !cancelledEcho) emit(CabinSoundState(error = "안내 음성이 끝나면 소리 감지를 이어가요."), token)
                    else emit(CabinSoundState(true, level, cancelledEcho, source = if (cancelledEcho) "차 안 소리 크기 · 에코 억제" else "차 안 소리 크기"), token)
                }
            }
        } catch (_: Exception) {
            if (token == epoch) emit(CabinSoundState(error = "소리 감지를 사용할 수 없어요. 마이크 설정을 확인해 주세요."), token)
        } finally {
            runCatching { input?.stop() }; runCatching { echo?.release() }; runCatching { noise?.release() }; runCatching { input?.release() }
            if (recorder === input) recorder = null
        }
    }
    fun close() {
        if (closed) return
        closed = true; enabled = false; ++epoch
        runCatching { recorder?.stop() }
        worker.shutdownNow()
    }
}
