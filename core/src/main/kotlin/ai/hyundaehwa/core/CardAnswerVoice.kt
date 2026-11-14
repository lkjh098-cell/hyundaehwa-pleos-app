package ai.hyundaehwa.core

data class SpokenCardAnswer(val choiceId: String?, val gaveUp: Boolean = false)
object SpokenCardAnswerParser {
    private fun normalized(value: String) = value.lowercase().replace(Regex("[^가-힣ㄱ-ㅎㅏ-ㅣa-z0-9]"), "")
    private val ordinals = listOf(listOf("1", "1번", "일번", "첫번째", "첫째", "첫번", "처음거"), listOf("2", "2번", "이번", "두번째", "두번", "둘째"),
        listOf("3", "3번", "삼번", "세번째", "세번", "셋째"), listOf("4", "4번", "사번", "네번째", "넷째"))
    private const val prefix = "(?:(?:저는|나는|난|나|제가|제생각에는|아|음|어|정답은|답은|그건|이건))*"
    private const val ending = "(?:(?:이|가|은|는)?정답(?:이야|이에요|입니다|인것같아|인것같아요)|(?:인|일)?것같(?:아|아요)|같(?:아|아요|은데|은데요)|(?:으로|로|에)?(?:할게|할게요|갈게|갈게요|가요|가겠습니다|선택|선택할게|선택할게요|고를게|고를게요)|(?:이요|요|이에요|예요|이야|야|입니다))?"
    fun parse(text: String, card: AdministrativeConversationTopic): SpokenCardAnswer? {
        if (text.isBlank() || text.length > 120) return null
        val raw = text.trim()
        val quoted = Regex("[\\\"'‘’“”「」『』`]").containsMatchIn(raw)
        if (quoted && card.options.none { it.label.trim() == raw }) return null
        val value = normalized(raw)
        // Entire unique visible labels win before control-like words, negation fragments or numeric aliases.
        val exact = card.options.filter { normalized(it.label) == value }
        if (exact.size == 1) return SpokenCardAnswer(exact.single().id)
        // Match an entire displayed option with a natural spoken answer ending before
        // rejecting report/negation fragments that may occur inside the actual label.
        val spokenLabels = card.options.filter { option ->
            val label = normalized(option.label)
            label.isNotBlank() && Regex("^$prefix${Regex.escape(label)}(?:$ending|(?:이)?라고생각(?:해|해요|합니다)|(?:이|가)?좋(?:아|아요)|(?:을|를)?고르겠습니다)$").matches(value)
        }
        if (spokenLabels.size == 1) return SpokenCardAnswer(spokenLabels.single().id)
        // A complete, unique keyword may be short (for example a spoken confirmation “예”).
        val exactKeywords = card.options.filter { option -> option.keywords.any { normalized(it) == value } }
        if (exactKeywords.size == 1) return SpokenCardAnswer(exactKeywords.single().id)
        // Accept one explicit correction only when both sides name different actual choices.
        // A negation without a new positive choice remains unresolved.
        if ((card.format == "quiz" || card.format == "balance" && card.options.size == 2) && Regex("말고|아니고").containsMatchIn(value)) {
            val parts = value.split(Regex("말고|아니고"))
            if (parts.size != 2) return null
            val before = parse(parts[0], card)?.choiceId ?: return null
            val after = parse(parts[1], card)?.choiceId ?: return null
            return if (before != after) SpokenCardAnswer(after) else null
        }
        if (Regex("아니|말고|않|하지마|라고|라던|말했|들었|말하|말한|답한").containsMatchIn(value)) return null
        val disclosure = Regex("^(?:정답|답)(?:을)?(?:알려(?:줘|주세요)|보여(?:줘|주세요)|보기|보여)$").matches(value)
        val miss = disclosure || Regex("^(?:아|저는?|나는?|이번에는?|이번엔)?(?:잘)?(?:모르겠|몰라|정답(?:을)?알려|정답[은이]뭐).{0,8}$").matches(value)
        if (card.format == "quiz" && miss) return SpokenCardAnswer(null, true)
        val hits = card.options.filterIndexed { index, option ->
            val names = (listOf(option.label) + option.keywords).map(::normalized).filter { it.length >= 2 }.distinct()
            val directional = if (card.options.size == 2) when (index) {
                0 -> listOf("앞에거", "앞에꺼", "앞의거", "앞에것", "앞의것", "앞거", "앞에있는거", "앞쪽", "전자", "첫번째거", "첫번째것")
                else -> listOf("뒤에거", "뒤에꺼", "뒤의거", "뒤에것", "뒤의것", "뒤거", "뒤에있는거", "뒤쪽", "후자", "두번째거", "두번째것")
            } else emptyList()
            val numbers = ordinals.getOrNull(index).orEmpty() + directional
            val forms = names + numbers + numbers.flatMap { n -> names.map { name -> n + name } } + names.flatMap { name -> numbers.map { n -> name + n } }
            forms.any { Regex("^$prefix${Regex.escape(it)}$ending$").matches(value) }
        }
        if (hits.size == 1) return SpokenCardAnswer(hits.single().id)
        // Only an explicit answer declaration may disclose an out-of-list guess.
        // Ordinary chatter, multiple candidates and passenger-addressed speech remain unresolved.
        val guess = Regex("^(?:정답은|답은)([가-힣a-z0-9]{2,30}?)(?:인것같아|인것같아요|같아|같아요|같은데|같은데요|이야|이에요|예요|입니다)$").matchEntire(value)?.groupValues?.get(1)
        if (card.format == "quiz" && hits.isEmpty() && guess != null &&
            !Regex("하고|이랑|또는|혹은|둘다|친구|엄마|아빠|아니|말고").containsMatchIn(guess) &&
            card.options.none { option -> (listOf(option.label) + option.keywords).map(::normalized).any { it.length >= 2 && it in guess } })
            return SpokenCardAnswer(null, true)
        return null
    }
}

