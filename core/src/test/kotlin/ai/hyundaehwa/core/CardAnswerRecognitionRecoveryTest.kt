package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class CardAnswerRecognitionRecoveryTest {
    private fun card(vararg labels: String) = AdministrativeConversationTopic(
        "recognition-recovery", "quiz", "지역 이야기", "어느 쪽일까요?",
        labels.mapIndexed { index, label -> AdministrativeConversationOption("choice-$index", label) },
        emptyMap(), correctChoiceId = "choice-0", explanation = "설명")

    private val walnut = card("팥앙금", "녹두앙금", "밤앙금")
    private val teaching = card("일제 휴교령으로 학교가 닫혀서", "독립선언서 작성자로 공식 임명되어서", "학당을 졸업하고 교사로 부임해서")

    @Test fun explicitDisplayedNumberSurvivesOneNoisyNounWithoutUsingCorrectAnswer() {
        for (heard in listOf("3번 사망금", "삼 번 사망금이요", "세 번째 사망금입니다", "정답은 3번 사망금"))
            assertEquals("choice-2", SpokenCardAnswerParser.parse(heard, walnut)?.choiceId, heard)
        assertEquals("choice-1", SpokenCardAnswerParser.parse("2번 사망금", walnut)?.choiceId)
        // This rule works for another card and a different answer position.
        assertEquals("choice-0", SpokenCardAnswerParser.parse("1번 장이석", card("대리석", "벽돌", "통나무"))?.choiceId)
    }

    @Test fun noisyNumberedTailCannotOverrideContradictionsOrBecomeChitchat() {
        for (heard in listOf(
            "3번 팥앙금", "3번 녹두앙금", "3번 사망금 2번", "3번 사망금 말고 2번", "3번 사망금 아니고 팥앙금",
            "3번 앙금", "3번 앙금이요",
            "3번 사망금 아니야", "3번 사망금은 아닌 것 같아", "3번 사망금 안 할래", "3번 사망금 싫어",
            "3번 사망금?", "3번 아닌가?", "3번이라고 했어?", "3번 사망금이라고 들었어",
            "3번 버스를 탔어요", "3번버스를탔어요", "3번 학교에 갔어요", "3번 뭐였지", "3번 사망금 알려줘",
            "엄마 3번 사망금", "민수야 3번 사망금", "3번 사망금 또는 팥앙금", "3번 사망금 그리고 녹두앙금"))
            assertNull(SpokenCardAnswerParser.parse(heard, walnut), heard)
    }

    @Test fun intactUniqueTerminalPhraseAndClauseConnectorCanRecoverDamagedPrefix() {
        assertEquals("choice-2", SpokenCardAnswerParser.parse("학다각하고 교사로 부임해서", teaching)?.choiceId)
        assertEquals("choice-2", SpokenCardAnswerParser.parse("학다각하고 교사로 부임해서요", teaching)?.choiceId)
        assertEquals("choice-2", SpokenCardAnswerParser.parse("3번 학다각하고 교사로 부임해서", teaching)?.choiceId)
        assertNull(SpokenCardAnswerParser.parse("1번 학다각하고 교사로 부임해서", teaching))
        // A different noun, predicate, connector and answer position use the same rule.
        val other = card("짐을 챙기고 바다로 이동하기", "숲에서 쉬기", "식당에서 먹기")
        assertEquals("choice-0", SpokenCardAnswerParser.parse("지믈개기고 바다로 이동하기", other)?.choiceId)
        // Losing the final predicate supplies insufficient evidence.
        assertNull(SpokenCardAnswerParser.parse("학당을 졸업하고 그 부인에서", teaching))
    }

    @Test fun terminalPhraseRecoveryRejectsNegationReportsCompetingChoicesAndSharedSuffixes() {
        for (heard in listOf(
            "학다각하고 교사로 부임해서 아니야", "학다각하고 교사로 부임해서는 아닌 것 같아",
            "학다각하고 교사로 부임해서라고 들었어", "학다각하고 교사로 부임해서?",
            "엄마 학다각하고 교사로 부임해서", "민수야 학다각하고 교사로 부임해서",
            "친구가 학다각하고 교사로 부임해서",
            "일제 휴교령으로 학교가 닫혀서 학다각하고 교사로 부임해서",
            "독립선언서 작성자로 학다각하고 교사로 부임해서", "내일 교사로 부임해서"))
            assertNull(SpokenCardAnswerParser.parse(heard, teaching), heard)
        val shared = card("학당을 졸업하고 교사로 부임해서", "시험에 합격하고 교사로 부임해서", "학교가 닫혀서")
        assertNull(SpokenCardAnswerParser.parse("학다각하고 교사로 부임해서", shared))
        val generic = card("꽃을 구경하고 함께 즐기기", "밥을 먹고 따로 쉬기", "사진을 찍고 돌아가기")
        assertNull(SpokenCardAnswerParser.parse("아무개하고 함께 즐기기", generic))
    }
}
