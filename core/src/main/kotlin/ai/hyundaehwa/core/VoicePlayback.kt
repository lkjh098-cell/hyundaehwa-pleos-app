package ai.hyundaehwa.core

enum class VoicePhase(val label: String) {
    IDLE("음성 확인 전"), PREPARING("음성 준비 중"), PLAYING("음성 재생 중"),
    COMPLETED("재생 완료"), FAILED("재생 실패"), CANCELLED("재생 중단")
}
enum class VoiceSource(val label: String) { UNSELECTED("엔진 확인 중"), PLEOS_ON_DEVICE("Pleos 기기 내 음성"), ANDROID_FALLBACK("Android 대체 음성"), RECORDED_CACHE("앱에 저장된 한국어 음성") }
/** Completed playback uses a shorter echo tail; uncertain synthesis or interruption requires the full guard. */
object VoiceEchoDelay {
    fun milliseconds(state: VoicePlaybackState): Long =
        if (state.source == VoiceSource.RECORDED_CACHE && state.phase == VoicePhase.COMPLETED) 300L else 650L
}
enum class VoicePurpose { PROMPT, TEST, TALK, WELCOME }
data class VoicePlaybackState(
    val token: Long = 0, val requestId: String? = null, val purpose: VoicePurpose = VoicePurpose.PROMPT,
    val phase: VoicePhase = VoicePhase.IDLE, val source: VoiceSource = VoiceSource.UNSELECTED,
    val engine: String = "", val error: String? = null, val fallbackReason: String? = null
) { val active: Boolean get() = phase == VoicePhase.PREPARING || phase == VoicePhase.PLAYING }
sealed interface VoicePlaybackEvent {
    data class Request(val token: Long, val requestId: String, val purpose: VoicePurpose) : VoicePlaybackEvent
    data class Backend(val token: Long, val source: VoiceSource, val engine: String, val fallbackReason: String? = null) : VoicePlaybackEvent
    data class Started(val token: Long) : VoicePlaybackEvent
    data class Done(val token: Long) : VoicePlaybackEvent
    data class Failed(val token: Long, val reason: String) : VoicePlaybackEvent
    data class Cancel(val token: Long, val reason: String) : VoicePlaybackEvent
}
/** Request acceptance is PREPARING. Only actual playback callbacks can reach PLAYING/COMPLETED. */
object VoicePlayback {
    fun reduce(s: VoicePlaybackState, event: VoicePlaybackEvent): VoicePlaybackState {
        if (event is VoicePlaybackEvent.Request) return if (event.token > s.token) VoicePlaybackState(token = event.token, requestId = event.requestId, purpose = event.purpose, phase = VoicePhase.PREPARING) else s
        val token = when (event) {
            is VoicePlaybackEvent.Backend -> event.token
            is VoicePlaybackEvent.Started -> event.token
            is VoicePlaybackEvent.Done -> event.token
            is VoicePlaybackEvent.Failed -> event.token
            is VoicePlaybackEvent.Cancel -> event.token
            else -> return s
        }
        if (token != s.token || !s.active) return s
        return when (event) {
            is VoicePlaybackEvent.Backend -> s.copy(phase = VoicePhase.PREPARING, source = event.source, engine = event.engine, fallbackReason = event.fallbackReason)
            is VoicePlaybackEvent.Started -> s.copy(phase = VoicePhase.PLAYING)
            is VoicePlaybackEvent.Done -> if (s.phase == VoicePhase.PLAYING) s.copy(phase = VoicePhase.COMPLETED) else s.copy(phase = VoicePhase.FAILED, error = "재생 시작을 확인하지 못했어요. 다시 확인해 주세요.")
            is VoicePlaybackEvent.Failed -> s.copy(phase = VoicePhase.FAILED, error = event.reason)
            is VoicePlaybackEvent.Cancel -> s.copy(phase = VoicePhase.CANCELLED, error = event.reason)
            else -> s
        }
    }
}

/** One eligibility rule is used before a test request and throughout its preparation/playback. */
data class VoiceTestConditions(
    val resumed: Boolean, val parked: Boolean, val quiet: Boolean,
    val guards: Set<FocusKind>, val mediaActive: Boolean, val navigationVoiceActive: Boolean
)
object VoiceTestPolicy {
    fun blockReason(c: VoiceTestConditions): String? = when {
        !c.resumed -> "앱 화면이 비활성화되어 음성 확인을 멈췄어요."
        !c.parked -> "P 정차 신호가 확인되면 음성을 시험할 수 있어요."
        c.quiet -> "조용히 가기를 해제한 뒤 음성을 확인해 주세요."
        c.guards.isNotEmpty() -> "운전 집중 구간에서는 음성 확인을 잠시 멈춰요."
        c.mediaActive -> "미디어 재생을 멈춘 뒤 음성을 확인해 주세요."
        c.navigationVoiceActive -> "내비게이션 안내가 끝난 뒤 다시 눌러 주세요."
        else -> null
    }
}
