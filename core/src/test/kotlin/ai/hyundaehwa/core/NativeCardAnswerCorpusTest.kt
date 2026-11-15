package ai.hyundaehwa.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import org.junit.Test
import kotlin.test.*

/** Uses every option and keyword in the actual shipped bank, including incorrect choices. */
class NativeCardAnswerCorpusTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/native-answer-corpus/$name")).bufferedReader().use { it.readText() }
    private fun cards(): List<AdministrativeConversationTopic> = resource("cards.tsv").lineSequence().filter { it.isNotBlank() }.map { line ->
        val f = line.split('\t').map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
        val count = f[3].toInt()
        require(f.size == 4 + count * 3)
        AdministrativeConversationTopic(f[0], f[1], "", "", (0 until count).map { i ->
            AdministrativeConversationOption(f[4 + i * 3], f[5 + i * 3], f[6 + i * 3].split('\n').filter { it.isNotEmpty() })
        }, correctChoiceId = f[2].ifBlank { null })
    }.toList()
    private fun normalized(value: String) = value.lowercase().replace(Regex("[^가-힣ㄱ-ㅎㅏ-ㅣa-z0-9]"), "")
    private fun <T> permutations(values: List<T>): List<List<T>> = if (values.isEmpty()) listOf(emptyList()) else
        values.indices.flatMap { i -> permutations(values.filterIndexed { j, _ -> i != j }).map { listOf(values[i]) + it } }

    @Test fun corpusIsPinnedToEveryCurrentPackagedCardAndKeyword() {
        val bank = assertNotNull(listOf(File("../app/src/main/assets/question-bank.json"), File("app/src/main/assets/question-bank.json")).firstOrNull { it.isFile })
        val expected = Properties().apply { load(resource("snapshot.properties").reader()) }
        assertEquals(expected.getProperty("bankSha256"), MessageDigest.getInstance("SHA-256").digest(bank.readBytes()).joinToString("") { "%02x".format(it) })
        val cards = cards()
        assertEquals(2859, cards.size); assertEquals(2354, cards.count { it.format == "quiz" }); assertEquals(505, cards.count { it.format == "balance" })
        assertEquals(8072, cards.sumOf { it.options.size })
        assertTrue(cards.all { card -> card.options.all { it.keywords.isNotEmpty() } })
    }
    @Test fun everyFullLabelAndNumberCombinationResolvesInEveryDisplayedOrder() {
        val failures = mutableListOf<String>(); var cases = 0; var orders = 0
        for (original in cards()) for (options in permutations(original.options)) {
            val card = original.copy(options = options); orders++
            for ((index, option) in options.withIndex()) {
                for (text in listOf(option.label, "${index + 1}번 ${option.label}", "${option.label} ${index + 1}번", "정답은 ${index + 1}번 ${option.label}입니다")) {
                    val actual = SpokenCardAnswerParser.parse(text, card)
                    if (actual?.choiceId != option.id || actual.gaveUp) failures += "${card.id}\t$text\t${option.id}\t$actual"
                    cases++
                }
            }
        }
        report("full-labels", cases, failures)
        assertEquals(15134, orders); assertEquals(177568, cases)
        assertTrue(failures.isEmpty(), "${failures.size} failures\n${failures.take(20).joinToString("\n")}")
    }
    private val functional = setOf("그곳", "이곳", "다른", "같은", "있는", "없는", "하는", "위한", "받은", "함께", "각자", "먼저", "그대로", "모두", "사람", "장소", "지역", "방식", "이용", "통해", "때문에", "자체", "직접", "제작한", "만든", "여러", "저곳", "우리", "현재", "다음", "모든", "작은", "큰", "새로운", "만들어", "있어요", "없어요", "보기", "하기", "가요", "해요", "이", "그", "저", "내", "제", "것", "거", "수", "등", "더", "좀", "한", "두", "세", "네", "다", "안", "못", "덜", "만", "및", "또", "꽉", "잘", "을", "를", "은", "는", "의", "와", "과", "에", "로", "가", "하", "않고", "않는", "않는다", "않았다", "같아", "선택", "1번", "일번", "첫번째", "첫째", "첫번", "처음거", "2번", "이번", "두번째", "두번", "둘째", "3번", "삼번", "세번째", "세번", "셋째", "4번", "사번", "네번째", "넷째")
    // Independent corpus probes: complete words and conservative particle-stripped nouns.
    private fun shortCandidates(option: AdministrativeConversationOption): Set<String> {
        val words = option.label.split(Regex("[^가-힣a-zA-Z0-9]+")).filter { it.isNotBlank() }
        return (words + words.map { it.replace(Regex("(?:으로|에서|에게|까지|부터|을|를|로|에|와|과)$"), "") } + option.keywords)
            .map(::normalized).filter { it.isNotEmpty() && !it.all { char -> char.isDigit() } && it !in functional }.toSet()
    }
    @Test fun everyDistinctiveWordOrNounWorksButSharedWordsAndConflictingNumbersDoNot() {
        val failures = mutableListOf<String>(); var accepted = 0; var rejected = 0
        for (original in cards()) {
            val candidates = original.options.map(::shortCandidates)
            for (options in listOf(original.options, original.options.reversed())) {
                val card = original.copy(options = options)
                for ((index, option) in options.withIndex()) {
                    val own = original.options.indexOfFirst { it.id == option.id }
                    val unique = candidates[own].filter { candidate -> original.options.indices.filter { it != own }.none { other ->
                        candidates[other].any { candidate in it } || candidate in normalized(original.options[other].label)
                    } }
                    for (word in unique) for (text in listOf(word, "${word}이요", "${index + 1}번 $word", "$word ${index + 1}번")) {
                        val actual = SpokenCardAnswerParser.parse(text, card)
                        if (actual?.choiceId != option.id || actual.gaveUp) failures += "${card.id}\tshort\t$text\t${option.id}\t$actual"
                        accepted++
                    }
                    for (otherIndex in options.indices.filter { it != index }) {
                        for (name in listOf(option.label) + unique) {
                            val text = "${otherIndex + 1}번 $name"
                            if (SpokenCardAnswerParser.parse(text, card) != null) failures += "${card.id}\tconflict\t$text"
                            rejected++
                        }
                    }
                }
                val shared = candidates.flatten().toSet().filter { word -> candidates.count { group -> group.any { word in it } } > 1 && card.options.none { normalized(it.label) == word } }
                for (word in shared) {
                    if (SpokenCardAnswerParser.parse(word, card) != null) failures += "${card.id}\tshared\t$word"
                    rejected++
                }
            }
        }
        report("short-and-conflict", accepted + rejected, failures, "accepted=$accepted\nrejected=$rejected\n")
        assertTrue(accepted > 80000); assertTrue(rejected > 30000)
        assertTrue(failures.isEmpty(), "${failures.size} failures\n${failures.take(30).joinToString("\n")}")
    }
    @Test fun everyOptionRejectsNegationReportedSpeechQuotesAndPassengerAddress() {
        val failures = mutableListOf<String>(); var cases = 0
        for (card in cards()) for (option in card.options) {
            for (text in listOf("${option.label} 아니야", "${option.label}이라고 들었어", "민수야 ${option.label}", "'${option.label}'")) {
                if (SpokenCardAnswerParser.parse(text, card) != null) failures += "${card.id}\t$text"
                cases++
            }
        }
        report("unsafe-speech", cases, failures)
        assertEquals(32288, cases)
        assertTrue(failures.isEmpty(), "${failures.size} failures\n${failures.take(20).joinToString("\n")}")
    }
    private fun report(name: String, cases: Int, failures: List<String>, extra: String = "") {
        File("build/reports/native-answer-$name.txt").apply { parentFile.mkdirs() }.writeText("cases=$cases\n${extra}failures=${failures.size}\n" + failures.joinToString("\n"))
    }
}
