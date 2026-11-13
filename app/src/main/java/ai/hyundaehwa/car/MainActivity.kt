package ai.hyundaehwa.car

import ai.hyundaehwa.core.*
import ai.hyundaehwa.car.platform.*
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.content.res.AppCompatResources

/** Native wide-screen conversation app. Vehicle signals and explicit emulator inputs remain separate. */
class MainActivity : Activity() {
    private val ink = Color.rgb(20, 40, 60)
    private val muted = Color.rgb(107, 112, 117)
    private val teal = Color.rgb(11, 38, 72)
    private val accentPressed = Color.rgb(23, 77, 122)
    private val selectedFill = Color.rgb(237, 242, 248)
    private val messageFill = Color.rgb(232, 240, 243)
    private val accentBorder = Color.rgb(183, 199, 216)
    private val navy = Color.rgb(248, 250, 252)
    private val cream = Color.rgb(245, 245, 245)
    private val appFont: Typeface by lazy { ResourcesCompat.getFont(this, R.font.pretendard) ?: Typeface.create("sans-serif", Typeface.NORMAL) }
    private val handler = Handler(Looper.getMainLooper())
    private var state = HyundaehwaEngine.initial()
    private var passengerProfile = PassengerProfile()
    private var difficultyMode = DifficultyMode.AUTO
    private var settingsOpen = false
    private var settingsDialog: AlertDialog? = null
    private var settingsHolder: android.widget.FrameLayout? = null
    private var profileDetailsOpen = false
    private lateinit var guidanceButton: Button
    private lateinit var quietButton: Button
    private lateinit var composer: LinearLayout
    private lateinit var composerInput: EditText
    private val effectiveDifficulty get() = QuestionDifficulty.resolve(difficultyMode, passengerProfile)
    private fun saveProfile() {
        if (emulatorMode) return // Developer scenarios never alter actual vehicle preferences.
        getSharedPreferences("conversation_profile", MODE_PRIVATE).edit()
            .putString("youngest", passengerProfile.youngest.name)
            .putString("preference", passengerProfile.preference.name)
            .putString("difficulty", difficultyMode.name)
            .putInt("silence_minutes", silenceMinutes).apply()
    }
    private fun restoreProfile() {
        val prefs = getSharedPreferences("conversation_profile", MODE_PRIVATE)
        passengerProfile = PassengerProfile(
            AgeBand.entries.firstOrNull { it.name == prefs.getString("youngest", "UNKNOWN") } ?: AgeBand.UNKNOWN,
            QuizPreference.entries.firstOrNull { it.name == prefs.getString("preference", "CASUAL") } ?: QuizPreference.CASUAL)
        silenceMinutes = prefs.getInt("silence_minutes", 5).coerceIn(1, 15)
        difficultyMode = DifficultyMode.entries.firstOrNull { it.name == prefs.getString("difficulty", "AUTO") } ?: DifficultyMode.AUTO
    }
    private var emulatorMode = false
    private var demoPlaybackSpeed = DemoPlaybackClock.DEFAULT_SPEED
    private lateinit var demoPlaybackControls: LinearLayout
    private lateinit var demoPlaybackLabel: TextView
    private val demoPlaybackButtons = mutableMapOf<Int, Button>()
    private var emulatorTab = false
    private var resumed = false
    private var lastTick = 0L
    private var spokenOutput: String? = null
    private var voiceEnabled = true
    private var manualExternalMedia = false
    private var voiceState = VoicePlaybackState()
    private var voiceTestSequence = 0L
    private var lastLocalVoiceEndedAt = 0L
    private var lastLocalVoiceEchoDelayMs = 650L
    private var welcomePending = false
    private var automaticPassengerPromptShown = false
    private var welcomeParkedSince = 0L
    private var safetyNoticeIssued = false
    private val safetyNoticeId = "safety-mode-notice"
    private var silenceMinutes = 5
    private val entryWelcome = "안녕하세요. 현대화예요. 함께하는 길에, 지금 지나는 곳을 이야기해 봐요."
    private var answerVoice: CardAnswerVoiceInput? = null
    private var answerVoiceState = CardAnswerVoiceState()
    private var cabinSoundOptIn = false
    private var cabinMonitor: CabinSoundMonitor? = null
    private var lastCabinActivityAt = 0L
    private var answerVoiceOptIn = false
    private var answerVoicePermissionPending = false
    private var answerVoiceAttemptedCardId: String? = null
    private var answerVoiceTargetDestination = ""
    private var answerVoiceQuestionReady = false
    private var answerVisible = false
    private val answerSpeech = AnswerSpeechQueue()
    private val regionEntry = RegionEntryQueue()
    private var answerBubbleView: View? = null
    private var answerScrollPending = false
    private var conversationScrollY = 0
    private var unansweredReveal = false
    private var pinnedCard: AdministrativeConversationTopic? = null
    private var pinnedRegionId: String? = null
    private var pinnedShownAt = 0L
    private var revealedAt = 0L
    private var pausedAt = 0L
    private fun pinCard(item: AdministrativeConversationTopic, region: String) {
        if (pinnedCard?.id == item.id) return
        answerVoice?.clear(); answerVoiceAttemptedCardId = null; answerVoiceQuestionReady = !voiceEnabled
        discardAnswerSpeech(reset = true)
        clearPromptResume()
        conversationScrollY = 0; answerScrollPending = false
        pinnedCard = item; pinnedRegionId = region; pinnedShownAt = SystemClock.elapsedRealtime(); revealedAt = 0L
        choiceCardId = item.id; selectedChoiceId = null; heardChoice = null; answerVisible = false; unansweredReveal = false
    }
    private fun discardAnswerSpeech(reset: Boolean = false) {
        if (reset) answerSpeech.reset() else answerSpeech.cancel()
        state = state.copy(answerExplanationHeld = false)
    }
    private fun revealPinned(timedOut: Boolean = false) {
        if (answerVisible) return // Duplicate reveal/results must not interrupt or replay an explanation.
        val item = pinnedCard ?: return
        answerVoice?.cancel("답변 확인을 마쳤어요.")
        clearPromptResume()
        if (!timedOut && (selectedChoiceId != null || !heardChoice.isNullOrBlank())) {
            state = HyundaehwaEngine.reduce(state, ConversationEvent.UserResponded)
        }
        answerVisible = true; unansweredReveal = timedOut; revealedAt = SystemClock.elapsedRealtime()
        if (answerSpeech.enqueue(item.id, AnswerExplanation.text(item, selectedChoiceId, timedOut), state.destination)) {
            state = state.copy(answerExplanationHeld = true)
            speech?.stop("정답과 해설을 이어서 알려드릴게요.")
            playPendingOutput()
        }
    }
    private fun advancePinned(now: Long, elapsed: Long) {
        if (pinnedCard == null) return
        // Do not consume reading time while another panel covers the card.
        val hidden = answerVoiceState.active || state.answerListening || relationshipVoice != null || answerSpeech.unfinished(pinnedCard?.id) || (state.restDue && state.restAnnounced) || talkState.active || talkState.transcript.isNotBlank() || talkState.error != null ||
            (settingsOpen && resources.configuration.screenWidthDp < 600) || promptResume.holdsCard(pinnedCard?.id) || voiceState.active || state.speechActive || state.guards.isNotEmpty() || speech?.audioFocusBlocked == true || state.mediaActive != false || state.quiet || (!emulatorMode && currentSnapshot.navigationVoiceActive == true)
        if (hidden) {
            pinnedShownAt += elapsed
            if (revealedAt > 0) revealedAt += elapsed
            return
        }
        if (answerVoiceQuestionReady && PinnedQuizTiming.shouldReveal(now, pinnedShownAt, answerVisible)) { revealPinned(timedOut = true); render(force = true) }
        val nextEligible = state.enabled && ((state.gear == Gear.D && state.speedKph > 0) || state.arrived) &&
            !(state.restDue && state.restAnnounced) && !state.speechActive && HyundaehwaEngine.view(state).canSpeak &&
            ((state.regionId !in state.presentedRegionIds && state.regionalTopicsRegionId == state.regionId &&
            state.regionalTopics.any { it.id !in state.usedTopicIds } && state.regionStableMs >= ConversationLimits.REGION_STABLE_MS) ||
                DestinationConversation.requested(state) || state.restDue || state.arrived)
        val feedbackFinished = revealedAt > 0 && now >= revealedAt && now - revealedAt >= PinnedQuizTiming.FEEDBACK_MS
        if (answerVisible && nextEligible && feedbackFinished) {
            pinnedCard = null; pinnedRegionId = null; revealedAt = 0L
            clearGleoCard()
            answerSpeech.reset()
            state = HyundaehwaEngine.reduceBatch(state, listOf(ConversationEvent.ReleaseTopic, ConversationEvent.AnswerExplanationHold(false)))
            render(force = true)
        }
    }
    private var speech: AndroidSpeechOutput? = null
    private var talk: TalkConversation? = null
    private var talkState = TalkState()
    private var gleoStructuredCard: AdministrativeConversationTopic? = null
    private var choiceCardId = ""
    private var selectedChoiceId: String? = null
    private var realRestChoice: String? = null
    private var restStatus: WaypointResult? = null
    private var restHeard: String? = null
    private var restVoiceStage = "" // choose, confirm; only the latter can authorize a waypoint.
    private var restVoiceReady = false
    private var restVoiceAttempted = false
    private var restVoiceSequence = 0L
    private var restConfirmationHeard: String? = null
    private var pendingRestStatusSpeech: String? = null
    private var weatherConsent = false
    private var weatherTripKey = ""
    private var weatherTried = false
    private var weatherLoading = false
    private var weatherPresented = false
    private var weatherCard = ""
    private var weatherEpoch = 0L
    private var forecast: ArrivalForecast? = null
    private fun clearWeather() {
        weatherEpoch++; weatherTripKey = ""; weatherCard = ""; forecast = null; weatherPresented = false; weatherTried = false; weatherLoading = false
        state = HyundaehwaEngine.reduce(state, ConversationEvent.WeatherReady(""))
    }
    private var heardChoice: String? = null
    private val activeAudience get() = passengerProfile.audience
    private var gleoLastTopic = ""
    private var gleoLastTopicFormat = ""
    private var gleoLastTopicSource = ""
    private var gleoCardRegionId: String? = null
    private var gleoCardAudience: String? = null
    private var gleoCardDifficulty: Int? = null
    private var administrativeCatalog: AdministrativeConversationCatalog? = null
    private var gleoRequestStatus = "차량 AI 연결 준비"
    private var gleoCallbackSent: Boolean? = null
    private lateinit var relayStore: RelayConnectionStore
    private var relaySettings = RelayConnection()
    private var talkConsent = false
    private var parkedDialog: AlertDialog? = null
    private var relationshipVoice: RelationshipVoiceInput? = null
    private var pendingRelationshipVoiceStart: (() -> Unit)? = null
    private var relationshipPermissionInFlight = false
    private var relationshipPermissionFeedback: ((String) -> Unit)? = null
    private var bridge: PleosPlatformBridge? = null
    private var activeSpeechId: String? = null
    private val promptResume = PromptResumeQueue()
    private var localPrompt: Utterance? = null
    private var localPromptDestination = ""
    private var localPromptCardId: String? = null
    private fun currentPromptCardId() = pinnedCard?.id ?: state.topic?.id
    private fun rememberInterruptedPrompt(id: String, remaining: String) {
        val prompt = localPrompt ?: return
        if (prompt.id != id || !state.enabled || state.quiet || (state.arrived && !(prompt.kind == "answer" && state.answerExplanationHeld)) ||
            localPromptDestination != state.destination || localPromptCardId != currentPromptCardId()) return
        promptResume.remember(prompt, remaining, localPromptDestination, localPromptCardId)
    }
    private fun clearPromptResume() { promptResume.clear(); localPrompt = null; speech?.releaseInterruptedFocus() }

