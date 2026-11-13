package ai.hyundaehwa.core

/** A developer-demo clock only. Actual vehicle observations and wall-clock speech never speed up. */
object DemoPlaybackClock {
    val speeds = listOf(1, 3, 6, 12, 24)
    const val DEFAULT_SPEED = 3
    fun navigationDelta(wallDeltaMs: Long, debugBuild: Boolean, emulatorMode: Boolean, speed: Int): Long {
        val elapsed = wallDeltaMs.coerceAtLeast(0)
        if (!debugBuild || !emulatorMode) return elapsed
        val multiplier = speed.takeIf { it in speeds } ?: 1
        return elapsed.coerceAtMost(Long.MAX_VALUE / multiplier) * multiplier
    }
}
