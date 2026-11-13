package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class GleoTalkActionsTest {
    private fun handle(input: GleoTalkInput, quiet: Boolean = false, parked: Boolean = true) = GleoTalkActions.handle(input, quiet, true, parked)
    @Test fun onlyThreeDocumentedCapabilitiesExist() {
        assertEquals(setOf("GET_TALK_TOPIC", "SET_TALK_QUIET", "ANSWER_TALK_CARD"), GleoTalkActions.names)
        assertNull(handle(GleoTalkInput("CHANGE_ROUTE")))
    }
    @Test fun unknownAndExpiredLocationNeverFallBackToAnUnrelatedQuestion() {
        for (context in listOf(null, GleoTopicContext("unsupported"))) {
            val r = checkNotNull(GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.TOPIC), false, true, true, context = context))
            assertEquals("asking", r.status); assertNull(r.topic); assertTrue(r.data.isEmpty())
            assertTrue(r.message.contains("위치"))
        }
    }
    @Test fun anUnresolvedAreaCannotProduceAnAddressBasedFallback() {
        val r = checkNotNull(GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.TOPIC, "travel"), false, true, true, context = GleoTopicContext("seoul")))
        assertEquals("asking", r.status); assertNull(r.topic)
    }
    @Test fun malformedAndInvalidParametersDoNotExecuteAnAction() {
        val r = checkNotNull(handle(GleoTalkInput(GleoTalkActions.QUIET, enabled = true, malformed = true)))
        assertEquals("fail", r.status); assertNull(r.setQuiet)
        assertEquals("asking", handle(GleoTalkInput(GleoTalkActions.TOPIC, "unsupported"))?.status)
        assertEquals("asking", handle(GleoTalkInput(GleoTalkActions.QUIET))?.status)
    }
    @Test fun quietActionWorksWithoutDrivingOrOccupancyDetailsAndStaysSilent() {
        val r = checkNotNull(handle(GleoTalkInput(GleoTalkActions.QUIET, enabled = true), parked = false))
        assertEquals(true, r.setQuiet); assertEquals("success", r.status); assertEquals("silent", r.mode)
        assertEquals(mapOf("quiet" to true), r.data)
    }
    @Test fun quietCannotBeResumedWithoutConfirmedPark() {
        val r = checkNotNull(handle(GleoTalkInput(GleoTalkActions.QUIET, enabled = false), quiet = true, parked = false))
        assertNull(r.setQuiet); assertEquals("fail", r.status); assertEquals("silent", r.mode)
        assertEquals(false, handle(GleoTalkInput(GleoTalkActions.QUIET, enabled = false), quiet = true)?.setQuiet)
    }
    @Test fun topicsCannotBypassQuietMode() {
        val r = checkNotNull(handle(GleoTalkInput(GleoTalkActions.TOPIC), quiet = true))
        assertEquals("fail", r.status); assertEquals("silent", r.mode); assertNull(r.topic)
    }
    @Test fun focusMediaOrNavigationPriorityDefersTopicSilently() {
        val r = checkNotNull(GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.TOPIC), false, true, true, voiceBlocked = true))
        assertEquals("fail", r.status); assertEquals("silent", r.mode); assertNull(r.topic)
        assertTrue(r.data.isEmpty())
    }
    @Test fun answerRequiresAnActiveCardAndSingleChoice() {
        val card = AdministrativeConversationTopic("id", "quiz", "title", "question", listOf(
            AdministrativeConversationOption("a", "감홍 사과", listOf("감홍")), AdministrativeConversationOption("b", "배")),
            mapOf("a" to "맞아요", "b" to "정답은 감홍이에요"), correctChoiceId = "a", explanation = "감홍 사과예요.")
        fun result(text: String, blocked: Boolean = false) = GleoTalkActions.handle(GleoTalkInput(GleoTalkActions.ANSWER, answer = text),
            false, true, false, blocked, activeCard = card)!!
        assertEquals("asking", handle(GleoTalkInput(GleoTalkActions.ANSWER, answer = "1번"))?.status)
        assertEquals("a", result("1번 감홍 같은데요").choiceId)
        assertEquals("b", result("두 번째").choiceId)
        assertEquals("asking", result("1번 아니면 2번").status)
        assertEquals("success", result("몰라").status)
        assertEquals("success", result("이번에는 모르겠어요").status)
        assertNull(result("이번에는 모르겠어요").choiceId)
        // One explicit correction names two distinct displayed choices; only its positive choice commits.
        val corrected = result("1번말고2번")
        assertEquals("success", corrected.status)
        assertEquals("b", corrected.choiceId)
        assertTrue(corrected.data["reply"].toString().endsWith(card.explanation))
        assertEquals("asking", result("1번말고").status)
        assertEquals("asking", result("1번말고1번").status)
        assertEquals("asking", result("감홍은 아닌 것 같아요").status)
        assertNull(CardAnswer.match("망향휴게소에는 안 갈게요",listOf(AdministrativeConversationOption("m", "망향휴게소"))))
        assertEquals("asking", result("이번 여행 재밌다").status)
        assertEquals("asking", result("이 번호 차 바꿀까").status)
        assertEquals("silent", result("1번", true).mode)
    }
}
