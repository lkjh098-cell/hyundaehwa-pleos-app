package ai.hyundaehwa.core

/** Latest administrative entry; successful playback and guarded visible-text delivery remain distinct. */
data class RegionEntryNotice(val regionId: String, val label: String, val destination: String, val sequence: Long) {
    val speechId: String get() = "region-entry-$sequence"
    val message: String get() = "${label}에 진입했습니다"
    val spokenText: String get() = "$message."
}
data class RegionEntryConditions(val resumed: Boolean, val voiceEnabled: Boolean, val localAudioReady: Boolean,
    val cardHeld: Boolean, val otherVoiceActive: Boolean, val checkpointHeld: Boolean, val navigationVoiceActive: Boolean,
    val audioFocusBlocked: Boolean = false)
enum class RegionEntryRecovery { NONE, RETRY, TEXT }
enum class RegionEntryDelivery { NONE, AUDIO, TEXT }
class RegionEntryQueue {
    private var sequence = 0L
    private var failedAttempts = 0
    var delivery = RegionEntryDelivery.NONE; private set
    var visual: RegionEntryNotice? = null; private set
    var pending: RegionEntryNotice? = null; private set
    var active: RegionEntryNotice? = null; private set
    var failure: String? = null; private set
    val blocksContent: Boolean get() = pending != null || active != null
    fun observe(regionId: String, label: String, destination: String, playingSpeechId: String? = null) {
        if (regionId.isBlank() || label.isBlank()) { clear(); return }
        val previous = visual
        if (previous?.regionId == regionId && previous.destination == destination) return
        val notice = RegionEntryNotice(regionId, label, destination, ++sequence)
        if (previous?.destination != destination || active?.speechId != playingSpeechId) active = null
        visual = notice; pending = notice; failure = null; failedAttempts = 0; delivery = RegionEntryDelivery.NONE
    }
    private fun readyNotice(state: ConversationState, c: RegionEntryConditions): RegionEntryNotice? {
        val next = pending ?: return null
        if (next.regionId != state.regionId || next.destination != state.destination ||
            !state.enabled || state.arrived || state.quiet || state.guards.isNotEmpty() || state.answerListening || state.answerExplanationHeld ||
            state.topic != null || state.output != null || state.speechActive || (state.restDue && state.restAnnounced) ||
            !HyundaehwaEngine.audioClear(state) || !c.resumed || c.cardHeld ||
            c.otherVoiceActive || c.checkpointHeld || c.navigationVoiceActive || c.audioFocusBlocked || state.gear !in setOf(Gear.P, Gear.D)) return null
        return next
    }
    fun begin(state: ConversationState, c: RegionEntryConditions): RegionEntryNotice? {
        if (!c.voiceEnabled || active != null || failure != null) return null
        val next = readyNotice(state, c) ?: return null
        active = next; return next
    }
    /** Voice failure/cancellation alone never acknowledges entry. The visible notice remains on screen. */
    fun acknowledgeText(state: ConversationState, c: RegionEntryConditions): Boolean {
        if (c.voiceEnabled || readyNotice(state, c) == null) return false
        pending = null; active = null; failure = null; delivery = RegionEntryDelivery.TEXT
        return true
    }
    fun complete(speechId: String): Boolean {
        val done = active?.takeIf { it.speechId == speechId } ?: return false
        active = null
        if (pending?.speechId == done.speechId) { pending = null; failure = null; delivery = RegionEntryDelivery.AUDIO }
        return true
    }
    fun cancelled(speechId: String, checkpointRetained: Boolean) {
        if (active?.speechId != speechId) return
        if (!checkpointRetained) active = null
    }
    fun failed(speechId: String, reason: String) {
        if (active?.speechId != speechId) return
        active = null
        if (pending?.speechId == speechId) { failure = reason; failedAttempts++ }
    }
    /** Call only after the failed output has been removed from ConversationState. */
    fun recover(state: ConversationState, c: RegionEntryConditions): RegionEntryRecovery {
        if (failure == null || active != null || readyNotice(state, c) == null) return RegionEntryRecovery.NONE
        if (c.voiceEnabled && failedAttempts < 2) { retry(); return RegionEntryRecovery.RETRY }
        pending = null; active = null; failure = null; delivery = RegionEntryDelivery.TEXT
        return RegionEntryRecovery.TEXT
    }
    /** Reattach a deliberately retried checkpoint to the existing region notice. */
    fun resumeFailedCheckpoint(id: String): Boolean {
        val notice = pending?.takeIf { failure != null && it.speechId == id } ?: return false
        failure = null; active = notice; return true
    }
    /** Every retry has a new identity: a delayed callback from the failed attempt cannot finish it. */
    fun retry() {
        if (failure != null) pending?.let { old ->
            val next = old.copy(sequence = ++sequence)
            pending = next
            if (visual?.speechId == old.speechId) visual = next
        }
        failure = null
    }
    fun clear() { visual = null; pending = null; active = null; failure = null; failedAttempts = 0; delivery = RegionEntryDelivery.NONE }
}
