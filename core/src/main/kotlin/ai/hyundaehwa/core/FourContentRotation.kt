package ai.hyundaehwa.core

import kotlin.random.Random

/** A persisted four-slot bag: three quizzes, one local preference card, for every relationship. */
object FourContentRotation {
    val order = listOf("quiz", "quiz", "quiz", "balance")
    fun enabled(@Suppress("UNUSED_PARAMETER") relationship: Relationship) = true
    fun key(topic: Topic): String = when (topic.type) {
        TopicType.QUIZ -> "quiz"
        TopicType.BALANCE -> "balance"
        else -> "" // Weather, rest, story and destination prompts never enter this bag.
    }
    fun newBag(random: Random = Random.Default, last: String? = null): List<String> {
        val bag = order.shuffled(random).toMutableList()
        if (last == "balance" && bag.first() == "balance") {
            val firstQuiz = bag.indexOf("quiz")
            bag[0] = "quiz"; bag[firstQuiz] = "balance"
        }
        return bag
    }
    /** Pure lookup. GPS updates and repeated UI/Gleo reads never redraw the bag. */
    fun next(remaining: List<String>, last: String?, available: Set<String>): String? =
        remaining.firstOrNull { it in available && it in order && !(last == "balance" && it == "balance") }

    /** Remove one matching slot only after a successful presentation. Missing kinds wait. */
    fun consume(remaining: List<String>, key: String, random: Random = Random.Default): List<String> {
        if (key !in order) return remaining
        val index = remaining.indexOf(key)
        if (index < 0) return remaining
        val next = remaining.toMutableList().also { it.removeAt(index) }
        return if (next.isEmpty()) newBag(random, key) else next
    }
}
