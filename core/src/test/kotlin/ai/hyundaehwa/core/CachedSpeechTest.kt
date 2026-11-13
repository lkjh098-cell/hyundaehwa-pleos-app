package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class CachedSpeechTest {
    private fun clip(text: String, id: Int) = CachedSpeechEntry(text, "$id.ogg", 1.5, "a".repeat(64), 4096)
    @Test fun exactOrderedCoveragePreservesEverySentenceAndResumeOffset() {
        val clips = listOf(clip("맞아요.", 1), clip("정답은 2번입니다.", 2), clip("황포묵.", 3), clip("황포묵은 녹두로 만듭니다.", 4))
        val text = clips.joinToString(" ") { it.text }
        val plan = assertNotNull(CachedSpeechMatcher(clips).match(text))
        assertEquals(clips, plan.segments.map { it.entry })
        assertEquals(text, plan.remainingText(0))
        assertEquals("황포묵. 황포묵은 녹두로 만듭니다.", plan.remainingText(2))
        assertEquals("", plan.remainingText(4))
    }
    @Test fun missingMiddleEndChangedNumberPlaceOrPunctuationCannotPlayAnotherText() {
        val matcher = CachedSpeechMatcher(listOf(clip("서울에 진입했습니다.", 1), clip("1번.", 2), clip("황포묵.", 3)))
        assertNotNull(matcher.match("서울에 진입했습니다."))
        for (text in listOf("부산에 진입했습니다.", "서울에 진입했어요.", "서울에 진입했습니다!", "2번. 황포묵.", "1번. 없는 문장. 황포묵.", "1번. 황포묵. 없는 끝문장."))
            assertNull(matcher.match(text), text)
        assertNull(matcher.match(""))
    }
    @Test fun whitespaceOnlyMayVaryAndClipsCannotMatchInsideWords() {
        val matcher = CachedSpeechMatcher(listOf(clip("황포", 1), clip("묵.", 2), clip("1번.", 3)))
        assertNull(matcher.match("황포묵."))
        assertNotNull(matcher.match(" 1번.\n\n 묵. "))
    }
    @Test fun conflictingRecordingsAndUnsafeManifestEntriesAreRejected() {
        val one = clip("같은 문장.", 1)
        val two = clip("같은 문장.", 2)
        assertNull(CachedSpeechMatcher(listOf(one, two, one)).match(one.text))
        val unsafe = listOf(one.copy(file = "../1.ogg"), one.copy(sha256 = "bad"), one.copy(bytes = 0), one.copy(durationSeconds = Double.NaN))
        unsafe.forEach { assertNull(CachedSpeechMatcher(listOf(it)).match(it.text)) }
    }
    @Test fun existingLabelPunctuationIsPreservedWithoutAddingAnotherPeriod() {
        assertEquals("1번. 산책? 2번. 사진! 3번. 쉼.", SpokenChoiceCues.options(listOf(
            AdministrativeConversationOption("a", "산책?"), AdministrativeConversationOption("b", "사진!"), AdministrativeConversationOption("c", "쉼"))))
    }
    @Test fun canonicalQuizAndFullFeedbackShareNumberAndLabelClipsAcrossShuffles() {
        val card = AdministrativeConversationTopic("q", "quiz", "제목", "어떤 묵일까요?", listOf(
            AdministrativeConversationOption("a", "황포묵"), AdministrativeConversationOption("b", "도토리묵")),
            correctChoiceId = "a", explanation = "황포묵은 녹두로 만듭니다.")
        val texts = listOf("어떤 묵일까요?", "1번.", "2번.", "황포묵.", "도토리묵.", "정답은 1번입니다.", "정답은 2번입니다.", "맞아요.", "아쉽네요.", card.explanation)
        val matcher = CachedSpeechMatcher(texts.mapIndexed { index, text -> clip(text, index) })
        for (question in listOf(card, card.copy(options = card.options.reversed()))) {
            assertNotNull(matcher.match(question.spokenPrompt))
            for (choice in listOf("a", "b", null)) assertNotNull(matcher.match(AnswerExplanation.text(question, choice)!!))
            assertNotNull(matcher.match(AnswerExplanation.text(question, null, true)!!))
        }
    }
}
