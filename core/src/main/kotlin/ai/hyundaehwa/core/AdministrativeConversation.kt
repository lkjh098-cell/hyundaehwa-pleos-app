package ai.hyundaehwa.core

import kotlin.random.Random

/** Shared, versioned public boundary/content data. No network or Android dependency. */
data class AdministrativePoint(val longitude: Double, val latitude: Double)
data class AdministrativeConversationOption(val id: String, val label: String, val keywords: List<String> = emptyList())
data class AdministrativeConversationTopic(
    val id: String, val format: String, val title: String, val question: String,
    val options: List<AdministrativeConversationOption>, val replyByChoice: Map<String, String> = emptyMap(),
    val sourceTitle: String = "", val sourceUrl: String = "", val dataTier: String = "administrative-basic",
    val correctChoiceId: String? = null, val contentNote: String = "", val sourceKind: String = "editorial-preference",
    val audiences: Set<String> = setOf("family", "adult"), val difficulty: Int = 2,
    val contentVersion: String = "", val explanation: String = "", val specificity: Int = 1, val placeName: String = "", val rotationKey: String = "", val subject: String = "history"
) {
    val contentRotationKey: String get() = rotationKey.ifBlank { when {
        sourceKind == DestinationConversation.SOURCE -> "destination"
        format == "quiz" -> "quiz"; format == "balance" -> "balance"; else -> "story"
    } }
    fun engineTopic(regionId: String): Topic = Topic(id, when (contentRotationKey) {
        "destination" -> TopicType.QUESTION; "story" -> TopicType.TRIVIA; "balance" -> TopicType.BALANCE; else -> TopicType.QUIZ
    }, regionId, spokenPrompt, correctChoiceId?.let { replyByChoice[it] }, contentNote,
        if (sourceKind == DestinationConversation.SOURCE) DestinationConversation.SOURCE else sourceTitle,
        difficulty, contentVersion, contentRotationKey)
    val spokenPrompt: String get() = if (format == "balance") BalancePresentation.spokenPrompt(question, options) else "$question ${SpokenChoiceCues.options(options)}"
}
data class AdministrativeConversationArea(
    val id: String, val code: String, val label: String, val province: String,
    val city: String, val district: String, val unitType: String,
    /** MultiPolygon -> Polygon -> Ring -> Point, using GeoJSON ring ordering. */
    val polygons: List<List<List<AdministrativePoint>>>,
    val topics: List<AdministrativeConversationTopic>
)
data class AdministrativeConversationDataset(
    val boundaryVintage: String, val boundarySourceTitle: String, val boundarySourceUrl: String,
    val areas: List<AdministrativeConversationArea>
)
data class AdministrativeConversationSelection(
    val area: AdministrativeConversationArea, val topic: AdministrativeConversationTopic,
    val boundaryVintage: String, val boundarySourceTitle: String, val boundarySourceUrl: String
) {
    fun gleoContext() = GleoTopicContext(area.id, this)
}

