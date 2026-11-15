package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** A final-result recognizer for one visible card. No background dictation, relay or destination intent. */
class CardAnswerVoiceInput(private val context: Context, private val blockReason: (String) -> String?,
    private val changed: (CardAnswerVoiceState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: RecognitionBackend? = null
    private var timeout: Runnable? = null
    private var sequence = 0L
    private var responseDeadline: CardAnswerVoiceDeadline? = null
    private var closed = false
    private var noSpeechRestarts = 0
    var state = CardAnswerVoiceState(); private set
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ value -> if (value != AudioManager.AUDIOFOCUS_GAIN && state.active)
            cancel("길 안내나 다른 음성이 우선이에요. 끝난 뒤 다시 답변을 들을게요.", true) }, main).build()
    private fun update(event: CardAnswerVoiceEvent) {
        val next = CardAnswerVoiceSession.reduce(state, event)
        if (next != state) { state = next; changed(next) }
    }
    private fun current(token: Long, candidate: RecognitionBackend? = recognizer) = !closed && state.token == token && state.active && candidate === recognizer
    fun listen(card: AdministrativeConversationTopic) {
        if (closed) return
        cancel("새 답변을 들어요.")
        val token = ++sequence; responseDeadline = null; noSpeechRestarts = 0
        update(CardAnswerVoiceEvent.Begin(token, card.id))
        blockReason(card.id)?.let { fail(token, it); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(token, "다른 음성이 끝난 뒤 다시 눌러 주세요. 화면에서도 답할 수 있어요."); return
        }
        prepare(token, card, PleosRecognitionBackend(context), false)
    }
    private fun prepare(token: Long, card: AdministrativeConversationTopic, candidate: RecognitionBackend, fallback: Boolean) {
        if (expireIfNeeded(token)) { candidate.release(); return }
        recognizer = candidate
        timeout?.let(main::removeCallbacks)
        timeout = Runnable { if (current(token, candidate)) preparationFailed(token, card, candidate, fallback, "음성 인식 서비스 응답이 없어요.") }.also { main.postDelayed(it, RecognitionStartupPolicy.timeoutMs(false, responseDeadline?.remainingMs(SystemClock.elapsedRealtime()))) }
        candidate.prepare({ main.post {
            if (!current(token, candidate)) return@post
            blockReason(card.id)?.let { cancel(it, true); return@post }
            timeout?.let(main::removeCallbacks)
            timeout = Runnable { if (current(token, candidate)) preparationFailed(token, card, candidate, fallback, "마이크가 시작되지 않았어요. 한국어 기기 내 음성 인식을 확인해 주세요.") }.also { main.postDelayed(it, RecognitionStartupPolicy.timeoutMs(true, responseDeadline?.remainingMs(SystemClock.elapsedRealtime()))) }
            candidate.start(object : RecognitionEvents {
                override fun started() { main.post {
                    if (!current(token, candidate)) return@post
                    if (responseDeadline == null) responseDeadline = CardAnswerVoiceDeadline(SystemClock.elapsedRealtime())
                    update(CardAnswerVoiceEvent.Ready(token, candidate.source))
                    scheduleResponseTimeout(token, candidate)
                } }
                override fun speechStarted() { main.post {
                    if (!current(token, candidate)) return@post
                    responseDeadline?.speechStarted(SystemClock.elapsedRealtime())
                    scheduleResponseTimeout(token, candidate)
                } }
                override fun awaitingFinal() { main.post {
                    if (!current(token, candidate)) return@post
                    responseDeadline?.awaitingFinal(SystemClock.elapsedRealtime())
                    scheduleResponseTimeout(token, candidate)
                } }
                override fun text(text: String, final: Boolean) { main.post {
                    if (!current(token, candidate)) return@post
                    blockReason(card.id)?.let { cancel(it, true); return@post }
                    if (!final) {
                        responseDeadline?.partial(text, SystemClock.elapsedRealtime())
                        update(CardAnswerVoiceEvent.Partial(token, text))
                        scheduleResponseTimeout(token, candidate)
                    } else { release(); update(CardAnswerVoiceEvent.Final(token, text, card)) }
                } }
                override fun ended() { main.post {
                    if (!current(token, candidate)) return@post
                    if (state.phase == CardAnswerVoicePhase.PREPARING) { fail(token, "마이크가 시작되지 않았어요. 화면에서 답하거나 다시 눌러 주세요."); return@post }
                    scheduleResponseTimeout(token, candidate)
                } }
                override fun noSpeech() { main.post {
                    if (!current(token, candidate)) return@post
                    // Android terminates the recognizer after NO_MATCH/TIMEOUT. Do not leave a
                    // dead microphone labelled as listening for the rest of the answer window.
                    val deadline = responseDeadline
                    if (deadline == null) {
                        preparationFailed(token, card, candidate, fallback, "마이크가 시작되기 전에 음성 인식이 끝났어요.")
                        return@post
                    }
                    if (RecognitionRetryPolicy.canRestart(noSpeechRestarts, deadline.remainingMs(SystemClock.elapsedRealtime()))) {
                        noSpeechRestarts++
                        update(CardAnswerVoiceEvent.Reconnecting(token))
                        candidate.release(); recognizer = null
                        prepare(token, card, if (fallback) AndroidOfflineRecognitionBackend(context) else PleosRecognitionBackend(context), fallback)
                    } else if (deadline.remainingMs(SystemClock.elapsedRealtime()) == 0L) {
                        val event = deadline.expiryEvent(token, state.transcript); release(); update(event)
                    } else fail(token, "말씀을 인식하지 못했어요. 마이크를 눌러 다시 답해 주세요.")
                } }
                override fun failed(reason: String) { main.post {
                    if (!current(token, candidate)) return@post
                    if (state.phase == CardAnswerVoicePhase.PREPARING) preparationFailed(token, card, candidate, fallback, reason)
                    else fail(token, "$reason 화면에서도 답할 수 있어요.")
                } }
            })
        } }, { reason -> main.post { if (current(token, candidate)) preparationFailed(token, card, candidate, fallback, reason) } })
    }
    private fun scheduleResponseTimeout(token: Long, candidate: RecognitionBackend) {
        val deadline = responseDeadline ?: return
        timeout?.let(main::removeCallbacks)
        timeout = Runnable {
            if (!current(token, candidate)) return@Runnable
            if (deadline.remainingMs(SystemClock.elapsedRealtime()) > 0) scheduleResponseTimeout(token, candidate)
            else { val event = deadline.expiryEvent(token, state.transcript); release(); update(event) }
        }.also { main.postDelayed(it, deadline.remainingMs(SystemClock.elapsedRealtime())) }
    }
    private fun preparationFailed(token: Long, card: AdministrativeConversationTopic, candidate: RecognitionBackend, fallback: Boolean, reason: String) {
        timeout?.let(main::removeCallbacks); timeout = null
        candidate.release(); recognizer = null
        if (expireIfNeeded(token)) return
        if (!fallback) prepare(token, card, AndroidOfflineRecognitionBackend(context), true)
        else fail(token, "$reason 아래 답변 버튼을 이용해 주세요.")
    }
    private fun expireIfNeeded(token: Long): Boolean {
        val deadline = responseDeadline ?: return false
        if (deadline.remainingMs(SystemClock.elapsedRealtime()) > 0) return false
        val event = deadline.expiryEvent(token, state.transcript)
        release(); update(event)
        return true
    }
    private fun release() {
        timeout?.let(main::removeCallbacks); timeout = null
        recognizer?.release(); recognizer = null
        audio.abandonAudioFocusRequest(focus)
    }
    private fun fail(token: Long, reason: String) { release(); update(CardAnswerVoiceEvent.Fail(token, reason)) }
    fun enforce() { if (state.active) blockReason(state.cardId)?.let { cancel(it, true) } }
    fun cancel(reason: String = "음성 답변을 멈췄어요.", retryWhenSafe: Boolean = false) {
        release(); if (state.active) update(CardAnswerVoiceEvent.Cancel(state.token, reason, retryWhenSafe))
    }
    fun clear() { cancel(); state = CardAnswerVoiceState(token = ++sequence); changed(state) }
    fun close() { if (closed) return; clear(); closed = true; ++sequence }
}
