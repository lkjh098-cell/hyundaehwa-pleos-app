package ai.hyundaehwa.core

import kotlin.random.Random

/** No Android, Pleos, recording, network or TTS dependency: input is supplied by adapters. */
object ConversationLimits {
    const val REGION_STABLE_MS = 3_000L
    const val SILENCE_MS = 300_000L
    const val TOPIC_GAP_MS = 600_000L
    const val REST_MS = 3_600_000L
    const val LOW_RMS = 0.025
    const val REARM_QUIET_MS = 3_000L
    const val ACTIVITY_RMS = 0.04
    const val SOUND_FRESH_MS = 2_000L
}
enum class Gear { UNKNOWN, P, D, N, R }
enum class FocusKind { MERGE, BRANCH, TUNNEL, JUNCTION }
enum class TopicType(val label: String) { WEATHER("날씨 이야기"), QUIZ("지역 퀴즈"), BALANCE("밸런스 게임"), TRIVIA("지역 상식"), QUESTION("목적지 이야기") }
enum class Relationship(val label: String, val types: List<TopicType>) {
    FAMILY("가족", listOf(TopicType.QUIZ, TopicType.TRIVIA, TopicType.QUESTION, TopicType.BALANCE, TopicType.WEATHER)),
    FRIEND("친구", listOf(TopicType.BALANCE, TopicType.QUIZ, TopicType.QUESTION, TopicType.TRIVIA, TopicType.WEATHER)),
    PARTNER("연인", listOf(TopicType.QUESTION, TopicType.BALANCE, TopicType.WEATHER, TopicType.QUIZ, TopicType.TRIVIA)),
    COLLEAGUE("동료", listOf(TopicType.QUIZ, TopicType.QUESTION, TopicType.WEATHER, TopicType.TRIVIA, TopicType.BALANCE)),
    BOSS("상사", listOf(TopicType.WEATHER, TopicType.QUIZ)),
    STRANGER("처음 만난 사이", listOf(TopicType.WEATHER, TopicType.QUIZ)),
    SOLO("혼자", listOf(TopicType.TRIVIA, TopicType.QUIZ, TopicType.BALANCE))
}
enum class Scenario(val label: String, val destination: String, val relationship: Relationship, val frontOccupied: Boolean, val rearCount: Int, val regionId: String, val regionLabel: String, val firstType: TopicType) {
    COLLEAGUE("업무 동행 · 서울 → 포항", "포항 철강단지", Relationship.COLLEAGUE, true, 0, "kr-44130", "충청남도 천안시", TopicType.QUIZ),
    FAMILY("가족 · 서울 → 전주", "전주 한옥마을", Relationship.FAMILY, true, 2, "kr-44230", "충청남도 논산시", TopicType.TRIVIA),
    FRIEND("친구 · 서울 → 부산", "부산 해운대", Relationship.FRIEND, true, 1, "kr-30230", "대전광역시 대덕구", TopicType.BALANCE),
    SOLO("혼자 · 서울 상암 → 서울대", "서울대학교 관악캠퍼스", Relationship.SOLO, false, 0, "kr-11440", "서울특별시 마포구", TopicType.QUIZ)
}
data class Topic(val id: String, val type: TopicType, val regionId: String, val text: String, val answer: String? = null, val note: String? = null, val source: String = "curated-example", val difficulty: Int? = null, val contentVersion: String = "", val rotationKey: String = "") {
    val label: String get() = type.label
}
data class Utterance(val id: String, val kind: String, val text: String, val topicId: String? = null)
data class RestStop(val id: String, val label: String, val detail: String, val source: String = "simulation")
object ConversationContent {
    val restStops = listOf(RestStop("rest-a", "휴식 후보 A", "12km · 시연 예시"), RestStop("rest-b", "휴식 후보 B", "24km · 시연 예시"))

}
data class ConversationState(
    val scenario: Scenario = Scenario.COLLEAGUE, val enabled: Boolean = false,
    val gear: Gear = Gear.UNKNOWN, val speedKph: Double = 0.0,
    // Actual mode starts unknown. Only VehicleEvidenceChanged can authorize parked interaction.
    val demoVehicle: Boolean = false, val parkedEvidence: ParkedEvidence? = null,
    val relationship: Relationship = scenario.relationship,
    val frontOccupied: Boolean = false, val rearCount: Int = 0,
    val regionId: String = "", val regionLabel: String = "위치 미확인", val destination: String = "",
    val guards: Set<FocusKind> = emptySet(), val quiet: Boolean = false,
    val mediaActive: Boolean? = null, val speechActive: Boolean = false, val answerListening: Boolean = false, val rms: Double? = null, val quietAudioMs: Long = 0,
    val wallElapsedMs: Long = 0, val simElapsedMs: Long = 0, val silenceMs: Long = 0, val drivingMs: Long = 0,
    val silenceThresholdMs: Long = ConversationLimits.SILENCE_MS, val soundObservedAtMs: Long? = null,
    val pendingWeather: String? = null, val weatherPresented: Boolean = false,
    val lastTopicAtMs: Long? = null, val usedTopicIds: Set<String> = emptySet(),
    val lastTopicId: String? = null, val topicFormatCursor: Int = 0,
    val rotationRemaining: List<String> = FourContentRotation.newBag(), val lastRotationKey: String? = null,
    val rotationCommittedTopicId: String? = null,
    val presentedRegionIds: Set<String> = emptySet(),
    val regionEntryHeld: Boolean = false, // Set by the local entry narrator; never inferred from an external Gleo callback.
    val regionalTopics: List<Topic> = emptyList(), val regionalTopicsRegionId: String = "",
    val destinationSilenceMs: Long = 0, val destinationPromptArmed: Boolean = true, val destinationPromptPending: Boolean = false,
    val destinationPromptCount: Int = 0, val lastDestinationPromptAtMs: Long? = null,
    val pendingRegionId: String = "", val regionStableMs: Long = 0, val lastAnnouncedRegionId: String = "",
    val topic: Topic? = null, val output: Utterance? = null, val outputSequence: Long = 0,
    val arrived: Boolean = false, val arrivalAnnounced: Boolean = false, val answerExplanationHeld: Boolean = false,
    val upcomingRestNames: List<String> = emptyList(),
    val restDue: Boolean = false, val restRequested: Boolean = false, val restAnnounced: Boolean = false,
    val restSelection: RestStop? = null, val restCommitted: Boolean = false, val notice: String = "",
    val seatSource: String = "unavailable", val soundSource: String = "unavailable", val navigationSource: String = "unavailable"
)
sealed interface ConversationEvent {
    data class ArrivalChanged(val arrived: Boolean) : ConversationEvent
    data class WeatherReady(val text: String) : ConversationEvent
    data class AnswerSpoken(val text: String) : ConversationEvent
    data object ReleaseTopic : ConversationEvent
    data object UserResponded : ConversationEvent
    data class AnswerExplanationHold(val active: Boolean) : ConversationEvent
    data class AnswerListeningChanged(val active: Boolean) : ConversationEvent
    data object Start : ConversationEvent
    data object Stop : ConversationEvent
    data class Toggle(val enabled: Boolean) : ConversationEvent
    data class Tick(val dtMs: Long, val wallDtMs: Long = dtMs) : ConversationEvent
    /** Simulation control only; never establishes actual-vehicle evidence. */
    data class GearChanged(val gear: Gear, val speedKph: Double = 0.0) : ConversationEvent
    data class VehicleEvidenceChanged(val evidence: ParkedEvidence) : ConversationEvent
    data class SeatsChanged(val frontOccupied: Boolean, val rearCount: Int, val source: String = "simulation") : ConversationEvent
    data class RelationshipChanged(val relationship: Relationship) : ConversationEvent
    data object PreferencesChanged : ConversationEvent
    data class SoundLevel(val rms: Double, val source: String = "android-rms") : ConversationEvent
    data class SilenceThresholdChanged(val ms: Long) : ConversationEvent
    data class SoundUnavailable(val reason: String = "소리 신호 없음") : ConversationEvent
    data class MediaChanged(val active: Boolean?) : ConversationEvent
    data class FocusChanged(val kind: FocusKind, val active: Boolean) : ConversationEvent
    data class QuietChanged(val active: Boolean) : ConversationEvent
    data class SpeechChanged(val active: Boolean) : ConversationEvent
    data class OutputDone(val id: String? = null) : ConversationEvent
    data class RegionalTopicsChanged(val regionId: String, val topics: List<Topic>) : ConversationEvent
    data class RegionChanged(val regionId: String, val label: String, val destination: String, val source: String = "simulation") : ConversationEvent
    data class ExternalTopicPresented(val regionId: String, val topicId: String) : ConversationEvent
    data class LocalTopicDisplayed(val topicId: String) : ConversationEvent
    data class RestOptionsChanged(val names: List<String>) : ConversationEvent
    data class RestSelected(val id: String) : ConversationEvent
    data object RestCommitted : ConversationEvent
    data object RestCancelled : ConversationEvent
    data object RestConfirmed : ConversationEvent
    data class ScenarioChanged(val scenario: Scenario) : ConversationEvent
}
data class ConversationView(val state: ConversationState, val status: String, val statusTitle: String, val statusDetail: String, val passengerCount: Int, val canChangeRelationship: Boolean, val canSpeak: Boolean, val nextTopicInMs: Long, val restInMs: Long)

