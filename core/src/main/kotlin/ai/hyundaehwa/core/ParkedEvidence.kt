package ai.hyundaehwa.core

/** All times use the same monotonic elapsed-realtime clock, never wall-clock time. */
data class ParkedEvidence(
    val connected: Boolean,
    val gear: Gear?,
    val speedKph: Double?,
    val gearAtMs: Long?,
    val speedAtMs: Long?,
    val observedAtMs: Long,
    /** True only after a current CarUxRestrictions reading permits setup interaction. */
    val uxAllowsSetup: Boolean?,
) {
    fun signalsFresh(nowMs: Long): Boolean = fresh(gearAtMs, nowMs) && fresh(speedAtMs, nowMs)
    fun isParked(nowMs: Long): Boolean = connected && uxAllowsSetup == true &&
        gear == Gear.P && speedKph != null && speedKph.isFinite() && speedKph == 0.0 &&
        signalsFresh(nowMs)

    private fun fresh(at: Long?, now: Long): Boolean =
        at != null && at >= 0 && now >= at && now - at <= MAX_AGE_MS

    companion object { const val MAX_AGE_MS = 8_000L }
}
