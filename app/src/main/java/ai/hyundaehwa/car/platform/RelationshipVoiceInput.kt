package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Explicit one-shot, parked-only chooser input. No relay, destination routing, stored audio or background restart. */
class RelationshipVoiceInput(private val context: Context, private val blockReason: () -> String?,
    private val changed: (RelationshipVoiceState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: RecognitionBackend? = null
    private var timeout: Runnable? = null
    private var sequence = 0L
    private var closed = false
    var state = RelationshipVoiceState(); private set
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ value -> if (value != AudioManager.AUDIOFOCUS_GAIN && state.active) fail(state.token, "다른 안내가 우선이에요. 아래 버튼에서 선택해 주세요.") }, main).build()
    private fun update(event: RelationshipVoiceEvent) {
        val next = RelationshipVoiceSession.reduce(state, event)
        if (next != state) { state = next; changed(next) }
    }
    private fun current(token: Long, candidate: RecognitionBackend? = recognizer) = !closed && state.token == token && state.active && candidate === recognizer
    fun listen() {
        if (closed) return
        cancel(); val token = ++sequence
        update(RelationshipVoiceEvent.Begin(token))
        blockReason()?.let { fail(token, it); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(token, "다른 안내가 끝난 뒤 다시 누르거나 아래 버튼에서 선택해 주세요."); return
        }
        prepare(token, PleosRecognitionBackend(context), false)
    }
    private fun prepare(token: Long, candidate: RecognitionBackend, fallback: Boolean) {
        recognizer = candidate
        timeout?.let(main::removeCallbacks)
        timeout = Runnable { if (current(token, candidate)) preparationFailed(token, candidate, fallback, "음성 인식 응답이 없어요.") }.also { main.postDelayed(it, 5_000) }
        candidate.prepare({ main.post {
            if (!current(token, candidate)) return@post
            blockReason()?.let { fail(token, it); return@post }
            timeout?.let(main::removeCallbacks)
            timeout = Runnable { if (current(token, candidate)) fail(token, "말씀을 확인하지 못했어요. 다시 누르거나 아래 버튼에서 골라 주세요.") }.also { main.postDelayed(it, 20_000) }
            candidate.start(object : RecognitionEvents {
                override fun started() { main.post { if (current(token, candidate)) update(RelationshipVoiceEvent.Ready(token, candidate.source)) } }
                override fun text(text: String, final: Boolean) { main.post {
                    if (!current(token, candidate)) return@post
                    blockReason()?.let { fail(token, it); return@post }
                    if (!final) update(RelationshipVoiceEvent.Partial(token, text))
                    else { release(); update(RelationshipVoiceEvent.Final(token, text)) }
                } }
                override fun ended() { main.post {
                    if (!current(token, candidate)) return@post
                    timeout?.let(main::removeCallbacks)
                    timeout = Runnable { if (current(token, candidate)) fail(token, "말씀을 확인하지 못했어요. 아래 버튼에서도 선택할 수 있어요.") }.also { main.postDelayed(it, 1_500) }
                } }
                override fun failed(reason: String) { main.post {
                    if (!current(token, candidate)) return@post
                    if (state.phase == RelationshipVoicePhase.PREPARING) preparationFailed(token, candidate, fallback, reason)
                    else fail(token, "$reason 아래 버튼에서 선택할 수 있어요.")
                } }
            })
        } }, { reason -> main.post { if (current(token, candidate)) preparationFailed(token, candidate, fallback, reason) } })
    }
    private fun preparationFailed(token: Long, candidate: RecognitionBackend, fallback: Boolean, reason: String) {
        timeout?.let(main::removeCallbacks); timeout = null
        candidate.release(); recognizer = null
        if (!fallback) prepare(token, AndroidOfflineRecognitionBackend(context), true)
        else fail(token, "$reason 아래 버튼에서 선택해 주세요.")
    }
    private fun release() {
        timeout?.let(main::removeCallbacks); timeout = null
        recognizer?.release(); recognizer = null
        audio.abandonAudioFocusRequest(focus)
    }
    private fun fail(token: Long, reason: String) { release(); update(RelationshipVoiceEvent.Fail(token, reason)) }
    fun cancel() { release(); if (state.active) update(RelationshipVoiceEvent.Cancel(state.token)) }
    fun close() { if (closed) return; cancel(); closed = true; ++sequence }
}
