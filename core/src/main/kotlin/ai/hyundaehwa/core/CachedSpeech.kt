package ai.hyundaehwa.core

/** Each asset is a recording of this exact text. Metadata is validated again before playback. */
data class CachedSpeechEntry(val text: String, val file: String, val durationSeconds: Double,
    val sha256: String, val bytes: Long)
data class CachedSpeechSegment(val entry: CachedSpeechEntry, val startOffset: Int, val endOffset: Int)
data class CachedSpeechPlan(val text: String, val segments: List<CachedSpeechSegment>) {
    fun remainingText(index: Int): String = segments.getOrNull(index)?.let { text.substring(it.startOffset) }.orEmpty()
}
/** No alias, punctuation removal, numeric substitution, or nearest-text matching is allowed here. */
class CachedSpeechMatcher(entries: List<CachedSpeechEntry>) {
    // A sentence index avoids one heavyweight mutable map per character on vehicle devices.
    private val byFirst: Map<Char, List<Pair<String, CachedSpeechEntry>>> = entries.filter(::validEntry)
        .groupBy { normalize(it.text) }.mapNotNull { (text, variants) ->
            variants.distinct().singleOrNull()?.let { text to it }
        }.groupBy { it.first.first() }.mapValues { (_, values) -> values.sortedByDescending { it.first.length } }
    fun match(text: String): CachedSpeechPlan? {
        val value = normalize(text)
        if (value.isBlank() || value.length > 12_000) return null
        val failed = mutableSetOf<Int>()
        fun cover(offset: Int): List<CachedSpeechSegment>? {
            var start = offset
            while (start < value.length && value[start] == ' ') start++
            if (start == value.length) return emptyList()
            if (start in failed) return null
            val candidates = byFirst[value[start]].orEmpty().filter { (known, _) -> value.startsWith(known, start) }
                .map { (known, entry) -> CachedSpeechSegment(entry, start, start + known.length) }
            for (candidate in candidates) {
                // The next clip may begin only at a text boundary, never inside another word.
                if (candidate.endOffset != value.length && value[candidate.endOffset] != ' ') continue
                val rest = cover(candidate.endOffset) ?: continue
                return listOf(candidate) + rest
            }
            failed += start; return null
        }
        return cover(0)?.takeIf { it.isNotEmpty() }?.let { CachedSpeechPlan(value, it) }
    }
    companion object {
        fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
        fun validEntry(entry: CachedSpeechEntry): Boolean = entry.text.isNotBlank() && entry.bytes > 0 &&
            entry.durationSeconds.isFinite() && entry.durationSeconds > 0 && entry.durationSeconds <= 600 &&
            Regex("[a-fA-F0-9]{64}").matches(entry.sha256) &&
            Regex("[A-Za-z0-9_./-]+\\.(?:ogg|mp3|wav|m4a)").matches(entry.file) &&
            !entry.file.startsWith('/') && '\\' !in entry.file && entry.file.split('/').none { it == "." || it == ".." }
    }
}
