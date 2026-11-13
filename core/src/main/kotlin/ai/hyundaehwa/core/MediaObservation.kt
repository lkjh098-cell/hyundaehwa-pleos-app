package ai.hyundaehwa.core

/** Public playback attributes describe usage, not the identity of a player. */
enum class PlaybackUsage { MEDIA, ASSISTANT, UNKNOWN }

/** A nullable Android playback observation with a separate freshness clock. */
object MediaObservation {
    const val FRESH_FOR_MS = 8_000L

    fun resolve(
        active: Boolean?, observedAtMs: Long?, nowMs: Long,
        appSpeechActive: Boolean, previous: Boolean?, manualExternalMedia: Boolean = false,
        playbackUsages: List<PlaybackUsage>? = null
    ): Boolean? {
        // Hardware radio is not guaranteed to appear in Android playback configurations.
        if (manualExternalMedia) return true
        if (active == null || observedAtMs == null || nowMs - observedAtMs !in 0..FRESH_FOR_MS) return null
        val usages = playbackUsages ?: return if (active) true else null
        if (PlaybackUsage.MEDIA in usages) return true
        if (PlaybackUsage.UNKNOWN in usages) return null
        if (PlaybackUsage.ASSISTANT in usages) {
            // There is no public player UID here. Permit only continuation from a known
            // clear state while our own assistant-usage output is active. Focus still applies.
            return if (appSpeechActive && previous == false) false else null
        }
        // A positive music flag with no attributable configuration is ambiguous.
        return if (active) null else false
    }
}
