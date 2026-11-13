package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class RelationshipSpeechTest {
    @Test fun naturalAccompanimentPhrasesChooseAllSevenRelationships() {
        val cases = mapOf("가족이랑 같이 가요" to Relationship.FAMILY, "친구랑 가고 있어" to Relationship.FRIEND,
            "친구랑 왔어" to Relationship.FRIEND, "남자친구랑 가요" to Relationship.PARTNER,
            "여자친구랑 같이 타고 있어요" to Relationship.PARTNER, "커플이야" to Relationship.PARTNER,
            "회사 동료랑 출장 가요" to Relationship.COLLEAGUE, "부장님이랑 같이 가" to Relationship.BOSS,
            "모르는 사람이랑 가요" to Relationship.STRANGER, "오늘 처음 만났어" to Relationship.STRANGER,
            "혼자예요" to Relationship.SOLO, "혼자 운전하고 있어요" to Relationship.SOLO,
            "엄마랑 가요" to Relationship.FAMILY, "아빠랑 왔어" to Relationship.FAMILY, "부모님이랑 같이 가요" to Relationship.FAMILY,
            "아이들이랑 가요" to Relationship.FAMILY, "아내랑 가요" to Relationship.PARTNER, "남편이랑 왔어요" to Relationship.PARTNER,
            "배우자랑 같이 가요" to Relationship.PARTNER, "부부예요" to Relationship.PARTNER, "동창이랑 가요" to Relationship.FRIEND)
        cases.forEach { (text, relation) -> assertEquals(relation, RelationshipSpeech.parse(text), text) }
    }
    @Test fun directLabelsAndInflectionsRemainAccepted() {
        val cases = mapOf("가족" to Relationship.FAMILY, "친구예요" to Relationship.FRIEND,
            "연인이에요" to Relationship.PARTNER, "동료" to Relationship.COLLEAGUE,
            "상사" to Relationship.BOSS, "처음 만난 사이" to Relationship.STRANGER,
            "혼자" to Relationship.SOLO, "친구들이랑 여행 가요" to Relationship.FRIEND)
        cases.forEach { (text, relation) -> assertEquals(relation, RelationshipSpeech.parse(text), text) }
    }
    @Test fun partnerCompoundNeverBecomesOrdinaryFriend() {
        assertEquals(Relationship.PARTNER, RelationshipSpeech.parse("남자 친구랑 가고 있어요"))
        assertEquals(Relationship.PARTNER, RelationshipSpeech.parse("여자 친구랑 왔어요"))
    }
    @Test fun negationReportedSpeechConflictsAndPersonAddressesDoNotChangeRelationship() {
        for (text in listOf("가족이 아니에요", "친구 말고 동료", "혼자가 아니야", "가족과 친구랑 같이 가요",
            "친구가 가족이라고 했어", "부장님 광화문 갈까요", "친구 만나러 가요", "친구 집으로 갈게",
            "가족공원으로 가요", "왕십리역으로 가요", "친구가 있어요", "오늘 날씨 좋아요",
            "친구랑 싸웠어", "민수야 친구랑 가요", "친구랑 대구 가요", "")) {
            assertNull(RelationshipSpeech.parse(text), text)
        }
    }
    @Test fun onlyFinalResultCommitsAndStaleRecognizerCallbacksCannotChoose() {
        var s = RelationshipVoiceSession.reduce(RelationshipVoiceState(), RelationshipVoiceEvent.Begin(1))
        assertEquals(RelationshipVoicePhase.PREPARING, s.phase)
        s = RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Ready(1, "기기 내 음성"))
        s = RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Partial(1, "친구랑"))
        assertNull(s.relationship); assertTrue(s.active)
        s = RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Final(1, "친구랑 가고 있어"))
        assertEquals(Relationship.FRIEND, s.relationship); assertFalse(s.active)
        assertEquals(s, RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Final(1, "가족이랑 가요")))
        s = RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Begin(2))
        assertEquals(s, RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Final(1, "가족이랑 가요")))
    }
    @Test fun unsupportedOrDeniedRecognitionHasVisibleFailureAndTouchFallback() {
        var s = RelationshipVoiceSession.reduce(RelationshipVoiceState(), RelationshipVoiceEvent.Begin(1))
        s = RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Fail(1, "기기에 오프라인 인식이 없어요. 버튼에서 골라 주세요."))
        assertEquals(RelationshipVoicePhase.FAILED, s.phase); assertNull(s.relationship); assertNotNull(s.error)
        s = RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Begin(2))
        s = RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Final(2, "친구와 가족이랑 가요"))
        assertEquals(RelationshipVoicePhase.FAILED, s.phase); assertNotNull(s.error)
    }
    @Test fun cancellationPreventsLaterFinalCallbacks() {
        var s = RelationshipVoiceSession.reduce(RelationshipVoiceState(), RelationshipVoiceEvent.Begin(1))
        s = RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Cancel(1))
        assertEquals(RelationshipVoicePhase.CANCELLED, s.phase)
        assertEquals(s, RelationshipVoiceSession.reduce(s, RelationshipVoiceEvent.Final(1, "가족이랑 가요")))
    }
}