object HyundaehwaEngine {
    /** Real mode starts with unknown seat/audio/location signals. Test scenarios must be explicitly requested. */
    fun initial(scenario: Scenario = Scenario.COLLEAGUE, random: Random = Random.Default): ConversationState =
        ConversationState(scenario = scenario, rotationRemaining = FourContentRotation.newBag(random))
    fun simulation(scenario: Scenario = Scenario.COLLEAGUE, random: Random = Random.Default): ConversationState = initial(scenario, random).copy(
        demoVehicle = true, gear = Gear.P, frontOccupied = scenario.frontOccupied, rearCount = scenario.rearCount, regionId = scenario.regionId,
        regionLabel = scenario.regionLabel, destination = scenario.destination, rms = 0.01, mediaActive = false,
        pendingRegionId = scenario.regionId, quietAudioMs = 0, soundObservedAtMs = 0, seatSource = "simulation", soundSource = "simulation", navigationSource = "simulation"
    )
    /** Re-evaluate evidence against elapsedRealtime immediately before UI actions. */
    fun canConfigure(s: ConversationState, nowElapsedMs: Long? = null): Boolean =
        if (s.demoVehicle) s.gear == Gear.P && s.speedKph == 0.0
        else s.parkedEvidence?.isParked(nowElapsedMs ?: s.parkedEvidence.observedAtMs) == true

