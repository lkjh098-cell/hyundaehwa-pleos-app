package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Typed voice coordinator: official Pleos ON_DEVICE first, installed Android Korean voice as fallback. */
class AndroidSpeechOutput(private val context: Context,
    private val onInterrupted: (String, String) -> Unit = { _, _ -> },
    private val onState: (VoicePlaybackState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var backend: VoiceBackend? = null
    private var closed = false
    private var generation = 0L
    private val transaction = UtterancePlaybackTransaction()
    private var playbackEpoch = 0L
    private var requestSource = VoiceSource.PLEOS_ON_DEVICE
    private val cachedCatalog = CachedVoiceCatalog(context.applicationContext)
    val playbackCheckpoint: String get() = "$generation:${transaction.index}:$playbackEpoch:${state.phase.name}:${backend?.checkpoint.orEmpty()}"
    private var hasAudioFocus = false
    var audioFocusBlocked = false
        private set
    val remainingText: String get() = backend?.remainingText?.let {
        listOf(it, transaction.remainingAfterCurrent).filter(String::isNotBlank).joinToString(" ")
    } ?: transaction.remainingText
    private var initTimeout: Runnable? = null
    private var playbackTimeout: Runnable? = null
    var state = VoicePlaybackState()
        private set
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (!closed && change == AudioManager.AUDIOFOCUS_GAIN) {
                audioFocusBlocked = false; hasAudioFocus = true
            } else if (change != AudioManager.AUDIOFOCUS_GAIN && hasAudioFocus && state.active) {
                audioFocusBlocked = true; hasAudioFocus = false
                if (state.purpose == VoicePurpose.PROMPT) state.requestId?.let { onInterrupted(it, remainingText) }
                val token = generation
                // Keep the transient focus request registered so the real GAIN callback can unlock resume.
                dispose(keepFocusRequest = true)
                update(VoicePlaybackEvent.Cancel(token, "다른 안내나 통화가 우선이라 재생을 멈췄어요."))
            }
        }, main).build()
    private fun update(event: VoicePlaybackEvent) { val next = VoicePlayback.reduce(state, event); if (next != state) { state = next; onState(next) } }
    private fun current(token: Long, candidate: VoiceBackend? = backend) = !closed && generation == token && state.active && candidate === backend
    fun speak(id: String, text: String, purpose: VoicePurpose = VoicePurpose.PROMPT, keepTogether: Boolean = true): Boolean {
        if (closed || text.isBlank()) return false
        stop("새 음성 요청으로 이전 재생을 멈췄어요.")
        val token = ++generation
        transaction.begin(token, text, keepTogether)
        requestSource = VoiceSource.PLEOS_ON_DEVICE
        update(VoicePlaybackEvent.Request(token, id, purpose))
        if (runCatching { audio.requestAudioFocus(focus) }.getOrNull() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(token, "다른 안내나 통화가 우선이에요. 끝난 뒤 다시 눌러 주세요."); return true
        }
        hasAudioFocus = true
        val first = transaction.currentText
        if (first == null) { fail(token, "읽을 문장이 없어요."); return true }
        prepare(token, first, PleosOnDeviceVoiceBackend(context, attributes), null)
        return true
    }
    private fun prepare(token: Long, text: String, candidate: VoiceBackend, fallbackReason: String?) {
        backend = candidate
        val segment = transaction.index
        var segmentStarted = false
        update(VoicePlaybackEvent.Backend(token, candidate.source, candidate.engineName, fallbackReason))
        val timeout = Runnable {
            if (!current(token, candidate)) return@Runnable
            initializationFailed(token, text, candidate, "${candidate.source.label} 초기화 응답이 없어요.")
        }
        initTimeout = timeout; main.postDelayed(timeout, 5_000)
        candidate.prepare({ main.post {
            if (!current(token, candidate)) return@post
            initTimeout?.let(main::removeCallbacks); initTimeout = null
            update(VoicePlaybackEvent.Backend(token, candidate.source, candidate.engineName, fallbackReason))
            val startTimeout = Runnable { if (current(token, candidate)) playbackFailed(token, text, candidate, "재생 시작 응답이 없어요. 음량과 음성 엔진을 확인해 주세요.") }
            playbackTimeout = startTimeout; main.postDelayed(startTimeout, 15_000)
            candidate.speak(text, object : PlaybackListener {
                override fun onStarted() { main.post {
                    if (!current(token, candidate)) return@post
                    if (!transaction.started(token, segment)) return@post
                    segmentStarted = true
                    playbackEpoch++
                    playbackTimeout?.let(main::removeCallbacks)
                    update(VoicePlaybackEvent.Started(token))
                    val doneTimeout = Runnable { if (current(token, candidate)) playbackFailed(token, text, candidate, "음성 완료 응답이 없어 재생을 멈췄어요.") }
                    playbackTimeout = doneTimeout; main.postDelayed(doneTimeout, PromptSentences.timeoutMs(text))
                } }
                override fun onCompleted() { main.post {
                    if (!current(token, candidate)) return@post
                    if (!segmentStarted) { playbackFailed(token, text, candidate, "재생 시작을 확인하지 못했어요."); return@post }
                    when (transaction.completed(token, segment)) {
                        SegmentCompletion.NEXT -> {
                            playbackTimeout?.let(main::removeCallbacks); playbackTimeout = null
                            candidate.release(); backend = null
                            val nextText = checkNotNull(transaction.currentText)
                            prepare(token, nextText, nextBackend(nextText), state.fallbackReason)
                        }
                        SegmentCompletion.FINISHED -> { dispose(); update(VoicePlaybackEvent.Done(token)) }
                        SegmentCompletion.MISSING_START -> fail(token, "재생 시작을 확인하지 못했어요. 다시 확인해 주세요.")
                        SegmentCompletion.IGNORED -> Unit
                    }
                } }
                override fun onStopped() { main.post { if (current(token, candidate)) playbackFailed(token, text, candidate, "음성 엔진이 재생을 중단했어요.") } }
                override fun onFailed(reason: String) { main.post { if (current(token, candidate)) playbackFailed(token, text, candidate, reason) } }
            })
        } }, { reason -> main.post { if (current(token, candidate)) initializationFailed(token, text, candidate, reason) } })
    }
    private fun nextBackend(text: String): VoiceBackend = when (requestSource) {
        VoiceSource.PLEOS_ON_DEVICE -> PleosOnDeviceVoiceBackend(context, attributes)
        VoiceSource.ANDROID_FALLBACK -> AndroidVoiceBackend(context, attributes)
        VoiceSource.RECORDED_CACHE -> CachedRecordedVoiceBackend(context, attributes, cachedCatalog, text)
        else -> error("No speech backend selected")
    }
    private fun fallback(token: Long, text: String, candidate: VoiceBackend, reason: String) {
        val next = when (candidate.source) {
            VoiceSource.PLEOS_ON_DEVICE -> VoiceSource.ANDROID_FALLBACK
            VoiceSource.ANDROID_FALLBACK -> VoiceSource.RECORDED_CACHE
            else -> null
        }
        if (next == null || !transaction.retrySegment(token, transaction.index)) { fail(token, reason); return }
        candidate.release(); backend = null; requestSource = next
        val previous = state.fallbackReason
        val nextText = if (next == VoiceSource.RECORDED_CACHE) transaction.packRemaining(token, transaction.index) ?: text else text
        prepare(token, nextText, nextBackend(nextText), listOfNotNull(previous, reason).distinct().joinToString("\n"))
    }
    private fun playbackFailed(token: Long, text: String, candidate: VoiceBackend, reason: String) {
        playbackTimeout?.let(main::removeCallbacks); playbackTimeout = null
        // Once audio has started we cannot know the spoken word offset. A fallback here
        // would replay already spoken material; retain the checkpoint for an explicit retry.
        if (transaction.currentSegmentStarted) {
            if (state.purpose == VoicePurpose.PROMPT) state.requestId?.let { onInterrupted(it, remainingText) }
            fail(token, reason)
            return
        }
        fallback(token, text, candidate, reason)
    }
    private fun initializationFailed(token: Long, text: String, candidate: VoiceBackend, reason: String) {
        initTimeout?.let(main::removeCallbacks); initTimeout = null
        fallback(token, text, candidate, reason)
    }
    private fun fail(token: Long, reason: String) { transaction.cancel(); dispose(); update(VoicePlaybackEvent.Failed(token, reason)) }
    private fun dispose(keepFocusRequest: Boolean = false) {
        initTimeout?.let(main::removeCallbacks); initTimeout = null
        playbackTimeout?.let(main::removeCallbacks); playbackTimeout = null
        backend?.release(); backend = null
        hasAudioFocus = false
        if (!keepFocusRequest) {
            audioFocusBlocked = false
            audio.abandonAudioFocusRequest(focus)
        }
    }
    fun releaseInterruptedFocus() {
        if (audioFocusBlocked) { audioFocusBlocked = false; hasAudioFocus = false; audio.abandonAudioFocusRequest(focus) }
    }
    fun stop(reason: String = "재생을 멈췄어요.") {
        val wasActive = state.active
        val token = generation
        transaction.cancel()
        dispose()
        if (wasActive) update(VoicePlaybackEvent.Cancel(token, reason))
    }
    fun shutdown() { if (closed) return; stop("앱을 종료해 음성을 멈췄어요."); closed = true; ++generation }
}
