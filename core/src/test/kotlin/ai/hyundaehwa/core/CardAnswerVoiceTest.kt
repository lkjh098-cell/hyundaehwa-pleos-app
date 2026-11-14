package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class CardAnswerVoiceTest {
    private val card = AdministrativeConversationTopic("active", "quiz", "전주 이야기", "어느 묵일까요?", listOf(
        AdministrativeConversationOption("a", "황포묵", listOf("황포")), AdministrativeConversationOption("b", "도토리묵"),
        AdministrativeConversationOption("c", "청포묵")), mapOf("a" to "짧은 답변"),
        correctChoiceId = "a", explanation = "황포묵은 녹두로 만드는 묵입니다. 전주 비빔밥 고명으로 사용합니다.")
    private val safe = CardAnswerVoiceConditions(true, true, true, Gear.D, false, emptySet(), false, false, false, false, false)
    @Test fun correctWrongAndUnknownFinalAnswersRouteToFullPinnedFeedback() {
        for ((heard, expected) in listOf("1번 황포묵이요" to "a", "도토리묵" to "b", "세 번째" to "c")) {
            val parsed = assertNotNull(SpokenCardAnswerParser.parse(heard, card), heard)
            assertEquals(expected, parsed.choiceId); assertFalse(parsed.gaveUp)
            val feedback = assertNotNull(GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.ANSWER, answer = heard), false, true, false, activeCard = card))
            assertEquals("success", feedback.status); assertEquals(expected, feedback.choiceId)
            assertTrue(feedback.data["reply"].toString().endsWith(card.explanation))
        }
        for (heard in listOf("몰라", "잘 모르겠어요", "정답 알려줘")) {
            val parsed = assertNotNull(SpokenCardAnswerParser.parse(heard, card)); assertTrue(parsed.gaveUp); assertNull(parsed.choiceId)
            val result = GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.ANSWER, answer = heard), false, true, false, activeCard = card)!!
            assertTrue(result.data["reply"].toString().endsWith(card.explanation)); assertNull(result.choiceId)
        }
    }
    @Test fun koreanSecondNumberAndNaturalAnswerPredicatesWork() {
        for (heard in listOf("이번", "이 번", "2번이 정답이야", "두 번째가 정답인 것 같아", "저는 도토리묵인 것 같아요")) {
            assertEquals("b", SpokenCardAnswerParser.parse(heard, card)?.choiceId, heard)
        }
    }
    @Test fun twoWayBalanceAcceptsSpokenPositionAndOrdinalWithoutGuessingConflicts() {
        val balance = card.copy(format = "balance", options = card.options.take(2))
        for (heard in listOf("1번", "일 번이요", "앞에 거", "앞에 꺼요", "전자", "저는 전자로 할게요", "앞쪽", "첫 번째 거", "앞의 거", "앞거", "앞에 것", "첫번", "처음 거"))
            assertEquals("a", SpokenCardAnswerParser.parse(heard, balance)?.choiceId, heard)
        for (heard in listOf("2번", "이 번", "뒤에 거", "뒤에 꺼", "후자", "후자로 선택할게요", "뒤쪽이요", "뒤의 거", "뒤거", "뒤에 것", "두 번째 것", "전자 말고 후자", "1번 말고 후자", "앞에 거 말고 뒤에 거"))
            assertEquals("b", SpokenCardAnswerParser.parse(heard, balance)?.choiceId, heard)
        for (heard in listOf("둘 다", "1번 아닌 것 같아", "앞에 거 아니야", "1번 아니면 2번", "전자 후자", "앞에 거랑 뒤에 거", "민수야 후자", "'후자'", "응", "맞아"))
            assertNull(SpokenCardAnswerParser.parse(heard, balance), heard)
        assertNull(SpokenCardAnswerParser.parse("후자", card)) // three options make a relative choice ambiguous
        assertEquals("b", SpokenCardAnswerParser.parse("후자", card.copy(options = card.options.take(2)))?.choiceId)
    }
    @Test fun all2196ExactCurrentBankLabelsWinBeforeGrammarOrControlLikeFragments() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/current-bank-answer-options.tsv")).bufferedReader().use { it.readLines() }
        var cases = 0
        for (line in lines.filter { it.isNotBlank() && !it.startsWith("#") }) {
            val fields = line.split('\t'); assertEquals(8, fields.size)
            val options = (2 until fields.size step 2).map { AdministrativeConversationOption(fields[it], fields[it + 1]) }
            val question = card.copy(id = fields[0], options = options, correctChoiceId = fields[1])
            options.forEach { option -> assertEquals(option.id, SpokenCardAnswerParser.parse(option.label, question)?.choiceId, "${question.id}: ${option.label}"); cases++ }
        }
        assertEquals(2196, cases)
    }
    @Test fun exactLabelsWithControlLikeKoreanStillWorkButQuotesAndPersonAddressDoNot() {
        val special = card.copy(options = listOf(AdministrativeConversationOption("a", "왕실 목재 생산목"),
            AdministrativeConversationOption("b", "바다를 건넌 교류"), AdministrativeConversationOption("c", "들었다는 소리")))
        special.options.forEach { assertEquals(it.id, SpokenCardAnswerParser.parse(it.label, special)?.choiceId) }
        for (heard in listOf("'왕실 목재 생산목'", "민수야 왕실 목재 생산목", "왕실 목재 생산목이라고 들었어", "1번 아니면 2번", "2번 아니야", "친구랑 가요", "광화문으로 가요")) {
            assertNull(SpokenCardAnswerParser.parse(heard, special), heard)
        }
    }
    @Test fun partialTranscriptDoesNotCommitAndOnlyCurrentCardFinalCanAnswer() {
        var s = CardAnswerVoiceSession.reduce(CardAnswerVoiceState(), CardAnswerVoiceEvent.Begin(1, card.id))
        s = CardAnswerVoiceSession.reduce(s, CardAnswerVoiceEvent.Ready(1, "기기 내 음성"))
        s = CardAnswerVoiceSession.reduce(s, CardAnswerVoiceEvent.Partial(1, "2번"))
        assertTrue(s.active); assertNull(s.answer)
        assertEquals(s, CardAnswerVoiceSession.reduce(s, CardAnswerVoiceEvent.Final(1, "1번", card.copy(id = "other"))))
        s = CardAnswerVoiceSession.reduce(s, CardAnswerVoiceEvent.Final(1, "2번이 정답이야", card))
        assertEquals(CardAnswerVoicePhase.RESOLVED, s.phase); assertEquals("b", s.answer?.choiceId)
        assertEquals(s, CardAnswerVoiceSession.reduce(s, CardAnswerVoiceEvent.Final(1, "1번", card)))
    }
    @Test fun explicitAnswerWindowWorksAndLegacySoundNeverSuppressesAfterward() {
        var s = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D, speedKph = 80.0, topic = card.engineTopic("area"))
        s = HyundaehwaEngine.reduce(s, ConversationEvent.AnswerListeningChanged(true))
        s = HyundaehwaEngine.reduce(s, ConversationEvent.SoundLevel(0.2))
        assertTrue(s.answerListening); assertFalse(HyundaehwaEngine.view(s).canSpeak)
        assertEquals("answer-listening", HyundaehwaEngine.view(s).status)
        assertNull(CardAnswerVoicePolicy.blockReason(safe)) // RMS is deliberately absent from own-answer eligibility.
        s = HyundaehwaEngine.reduce(s, ConversationEvent.AnswerListeningChanged(false))
        assertNotEquals("conversation", HyundaehwaEngine.view(s).status)
        assertTrue(HyundaehwaEngine.view(s).canSpeak)
    }
    @Test fun mediaNavigationForkQuietAndOtherContextsStillBlockOwnAnswerAsr() {
        val blocked = listOf(safe.copy(cardOpen = false), safe.copy(enabled = false), safe.copy(resumed = false),
            safe.copy(mediaActive = true), safe.copy(mediaActive = null), safe.copy(navigationVoiceActive = true),
            safe.copy(quiet = true), safe.copy(promptVoiceActive = true), safe.copy(explanationHeld = true),
            safe.copy(otherInputOpen = true), safe.copy(gear = Gear.R), safe.copy(gear = Gear.N)) +
            FocusKind.entries.map { safe.copy(guards = setOf(it)) }
        blocked.forEach { assertNotNull(CardAnswerVoicePolicy.blockReason(it)) }
        assertNull(CardAnswerVoicePolicy.blockReason(safe.copy(gear = Gear.P)))
    }
    @Test fun noMicrophoneStartIsFailureNotFakeUnansweredResult() {
        val preparing = CardAnswerVoiceSession.reduce(CardAnswerVoiceState(), CardAnswerVoiceEvent.Begin(1, card.id))
        val failed = CardAnswerVoiceSession.reduce(preparing, CardAnswerVoiceEvent.Timeout(1))
        assertEquals(CardAnswerVoicePhase.FAILED, failed.phase); assertNull(failed.answer)
        val listening = CardAnswerVoiceSession.reduce(preparing, CardAnswerVoiceEvent.Ready(1, "기기 내 음성"))
        val empty = CardAnswerVoiceSession.reduce(listening, CardAnswerVoiceEvent.Timeout(1))
        assertEquals(CardAnswerVoicePhase.EMPTY_TIMEOUT, empty.phase); assertNull(empty.answer)
        val partial = CardAnswerVoiceSession.reduce(listening, CardAnswerVoiceEvent.Partial(1, "2번"))
        assertEquals(CardAnswerVoicePhase.UNMATCHED, CardAnswerVoiceSession.reduce(partial, CardAnswerVoiceEvent.Timeout(1)).phase)
    }
    @Test fun safetyCancellationCanRetryOnceSafeAndOldCallbacksAreIgnored() {
        var s = CardAnswerVoiceSession.reduce(CardAnswerVoiceState(), CardAnswerVoiceEvent.Begin(1, card.id))
        s = CardAnswerVoiceSession.reduce(s, CardAnswerVoiceEvent.Cancel(1, "길 안내", true))
        assertTrue(s.retryWhenSafe); assertFalse(s.active)
        assertEquals(s, CardAnswerVoiceSession.reduce(s, CardAnswerVoiceEvent.Final(1, "1번", card)))
        s = CardAnswerVoiceSession.reduce(s, CardAnswerVoiceEvent.Begin(2, card.id))
        assertEquals(s, CardAnswerVoiceSession.reduce(s, CardAnswerVoiceEvent.Partial(1, "2번")))
    }
    @Test fun answerListeningHoldsNewPromptsAndDoesNotAccumulateSilenceAsUserSpeaks() {
        var s = HyundaehwaEngine.simulation(Scenario.FRIEND).copy(enabled = true, gear = Gear.D, speedKph = 80.0,
            topic = card.engineTopic("area"), answerListening = true)
        s = HyundaehwaEngine.reduce(s, ConversationEvent.Tick(300_000, 8000))
        assertNull(s.output); assertEquals(0L, s.destinationSilenceMs); assertEquals(0L, s.silenceMs)
        assertEquals(8000L, s.wallElapsedMs); assertTrue(s.answerListening)
    }
    @Test fun explicitQuizCorrectionAndDisclosureCommitOnlyOnce() {
        for (text in listOf("황포묵 아니고 도토리묵", "1번 말고 2번", "도토리묵 아니고 3번")) {
            val expected = if (text.endsWith("3번")) "c" else "b"
            assertEquals(expected, SpokenCardAnswerParser.parse(text, card)?.choiceId, text)
        }
        for (text in listOf("정답 보여줘", "정답 보기", "정답을 알려주세요", "정답은 사자 같은데")) {
            val answer = assertNotNull(SpokenCardAnswerParser.parse(text, card), text)
            assertTrue(answer.gaveUp); assertNull(answer.choiceId)
            var session = CardAnswerVoiceSession.reduce(CardAnswerVoiceState(), CardAnswerVoiceEvent.Begin(100, card.id))
            session = CardAnswerVoiceSession.reduce(session, CardAnswerVoiceEvent.Final(100, text, card))
            assertEquals(CardAnswerVoicePhase.RESOLVED, session.phase)
            assertEquals(session, CardAnswerVoiceSession.reduce(session, CardAnswerVoiceEvent.Final(100, "1번", card)))
        }
    }
    @Test fun newQuizToleranceDoesNotAcceptChatterAmbiguityOrNegationWithoutPositiveChoice() {
        for (text in listOf("황포묵 아니고", "황포묵 아니고 황포묵", "1번 아니면 2번", "황포묵 아니고 도토리묵 아니고 청포묵",
            "엄마 정답 보여줘", "친구야 모르겠어", "‘정답 보기’", "민수야 도토리묵", "정답은 황포묵하고 도토리묵 같은데",
            "정답은 사자하고 호랑이 같은데", "정답은 민수가 말한 것 같아", "비 올 것 같은데"))
            assertNull(SpokenCardAnswerParser.parse(text, card), text)
    }
}
