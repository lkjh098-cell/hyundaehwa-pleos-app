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
        lateinit var expireFinalGrace: () -> Unit
        var pendingFinal = 0
        var starts = 0
        var speechStarts = 0
        var noSpeech = 0
        var ends = 0
        val finals = mutableListOf<String>()
        override fun started() { starts++ }
        override fun speechStarted() { speechStarts++ }
        override fun awaitingFinal() { pendingFinal++ }
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
            PleosRecognitionBackend::class.java.getDeclaredField("requestIssued").apply { isAccessible = true }.setBoolean(backend, true)
            PleosRecognitionBackend::class.java.getDeclaredField("events").apply { isAccessible = true }.set(backend, observed)
            listener = PleosRecognitionBackend::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(backend) as ResultListener
        }
        observed.expireFinalGrace = {
            instrumentation.runOnMainSync {
                val timeout = PleosRecognitionBackend::class.java.getDeclaredField("completionFallback")
                    .apply { isAccessible = true }.get(backend) as? Runnable
                assertNotNull("The final-result grace must be bounded by a scheduled timeout", timeout)
                timeout!!.run()
            }
            instrumentation.waitForIdleSync()
        }
        try { test(listener, observed) { instrumentation.waitForIdleSync() } }
        finally { instrumentation.runOnMainSync { backend.release() } }
    }
    @Test fun postRequestReadyInvitesSpeechWithoutWaitingForEpdOnset() = withListener { listener, observed, drain ->
        listener.onReady(); listener.onReady(); drain()
        assertEquals(1, observed.starts)
        assertEquals("Readiness is not evidence of user speech", 0, observed.speechStarts)
        assertTrue(observed.finals.isEmpty())
        listener.onStartedRecognition(); drain()
        assertEquals("EPD onset must not restart the answer deadline", 1, observed.starts)
        assertEquals(1, observed.speechStarts)
    }
    @Test fun initializationReadyCannotBecomeCaptureReadyAfterQueueDelayAndReleasedCallbacksAreIgnored() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val observed = Observed()
        lateinit var backend: PleosRecognitionBackend
        lateinit var listener: ResultListener
        val requested = PleosRecognitionBackend::class.java.getDeclaredField("requestIssued").apply { isAccessible = true }
        instrumentation.runOnMainSync {
            backend = PleosRecognitionBackend(instrumentation.targetContext)
            PleosRecognitionBackend::class.java.getDeclaredField("events").apply { isAccessible = true }.set(backend, observed)
            listener = PleosRecognitionBackend::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(backend) as ResultListener
            listener.onReady() // callback is queued while requestIssued is false
            requested.setBoolean(backend, true) // same main turn; queued callback must stay ignored
        }
        try {
            instrumentation.waitForIdleSync()
            assertEquals(0, observed.starts)
            listener.onReady(); instrumentation.waitForIdleSync()
            assertEquals(1, observed.starts)
            instrumentation.runOnMainSync {
                listener.onReady(); listener.onStartedRecognition()
                backend.release() // queued callbacks belong to the released request
            }
            instrumentation.waitForIdleSync()
            assertEquals(1, observed.starts)
            assertEquals(0, observed.speechStarts)
        } finally { instrumentation.runOnMainSync { backend.release() } }
        // A new backend after recovery has its own request/readiness lifecycle.
        withListener { fresh, next, drain ->
            fresh.onReady(); drain()
            assertEquals(1, next.starts)
            assertEquals(0, next.speechStarts)
        }
    }
    @Test fun emptyEarlyEndRequestsRecoveryOnceWithoutSubmittingAnAnswer() = withListener { listener, observed, drain ->
        listener.onEndedRecognition(); listener.onEndedRecognition(); drain()
        assertEquals(0, observed.noSpeech)
        assertEquals(1, observed.pendingFinal)
        observed.expireFinalGrace()
        assertEquals(1, observed.noSpeech)
        assertEquals(0, observed.starts)
        assertTrue(observed.finals.isEmpty())
        assertEquals(0, observed.ends)
    }
    @Test fun emptyEndAfterCaptureUsesRecoveryInsteadOfUnmatchedSubmission() = withListener { listener, observed, drain ->
        listener.onStartedRecognition(); listener.onEndedRecognition(); drain()
        assertEquals(0, observed.noSpeech)
        observed.expireFinalGrace()
        assertEquals(1, observed.starts)
        assertEquals(1, observed.noSpeech)
        assertTrue(observed.finals.isEmpty())
    }
    @Test fun partialSpeechIsNeverPromotedToAnAnswerByTheEndCallback() = withListener { listener, observed, drain ->
        listener.onUpdated("1번", false); listener.onEndedRecognition(); drain()
        assertTrue(observed.finals.isEmpty())
        observed.expireFinalGrace()
        assertEquals(1, observed.starts)
        assertEquals(0, observed.noSpeech)
        assertEquals(listOf(""), observed.finals)
        assertEquals(1, observed.ends)
    }
    @Test fun epdEndBeforeCompletedTextKeepsTheOriginalResultAndNeverRetries() {
        // Match the real SDK ordering: EPD_OFF, then completed text about 150 ms later.
        for (text in listOf("1번", "응")) withListener { listener, observed, drain ->
            listener.onReady(); listener.onEndedRecognition(); listener.onEndedRecognition(); drain()
            assertEquals(1, observed.pendingFinal)
            assertEquals(0, observed.noSpeech)
            assertTrue(observed.finals.isEmpty())
            Thread.sleep(150)
            listener.onUpdated(text, true); drain()
            assertEquals(listOf(text), observed.finals)
            assertEquals(0, observed.noSpeech)
            assertEquals(1, observed.ends)
            listener.onUpdated("2번", true); listener.onEndedRecognition(); drain()
            assertEquals("Late callbacks cannot replace the original final", listOf(text), observed.finals)
        }
    }
    @Test fun finalWithoutEpdEndStillUsesTheExistingStableCompletionFallback() = withListener { listener, observed, drain ->
        listener.onUpdated("1번", true); drain()
        assertTrue(observed.finals.isEmpty())
        observed.expireFinalGrace()
        assertEquals(listOf("1번"), observed.finals)
        assertEquals(0, observed.noSpeech)
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
