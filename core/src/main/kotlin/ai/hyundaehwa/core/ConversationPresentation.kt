package ai.hyundaehwa.core

/** Display density only: this policy never changes a route, sensing state, or pending card. */
data class CompactConversationStatus(val label: String, val detail: String)

object ConversationPresentation {
    /** Seat occupancy does not imply a relationship; only an explicit selection gets a label. */
    fun passengerLabel(relationship: Relationship?): String = when (relationship) {
        null -> "동승자 선택 전"
        Relationship.SOLO -> "혼자 운전"
        else -> "동승자 · ${relationship.label}"
    }

    fun compact(
        state: ConversationState,
        parkedConfirmed: Boolean,
        guidancePending: Boolean = false
    ): CompactConversationStatus? {
        // Unknown parking is not parking. A pending question must never cover a driving focus state.
        if (!parkedConfirmed && state.guards.isNotEmpty()) return if (guidancePending)
            CompactConversationStatus("길 안내 확인 중", "안내 구간을 확인한 뒤 이야기를 이어갈게요")
        else CompactConversationStatus("길 안내를 우선할게요", "지금은 운전에 집중해 주세요")
        if (parkedConfirmed) return null
        val status = HyundaehwaEngine.view(state).status
        return when (status) {
            "disabled" -> CompactConversationStatus("현대화가 쉬고 있어요", "정차 후 화두 안내를 켤 수 있어요")
            "quiet" -> CompactConversationStatus("조용히 함께 갈게요", "이야기를 나누고 싶을 때 다시 켜 주세요")
            "media-hold" -> CompactConversationStatus("미디어 재생 중", "소리를 듣는 동안 화두는 잠시 쉴게요")
            "media-unknown" -> CompactConversationStatus("잠시 기다릴게요", "재생 중인 소리가 있는지 확인하고 있어요")
            "waiting" -> CompactConversationStatus("함께 가고 있어요", "지역이 바뀌면 이곳의 이야기를 건넬게요")
            else -> null
        }
    }
}
