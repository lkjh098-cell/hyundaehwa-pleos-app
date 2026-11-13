package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class TalkSessionTest {
    @Test fun listeningRequiresActualCallback() {
        val s = TalkSession.reduce(TalkState(), TalkEvent.Begin(1))
        assertEquals(TalkPhase.PREPARING, s.phase)
        assertEquals(TalkPhase.LISTENING, TalkSession.reduce(s, TalkEvent.Listening(1, "Pleos")).phase)
    }
    @Test fun cancellationRejectsLateRecognitionAndAnswer() {
        var s = TalkSession.reduce(TalkState(), TalkEvent.Begin(1))
        s = TalkSession.reduce(s, TalkEvent.Cancel(1, "집중"))
        assertEquals(s, TalkSession.reduce(s, TalkEvent.Recognized(1, "안녕하세요")))
        assertEquals(s, TalkSession.reduce(s, TalkEvent.Answer(1, "늦은 답변", "model")))
    }
    @Test fun supersededTurnRejectsOldCallbacks() {
        var s = TalkSession.reduce(TalkState(), TalkEvent.Begin(1, "첫 질문"))
        s = TalkSession.reduce(s, TalkEvent.Begin(2, "다음 질문"))
        assertEquals(s, TalkSession.reduce(s, TalkEvent.Answer(1, "첫 답변", "model")))
    }
    @Test fun finalRecognitionIsSentOnce() {
        val s = TalkSession.reduce(TalkSession.reduce(TalkState(), TalkEvent.Begin(1)), TalkEvent.Recognized(1, "안녕하세요"))
        assertEquals(TalkPhase.THINKING, s.phase)
        assertEquals(s, TalkSession.reduce(s, TalkEvent.Recognized(1, "중복")))
    }
    @Test fun failedRequestsDoNotPolluteHistory() {
        var s = TalkSession.reduce(TalkState(), TalkEvent.Begin(1, "질문"))
        s = TalkSession.reduce(s, TalkEvent.Fail(1, "연결 실패"))
        assertTrue(s.history.isEmpty())
    }
    @Test fun successfulTurnsPreserveContextAndBoundHistory() {
        var s = TalkState()
        repeat(9) { i ->
            s = TalkSession.reduce(s, TalkEvent.Begin(i + 1L, "질문 $i"))
            s = TalkSession.reduce(s, TalkEvent.Answer(i + 1L, "답변 $i", "Gemini"))
            assertEquals(TalkPhase.THINKING, s.phase)
            s = TalkSession.reduce(s, TalkEvent.Speaking(i + 1L))
            s = TalkSession.reduce(s, TalkEvent.Spoken(i + 1L))
        }
        assertEquals(10, s.history.size)
        assertEquals(TalkTurn("user", "질문 4"), s.history.first())
        assertEquals(TalkPhase.COMPLETED, s.phase)
    }
    @Test fun speechCompletionWithoutStartDoesNotReportSuccess() {
        var s = TalkSession.reduce(TalkState(), TalkEvent.Begin(1, "질문"))
        s = TalkSession.reduce(s, TalkEvent.Answer(1, "답변", "Gemini"))
        assertEquals(s, TalkSession.reduce(s, TalkEvent.Spoken(1)))
    }
    @Test fun duplicateServerCompletionCannotAddHistoryTwiceWhileVoicePrepares() {
        var s = TalkSession.reduce(TalkState(), TalkEvent.Begin(1, "질문"))
        s = TalkSession.reduce(s, TalkEvent.Answer(1, "답변", "Gemini"))
        assertEquals(s, TalkSession.reduce(s, TalkEvent.Answer(1, "중복 답변", "Gemini")))
        assertEquals(2, s.history.size)
    }
    @Test fun clearForgetsHistoryAndRejectsOldRequests() {
        var s = TalkSession.reduce(TalkState(), TalkEvent.Begin(1, "질문"))
        s = TalkSession.reduce(s, TalkEvent.Answer(1, "답변", "Gemini"))
        s = TalkSession.reduce(s, TalkEvent.Clear(2))
        assertTrue(s.history.isEmpty()); assertEquals(TalkPhase.IDLE, s.phase)
        assertEquals(s, TalkSession.reduce(s, TalkEvent.Answer(1, "늦은 답", "Gemini")))
    }
    @Test fun endpointRejectsInsecureOrCredentialBearingUrls() {
        assertEquals("https://example.com", TalkRelayEndpoint.validate("https://example.com/"))
        listOf("http://example.com", "https://key@example.com", "https://example.com/?key=x", "file:///tmp/key", "https://example.com/path").forEach { assertNull(TalkRelayEndpoint.validate(it, true)) }
        assertNull(TalkRelayEndpoint.validate("http://10.0.2.2:4181"))
        assertEquals("http://10.0.2.2:4181", TalkRelayEndpoint.validate("http://10.0.2.2:4181", true))
    }
}