    private fun moving(s: ConversationState) = s.gear == Gear.D && s.speedKph > 0.0 &&
        (s.demoVehicle || s.parkedEvidence?.let { it.connected && it.signalsFresh(it.observedAtMs) } == true)
    /** Unknown media always holds output. */
    fun audioClear(s: ConversationState) = s.mediaActive == false
    private fun calmDriving(s: ConversationState) = s.enabled && moving(s) && s.guards.isEmpty() && !s.quiet && audioClear(s) && !s.speechActive && !s.answerListening
    private fun soundQuiet(s: ConversationState): Boolean {
        val observed = s.soundObservedAtMs ?: return false
        return s.rms != null && s.rms.isFinite() && s.rms >= 0.0 && s.rms < ConversationLimits.LOW_RMS &&
            s.wallElapsedMs >= observed && s.wallElapsedMs - observed <= ConversationLimits.SOUND_FRESH_MS
    }
    private fun eligible(s: ConversationState) = calmDriving(s) && s.regionId.isNotBlank() &&
        soundQuiet(s) && s.silenceMs >= s.silenceThresholdMs
    private fun clear(s: ConversationState) = s.copy(output = null, topic = null, speechActive = false, answerExplanationHeld = false, answerListening = false)
    private fun emit(s: ConversationState, kind: String, text: String, topic: Topic? = null): ConversationState {
        val sequence = s.outputSequence + 1
        return s.copy(destinationSilenceMs = 0, outputSequence = sequence, output = Utterance("${s.scenario.name}-$sequence", kind, text, topic?.id), topic = topic)
    }
    private fun formatOrder(s: ConversationState) = RelationshipContent.formats(s.relationship)
    private fun commitRotation(s: ConversationState, topic: Topic?, random: Random): ConversationState {
        if (topic == null || topic.id == s.rotationCommittedTopicId) return s
        val key = FourContentRotation.key(topic)
        return if (key in FourContentRotation.order) s.copy(
            rotationRemaining = FourContentRotation.consume(s.rotationRemaining, key, random), lastRotationKey = key,
            rotationCommittedTopicId = topic.id) else s
    }
    private fun commitDestination(s: ConversationState, topic: Topic?): ConversationState =
        if (topic?.source == DestinationConversation.SOURCE) s.copy(destinationPromptArmed = false,
            destinationPromptPending = false, destinationPromptCount = s.destinationPromptCount + 1,
            lastDestinationPromptAtMs = s.simElapsedMs) else s
    private fun settle(s: ConversationState, random: Random): ConversationState {
        if (s.answerExplanationHeld || s.answerListening) return s
        if (s.arrived) {
            if (s.enabled && !s.arrivalAnnounced && !s.quiet && s.guards.isEmpty() && audioClear(s) && !s.speechActive)
                return emit(s, "arrival", "목적지에 도착했어요. 즐거운 시간 보내세요.").copy(arrivalAnnounced = true)
            return s
        }
        if (s.enabled && s.restDue && !s.restAnnounced && s.topic == null && s.output == null && moving(s) && s.guards.isEmpty() && audioClear(s) && !s.speechActive) {
            val names = s.upcomingRestNames
            if (names.isNotEmpty()) {
                val prompt = if (names.size > 1) "${names[0]}와 ${names[1]} 중 어디에서 쉬어 갈까요?" else "앞에 ${names[0]}가 있어요. 쉬어 갈까요?"
                return emit(s, "rest", "운전한 지 한 시간이 되었네요. $prompt").copy(restAnnounced = true)
            }
            return emit(s, "rest", if (s.frontOccupied) "운전한 지 한 시간이 되었네요. 조수석에서 안전하게 쉴 곳을 함께 찾아주실래요?" else "운전한 지 한 시간이 되었네요. 안전한 곳에서 쉬어 가세요.").copy(restAnnounced = true)
        }
        if (eligible(s) && s.output == null && s.topic == null && !s.restDue && !s.regionEntryHeld && !s.weatherPresented && !s.pendingWeather.isNullOrBlank())
            return emit(s, "weather", s.pendingWeather).copy(weatherPresented = true, pendingWeather = null, silenceMs = 0, lastTopicAtMs = s.simElapsedMs)
        val regionReady = s.pendingRegionId.isNotBlank() && s.pendingRegionId == s.regionId &&
            s.regionStableMs >= ConversationLimits.REGION_STABLE_MS && s.pendingRegionId != s.lastAnnouncedRegionId
        if (!s.regionEntryHeld && s.topic == null && s.regionId !in s.presentedRegionIds && eligible(s) && s.output == null && !s.restDue && regionReady) {
            val all = if (s.regionalTopicsRegionId == s.regionId) s.regionalTopics.filter { it.regionId == s.regionId } else emptyList()
            val candidates = all.filter { it.id !in s.usedTopicIds }
            val order = formatOrder(s)
            val nextKey = if (FourContentRotation.enabled(s.relationship)) FourContentRotation.next(
                s.rotationRemaining, s.lastRotationKey, candidates.map(FourContentRotation::key).toSet()) else null
            val nextType = (0 until order.size).map { order[(s.topicFormatCursor + it) % order.size] }
                .firstOrNull { type -> candidates.any { it.type == type } }
            val topic = if (FourContentRotation.enabled(s.relationship)) candidates.firstOrNull { FourContentRotation.key(it) == nextKey }
                else candidates.firstOrNull { it.type == nextType }
            if (topic != null) {
                val spoken = topic.text
                val presented = emit(s, "topic", spoken, topic).copy(usedTopicIds = s.usedTopicIds + topic.id,
                    lastTopicId = topic.id, topicFormatCursor = (order.indexOf(topic.type) + 1) % order.size,
                    pendingRegionId = if (regionReady) "" else s.pendingRegionId,
                    lastAnnouncedRegionId = s.regionId, presentedRegionIds = s.presentedRegionIds + s.regionId,
                    lastTopicAtMs = s.simElapsedMs, silenceMs = 0)
                return commitDestination(presented, topic)
            }
        }
        return s
    }
    private fun externalTopic(state: ConversationState, event: ConversationEvent.ExternalTopicPresented): Topic? =
        if (!state.regionEntryHeld && eligible(state) && state.output == null && state.topic == null && !state.restDue && event.regionId.isNotBlank() && state.regionId == event.regionId && event.regionId !in state.presentedRegionIds && state.regionalTopicsRegionId == event.regionId)
            state.regionalTopics.firstOrNull { it.regionId == event.regionId && it.id == event.topicId &&
                FourContentRotation.key(it) in state.rotationRemaining &&
                !(state.lastRotationKey == "balance" && FourContentRotation.key(it) == "balance") } else null
    fun reduce(state: ConversationState, event: ConversationEvent, random: Random = Random.Default): ConversationState {
        if (event is ConversationEvent.ExternalTopicPresented) {
            return if (externalTopic(state, event) == null) state else applyEvent(state, event, random)
        }
        return settle(applyEvent(state, event, random), random)
    }
    /** One vehicle snapshot is applied completely before a single decision can emit or consume a card. */
    fun reduceBatch(state: ConversationState, events: List<ConversationEvent>, random: Random = Random.Default): ConversationState =
        settle(events.fold(state) { current, event -> applyEvent(current, event, random) }, random)

