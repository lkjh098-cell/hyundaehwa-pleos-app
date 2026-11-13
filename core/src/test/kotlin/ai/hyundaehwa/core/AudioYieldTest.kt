package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class AudioYieldTest {
    @Test fun formalRelationshipsReceiveLocalBalanceThroughTheSameBag() {
        for (relationship in listOf(Relationship.BOSS, Relationship.STRANGER)) {
            var s = HyundaehwaEngine.simulation().copy(enabled = true, gear = Gear.D, speedKph = 50.0,
                relationship = relationship, destination = "", silenceThresholdMs = 3_000, rotationRemaining = listOf("balance", "quiz", "quiz", "quiz"))
            s = HyundaehwaEngine.reduce(s, ConversationEvent.RegionalTopicsChanged(s.regionId,
                listOf(Topic("b", TopicType.BALANCE, s.regionId, "선택"))))
            s = HyundaehwaEngine.reduce(s, ConversationEvent.Tick(3_000, 1_000))
            assertEquals("b", s.output?.topicId)
            assertEquals(4, s.rotationRemaining.size)
            s = HyundaehwaEngine.reduce(s, ConversationEvent.LocalTopicDisplayed("b"))
            assertEquals(listOf("quiz", "quiz", "quiz"), s.rotationRemaining)
        }
    }
}