    private var vehicleParked = false
    private var currentSnapshot = PlatformSnapshot()
    private lateinit var root: LinearLayout
    private lateinit var headerStatus: TextView
    private lateinit var passengerStatus: TextView
    private lateinit var activityStatusTextView: TextView
    private lateinit var destinationAction: TextView
    private var relationshipChosen = false
    private lateinit var body: LinearLayout
    private lateinit var footerStatus: TextView
    private var platformStatus = "차량 신호 연결 대기"
    private var lastRendered = ""
    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            val now = SystemClock.elapsedRealtime()
            val wallElapsed = if (lastTick == 0L) 0L else (now - lastTick).coerceAtLeast(0)
            val elapsed = wallElapsed.coerceAtMost(2_000)
            lastTick = now
            if (elapsed > 0) {
                // Advance the existing evidence clock before evaluating a newly read snapshot.
                dispatch(ConversationEvent.Tick(DemoPlaybackClock.navigationDelta(elapsed, BuildConfig.DEBUG, emulatorMode, demoPlaybackSpeed), wallElapsed))
                if (!emulatorMode) receivePlatform(currentSnapshot) else refreshRegionalTopics()
            }
            syncCabinMonitor()
            maybeStartAnswerVoice()
            maybeStartRestVoice()
            maybeSpeakRestStatus()
            maybeSpeakSafetyNotice()
            advancePinned(now, elapsed)
            playEntryWelcome()
            maybeShowInitialPassengerPrompt()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreProfile()
        state = HyundaehwaEngine.reduce(state, ConversationEvent.SilenceThresholdChanged(silenceMinutes * 60_000L))
        cabinSoundOptIn = getSharedPreferences("conversation_profile", MODE_PRIVATE).getBoolean("cabin_sound_monitor", false)
        answerVoiceOptIn = getSharedPreferences("conversation_profile", MODE_PRIVATE).getBoolean("on_device_card_answers", false)
        weatherConsent = getSharedPreferences("conversation_profile", MODE_PRIVATE).getBoolean("weather", false)
        voiceEnabled = getSharedPreferences("conversation_profile", MODE_PRIVATE).getBoolean("spoken_guidance", true)
        manualExternalMedia = getSharedPreferences("conversation_profile", MODE_PRIVATE).getBoolean("external_media_hold", false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Keep the system bars available; vehicle launchers own their surrounding navigation.
        window.statusBarColor = navy
        window.navigationBarColor = Color.rgb(245, 245, 245)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        cabinMonitor = CabinSoundMonitor(this) { observation ->
            handler.post {
                if (!resumed || !cabinSoundOptIn) return@post
                val rms = observation.rms
                if (observation.available && rms != null) {
                    if (rms >= ConversationLimits.LOW_RMS) lastCabinActivityAt = SystemClock.elapsedRealtime()
                    if (rms >= 0.04) {
                        if (observation.echoCancellation && voiceState.active && voiceState.purpose == VoicePurpose.PROMPT) {
                            activeSpeechId?.let { rememberInterruptedPrompt(it, speech?.remainingText.orEmpty()) }
                            speech?.stop("대화를 듣기 위해 안내를 잠시 멈췄어요.")
                        }
                    }
                    dispatch(ConversationEvent.SoundLevel(rms, observation.source))
                } else dispatch(ConversationEvent.SoundUnavailable(observation.error ?: "소리 입력 확인 중"))
            }
        }
        answerVoice = CardAnswerVoiceInput(this, { cardId -> answerVoiceBlockReason(cardId) }, ::onAnswerVoiceChanged)
        speech = AndroidSpeechOutput(this, { id, remaining -> rememberInterruptedPrompt(id, remaining) }) { next ->
            val wasActive = voiceState.active
            voiceState = next
            syncCabinMonitor()
            if (wasActive && !next.active) {
                lastLocalVoiceEndedAt = SystemClock.elapsedRealtime()
                lastLocalVoiceEchoDelayMs = VoiceEchoDelay.milliseconds(next)
            }
            if (next.purpose == VoicePurpose.PROMPT && activeSpeechId == next.requestId) {
                if (next.phase == VoicePhase.PLAYING && !state.speechActive) dispatch(ConversationEvent.SpeechChanged(true))
                if (!next.active && next.phase != VoicePhase.IDLE) {
                    val completed = activeSpeechId
                    val completedKind = localPrompt?.kind
                    if (next.phase == VoicePhase.FAILED && completed != null) promptResume.holdAfterFailure(completed)
                    activeSpeechId = null; localPrompt = null
                    if (completed != null && regionEntry.active?.speechId == completed) {
                        when (next.phase) {
                            VoicePhase.COMPLETED -> regionEntry.complete(completed)
                            VoicePhase.CANCELLED -> regionEntry.cancelled(completed, promptResume.pending?.utterance?.id == completed)
                            VoicePhase.FAILED -> regionEntry.failed(completed, next.error ?: "지역 안내 음성을 재생하지 못했어요.")
                            else -> Unit
                        }
                        state = state.copy(regionEntryHeld = regionEntry.active != null)
                    }
                    if (completed != null && answerSpeech.current?.id == completed) {
                        when (next.phase) {
                            VoicePhase.COMPLETED -> if (answerSpeech.complete(completed)) revealedAt = SystemClock.elapsedRealtime()
                            VoicePhase.CANCELLED -> if (promptResume.pending?.utterance?.id == completed) answerSpeech.pause(completed) else answerSpeech.fail(completed)
                            VoicePhase.FAILED -> answerSpeech.fail(completed)
                            else -> Unit
                        }
                    }
                    if (pinnedCard != null && next.phase == VoicePhase.COMPLETED && state.output?.topicId == pinnedCard?.id && !answerVisible) {
                        pinnedShownAt = SystemClock.elapsedRealtime(); answerVoiceQuestionReady = true
                    }
                    if (next.phase == VoicePhase.COMPLETED && completedKind == "rest") {
                        restVoiceStage = "choose"; restVoiceReady = true; restVoiceAttempted = false
                    } else if (next.phase == VoicePhase.COMPLETED && completedKind == "rest-confirm") {
                        restVoiceStage = "confirm"; restVoiceReady = true; restVoiceAttempted = false
                    }
                    dispatch(ConversationEvent.OutputDone(completed))
                }
            }
            talk?.voiceChanged(next)
            render(force = true)
            maybeStartAnswerVoice()
        }
        if ((BuildConfig.DEBUG && BuildConfig.ENABLE_GEMINI_RELAY)) {
        relayStore = RelayConnectionStore(this)
        relaySettings = relayStore.read()
        talk = TalkConversation(this, { relaySettings }, { voiceTestBlockReason() }, {
            mapOf("currentRoad" to "", "currentArea" to if (emulatorMode) state.regionLabel else currentSnapshot.address.orEmpty(),
                "destination" to if (emulatorMode) state.destination else currentSnapshot.destination.orEmpty(),
                "relationship" to state.relationship.label, "gear" to state.gear.name)
        }, { id, value -> speech?.speak(id, value, VoicePurpose.TALK) == true }, {
            if (voiceState.active && voiceState.purpose == VoicePurpose.TALK) speech?.stop("대화를 멈췄어요.")
        }, { next -> talkState = next; render(force = true) })
        }
        // Parse and index the shared polygons once off the UI thread. Gleo can truthfully ask
        // to wait if an invocation arrives before the local catalog is ready.
        Thread({
            val catalog = AdministrativeConversationLoader.load(assets)
            handler.post {
                if (!isFinishing && !isDestroyed) {
                    administrativeCatalog = catalog
                    if (!emulatorMode) receivePlatform(currentSnapshot) else render(force = true)
                }
            }
        }, "hyundaehwa-administrative-catalog").apply { isDaemon = true; start() }
        bridge = PleosPlatformBridge(this) { snapshot -> if (!emulatorMode) receivePlatform(snapshot) }
        state = HyundaehwaEngine.reduce(state, ConversationEvent.SeatsChanged(false, 0, "unavailable"))
        buildShell()
        render(force = true)
        intent?.let(::handleGleoIntent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGleoIntent(intent)
    }
    private fun handleGleoIntent(intent: Intent) {
        if (!GleoInterface.canAcceptActions(this)) return
        val input = GleoInterface.parse(intent) ?: return
        welcomePending = false // Gleo owns the final response for this invocation.
        clearStaleGleoCard()
        val ordinaryVoiceBlocked = regionEntry.blocksContent || (!emulatorMode && manualExternalMedia) || state.guards.isNotEmpty() || !HyundaehwaEngine.audioClear(state) || (!emulatorMode && currentSnapshot.navigationVoiceActive == true)
        val topicContext = currentTopicContext(input.topicType)
        val restCard = if (state.restDue && state.restAnnounced && !emulatorMode && !state.arrived)
            if (restVoiceStage == "confirm" && restVoiceReady) restConfirmationCard() else realRestCard() else null
        val card = if (input.functionName == GleoTalkActions.ANSWER) restCard ?: currentAnswerCard() else null
        // A final vehicle-transcribed card answer must not be rejected by the user's own RMS voice.
        val voiceBlocked = if (input.functionName == GleoTalkActions.ANSWER && card != null)
            CardAnswerVoicePolicy.blockReason(CardAnswerVoiceConditions(resumed, state.enabled, true, state.gear,
                state.quiet, state.guards, state.mediaActive, !emulatorMode && currentSnapshot.navigationVoiceActive == true,
                false, state.answerExplanationHeld, relationshipVoice != null || settingsOpen || parkedDialog != null)) != null
            else ordinaryVoiceBlocked
        val requested = GleoTalkActions.handle(input, state.quiet, state.enabled, canConfigure(), voiceBlocked, topicContext, card) ?: return
        // Gleo owns its final response, so only request silent mode; enforcement is external.
        val result = if (input.functionName == GleoTalkActions.ANSWER) requested.copy(mode = "silent", message = if (requested.status == "success" && card != null && restCard == null)
            "Answer received. The app will play the full Korean explanation locally when safe; do not speak over it." else requested.message) else if (voiceBlocked) requested.copy(mode = "silent") else requested
        result.setQuiet?.let { quiet ->
            if (quiet) { discardAnswerSpeech(); clearPromptResume(); talk?.cancel("차량 음성 요청으로 조용히 가기를 켰어요."); speech?.stop("조용히 가기를 켰어요.") }
            state = HyundaehwaEngine.reduce(state, ConversationEvent.QuietChanged(quiet))
        }
        // Reply immediately: Gleo owns its final spoken response and the documented 7-second deadline.
        gleoCallbackSent = GleoInterface.respond(this, result)
        val selectedRestChoice = result.choiceId
        if (gleoCallbackSent == true && selectedRestChoice != null && restCard != null) {
            if (restStatus?.status !in setOf(WaypointResultStatus.CONFIRMED, WaypointResultStatus.REQUESTED)) {
                if (restCard.id == "active-rest-confirm") finishSpokenRestConfirmation(selectedRestChoice, input.answer)
                else chooseSpokenRest(selectedRestChoice, input.answer)
            }
        } else if (gleoCallbackSent == true && result.status == "success" && card != null && input.functionName == GleoTalkActions.ANSWER) {
            choiceCardId = card.id; selectedChoiceId = result.choiceId; heardChoice = input.answer
            answerScrollPending = true
            revealPinned()
            playPendingOutput()
        }
        if (gleoCallbackSent == true && result.status == "success" && input.functionName == GleoTalkActions.TOPIC) {
            topicContext?.administrativeSelection?.let {
                administrativeCatalog?.recordPresented(it, activeAudience, effectiveDifficulty)
                clearPromptResume()
                speech?.stop("차량 AI가 화두 안내를 이어가요.")
                activeSpeechId = null
                state = HyundaehwaEngine.reduce(state, ConversationEvent.ExternalTopicPresented(it.area.id, it.topic.id))
                pinCard(it.topic, it.area.id)
                answerVoiceQuestionReady = false // Gleo final-speech completion is vehicle-owned, not inferred here.
                gleoStructuredCard = it.topic
                gleoLastTopic = result.topic.orEmpty()
                gleoLastTopicFormat = when (it.topic.contentRotationKey) { "destination" -> "목적지 이야기"; "balance" -> "밸런스게임"; "quiz" -> "위치 기반 퀴즈"; else -> "이곳의 이야기" }
                gleoLastTopicSource = "${it.area.label} · ${it.topic.contentNote}\n콘텐츠 자료: ${it.topic.sourceTitle}\n위치 판별: ${it.boundarySourceTitle} · ${it.boundaryVintage}"
                gleoCardRegionId = it.area.id
                gleoCardAudience = activeAudience
                gleoCardDifficulty = effectiveDifficulty
            }
        } else if (input.functionName == GleoTalkActions.TOPIC && pinnedCard == null) {
            clearGleoCard()
        }
        gleoRequestStatus = when {
            gleoCallbackSent == false -> "요청 결과를 전달하지 못했어요"
            result.status == "asking" -> "요청에 필요한 내용을 확인해 주세요"
            result.status == "fail" -> "현재 조건에서는 요청을 처리할 수 없어요"
            else -> "음성 기능 요청을 처리했어요"
        }
        render(force = true)
    }
    override fun onResume() {
        super.onResume(); if (pausedAt > 0 && pinnedCard != null) { val gap = SystemClock.elapsedRealtime() - pausedAt; pinnedShownAt += gap; if (revealedAt > 0) revealedAt += gap }; pausedAt = 0; resumed = true; welcomePending = intent?.action !in GleoTalkActions.names; if (!emulatorMode) bridge?.start(); lastTick = SystemClock.elapsedRealtime(); handler.post(tick)
    }
    override fun onPause() {
        resumed = false; settingsOpen = false; settingsDialog?.dismiss(); settingsDialog = null; settingsHolder = null; cabinMonitor?.setEnabled(false); answerVoice?.clear()
        discardAnswerSpeech()
        clearPromptResume()
        relationshipVoice?.cancel()
        pausedAt = SystemClock.elapsedRealtime(); resumed = false; welcomePending = false; handler.removeCallbacks(tick); lastTick = 0
        if (!relationshipPermissionInFlight) parkedDialog?.dismiss()
        talk?.clear(); talkConsent = false; speech?.stop(); bridge?.stop()
        state = HyundaehwaEngine.reduce(state, ConversationEvent.SpeechChanged(false))
        super.onPause()
    }
    override fun onDestroy() { cabinMonitor?.close(); cabinMonitor = null; answerVoice?.close(); answerVoice = null; relationshipVoice?.close(); relationshipVoice = null; pendingRelationshipVoiceStart = null; relationshipPermissionFeedback = null; talk?.close(); talk = null; bridge?.stop(); speech?.shutdown(); speech = null; super.onDestroy() }

    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        render(force = true)
    }

    private fun observeRegionEntry(regionId: String, label: String, destination: String) {
        regionEntry.observe(regionId, label, destination, voiceState.requestId?.takeIf { voiceState.active })
        val checkpoint = promptResume.pending
        if (checkpoint?.utterance?.kind == "region-entry" && checkpoint.utterance.id != (regionEntry.active?.speechId ?: regionEntry.pending?.speechId)) clearPromptResume()
        state = state.copy(regionEntryHeld = regionEntry.active != null)
    }
    private fun prepareRegionEntryOutput(localAudioReady: Boolean) {
        // Entry text can be shown immediately. Speaking waits for fresh, sustained quiet,
        // and a known arrival forecast gets the first eligible silence.
        val observed = state.soundObservedAtMs
        val soundFresh = observed != null && state.wallElapsedMs - observed in 0..ConversationLimits.SOUND_FRESH_MS
        val quietEnough = soundFresh && state.rms != null && state.rms!! < ConversationLimits.LOW_RMS &&
            state.silenceMs >= state.silenceThresholdMs
        val weatherHasPriority = weatherConsent && weatherTried &&
            (weatherLoading || (forecast != null && !state.weatherPresented))
        if (!quietEnough || weatherHasPriority) return
        val conditions = RegionEntryConditions(resumed, voiceEnabled, localAudioReady,
            pinnedCard != null || state.answerExplanationHeld || settingsOpen || parkedDialog != null || emulatorTab,
            voiceState.active || talkState.active || answerVoiceState.active || relationshipVoice != null,
            promptResume.pending != null, !emulatorMode && currentSnapshot.navigationVoiceActive == true,
            speech?.audioFocusBlocked == true)
        if (regionEntry.recover(state, conditions) == RegionEntryRecovery.TEXT) {
            state = state.copy(regionEntryHeld = regionEntry.active != null)
            return
        }
        if (!voiceEnabled) {
            if (regionEntry.acknowledgeText(state, conditions)) {
                // The normal next tick selects content; text mode never fabricates an output/voice completion.
                state = state.copy(regionEntryHeld = regionEntry.active != null)
            }
            return
        }
        val next = regionEntry.begin(state, conditions) ?: return
        state = state.copy(regionEntryHeld = true, output = Utterance(next.speechId, "region-entry", next.spokenText), speechActive = false)
        spokenOutput = null
    }
    private fun dispatch(event: ConversationEvent) {
        val before = state
        if (event is ConversationEvent.RegionChanged) {
            invalidateDestinationCardOnChange(event.destination)
            observeRegionEntry(event.regionId, event.label, event.destination)
        }
        if (event == ConversationEvent.Stop || event == ConversationEvent.ReleaseTopic ||
            (event is ConversationEvent.QuietChanged && event.active) || (event is ConversationEvent.Toggle && !event.enabled) ||
            event is ConversationEvent.ScenarioChanged ||
            (event is ConversationEvent.RegionChanged && event.destination != state.destination)) {
            discardAnswerSpeech(); clearPromptResume()
            if (event == ConversationEvent.Stop || event is ConversationEvent.ScenarioChanged || (event is ConversationEvent.Toggle && !event.enabled)) {
                regionEntry.clear(); state = state.copy(regionEntryHeld = false)
            }
        }
        val guarded = event is ConversationEvent.RelationshipChanged || event == ConversationEvent.PreferencesChanged || event == ConversationEvent.RestConfirmed || event is ConversationEvent.ScenarioChanged
        state = if (!emulatorMode && guarded) HyundaehwaEngine.reduceBatch(state, listOf(ConversationEvent.VehicleEvidenceChanged(parkedEvidence(currentSnapshot, SystemClock.elapsedRealtime())), event)) else HyundaehwaEngine.reduce(state, event)
        answerVoice?.enforce()
        if (!state.restDue) { realRestChoice = null; restStatus = null; restHeard = null; restVoiceStage = ""; restVoiceReady = false; restVoiceAttempted = false; restConfirmationHeard = null; pendingRestStatusSpeech = null }
        if (pinnedCard == null && before.topic?.id != state.topic?.id) { answerVisible = false; selectedChoiceId = null; heardChoice = null; choiceCardId = "" }
        if (event == ConversationEvent.Stop || event is ConversationEvent.ScenarioChanged) { pinnedCard = null; pinnedRegionId = null; clearGleoCard() }
        clearStaleGleoCard()
        talk?.enforce()
        cancelUnsafeSpeech()
        render()
        playPendingOutput()
    }
    private fun playPendingOutput() {
        promptResume.discardStale(state, state.destination, currentPromptCardId())
        if (lastCabinActivityAt > 0 && (SystemClock.elapsedRealtime() - lastCabinActivityAt < 5_000 ||
            (promptResume.pending != null && (state.rms == null || state.rms!! >= ConversationLimits.LOW_RMS)))) return
        if (!resumed || answerVoiceState.active || state.answerListening || relationshipVoice != null || talkState.active || voiceState.active || speech?.audioFocusBlocked == true) return
        val localAudioReady = true // Playback never depends on microphone permission, RMS or AEC.
        prepareRegionEntryOutput(localAudioReady)
        val resumedPrompt = promptResume.takeIfReady(state, state.destination, currentPromptCardId(), PromptResumeConditions(
            resumed = resumed, voiceEnabled = voiceEnabled, localAudioReady = localAudioReady,
            otherVoiceActive = talkState.active || voiceState.active,
            navigationVoiceActive = !emulatorMode && currentSnapshot.navigationVoiceActive == true,
            audioFocusBlocked = speech?.audioFocusBlocked == true))
        if (resumedPrompt != null) {
            // Restore only this interrupted local output; Gleo's final speech is never app-controlled.
            state = state.copy(output = resumedPrompt.utterance.copy(text = resumedPrompt.remainingText), speechActive = false)
            spokenOutput = null
        }
        val explanation = answerSpeech.current?.takeIf { it.cardId == pinnedCard?.id && it.destination == state.destination }
        if (explanation != null && answerSpeech.unfinished(explanation.cardId)) {
            if (!voiceEnabled) { answerSpeech.cancel(); clearPromptResume() }
            else if (explanation.phase == AnswerSpeechPhase.FAILED) return
            else if (resumedPrompt == null && explanation.phase == AnswerSpeechPhase.PAUSED) return
            else if (explanation.phase == AnswerSpeechPhase.WAITING && localAudioReady &&
                HyundaehwaEngine.view(state).canSpeak && (emulatorMode || currentSnapshot.navigationVoiceActive != true)) {
                state = state.copy(output = Utterance(explanation.id, "answer", explanation.text, explanation.cardId), speechActive = false)
                spokenOutput = null
            }
        }
        val output = state.output ?: return
        if (output.kind == "weather") weatherCard = output.text
        if (answerSpeech.unfinished(pinnedCard?.id) && output.id != answerSpeech.current?.id) return
        if (!localAudioReady || (!emulatorMode && currentSnapshot.navigationVoiceActive == true)) return
        if (!voiceEnabled) { clearPromptResume(); dispatch(ConversationEvent.OutputDone(output.id)); return }
        if (!HyundaehwaEngine.view(state).canSpeak || spokenOutput == output.id) return
        spokenOutput = output.id
        activeSpeechId = output.id
        if (output.kind == "answer") answerSpeech.start(output.id)
        localPrompt = output; localPromptDestination = state.destination; localPromptCardId = currentPromptCardId()
        if (speech?.speak(output.id, output.text, VoicePurpose.PROMPT, keepTogether = true) != true) {
            activeSpeechId = null; localPrompt = null; answerSpeech.fail(output.id); dispatch(ConversationEvent.OutputDone(output.id))
        }
    }
    private fun cancelUnsafeSpeech() {
        promptResume.discardStale(state, state.destination, currentPromptCardId())
        if (!voiceEnabled) clearPromptResume()
        if (!voiceState.active) return
        if (voiceState.purpose == VoicePurpose.TALK) { talk?.enforce(); return }
        if (voiceState.requestId == safetyNoticeId) {
            if (state.guards.isEmpty() || state.quiet || state.mediaActive != false ||
                (!emulatorMode && currentSnapshot.navigationVoiceActive == true)) speech?.stop("길 안내가 우선이에요.")
            return
        }
        val navigationPriority = currentSnapshot.navigationVoiceActive == true && !emulatorMode
        val interruptedEntry = localPrompt?.kind == "region-entry" && regionEntry.active?.speechId == activeSpeechId &&
            (state.output == null || state.output?.id != activeSpeechId || !HyundaehwaEngine.view(state).canSpeak)
        val interruptedAnswer = localPrompt?.kind == "answer" && answerSpeech.unfinished(pinnedCard?.id) &&
            (state.output == null || state.output?.id != activeSpeechId || !HyundaehwaEngine.view(state).canSpeak)
        if (voiceState.purpose == VoicePurpose.PROMPT && (state.guards.isNotEmpty() || navigationPriority || interruptedAnswer || interruptedEntry)) {
            voiceState.requestId?.let { rememberInterruptedPrompt(it, speech?.remainingText.orEmpty()) }
            speech?.stop("길 안내가 우선이에요. 안전 구간에서 이야기를 이어갈게요.")
            return
        }
        if (navigationPriority) { speech?.stop("내비게이션 음성 안내가 우선이라 멈췄어요."); return }
        if (voiceState.purpose == VoicePurpose.TEST || voiceState.purpose == VoicePurpose.WELCOME) {
            voiceTestBlockReason()?.let { speech?.stop(it) }
        } else if (state.output == null || state.output?.id != activeSpeechId || !HyundaehwaEngine.view(state).canSpeak) {
            speech?.stop("운전 집중 또는 안내 중단 조건으로 음성을 멈췄어요.")
        }
    }
    private fun maybeSpeakSafetyNotice() {
        if (state.guards.isEmpty()) { safetyNoticeIssued = false; return }
        if (safetyNoticeIssued || !resumed || !voiceEnabled || !state.enabled || state.quiet || state.mediaActive != false ||
            voiceState.active || answerVoiceState.active || talkState.active || relationshipVoice?.state?.active == true ||
            speech?.audioFocusBlocked == true || (!emulatorMode && currentSnapshot.navigationVoiceActive == true)) return
        // A missing maneuver signal can hold content, but it is not proof of a hazard.
        val now = SystemClock.elapsedRealtime()
        val verifiedHazard = emulatorMode || (currentSnapshot.maneuverUpdatedAtMs?.let { now - it in 0..8_000 } == true &&
            (currentSnapshot.nearManeuver == true || currentSnapshot.tunnel == true))
        if (!verifiedHazard) return
        safetyNoticeIssued = true
        speech?.speak(safetyNoticeId, "운전에 집중해야 하는 구간이에요. 안전 모드로 진입합니다.", VoicePurpose.WELCOME)
    }
    private fun voiceTestBlockReason(): String? = if (speech?.audioFocusBlocked == true) "다른 음성 안내가 끝나기를 기다리고 있어요." else if (!HyundaehwaEngine.audioClear(state)) "음악이나 라디오 재생이 끝난 뒤 다시 시도해 주세요." else VoiceTestPolicy.blockReason(VoiceTestConditions(
        resumed = resumed, parked = canConfigure(), quiet = state.quiet, guards = state.guards,
        mediaActive = state.mediaActive != false, navigationVoiceActive = !emulatorMode && currentSnapshot.navigationVoiceActive == true
    ))
    private fun regionalCards(area: AdministrativeConversationArea): List<AdministrativeConversationTopic> {
        val bank = administrativeCatalog?.available(area, activeAudience, effectiveDifficulty).orEmpty()
        return bank.filter { it.format in setOf("quiz", "balance") }
    }

