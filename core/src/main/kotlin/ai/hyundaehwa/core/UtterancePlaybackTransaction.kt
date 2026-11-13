package ai.hyundaehwa.core

/** The platform adapter routes every actual backend start/done callback through this transaction. */
enum class SegmentCompletion { IGNORED, MISSING_START, NEXT, FINISHED }
class UtterancePlaybackTransaction {
    private var token = 0L
    private var chunks: List<String> = emptyList()
    private var segmentStarted = false
    private var active = false
    var index = 0; private set
    val currentText: String? get() = if (active) chunks.getOrNull(index) else null
    val remainingText: String get() = if (active) chunks.drop(index).joinToString(" ") else ""
    val remainingAfterCurrent: String get() = if (active) chunks.drop(index + 1).joinToString(" ") else ""
    val segmentCount: Int get() = chunks.size
    val currentSegmentStarted: Boolean get() = active && segmentStarted
    fun begin(token: Long, text: String, keepTogether: Boolean = true): Boolean {
        this.token = token; index = 0; segmentStarted = false
        val sentences = PromptSentences.forSpeech(text)
        chunks = if (!keepTogether) sentences else buildList {
            var buffer = ""
            for (sentence in sentences) {
                if (buffer.isNotEmpty() && buffer.length + 1 + sentence.length > 300) { add(buffer); buffer = "" }
                buffer = if (buffer.isEmpty()) sentence else "$buffer $sentence"
            }
            if (buffer.isNotBlank()) add(buffer)
        }
        active = chunks.isNotEmpty(); return active
    }
    fun started(token: Long, segment: Int): Boolean {
        if (!active || token != this.token || segment != index || segmentStarted) return false
        segmentStarted = true; return true
    }
    /** Recorded playback has no synthesis input limit; require coverage of the whole remaining output. */
    fun packRemaining(token: Long, segment: Int): String? {
        if (!active || token != this.token || segment != index) return null
        val remaining = remainingText
        chunks = chunks.take(index) + remaining
        segmentStarted = false
        return remaining
    }
    fun retrySegment(token: Long, segment: Int): Boolean {
        if (!active || token != this.token || segment != index) return false
        segmentStarted = false; return true
    }
    fun completed(token: Long, segment: Int): SegmentCompletion {
        if (!active || token != this.token || segment != index) return SegmentCompletion.IGNORED
        if (!segmentStarted) { active = false; return SegmentCompletion.MISSING_START }
        if (index + 1 < chunks.size) { index++; segmentStarted = false; return SegmentCompletion.NEXT }
        active = false; return SegmentCompletion.FINISHED
    }
    fun cancel() { active = false; segmentStarted = false }
}
