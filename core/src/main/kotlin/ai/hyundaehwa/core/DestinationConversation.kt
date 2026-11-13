package ai.hyundaehwa.core

/** Selected destination text is a conversation subject, never a substitute for current GPS. */
object DestinationConversation {
    const val SOURCE = "editorial-destination-preference"
    /** Silence-triggered destination prompts were removed; destination cards remain in regional rotation. */
    @Suppress("UNUSED_PARAMETER")
    fun requested(state: ConversationState): Boolean = false
    fun card(destination: String, relationship: Relationship, sequence: Int): AdministrativeConversationTopic? {
        val name = destination.trim().take(160)
        if (name.isBlank()) return null
        val pair = when (relationship) {
            Relationship.FAMILY -> "함께 먹을 간식부터 정하기" to "가족이 함께 둘러볼 계획 세우기"
            Relationship.FRIEND -> "첫 끼 메뉴부터 함께 정하기" to "함께 남길 사진부터 계획하기"
            Relationship.PARTNER -> "둘이 먹고 싶은 메뉴 정하기" to "천천히 함께 걸을 계획 세우기"
            Relationship.COLLEAGUE, Relationship.BOSS -> "도착 후 일정부터 확인하기" to "잠깐 쉴 시간부터 정하기"
            Relationship.STRANGER -> "도착하면 할 일 먼저 정하기" to "편하게 쉬는 계획 이야기하기"
            Relationship.SOLO -> "가장 하고 싶은 한 가지 정하기" to "잠깐 쉬어 갈 계획 세우기"
        }
        val question = when (sequence.mod(3)) {
            0 -> "목적지인 ${name}에 도착하면 무엇부터 해볼까요?"
            1 -> "${name}에서 보낼 시간을 미리 골라 볼까요?"
            else -> "${name}에 도착한 뒤 가장 먼저 하고 싶은 일을 정해 볼까요?"
        }
        return AdministrativeConversationTopic("destination-${name.hashCode()}-$sequence", "conversation", "목적지 이야기", question,
            listOf(AdministrativeConversationOption("first", pair.first), AdministrativeConversationOption("second", pair.second)),
            mapOf("first" to "${pair.first} 쪽을 골랐네요. 구체적으로 어떤 계획이면 좋을지 이야기해 봐요.",
                "second" to "${pair.second} 쪽을 골랐네요. 도착해서 하고 싶은 일을 하나 더 떠올려 봐요."),
            sourceTitle = "직접 설정한 목적지 · 대화 제안", dataTier = "destination-editorial",
            contentNote = "직접 정한 목적지로 만드는 대화 제안이며 실시간 영업·날씨 정보가 아니에요.",
            sourceKind = SOURCE, contentVersion = "2026.11.07.editorial.1", rotationKey = "destination")
    }
    fun rotationCard(destination: String, relationship: Relationship, sequence: Int, areaId: String): AdministrativeConversationTopic? =
        card(destination, relationship, sequence)?.let { it.copy(id = "${it.id}-region-$areaId") }
    fun activeCard(state: ConversationState): AdministrativeConversationTopic? {
        val sequence = (state.destinationPromptCount - 1).coerceAtLeast(0)
        return listOfNotNull(card(state.destination, state.relationship, sequence),
            rotationCard(state.destination, state.relationship, sequence, state.regionId)).firstOrNull { it.id == state.topic?.id }
    }
    fun topic(state: ConversationState): Topic? = card(state.destination, state.relationship, state.destinationPromptCount)?.let {
        it.engineTopic(state.regionId)
    }
}
