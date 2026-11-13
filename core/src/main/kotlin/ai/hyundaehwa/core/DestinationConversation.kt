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
        val kind = when {
            Regex("해수욕장|해변|해안|해운대|광안리|바닷가").containsMatchIn(name) -> "beach"
            Regex("한옥|고택").containsMatchIn(name) -> "hanok"
            Regex("박물관|미술관|전시관|기념관|과학관").containsMatchIn(name) -> "museum"
            Regex("등산|국립공원|휴양림|숲길|설악산|지리산|한라산|북한산|남산|관악산|청계산|도봉산|속리산|오대산|팔공산|무등산|치악산").containsMatchIn(name) -> "mountain"
            Regex("공원|수목원|정원|식물원").containsMatchIn(name) -> "park"
            Regex("시장|장터").containsMatchIn(name) -> "market"
            Regex("역|거리|광장|시청|백화점|쇼핑|도심|성수|홍대|명동|대학로").containsMatchIn(name) -> "city"
            else -> "general"
        }
        val together = when (relationship) {
            Relationship.FAMILY -> "가족과"
            Relationship.FRIEND -> "친구들과"
            Relationship.PARTNER -> "연인과"
            Relationship.COLLEAGUE -> "동료와"
            Relationship.BOSS, Relationship.STRANGER -> "함께"
            Relationship.SOLO -> "혼자"
        }
        val choices = DestinationTemplates.choices.getValue(kind)
        val item = choices[sequence.coerceAtLeast(0) % choices.size]
        val options = listOf(
            AdministrativeConversationOption("first", item.first, listOf(item.first) + item.firstKeywords),
            AdministrativeConversationOption("second", item.second, listOf(item.second) + item.secondKeywords))
        val replies = options.associate { it.id to "“${it.label}” 쪽이군요. 도착해서 하고 싶은 일을 편하게 이야기해 보세요." }
        return AdministrativeConversationTopic("destination-${name.hashCode()}-$sequence", "conversation", "목적지에서 할 일",
            "${name}에 도착하면 $together ${item.prompt}", options, replies,
            sourceTitle = "현대화 · 목적지 대화", dataTier = "destination-editorial",
            contentNote = "선택한 목적지에서 가능한 활동을 상상하는 창작 대화예요. 실제 이용 가능 여부·운영 시간·식당·날씨를 확인한 안내는 아니에요.",
            sourceKind = SOURCE, contentVersion = "pleos-audio-layout-v46-20261113", rotationKey = "destination")
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
