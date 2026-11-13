package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class QuestionDifficultyTest {
    @Test fun autoUsesOnlyExplicitlyProvidedPassengerTraits() {
        assertEquals(2, QuestionDifficulty.resolve(DifficultyMode.AUTO, PassengerProfile()))
        assertEquals(1, QuestionDifficulty.resolve(DifficultyMode.AUTO, PassengerProfile(AgeBand.CHILD, QuizPreference.ENTHUSIAST)))
        assertEquals(2, QuestionDifficulty.resolve(DifficultyMode.AUTO, PassengerProfile(AgeBand.TEEN, QuizPreference.ENTHUSIAST)))
        assertEquals(2, QuestionDifficulty.resolve(DifficultyMode.AUTO, PassengerProfile(AgeBand.ADULT, QuizPreference.CASUAL)))
        assertEquals(3, QuestionDifficulty.resolve(DifficultyMode.AUTO, PassengerProfile(AgeBand.ADULT, QuizPreference.ENTHUSIAST)))
    }
    @Test fun explicitDifficultyOverridesTheAutomaticPreference() {
        val child = PassengerProfile(AgeBand.CHILD)
        assertEquals(3, QuestionDifficulty.resolve(DifficultyMode.HIGH, child))
        assertEquals(2, QuestionDifficulty.resolve(DifficultyMode.MEDIUM, child))
        assertEquals(1, QuestionDifficulty.resolve(DifficultyMode.LOW, PassengerProfile(AgeBand.ADULT, QuizPreference.ENTHUSIAST)))
    }
}
