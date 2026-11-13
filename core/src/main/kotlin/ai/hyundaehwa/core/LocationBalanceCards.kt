package ai.hyundaehwa.core

/** Editorial preferences use a verified local anchor; they assert no live access, business or weather facts. */
object LocationBalanceCards {
    const val VERSION = "2026.11.07.editorial.1"
    fun isLocal(card: AdministrativeConversationTopic, area: AdministrativeConversationArea, anchor: String): Boolean {
        val province = area.province.replace(Regex("(?:특별자치도|특별자치시|특별시|광역시|도)$"), "")
        val names = listOf(anchor, area.district, area.city, province).filter { it.length >= 2 }
        return names.any { card.question.contains(it) }
    }
    fun create(area: AdministrativeConversationArea, audience: String, difficulty: Int): AdministrativeConversationTopic? {
        val fact = area.topics.firstOrNull { it.format == "quiz" && it.sourceKind == "official-place-fact" &&
            it.difficulty == difficulty && audience in it.audiences && it.sourceUrl.startsWith("https://") } ?: return null
        val anchor = fact.placeName.ifBlank { fact.title.removeSuffix(" 이야기").trim() }
        if (anchor.isBlank()) return null
        val choices = listOf(AdministrativeConversationOption("story", "${anchor}의 유래 듣기", listOf("유래", "옛이야기")),
            AdministrativeConversationOption("look", "${anchor}의 모습 찾아보기", listOf("모습", "사진")))
        return AdministrativeConversationTopic("local-balance-${area.code}-l$difficulty", "balance", "${area.label} 밸런스 게임",
            "${area.label}에서 ${anchor} 이야기가 나왔네요. 이 지역을 더 알아본다면 둘 중 어느 쪽으로 가볼까요?",
            choices, mapOf("story" to "유래를 듣는 쪽을 골랐네요. 어떤 이야기가 가장 궁금한지 서로 하나씩 골라 봐요.",
                "look" to "모습을 찾아보는 쪽을 골랐네요. 어떤 장면을 함께 보고 싶은지 이야기해 봐요."),
            sourceTitle = fact.sourceTitle, sourceUrl = fact.sourceUrl, dataTier = "regional-editorial",
            contentNote = "지역 자료를 바탕으로 만든 취향 대화예요.",
            sourceKind = "editorial-local-preference", audiences = setOf("family", "adult"), difficulty = difficulty,
            contentVersion = VERSION, specificity = fact.specificity, placeName = anchor)
    }
}
object RelationshipContent {
    fun formats(@Suppress("UNUSED_PARAMETER") relationship: Relationship): List<TopicType> =
        listOf(TopicType.QUIZ, TopicType.BALANCE)
}
