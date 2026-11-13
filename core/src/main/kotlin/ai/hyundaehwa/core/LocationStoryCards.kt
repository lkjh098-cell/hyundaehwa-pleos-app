package ai.hyundaehwa.core

/** Factual introduction is copied from a verified place explanation; choices are editorial preferences. */
object LocationStoryCards {
    fun create(area: AdministrativeConversationArea, audience: String, difficulty: Int): AdministrativeConversationTopic? {
        val fact = area.topics.firstOrNull { it.format == "quiz" && it.sourceKind == "official-place-fact" &&
            it.difficulty == difficulty && audience in it.audiences && it.explanation.isNotBlank() && it.sourceUrl.startsWith("https://") } ?: return null
        val anchor = fact.placeName.ifBlank { fact.title.removeSuffix(" 이야기").trim() }
        if (anchor.isBlank()) return null
        return AdministrativeConversationTopic("local-story-${area.code}-l$difficulty", "conversation", "${area.label} 이야기",
            "${area.label} 부근의 ${anchor} 이야기예요. ${fact.explanation} 이 이야기에 이어서 무엇을 나눠 볼까요?",
            listOf(AdministrativeConversationOption("memory", "이곳에 얽힌 기억 나누기", listOf("기억", "추억")),
                AdministrativeConversationOption("next", "다음에 궁금한 모습 고르기", listOf("다음", "궁금한"))),
            mapOf("memory" to "떠오르는 기억이 있다면 편하게 들려주세요.", "next" to "다음에 알아보고 싶은 모습을 하나씩 골라 봐요."),
            sourceTitle = fact.sourceTitle, sourceUrl = fact.sourceUrl, dataTier = "regional-editorial",
            contentNote = "지역 자료의 사실 설명에 대화 선택지를 더했어요.", sourceKind = "editorial-local-story",
            audiences = setOf("family", "adult"), difficulty = difficulty, contentVersion = "2026.11.07.editorial.2",
            explanation = fact.explanation, specificity = fact.specificity, placeName = anchor, rotationKey = "story")
    }
}
