package ai.hyundaehwa.core

/** SDK observations use elapsed realtime; missing, future and expired signals are unavailable. */
object SignalFreshness {
    const val MAX_AGE_MS = 8_000L

    fun isFresh(observedAtMs: Long?, nowMs: Long): Boolean =
        observedAtMs != null && nowMs - observedAtMs in 0..MAX_AGE_MS
}
