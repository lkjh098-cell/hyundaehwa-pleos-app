package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.RecognizedUtterance
import ai.hyundaehwa.core.RecognitionSpeechRanges
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale

interface RecognitionEvents {
    fun speechStarted() = Unit
    fun started()
    fun text(text: String, final: Boolean)
    fun ended()
    fun failed(reason: String)
    fun noSpeech() = failed("말씀을 확인하지 못했어요. 다시 말해 주세요.")
}
interface RecognitionBackend {
    val source: String
    fun prepare(ready: () -> Unit, failed: (String) -> Unit)
    fun start(events: RecognitionEvents)
    fun release()
}
/** A fresh SDK instance per utterance prevents callbacks without request IDs crossing turns. */
class PleosRecognitionBackend(private val context: Context) : RecognitionBackend {
    override val source = "Pleos 기기 내 음성 인식 · ko-KR"
    private var engine: SpeechToText? = null
    private var events: RecognitionEvents? = null
    private var released = false
    private val utterance = RecognizedUtterance()
    private val speechRanges = RecognitionSpeechRanges()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var completionFallback: Runnable? = null
    private var ended = false
    private var started = false
    private fun markStarted() {
        if (!released && !ended && !started) { started = true; events?.started() }
    }
    private fun finishUtterance() {
        if (released || ended) return
        ended = true; completionFallback?.let(main::removeCallbacks); completionFallback = null
        // An empty early end is a terminated microphone, not a submitted answer.
        // Keep unfinished speech unmatched; never promote a partial to a final answer.
        if (utterance.preview.isBlank()) events?.noSpeech()
        else { events?.text(utterance.finalText, true); events?.ended() }
    }
    private val listener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() { main.post { markStarted() } }
        override fun onUpdated(stt: String, completed: Boolean) { main.post {
            if (!released && !ended) {
                // A transcript proves capture began even when a service omits its started callback.
                markStarted()
                if (stt.isNotBlank()) events?.speechStarted()
                completionFallback?.let(main::removeCallbacks); completionFallback = null
                events?.text(utterance.update(stt, completed), false)
                // Some service versions omit the end callback. Only stable completed text gets a
                // bounded silence fallback; a partial transcript can never become an answer.
                if (completed && stt.isNotBlank()) completionFallback = Runnable { finishUtterance() }.also { main.postDelayed(it, 3_200) }
            }
        } }
        override fun onUpdatedEpdData(on: Long, off: Long) { main.post {
            if (!released && !ended && speechRanges.observed(on, off)) {
                markStarted(); events?.speechStarted()
                completionFallback?.let(main::removeCallbacks); completionFallback = null
                if (utterance.finalText.isNotBlank()) completionFallback = Runnable { finishUtterance() }.also { main.postDelayed(it, 3_200) }
            }
        } }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { main.post { finishUtterance() } }
        override fun onError(errorType: ErrorType) { main.post { if (!released && !ended) {
            ended = true; completionFallback?.let(main::removeCallbacks); completionFallback = null
            events?.failed("기기 내 음성 인식 오류: ${errorType.name}")
        } } }
    }
    override fun prepare(ready: () -> Unit, failed: (String) -> Unit) {
        runCatching {
            engine = SpeechToText(context.applicationContext, Mode.ON_DEVICE).also { stt ->
                stt.initialize(object : OnInitializeListener {
                    override fun onSuccess() { if (!released) runCatching { stt.setLocale(Locale.KOREA); stt.addListener(listener) }.onSuccess { ready() }.onFailure { failed("Pleos 음성 인식 설정을 확인하지 못했어요.") } }
                    override fun onFailure(errorType: ErrorType) { if (!released) failed("Pleos 음성 인식 연결: ${errorType.name}") }
                })
            }
        }.onFailure { failed("Pleos 음성 인식 서비스에 연결하지 못했어요.") }
    }
    override fun start(events: RecognitionEvents) {
        this.events = events
        runCatching { checkNotNull(engine).request() }.onFailure { events.failed("음성 인식을 시작하지 못했어요.") }
    }
    override fun release() {
        if (released) return
        released = true; events = null; completionFallback?.let(main::removeCallbacks); completionFallback = null
        engine?.let { stt -> runCatching { stt.removeListener(listener) }; runCatching { stt.stop() }; runCatching { stt.release() } }; engine = null
    }
}

/** Android 12+ guarantees an on-device recognizer; earlier systems use typed input instead. */
class AndroidOfflineRecognitionBackend(private val context: Context) : RecognitionBackend {
    override val source = "Android 기기 내 음성 인식 · ko-KR"
    private var engine: SpeechRecognizer? = null
    private var events: RecognitionEvents? = null
    private var released = false
    private var ended = false
    override fun prepare(ready: () -> Unit, failed: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            failed("기기에 오프라인 음성 인식이 없어요. 한국어 인식 데이터를 설치하거나 직접 입력을 사용해 주세요."); return
        }
        runCatching {
            engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            engine?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (!released) events?.started() }
                override fun onBeginningOfSpeech() { if (!released) events?.speechStarted() }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) {
                    if (released || ended) return
                    ended = true
                    if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) { events?.noSpeech(); return }
                    events?.failed(when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한을 허용해 주세요."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말씀을 확인하지 못했어요. 다시 말해 주세요."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "한국어 오프라인 인식 데이터를 설치해 주세요."
                    else -> "기기 내 음성 인식이 중단됐어요. 다시 눌러 주세요."
                }) }
                override fun onResults(results: Bundle?) { if (!released && !ended) { ended = true; events?.text(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(), true); events?.ended() } }
                override fun onPartialResults(results: Bundle?) { if (!released && !ended) events?.text(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(), false) }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }); ready()
        }.onFailure { failed("기기 내 음성 인식을 열지 못했어요. 직접 입력을 사용해 주세요.") }
    }
    override fun start(events: RecognitionEvents) {
        this.events = events
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        runCatching { engine?.startListening(intent) }.onFailure { events.failed("마이크를 열지 못했어요. 권한과 기기 설정을 확인해 주세요.") }
    }
    override fun release() { if (released) return; released = true; events = null; engine?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }; engine = null }
}