data class CardAnswerVoiceConditions(
    val resumed: Boolean, val enabled: Boolean, val cardOpen: Boolean, val gear: Gear,
    val quiet: Boolean, val guards: Set<FocusKind>, val mediaActive: Boolean?,
    val navigationVoiceActive: Boolean, val promptVoiceActive: Boolean,
    val explanationHeld: Boolean, val otherInputOpen: Boolean
)
/** Own-answer input intentionally has no RMS predicate: the requested user's voice is the signal. */
object CardAnswerVoicePolicy {
    fun blockReason(c: CardAnswerVoiceConditions): String? = when {
        !c.resumed -> "앱 화면으로 돌아온 뒤 다시 답해 주세요."
        !c.enabled || !c.cardOpen || c.explanationHeld -> "지금 답을 기다리는 질문이 없어요."
        c.gear !in setOf(Gear.P, Gear.D) -> "차량 상태를 확인한 뒤 음성 답변을 사용할 수 있어요."
        c.quiet -> "조용히 가기에서는 음성 답변을 쉬어요."
        c.guards.isNotEmpty() || c.navigationVoiceActive -> "길 안내가 우선이에요. 안전 구간에서 답변을 들을게요."
        c.mediaActive != false -> "음악이나 라디오 재생 상태가 확인되고 멈추면 답변을 들을게요."
        c.promptVoiceActive -> "현대화의 질문이 끝난 뒤 답변을 들을게요."
        c.otherInputOpen -> "열린 선택 화면을 마친 뒤 답해 주세요."
        else -> null
    }
}
enum class CardAnswerVoicePhase { IDLE, PREPARING, LISTENING, RESOLVED, UNMATCHED, EMPTY_TIMEOUT, FAILED, CANCELLED }
data class CardAnswerVoiceState(val token: Long = 0, val cardId: String = "", val phase: CardAnswerVoicePhase = CardAnswerVoicePhase.IDLE,
    val transcript: String = "", val source: String = "", val answer: SpokenCardAnswer? = null,
    val error: String? = null, val retryWhenSafe: Boolean = false) {
    val active: Boolean get() = phase in setOf(CardAnswerVoicePhase.PREPARING, CardAnswerVoicePhase.LISTENING)
}
sealed interface CardAnswerVoiceEvent {
    data class Begin(val token: Long, val cardId: String) : CardAnswerVoiceEvent
    data class Ready(val token: Long, val source: String) : CardAnswerVoiceEvent
    data class Reconnecting(val token: Long) : CardAnswerVoiceEvent
    data class Partial(val token: Long, val text: String) : CardAnswerVoiceEvent
    data class Final(val token: Long, val text: String, val card: AdministrativeConversationTopic) : CardAnswerVoiceEvent
    data class Timeout(val token: Long) : CardAnswerVoiceEvent
    data class Fail(val token: Long, val reason: String) : CardAnswerVoiceEvent
    data class Cancel(val token: Long, val reason: String, val retryWhenSafe: Boolean = false) : CardAnswerVoiceEvent
}
object CardAnswerVoiceSession {
    fun reduce(s: CardAnswerVoiceState, event: CardAnswerVoiceEvent): CardAnswerVoiceState {
        if (event is CardAnswerVoiceEvent.Begin) return if (event.token > s.token)
            CardAnswerVoiceState(event.token, event.cardId, CardAnswerVoicePhase.PREPARING) else s
        val token = when (event) {
            is CardAnswerVoiceEvent.Reconnecting -> event.token
            is CardAnswerVoiceEvent.Ready -> event.token; is CardAnswerVoiceEvent.Partial -> event.token
            is CardAnswerVoiceEvent.Final -> event.token; is CardAnswerVoiceEvent.Timeout -> event.token
            is CardAnswerVoiceEvent.Fail -> event.token; is CardAnswerVoiceEvent.Cancel -> event.token; else -> return s
        }
        if (token != s.token || !s.active) return s
        return when (event) {
            is CardAnswerVoiceEvent.Reconnecting -> s.copy(phase = CardAnswerVoicePhase.PREPARING)
            is CardAnswerVoiceEvent.Ready -> s.copy(phase = CardAnswerVoicePhase.LISTENING, source = event.source)
            is CardAnswerVoiceEvent.Partial -> s.copy(transcript = event.text.take(120))
            is CardAnswerVoiceEvent.Final -> if (event.card.id != s.cardId) s else {
                val answer = SpokenCardAnswerParser.parse(event.text, event.card)
                s.copy(phase = if (answer == null) CardAnswerVoicePhase.UNMATCHED else CardAnswerVoicePhase.RESOLVED,
                    transcript = event.text.take(120), answer = answer,
                    error = if (answer == null) "답을 확인하지 못했어요. 보기의 번호나 이름으로 다시 말해 주세요." else null)
            }
            is CardAnswerVoiceEvent.Timeout -> if (s.phase == CardAnswerVoicePhase.PREPARING)
                s.copy(phase = CardAnswerVoicePhase.FAILED, error = "마이크 시작을 확인하지 못했어요. 화면에서 답하거나 다시 시도해 주세요.")
            else s.copy(phase = if (s.transcript.isBlank()) CardAnswerVoicePhase.EMPTY_TIMEOUT else CardAnswerVoicePhase.UNMATCHED,
                error = if (s.transcript.isBlank()) null else "말씀을 끝까지 확인하지 못했어요. 다시 답하거나 화면에서 골라 주세요.")
            is CardAnswerVoiceEvent.Fail -> s.copy(phase = CardAnswerVoicePhase.FAILED, error = event.reason)
            is CardAnswerVoiceEvent.Cancel -> s.copy(phase = CardAnswerVoicePhase.CANCELLED, error = event.reason, retryWhenSafe = event.retryWhenSafe)
            else -> s
        }
    }
}

