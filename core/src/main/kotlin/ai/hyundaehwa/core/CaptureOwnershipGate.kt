package ai.hyundaehwa.core

/** Serializes recorder start with revocation, including workers still constructing a recorder.
 * Construction and reads stay outside this lock. A handoff returns only after the current
 * recorder's stop callback returns. Stop callbacks must handle their platform-specific errors. */
class CaptureOwnershipGate<T : Any> {
    @Volatile var generation = 0L; private set
    private var requested = false
    private var closed = false
    private var owner: T? = null
    private var stopOwner: (() -> Unit)? = null

    @Synchronized fun request(run: Boolean): Long? {
        if (closed || requested == run) return null
        requested = run
        generation++
        stopLocked()
        return generation
    }
    @Synchronized fun isCurrent(token: Long): Boolean = !closed && requested && generation == token

    @Synchronized fun start(token: Long, candidate: T, start: () -> Unit, stop: () -> Unit): Boolean {
        if (!isCurrent(token) || owner != null) return false
        owner = candidate
        stopOwner = stop
        try { start() } catch (failure: Throwable) {
            stopLocked()
            throw failure
        }
        return true
    }
    @Synchronized fun finish(candidate: T) {
        if (owner === candidate) stopLocked()
    }
    @Synchronized fun close() {
        if (closed) return
        closed = true
        requested = false
        generation++
        stopLocked()
    }
    private fun stopLocked() {
        val stop = stopOwner
        owner = null
        stopOwner = null
        stop?.invoke()
    }
}
