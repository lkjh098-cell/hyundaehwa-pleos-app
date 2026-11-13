package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/** Shared public dataset only. Neither coordinates from the car nor addresses are persisted. */
object AdministrativeConversationLoader {
    @Volatile private var cached: AdministrativeConversationCatalog? = null
    private fun text(value: JSONObject, key: String, fallback: String = "") =
        (if (value.isNull(key)) fallback else value.optString(key, fallback)).take(2000)
    private fun ring(value: JSONArray): List<AdministrativePoint> = (0 until value.length()).map { i ->
        val point = value.getJSONArray(i)
        require(point.length() == 2)
        AdministrativePoint(point.getDouble(0), point.getDouble(1))
    }
    private fun polygon(value: JSONArray): List<List<AdministrativePoint>> = (0 until value.length()).map { ring(value.getJSONArray(it)) }
    private fun geometry(value: JSONObject): List<List<List<AdministrativePoint>>> {
        val coordinates = value.getJSONArray("coordinates")
        return when (value.getString("type")) {
            "Polygon" -> listOf(polygon(coordinates))
            "MultiPolygon" -> (0 until coordinates.length()).map { polygon(coordinates.getJSONArray(it)) }
            else -> error("Unsupported administrative geometry")
        }
    }
    private fun names(value: JSONArray): List<String> = (0 until value.length()).map { value.getString(it).trim() }.filter { it.isNotBlank() }
    private fun same(actual: String, expected: String): Boolean = actual.filterNot(Char::isWhitespace) == expected.filterNot(Char::isWhitespace)
    private fun specificity(rule: JSONObject, area: JSONObject): Int {
        var count = 0
        for (key in listOf("province", "city", "district", "code")) {
            val expected = text(rule, key)
            if (expected.isNotBlank()) {
                if (!same(text(area, key), expected)) return -1
                count++
            }
        }
        return if (text(rule, "province").isBlank()) -1 else count
    }
    private fun topics(bank: JSONObject?, area: JSONObject): List<AdministrativeConversationTopic> {
        if (bank == null) return emptyList()
        val cards = bank.getJSONArray("questions"); val version = text(bank, "version")
        require(cards.length() in 1..20000 && version.isNotBlank())
        return (0 until cards.length()).mapNotNull { i ->
            val value = cards.getJSONObject(i)
            val format = text(value, "format")
            if (format !in AdministrativeConversationCatalog.formats) return@mapNotNull null
            if (!LocalContentEligibility.accepts(format, text(value, "sourceKind"),
                value.optBoolean("reviewed", false), value.optBoolean("verified", false))) return@mapNotNull null
            val rules = value.getJSONArray("areas")
            // Every format must name the exact administrative code. A province label
            // or a generic preference tag cannot turn another area's card into local content.
            val spec = (0 until rules.length()).map { rules.getJSONObject(it) }
                .filter { LocalContentEligibility.exactCode(text(it, "code"), text(area, "code")) }
                .maxOfOrNull { specificity(it, area) } ?: -1
            if (spec < 0) return@mapNotNull null
            val opts = value.getJSONArray("options")
            val choices = (0 until opts.length()).map { j ->
                val o = opts.getJSONObject(j)
                AdministrativeConversationOption(text(o, "id"), text(o, "label"), o.optJSONArray("keywords")?.let(::names) ?: emptyList())
            }
            val id = text(value, "id"); val correct = text(value, "correctChoiceId").ifBlank { null }
            val question = text(value, "question"); val explanation = text(value, "explanation")
            val audience = names(value.getJSONArray("audiences")).toSet()
            val sourceTitle = text(value, "sourceTitle"); val sourceUrl = text(value, "sourceUrl")
            require(id.isNotBlank() && question.isNotBlank() && choices.size >= 2 && choices.map { it.id }.toSet().size == choices.size)
            require(audience.isNotEmpty() && audience.all { it in setOf("family", "adult") })
            require(choices.all { it.id.isNotBlank() && it.label.isNotBlank() })
            if (format == "quiz") require(correct != null && choices.any { it.id == correct } && explanation.isNotBlank() && sourceTitle.isNotBlank() && sourceUrl.startsWith("https://"))
            val directReplies = value.optJSONObject("replyByChoice")
            val replies = directReplies?.keys()?.asSequence()?.associateWith { directReplies.getString(it).take(2000) } ?: emptyMap()
            if (format == "balance") require(correct == null && choices.size == 2 &&
                sourceTitle.isNotBlank() && sourceUrl.startsWith("https://") &&
                choices.all { replies[it.id].orEmpty().isNotBlank() } &&
                value.optJSONObject("evidence")?.let { text(it, "placeName") }.orEmpty().isNotBlank())
            val followups = if (format == "quiz") choices.associate { option -> option.id to
                (if (option.id == correct) "맞아요. $explanation" else "정답은 ${choices.indexOfFirst { it.id == correct } + 1}번이에요. $explanation") } else replies
            AdministrativeConversationTopic(id, format, text(value, "title"), question, choices, followups,
                sourceTitle, sourceUrl, when {
                    format != "quiz" -> "regional-editorial"
                    else -> "verified-place-fact"
                }, correct,
                text(value, "contentNote"), text(value, "sourceKind"), audience, value.optInt("difficulty", 2), version, explanation, spec,
                value.optJSONObject("evidence")?.let { text(it, "placeName") }.orEmpty(), subject = text(value, "subject").ifBlank { "history" })
        }
    }
    @Synchronized fun load(assets: AssetManager): AdministrativeConversationCatalog? {
        cached?.let { return it }
        return runCatching {
        val raw = assets.open("administrative-conversation.json").bufferedReader().use { it.readText() }
        require(raw.length <= 60_000_000)
        val root = JSONObject(raw); require(root.getInt("schemaVersion") == 1)
        val source = root.getJSONObject("boundarySource")
        val bank = runCatching { JSONObject(assets.open("question-bank.json").bufferedReader().use { it.readText() }).also { require(it.getInt("schemaVersion") == 1) } }.getOrNull()
        val data = root.getJSONArray("areas"); require(data.length() in 1..1000)
        val areas = (0 until data.length()).map { i ->
            val area = data.getJSONObject(i)
            AdministrativeConversationArea(text(area,"id"),text(area,"code"),text(area,"label"),text(area,"province"),
                text(area,"city"),text(area,"district"),text(area,"unitType"),geometry(area.getJSONObject("geometry")),
                topics(bank, area))
        }
        AdministrativeConversationCatalog(AdministrativeConversationDataset(text(root,"boundaryVintage"),text(source,"title"),text(source,"url"),areas))
    }.getOrNull()?.also { cached = it }
    }
}
