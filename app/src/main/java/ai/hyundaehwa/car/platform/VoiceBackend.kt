package ai.hyundaehwa.car.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import ai.hyundaehwa.core.VoiceSource
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/** Each instance handles at most one utterance, so callbacks without SDK request IDs cannot cross requests. */
interface VoiceBackend {
    val source: VoiceSource
    val engineName: String
    val remainingText: String? get() = null
    val checkpoint: String get() = ""
    fun prepare(onReady: () -> Unit, onError: (String) -> Unit)
    fun speak(text: String, listener: PlaybackListener)
    fun release()
}
interface PlaybackListener {
    fun onStarted()
    fun onCompleted()
    fun onStopped()
    fun onFailed(reason: String)
}
class PleosOnDeviceVoiceBackend(private val context: Context, private val attributes: AudioAttributes) : VoiceBackend {
    override val source = VoiceSource.PLEOS_ON_DEVICE
    override val engineName = "Pleos TextToSpeech 3.3.1 · ON_DEVICE · ko-KR"
    private var engine: PleosTts? = null
    private var playback: PlaybackListener? = null
    private var completedNaturally = false
    private var released = false
    private val listener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() { if (!released) playback?.onStarted() }
        override fun onDone() { if (!released) { completedNaturally = true; playback?.onCompleted() } }
        override fun onStop() { if (!released) playback?.onStopped() }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: ErrorType) { if (!released) playback?.onFailed("Pleos 음성 오류: ${errorType.name}") }
    }
    override fun prepare(onReady: () -> Unit, onError: (String) -> Unit) {
        runCatching {
            engine = PleosTts(context.applicationContext, Mode.ON_DEVICE).also { tts ->
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        if (released) return
                        runCatching { tts.setAudioAttributes(attributes); tts.setLocale(Locale.KOREA); tts.addEventListener(listener) }
                            .onSuccess { onReady() }.onFailure { onError("Pleos 한국어 음성 설정 실패") }
                    }
                    override fun onFailure(errorType: ErrorType) { if (!released) onError("Pleos 초기화: ${errorType.name}") }
                })
            }
        }.onFailure { onError("Pleos 기기 내 음성 서비스를 사용할 수 없어요.") }
    }
    override fun speak(text: String, listener: PlaybackListener) {
        playback = listener
        runCatching { checkNotNull(engine).speak(text, true) }.onFailure { listener.onFailed("Pleos 음성 요청 실패") }
    }
    override fun release() {
        if (released) return
        released = true; playback = null
        engine?.let { tts -> runCatching { tts.removeEventListener(listener) }; if (!completedNaturally) runCatching { tts.stop() }; runCatching { tts.release() } }
        engine = null
    }
}
class AndroidVoiceBackend(private val context: Context, private val attributes: AudioAttributes) : VoiceBackend {
    override val source = VoiceSource.ANDROID_FALLBACK
    override val engineName: String get() = "Android TTS · ${engine?.defaultEngine ?: "엔진 미확인"} · ko-KR"
    private val main = Handler(Looper.getMainLooper())
    private var engine: android.speech.tts.TextToSpeech? = null
    private var playback: PlaybackListener? = null
    private var released = false
    private val utteranceId = "hyundai-talk-${System.nanoTime()}"
    override fun prepare(onReady: () -> Unit, onError: (String) -> Unit) {
        runCatching {
            engine = android.speech.tts.TextToSpeech(context.applicationContext) { result -> main.post {
                if (released) return@post
                runCatching {
                val tts = engine ?: run { onError("한국어 음성 엔진을 열지 못했어요."); return@runCatching }
                val language = if (result == android.speech.tts.TextToSpeech.SUCCESS) tts.setLanguage(Locale.KOREA) else null
                if (language == null || language < 0) { onError("한국어 음성 데이터 또는 엔진이 없어요. 기기의 음성 설정을 확인해 주세요."); return@runCatching }
                // Network-free is not enough: downloadable voice entries can exist before data is installed.
                val localVoices = tts.voices.orEmpty().filter {
                    it.locale.language == "ko" && !it.isNetworkConnectionRequired &&
                        android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                }.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "KR" }
                    .thenByDescending { it.quality }.thenBy { it.latency }.thenBy { it.name })
                val chosen = localVoices.firstOrNull { tts.setVoice(it) == android.speech.tts.TextToSpeech.SUCCESS }
                if (chosen == null || tts.voice?.locale?.language != "ko" || tts.voice?.isNetworkConnectionRequired != false) {
                    onError("기기에 설치된 한국어 오프라인 음성이 없어요. 음성 설정에서 한국어 데이터를 설치해 주세요."); return@runCatching
                }
                if (tts.setAudioAttributes(attributes) != android.speech.tts.TextToSpeech.SUCCESS) {
                    onError("기기의 음성 출력 경로를 설정하지 못했어요."); return@runCatching
                }
                onReady()
                }.onFailure { onError("한국어 음성 데이터를 확인하지 못했어요. 기기의 음성 설정을 확인해 주세요.") }
            } }
            engine?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) { if (!released && id == utteranceId) playback?.onStarted() }
                override fun onDone(id: String?) { if (!released && id == utteranceId) playback?.onCompleted() }
                @Deprecated("Deprecated by Android")
                override fun onError(id: String?) { if (!released && id == utteranceId) playback?.onFailed("Android 음성 재생 실패") }
                override fun onError(id: String?, code: Int) { if (!released && id == utteranceId) playback?.onFailed("Android 음성 재생 실패 ($code)") }
                override fun onStop(id: String?, interrupted: Boolean) { if (!released && id == utteranceId) playback?.onStopped() }
            })
        }.onFailure { onError("Android 한국어 음성 엔진을 열지 못했어요.") }
    }
    override fun speak(text: String, listener: PlaybackListener) {
        playback = listener
        val result = runCatching {
            val tts = checkNotNull(engine)
            if (tts.playSilentUtterance(180L, android.speech.tts.TextToSpeech.QUEUE_FLUSH, "$utteranceId-preroll") != android.speech.tts.TextToSpeech.SUCCESS)
                return@runCatching android.speech.tts.TextToSpeech.ERROR
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, android.os.Bundle().apply {
                putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
            }, utteranceId)
        }.getOrNull()
        if (result != android.speech.tts.TextToSpeech.SUCCESS) listener.onFailed("Android 음성 요청 실패")
    }
    override fun release() { if (released) return; released = true; playback = null; engine?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }; engine = null }
}
