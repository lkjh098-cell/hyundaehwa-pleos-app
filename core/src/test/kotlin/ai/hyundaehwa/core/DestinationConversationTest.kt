package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class DestinationConversationTest {
    private fun step(s: ConversationState, e: ConversationEvent) = HyundaehwaEngine.reduce(s, e)
    private fun base() = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D, speedKph = 80.0,
        destination = "안목해변", presentedRegionIds = setOf("used-area"), regionId = "used-area", usedTopicIds = setOf("old-quiz"))
    @Test fun quietTimeAndUserActivityNeverCreateAnotherAutomaticDestinationPrompt() {
        var s = step(base(), ConversationEvent.Tick(300000))
        s = step(s, ConversationEvent.UserResponded); s = step(s, ConversationEvent.SoundLevel(0.8))
        s = step(s, ConversationEvent.Tick(600000))
        assertNull(s.output); assertNull(s.topic)
        assertEquals(0L, s.destinationSilenceMs); assertEquals(0, s.destinationPromptCount)
    }
    @Test fun destinationActivityStillUsesActualSelectedDestinationAsARegionalRotationCard() {
        val card = DestinationConversation.rotationCard("안목해변", Relationship.FRIEND, 0, "region")!!
        assertTrue(card.question.contains("안목해변")); assertEquals("destination", card.contentRotationKey)
        assertTrue(card.contentNote.contains("이용 가능")); assertNull(card.correctChoiceId)
        assertNull(DestinationConversation.card(" ", Relationship.FRIEND, 0))
    }
    @Test fun relationTailoringIsExplicitAndDoesNotInventBusinessesOrWeather() {
        val friend = assertNotNull(DestinationConversation.card("강릉", Relationship.FRIEND, 0))
        val partner = assertNotNull(DestinationConversation.card("강릉", Relationship.PARTNER, 0))
        val boss = assertNotNull(DestinationConversation.card("강릉", Relationship.BOSS, 0))
        assertTrue(friend.question.contains("친구들과"))
        assertTrue(partner.question.contains("연인과"))
        assertTrue(boss.question.contains("함께"))
        assertEquals(friend.options.map { it.label }, partner.options.map { it.label })
        assertEquals(listOf("잠깐 쉬면서 하루를 천천히 시작해요", "쉬기 전에 가장 하고 싶던 일부터 해요"), friend.options.map { it.label })
        assertTrue(Relationship.entries.all { DestinationConversation.card("목적지", it, 0)?.options?.size == 2 })
    }
    @Test fun pendingOneHourRestDoesNotCoverAQuestionOrItsExplanation() {
        val quiz = Topic("current", TopicType.QUIZ, "used-area", "질문")
        var s = step(base().copy(topic = quiz, output = Utterance("asking", "topic", "질문", quiz.id)), ConversationEvent.Tick(3_600_000))
        assertTrue(s.restDue); assertFalse(s.restAnnounced); assertEquals("asking", s.output?.id)
        assertNotEquals("rest", HyundaehwaEngine.view(s).status)
        s = step(s, ConversationEvent.OutputDone())
        s = step(s, ConversationEvent.AnswerSpoken("정답 해설"))
        assertEquals("answer", s.output?.kind)
        s = step(s, ConversationEvent.OutputDone()); assertNull(s.output)
        s = step(s, ConversationEvent.ReleaseTopic)
        assertEquals("rest", s.output?.kind); assertTrue(s.restAnnounced)
    }
    @Test fun oneHourRestHasPriorityAndUsesOnlyVerifiedAheadDirectionFacilities() {
        val valid = RestFacilities.ahead(listOf(RestInstruction("천안호두휴게소 부산방향", 15_000.0), RestInstruction("망향휴게소 부산방향", 5_000.0)))
        assertEquals(listOf("망향휴게소", "천안호두휴게소"), valid.map { it.entrance.label })
        assertTrue(RestFacilities.ahead(listOf(RestInstruction("망향휴게소 서울방향", 5_000.0), RestInstruction("천안호두휴게소 부산방향", -1.0), RestInstruction("알수없는휴게소 부산방향", 100.0))).isEmpty())
        var s = step(base().copy(destination = ""), ConversationEvent.RestOptionsChanged(valid.map { it.entrance.label }))
        s = step(s, ConversationEvent.Tick(3_599_999)); assertFalse(s.restDue)
        s = step(s, ConversationEvent.Tick(1)); assertEquals("rest", s.output?.kind)
        assertTrue(s.output!!.text.contains("망향휴게소")); assertTrue(s.output!!.text.contains("천안호두휴게소"))
        val unknown = step(base(), ConversationEvent.Tick(3_600_000))
        assertEquals("rest", unknown.output?.kind)
        assertFalse(unknown.output!!.text.contains("망향휴게소")); assertFalse(unknown.output!!.text.contains("천안호두휴게소"))
        assertTrue(unknown.output!!.text.contains("안전"))
    }
}
