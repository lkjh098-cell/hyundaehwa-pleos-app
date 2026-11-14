package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.AdministrativeConversationCatalog
import ai.hyundaehwa.core.AdministrativeConversationTopic
import ai.hyundaehwa.core.LocalContentEligibility
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.InvocationTargetException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compares the indexed parser with an independent full scan of the shipped bank. */
@RunWith(AndroidJUnit4::class)
class AdministrativeConversationLoaderIndexIntegrationTest {
    private fun text(obj: JSONObject, key: String): String =
        (if (obj.isNull(key)) "" else obj.optString(key, "")).take(2000)
    private fun same(a: String, b: String) = a.filterNot(Char::isWhitespace) == b.filterNot(Char::isWhitespace)
    private fun expectedByFullScan(bank: JSONObject, area: JSONObject): List<Pair<String, Int>> {
        val cards = bank.getJSONArray("questions")
        val result = mutableListOf<Pair<String, Int>>()
        for (i in 0 until cards.length()) {
            val card = cards.getJSONObject(i)
            val format = text(card, "format")
            if (format !in AdministrativeConversationCatalog.formats ||
                !LocalContentEligibility.accepts(format, text(card, "sourceKind"),
                    card.optBoolean("reviewed", false), card.optBoolean("verified", false))) continue
            val rules = card.getJSONArray("areas")
            var best = -1
            for (j in 0 until rules.length()) {
                val rule = rules.getJSONObject(j)
                if (!LocalContentEligibility.exactCode(text(rule, "code"), text(area, "code"))) continue
                var count = 0; var matches = text(rule, "province").isNotBlank()
                for (key in listOf("province", "city", "district", "code")) {
                    val value = text(rule, key)
                    if (value.isNotBlank()) { if (!same(text(area, key), value)) matches = false; count++ }
                }
                if (matches) best = maxOf(best, count)
            }
            if (best >= 0) result += text(card, "id") to best
        }
        return result
    }
    private fun raw(name: String): JSONObject = JSONObject(
        InstrumentationRegistry.getInstrumentation().targetContext.assets.open(name).bufferedReader().use { it.readText() })

    @Test(timeout = 75_000L)
    fun indexedCatalogMatchesFullScanForRepresentativeRealRegions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val start = SystemClock.elapsedRealtime()
        val catalog = requireNotNull(AdministrativeConversationLoader.load(instrumentation.targetContext.assets))
        val loadMs = SystemClock.elapsedRealtime() - start
        val boundaries = raw("administrative-conversation.json").getJSONArray("areas")
        val bank = raw("question-bank.json")
        val indices = linkedSetOf(0, boundaries.length() / 4, boundaries.length() / 2,
            boundaries.length() * 3 / 4, boundaries.length() - 1)
        (0 until boundaries.length()).firstOrNull { text(boundaries.getJSONObject(it), "code") == "11440" }?.let { indices += it }
        var checked = 0
        for (index in indices) {
            val area = boundaries.getJSONObject(index)
            val id = text(area, "id")
            val expected = expectedByFullScan(bank, area)
            val actual = requireNotNull(catalog.areaById(id)).topics.map { it.id to it.specificity }
            assertEquals("Indexed output must preserve full-scan order and specificity for $id", expected, actual)
            checked++
        }
        assertTrue("Several distinct regions must be checked", checked >= 5)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("loader_index_observation", "loadMs=$loadMs; representativeRegions=$checked; full-scan comparison; cached-or-cold not inferred")
        })
    }

    @Test fun indexDeduplicatesRulesAndRejectsMalformedEligibleRules() {
        val bank = raw("question-bank.json")
        val boundaries = raw("administrative-conversation.json").getJSONArray("areas")
        val area = (0 until boundaries.length()).map { boundaries.getJSONObject(it) }
            .first { text(it, "code") == "11440" }
        val cards = bank.getJSONArray("questions")
        val source = (0 until cards.length()).map { cards.getJSONObject(it) }.first { card ->
            val format = text(card, "format")
            format == "quiz" && LocalContentEligibility.accepts(format, text(card, "sourceKind"),
                card.optBoolean("reviewed", false), card.optBoolean("verified", false)) &&
                (0 until card.getJSONArray("areas").length()).any { j ->
                    val rule = card.getJSONArray("areas").getJSONObject(j)
                    LocalContentEligibility.exactCode(text(rule, "code"), text(area, "code")) &&
                        same(text(rule, "province"), text(area, "province"))
                }
        }
        val copy = JSONObject(source.toString())
        val correctRule = (0 until source.getJSONArray("areas").length()).map { source.getJSONArray("areas").getJSONObject(it) }
            .first { LocalContentEligibility.exactCode(text(it, "code"), text(area, "code")) && same(text(it, "province"), text(area, "province")) }
        copy.put("areas", JSONArray().put(JSONObject(correctRule.toString())).put(JSONObject(correctRule.toString())))
        val fixture = JSONObject().put("version", "fixture").put("questions", JSONArray().put(copy))
        val indexMethod = AdministrativeConversationLoader::class.java.getDeclaredMethod("indexBank", JSONObject::class.java).apply { isAccessible = true }
        val indexed = requireNotNull(indexMethod.invoke(AdministrativeConversationLoader, fixture))
        val topicsMethod = AdministrativeConversationLoader::class.java.getDeclaredMethod("topics", indexed.javaClass, JSONObject::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val topics = topicsMethod.invoke(AdministrativeConversationLoader, indexed, area) as List<AdministrativeConversationTopic>
        assertEquals("Duplicate area rules cannot duplicate a card", 1, topics.size)
        assertEquals(text(copy, "id"), topics.single().id)
        val broken = JSONObject(copy.toString()).put("areas", JSONArray().put("not-an-object"))
        val malformedBank = JSONObject().put("version", "fixture").put("questions", JSONArray().put(broken))
        try {
            indexMethod.invoke(AdministrativeConversationLoader, malformedBank)
            fail("Malformed rules of an eligible card must be rejected")
        } catch (e: InvocationTargetException) {
            assertNotNull("The parser must preserve the underlying validation error", e.cause)
        }
    }
}