    private fun applyEvent(state: ConversationState, event: ConversationEvent, random: Random): ConversationState {
        var s = state.copy(notice = "")
        s = when (event) {
            is ConversationEvent.ArrivalChanged -> if (s.arrived == event.arrived) s else {
                val changed = s.copy(arrived = event.arrived, arrivalAnnounced = false, restDue = false, restRequested = false,
                    restAnnounced = false, restSelection = null, restCommitted = false, drivingMs = 0,
                    pendingRegionId = "", silenceMs = 0, lastTopicAtMs = null)
                if (s.answerExplanationHeld) changed else clear(changed)
            }
            is ConversationEvent.WeatherReady -> if (event.text.isBlank()) s.copy(pendingWeather = null) else if (!s.weatherPresented) s.copy(pendingWeather = event.text) else s
            ConversationEvent.ReleaseTopic -> s.copy(topic = null, output = null, speechActive = false)
            is ConversationEvent.AnswerListeningChanged -> s.copy(answerListening = event.active,
                output = if (event.active) null else s.output, speechActive = if (event.active) false else s.speechActive)
            is ConversationEvent.AnswerExplanationHold -> s.copy(answerExplanationHeld = event.active)
            ConversationEvent.UserResponded -> s.copy(silenceMs = 0, destinationSilenceMs = 0,
                destinationPromptArmed = true, destinationPromptPending = false)
            is ConversationEvent.AnswerSpoken -> if (s.enabled && !s.quiet && audioClear(s) && s.guards.isEmpty() && event.text.isNotBlank())
                emit(s, "answer", event.text, s.topic) else s
            ConversationEvent.Start -> s.copy(enabled = true)
            ConversationEvent.Stop -> clear(s.copy(enabled = false, silenceMs = 0, destinationSilenceMs = 0, destinationPromptPending = false))
            is ConversationEvent.Toggle -> if (event.enabled) s.copy(enabled = true) else clear(s.copy(enabled = false, silenceMs = 0, destinationSilenceMs = 0, destinationPromptPending = false))
            is ConversationEvent.Tick -> {
                val dt = event.dtMs.coerceAtLeast(0)
                val elapsed = s.copy(parkedEvidence = s.parkedEvidence?.let { evidence ->
                    val advance = event.wallDtMs.coerceAtLeast(0)
                    evidence.copy(observedAtMs = if (Long.MAX_VALUE - evidence.observedAtMs < advance) Long.MAX_VALUE else evidence.observedAtMs + advance)
                }, wallElapsedMs = s.wallElapsedMs + event.wallDtMs.coerceAtLeast(0), simElapsedMs = s.simElapsedMs + dt,
                    destinationSilenceMs = 0, destinationPromptPending = false,
                    quietAudioMs = if (calmDriving(s) && soundQuiet(s)) s.quietAudioMs + dt else 0,
                    drivingMs = s.drivingMs + if (moving(s)) dt else 0,
                    silenceMs = if (calmDriving(s) && s.output == null && s.topic == null && soundQuiet(s) && event.wallDtMs.coerceAtLeast(0) <= ConversationLimits.SOUND_FRESH_MS && s.soundObservedAtMs != null && s.wallElapsedMs + event.wallDtMs.coerceAtLeast(0) - s.soundObservedAtMs <= ConversationLimits.SOUND_FRESH_MS) (s.silenceMs + dt).coerceAtMost(s.silenceThresholdMs) else s.silenceMs,
                    regionStableMs = if (s.pendingRegionId.isNotBlank() && s.pendingRegionId == s.regionId)
                        (s.regionStableMs + dt).coerceAtMost(ConversationLimits.REGION_STABLE_MS) else 0)
                val pending = if (!elapsed.demoVehicle && elapsed.parkedEvidence?.let { !it.connected || !it.signalsFresh(it.observedAtMs) } != false)
                    elapsed.copy(gear = Gear.UNKNOWN, speedKph = 0.0, output = null, speechActive = false, silenceMs = 0) else elapsed
                if (pending.enabled && pending.drivingMs >= ConversationLimits.REST_MS && !pending.restRequested) pending.copy(restDue = true, restRequested = true) else pending
            }
            is ConversationEvent.VehicleEvidenceChanged -> {
                val e = event.evidence
                val known = e.connected && e.gear != null && e.speedKph != null && e.speedKph.isFinite() && e.speedKph >= 0.0 && e.signalsFresh(e.observedAtMs)
                val changed = s.copy(demoVehicle = false, parkedEvidence = e, gear = if (known) e.gear!! else Gear.UNKNOWN, speedKph = if (known) e.speedKph!! else 0.0)
                val continuingParkedEntry = s.output?.kind == "region-entry" && s.gear == Gear.P &&
                    s.parkedEvidence?.isParked(e.observedAtMs) == true && e.isParked(e.observedAtMs)
                val continuingStoppedDriveEntry = s.output?.kind == "region-entry" && known &&
                    s.gear == Gear.D && s.speedKph == 0.0 && changed.gear == Gear.D && changed.speedKph == 0.0 &&
                    s.parkedEvidence?.let { it.connected && it.signalsFresh(e.observedAtMs) } == true
                val leavingParkedEntry = s.output?.kind == "region-entry" && s.gear == Gear.P && !continuingParkedEntry
                if (continuingParkedEntry || continuingStoppedDriveEntry) changed
                else if (!known || !moving(changed) || leavingParkedEntry) changed.copy(output = if (changed.output?.kind in setOf("arrival", "answer") && known) changed.output else null, speechActive = false, silenceMs = 0) else changed
            }
            is ConversationEvent.GearChanged -> {
                val changed = s.copy(gear = event.gear, speedKph = if (!event.speedKph.isFinite()) 0.0 else event.speedKph.coerceAtLeast(0.0), parkedEvidence = null)
                if (!moving(changed) && changed.output?.kind !in setOf("arrival", "answer")) changed.copy(output = null, speechActive = false, silenceMs = 0) else changed
            }
            is ConversationEvent.SeatsChanged -> {
                val changed = s.copy(frontOccupied = event.frontOccupied, rearCount = event.rearCount.coerceIn(0, 6), seatSource = event.source)
                // Solo and rear-seat travel may receive the same location-scoped content.
                changed
            }
            is ConversationEvent.RelationshipChanged -> if (canConfigure(s)) s.copy(relationship = event.relationship, silenceMs = 0) else s.copy(notice = "관계는 출발 전 P 정차에서 선택해 주세요.")
            ConversationEvent.PreferencesChanged -> if (canConfigure(s)) s.copy(silenceMs = 0) else s.copy(notice = "설정은 P 정차에서 바꿀 수 있어요.")
            is ConversationEvent.SilenceThresholdChanged -> s.copy(silenceThresholdMs = event.ms.coerceAtLeast(1L), silenceMs = 0)
            is ConversationEvent.SoundLevel -> {
                if (!event.rms.isFinite() || event.rms < 0.0) s.copy(rms = null, soundObservedAtMs = null, quietAudioMs = 0, soundSource = "unavailable")
                else s.copy(rms = event.rms, soundObservedAtMs = s.wallElapsedMs, soundSource = event.source,
                    silenceMs = if (event.rms >= ConversationLimits.ACTIVITY_RMS) 0 else s.silenceMs,
                    quietAudioMs = if (event.rms >= ConversationLimits.LOW_RMS) 0 else s.quietAudioMs)
            }
            is ConversationEvent.SoundUnavailable -> s.copy(rms = null, soundObservedAtMs = null, quietAudioMs = 0, soundSource = event.reason)
            is ConversationEvent.MediaChanged -> if (event.active != false) s.copy(mediaActive = event.active, output = null, speechActive = false, quietAudioMs = 0, silenceMs = 0, destinationSilenceMs = 0, restAnnounced = if (s.output?.kind == "rest") false else s.restAnnounced, arrivalAnnounced = if (s.output?.kind == "arrival") false else s.arrivalAnnounced) else s.copy(mediaActive = false)
            is ConversationEvent.FocusChanged -> {
                val changed = s.copy(guards = if (event.active) s.guards + event.kind else s.guards - event.kind)
                if (changed.guards.isNotEmpty()) changed.copy(output = null, speechActive = false, silenceMs = 0, destinationSilenceMs = 0) else changed
            }
            is ConversationEvent.QuietChanged -> if (event.active && s.output?.kind != "rest") clear(s.copy(quiet = true, silenceMs = 0, destinationSilenceMs = 0, destinationPromptPending = false)) else s.copy(quiet = event.active, silenceMs = if (event.active) 0 else s.silenceMs)
            is ConversationEvent.SpeechChanged -> if (!event.active) s.copy(speechActive = false, output = null) else s.copy(speechActive = s.output != null && s.guards.isEmpty() && (!s.quiet || s.output?.kind == "rest"))
            is ConversationEvent.OutputDone -> if (event.id != null && event.id != s.output?.id) s else s.copy(output = null, speechActive = false)
            is ConversationEvent.RegionalTopicsChanged -> if (event.regionId != s.regionId) s.copy(regionalTopics = emptyList(), regionalTopicsRegionId = "") else s.copy(regionalTopics = event.topics.filter { it.regionId == event.regionId }, regionalTopicsRegionId = event.regionId)
            is ConversationEvent.RegionChanged -> {
                if (s.destination != event.destination) {
                    s = s.copy(destinationSilenceMs = 0, destinationPromptArmed = true, destinationPromptPending = false,
                        destinationPromptCount = 0, lastDestinationPromptAtMs = null, pendingWeather = null, weatherPresented = false)
                    if (s.topic?.source == DestinationConversation.SOURCE) s = clear(s)
                }
                if (s.regionId == event.regionId) {
                s.copy(regionLabel = event.label, destination = event.destination, navigationSource = event.source)
            } else {
                s.copy(regionId = event.regionId, regionLabel = event.label, destination = event.destination,
                    navigationSource = event.source, output = if (s.destination == event.destination && event.regionId.isNotBlank()) s.output else null,
                    speechActive = s.destination == event.destination && event.regionId.isNotBlank() && s.speechActive, regionalTopics = emptyList(), regionalTopicsRegionId = "",
                    pendingRegionId = if (event.regionId.isNotBlank() && event.regionId != s.lastAnnouncedRegionId) event.regionId else "",
                    regionStableMs = 0)
            }
            }
            is ConversationEvent.LocalTopicDisplayed -> {
                val topic = s.topic?.takeIf { it.id == event.topicId && s.output?.topicId == it.id }
                commitRotation(s, topic, random)
            }
            is ConversationEvent.ExternalTopicPresented -> {
                val topic = externalTopic(s, event)
                if (topic == null) s else {
                    val order = formatOrder(s)
                    val index = order.indexOf(topic.type)
                    val presented = s.copy(topic = topic, output = null, speechActive = false, destinationSilenceMs = 0, destinationPromptPending = false, pendingRegionId = "", lastAnnouncedRegionId = event.regionId, presentedRegionIds = s.presentedRegionIds + event.regionId,
                        usedTopicIds = s.usedTopicIds + topic.id, lastTopicId = topic.id,
                        lastTopicAtMs = s.simElapsedMs, silenceMs = 0,
                        topicFormatCursor = if (index >= 0) (index + 1) % order.size else s.topicFormatCursor)
                    commitRotation(commitDestination(presented, topic), topic, random)
                }
            }
            is ConversationEvent.RestOptionsChanged -> s.copy(upcomingRestNames = event.names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(2))
            is ConversationEvent.RestSelected -> if (s.restDue) s.copy(restSelection = s.upcomingRestNames.firstOrNull { it == event.id }?.let { RestStop(it, it, "", "navigation") }) else s
            ConversationEvent.RestCommitted -> if (s.restDue && s.restSelection != null) s.copy(restCommitted = true, notice = "휴식 장소 선택을 기록했어요. 실제 경로 변경 여부는 내비게이션에서 확인해 주세요.") else s
            ConversationEvent.RestCancelled -> if (s.restDue) clear(s.copy(restDue = false, restSelection = null, restCommitted = false, silenceMs = 0, destinationSilenceMs = 0, destinationPromptPending = false, destinationPromptArmed = true)) else s
            ConversationEvent.RestConfirmed -> if (canConfigure(s)) clear(s.copy(drivingMs = 0, silenceMs = 0, restDue = false, restRequested = false, restAnnounced = false, restSelection = null, restCommitted = false)) else s.copy(notice = "안전하게 주차하고 휴식을 마친 뒤 확인해 주세요.")
            is ConversationEvent.ScenarioChanged -> if (s.demoVehicle && canConfigure(s)) simulation(event.scenario).copy(topicFormatCursor = s.topicFormatCursor,
                rotationRemaining = s.rotationRemaining, lastRotationKey = s.lastRotationKey) else s.copy(notice = "시연 조건은 P 정차에서 바꿀 수 있어요.")
        }
        return s
    }
    fun view(s: ConversationState): ConversationView {
        val info = when {
            !s.enabled -> Triple("disabled", "함께하는 길에, 대화 한마디.", "출발 전에 함께 탄 사람과의 관계를 골라 주세요. 운전에 여유가 있을 때 가볍게 말을 건네요.")
            s.arrived -> Triple("arrival", "목적지에 도착했어요", "즐거운 시간 보내세요")
            canConfigure(s) && s.topic != null && !(s.restDue && s.restAnnounced) && !s.quiet -> Triple("topic", s.topic.label, s.topic.note ?: "정차 중 답을 선택해 주세요.")
            s.guards.isNotEmpty() -> Triple("focus", "지금은 운전에 집중할 시간", "분기점·합류·터널 구간에서는 진행 중인 음성도 멈춰요.")
            (s.restDue && s.restAnnounced) -> Triple("rest", "잠깐, 쉬어 갈까요?", "운전한 지 한 시간이 되었네요. 안전한 곳에서 쉬어 가세요.")
            s.quiet -> Triple("quiet", "조용히 갈게요", "화두는 쉬고, 휴식 안내만 유지해요.")
            canConfigure(s) -> Triple("parked", "출발 준비가 되었어요", "동승 관계를 고르고 출발해 주세요.")
            s.mediaActive == null -> Triple("media-unknown", "미디어 상태 확인 중", "미디어 재생 상태가 확인되면 지역 안내를 이어가요.")
            s.mediaActive == true -> Triple("media-hold", "미디어 재생 중", "음악이나 라디오가 끝나면 지역 안내를 이어가요.")
            s.answerListening -> Triple("answer-listening", "답변을 듣고 있어요", "보기의 번호나 이름으로 말해 주세요.")
            s.speechActive -> Triple("speaking", "이곳의 이야기를 들려드려요", "길 안내와 운전 집중 구간에서는 잠시 멈춰요.")
            s.topic != null -> Triple("topic", s.topic.label, s.topic.note ?: "다른 지역에 들어가면 새로운 화두를 건네요.")
            else -> Triple("waiting", "이야기가 시작되길 기다려요", "지역이 바뀌면 지금 지나는 곳의 이야기를 건네요.")
        }
        return ConversationView(s, info.first, info.second, info.third, (if (s.frontOccupied) 1 else 0) + s.rearCount,
            canConfigure(s), s.enabled && !s.answerListening && s.guards.isEmpty() && audioClear(s) && (!s.quiet || s.output?.kind == "rest"),
            (s.silenceThresholdMs - s.silenceMs).coerceAtLeast(0), (ConversationLimits.REST_MS - s.drivingMs).coerceAtLeast(0))
    }
}
