package ai.hyundaehwa.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import kotlin.test.*
import org.junit.Test

/** Full-bank regression calls the actual native factories, rather than trusting a web-generated transcript. */
class NativeCachedSpeechCoverageTest {
    private val prefix = "/native-cache-coverage/"
    private fun textResource(name: String) = checkNotNull(javaClass.getResourceAsStream(prefix + name)).bufferedReader().use { it.readText() }
    private val properties = Properties().apply { load(textResource("snapshot.properties").reader()) }
    private fun rows(name: String): List<List<String>> = textResource(name).lineSequence().filter { it.isNotBlank() }
        .map { line -> line.split('\t').map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) } }.toList()
    private fun cards(): List<AdministrativeConversationTopic> = rows("cards.tsv").map { f ->
        val count = f[12].toInt()
        require(f.size == 13 + count * 3)
        AdministrativeConversationTopic(f[0], f[1], f[2], f[3], (0 until count).map { i ->
            AdministrativeConversationOption(f[13 + i * 3], f[14 + i * 3])
        }, (0 until count).associate { i -> f[13 + i * 3] to f[15 + i * 3] },
            sourceTitle = f[6], sourceUrl = f[7], correctChoiceId = f[4].ifBlank { null },
            contentNote = f[9], sourceKind = f[8], difficulty = f[10].toIntOrNull() ?: 2, contentVersion = f[11], explanation = f[5])
    }
    private fun <T> permutations(values: List<T>): List<List<T>> = if (values.isEmpty()) listOf(emptyList()) else
        values.indices.flatMap { i -> permutations(values.filterIndexed { j, _ -> i != j }).map { listOf(values[i]) + it } }
    @Test fun snapshotIsTheExactPackagedCurrentBankAndKeepsEveryCardAndChoice() {
        val source = listOf(File("../app/src/main/assets/question-bank.json"), File("app/src/main/assets/question-bank.json"))
            .firstOrNull { it.isFile }
        val raw = assertNotNull(source, "Packaged question-bank.json must be available for snapshot freshness validation").readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        assertEquals(properties.getProperty("bankSha256"), sha, "Refresh native coverage fixtures after changing the actual bank")
        val cards = cards()
        assertEquals(2859, cards.size); assertEquals(2859, cards.map { it.id }.toSet().size)
        assertEquals(2354, cards.count { it.format == "quiz" }); assertEquals(505, cards.count { it.format == "balance" })
        assertTrue(cards.all { it.contentVersion == properties.getProperty("bankVersion") })
        cards.forEach { card ->
            assertEquals(if (card.format == "quiz") 3 else 2, card.options.size, card.id)
            if (card.format == "quiz") assertTrue(card.options.any { it.id == card.correctChoiceId }, card.id)
        }
    }
    @Test fun allActualPromptsAndFactoryFeedbackExactlyMatchAcrossEveryChoicePermutation() {
        val entries = rows("catalog.tsv").map { f -> CachedSpeechEntry(f[0], f[1], 1.0, "a".repeat(64), 1) }
        assertEquals(properties.getProperty("catalogEntries").toInt(), entries.size)
        val matcher = CachedSpeechMatcher(entries)
        val missing = mutableListOf<String>()
        var promptCases = 0; var feedbackCases = 0; var intentionalSilenceCases = 0
        fun check(card: AdministrativeConversationTopic, order: String, route: String, text: String) {
            if (matcher.match(text) == null) missing += "${card.id}\t$order\t$route\t$text"
        }
        for (original in cards()) {
            val orders = permutations(original.options)
            assertEquals(if (original.format == "quiz") 6 else 2, orders.size, original.id)
            for (options in orders) {
                val card = original.copy(options = options)
                val order = options.joinToString(",") { it.id }
                check(card, order, "prompt", card.spokenPrompt); promptCases++
                for (option in options) {
                    val route = if (card.format == "quiz") if (option.id == card.correctChoiceId) "correct" else "wrong" else "preference"
                    val feedback = assertNotNull(AnswerExplanation.text(card, option.id), "${card.id}: $route")
                    check(card, order, route, feedback); feedbackCases++
                }
                for ((route, timedOut) in listOf("timeout" to true, "reveal" to false)) {
                    val feedback = AnswerExplanation.text(card, null, timedOut)
                    if (card.format == "balance") { assertNull(feedback, card.id); intentionalSilenceCases++ }
                    else { check(card, order, route, assertNotNull(feedback)); feedbackCases++ }
                }
            }
        }
        assertEquals(15134, promptCases)
        assertEquals(72640, feedbackCases)
        assertEquals(2020, intentionalSilenceCases)
        val report = File("build/reports/native-cached-speech-coverage.txt").apply { parentFile.mkdirs() }
        report.writeText("prompts=$promptCases\nfeedback=$feedbackCases\nintentionalSilence=$intentionalSilenceCases\nmissing=${missing.size}\n" + missing.joinToString("\n"))
        assertTrue(missing.isEmpty(), "${missing.size} exact-text coverage failures; see ${report.absolutePath}\n" + missing.take(20).joinToString("\n"))
    }
}
