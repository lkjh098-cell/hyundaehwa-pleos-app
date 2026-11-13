package ai.hyundaehwa.core
import kotlin.test.*
import org.junit.Test
class RestConfirmationVoiceTest {
    private val card = AdministrativeConversationTopic("active-rest-confirm", "rest", "경유 확인", "휴게소를 경유할까요?",
        listOf(AdministrativeConversationOption("yes", "네", listOf("예", "맞아요", "예 해주세요", "추가해 주세요")),
            AdministrativeConversationOption("no", "아니요", listOf("취소", "안 갈래요"))))
    @Test fun explicitShortConfirmationAndCancellationAreRecognized() {
        listOf("네", "예", "맞아요", "예 해주세요", "추가해 주세요").forEach { assertEquals("yes", SpokenCardAnswerParser.parse(it, card)?.choiceId, it) }
        listOf("아니요", "취소", "안 갈래요").forEach { assertEquals("no", SpokenCardAnswerParser.parse(it, card)?.choiceId, it) }
    }
    @Test fun quotedConflictingOrPassengerDirectedSpeechIsNotConsent() {
        listOf("'예'", "예 아니요", "맞아요 아니고", "민수야 예", "네가 골라", "예라고 말했어").forEach { assertNull(SpokenCardAnswerParser.parse(it, card), it) }
    }
    @Test fun ambiguousShortAliasCannotChooseBetweenOptions() {
        val ambiguous = card.copy(options = card.options.map { it.copy(keywords = it.keywords + "응") })
        assertNull(SpokenCardAnswerParser.parse("응", ambiguous))
    }
}