/** Eight seconds of no response; real speech activity earns bounded final-result grace. Wall clock only. */
class CardAnswerVoiceDeadline(private val startedAt: Long) {
    companion object { const val NO_RESPONSE_MS = 8_000L; const val FINAL_GRACE_MS = 4_000L; const val HARD_MAX_MS = 30_000L }
    private var lastActivityAt: Long? = null
    val activitySeen: Boolean get() = lastActivityAt != null
    fun speechStarted(now: Long) { lastActivityAt = now.coerceAtLeast(startedAt) }
    fun partial(text: String, now: Long) { if (text.isNotBlank()) speechStarted(now) }
    fun deadlineAt(): Long = minOf(startedAt + HARD_MAX_MS,
        maxOf(startedAt + NO_RESPONSE_MS, lastActivityAt?.plus(FINAL_GRACE_MS) ?: startedAt))
    fun expiryEvent(token: Long, transcript: String): CardAnswerVoiceEvent = if (activitySeen && transcript.isBlank())
        CardAnswerVoiceEvent.Fail(token, "말씀하신 소리는 감지했지만 답을 인식하지 못했어요. 다시 말하거나 화면에서 골라 주세요.")
        else CardAnswerVoiceEvent.Timeout(token)
    fun remainingMs(now: Long): Long = (deadlineAt() - now).coerceAtLeast(0)
}
