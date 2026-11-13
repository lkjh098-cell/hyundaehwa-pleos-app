package ai.hyundaehwa.core

/** Explicit passenger preference only. Vehicle sensors do not reveal age or quiz ability. */
enum class AgeBand(val label: String) { UNKNOWN("선택 안 함"), CHILD("어린이"), TEEN("청소년"), ADULT("성인") }
enum class QuizPreference(val label: String) { CASUAL("가볍게"), ENTHUSIAST("퀴즈 좋아함") }
enum class DifficultyMode(val label: String) { AUTO("자동"), LOW("하"), MEDIUM("중"), HIGH("상") }
data class PassengerProfile(val youngest: AgeBand = AgeBand.UNKNOWN, val preference: QuizPreference = QuizPreference.CASUAL) {
    val audience: String get() = if (youngest == AgeBand.CHILD) "family" else "adult"
}
object QuestionDifficulty {
    fun resolve(mode: DifficultyMode, profile: PassengerProfile): Int = when (mode) {
        DifficultyMode.LOW -> 1
        DifficultyMode.MEDIUM -> 2
        DifficultyMode.HIGH -> 3
        DifficultyMode.AUTO -> when (profile.youngest) {
            AgeBand.CHILD -> 1
            AgeBand.TEEN -> 2
            AgeBand.UNKNOWN -> 2
            AgeBand.ADULT -> if (profile.preference == QuizPreference.ENTHUSIAST) 3 else 2
        }
    }
    fun label(value: Int): String = when (value) { 1 -> "하"; 3 -> "상"; else -> "중" }
}
