package ai.hyundaehwa.core

/** A completed recognition update is a text segment, not permission to close the microphone.
 * Only the recognizer's actual end callback may publish the accumulated final utterance. */
class RecognizedUtterance {
    private var committed = ""
    private var live = ""
    val preview: String get() = merge(committed, live)
    val finalText: String get() = if (live.isBlank()) committed else ""
    fun update(text: String, completed: Boolean): String {
        val next = text.trim().take(240)
        if (completed) { committed = merge(committed, next); live = "" } else live = next
        return preview
    }
    companion object {
        internal fun merge(previous: String, next: String): String {
            if (next.isBlank()) return previous
            if (previous.isBlank() || next.startsWith(previous)) return next
            if (previous.startsWith(next) || previous == next) return previous
            for (overlap in minOf(previous.length, next.length) downTo 2) {
                if (previous.takeLast(overlap) == next.take(overlap)) return previous + next.drop(overlap)
            }
            return "$previous $next"
        }
    }
}