    private fun currentTopicContext(topicType: String? = null): GleoTopicContext? {
        val snapshot = currentSnapshot
        if (regionEntry.blocksContent || pinnedCard != null || state.regionId in state.presentedRegionIds || emulatorMode) return null
        val age = snapshot.locationUpdatedAtMs?.let { SystemClock.elapsedRealtime() - it }
        if (age == null || age !in 0..8_000) return null
        val catalog = administrativeCatalog ?: return null
        val area = catalog.areaAt(snapshot.longitude, snapshot.latitude)?.takeIf { it.id == state.regionId } ?: return null
        val cards = regionalCards(area).filter { it.id !in state.usedTopicIds }
        val key = if (FourContentRotation.enabled(state.relationship)) FourContentRotation.next(
            state.rotationRemaining, state.lastRotationKey, cards.map { it.contentRotationKey }.toSet()) else "quiz"
        val selected = cards.firstOrNull { it.contentRotationKey == key } ?: return null
        return catalog.selection(area, selected)?.gleoContext()
    }
    private fun answerVoiceConditions(cardId: String, allowInterruptedExplanation: Boolean = false) = CardAnswerVoiceConditions(
        resumed = resumed, enabled = state.enabled,
        cardOpen = pinnedCard?.id == cardId && (allowInterruptedExplanation || (!answerVisible && !state.arrived)),
        gear = state.gear, quiet = state.quiet, guards = state.guards, mediaActive = state.mediaActive,
        navigationVoiceActive = !emulatorMode && currentSnapshot.navigationVoiceActive == true,
        promptVoiceActive = voiceState.active || state.speechActive || SystemClock.elapsedRealtime() - lastLocalVoiceEndedAt < lastLocalVoiceEchoDelayMs,
        explanationHeld = state.answerExplanationHeld && !allowInterruptedExplanation,
        otherInputOpen = relationshipVoice != null || talkState.active || settingsOpen || parkedDialog != null || emulatorTab)
    private fun answerVoiceBlockReason(cardId: String): String? {
        if (cardId == "active-rest" || cardId == "active-rest-confirm") {
            val matchesStage = if (cardId == "active-rest") restVoiceStage == "choose" else restVoiceStage == "confirm"
            return CardAnswerVoicePolicy.blockReason(CardAnswerVoiceConditions(resumed, state.enabled,
                state.restDue && state.restAnnounced && matchesStage, state.gear, state.quiet, state.guards,
                state.mediaActive, currentSnapshot.navigationVoiceActive == true,
                voiceState.active || state.speechActive || SystemClock.elapsedRealtime() - lastLocalVoiceEndedAt < lastLocalVoiceEchoDelayMs,
                false, relationshipVoice != null || talkState.active || settingsOpen || parkedDialog != null))
        }
        if (answerVoiceState.active && state.destination != answerVoiceTargetDestination) return "목적지가 바뀌어 이전 질문의 음성 답변을 멈췄어요."
        return CardAnswerVoicePolicy.blockReason(answerVoiceConditions(cardId))
    }
    private fun onAnswerVoiceChanged(next: CardAnswerVoiceState) {
        answerVoiceState = next
        syncCabinMonitor()
        if (!next.active && state.answerListening) finishAnswerListening()
        if (next.cardId == "active-rest" || next.cardId == "active-rest-confirm") {
            val resolvedRestChoice = next.answer?.choiceId
            if (next.phase == CardAnswerVoicePhase.RESOLVED && resolvedRestChoice != null) {
                if (next.cardId == "active-rest") chooseSpokenRest(resolvedRestChoice, next.transcript)
                else finishSpokenRestConfirmation(resolvedRestChoice, next.transcript)
            } else if (!next.active && next.phase in setOf(CardAnswerVoicePhase.UNMATCHED, CardAnswerVoicePhase.EMPTY_TIMEOUT, CardAnswerVoicePhase.FAILED, CardAnswerVoicePhase.CANCELLED)) {
                restVoiceReady = false
                if (next.retryWhenSafe) { restVoiceReady = true; restVoiceAttempted = false }
            }
            render(force = true); return
        }
        val matches = pinnedCard?.id == next.cardId && !answerVisible
        val recognizedAnswer = next.answer
        if (matches && next.phase == CardAnswerVoicePhase.RESOLVED && recognizedAnswer != null) {
            choiceCardId = next.cardId; selectedChoiceId = recognizedAnswer.choiceId; heardChoice = next.transcript
            answerScrollPending = true; revealPinned()
        } else if (matches && next.phase == CardAnswerVoicePhase.EMPTY_TIMEOUT) revealPinned(timedOut = true)
        else if (matches && next.phase in setOf(CardAnswerVoicePhase.FAILED, CardAnswerVoicePhase.UNMATCHED, CardAnswerVoicePhase.CANCELLED)) {
            pinnedShownAt = SystemClock.elapsedRealtime()
            if (next.retryWhenSafe && answerVoiceOptIn) answerVoiceAttemptedCardId = null
        }
        render(force = true)
    }
    private fun finishAnswerListening() {
        state = HyundaehwaEngine.reduce(state, ConversationEvent.AnswerListeningChanged(false))
    }
    private fun startAnswerVoice(card: AdministrativeConversationTopic, interruptPrompt: Boolean = false): Boolean {
        if (!answerVoiceOptIn || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (interruptPrompt && voiceState.active && voiceState.purpose == VoicePurpose.PROMPT &&
            localPrompt?.topicId == card.id && !answerVisible) {
            CardAnswerVoicePolicy.blockReason(answerVoiceConditions(card.id).copy(promptVoiceActive = false))?.let { toast(it); return false }
            answerVoiceAttemptedCardId = card.id // suppress auto-listen during the stop callback
            clearPromptResume()
            speech?.stop("답변을 듣기 위해 질문을 멈췄어요.")
            answerVoiceQuestionReady = true
            handler.postDelayed({ if (pinnedCard?.id == card.id && !answerVisible) startAnswerVoice(card) }, 700)
            return true
        }
        answerVoiceBlockReason(card.id)?.let { if (interruptPrompt) toast(it); return false }
        answerVoiceAttemptedCardId = card.id; answerVoiceTargetDestination = state.destination
        state = HyundaehwaEngine.reduce(state, ConversationEvent.AnswerListeningChanged(true))
        answerVoice?.listen(card)
        return true
    }
    private fun maybeStartAnswerVoice() {
        val card = pinnedCard ?: return
        if (!answerVoiceOptIn || !answerVoiceQuestionReady || answerVisible || answerVoiceState.active ||
            answerVoiceAttemptedCardId == card.id || !resumed) return
        if (answerVoiceBlockReason(card.id) == null) startAnswerVoice(card)
    }
    private fun syncCabinMonitor() {
        val monitor = cabinMonitor ?: return
        monitor.setRecognitionActive(answerVoiceState.active || relationshipVoice?.state?.active == true || talkState.active)
        monitor.setOwnSpeechActive(voiceState.active)
        monitor.setEnabled(resumed && !emulatorMode && cabinSoundOptIn &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    private fun configureAnswerVoice(forceEnable: Boolean = false) {
        if (answerVoiceOptIn && !forceEnable) {
            answerVoiceOptIn = false; cabinSoundOptIn = false; answerVoice?.clear(); syncCabinMonitor()
            dispatch(ConversationEvent.SoundUnavailable("마이크 사용 꺼짐"))
            if (!emulatorMode) getSharedPreferences("conversation_profile", MODE_PRIVATE).edit().putBoolean("on_device_card_answers", false).putBoolean("cabin_sound_monitor", false).apply()
            render(force = true); return
        }
        if (!emulatorMode && !canConfigure()) { toast("P 정차 후 음성 답변을 설정해 주세요."); return }
        getSharedPreferences("conversation_profile", MODE_PRIVATE).edit().putBoolean("audio_setup_seen", true).apply()
        val notice = AlertDialog.Builder(this).setTitle("음성 답변과 침묵 감지를 사용할까요?")
            .setMessage("마이크로 소리 크기를 확인해 조용한 시간이 이어지면 화두를 건네고, 질문 뒤에는 기기 내 음성 인식으로 답변을 들어요. 소리 크기 측정은 앱을 사용하는 동안에만 작동하며 음성 파일을 저장하지 않아요. 기기 내 음성 인식이 제공되지 않거나 소리 입력을 확인할 수 없으면 자동 기능을 보류해요. 설정에서 언제든 끌 수 있어요.")
            .setPositiveButton("음성 답변 사용") { _, _ ->
                if (emulatorMode || canConfigure()) {
                    answerVoiceOptIn = true; cabinSoundOptIn = true; answerVoiceAttemptedCardId = null
                    if (!emulatorMode) getSharedPreferences("conversation_profile", MODE_PRIVATE).edit().putBoolean("on_device_card_answers", true).putBoolean("cabin_sound_monitor", true).apply()
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        answerVoicePermissionPending = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 14)
                    }
                    syncCabinMonitor(); render(force = true)
                }
            }.setNegativeButton("화면으로 답하기", null).create()
        parkedDialog = notice
        notice.setOnDismissListener { if (parkedDialog === notice) parkedDialog = null }
        notice.show()
    }
    private fun currentAnswerCard(): AdministrativeConversationTopic? {
        if (answerVisible || state.quiet || (state.restDue && state.restAnnounced) || state.arrived) return null
        return pinnedCard
    }
    private fun refreshRegionalTopics() {
        // Development inputs do not represent fresh SDK coordinates; no synthetic location in real mode.
        val snapshot = currentSnapshot
        val age = snapshot.locationUpdatedAtMs?.let { SystemClock.elapsedRealtime() - it }
        val area = if (emulatorMode) administrativeCatalog?.areaById(state.regionId)
            else if (age != null && age in 0..8_000) administrativeCatalog?.areaAt(snapshot.longitude, snapshot.latitude)
            else null
        val available = area?.let { found -> regionalCards(found).map { it.engineTopic(found.id) } }.orEmpty()
        state = HyundaehwaEngine.reduce(state, ConversationEvent.RegionalTopicsChanged(area?.id.orEmpty(), available))
        clearStaleGleoCard()
    }
    private fun clearGleoCard() {
        gleoStructuredCard = null; gleoLastTopic = ""; gleoLastTopicFormat = ""; gleoLastTopicSource = ""
        gleoCardRegionId = null; gleoCardAudience = null; gleoCardDifficulty = null
        choiceCardId = ""; selectedChoiceId = null; heardChoice = null; answerVisible = false
    }
    private fun clearStaleGleoCard() {
        // The displayed card is independent of live location and preference changes.
    }
    private fun playEntryWelcome() {
        if (!welcomePending || emulatorMode || voiceState.active || talkState.active || state.output != null) return
        if (!voiceEnabled) { welcomePending = false; return }
        if (voiceTestBlockReason() != null) {
            // Do not let unavailable audio prevent the parked manual chooser forever.
            if (canConfigure()) {
                if (welcomeParkedSince == 0L) welcomeParkedSince = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - welcomeParkedSince >= 3_000) welcomePending = false
            } else welcomeParkedSince = 0L
            return
        }
        welcomeParkedSince = 0L
        // Consume before calling the backend: asynchronous failure must not loop or interrupt other audio.
        welcomePending = false
        speech?.speak("entry-welcome-${++voiceTestSequence}", entryWelcome, VoicePurpose.WELCOME)
    }
    private fun maybeShowInitialPassengerPrompt() {
        // Seat occupancy is only context; a person must explicitly choose the relationship.
        // A missing occupancy signal must not prevent manual selection while parked.
        if (!resumed || emulatorMode || automaticPassengerPromptShown || relationshipChosen || !canConfigure() ||
            welcomePending || voiceState.active || parkedDialog != null || settingsOpen) return
        automaticPassengerPromptShown = true
        showPassengerDialog()
    }
    private fun startVoiceTest() {
        discardAnswerSpeech()
        clearPromptResume()
        voiceTestBlockReason()?.let { toast(it); return }
        talk?.cancel("음성 확인으로 전환했어요.")
        speech?.stop("음성 확인으로 전환했어요.")
        activeSpeechId = null
        state = HyundaehwaEngine.reduce(state, ConversationEvent.OutputDone())
        speech?.speak("voice-test-${++voiceTestSequence}", "안녕하세요. 현대화입니다. 이 목소리가 들리면 음성 안내를 사용할 수 있어요.", VoicePurpose.TEST)
    }
    private fun renderVoiceStatus(container: LinearLayout) {
        container.addView(text("한국어 음성 · ${voiceState.phase.label}", 13f, if (voiceState.phase == VoicePhase.FAILED) Color.rgb(165, 62, 49) else teal, true).apply { setPadding(0, dp(14), 0, dp(7)) })
        container.addView(text("정차 중 고정 인사말로 스피커 재생을 확인해요.", 11f, muted))
        container.addView(text(if (voiceState.engine.isBlank()) "정차 후 버튼을 누르면 음성 엔진을 확인해요." else "${voiceState.source.label}\n${voiceState.engine}", 10f, muted))
        voiceState.fallbackReason?.let { container.addView(text("음성 엔진 연결이 어려워 다른 로컬 음성을 사용해요.\n$it", 10f, muted).apply { setPadding(0, dp(7), 0, 0) }) }
        voiceState.error?.let { container.addView(text(it, 12f, muted).apply { setPadding(0, dp(7), 0, 0) }) }
        if (voiceState.phase == VoicePhase.COMPLETED) container.addView(text("음성 엔진의 재생 완료 신호를 받았어요. 실제 소리는 기기 음량도 확인해 주세요.", 11f, muted).apply { setPadding(0, dp(7), 0, 0) })
        container.addView(button(if (voiceState.active && voiceState.purpose == VoicePurpose.TEST) "음성 확인 중지" else if (voiceState.phase == VoicePhase.FAILED || voiceState.phase == VoicePhase.CANCELLED) "다시 음성 확인" else "한국어 음성 듣기", selectedFill, teal) { if (voiceState.active && voiceState.purpose == VoicePurpose.TEST) speech?.stop("음성 확인을 멈췄어요.") else startVoiceTest() }.apply { isEnabled = voiceTestBlockReason() == null || (voiceState.active && voiceState.purpose == VoicePurpose.TEST) })
        voiceTestBlockReason()?.let { container.addView(text(it, 10f, muted)) }
    }
    private fun renderGleo(container: LinearLayout) {
        // The vehicle owns navigation and its voice assistant. Only the Hyundaihwa panel is drawn here.
        if (state.topic != null && HyundaehwaEngine.view(state).status in setOf("topic", "speaking")) return
        val view = HyundaehwaEngine.view(state)
        if (view.status == "rest") return
        val bankCard = gleoStructuredCard
        // Parked touch answers do not require a current turn-by-turn instruction or microphone.
        if (bankCard != null && gleoCardRegionId == state.regionId && gleoLastTopic.isNotBlank() && canConfigure() && !state.quiet) {
            renderBankCard(container, bankCard)
            return
        }
        if (view.status in setOf("arrival", "focus", "media-hold", "media-unknown", "quiet", "sound-unavailable")) {
            val hold = column().apply { setPadding(dp(24), dp(22), dp(24), dp(22)); background = messageBackground() }
            hold.addView(text(view.statusTitle, 26f, teal, true))
            hold.addView(text(view.statusDetail, 17f, muted).apply { setPadding(0, dp(12), 0, 0) })
            container.addView(hold)
            return
        }
        if (bankCard != null && gleoCardRegionId == state.regionId && gleoLastTopic.isNotBlank()) {
            renderBankCard(container, bankCard)
            return
        }
        val card = column().apply { setPadding(dp(24), dp(22), dp(24), dp(22)); background = messageBackground() }
        card.addView(text("현대화", 14f, teal, true))
        card.addView(text(if (gleoLastTopic.isNotBlank()) gleoLastTopic else if (state.quiet) "조용히 함께 갈게요" else "지금 지나는 곳에서 이야기를 시작해요", 26f, ink, true).apply { setPadding(0, dp(12), 0, dp(6)); setLineSpacing(dp(6).toFloat(), 1f) })
        if (gleoLastTopic.isNotBlank()) card.addView(text(gleoLastTopicFormat, 13f, teal, true))
        container.addView(card)
        if (!state.enabled && relationshipChosen) container.addView(button("대화 안내 시작", teal, Color.WHITE) {
            parked { dispatch(ConversationEvent.Start) }
        }.apply { isEnabled = canConfigure() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        if (!answerVoiceOptIn && relationshipChosen && canConfigure()) container.addView(button("음성 답변과 침묵 감지 켜기", selectedFill, teal) {
            configureAnswerVoice()
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
    }
    private fun capabilityRow(container: LinearLayout, icon: String, title: String, detail: String) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        line.addView(text(icon, 21f, muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), dp(44)))
        val labels = column().apply { setPadding(dp(8), 0, 0, 0) }
        labels.addView(text(title, 16f, ink, true))
        labels.addView(text(detail, 14f, muted).apply { setPadding(0, dp(4), 0, 0) })
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(line)
        container.addView(View(this).apply { setBackgroundColor(Color.rgb(224, 226, 228)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }
    private fun renderTalk(container: LinearLayout) {
        if (!(BuildConfig.DEBUG && BuildConfig.ENABLE_GEMINI_RELAY)) { renderGleo(container); return }
        container.addView(text(entryWelcome, 19f, ink, true).apply { setPadding(0, 0, 0, dp(16)) })
        val card = column().apply { setPadding(dp(18), dp(14), dp(18), dp(16)); background = rounded(cream, 8) }
        card.addView(text("말로 나누는 대화", 12f, teal, true))
        card.addView(text(talkState.phase.label, 25f, ink, true).apply { setPadding(0, dp(9), 0, dp(8)) })
        if (talkState.transcript.isNotBlank()) {
            card.addView(text("내가 한 말", 10f, muted))
            card.addView(text(talkState.transcript, 16f, ink).apply { setPadding(0, dp(5), 0, dp(12)); setTextIsSelectable(true) })
        }
        if (talkState.reply.isNotBlank()) {
            card.addView(text("현대화", 10f, teal, true))
            card.addView(text(talkState.reply, 21f, ink).apply { setPadding(0, dp(6), 0, dp(12)); setLineSpacing(dp(6).toFloat(), 1f); setTextIsSelectable(true) })
        }
        talkState.error?.let { card.addView(text(it, 12f, muted).apply { setPadding(0, 0, 0, dp(10)) }) }
        val actions = row()
        actions.addView(button(if (talkState.active) "대화 중지" else "말 걸기", teal, Color.WHITE) {
            if (talkState.active) talk?.cancel() else beginTalk(false)
        }.apply { isEnabled = talkState.active || voiceTestBlockReason() == null }, LinearLayout.LayoutParams(0, dp(53), 1f))
        actions.addView(button("직접 입력", Color.WHITE, teal) { beginTalk(true) }.apply { isEnabled = voiceTestBlockReason() == null }, LinearLayout.LayoutParams(0, dp(53), 1f).apply { leftMargin = dp(8) })
        card.addView(actions)
        if (talkState.history.isNotEmpty() || talkState.transcript.isNotBlank()) card.addView(button("대화 지우기", Color.TRANSPARENT, muted) { talk?.clear() })
        card.addView(text(if (!relaySettings.valid) "서버 연결 후 사용할 수 있어요. 음성은 기기 내 한국어 엔진을 사용해요." else if (talkState.source.isBlank()) "한 번 누르고 말하면 답해요. 이어 말할 때 다시 눌러 주세요." else talkState.source, 10f, muted).apply { setPadding(0, dp(8), 0, 0) })
        if (!talkState.active) voiceTestBlockReason()?.let { card.addView(text(it, 10f, muted)) }
        container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun beginTalk(typed: Boolean) {
        if (!(BuildConfig.DEBUG && BuildConfig.ENABLE_GEMINI_RELAY)) return
        voiceTestBlockReason()?.let { toast(it); return }
        if (!relaySettings.valid) { configureRelay(); return }
        val proceed = {
            if (voiceTestBlockReason() == null) {
                if (typed) showTypedTalk()
                else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startTalkListening()
                else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 12)
            }
        }
        if (talkConsent) proceed()
        else AlertDialog.Builder(this).setTitle("말로 이야기해 볼까요?")
            .setMessage("말씀은 기기 내 한국어 음성 인식으로 글자로 바꿔요. 인식한 문장과 최근 대화, 현재 지역·목적지·동승 관계를 연결한 서버와 Gemini에 보내 답변을 받아요. 음성 파일은 보내거나 저장하지 않아요. 대화는 화면을 벗어나면 지워요. Gemini 무료 등급의 데이터 처리 정책이 적용돼요.")
            .setPositiveButton("동의하고 시작") { _, _ -> if (voiceTestBlockReason() == null) { talkConsent = true; proceed() } }
            .setNegativeButton("취소", null).show()
    }
    private fun startTalkListening() {
        discardAnswerSpeech()
        clearPromptResume()
        if (voiceTestBlockReason() != null) return
        speech?.stop("말씀을 듣기 위해 음성을 멈췄어요.")
        state = HyundaehwaEngine.reduce(state, ConversationEvent.OutputDone())
        talk?.listen()
    }
    private fun showTypedTalk() {
        val input = EditText(this).apply { hint = "예: 오늘 좀 지쳤어. 잠깐 이야기하자."; setTextColor(ink); maxLines = 4; setSaveEnabled(false); filters = arrayOf(android.text.InputFilter.LengthFilter(1000)) }
        val dialog = AlertDialog.Builder(this).setTitle("현대화에게 말하기").setView(input)
            .setPositiveButton("보내기", null).setNegativeButton("취소", null).create()
        parkedDialog = dialog
        dialog.setOnDismissListener { if (parkedDialog === dialog) parkedDialog = null }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            voiceTestBlockReason()?.let { toast(it); dialog.dismiss(); return@setOnClickListener }
            val message = input.text.toString().trim()
            if (message.isBlank()) { input.error = "내용을 입력해 주세요."; return@setOnClickListener }
            discardAnswerSpeech(); clearPromptResume(); speech?.stop("새 질문을 준비해요."); state = HyundaehwaEngine.reduce(state, ConversationEvent.OutputDone())
            talk?.submit(message); dialog.dismiss()
        } }; dialog.show()
    }
    private fun configureRelay() {
        if (!(BuildConfig.DEBUG && BuildConfig.ENABLE_GEMINI_RELAY)) return
        if (!canConfigure()) { toast("연결은 P 정차 후 설정해 주세요."); return }
        val form = column().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        form.addView(text("Gemini API 키 대신, 실행한 대화 서버 주소와 연결 코드를 입력해요. 키는 서버에만 보관해요.", 12f, muted))
        val url = EditText(this).apply { hint = "https://대화서버주소"; setText(relaySettings.url); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; maxLines = 1 }
        val token = EditText(this).apply { hint = "연결 코드 · 24자 이상"; setText(relaySettings.token); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; maxLines = 1; setSaveEnabled(false) }
        form.addView(url); form.addView(token)
        form.addView(text("기기 내 음성에는 별도 음성 크레딧을 쓰지 않아요. Gemini는 연결한 키의 요금제와 한도가 적용돼요.", 11f, muted))
        val dialog = AlertDialog.Builder(this).setTitle("대화 서버 연결").setView(form).setPositiveButton("저장", null).setNegativeButton("취소", null)
            .setNeutralButton("연결 지우기") { _, _ -> if (canConfigure()) { talk?.clear(); relayStore.clear(); relaySettings = RelayConnection(); talkConsent = false; render(force = true) } }.create()
        parkedDialog = dialog
        dialog.setOnDismissListener { if (parkedDialog === dialog) parkedDialog = null }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!canConfigure()) { toast("P 정차 상태를 확인해 주세요."); dialog.dismiss(); return@setOnClickListener }
            val endpoint = TalkRelayEndpoint.validate(url.text.toString(), BuildConfig.DEBUG)
            val next = RelayConnection(endpoint.orEmpty(), token.text.toString().trim())
            if (!next.valid) { toast("HTTPS 주소와 24자 이상의 연결 코드를 확인해 주세요."); return@setOnClickListener }
            if (!relayStore.save(next)) { toast("보안 저장소에 연결 정보를 저장하지 못했어요."); return@setOnClickListener }
            talk?.clear(); relaySettings = next; talkConsent = false; render(force = true); dialog.dismiss()
        } }; dialog.show()
    }
    private fun parked(action: () -> Unit) {
        if (!canConfigure()) toast("설정은 P 정차 후 바꿀 수 있어요.") else action()
    }
    private fun buildShell() {
        val compactShell = resources.configuration.screenWidthDp < 600
        // The host owns window placement and the OEM map; this view is only the conversation panel.
        root = column().apply { setPadding(0, 0, 0, 0); setBackgroundColor(Color.rgb(245, 249, 250)) }
        setContentView(root)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(12), dp(10)); setBackgroundColor(teal) }
        header.addView(conversationMark(if (compactShell) 40 else 48))
        val brand = column().apply { setPadding(dp(if (compactShell) 9 else 13), 0, dp(if (compactShell) 10 else 22), 0) }
        brand.addView(text("현대화", 20f, Color.WHITE, true))
        brand.addView(text("차량 대화 도우미", 12f, selectedFill).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        headerStatus = text("— · 차량 연결 대기", 12f, selectedFill).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        headerStatus.visibility = View.GONE
        header.addView(headerStatus)
        quietButton = button("조용히 가기", teal, Color.WHITE) { dispatch(ConversationEvent.QuietChanged(!state.quiet)) }
        header.addView(button("⚙ 설정", Color.TRANSPARENT, Color.WHITE) {
            parked { settingsOpen = true; render(force = true) }
        }.apply { contentDescription = "설정 열기"; minWidth = 0; minimumWidth = 0 })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        passengerStatus = text(ConversationPresentation.passengerLabel(null), 18f, teal, true).apply {
            setPadding(dp(16), dp(9), dp(16), dp(3)); setBackgroundColor(Color.TRANSPARENT)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        // A persistent shell element: cards, focus/quiet mode and narrow settings never hide it.
        root.addView(passengerStatus, LinearLayout.LayoutParams(-1, -2))
        activityStatusTextView = text("차량 상태를 확인하고 있어요", 14f, muted).apply {
            setPadding(dp(16), dp(3), dp(16), dp(6)); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = text
            setOnClickListener {
                if ((!cabinSoundOptIn || !answerVoiceOptIn || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) && canConfigure()) configureAnswerVoice(forceEnable = true)
            }
        }
        root.addView(activityStatusTextView, LinearLayout.LayoutParams(-1, -2))
        destinationAction = text("목적지 설정 · 순정 내비 열기  ›", 13f, teal, true).apply {
            setPadding(dp(16), dp(5), dp(16), dp(8)); maxLines = 1
            setOnClickListener {
                parked {
                    if (checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf("pleos.car.permission.NAVI_CUSTOM_MAP"), 16)
                    else if (bridge?.openNavigation() != true) toast("내비에 연결하지 못했어요.")
                }
            }
        }
        root.addView(destinationAction, LinearLayout.LayoutParams(-1, -2))
        demoPlaybackControls = column().apply { visibility = View.GONE }
        demoPlaybackLabel = text("시연 주행 배속 · ${demoPlaybackSpeed}x", 13f, teal, true).apply { setPadding(dp(10), 0, 0, dp(5)) }
        demoPlaybackControls.addView(demoPlaybackLabel)
        val playbackSpeeds = row()
        DemoPlaybackClock.speeds.forEach { speed ->
            val control = button("${speed}x", selectedFill, teal) {
                // This only changes an explicitly enabled developer clock. It never sends a vehicle command.
                if (BuildConfig.DEBUG && emulatorMode) { demoPlaybackSpeed = speed; render() }
            }.apply { minWidth = 0; minimumWidth = 0; textSize = 14f }
            demoPlaybackButtons[speed] = control
            playbackSpeeds.addView(control, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        }
        demoPlaybackControls.addView(playbackSpeeds)
        root.addView(demoPlaybackControls, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        body = row().apply { gravity = Gravity.TOP }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        composer = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        composerInput = EditText(this).apply {
            hint = "현대화에게 답하기"; setSingleLine(true); setTextColor(ink); setHintTextColor(muted); typeface = appFont
            textSize = 16f; setPadding(dp(14), 0, dp(14), 0); background = rounded(selectedFill, 12)
            setSaveEnabled(false); filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { submitComposer(); true } else false
            }
        }
        composer.addView(composerInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        composer.addView(button("보내기", teal, Color.WHITE) { submitComposer() }, LinearLayout.LayoutParams(dp(76), dp(48)).apply { leftMargin = dp(6) })
        composer.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_composer_mic); background = buttonBackground(selectedFill)
            contentDescription = "음성으로 답하기"; scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { composerVoice() }
        }, LinearLayout.LayoutParams(dp(56), dp(48)).apply { leftMargin = dp(6) })
        root.addView(composer.apply { setPadding(dp(14), dp(6), dp(14), dp(6)) })
        root.addView(quietButton.apply { textSize = 15f }, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(14), 0, dp(14), dp(12)) })
        val footer = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        footerStatus = text("차량 신호 연결 대기", 11f, muted)
        footer.addView(footerStatus, LinearLayout.LayoutParams(0, -2, 1f))
        if (BuildConfig.DEBUG) footer.addView(button("개발자 도구", Color.TRANSPARENT, muted) { if (canOpenEmulator()) { emulatorTab = true; render(force = true) } else toast("개발자 도구는 정차 후 열 수 있어요.") })
        guidanceButton = button("화두 안내 켜기", Color.TRANSPARENT, teal) { if (state.enabled) dispatch(ConversationEvent.Stop) else parked { dispatch(ConversationEvent.Start) } }
        footer.addView(guidanceButton)
        footer.visibility = View.GONE
        root.addView(footer)
    }
    /** Updates the small status independently of card rendering, so time changes never rebuild a card. */
    private fun updateActivityStatus() {
        if (!::activityStatusTextView.isInitialized) return
        val permissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val needsAudioSetup = !cabinSoundOptIn || !answerVoiceOptIn || !permissionGranted
        val observed = state.soundObservedAtMs
        val soundFresh = observed != null && state.wallElapsedMs - observed in 0..ConversationLimits.SOUND_FRESH_MS
        val level = state.rms
        val status = when {
            !relationshipChosen -> "출발 전 동승 관계를 선택해 주세요"
            !state.enabled -> "대화 안내가 꺼져 있어요 · 설정에서 켜기"
            state.arrived -> "목적지에 도착했어요"
            state.quiet -> "조용히 가는 중"
            state.guards.isNotEmpty() && !emulatorMode && currentSnapshot.nearManeuver == null && currentSnapshot.tunnel != true -> "경로 안내 확인 중 · 화두 대기"
            state.guards.isNotEmpty() -> "안전 모드 · 운전에 집중해 주세요"
            needsAudioSetup && canConfigure() -> "자동 대화 꺼짐 · 눌러서 켜기"
            needsAudioSetup -> "자동 대화 꺼짐 · 정차 후 켤 수 있어요"
            canConfigure() -> "출발 대기 · 준비가 되었어요"
            state.mediaActive == null -> "미디어 상태 확인 중 · 화두 대기"
            state.mediaActive == true -> "음악·라디오 재생 중 · 화두 대기"
            state.restDue && state.restAnnounced -> "휴식 장소 선택을 기다리고 있어요"
            answerVoiceState.active || state.answerListening -> "답변을 듣고 있어요"
            voiceState.active || state.speechActive -> "이야기를 들려드리고 있어요"
            (state.topic != null || pinnedCard != null) && !answerVisible -> "답변을 기다리고 있어요"
            answerVisible -> "답변을 확인했어요"
            !soundFresh || level == null -> "소리 입력 확인 중 · 자동 대화 대기"
            level >= ConversationLimits.LOW_RMS -> "차 안 소리 감지 중 · 자동 대화 대기"
            state.gear != Gear.D || state.speedKph <= 0.0 -> "주행 상태 확인 중"
            else -> {
                val seconds = (state.silenceMs / 1_000L).coerceAtLeast(0)
                val thresholdMinutes = state.silenceThresholdMs / 60_000L
                "조용한 시간 %d:%02d / %d분".format(seconds / 60, seconds % 60, thresholdMinutes)
            }
        }
        activityStatusTextView.text = status
        activityStatusTextView.contentDescription = status
        activityStatusTextView.isClickable = status.startsWith("자동 대화 꺼짐") && canConfigure()
        activityStatusTextView.setTextColor(if (activityStatusTextView.isClickable) teal else muted)
    }
    private fun render(force: Boolean = false) {
        if (!::body.isInitialized) return
        if (!canConfigure()) parkedDialog?.dismiss()
        val view = HyundaehwaEngine.view(state)
        val awaitingGuidance = !emulatorMode && state.enabled && view.status == "focus" && currentSnapshot.nearManeuver == null
        val title = if (awaitingGuidance) "경로 안내 확인 중" else view.statusTitle
        val detail = if (awaitingGuidance) "다음 안내 구간을 확인한 뒤 화두를 건네요." else view.statusDetail
        val destinationLabel = if (emulatorMode) state.destination else currentSnapshot.destination?.takeIf { it.isNotBlank() } ?: "목적지 없음"
        val areaLabel = if (emulatorMode) state.regionLabel else {
            val age = currentSnapshot.locationUpdatedAtMs?.let { SystemClock.elapsedRealtime() - it }
            if (age != null && age in 0..8_000) administrativeCatalog?.areaAt(currentSnapshot.longitude, currentSnapshot.latitude)?.label ?: "위치 미확인" else "위치 미확인"
        }
        val restrictedScreen = !emulatorMode && (!currentSnapshot.vehicleConnected || state.gear == Gear.UNKNOWN || currentSnapshot.uxAllowsSetup != true)
        val connectionLabel = if (!currentSnapshot.vehicleConnected || state.gear == Gear.UNKNOWN) "차량 신호 확인 중" else "차량 연결됨"
        val narrow = resources.configuration.screenWidthDp < 770
        if (!canConfigure() && !emulatorMode && settingsOpen) {
            settingsOpen = false
            val expiredSettings = settingsDialog; settingsDialog = null; settingsHolder = null
            expiredSettings?.dismiss()
        }
        val key = listOf(cabinSoundOptIn, state.rms == null, promptResume.pending?.utterance?.id, promptResume.failureHeld, restrictedScreen, currentSnapshot.vehicleConnected, currentSnapshot.uxAllowsSetup, currentSnapshot.navigationConnected, currentSnapshot.navigationScreenStatus, regionEntry.visual, regionEntry.active, regionEntry.failure, regionEntry.blocksContent, answerVoiceState, answerVoiceOptIn, state.answerListening, answerSpeech.current, state.answerExplanationHeld, weatherConsent, weatherCard, narrow, realRestChoice, restStatus, restHeard, if (state.restDue) aheadFacilities().map { it.entrance.id to (it.meters / 100).toInt() } else emptyList(), gleoLastTopic, gleoLastTopicFormat, gleoLastTopicSource, gleoRequestStatus, gleoCallbackSent, talkState, relaySettings.valid, voiceState, state.guards, state.mediaActive, resumed, destinationLabel, areaLabel, view.status, view.statusTitle, state.topic?.id, state.relationship, state.scenario, state.gear, state.frontOccupied, state.rearCount, state.quiet, state.restSelection, state.restCommitted, state.enabled, emulatorTab, emulatorMode, voiceEnabled, manualExternalMedia, platformStatus, answerVisible, relationshipChosen, choiceCardId, selectedChoiceId, heardChoice, unansweredReveal, passengerProfile, difficultyMode, settingsOpen, profileDetailsOpen).joinToString("|")
        quietButton.visibility = if (restrictedScreen) View.GONE else View.VISIBLE
        guidanceButton.visibility = if (restrictedScreen) View.GONE else View.VISIBLE
        passengerStatus.visibility = if (restrictedScreen) View.GONE else View.VISIBLE
        activityStatusTextView.visibility = if (restrictedScreen) View.GONE else View.VISIBLE
        destinationAction.visibility = if (!restrictedScreen && !emulatorMode && currentSnapshot.destination.isNullOrBlank() && canConfigure()) View.VISIBLE else View.GONE
        updateActivityStatus()
        quietButton.text = if (state.quiet) "같이 이야기 나누기" else "조용히 가기"
        quietButton.minWidth = dp(56); quietButton.minimumWidth = dp(56)
        val speaker = AppCompatResources.getDrawable(this, if (state.quiet) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off)?.mutate()
        speaker?.setTint(Color.WHITE); speaker?.setBounds(0, 0, dp(22), dp(22))
        quietButton.setCompoundDrawables(speaker, null, null, null)
        quietButton.compoundDrawablePadding = dp(9)
        quietButton.contentDescription = if (state.quiet) "조용히 가는 중. 누르면 같이 이야기 나눠요" else "조용히 가기. 화두 안내를 잠시 멈춰요"
        guidanceButton.text = if (state.enabled) "화두 안내 끄기" else "화두 안내 켜기"
        guidanceButton.isEnabled = state.enabled || canConfigure()
        passengerStatus.text = ConversationPresentation.passengerLabel(state.relationship.takeIf { relationshipChosen })
        passengerStatus.contentDescription = passengerStatus.text
        demoPlaybackControls.visibility = if (BuildConfig.DEBUG && emulatorMode) View.VISIBLE else View.GONE
        demoPlaybackLabel.text = "시연 주행 배속 · ${demoPlaybackSpeed}x"
        demoPlaybackButtons.forEach { (speed, control) ->
            val selected = speed == demoPlaybackSpeed
            control.background = buttonBackground(if (selected) teal else selectedFill)
            control.setTextColor(if (selected) Color.WHITE else teal)
            control.contentDescription = "시연 주행 배속 ${speed}배${if (selected) " · 선택됨" else ""}"
        }
        headerStatus.text = "${if (state.gear != Gear.UNKNOWN) state.gear.name else "—"} · ${if (emulatorMode) "개발자 도구" else connectionLabel}"
        footerStatus.text = if (emulatorMode) "● 시연 신호 · 실제 차량·위치·시간 정보가 아니에요" else "● $connectionLabel"
        if (!force && key == lastRendered) return
        lastRendered = key; body.removeAllViews()
        composer.visibility = if (emulatorTab || restrictedScreen) View.GONE else View.VISIBLE
        if (restrictedScreen) {
            composerInput.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(composerInput.windowToken, 0)
            val screen = column().apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); background = rounded(Color.rgb(14, 40, 74), 8) }
            screen.addView(text(if (currentSnapshot.uxAllowsSetup == false) "운전에 집중해 주세요" else "차량 화면 이용 확인 중", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            screen.addView(text("안전할 때 다시 화면을 이용해 주세요.", 16f, selectedFill).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
            if (currentSnapshot.vehicleConnected && currentSnapshot.uxAllowsSetup == true && !hasCarSpeedPermission()) {
                screen.addView(text("안전한 정차 상태를 확인하려면 차량 속도 접근 권한이 필요해요.", 16f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0) })
                screen.addView(button("차량 속도 권한 허용", selectedFill, ink) {
                    if (currentSnapshot.vehicleConnected && currentSnapshot.uxAllowsSetup == true && !hasCarSpeedPermission()) requestPermissions(arrayOf(carSpeedPermission), 15)
                })
            }
            body.addView(screen, LinearLayout.LayoutParams(-1, -1))
            return
        }
        if (emulatorTab) { buildEmulatorPanel(); return }
        val main = column().apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(Color.rgb(245, 249, 250)) }
        main.addView(text(if (areaLabel == "위치 미확인") "현재 위치를 확인하고 있어요." else "${areaLabel} 부근을 지나고 있어요.", 14f, muted).apply { setPadding(0, dp(4), 0, dp(12)) })
        regionEntry.visual?.takeIf { it.regionId == state.regionId }?.let { entry ->
            main.addView(text(entry.message, 17f, Color.WHITE, true).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10)); background = rounded(selectedFill, 14); setTextColor(ink)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            if (regionEntry.failure != null) {
                main.addView(button("지역 안내 다시 듣기", Color.TRANSPARENT, Color.WHITE) {
                    if (emulatorMode || canConfigure()) {
                        val heldId = promptResume.pending?.utterance?.id?.takeIf { promptResume.failureHeld }
                        if (heldId != null && regionEntry.resumeFailedCheckpoint(heldId)) promptResume.retryAfterFailure(heldId)
                        else regionEntry.retry()
                        spokenOutput = null; playPendingOutput(); render(force = true)
                    }
                }.apply { isEnabled = emulatorMode || canConfigure() })
            }
        }
        val heldPrompt = promptResume.pending?.takeIf { promptResume.failureHeld && it.utterance.kind !in setOf("region-entry", "answer") }
        if (heldPrompt != null) main.addView(button("안내 다시 듣기", selectedFill, ink) {
            if ((emulatorMode || canConfigure()) && promptResume.retryAfterFailure(heldPrompt.utterance.id)) {
                spokenOutput = null; playPendingOutput(); render(force = true)
            }
        }.apply { isEnabled = emulatorMode || canConfigure(); minHeight = dp(52); minimumHeight = dp(52) })
        val content = column()
        val restoreScrollY = conversationScrollY
        answerBubbleView = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true; addView(content)
            setOnScrollChangeListener { _, _, y, _, _ -> conversationScrollY = y }
        }
        main.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val compactStatus = ConversationPresentation.compact(state, canConfigure(), awaitingGuidance)
        if (compactStatus != null && (pinnedCard == null || state.guards.isNotEmpty())) {
            renderCompactStatus(content, compactStatus)
            body.addView(main, LinearLayout.LayoutParams(-1, -1))
            return
        }
        if (weatherCard.isNotBlank() && state.enabled && !state.quiet && !state.restDue && !state.arrived && state.guards.isEmpty() && state.topic == null) {
            val box = column().apply { setPadding(dp(20), dp(18), dp(20), dp(18)); background = messageBackground() }
            box.addView(text("목적지 날씨", 14f, teal, true)); box.addView(text(weatherCard, 23f, ink, true))
            box.addView(text("도착 시각 예보 · MET Norway", 12f, muted))
            content.addView(box)
        } else renderTalk(content)
        if (state.arrived && !state.answerExplanationHeld) {
            val arrival = column().apply { setPadding(dp(24), dp(24), dp(24), dp(24)); background = messageBackground() }
            arrival.addView(text("목적지에 도착했어요", 28f, teal, true))
            arrival.addView(text("함께해 주셔서 고마워요. 즐거운 시간 보내세요.", 20f, ink).apply { setPadding(0, dp(12), 0, 0) })
            content.addView(arrival)
        }
        else if (view.status == "rest") renderRest(content)
        else if (pinnedCard != null) renderBankCard(content, pinnedCard!!)
        else if (talkState.active || talkState.transcript.isNotBlank() || talkState.error != null) { /* The real conversation owns this card until cleared. */ }
        else if (state.topic != null && (view.status == "topic" || view.status == "speaking")) renderTopic(content)
        else if (cabinSoundOptIn && state.rms == null && state.enabled && !state.arrived) {
            content.addView(text("소리 입력을 확인하고 있어요", 24f, teal, true).apply { setPadding(dp(20), dp(18), dp(20), dp(10)); background = messageBackground() })
            content.addView(text("입력을 확인할 수 없으면 침묵을 판단하거나 자동으로 화두를 건네지 않아요.", 16f, muted).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) })
        }
        else if ((BuildConfig.DEBUG && BuildConfig.ENABLE_GEMINI_RELAY)) {
            content.addView(conversationMark(108).apply { setPadding(0, dp(14), 0, dp(26)) })
            content.addView(text(title, 38f, ink, true).apply { setLineSpacing(dp(8).toFloat(), 1f) })
            content.addView(text(detail, 17f, muted).apply { setPadding(0, dp(20), 0, dp(18)); setLineSpacing(dp(7).toFloat(), 1f) })
            if (!state.enabled) content.addView(button("대화 안내 시작", teal, navy) { dispatch(ConversationEvent.Start) })
            if (state.quiet) content.addView(button("다시 이야기 나누기", teal, navy) { dispatch(ConversationEvent.QuietChanged(false)) })
            content.addView(button(if (voiceState.active && voiceState.purpose == VoicePurpose.TEST) "음성 확인 중지" else "한국어 음성 듣기", Color.TRANSPARENT, teal) { if (voiceState.active && voiceState.purpose == VoicePurpose.TEST) speech?.stop("음성 확인을 멈췄어요.") else startVoiceTest() }.apply { isEnabled = voiceTestBlockReason() == null || (voiceState.active && voiceState.purpose == VoicePurpose.TEST) })
        }
        body.addView(main, LinearLayout.LayoutParams(-1, -1))
        scroll.post {
            if (scroll.isAttachedToWindow) {
                val target = answerBubbleView
                if (answerScrollPending && target != null) {
                    scroll.smoothScrollTo(0, (target.top - dp(16)).coerceAtLeast(0))
                    answerScrollPending = false
                } else scroll.scrollTo(0, restoreScrollY)
            }
        }
        if (!settingsOpen) {
            val closedSettings = settingsDialog; settingsDialog = null; settingsHolder = null
            closedSettings?.dismiss()
            return
        }
        val side = column().apply { setPadding(dp(20), dp(20), dp(20), dp(15)); background = rounded(Color.WHITE, 8) }
        val sideContent = column(); val sideScroll = ScrollView(this).apply { addView(sideContent) }
        side.addView(sideScroll, LinearLayout.LayoutParams(-1, -1))
        sideContent.addView(button("설정 닫기", selectedFill, teal) { settingsOpen = false; render(force = true) }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(14) })
        sideContent.addView(text("차량 및 내비게이션 상태", 14f, muted).apply { setPadding(0, dp(8), 0, dp(8)) })
        sideContent.addView(text(if (currentSnapshot.navigationConnected) "순정 내비 연결됨" else "순정 내비 연결 확인 중", 14f, ink))
        sideContent.addView(button("순정 내비 열기", selectedFill, teal) {
            if (checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf("pleos.car.permission.NAVI_CUSTOM_MAP"), 16)
            else if (bridge?.openNavigation() != true) toast("내비에 연결하지 못했어요.")
        }.apply { isEnabled = canConfigure() && currentSnapshot.navigationConnected })
        sideContent.addView(button(if (state.enabled) "화두 안내 끄기" else "화두 안내 켜기", selectedFill, teal) {
            if (state.enabled) dispatch(ConversationEvent.Stop) else parked { dispatch(ConversationEvent.Start) }
        }.apply { isEnabled = state.enabled || canConfigure() })
        sideContent.addView(button("동승자 선택 · ${state.relationship.label}", selectedFill, teal) { parked { showPassengerDialog() } }.apply { isEnabled = canConfigure() })
        val childSelected = passengerProfile.youngest == AgeBand.CHILD
        sideContent.addView(button(if (childSelected) "어린이도 함께 타요 · 선택됨" else "어린이도 함께 타요",
            if (childSelected) teal else selectedFill, if (childSelected) Color.WHITE else teal) {
            parked {
                passengerProfile = passengerProfile.copy(youngest = if (childSelected) AgeBand.UNKNOWN else AgeBand.CHILD)
                saveProfile(); dispatch(ConversationEvent.PreferencesChanged); refreshRegionalTopics(); render(force = true)
            }
        }.apply { isEnabled = canConfigure() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        sideContent.addView(text("침묵 감지 시간", 17f, teal, true).apply { setPadding(0, dp(16), 0, dp(6)) })
        val silenceRow = row()
        listOf(1, 3, 5, 10, 15).forEach { minutes ->
            silenceRow.addView(button("${minutes}분", if (silenceMinutes == minutes) teal else Color.WHITE,
                if (silenceMinutes == minutes) Color.WHITE else ink) {
                parked {
                    silenceMinutes = minutes; saveProfile()
                    dispatch(ConversationEvent.SilenceThresholdChanged(minutes * 60_000L))
                    render(force = true)
                }
            }.apply { isEnabled = canConfigure(); textSize = 14f }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        sideContent.addView(silenceRow)
        sideContent.addView(text("기본 5분 · 소리 입력을 확인할 수 없으면 자동 제안을 보류해요.", 12f, muted))
        sideContent.addView(text("퀴즈 난이도", 17f, teal, true).apply { setPadding(0, dp(16), 0, dp(6)) })
        val difficultyRow = row()
        DifficultyMode.entries.forEach { mode -> difficultyRow.addView(button(mode.label,
            if (difficultyMode == mode) teal else Color.WHITE, if (difficultyMode == mode) Color.WHITE else ink) {
            parked { difficultyMode = mode; saveProfile(); dispatch(ConversationEvent.PreferencesChanged); refreshRegionalTopics(); render(force = true) }
        }.apply { isEnabled = canConfigure() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) }) }
        sideContent.addView(difficultyRow)
        sideContent.addView(text(if (difficultyMode == DifficultyMode.AUTO)
            "자동 · 현재 ${QuestionDifficulty.label(effectiveDifficulty)} 난이도" else "선택한 ${QuestionDifficulty.label(effectiveDifficulty)} 난이도로 안내해요", 14f, teal, true).apply { setPadding(0, dp(10), 0, dp(4)) })
        sideContent.addView(button(if (profileDetailsOpen) "동승자 세부 정보 접기  −" else "동승자 세부 정보  +", Color.TRANSPARENT, muted) {
            profileDetailsOpen = !profileDetailsOpen; render(force = true)
        })
        if (profileDetailsOpen) {
            sideContent.addView(text("직접 입력한 연령과 퀴즈 취향으로 자동 난이도를 정해요. 좌석 센서로 나이를 추정하지 않아요.", 12f, muted).apply { setPadding(0, dp(4), 0, dp(8)) })
        sideContent.addView(text("가장 어린 동승자 · 직접 선택", 12f, muted).apply { setPadding(0, dp(12), 0, dp(4)) })
        val ageRow = row()
        AgeBand.entries.forEach { age -> ageRow.addView(button(age.label,
            if (passengerProfile.youngest == age) teal else Color.WHITE, if (passengerProfile.youngest == age) Color.WHITE else ink) {
            parked { passengerProfile = passengerProfile.copy(youngest = age); saveProfile(); dispatch(ConversationEvent.PreferencesChanged); refreshRegionalTopics(); render(force = true) }
        }.apply { isEnabled = canConfigure() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) }) }
        sideContent.addView(ageRow)
        sideContent.addView(text("퀴즈 취향 · 직접 선택", 12f, muted).apply { setPadding(0, dp(12), 0, dp(4)) })
        val preferenceRow = row()
        QuizPreference.entries.forEach { preference -> preferenceRow.addView(button(preference.label,
            if (passengerProfile.preference == preference) teal else Color.WHITE, if (passengerProfile.preference == preference) Color.WHITE else ink) {
            parked { passengerProfile = passengerProfile.copy(preference = preference); saveProfile(); dispatch(ConversationEvent.PreferencesChanged); refreshRegionalTopics(); render(force = true) }
        }.apply { isEnabled = canConfigure() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) }) }
        sideContent.addView(preferenceRow)
        }
        if (!emulatorMode) sideContent.addView(button(if (weatherConsent) "목적지 날씨 켜짐" else "목적지 날씨 사용", selectedFill, teal) {
            parked {
                fun setConsent(value: Boolean) {
                    weatherConsent = value; clearWeather()
                    getSharedPreferences("conversation_profile", MODE_PRIVATE).edit().putBoolean("weather", value).apply(); render(force = true)
                }
                if (weatherConsent) setConsent(false) else {
                    val notice = AlertDialog.Builder(this).setTitle("목적지 날씨를 받을까요?")
                        .setMessage("목적지 예보를 위해 목적지 좌표를 소수점 한 자리로 줄여 MET Norway에 보내요. 서비스에 IP 주소와 반올림한 좌표가 전달됩니다. 예보의 기온을 반올림해 옷차림 안내로 전해요. 자료: MET Norway · CC BY 4.0 (creativecommons.org/licenses/by/4.0/)")
                        .setPositiveButton("동의하고 켜기") { _, _ -> if (canConfigure()) setConsent(true) }.setNegativeButton("취소", null).create()
                    parkedDialog = notice; notice.show()
                }
            }
        }.apply { isEnabled = canConfigure() })
        sideContent.addView(text("현재 지역", 14f, muted).apply { setPadding(0, dp(17), 0, dp(6)) })
        sideContent.addView(text(areaLabel, 17f, ink, true))
        val currentAgeMs = currentSnapshot.locationUpdatedAtMs?.let { SystemClock.elapsedRealtime() - it }
        val topicArea = if (emulatorMode) administrativeCatalog?.areaById(state.regionId)
            else if (currentAgeMs != null && currentAgeMs in 0..8_000) administrativeCatalog?.areaAt(currentSnapshot.longitude, currentSnapshot.latitude) else null
        if (topicArea != null && administrativeCatalog?.available(topicArea, activeAudience, effectiveDifficulty).orEmpty().none { it.format == "quiz" }) {
            sideContent.addView(text("이 지역의 ${QuestionDifficulty.label(effectiveDifficulty)} 난이도 퀴즈는 준비 중이에요.", 11f, muted).apply { setPadding(0, dp(6), 0, 0) })
        }
        sideContent.addView(text("목적지", 14f, muted).apply { setPadding(0, dp(18), 0, dp(6)) })
        sideContent.addView(button(destinationLabel + "  ›", selectedFill, teal) { showDestinationDialog(destinationLabel) })
        if (!emulatorMode) {
            sideContent.addView(text("차량 라디오 등은 자동으로 확인되지 않을 수 있어요. 재생 중에는 직접 켜 주세요.", 11f, muted))
            sideContent.addView(button(if (manualExternalMedia) "외부 음악·라디오 재생 중 · 보류 켜짐" else "외부 음악·라디오 재생 중 · 보류 꺼짐", selectedFill, teal) {
                parked {
                    manualExternalMedia = !manualExternalMedia
                    getSharedPreferences("conversation_profile", MODE_PRIVATE).edit().putBoolean("external_media_hold", manualExternalMedia).apply()
                    receivePlatform(currentSnapshot)
                    render(force = true)
                }
            }.apply { isEnabled = canConfigure() })
        }
        sideContent.addView(button(if (answerVoiceOptIn) "기기 내 음성 답변 켜짐 · 끄기" else "기기 내 음성 답변 사용", selectedFill, teal) { configureAnswerVoice() })
        sideContent.addView(button(if (voiceEnabled) "음성 안내 켜짐" else "음성 안내 켜기", Color.TRANSPARENT, muted) { voiceEnabled = !voiceEnabled; if (!emulatorMode) getSharedPreferences("conversation_profile", MODE_PRIVATE).edit().putBoolean("spoken_guidance", voiceEnabled).apply(); if (!voiceEnabled) { answerSpeech.cancel(); clearPromptResume(); speech?.stop(); dispatch(ConversationEvent.SpeechChanged(false)) }; render(force = true); playPendingOutput() })
        if ((BuildConfig.DEBUG && BuildConfig.ENABLE_GEMINI_RELAY)) sideContent.addView(button(if (relaySettings.valid) "대화 서버 설정됨 · 설정" else "대화 서버 연결 설정", selectedFill, teal) { parked { configureRelay() } }.apply { isEnabled = canConfigure() })
        renderVoiceStatus(sideContent)
        if (BuildConfig.DEBUG) sideContent.addView(button("개발자 도구", Color.TRANSPARENT, muted) {
            if (canOpenEmulator()) { settingsOpen = false; emulatorTab = true; render(force = true) }
            else toast("개발자 도구는 정차 후 열 수 있어요.")
        })

        val holder = settingsHolder ?: android.widget.FrameLayout(this).also { newHolder ->
            settingsHolder = newHolder
            val settings = AlertDialog.Builder(this).setCustomTitle(dialogHeader("설정"))
                .setView(newHolder).create()
            settingsDialog = settings
            settings.setOnDismissListener {
                if (settingsDialog === settings) {
                    settingsDialog = null; settingsHolder = null; settingsOpen = false
                    if (resumed && !isFinishing) render(force = true)
                }
            }
            settings.show()
            settings.window?.setLayout((resources.displayMetrics.widthPixels * .9f).toInt().coerceAtMost(dp(620)), (resources.displayMetrics.heightPixels * .85f).toInt())
        }
        holder.removeAllViews(); holder.addView(side, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    private fun showPassengerDialog() {
        if (!canConfigure()) return
        val choices = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this).setCustomTitle(dialogHeader("누구와 함께 가세요?")).setView(ScrollView(this).apply { addView(choices) }).setNegativeButton("닫기", null).create()
        val status = text("말로 선택을 누른 뒤 동승자 관계를 말씀해 주세요.", 15f, teal).apply {
            setPadding(0, dp(6), 0, dp(10)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val voiceButton = button("말로 선택", selectedFill, teal) { }
        fun select(relation: Relationship) {
            if (!canConfigure()) { status.text = "P 정차 상태에서 다시 선택해 주세요."; return }
            relationshipChosen = true; dispatch(ConversationEvent.RelationshipChanged(relation))
            refreshRegionalTopics(); dispatch(ConversationEvent.Start); render(force = true); dialog.dismiss()
            handler.post {
                if (resumed && canConfigure() && parkedDialog == null) {
                    if (voiceEnabled && voiceTestBlockReason() == null) {
                        val confirmation = if (relation == Relationship.SOLO) "혼자 운전하는 대화형 모드입니다." else "동승자 유형은 ${relation.label}입니다."
                        speech?.speak("relationship-confirm-${++voiceTestSequence}", confirmation, VoicePurpose.WELCOME)
                    }
                    if (!answerVoiceOptIn && !getSharedPreferences("conversation_profile", MODE_PRIVATE).getBoolean("audio_setup_seen", false)) configureAnswerVoice()
                }
            }
        }
        val chooserVoice = RelationshipVoiceInput(this, {
            when {
                !resumed || parkedDialog !== dialog || !dialog.isShowing -> "선택 화면을 다시 열어 주세요."
                !canConfigure() -> "P 정차 상태에서만 말로 선택할 수 있어요."
                else -> null
            }
        }, { next ->
            if (parkedDialog === dialog && dialog.isShowing) {
                voiceButton.text = if (next.active) "음성 선택 중지" else "다시 말로 선택"
                status.text = when (next.phase) {
                    RelationshipVoicePhase.PREPARING -> "마이크 연결 중이에요. 잠시 기다려 주세요."
                    RelationshipVoicePhase.LISTENING -> "듣고 있어요. 가족·친구·연인·동료·상사·처음 만난 사이·혼자 중 하나를 말해 주세요." +
                        next.transcript.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                    RelationshipVoicePhase.FAILED -> next.error.orEmpty() + next.transcript.takeIf { it.isNotBlank() }?.let { "\n들은 말: $it" }.orEmpty()
                    RelationshipVoicePhase.CANCELLED -> "음성 선택을 멈췄어요. 아래 버튼에서도 고를 수 있어요."
                    RelationshipVoicePhase.RESOLVED -> "${next.relationship?.label}로 선택했어요."
                    else -> "말로 선택하거나 아래 버튼에서 골라 주세요."
                }
                if (next.phase == RelationshipVoicePhase.RESOLVED) next.relationship?.let(::select)
            }
        })
        relationshipVoice?.close(); relationshipVoice = chooserVoice
        val startVoice = {
            if (parkedDialog === dialog && dialog.isShowing && resumed && canConfigure()) {
                if (localPrompt?.kind == "answer") activeSpeechId?.let { rememberInterruptedPrompt(it, speech?.remainingText.orEmpty()) }
                else clearPromptResume()
                talk?.cancel("동승자를 선택하고 있어요.")
                speech?.stop("동승자 관계를 듣기 위해 안내를 멈췄어요.")
                handler.postDelayed({
                    if (parkedDialog === dialog && dialog.isShowing && resumed && canConfigure() &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) chooserVoice.listen()
                }, 700)
            }
        }
        voiceButton.setOnClickListener {
            if (chooserVoice.state.active) chooserVoice.cancel()
            else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
            else {
                status.text = "마이크 권한을 허용하면 말로 선택할 수 있어요. 아래 버튼은 권한 없이 사용할 수 있어요."
                pendingRelationshipVoiceStart = startVoice
                relationshipPermissionInFlight = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 13)
            }
        }
        relationshipPermissionFeedback = { message -> if (dialog.isShowing && parkedDialog === dialog) status.text = message }
        choices.addView(voiceButton); choices.addView(status)
        choices.addView(text("예: 친구랑 가고 있어요 · 남자친구랑 가요 · 혼자예요", 12f, muted).apply { setPadding(0, 0, 0, dp(8)) })
        Relationship.entries.chunked(2).forEach { group ->
            val line = row()
            group.forEach { relation ->
                line.addView(button(relation.label, if (relationshipChosen && state.relationship == relation) teal else selectedFill,
                    if (relationshipChosen && state.relationship == relation) Color.WHITE else ink) { chooserVoice.cancel(); select(relation) },
                    LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(0, 0, dp(6), dp(6)) })
            }
            choices.addView(line)
        }
        choices.addView(text("말로 선택할 때만 기기 내 한국어 음성 인식을 사용해요. 음성 파일이나 인식한 문장을 서버에 보내거나 저장하지 않아요. 어린이 동승과 난이도는 설정에서 바꿀 수 있어요.", 12f, muted))
        parkedDialog = dialog
        dialog.setOnDismissListener {
            pendingRelationshipVoiceStart = null; relationshipPermissionFeedback = null; chooserVoice.close()
            if (relationshipVoice === chooserVoice) relationshipVoice = null
            if (parkedDialog === dialog) parkedDialog = null
        }
        dialog.show()
    }
    private fun submitComposer() {
        val message = composerInput.text.toString().trim()
        if (message.isBlank()) { toast("내용을 입력해 주세요."); return }
        if (!canConfigure()) { toast("화면 입력은 P 정차 후 사용할 수 있어요."); return }
        val card = currentAnswerCard()
        val answer = card?.let { SpokenCardAnswerParser.parse(message, it) }
        if (card == null || answer == null) {
            toast(if (card == null) "현재 입력할 질문이 없어요. 자유 대화 연결은 준비 중이에요." else "보기의 번호나 이름으로 답해 주세요. 자유 대화 연결은 준비 중이에요.")
            return
        }
        choiceCardId = card.id; selectedChoiceId = answer.choiceId; heardChoice = message
        answerScrollPending = true; revealPinned(); composerInput.text.clear(); render(force = true)
    }
    private fun composerVoice() {
        val card = currentAnswerCard()
        if (card == null) { toast("현재 음성으로 답할 질문이 없어요. 차량 음성 버튼으로 화두를 요청해 주세요."); return }
        if (!answerVoiceOptIn) configureAnswerVoice()
        else if (answerVoiceState.active) answerVoice?.cancel("음성 답변을 멈췄어요. 화면에서도 고를 수 있어요.")
        else { answerVoiceAttemptedCardId = null; startAnswerVoice(card, interruptPrompt = true) }
    }
    private fun showDestinationDialog(destination: String) {
        AlertDialog.Builder(this).setTitle("목적지")
            .setMessage("현재 목적지: $destination\n\n목적지 검색과 변경은 차량의 순정 내비게이션에서 해주세요. 이 앱에서는 목적지를 직접 변경할 수 없어요.")
            .setPositiveButton("확인", null).show()
    }
    private var lastAutoCommittedOutput: String? = null
    private fun renderTopic(content: LinearLayout) {
        val topic = state.topic ?: return
        val area = administrativeCatalog?.areaById(topic.regionId)
        val bankCard = pinnedCard ?: DestinationConversation.activeCard(state) ?: area?.let {
            regionalCards(it).firstOrNull { card -> card.id == topic.id }
        }
        if (bankCard != null) { pinCard(bankCard, topic.regionId); renderBankCard(content, bankCard) }
        else {
            val card = column().apply { setPadding(dp(25), dp(24), dp(25), dp(17)); background = messageBackground() }
            card.addView(text("현대화", 14f, teal, true))
            card.addView(text(topic.text, if (resources.configuration.screenWidthDp < 600) 22f else 26f, ink, true).apply { setPadding(0, dp(22), 0, dp(22)); setLineSpacing(dp(7).toFloat(), 1f) })
            content.addView(card)
        }
        // A visible automatic card and a successfully delivered Gleo callback
        // update the same ledger; a guarded or merely prepared item does not.
        val output = state.output
        if (output?.topicId == topic.id && output.id != lastAutoCommittedOutput && resumed) {
            if (administrativeCatalog?.recordPresentedById(topic.regionId, topic.id, activeAudience, effectiveDifficulty) == true) {
                state = HyundaehwaEngine.reduce(state, ConversationEvent.LocalTopicDisplayed(topic.id))
                lastAutoCommittedOutput = output.id
            }
        }
    }
    private fun renderBankCard(content: LinearLayout, item: AdministrativeConversationTopic) {
        if (choiceCardId != item.id) { choiceCardId = item.id; selectedChoiceId = null; heardChoice = null; answerVisible = false; unansweredReveal = false }
        val card = column().apply { setPadding(dp(25), dp(24), dp(25), dp(20)); background = messageBackground() }
        val name = when {
            item.sourceKind == DestinationConversation.SOURCE -> "목적지 이야기"
            item.format == "quiz" -> "위치 기반 퀴즈 · ${QuestionDifficulty.label(item.difficulty)}"
            item.format == "balance" -> BalancePresentation.TITLE
            else -> "이곳의 이야기"
        }
        card.addView(text(name, 14f, teal, true))
        card.addView(text(if (item.format == "balance") BalancePresentation.questionBody(item.question) else item.question, if (resources.configuration.screenWidthDp < 600) 22f else 26f, ink, true).apply { setPadding(0, dp(16), 0, dp(16)); setLineSpacing(dp(7).toFloat(), 1f) })
        if (!answerVisible) {
            val input = answerVoiceState.takeIf { it.cardId == item.id }
            val listeningLabel = when (input?.phase) {
                CardAnswerVoicePhase.PREPARING -> "답변 마이크 연결 중이에요."
                CardAnswerVoicePhase.LISTENING -> "답변을 듣고 있어요. 보기의 번호나 이름으로 말해 주세요."
                CardAnswerVoicePhase.UNMATCHED, CardAnswerVoicePhase.FAILED, CardAnswerVoicePhase.CANCELLED -> input.error.orEmpty()
                else -> if (answerVoiceOptIn) "질문 음성이 끝나면 기기 내에서 답변을 들어요." else "음성 답변은 별도 설정 후 사용할 수 있어요."
            }
            card.addView(text(listeningLabel, 14f, teal).apply { setPadding(0, 0, 0, dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
            input?.transcript?.takeIf { it.isNotBlank() }?.let { heard -> card.addView(text("들은 말: $heard", 15f, muted).apply { setPadding(0, 0, 0, dp(8)) }) }
            card.addView(button(if (input?.active == true) "음성 답변 중지" else if (answerVoiceOptIn) "말로 답하기 · 다시 듣기" else "기기 내 음성 답변 켜기", selectedFill, teal) {
                if (!answerVoiceOptIn) configureAnswerVoice()
                else if (input?.active == true) answerVoice?.cancel("음성 답변을 멈췄어요. 화면에서도 고를 수 있어요.")
                else { answerVoiceAttemptedCardId = null; startAnswerVoice(item, interruptPrompt = true) }
            })
        }
        item.options.forEachIndexed { index, choice ->
            val selected = selectedChoiceId == choice.id
            card.addView(button("${index + 1}  ${choice.label}", if (selected) teal else Color.WHITE, if (selected) Color.WHITE else ink) {
                parked {
                    selectedChoiceId = choice.id; heardChoice = null; answerScrollPending = true; revealPinned()
                    render(force = true)
                }
            }.apply { isEnabled = canConfigure() && !answerVisible; textSize = 20f; minHeight = dp(64); minimumHeight = dp(64); gravity = Gravity.CENTER_VERTICAL or Gravity.START }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        if (item.format == "quiz" && selectedChoiceId == null && !answerVisible) {
            card.addView(button("정답 보기", Color.TRANSPARENT, teal) { parked {
                revealPinned()
                render(force = true)
            } }.apply { isEnabled = canConfigure() }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        revealMessage(card)
        content.addView(card)
        CardAnswer.userMessage(item.options, selectedChoiceId, heardChoice)?.let { message ->
            val response = text(message, 20f, Color.WHITE, true).apply {
                setPadding(dp(20), dp(16), dp(20), dp(16)); background = rounded(teal, 22)
                contentDescription = "내 답변: $message"
            }
            answerBubbleView = response
            content.addView(response, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(14) })
        }
        if (answerVisible) {
            val reply = AnswerExplanation.text(item, selectedChoiceId, unansweredReveal)
            if (!reply.isNullOrBlank()) content.addView(text(reply, 22f, ink).apply { setPadding(dp(20), dp(16), dp(20), dp(16)); background = messageBackground(); setLineSpacing(dp(5).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            answerSpeech.current?.takeIf { it.cardId == item.id }?.let { explanation ->
                val status = when (explanation.phase) {
                    AnswerSpeechPhase.WAITING, AnswerSpeechPhase.PAUSED -> "해설을 이어 읽을 수 있는 순간을 기다리고 있어요."
                    AnswerSpeechPhase.PLAYING -> if (voiceState.phase == VoicePhase.PREPARING) "전체 해설 음성을 준비하고 있어요." else "전체 해설을 읽고 있어요."
                    AnswerSpeechPhase.COMPLETED -> "전체 해설을 들려드렸어요."
                    AnswerSpeechPhase.FAILED -> voiceState.error ?: "해설 음성을 재생하지 못했어요. 다시 들을 수 있어요."
                    AnswerSpeechPhase.CANCELLED -> "해설 음성 안내를 멈췄어요."
                }
                content.addView(text(status, 13f, muted).apply { setPadding(0, dp(10), 0, dp(4)) })
                if (explanation.phase in setOf(AnswerSpeechPhase.FAILED, AnswerSpeechPhase.COMPLETED, AnswerSpeechPhase.CANCELLED)) {
                    content.addView(button("해설 다시 듣기", selectedFill, teal) {
                        if (pinnedCard?.id == item.id && explanation.destination == state.destination && (emulatorMode || canConfigure()) && voiceEnabled && !state.quiet && answerSpeech.retry()) {
                            promptResume.retryAfterFailure(explanation.id)
                            state = state.copy(answerExplanationHeld = true); revealedAt = SystemClock.elapsedRealtime()
                            playPendingOutput(); render(force = true)
                        }
                    }.apply { isEnabled = explanation.destination == state.destination && (emulatorMode || canConfigure()) && voiceEnabled && !state.quiet })
                }
            }

        }
    }
    private fun aheadFacilities(): List<AheadFacility> {
        val s = currentSnapshot
        val now = SystemClock.elapsedRealtime()
        if (s.routeActive != true || s.destinationArrived == true || s.maneuverUpdatedAtMs?.let { now - it in 0..8_000 } != true) return emptyList()
        return s.upcomingFacilities
    }
    private fun restConfirmationCard(): AdministrativeConversationTopic = AdministrativeConversationTopic(
        "active-rest-confirm", "rest", "경유 확인", "선택한 휴게소를 경유할까요?",
        listOf(AdministrativeConversationOption("yes", "네", listOf("예", "맞아요", "네 해주세요", "예 해주세요", "네 추가해 주세요", "네 경유해 주세요", "좋아요", "경유해 주세요", "추가해 주세요")),
            AdministrativeConversationOption("no", "아니요", listOf("아니", "괜찮아요", "취소", "안 갈래요"))))
    private fun maybeStartRestVoice() {
        if (emulatorMode || !answerVoiceOptIn || !restVoiceReady || restVoiceAttempted || answerVoiceState.active || !resumed ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val card = when (restVoiceStage) { "choose" -> realRestCard(); "confirm" -> restConfirmationCard(); else -> null } ?: return
        if (answerVoiceBlockReason(card.id) != null) return
        restVoiceAttempted = true
        state = HyundaehwaEngine.reduce(state, ConversationEvent.AnswerListeningChanged(true))
        answerVoice?.listen(card)
    }
    private fun chooseSpokenRest(id: String, heard: String?) {
        val entrance = aheadFacilities().take(2).firstOrNull { it.entrance.id == id }?.entrance ?: return
        realRestChoice = entrance.id; restHeard = heard?.takeIf { it.isNotBlank() } ?: entrance.label
        restVoiceStage = "confirm"; restVoiceReady = false; restVoiceAttempted = false
        val promptId = "rest-confirm-${++restVoiceSequence}"
        state = state.copy(output = Utterance(promptId, "rest-confirm", "${entrance.label}를 경유할까요?"), speechActive = false)
        spokenOutput = null
        render(force = true); playPendingOutput()
    }
    private fun finishSpokenRestConfirmation(choiceId: String, heard: String?) {
        restVoiceReady = false; restVoiceAttempted = true
        restConfirmationHeard = heard?.takeIf { it.isNotBlank() }
        if (choiceId != "yes") {
            realRestChoice = null; restVoiceStage = ""
            render(force = true); return
        }
        val normalized = heard.orEmpty().lowercase().replace(Regex("[^가-힣a-z0-9]"), "")
        val explicitYes = normalized in setOf("네", "예", "네해주세요", "예해주세요", "네추가해주세요", "네경유해주세요", "좋아요", "경유해주세요", "추가해주세요", "맞아요")
        if (!explicitYes) {
            restStatus = WaypointResult(WaypointResultStatus.REJECTED, "확실한 동의를 확인하지 못해 경유지를 추가하지 않았어요.")
            render(force = true); return
        }
        val entrance = aheadFacilities().take(2).firstOrNull { it.entrance.id == realRestChoice }?.entrance
        if (entrance == null || answerVoiceBlockReason("active-rest-confirm") != null) {
            restStatus = WaypointResult(WaypointResultStatus.REJECTED, "경로와 주행 상태를 확인할 수 없어 경유지를 추가하지 못했어요.")
            render(force = true); return
        }
        restVoiceStage = ""
        requestRestWaypoint(entrance)
    }
    private fun maybeSpeakRestStatus() {
        val message = pendingRestStatusSpeech ?: return
        if (!resumed || !voiceEnabled || state.quiet || state.guards.isNotEmpty() || state.mediaActive != false ||
            currentSnapshot.navigationVoiceActive == true || state.output != null || voiceState.active || answerVoiceState.active) return
        pendingRestStatusSpeech = null
        state = state.copy(output = Utterance("rest-status-${++restVoiceSequence}", "rest-status", message), speechActive = false)
        spokenOutput = null; playPendingOutput()
    }
    private fun requestRestWaypoint(entrance: FacilityEntrance) {
        bridge?.addWaypoint(WaypointRequest(entrance.latitude, entrance.longitude, entrance.label, "",
            userConfirmed = true, verifiedEntrance = true)) { result ->
            restStatus = result
            pendingRestStatusSpeech = when (result.status) {
                WaypointResultStatus.CONFIRMED -> "경유지가 내비게이션에 추가된 것을 확인했어요."
                WaypointResultStatus.REQUESTED -> "경유지 추가를 요청했어요. 적용 여부를 확인하고 있어요."
                WaypointResultStatus.UNCONFIRMED -> "경유지 추가를 요청했지만 적용 여부는 확인하지 못했어요. 내비게이션에서 확인해 주세요."
                WaypointResultStatus.REJECTED, WaypointResultStatus.FAILED -> "경유지를 추가하지 못했어요. ${result.message}"
            }
            if (!isDestroyed) { render(force = true); maybeSpeakRestStatus() }
        } ?: run {
            restStatus = WaypointResult(WaypointResultStatus.FAILED, "내비게이션 연결을 확인할 수 없어요.")
            pendingRestStatusSpeech = "내비게이션 연결을 확인할 수 없어 경유지를 추가하지 못했어요."
            render(force = true); maybeSpeakRestStatus()
        }
    }
    private fun realRestCard(): AdministrativeConversationTopic? {
        if (restStatus?.status in setOf(WaypointResultStatus.REQUESTED, WaypointResultStatus.CONFIRMED)) return null
        val choices = aheadFacilities().take(2).map { AdministrativeConversationOption(it.entrance.id, it.entrance.label, listOf(it.entrance.label.removeSuffix("휴게소"))) }
        if (choices.isEmpty()) return null
        return AdministrativeConversationTopic("active-rest", "rest", "휴게소", "어디에서 쉬어 갈까요?", choices,
            choices.associate { it.id to "${it.label}를 선택했어요. 경유 여부를 다시 확인할게요." })
    }
    private fun submitRest(entrance: FacilityEntrance) {
        if (!canConfigure() || !state.restDue || aheadFacilities().none { it.entrance == entrance }) return
        val dialog = AlertDialog.Builder(this).setTitle("${entrance.label}를 경유할까요?")
            .setMessage("기존 목적지를 유지하고 확인된 진입로를 경유지로 추가해요.")
            .setNegativeButton("취소", null).setPositiveButton("경유지 추가") { _, _ ->
                if (canConfigure() && state.restDue && aheadFacilities().any { it.entrance == entrance }) {
                    requestRestWaypoint(entrance)
                }
            }.create()
        parkedDialog = dialog
        dialog.show()
    }
    private fun renderRest(content: LinearLayout) {
        content.addView(text(if (state.restCommitted) "잠깐 쉬어 가는 시간" else "잠깐, 쉬어 갈까요?", 31f, ink, true))
        content.addView(text("운전한 지 한 시간이 되었네요. 안전한 곳에서 쉬어 가세요.", 15f, muted).apply { setPadding(0, dp(15), 0, dp(18)) })
        if (!emulatorMode) {
            val candidates = aheadFacilities().take(2)
            if (candidates.isNotEmpty()) content.addView(text("진입로 · © OpenStreetMap contributors", 12f, muted))
            if (candidates.isEmpty()) content.addView(text("현재 경로 앞쪽에 진입 방향이 확인된 휴게소가 없어요.", 19f, ink))
            candidates.forEach { option ->
                val entrance = option.entrance
                content.addView(button("${entrance.label} · 부산 방향 · ${"%.1f".format(option.meters / 1000)} km",
                    if (realRestChoice == entrance.id) teal else Color.WHITE, ink) {
                    parked { realRestChoice = entrance.id; restHeard = entrance.label; render(force = true); submitRest(entrance) }
                }.apply { isEnabled = canConfigure() && restStatus?.status !in setOf(WaypointResultStatus.REQUESTED, WaypointResultStatus.CONFIRMED) })
            }
            restHeard?.let { value -> content.addView(text(value, 21f, Color.WHITE).apply { setPadding(dp(18), dp(12), dp(18), dp(12)); background = rounded(teal, 20) }) }
            restConfirmationHeard?.let { value -> content.addView(text(value, 21f, Color.WHITE).apply { setPadding(dp(18), dp(12), dp(18), dp(12)); background = rounded(teal, 20) }) }
            val spokenSelection = candidates.firstOrNull { it.entrance.id == realRestChoice }
            if (spokenSelection != null && restHeard != null && restStatus?.status !in setOf(WaypointResultStatus.REQUESTED, WaypointResultStatus.CONFIRMED)) {
                content.addView(button("경유지 추가 확인", selectedFill, teal) { parked { submitRest(spokenSelection.entrance) } }.apply { isEnabled = canConfigure() })
            }
            restStatus?.let { content.addView(text(it.message, 16f, teal)) }
            content.addView(button("이번에는 지나갈게요", Color.TRANSPARENT, muted) { dispatch(ConversationEvent.RestCancelled) })
            content.addView(button("휴식을 마쳤어요", Color.rgb(245, 245, 245), teal) { parked { dispatch(ConversationEvent.RestConfirmed) } }.apply { isEnabled = canConfigure() })
            return
        }
        // These candidates are explicitly simulated until a navigation provider supplies real facilities.
        listOf("rest-a" to "휴식 후보 A · 12km", "rest-b" to "휴식 후보 B · 24km").forEach { (id, label) ->
            content.addView(button("${if (state.restSelection?.id == id) "✓  " else ""}$label\n시연 후보 · 실제 시설이 아니에요", Color.rgb(245, 245, 245), ink) { parked { dispatch(ConversationEvent.RestSelected(id)) } }.apply { isEnabled = canConfigure() }, LinearLayout.LayoutParams(-1, dp(75)).apply { bottomMargin = dp(9) })
        }
        if (state.restSelection != null && !state.restCommitted) content.addView(button("이 휴식 후보 선택 · 시연", teal, navy) { parked { AlertDialog.Builder(this).setTitle("시연 휴식 후보를 선택할까요?").setMessage("휴식 후보 선택을 기록해요. 실제 내비게이션 경로는 바뀌지 않아요.").setPositiveButton("선택 확인") { _, _ -> dispatch(ConversationEvent.RestCommitted) }.setNegativeButton("취소", null).show() } }.apply { isEnabled = canConfigure() })
        content.addView(button("이번에는 지나갈게요", Color.TRANSPARENT, muted) { dispatch(ConversationEvent.RestCancelled) })
        content.addView(button("휴식을 마쳤어요", Color.rgb(245, 245, 245), teal) { parked { dispatch(ConversationEvent.RestConfirmed) } }.apply { isEnabled = canConfigure() })
    }
    private fun buildEmulatorPanel() {
        val outer = column().apply { setPadding(dp(22), dp(18), dp(22), dp(18)); background = rounded(Color.rgb(255, 255, 255), 8) }
        val scroll = ScrollView(this).apply { addView(outer) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        outer.addView(text("개발자 도구", 26f, ink, true))
        outer.addView(button("현대화로 돌아가기", Color.TRANSPARENT, teal) { emulatorTab = false; render(force = true) })
        if (emulatorMode) outer.addView(button("실제 차량 연결로 돌아가기", Color.TRANSPARENT, teal) { parked { useActualMode() } })
        outer.addView(text("개발용 입력 도구예요. 여기의 차량·지역·시간 신호는 모두 시연값이며 실제 차량 모드와 분리돼요.", 13f, muted).apply { setPadding(0, dp(10), 0, dp(18)) })
        if (!emulatorMode) {
            outer.addView(button("시연 신호 사용", teal, navy) { if (canOpenEmulator()) {
                AlertDialog.Builder(this).setTitle("시연 신호를 사용할까요?").setMessage("좌석·지역·시간을 선택한 시연 조건의 모의값으로 바꿔요.").setPositiveButton("시연 시작") { _, _ ->
                    regionEntry.clear(); answerVoice?.clear(); answerVoiceOptIn = false; discardAnswerSpeech(reset = true); clearPromptResume(); talk?.clear(); bridge?.stop(); emulatorMode = true; demoPlaybackSpeed = DemoPlaybackClock.DEFAULT_SPEED; relationshipChosen = true; state = HyundaehwaEngine.simulation(Scenario.COLLEAGUE).copy(topicFormatCursor = state.topicFormatCursor,
                        rotationRemaining = state.rotationRemaining, lastRotationKey = state.lastRotationKey); passengerProfile = PassengerProfile(AgeBand.ADULT); difficultyMode = DifficultyMode.AUTO; refreshRegionalTopics(); spokenOutput = null; render(force = true)
                }.setNegativeButton("취소", null).show()
            } })
            return
        }
        val scenarios = row()
        Scenario.entries.forEach { scenario -> scenarios.addView(button(scenario.label.replace(" · ", "\n"), if (state.scenario == scenario) selectedFill else Color.rgb(255, 255, 255), ink) { parked { passengerProfile = PassengerProfile(if (scenario == Scenario.FAMILY) AgeBand.CHILD else AgeBand.ADULT); difficultyMode = DifficultyMode.AUTO; dispatch(ConversationEvent.ScenarioChanged(scenario)); refreshRegionalTopics(); spokenOutput = null } }, LinearLayout.LayoutParams(0, dp(55), 1f).apply { rightMargin = dp(8) }) }
        outer.addView(scenarios)
        outer.addView(text("${state.scenario.label} · 동승자 ${HyundaehwaEngine.view(state).passengerCount}명 · ${state.regionLabel} → ${state.destination}", 12f, teal).apply { setPadding(0, dp(12), 0, dp(16)) })
        controls(outer, listOf(
            "P 정차" to { dispatch(ConversationEvent.GearChanged(Gear.P, 0.0)) },
            "주행 시작" to { dispatch(ConversationEvent.Start); dispatch(ConversationEvent.GearChanged(Gear.D, 80.0)); emulatorTab = false; render(force = true) }
        ))
        controls(outer, listOf(
            "합류 구간" to { dispatch(ConversationEvent.FocusChanged(FocusKind.MERGE, true)) },
            "집중 구간 해제" to { dispatch(ConversationEvent.FocusChanged(FocusKind.MERGE, false)) },
            "한 시간 운전" to { dispatch(ConversationEvent.Tick(3_600_000, 1_000)) }
        ))
        controls(outer, listOf(
            "미디어 재생 전환" to { dispatch(ConversationEvent.MediaChanged(state.mediaActive != true)) },
            "조수석 비움" to { dispatch(ConversationEvent.SeatsChanged(false, state.rearCount, "simulation")) },
            "조수석 탑승" to { dispatch(ConversationEvent.SeatsChanged(true, state.rearCount, "simulation")) },
            "목적지 도착" to { dispatch(ConversationEvent.ArrivalChanged(true)) }
        ))
        controls(outer, listOf(
            "다른 지역 입력" to {
                val places = Scenario.entries
                val next = places[(places.indexOfFirst { it.regionId == state.regionId } + 1).mod(places.size)]
                dispatch(ConversationEvent.RegionChanged(next.regionId, next.regionLabel, state.destination, "simulation"))
                refreshRegionalTopics(); render(force = true)
            },
            "지역 확인 3초" to { dispatch(ConversationEvent.Tick(3_000, 1_000)) },
            "미디어 미확인" to { dispatch(ConversationEvent.MediaChanged(null)) }
        ))
        outer.addView(text("${state.gear} · 연속 운전 ${state.drivingMs / 60_000}분 · 지역 진입 기반 안내\n${HyundaehwaEngine.view(state).statusTitle}", 12f, muted).apply { setPadding(0, dp(17), 0, 0); setLineSpacing(dp(6).toFloat(), 1f) })
    }
    private fun controls(container: LinearLayout, actions: List<Pair<String, () -> Unit>>) {
        val row = row()
        actions.forEach { (label, action) -> row.addView(button(label, Color.rgb(255, 255, 255), ink, action), LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(0, dp(9), dp(8), 0) }) }
        container.addView(row)
    }
    private val carSpeedPermission = "android.car.permission.CAR_SPEED"
    private fun hasCarSpeedPermission() = checkSelfPermission(carSpeedPermission) == PackageManager.PERMISSION_GRANTED
    private fun canOpenEmulator() = BuildConfig.DEBUG && canConfigure()
    private fun parkedEvidence(s: PlatformSnapshot, now: Long) = ParkedEvidence(
        connected = s.vehicleConnected, gear = s.gear?.let { value -> Gear.entries.firstOrNull { it.name == value } },
        speedKph = s.speedKph, gearAtMs = s.gearUpdatedAtMs, speedAtMs = s.speedUpdatedAtMs,
        observedAtMs = now, uxAllowsSetup = s.uxAllowsSetup)
    private fun canConfigure(): Boolean = if (emulatorMode) HyundaehwaEngine.canConfigure(state)
        else parkedEvidence(currentSnapshot, SystemClock.elapsedRealtime()).isParked(SystemClock.elapsedRealtime())
    private fun updateDestinationWeather(s: PlatformSnapshot) {
        if (!resumed || emulatorMode || !weatherConsent || !state.enabled || state.arrived || s.routeActive == false) {
            if (weatherTripKey.isNotEmpty()) clearWeather()
            return
        }
        val remaining = s.remainingSeconds ?: return
        if (s.routeActive != true || s.destinationCoordinatesAtMs?.let { SystemClock.elapsedRealtime() - it in 0..8_000 } != true ||
            s.destination.isNullOrBlank() || s.destinationLatitude == null || s.destinationLongitude == null) return
        val coarse = DestinationWeather.key(s.destinationLatitude, s.destinationLongitude) ?: return
        val trip = "${s.destination}:$coarse"
        if (weatherTripKey != trip) { clearWeather(); weatherTripKey = trip }
        if (remaining <= 0.0) return
        val eta = System.currentTimeMillis() / 1000 + remaining.toLong()
        if (!weatherTried) {
            weatherTried = true; weatherLoading = true
            val epoch = weatherEpoch
            Thread({
                val next = runCatching { DestinationWeather.fetch(coarse, eta) }.getOrNull()
                handler.post {
                    if (weatherEpoch == epoch && weatherConsent && !isDestroyed) { weatherLoading = false; forecast = next }
                }
            }, "destination-weather").apply { isDaemon = true; start() }
        }
        val next = forecast ?: return
        if (!weatherPresented && kotlin.math.abs(eta - next.epochSeconds) <= 3600) {
            // Store the forecast; the engine decides when the silence threshold is met.
            weatherPresented = true
            state = HyundaehwaEngine.reduce(state, ConversationEvent.WeatherReady(next.advice))
            render(force = true)
        }
    }
    private fun invalidateDestinationCardOnChange(destination: String) {
        if (state.destination != destination && pinnedCard?.sourceKind == DestinationConversation.SOURCE) {
            pinnedCard = null; pinnedRegionId = null; revealedAt = 0L
            clearGleoCard(); clearPromptResume()
        }
    }
    private fun receivePlatform(snapshot: PlatformSnapshot) {
        val destination = snapshot.destination?.takeIf { it.isNotBlank() }
            ?: if (snapshot.destinationArrived == true && state.answerExplanationHeld) state.destination else ""
        invalidateDestinationCardOnChange(destination)
        if (state.destination != destination) { discardAnswerSpeech(); clearPromptResume() }
        else if (snapshot.destinationArrived == true && !state.answerExplanationHeld) clearPromptResume()
        currentSnapshot = snapshot
        platformStatus = snapshot.status
        val now = SystemClock.elapsedRealtime()
        fun fresh(at: Long?) = at != null && now - at in 0..8_000
        val evidence = parkedEvidence(snapshot, now)
        vehicleParked = evidence.isParked(now)
        // Resolve the current administrative polygon; a destination/address label never substitutes for location.
        val area = if (fresh(snapshot.locationUpdatedAtMs)) administrativeCatalog?.areaAt(snapshot.longitude, snapshot.latitude) else null
        val region = area?.let { it.id to it.label } ?: ("" to "위치 미확인")
        val media = MediaObservation.resolve(snapshot.mediaActive, snapshot.mediaUpdatedAtMs, now, speech?.state?.active == true, state.mediaActive, manualExternalMedia, snapshot.playbackUsages)
        if (snapshot.destinationArrived == true) { regionEntry.clear(); state = state.copy(regionEntryHeld = false) }
        else observeRegionEntry(region.first, region.second, destination)
        val beforeTopic = state.topic?.id
        // One snapshot is one decision. Do not emit using a new gear with old region/media/TBT.
        state = HyundaehwaEngine.reduceBatch(state, listOf(
            ConversationEvent.VehicleEvidenceChanged(evidence),
            ConversationEvent.SeatsChanged(if (fresh(snapshot.seatUpdatedAtMs)) snapshot.frontPassengerOccupied == true else false,
                if (fresh(snapshot.seatUpdatedAtMs)) snapshot.rearOccupantCount ?: 0 else 0, snapshot.sourceLabel),
            ConversationEvent.MediaChanged(media),
            ConversationEvent.RestOptionsChanged(aheadFacilities().map { it.entrance.label }),
            ConversationEvent.FocusChanged(FocusKind.JUNCTION, snapshot.navigationVoiceActive == true || (!vehicleParked && (!fresh(snapshot.maneuverUpdatedAtMs) || snapshot.nearManeuver != false))),
            ConversationEvent.FocusChanged(FocusKind.TUNNEL, fresh(snapshot.maneuverUpdatedAtMs) && snapshot.tunnel == true),
            ConversationEvent.RegionChanged(region.first, region.second, destination, snapshot.sourceLabel)
        ) + listOfNotNull(snapshot.destinationArrived?.let { ConversationEvent.ArrivalChanged(it) }))
        if (pinnedCard == null && beforeTopic != state.topic?.id) { weatherCard = ""; answerVisible = false; selectedChoiceId = null; heardChoice = null; choiceCardId = "" }
        refreshRegionalTopics()
        updateDestinationWeather(snapshot)
        talk?.enforce()
        answerVoice?.enforce()
        cancelUnsafeSpeech()
        render()
        playPendingOutput()
    }
    private fun useActualMode() {
        regionEntry.clear()
        answerVoice?.clear()
        answerVoiceOptIn = getSharedPreferences("conversation_profile", MODE_PRIVATE).getBoolean("on_device_card_answers", false)
        discardAnswerSpeech(reset = true)
        clearPromptResume()
        talk?.clear(); talkConsent = false; speech?.stop(); bridge?.stop(); emulatorMode = false; demoPlaybackSpeed = DemoPlaybackClock.DEFAULT_SPEED; emulatorTab = false; spokenOutput = null
        relationshipChosen = false
        state = HyundaehwaEngine.initial().copy(topicFormatCursor = state.topicFormatCursor,
            rotationRemaining = state.rotationRemaining, lastRotationKey = state.lastRotationKey); restoreProfile(); vehicleParked = false; platformStatus = "차량 신호 연결 대기"; if (resumed) bridge?.start(); render(force = true)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 16) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toast("내비 권한이 허용됐어요. 순정 내비 열기를 다시 눌러 주세요.")
            else toast("내비 권한을 확인하지 못했어요. 기기의 앱 권한을 확인해 주세요.")
            render(force = true)
        }
        if (requestCode == 15) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && resumed && !emulatorMode) { bridge?.stop(); bridge?.start() }
            render(force = true)
        }
        if (requestCode == 14 && answerVoicePermissionPending) {
            answerVoicePermissionPending = false
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                answerVoiceOptIn = false; cabinSoundOptIn = false; syncCabinMonitor()
                if (!emulatorMode) getSharedPreferences("conversation_profile", MODE_PRIVATE).edit().putBoolean("on_device_card_answers", false).putBoolean("cabin_sound_monitor", false).apply()
                toast("마이크 권한이 없어 화면으로 답변해 주세요.")
            } else { answerVoiceAttemptedCardId = null; syncCabinMonitor(); handler.post { maybeStartAnswerVoice() } }
            render(force = true)
        }
        if (requestCode == 13) {
            relationshipPermissionInFlight = false
            val start = pendingRelationshipVoiceStart; pendingRelationshipVoiceStart = null
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            handler.post {
                if (granted && resumed && canConfigure() && start != null) start()
                else relationshipPermissionFeedback?.invoke(if (granted)
                    "마이크 권한이 허용됐어요. P 정차 상태에서 말로 선택을 다시 눌러 주세요."
                    else "마이크 권한이 없어요. 아래 동승자 버튼은 그대로 사용할 수 있어요.")
            }
        }
        if (requestCode == 12) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && voiceTestBlockReason() == null) startTalkListening()
            else toast("마이크 권한이 없어요. 직접 입력을 사용할 수 있어요.")
        }
    }
    private fun renderCompactStatus(container: LinearLayout, status: CompactConversationStatus) {
        val chip = column().apply {
            setPadding(dp(20), dp(17), dp(20), dp(17))
            background = rounded(selectedFill, 18)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "${status.label}. ${status.detail}"
        }
        chip.addView(text(status.label, 22f, teal, true))
        chip.addView(text(status.detail, 15f, muted).apply { setPadding(0, dp(7), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
        container.addView(chip, LinearLayout.LayoutParams(-1, -2))
        revealMessage(chip)
    }
    private fun dialogHeader(title: String): View = column().apply {
        setPadding(dp(22), dp(18), dp(22), dp(18)); setBackgroundColor(teal)
        addView(text(title, 22f, Color.WHITE, true))
        addView(text("현대화 · 차량 대화 도우미", 12f, selectedFill).apply { setPadding(0, dp(5), 0, 0) })
    }
    private fun messageBackground() = GradientDrawable().apply {
        setColor(messageFill); cornerRadius = dp(16).toFloat()
    }
    private fun revealMessage(view: View) {
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 0f; view.animate().alpha(1f).setDuration(240).start()
        }
    }
    private fun conversationMark(size: Int): ImageView = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        setImageResource(R.drawable.ic_hyundaehwa)
        contentDescription = "현대화 로고"
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); typeface = Typeface.create(appFont, if (bold) 600 else 400, false); includeFontPadding = false
    }
    private fun button(label: String, backgroundColor: Int = Color.TRANSPARENT, color: Int = ink, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 15f; typeface = Typeface.create(appFont, 600, false); isAllCaps = false; setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.rgb(150, 154, 158), if (backgroundColor == teal) Color.WHITE else color))); background = buttonBackground(backgroundColor); setPadding(dp(16), dp(9), dp(16), dp(9)); minHeight = dp(48); minimumHeight = dp(48); setOnClickListener { action() }
    }
    private fun buttonBackground(color: Int): RippleDrawable {
        val primary = color == teal
        val fill = color
        fun shape(value: Int) = GradientDrawable().apply {
            setColor(value); cornerRadius = dp(12).toFloat()
            if (value == Color.WHITE || value == navy || value == selectedFill) setStroke(dp(1), accentBorder)
        }
        val content = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(if (primary) Color.rgb(230, 231, 232) else fill))
            addState(intArrayOf(android.R.attr.state_pressed), shape(if (primary) accentPressed else fill))
            addState(intArrayOf(android.R.attr.state_hovered), shape(if (primary) accentPressed else fill))
            addState(intArrayOf(), shape(fill))
        }
        return RippleDrawable(ColorStateList.valueOf(if (primary) accentPressed else Color.argb(35, 19, 20, 23)), content, shape(Color.WHITE))
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun space(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
}
