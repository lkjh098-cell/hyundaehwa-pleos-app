package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** One button, one utterance, one answer. No ambient recording, wake word or automatic restart. */
class TalkConversation(
    private val context: Context,
    private val connection: () -> RelayConnection,
    private val blockReason: () -> String?,
    private val journey: () -> Map<String, String>,
    private val speak: (String, String) -> Boolean,
    private val stopSpeech: () -> Unit,
    private val changed: (TalkState) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val relay = TalkRelayClient()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: RecognitionBackend? = null
    private var timeout: Runnable? = null
    private var sequence = 0L
    private var closed = false
    var state = TalkState(); private set
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ value -> if (value != AudioManager.AUDIOFOCUS_GAIN) cancel("다른 안내나 통화가 우선이라 대화를 멈췄어요.") }, main).build()
    private fun update(event: TalkEvent) { val next = TalkSession.reduce(state, event); if (next != state) { state = next; changed(next) } }
    private fun current(token: Long) = !closed && state.token == token && state.active
    private fun later(token: Long, ms: Long, reason: String) {
        timeout?.let(main::removeCallbacks)
        timeout = Runnable { if (current(token)) fail(token, reason) }.also { main.postDelayed(it, ms) }
    }
    private fun eligible(): Boolean {
        blockReason()?.let { state = state.copy(error = it); changed(state); return false }
        if (!connection().valid) { state = state.copy(error = "먼저 대화 서버 연결을 설정해 주세요."); changed(state); return false }
        return !closed
    }
    fun listen() {
        if (!eligible()) return
        cancel("새 대화를 시작해요."); val token = ++sequence
        update(TalkEvent.Begin(token))
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { fail(token, "다른 안내나 통화가 끝난 뒤 다시 눌러 주세요."); return }
        prepare(token, PleosRecognitionBackend(context), fallback = false)
    }
    private fun prepare(token: Long, candidate: RecognitionBackend, fallback: Boolean) {
        recognizer = candidate
        timeout?.let(main::removeCallbacks)
        timeout = Runnable {
            if (current(token) && recognizer === candidate) preparationFailed(token, candidate, fallback, "음성 인식 서비스가 응답하지 않아요.")
        }.also { main.postDelayed(it, 5_000) }
        candidate.prepare({ main.post {
            if (!current(token) || recognizer !== candidate) return@post
            blockReason()?.let { cancel(it); return@post }
            later(token, 30_000, "말씀을 확인하는 시간이 지났어요. 다시 눌러 주세요.")
            candidate.start(object : RecognitionEvents {
                override fun started() { main.post { if (current(token) && recognizer === candidate) update(TalkEvent.Listening(token, candidate.source)) } }
                override fun text(text: String, final: Boolean) { main.post {
                    if (!current(token) || recognizer !== candidate) return@post
                    blockReason()?.let { cancel(it); return@post }
                    if (!final) update(TalkEvent.Partial(token, text))
                    else {
                        update(TalkEvent.Recognized(token, text)); releaseRecognition()
                        if (state.phase == TalkPhase.THINKING) send(token)
                        else audio.abandonAudioFocusRequest(focus)
                    }
                } }
                override fun ended() { main.post { if (current(token) && recognizer === candidate && state.phase in setOf(TalkPhase.PREPARING, TalkPhase.LISTENING)) later(token, 1_500, "말씀을 확인하지 못했어요. 다시 말하거나 직접 입력해 주세요.") } }
                override fun failed(reason: String) { main.post { if (current(token) && recognizer === candidate) fail(token, reason) } }
            })
        } }, { reason -> main.post { if (current(token) && recognizer === candidate) preparationFailed(token, candidate, fallback, reason) } })
    }
    private fun preparationFailed(token: Long, candidate: RecognitionBackend, fallback: Boolean, reason: String) {
        candidate.release(); recognizer = null
        if (!fallback) prepare(token, AndroidOfflineRecognitionBackend(context), fallback = true) else fail(token, reason)
    }
    fun submit(text: String) {
        if (text.isBlank() || !eligible()) return
        cancel("직접 입력으로 전환했어요."); val token = ++sequence
        update(TalkEvent.Begin(token, text)); send(token)
    }
    private fun send(token: Long) {
        if (!current(token)) return
        blockReason()?.let { cancel(it); return }
        // Keep transient focus while awaiting the answer; a navigation/call focus loss cancels the turn.
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { fail(token, "다른 안내나 통화가 끝난 뒤 다시 눌러 주세요."); return }
        later(token, 27_000, "답변을 기다리는 시간이 지났어요. 다시 눌러 주세요.")
        relay.request(connection(), state.transcript, state.history, journey(), { reply, model -> main.post {
            if (!current(token)) return@post
            blockReason()?.let { cancel(it); return@post }
            timeout?.let(main::removeCallbacks); timeout = null
            update(TalkEvent.Answer(token, reply, model))
            audio.abandonAudioFocusRequest(focus)
            if (!speak("talk-$token", reply)) fail(token, "답변은 받았지만 음성을 재생하지 못했어요.")
        } }, { reason -> main.post { if (current(token)) fail(token, reason) } })
    }
    fun voiceChanged(voice: VoicePlaybackState) {
        if (voice.purpose != VoicePurpose.TALK || voice.requestId != "talk-${state.token}" || !state.active) return
        when (voice.phase) {
            VoicePhase.PLAYING -> update(TalkEvent.Speaking(state.token))
            VoicePhase.COMPLETED -> update(TalkEvent.Spoken(state.token))
            VoicePhase.FAILED -> fail(state.token, voice.error ?: "답변 음성을 재생하지 못했어요.")
            VoicePhase.CANCELLED -> cancel(voice.error ?: "음성 답변을 멈췄어요.")
            else -> Unit
        }
    }
    private fun releaseRecognition() { timeout?.let(main::removeCallbacks); timeout = null; recognizer?.release(); recognizer = null }
    private fun fail(token: Long, reason: String) { releaseRecognition(); relay.cancel(); audio.abandonAudioFocusRequest(focus); update(TalkEvent.Fail(token, reason)) }
    fun enforce() { if (state.active) blockReason()?.let(::cancel) }
    fun cancel(reason: String = "대화를 멈췄어요.") {
        val token = state.token
        releaseRecognition(); relay.cancel(); audio.abandonAudioFocusRequest(focus)
        // Mark cancelled before stopping TTS: its callbacks must not re-enter an active turn.
        if (state.active) update(TalkEvent.Cancel(token, reason))
        stopSpeech()
    }
    fun clear() { cancel(); update(TalkEvent.Clear(++sequence)) }
    fun close() { clear(); closed = true; relay.close() }
}
