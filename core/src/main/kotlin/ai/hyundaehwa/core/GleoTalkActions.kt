package ai.hyundaehwa.core

data class GleoTalkInput(val functionName: String, val topicType: String? = null, val enabled: Boolean? = null, val malformed: Boolean = false, val answer: String? = null)
data class GleoTalkResult(
    val functionName: String, val status: String, val message: String, val mode: String = "voice",
    val data: Map<String, Any> = emptyMap(), val setQuiet: Boolean? = null, val topic: String? = null, val choiceId: String? = null
)
/** Only an allowlisted local region id crosses into topic selection; raw journey signals stay in the app. */
data class GleoTopicContext(val regionId: String, val administrativeSelection: AdministrativeConversationSelection? = null)
object LocalConversationTopics {
    // Only a location-resolved selection from the versioned bank can become a topic.
    fun topic(context: GleoTopicContext?): String? = context?.administrativeSelection?.topic?.spokenPrompt
}
/** Returns prepared topic content. A selected destination may appear in its question; raw coordinates, route objects and occupants are never returned. */
object GleoTalkActions {
    const val TOPIC = "GET_TALK_TOPIC"
    const val QUIET = "SET_TALK_QUIET"
    const val ANSWER = "ANSWER_TALK_CARD"
    val names = setOf(TOPIC, QUIET, ANSWER)
    fun handle(input: GleoTalkInput, quiet: Boolean, proactiveEnabled: Boolean, parked: Boolean, voiceBlocked: Boolean = false, context: GleoTopicContext? = null, activeCard: AdministrativeConversationTopic? = null): GleoTalkResult? {
        if (input.functionName !in names) return null
        val mode = if (quiet) "silent" else "voice"
        if (input.malformed) return GleoTalkResult(input.functionName, "fail", "The app action parameters are invalid.", mode)
        return when (input.functionName) {
            TOPIC -> {
                val type = input.topicType ?: "everyday"
                val question = LocalConversationTopics.topic(context)
                when {
                    quiet -> GleoTalkResult(TOPIC, "fail", "현대화 conversation prompts are paused.", "silent")
                    voiceBlocked -> GleoTalkResult(TOPIC, "fail", "The conversation starter is deferred while driving focus, navigation or media has priority.", "silent")
                    type !in setOf("everyday", "travel", "preferences", "balance", "quiz", "conversation") -> GleoTalkResult(TOPIC, "asking", "A supported conversation topic type is required.")
                    question == null -> GleoTalkResult(TOPIC, "asking", "현재 위치를 확인하지 못했거나 이 지역의 화두가 아직 준비되지 않았어요. 위치가 확인되면 그곳에 맞는 이야기를 건넬게요.")
                    else -> {
                        val selection = context?.administrativeSelection
                        val metadata: Map<String, Any> = if (selection == null) emptyMap() else mapOf(
                            "format" to selection.topic.format, "rotationKey" to selection.topic.contentRotationKey, "regionLabel" to selection.area.label,
                            "regionUnit" to selection.area.unitType, "sourceTitle" to selection.topic.sourceTitle,
                            "sourceUrl" to selection.topic.sourceUrl, "dataTier" to selection.topic.dataTier,
                            "boundaryVintage" to selection.boundaryVintage,
                            "contentNote" to selection.topic.contentNote, "sourceKind" to selection.topic.sourceKind,
                            "questionBankId" to selection.topic.id, "contentVersion" to selection.topic.contentVersion,
                            "difficulty" to selection.topic.difficulty, "explanation" to selection.topic.explanation,
                            "options" to selection.topic.options.map { mapOf("id" to it.id, "label" to it.label, "keywords" to it.keywords) },
                            "followups" to selection.topic.replyByChoice,
                            "answerPolicy" to "Ask the question first. For a quiz, reveal the supplied correct choice only after the user answers; preferences and conversation cards have no correct answer.",
                            "locationSourceTitle" to selection.boundarySourceTitle,
                            "locationSourceUrl" to selection.boundarySourceUrl
                        )
                        GleoTalkResult(TOPIC, "success", "A regional conversation prompt and its public content sources are available. Do not infer live conditions from these editorial prompts.", data = mapOf("topic" to question, "topicType" to (selection?.topic?.format ?: type)) + metadata + (selection?.topic?.correctChoiceId?.let { mapOf("correctChoiceId" to it) } ?: emptyMap()), topic = question)
                    }
                }
            }
            QUIET -> when {
                input.enabled == null -> GleoTalkResult(QUIET, "asking", "The enabled parameter is required to change 현대화 quiet mode.", mode)
                !input.enabled && !parked -> GleoTalkResult(QUIET, "fail", "현대화 prompts can be resumed after a parked state is confirmed.", mode)
                else -> GleoTalkResult(QUIET, "success", if (input.enabled) "현대화 conversation prompts are paused." else "현대화 conversation prompts may resume when app conditions allow.", if (input.enabled) "silent" else "voice", mapOf("quiet" to input.enabled), setQuiet = input.enabled)
            }
            else -> {
                if ((!proactiveEnabled && activeCard == null) || quiet || voiceBlocked)
                    return GleoTalkResult(ANSWER, "fail", "Answer processing is paused for driving focus.", "silent")
                val card = activeCard ?: return GleoTalkResult(ANSWER, "asking", "There is no open card.", data = mapOf("reply" to "지금 답을 기다리는 질문이 없어요."))
                val utterance = input.answer.orEmpty()
                val parsed = SpokenCardAnswerParser.parse(utterance, card)
                val choice = card.options.firstOrNull { it.id == parsed?.choiceId }
                if (choice == null) {
                    val text = utterance.replace(Regex("\\s+"), "")
                    val redApple = card.id.contains("mungyeong") && text.contains("빨간사과") && card.explanation.contains("감홍")
                    if (card.format == "quiz" && (parsed?.gaveUp == true || redApple)) {
                        val correct = card.options.firstOrNull { it.id == card.correctChoiceId }
                        if (correct != null) return GleoTalkResult(ANSWER, "success", "Give the supplied Korean feedback.",
                            data = mapOf("reply" to AnswerExplanation.text(card, null).orEmpty(), "cardId" to card.id))
                    }
                    return GleoTalkResult(ANSWER, "asking", "Ask for one of the offered choices.", data = mapOf("reply" to "한 가지 보기의 이름이나 번호로 말씀해 주세요."))
                }
                val reply = AnswerExplanation.text(card, choice.id) ?: return GleoTalkResult(ANSWER, "fail", "Feedback is unavailable.")
                GleoTalkResult(ANSWER, "success", "A choice was received. Speak the supplied Korean feedback.",
                    data = mapOf("reply" to reply, "choiceId" to choice.id, "cardId" to card.id), choiceId = choice.id)
            }
        }
    }
}
