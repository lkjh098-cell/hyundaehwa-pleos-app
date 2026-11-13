package ai.hyundaehwa.core

/** Used only while the explicit relationship chooser owns the input. It never returns a destination. */
object RelationshipSpeech {
    private val aliases = mapOf(
        Relationship.FAMILY to listOf("엄마아빠", "엄마랑아빠", "부모님", "가족", "엄마", "아빠", "아이", "애들", "자녀"),
        Relationship.FRIEND to listOf("친구", "동창친구", "동창"),
        Relationship.PARTNER to listOf("남자친구", "여자친구", "연인", "애인", "커플", "남편", "아내", "배우자", "부부"),
        Relationship.COLLEAGUE to listOf("회사동료", "직장동료", "동료", "회사사람", "직장사람"),
        Relationship.BOSS to listOf("부장님", "팀장님", "과장님", "차장님", "사장님", "직장상사", "회사상사", "상사"),
        Relationship.STRANGER to listOf("모르는사람", "처음만난사람", "처음만난분", "처음보는사람", "처음보는분", "처음만난사이", "낯선사람"),
        Relationship.SOLO to listOf("혼자"))
    private const val prefix = "(?:(?:저는|나는|우리는|오늘은|지금은|이번에는|이번엔|우리|나|저|오늘|지금))*"
    private const val directEnding = "(?:예요|에요|이에요|입니다|야|이야)?"
    private const val activity = "(?:(?:지금|현재|같이|함께|둘이|다같이|여행|출장|운전|드라이브))*"
    private const val travelEnding = "(?:가|가요|가고있어|가고있어요|가고있습니다|가고있다|가고있는중이야|가고있는중이에요|이동중이야|이동중이에요|이동하고있어|이동하고있어요|여행중이야|여행중이에요|왔어|왔어요|왔습니다|탔어|탔어요|탔습니다|타고있어|타고있어요|타고있습니다|가는중이야|가는중이에요|가려고해|가려고해요|갈거야|갈거예요|갈게|갈게요|와있어|와있어요|하고있어|하고있어요|하고있습니다|중이야|중이에요|동승했어|동승했어요|타고가요)?"
    fun parse(utterance: String): Relationship? {
        if (utterance.isBlank() || utterance.length > 120) return null
        val value = utterance.lowercase().replace(Regex("[^가-힣a-z0-9]"), "")
        if (Regex("아니|말고|없어|없는|안타|못타|않|라고|라던|라는|말했|말하|들었|친구가|만나러|데리러").containsMatchIn(value)) return null
        val hits = aliases.filter { (relationship, names) ->
            val named = names.sortedByDescending { it.length }.any { name ->
                val alias = Regex.escape(name)
                Regex("^$prefix$alias$directEnding$").matches(value) ||
                    Regex("^$prefix$alias(?:들)?(?:이랑|랑|하고|와|과)(?:은|는)?$activity$travelEnding$").matches(value)
            }
            named || (relationship == Relationship.STRANGER && Regex("^(?:오늘|오늘은|지금|방금|이번에)?처음만났(?:어|어요|습니다|다)$").matches(value)) ||
                (relationship == Relationship.SOLO && Regex("^${prefix}혼자$activity$travelEnding$").matches(value))
        }.keys
        return hits.singleOrNull()
    }
}
enum class RelationshipVoicePhase { IDLE, PREPARING, LISTENING, RESOLVED, FAILED, CANCELLED }
data class RelationshipVoiceState(val token: Long = 0, val phase: RelationshipVoicePhase = RelationshipVoicePhase.IDLE,
    val transcript: String = "", val source: String = "", val relationship: Relationship? = null, val error: String? = null) {
    val active: Boolean get() = phase == RelationshipVoicePhase.PREPARING || phase == RelationshipVoicePhase.LISTENING
}
sealed interface RelationshipVoiceEvent {
    data class Begin(val token: Long) : RelationshipVoiceEvent
    data class Ready(val token: Long, val source: String) : RelationshipVoiceEvent
    data class Partial(val token: Long, val text: String) : RelationshipVoiceEvent
    data class Final(val token: Long, val text: String) : RelationshipVoiceEvent
    data class Fail(val token: Long, val reason: String) : RelationshipVoiceEvent
    data class Cancel(val token: Long) : RelationshipVoiceEvent
}
object RelationshipVoiceSession {
    fun reduce(state: RelationshipVoiceState, event: RelationshipVoiceEvent): RelationshipVoiceState {
        if (event is RelationshipVoiceEvent.Begin) return if (event.token > state.token)
            RelationshipVoiceState(event.token, RelationshipVoicePhase.PREPARING) else state
        val token = when (event) {
            is RelationshipVoiceEvent.Ready -> event.token; is RelationshipVoiceEvent.Partial -> event.token
            is RelationshipVoiceEvent.Final -> event.token; is RelationshipVoiceEvent.Fail -> event.token
            is RelationshipVoiceEvent.Cancel -> event.token; else -> return state
        }
        if (token != state.token || !state.active) return state
        return when (event) {
            is RelationshipVoiceEvent.Ready -> state.copy(phase = RelationshipVoicePhase.LISTENING, source = event.source)
            is RelationshipVoiceEvent.Partial -> state.copy(transcript = event.text.take(120))
            is RelationshipVoiceEvent.Final -> {
                val relation = RelationshipSpeech.parse(event.text)
                state.copy(phase = if (relation == null) RelationshipVoicePhase.FAILED else RelationshipVoicePhase.RESOLVED,
                    transcript = event.text.take(120), relationship = relation,
                    error = if (relation == null) "한 가지 동승자 관계로 다시 말하거나 아래 버튼에서 골라 주세요." else null)
            }
            is RelationshipVoiceEvent.Fail -> state.copy(phase = RelationshipVoicePhase.FAILED, error = event.reason)
            is RelationshipVoiceEvent.Cancel -> state.copy(phase = RelationshipVoicePhase.CANCELLED, transcript = "")
            else -> state
        }
    }
}
