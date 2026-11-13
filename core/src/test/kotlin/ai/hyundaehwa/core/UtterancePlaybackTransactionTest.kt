package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class UtterancePlaybackTransactionTest {
    private val feedback = "맞아요. 정답은 1번 황포묵입니다. 황포묵은 녹두로 만들고 전주 비빔밥의 고명으로 쓰입니다."
    @Test fun all3660CurrentBankFeedbackOutcomesAreOneCompleteBackendRequest() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/current-bank-feedback-transactions.tsv")).bufferedReader().use { it.readLines() }
        var cases = 0
        for (line in lines.filter { it.isNotBlank() && !it.startsWith("#") }) {
            val fields = line.split('\t'); assertEquals(3, fields.size)
            val transaction = UtterancePlaybackTransaction(); assertTrue(transaction.begin(1, fields[2], true))
            assertEquals(1, transaction.segmentCount, fields[0]); assertEquals(fields[2], transaction.currentText)
            assertTrue(transaction.currentText!!.contains("정답은")); assertTrue(transaction.currentText!!.length > 10)
            assertTrue(transaction.started(1, 0)); assertEquals(SegmentCompletion.FINISHED, transaction.completed(1, 0))
            cases++
        }
        assertEquals(3660, cases)
    }
    @Test fun actualAdapterCallbackDecisionCannotFinishOnAcknowledgmentBeforeRemainingChunks() {
        val transaction = UtterancePlaybackTransaction(); transaction.begin(2, feedback, false)
        val answer = AnswerSpeechQueue(); answer.enqueue("q", feedback, "전주"); val id = answer.current!!.id; answer.start(id)
        var completedTransactions = 0
        assertEquals("맞아요.", transaction.currentText)
        assertTrue(transaction.started(2, 0))
        val firstDone = transaction.completed(2, 0)
        if (firstDone == SegmentCompletion.FINISHED) { completedTransactions++; answer.complete(id) }
        assertEquals(SegmentCompletion.NEXT, firstDone); assertEquals(0, completedTransactions); assertTrue(answer.unfinished("q"))
        assertTrue(transaction.remainingText.contains("정답은")); assertTrue(transaction.remainingText.endsWith("쓰입니다."))
        while (transaction.currentText != null) {
            val index = transaction.index; assertTrue(transaction.started(2, index))
            if (transaction.completed(2, index) == SegmentCompletion.FINISHED) { completedTransactions++; answer.complete(id) }
        }
        assertEquals(1, completedTransactions); assertEquals(AnswerSpeechPhase.COMPLETED, answer.current!!.phase)
    }
    @Test fun packedAnswerHasNoEngineHandoffBetweenAcknowledgmentAnswerAndExplanation() {
        val transaction = UtterancePlaybackTransaction(); transaction.begin(3, feedback, true)
        assertEquals(feedback, transaction.currentText); assertEquals(1, transaction.segmentCount)
        assertTrue(transaction.started(3, 0)); assertEquals(SegmentCompletion.FINISHED, transaction.completed(3, 0))
        assertNull(transaction.currentText); assertEquals("", transaction.remainingText)
    }
    @Test fun noStartDuplicateStaleOrCancelledBackendCallbackCanCompleteTransaction() {
        val transaction = UtterancePlaybackTransaction(); transaction.begin(5, feedback, true)
        assertEquals(SegmentCompletion.MISSING_START, transaction.completed(5, 0))
        assertEquals(SegmentCompletion.IGNORED, transaction.completed(5, 0))
        transaction.begin(6, feedback, true)
        assertFalse(transaction.started(5, 0)); assertTrue(transaction.started(6, 0)); assertFalse(transaction.started(6, 0))
        transaction.cancel(); assertEquals(SegmentCompletion.IGNORED, transaction.completed(6, 0))
        transaction.begin(7, feedback, true); assertEquals(SegmentCompletion.IGNORED, transaction.completed(6, 0))
        assertTrue(transaction.started(7, 0)); assertEquals(SegmentCompletion.FINISHED, transaction.completed(7, 0))
        assertEquals(SegmentCompletion.IGNORED, transaction.completed(7, 0))
    }
    @Test fun failedBackendCanRetrySameFullExplanationAndMustStartBeforeCompletion() {
        val transaction = UtterancePlaybackTransaction(); transaction.begin(8, feedback, true)
        assertTrue(transaction.started(8, 0)); assertTrue(transaction.retrySegment(8, 0))
        assertEquals(feedback, transaction.currentText); assertEquals(feedback, transaction.remainingText)
        assertFalse(transaction.retrySegment(7, 0)); assertFalse(transaction.retrySegment(8, 1))
        assertTrue(transaction.started(8, 0)); assertEquals(SegmentCompletion.FINISHED, transaction.completed(8, 0))
        assertFalse(transaction.retrySegment(8, 0))
    }
    @Test fun recordedFallbackRequiresTheWholeRemainingTextBeforePlayingAnyClip() {
        val transaction = UtterancePlaybackTransaction(); transaction.begin(12, feedback, false)
        assertTrue(transaction.started(12, 0)); assertEquals(SegmentCompletion.NEXT, transaction.completed(12, 0))
        val rest = transaction.remainingText
        assertNull(transaction.packRemaining(11, transaction.index))
        assertEquals(rest, transaction.packRemaining(12, transaction.index))
        assertEquals(rest, transaction.currentText); assertEquals("", transaction.remainingAfterCurrent)
        assertTrue(transaction.started(12, transaction.index))
        assertEquals(SegmentCompletion.FINISHED, transaction.completed(12, transaction.index))
    }
    @Test fun longFeedbackRetainsAllTextAndBackendProgressOnlyFinishesOnLastChunk() {
        val full = (1..50).joinToString(" ") { "${it}번째 해설 문장이며 원문이 끝까지 전달되어야 합니다." }
        val transaction = UtterancePlaybackTransaction(); transaction.begin(9, full, true)
        val sent = mutableListOf<String>(); var done = 0
        while (transaction.currentText != null) {
            sent += transaction.currentText!!; assertTrue(sent.last().length <= 300)
            val index = transaction.index; transaction.started(9, index)
            if (transaction.completed(9, index) == SegmentCompletion.FINISHED) done++
        }
        assertTrue(sent.size > 1); assertEquals(full, sent.joinToString(" ")); assertEquals(1, done)
    }
    @Test fun legitimateInterruptionCanCheckpointWholePackedFeedbackAndSafeResumeKeepsFullText() {
        val transaction = UtterancePlaybackTransaction(); transaction.begin(10, feedback, true); transaction.started(10, 0)
        val checkpoint = transaction.remainingText; assertEquals(feedback, checkpoint)
        transaction.cancel(); assertEquals(SegmentCompletion.IGNORED, transaction.completed(10, 0))
        transaction.begin(11, checkpoint, true); assertTrue(transaction.started(11, 0)); assertEquals(SegmentCompletion.FINISHED, transaction.completed(11, 0))
    }
    @Test fun ordinaryWelcomeAndQuestionDefaultToOneNaturalUtterance() {
        val full = "안녕하세요. 현대화입니다. 지금 보이는 질문의 보기 번호나 이름으로 답해 주세요."
        val transaction = UtterancePlaybackTransaction()
        assertTrue(transaction.begin(80, full))
        assertEquals(1, transaction.segmentCount)
        assertEquals(full, transaction.currentText)
        assertFalse(transaction.currentSegmentStarted)
        assertTrue(transaction.started(80, 0)); assertTrue(transaction.currentSegmentStarted)
        assertEquals(SegmentCompletion.FINISHED, transaction.completed(80, 0))
        assertFalse(transaction.currentSegmentStarted)
    }
}
