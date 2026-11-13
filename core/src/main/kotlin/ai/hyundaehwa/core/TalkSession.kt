package ai.hyundaehwa.core

import java.net.URI

enum class TalkPhase(val label: String) {
    IDLE("말을 걸어 주세요"), PREPARING("마이크 준비 중"), LISTENING("듣고 있어요"),
    THINKING("답변을 생각하고 있어요"), SPEAKING("답변을 읽고 있어요"),
    COMPLETED("이어서 이야기해 볼까요?"), FAILED("다시 확인해 주세요"), CANCELLED("대화를 멈췄어요")
}
data class TalkTurn(val role: String, val text: String)
data class TalkState(
    val token: Long = 0, val phase: TalkPhase = TalkPhase.IDLE, val transcript: String = "",
    val reply: String = "", val history: List<TalkTurn> = emptyList(), val error: String? = null,
    val source: String = "", val model: String = ""
) { val active get() = phase in setOf(TalkPhase.PREPARING, TalkPhase.LISTENING, TalkPhase.THINKING, TalkPhase.SPEAKING) }
sealed interface TalkEvent {
    data class Begin(val token: Long, val typed: String? = null) : TalkEvent
    data class Listening(val token: Long, val source: String) : TalkEvent
    data class Partial(val token: Long, val text: String) : TalkEvent
    data class Recognized(val token: Long, val text: String) : TalkEvent
    data class Answer(val token: Long, val reply: String, val model: String) : TalkEvent
    data class Speaking(val token: Long) : TalkEvent
    data class Spoken(val token: Long) : TalkEvent
    data class Fail(val token: Long, val reason: String) : TalkEvent
    data class Cancel(val token: Long, val reason: String) : TalkEvent
    data class Clear(val token: Long) : TalkEvent
}
/** Late recognition/network/voice callbacks cannot revive cancelled or superseded turns. */
object TalkSession {
    fun reduce(s: TalkState, event: TalkEvent): TalkState {
        if (event is TalkEvent.Clear) return if (event.token > s.token) TalkState(token = event.token) else s
        if (event is TalkEvent.Begin) return if (event.token > s.token) s.copy(token = event.token, phase = if (event.typed == null) TalkPhase.PREPARING else TalkPhase.THINKING, transcript = event.typed?.trim()?.take(1000).orEmpty(), reply = "", error = null, source = if (event.typed == null) "" else "직접 입력", model = "") else s
        val token = when (event) {
            is TalkEvent.Listening -> event.token; is TalkEvent.Partial -> event.token
            is TalkEvent.Recognized -> event.token; is TalkEvent.Answer -> event.token
            is TalkEvent.Speaking -> event.token; is TalkEvent.Spoken -> event.token
            is TalkEvent.Fail -> event.token; is TalkEvent.Cancel -> event.token
            else -> return s
        }
        if (token != s.token || !s.active) return s
        return when (event) {
            is TalkEvent.Listening -> if (s.phase == TalkPhase.PREPARING) s.copy(phase = TalkPhase.LISTENING, source = event.source) else s
            is TalkEvent.Partial -> if (s.phase in setOf(TalkPhase.PREPARING, TalkPhase.LISTENING)) s.copy(transcript = event.text.take(1000)) else s
            is TalkEvent.Recognized -> if (s.phase !in setOf(TalkPhase.PREPARING, TalkPhase.LISTENING)) s else if (event.text.isBlank()) s.copy(phase = TalkPhase.FAILED, error = "말씀을 확인하지 못했어요. 다시 말해 주세요.") else s.copy(phase = TalkPhase.THINKING, transcript = event.text.trim().take(1000))
            is TalkEvent.Answer -> if (s.phase != TalkPhase.THINKING || s.reply.isNotBlank()) s else if (event.reply.isBlank()) s.copy(phase = TalkPhase.FAILED, error = "답변이 비어 있어요. 다시 시도해 주세요.") else s.copy(reply = event.reply.take(800), model = event.model, history = (s.history + listOf(TalkTurn("user", s.transcript), TalkTurn("assistant", event.reply.take(800)))).takeLast(10))
            is TalkEvent.Speaking -> if (s.phase == TalkPhase.THINKING && s.reply.isNotBlank()) s.copy(phase = TalkPhase.SPEAKING) else s
            is TalkEvent.Spoken -> if (s.phase == TalkPhase.SPEAKING) s.copy(phase = TalkPhase.COMPLETED) else s
            is TalkEvent.Fail -> s.copy(phase = TalkPhase.FAILED, error = event.reason)
            is TalkEvent.Cancel -> s.copy(phase = TalkPhase.CANCELLED, error = event.reason)
            else -> s
        }
    }
}

object TalkRelayEndpoint {
    /** Release builds accept HTTPS only; debug allows exact emulator/loopback hosts. */
    fun validate(raw: String, debug: Boolean = false): String? = runCatching {
        val uri = URI(raw.trim())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.host != null && uri.rawPath.orEmpty() in setOf("", "/"))
        require(uri.scheme == "https" || (debug && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")))
        uri.toString().trimEnd('/')
    }.getOrNull()
}
