package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class LocationDrivenGuidanceTest {
    private val ready = RegionEntryConditions(true, true, false, false, false, false, false)
    private fun state() = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D, speedKph = 60.0,
        destination = "전주", regionId = "b", pendingRegionId = "b", rms = null, quietAudioMs = 0)
    @Test fun entryStartsWithoutPassiveMicrophonePermissionOrAecEvenWithLoudLegacyState() {
        for (rms in listOf(null, 0.0, 0.95)) {
            val s = state().copy(rms = rms); val queue = RegionEntryQueue(); queue.observe("b", "성북구", "전주")
            assertNotNull(queue.begin(s, ready)); assertTrue(HyundaehwaEngine.audioClear(s))
        }
    }
    @Test fun passengerConversationAndUnavailableInputDoNotCancelFullFeedbackTransaction() {
        val output = Utterance("answer", "answer", "맞아요. 정답과 전체 해설입니다.", "q")
        var s = state().copy(output = output, speechActive = true, answerExplanationHeld = true)
        for (event in listOf(ConversationEvent.SoundLevel(0.99), ConversationEvent.SoundUnavailable())) s = HyundaehwaEngine.reduce(s, event)
        assertEquals(output, s.output); assertTrue(s.speechActive); assertTrue(s.answerExplanationHeld)
    }
    @Test fun stableNewRegionWaitsWhenSoundIsUnknown() {
        var s = state(); val topic = Topic("new", TopicType.QUIZ, "b", "위치 질문")
        s = HyundaehwaEngine.reduce(s, ConversationEvent.RegionalTopicsChanged("b", listOf(topic)))
        s = HyundaehwaEngine.reduce(s, ConversationEvent.Tick(3000))
        assertNull(s.topic); assertTrue(HyundaehwaEngine.view(s).canSpeak); assertNull(s.rms)
        assertEquals(0L, s.silenceMs)
    }
    @Test fun explicitVoiceAnswerStillResolvesCurrentCardAndFullExplanation() {
        val card = AdministrativeConversationTopic("q", "quiz", "전주", "묵은?", listOf(
            AdministrativeConversationOption("a", "황포묵"), AdministrativeConversationOption("b", "도토리묵")),
            correctChoiceId = "a", explanation = "황포묵은 녹두로 만듭니다.")
        var voice = CardAnswerVoiceSession.reduce(CardAnswerVoiceState(), CardAnswerVoiceEvent.Begin(1, "q"))
        voice = CardAnswerVoiceSession.reduce(voice, CardAnswerVoiceEvent.Ready(1, "기기 내"))
        voice = CardAnswerVoiceSession.reduce(voice, CardAnswerVoiceEvent.Final(1, "1번이 정답이야", card))
        assertEquals(CardAnswerVoicePhase.RESOLVED, voice.phase); assertEquals("a", voice.answer?.choiceId)
        assertTrue(AnswerExplanation.text(card, voice.answer?.choiceId)!!.endsWith(card.explanation))
    }
    @Test fun mediaNavigationForkAndExplicitQuietStillHavePriorityWithoutAnyRmsGuard() {
        val s = state(); val queue = RegionEntryQueue(); queue.observe("b", "성북구", "전주")
        assertNull(queue.begin(s.copy(mediaActive = true), ready)); assertNull(queue.begin(s.copy(quiet = true), ready))
        assertNull(queue.begin(s.copy(guards = setOf(FocusKind.BRANCH)), ready))
        assertNull(queue.begin(s, ready.copy(navigationVoiceActive = true)))
        assertNotNull(queue.begin(s, ready))
    }
    @Test fun interruptedExplanationResumesDespiteAbsentOrLoudOldMicrophoneState() {
        val queue = PromptResumeQueue(); val output = Utterance("answer", "answer", "해설 원문", "q")
        queue.remember(output, "남은 해설", "전주", "q")
        val s = state().copy(answerExplanationHeld = true, topic = Topic("q", TopicType.QUIZ, "a", "질문"), rms = 0.9)
        val saved = queue.takeIfReady(s, "전주", "q", PromptResumeConditions(true, true, false, false, false))
        assertEquals("남은 해설", saved?.remainingText)
    }
}
