package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import ai.pleos.playground.stt.listener.ResultListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic SDK callbacks through the real backend listener; does not certify microphone capture. */
@RunWith(AndroidJUnit4::class)
class CardAnswerRecognitionCallbackIntegrationTest {
    private class Observed : RecognitionEvents {
        var starts = 0
        var noSpeech = 0
        var ends = 0
        val finals = mutableListOf<String>()
        override fun started() { starts++ }
        override fun text(text: String, final: Boolean) { if (final) finals += text }
        override fun ended() { ends++ }
        override fun noSpeech() { noSpeech++ }
        override fun failed(reason: String) { fail(reason) }
    }
    private fun withListener(test: (ResultListener, Observed, () -> Unit) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val observed = Observed()
        lateinit var backend: PleosRecognitionBackend
        lateinit var listener: ResultListener
        instrumentation.runOnMainSync {
            backend = PleosRecognitionBackend(instrumentation.targetContext)
            PleosRecognitionBackend::class.java.getDeclaredField("events").apply { isAccessible = true }.set(backend, observed)
            listener = PleosRecognitionBackend::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(backend) as ResultListener
        }
        try { test(listener, observed) { instrumentation.waitForIdleSync() } }
        finally { instrumentation.runOnMainSync { backend.release() } }
    }
    @Test fun emptyEarlyEndRequestsRecoveryOnceWithoutSubmittingAnAnswer() = withListener { listener, observed, drain ->
        listener.onReady(); drain()
        assertEquals("Service readiness is not capture readiness", 0, observed.starts)
        listener.onEndedRecognition(); listener.onEndedRecognition(); drain()
        assertEquals(1, observed.noSpeech)
        assertEquals(0, observed.starts)
        assertTrue(observed.finals.isEmpty())
        assertEquals(0, observed.ends)
    }
    @Test fun emptyEndAfterCaptureUsesRecoveryInsteadOfUnmatchedSubmission() = withListener { listener, observed, drain ->
        listener.onStartedRecognition(); listener.onEndedRecognition(); drain()
        assertEquals(1, observed.starts)
        assertEquals(1, observed.noSpeech)
        assertTrue(observed.finals.isEmpty())
    }
    @Test fun partialSpeechIsNeverPromotedToAnAnswerByTheEndCallback() = withListener { listener, observed, drain ->
        listener.onUpdated("1번", false); listener.onEndedRecognition(); drain()
        assertEquals(1, observed.starts)
        assertEquals(0, observed.noSpeech)
        assertEquals(listOf(""), observed.finals)
        assertEquals(1, observed.ends)
    }
    @Test fun firstCompletedShortAnswerSurvivesMissingStartCallbackAndEndsOnlyOnce() {
        val card = AdministrativeConversationTopic("first", "quiz", "제목", "질문", listOf(
            AdministrativeConversationOption("a", "황포묵"), AdministrativeConversationOption("b", "도토리묵")))
        for (text in listOf("1", "1번", "황포묵", "1번 황포묵이요")) withListener { listener, observed, drain ->
            listener.onUpdated(text, true); drain()
            assertEquals(1, observed.starts)
            assertTrue("Completed segment alone must wait for utterance end", observed.finals.isEmpty())
            listener.onEndedRecognition(); listener.onEndedRecognition(); drain()
            assertEquals(listOf(text), observed.finals)
            assertEquals("a", SpokenCardAnswerParser.parse(observed.finals.single(), card)?.choiceId)
            assertEquals(0, observed.noSpeech)
            assertEquals(1, observed.ends)
        }
    }
}