/** Uses current coordinates only: destination names and SDK address strings do not select regions. */
class AdministrativeConversationCatalog(private val dataset: AdministrativeConversationDataset, private val random: Random = Random.Default) {
    companion object {
        val formats = listOf("quiz", "balance")
        fun requestedFormat(type: String?): String? = when (type) {
            "balance", "preferences" -> "balance"
            "quiz", "travel" -> "quiz"
            "conversation", "everyday" -> "conversation"
            else -> null
        }
        private fun valid(p: AdministrativePoint) = p.longitude.isFinite() && p.latitude.isFinite() &&
            p.longitude in -180.0..180.0 && p.latitude in -90.0..90.0
        private fun onSegment(p: AdministrativePoint, a: AdministrativePoint, b: AdministrativePoint): Boolean {
            val dx = b.longitude - a.longitude; val dy = b.latitude - a.latitude
            val cross = (p.longitude - a.longitude) * dy - (p.latitude - a.latitude) * dx
            if (kotlin.math.abs(cross) > 1e-10) return false
            return p.longitude >= minOf(a.longitude, b.longitude) - 1e-10 && p.longitude <= maxOf(a.longitude, b.longitude) + 1e-10 &&
                p.latitude >= minOf(a.latitude, b.latitude) - 1e-10 && p.latitude <= maxOf(a.latitude, b.latitude) + 1e-10
        }
        private fun inRing(p: AdministrativePoint, ring: List<AdministrativePoint>): Boolean {
            if (ring.size < 4) return false
            var inside = false
            var a = ring.last()
            for (b in ring) {
                if (onSegment(p, a, b)) return true
                if ((a.latitude > p.latitude) != (b.latitude > p.latitude) &&
                    p.longitude < (b.longitude - a.longitude) * (p.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude) inside = !inside
                a = b
            }
            return inside
        }
        private fun contains(p: AdministrativePoint, polygon: List<List<AdministrativePoint>>) = polygon.isNotEmpty() &&
            inRing(p, polygon.first()) && polygon.drop(1).none { inRing(p, it) }
    }
    private data class Indexed(val area: AdministrativeConversationArea, val west: Double, val south: Double, val east: Double, val north: Double)
    private val indexed = dataset.areas.mapNotNull { area ->
        val points = area.polygons.flatMap { it.flatMap { ring -> ring } }
        if (area.id.isBlank() || area.label.isBlank() || points.isEmpty() || points.any { !valid(it) } ||
            area.topics.any { it.id.isBlank() || it.question.isBlank() || it.options.size < 2 }) null
        else Indexed(area, points.minOf { it.longitude }, points.minOf { it.latitude }, points.maxOf { it.longitude }, points.maxOf { it.latitude })
    }.sortedBy { it.area.id }
    val supportedAreaCount: Int get() = indexed.size
    fun areaAt(longitude: Double?, latitude: Double?): AdministrativeConversationArea? {
        if (longitude == null || latitude == null) return null
        val p = AdministrativePoint(longitude, latitude)
        if (!valid(p)) return null
        return indexed.firstOrNull { p.longitude in it.west..it.east && p.latitude in it.south..it.north && it.area.polygons.any { polygon -> contains(p, polygon) } }?.area
    }
    /** Development-only callers may use a named, explicit test area; actual mode uses areaAt. */
    fun areaById(id: String): AdministrativeConversationArea? = indexed.firstOrNull { it.area.id == id }?.area
    private val inventoryQueues = mutableMapOf<String, List<String>>()
    private val presentationVariants = mutableMapOf<String, AdministrativeConversationTopic>()
    private var selectionBag = FourContentRotation.newBag(random)
    private var lastSelectionKind: String? = null
    private val lastPresentedByArea = mutableMapOf<String, String>()
    private val quizSubjectHistory = mutableListOf<String>()
    /** Only the exact current administrative area supplies cards; no generated quiz fallback. */
    fun available(area: AdministrativeConversationArea, audience: String = "adult", preferredDifficulty: Int = 2): List<AdministrativeConversationTopic> {
        require(audience in setOf("family", "adult") && preferredDifficulty in 1..3)
        val eligible = area.topics.filter { audience in it.audiences && when (it.format) {
            "quiz" -> it.difficulty == preferredDifficulty && it.sourceKind == "official-place-fact"
            "balance" -> it.sourceKind == LocalContentEligibility.BALANCE_SOURCE
            else -> false
        } }
        // A queue is generated once for an inventory and moved only by recordPresented.
        // Repeated GPS, UI and Gleo reads return exactly the same candidate order.
        return formats.flatMap { format ->
            val candidates = eligible.filter { it.format == format }
            val key = "${area.id}:$format:$audience:$preferredDifficulty"
            val ids = candidates.map { it.id }.toSet()
            val queue = inventoryQueues[key]?.takeIf { it.toSet() == ids }
                ?: candidates.groupBy { it.specificity }.toSortedMap(compareByDescending { it })
                    .values.flatMap { group -> group.shuffled(random).map { it.id } }.also { inventoryQueues[key] = it }
            val byId = candidates.associateBy { it.id }
            val queued = queue.mapNotNull { byId[it] }
            // The subject budget follows actually presented quizzes across local units.
            // A subject change never substitutes a neighbour, easier level, or fabricated fact.
            val historyBlocked = format == "quiz" && quizSubjectHistory.takeLast(4).contains("history") &&
                queued.any { it.subject != "history" }
            val permitted = if (historyBlocked) queued.filter { it.subject != "history" } else queued
            val ordered = if (format == "quiz") permitted.sortedBy { card -> quizSubjectHistory.count { it == card.subject } } else permitted
            ordered.map { card ->
                presentationVariants.getOrPut("$key:${card.id}") {
                    if (card.format != "quiz") card else {
                        val choices = card.options.shuffled(random)
                        val replies = if (card.explanation.isBlank() || card.correctChoiceId == null) card.replyByChoice
                            else choices.associate { choice -> choice.id to if (choice.id == card.correctChoiceId)
                                "맞아요. ${card.explanation}" else "정답은 ${choices.indexOfFirst { it.id == card.correctChoiceId } + 1}번이에요. ${card.explanation}" }
                        card.copy(options = choices, replyByChoice = replies)
                    }
                }
            }
        }
    }

    /** Both automatic cards and Gleo consult the same presentation ledger. */
    fun availableForPresentation(area: AdministrativeConversationArea, audience: String = "adult", difficulty: Int = 2): List<AdministrativeConversationTopic> =
        available(area, audience, difficulty).filter { it.id != lastPresentedByArea[area.id] }

    /** Full eligible inventory: delivery history belongs to the engine, not this input list.
     * Filtering out the last UI card here would make a later Gleo delivery impossible to commit
     * when two successful callbacks arrive between SDK/catalog refreshes.
     */
    fun engineTopics(area: AdministrativeConversationArea, audience: String = "adult", difficulty: Int = 2): List<Topic> =
        available(area, audience, difficulty).map { it.engineTopic(area.id) }

    /** Caller has already resolved a fresh area and selected a local or destination editorial card. */
    fun selection(area: AdministrativeConversationArea, topic: AdministrativeConversationTopic): AdministrativeConversationSelection? {
        val supported = areaById(area.id) ?: return null
        if (supported.code != area.code || supported.topics.none { it.id == topic.id &&
            ((it.format == "quiz" && it.sourceKind == "official-place-fact") ||
                (it.format == "balance" && it.sourceKind == LocalContentEligibility.BALANCE_SOURCE)) }) return null
        return AdministrativeConversationSelection(supported, topic, dataset.boundaryVintage, dataset.boundarySourceTitle, dataset.boundarySourceUrl)
    }

    /** Commit a card only after its automatic UI is displayed. */
    fun recordPresentedById(areaId: String, topicId: String, audience: String = "adult", difficulty: Int = 2): Boolean {
        val area = areaById(areaId) ?: return false
        val topic = available(area, audience, difficulty).firstOrNull { it.id == topicId } ?: return false
        recordPresented(AdministrativeConversationSelection(area, topic, dataset.boundaryVintage, dataset.boundarySourceTitle, dataset.boundarySourceUrl), audience, difficulty)
        return true
    }

    fun select(longitude: Double?, latitude: Double?, fresh: Boolean, requestedType: String? = null,
        audience: String = "adult", difficulty: Int = 2, excludedTopicIds: Set<String> = emptySet()): AdministrativeConversationSelection? {
        if (!fresh) return null
        val area = areaAt(longitude, latitude) ?: return null
        val all = available(area, audience, difficulty).filter { it.id !in excludedTopicIds }
        val explicit = requestedFormat(requestedType)
        val format = explicit ?: FourContentRotation.next(selectionBag, lastSelectionKind, all.map { it.format }.toSet()) ?: return null
        val candidates = all.filter { it.format == format }
        if (candidates.isEmpty()) return null
        val topic = candidates.firstOrNull { it.id != lastPresentedByArea[area.id] } ?: return null
        return AdministrativeConversationSelection(area, topic, dataset.boundaryVintage, dataset.boundarySourceTitle, dataset.boundarySourceUrl)
    }
    /** Commit only when the caller has actually presented the selected card. */
    fun recordPresented(selection: AdministrativeConversationSelection, audience: String = "adult", difficulty: Int = 2) {
        val area = indexed.firstOrNull { it.area.id == selection.area.id }?.area ?: return
        if (lastPresentedByArea[area.id] == selection.topic.id) return
        val candidates = available(area, audience, difficulty).filter { it.format == selection.topic.format }
        if (candidates.none { it.id == selection.topic.id }) return
        val index = candidates.indexOfFirst { it.id == selection.topic.id }
        if (index < 0) return
        val key = "${area.id}:${selection.topic.format}:$audience:$difficulty"
        val queue = inventoryQueues[key].orEmpty()
        inventoryQueues[key] = queue.filter { it != selection.topic.id } + selection.topic.id
        presentationVariants.remove("$key:${selection.topic.id}")
        selectionBag = FourContentRotation.consume(selectionBag, selection.topic.format, random)
        if (selection.topic.format == "quiz") {
            quizSubjectHistory.add(selection.topic.subject)
            while (quizSubjectHistory.size > 14) quizSubjectHistory.removeAt(0)
        }
        lastSelectionKind = selection.topic.format
        lastPresentedByArea[area.id] = selection.topic.id
    }
}
