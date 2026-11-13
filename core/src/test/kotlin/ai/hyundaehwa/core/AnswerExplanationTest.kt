package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class AnswerExplanationTest {
    private val explanation = "황포묵은 녹두로 만든 묵입니다. 전주 비빔밥의 고명으로 사용됩니다."
    private val card = AdministrativeConversationTopic("jeonju-quiz", "quiz", "전주 이야기", "무슨 묵일까요?", listOf(
        AdministrativeConversationOption("a", "황포묵"), AdministrativeConversationOption("b", "도토리묵")),
        mapOf("a" to "맞아요.", "b" to "아쉬워요."), correctChoiceId = "a", explanation = explanation)
    @Test fun correctWrongUnknownManualAndTimedOutPathsAlwaysContainFullPinnedExplanation() {
        for ((choice, timeout) in listOf("a" to false, "b" to false, null to false, null to true)) {
            val text = assertNotNull(AnswerExplanation.text(card, choice, timeout))
            assertTrue(text.endsWith(explanation)); assertTrue(text.contains("1번입니다. 황포묵."))
            assertEquals(1, Regex(Regex.escape(explanation)).findAll(text).count())
            if (timeout) assertTrue(text.startsWith("아쉽네요."))
        }
        assertTrue(AnswerExplanation.text(card, "a")!!.startsWith("맞아요."))
        assertTrue(AnswerExplanation.text(card, "b")!!.startsWith("아쉽네요."))
        assertFalse(AnswerExplanation.text(card, null)!!.startsWith("맞아요."))
    }
    @Test fun reportedFeedbackUsesFullBankFactRatherThanShortChoiceReply() {
        for (text in listOf("1번", "2번", "몰라", "정답 알려줘")) {
            val result = assertNotNull(GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.ANSWER, answer = text), false, true, true, activeCard = card))
            assertEquals("success", result.status)
            assertTrue(result.data["reply"].toString().endsWith(explanation), text)
        }
    }
    @Test fun nonQuizFollowupIsSpokenWithoutGradingAndBalanceTimeoutStillHasNoDisclaimer() {
        val preference = card.copy(format = "balance", correctChoiceId = null, replyByChoice = mapOf("a" to "그쪽 취향이군요."))
        assertEquals("그쪽 취향이군요.", AnswerExplanation.text(preference, "a"))
        assertNull(AnswerExplanation.text(preference, null, true))
        assertNotNull(AnswerExplanation.text(preference.copy(format = "conversation"), null, true))
    }
    @Test fun duplicateRevealAndStaleCallbacksCannotReplayOrCompleteTheReading() {
        val q = AnswerSpeechQueue()
        assertTrue(q.enqueue(card.id, AnswerExplanation.text(card, "a"), "전주"))
        val id = q.current!!.id
        assertFalse(q.enqueue(card.id, AnswerExplanation.text(card, "b"), "전주"))
        assertFalse(q.complete(id)); assertTrue(q.unfinished(card.id))
        q.start(id); assertFalse(q.complete("old-id")); assertTrue(q.complete(id))
        assertFalse(q.enqueue(card.id, AnswerExplanation.text(card, "a"), "전주"))
        assertFalse(q.unfinished(card.id)); assertFalse(q.complete(id))
        assertTrue(q.retry()); assertNotEquals(id, q.current!!.id)
    }
    @Test fun interruptionFailureAndExplicitCancellationNeverClaimCompletion() {
        val q = AnswerSpeechQueue(); q.enqueue(card.id, explanation, "전주")
        val id = q.current!!.id; q.start(id); q.pause(id)
        assertEquals(AnswerSpeechPhase.PAUSED, q.current!!.phase); assertTrue(q.unfinished(card.id))
        assertFalse(q.complete(id)); q.start(id); q.fail(id)
        assertEquals(AnswerSpeechPhase.FAILED, q.current!!.phase); assertTrue(q.unfinished(card.id))
        q.cancel(); assertEquals(AnswerSpeechPhase.CANCELLED, q.current!!.phase); assertFalse(q.complete(id))
        assertFalse(q.unfinished(card.id)); assertTrue(q.retry())
    }
    @Test fun answerAndCardStayHeldThroughBoundaryRestAndPhysicalArrivalUntilRelease() {
        val topic = card.engineTopic("old-area")
        var s = HyundaehwaEngine.simulation(Scenario.FRIEND).copy(enabled = true, gear = Gear.D, speedKph = 80.0,
            topic = topic, output = Utterance("answer-1", "answer", explanation, card.id), speechActive = true, answerExplanationHeld = true)
        s = HyundaehwaEngine.reduce(s, ConversationEvent.RegionChanged("new-area", "새 지역", s.destination))
        assertEquals(topic, s.topic); assertTrue(s.answerExplanationHeld)
        s = HyundaehwaEngine.reduce(s, ConversationEvent.RegionalTopicsChanged("new-area", listOf(Topic("new", TopicType.QUIZ, "new-area", "새 문제"))))
        s = HyundaehwaEngine.reduce(s, ConversationEvent.Tick(3_600_000))
        assertTrue(s.restDue); assertEquals("answer-1", s.output?.id); assertTrue(s.speechActive); assertEquals(topic, s.topic) // A boundary no longer cuts the current explanation.
        s = HyundaehwaEngine.reduce(s, ConversationEvent.ArrivalChanged(true))
        assertTrue(s.arrived); assertFalse(s.arrivalAnnounced); assertEquals(topic, s.topic)
        s = HyundaehwaEngine.reduce(s, ConversationEvent.OutputDone("answer-1"))
        assertFalse(s.arrivalAnnounced)
        s = HyundaehwaEngine.reduceBatch(s, listOf(ConversationEvent.ReleaseTopic, ConversationEvent.AnswerExplanationHold(false)))
        assertEquals("arrival", s.output?.kind)
    }
    @Test fun answerCheckpointWaitsForMediaConversationAndAllGuardsThenSurvivesArrival() {
        val text = AnswerExplanation.text(card, "b")!!
        val output = Utterance("answer-2", "answer", text, card.id)
        val q = PromptResumeQueue(); q.remember(output, explanation, "전주", card.id)
        val safe = HyundaehwaEngine.simulation().copy(enabled = true, topic = card.engineTopic("area"), answerExplanationHeld = true, arrived = true, destination = "전주")
        val conditions = PromptResumeConditions(true, true, true, false, false)
        assertNull(q.takeIfReady(safe.copy(mediaActive = true), "전주", card.id, conditions))
        assertNull(q.takeIfReady(safe.copy(guards = setOf(FocusKind.BRANCH)), "전주", card.id, conditions))
        val resumed = assertNotNull(q.takeIfReady(safe, "전주", card.id, conditions))
        assertEquals(explanation, resumed.remainingText); assertEquals(output.id, resumed.utterance.id)
        assertNull(q.takeIfReady(safe, "전주", card.id, conditions))
    }
    @Test fun longExplanationsAreNotTruncatedAt800AndEachChunkKeepsAllContent() {
        val full = (1..50).joinToString(" ") { "${it}번째 지역의 역사와 건축을 설명하는 원문 문장입니다." }
        assertTrue(full.length > 800)
        val chunks = PromptSentences.forSpeech(full)
        assertTrue(chunks.all { it.length <= 300 })
        assertEquals(full.replace(Regex("\\s+"), ""), chunks.joinToString("").replace(Regex("\\s+"), ""))
        val oneLongSentence = "지역해설".repeat(250)
        assertEquals(oneLongSentence, PromptSentences.forSpeech(oneLongSentence).joinToString(""))
        assertTrue(PromptSentences.timeoutMs(chunks.first()) > 8_000)
    }
    @Test fun newCardGetsNewSpeechIdAndOldCompletionCannotAffectIt() {
        val q = AnswerSpeechQueue(); q.enqueue("old", "이전 해설", "이전 목적지")
        val id = q.current!!.id; q.start(id); q.reset()
        q.enqueue("new", "새 해설", "새 목적지")
        assertFalse(q.complete(id)); assertEquals("새 해설", q.current!!.text)
    }
}
