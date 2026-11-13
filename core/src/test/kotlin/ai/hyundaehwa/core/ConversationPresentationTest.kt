package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class ConversationPresentationTest {
    @Test fun unselectedRelationshipNeverDisplaysTheDefaultScenarioAsARealPassenger() {
        assertEquals("동승자 선택 전", ConversationPresentation.passengerLabel(null))
    }
    @Test fun eachSelectedRelationshipHasAnExplicitPersistentLabel() {
        val expected = mapOf(Relationship.FAMILY to "동승자 · 가족", Relationship.FRIEND to "동승자 · 친구",
            Relationship.PARTNER to "동승자 · 연인", Relationship.COLLEAGUE to "동승자 · 동료",
            Relationship.BOSS to "동승자 · 상사", Relationship.STRANGER to "동승자 · 처음 만난 사이",
            Relationship.SOLO to "혼자 운전")
        assertEquals(Relationship.entries.toSet(), expected.keys)
        expected.forEach { (relationship, label) -> assertEquals(label, ConversationPresentation.passengerLabel(relationship)) }
    }

    @Test fun focusHidesAnActiveCardWithoutDeletingIt() {
        val card = Topic("active", TopicType.QUIZ, "region", "질문")
        val state = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D,
            topic = card, guards = setOf(FocusKind.BRANCH))
        assertEquals("길 안내를 우선할게요", ConversationPresentation.compact(state, false)?.label)
        assertSame(card, state.topic)
        assertNull(ConversationPresentation.compact(state.copy(guards = emptySet()), false))
    }
    @Test fun unknownGuidanceNeverClaimsTheRoadIsClear() {
        val state = HyundaehwaEngine.initial().copy(enabled = true, guards = setOf(FocusKind.BRANCH))
        assertEquals("길 안내 확인 중", ConversationPresentation.compact(state, false, true)?.label)
        assertNull(ConversationPresentation.compact(state, true))
    }
    @Test fun mediaAndQuietRemainCompactAndAreNotTreatedAsSilence() {
        val base = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D)
        assertEquals("미디어 재생 중", ConversationPresentation.compact(base.copy(mediaActive = true), false)?.label)
        assertEquals("잠시 기다릴게요", ConversationPresentation.compact(base.copy(mediaActive = null), false)?.label)
        assertEquals("조용히 함께 갈게요", ConversationPresentation.compact(base.copy(quiet = true), false)?.label)
        assertEquals(0L, base.silenceMs)
    }
    @Test fun onlyConfirmedParkingRestoresTheFullSetupPanel() {
        val state = HyundaehwaEngine.initial()
        assertNotNull(ConversationPresentation.compact(state, false))
        assertNull(ConversationPresentation.compact(state, true))
    }
}
