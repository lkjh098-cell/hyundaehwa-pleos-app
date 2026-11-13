package ai.hyundaehwa.core

/** Use the same region's sourced local preference and its authored choices as the v46 demonstration. */
object LocationStoryCards {
    fun create(area: AdministrativeConversationArea, audience: String, difficulty: Int): AdministrativeConversationTopic? {
        val anchor = area.topics.firstOrNull { it.format == "quiz" && it.sourceKind == "official-place-fact" &&
            it.difficulty == difficulty && audience in it.audiences && it.sourceUrl.startsWith("https://") } ?: return null
        val preference = area.topics.firstOrNull { it.format == "balance" && it.sourceKind == LocalContentEligibility.BALANCE_SOURCE &&
            audience in it.audiences && it.placeName.isNotBlank() && it.sourceTitle.isNotBlank() && it.sourceUrl.startsWith("https://") &&
            it.factualIntro.isNotBlank() && it.question.isNotBlank() && it.options.size == 2 &&
            it.options.all { option -> option.id.isNotBlank() && option.label.isNotBlank() && it.replyByChoice[option.id].orEmpty().isNotBlank() } &&
            it.options.map { option -> option.id }.toSet().size == 2 && it.options.map { option -> option.label }.toSet().size == 2 } ?: return null
        val body = preference.question.replace(Regex("^(?:\\s*(?:정답이 없는 질문이에요[~!！.。]*|밸런스\\s*게임[~!！.。]*))+"), "").trim()
        if (body.isBlank()) return null
        val question = if (body.contains(preference.factualIntro)) body else "${preference.factualIntro} $body"
        return preference.copy(id = "story-${preference.id}", format = "conversation", title = "${preference.placeName} 이야기",
            question = question, correctChoiceId = null, dataTier = "regional-editorial", sourceKind = "editorial-place-story",
            contentNote = "확인된 지역 장소 설명에 구체적인 생활 선택을 묻는 창작 질문을 덧붙였어요. 활동은 가정이며 현장 이용 가능 여부를 안내하지 않아요.",
            explanation = preference.factualIntro, difficulty = anchor.difficulty, rotationKey = "story")
    }
}
