package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class ParkedEvidenceTest {
    private val now = 20_000L
    private fun evidence(gear: Gear? = Gear.P, speed: Double? = 0.0, connected: Boolean = true,
        gearAt: Long? = now, speedAt: Long? = now, ux: Boolean? = true) =
        ParkedEvidence(connected, gear, speed, gearAt, speedAt, now, ux)
    private fun apply(e: ParkedEvidence) = HyundaehwaEngine.reduce(HyundaehwaEngine.initial(), ConversationEvent.VehicleEvidenceChanged(e))
    @Test fun actualModeStartsUnknownAndLegacyGearEventDoesNotAuthorizeSetup() {
        val state = HyundaehwaEngine.initial()
        assertFalse(HyundaehwaEngine.canConfigure(state, now))
        val legacy = HyundaehwaEngine.reduce(state, ConversationEvent.GearChanged(Gear.P))
        assertFalse(HyundaehwaEngine.canConfigure(legacy, now))
        assertEquals(state.relationship, HyundaehwaEngine.reduce(legacy, ConversationEvent.RelationshipChanged(Relationship.FAMILY)).relationship)
    }
    @Test fun setupRequiresFreshParkAndZeroSpeedAndUnrestrictedUx() {
        val good = apply(evidence())
        assertTrue(HyundaehwaEngine.canConfigure(good, now))
        assertEquals(Relationship.FAMILY, HyundaehwaEngine.reduce(good, ConversationEvent.RelationshipChanged(Relationship.FAMILY)).relationship)
        val bad = listOf(evidence(speed = 0.1), evidence(speed = Double.NaN), evidence(speed = null),
            evidence(gear = null), evidence(gear = Gear.D), evidence(gear = Gear.R), evidence(gear = Gear.N),
            evidence(connected = false), evidence(ux = false), evidence(ux = null),
            evidence(gearAt = now - 8_001), evidence(speedAt = null), evidence(speedAt = now + 1))
        bad.forEach { sample ->
            val s = apply(sample)
            assertFalse(HyundaehwaEngine.canConfigure(s, now), "Unexpected setup access: $sample")
            assertEquals(s.relationship, HyundaehwaEngine.reduce(s, ConversationEvent.RelationshipChanged(Relationship.FAMILY)).relationship)
            assertFalse(HyundaehwaEngine.view(s).canChangeRelationship)
        }
        assertEquals(0.1, apply(evidence(speed = 0.1)).speedKph)
    }
    @Test fun permissionExpiresAndDisconnectOrDepartureRevokesIt() {
        var state = apply(evidence())
        assertTrue(HyundaehwaEngine.canConfigure(state, now + 8_000))
        assertFalse(HyundaehwaEngine.canConfigure(state, now + 8_001))
        state = HyundaehwaEngine.reduce(state, ConversationEvent.Tick(0, 8_001))
        assertFalse(HyundaehwaEngine.view(state).canChangeRelationship)
        assertEquals(state.relationship, HyundaehwaEngine.reduce(state, ConversationEvent.RelationshipChanged(Relationship.FAMILY)).relationship)
        state = HyundaehwaEngine.reduce(state, ConversationEvent.VehicleEvidenceChanged(evidence(gear = Gear.D, speed = 30.0)))
        assertFalse(HyundaehwaEngine.canConfigure(state, now))
        state = HyundaehwaEngine.reduce(state, ConversationEvent.VehicleEvidenceChanged(evidence(connected = false)))
        assertFalse(HyundaehwaEngine.canConfigure(state, now))
    }
    @Test fun repeatedParkedSnapshotsKeepRegionSpeechButUnsafeChangesStopIt() {
        val parked = apply(evidence()).copy(enabled = true, mediaActive = false, regionId = "test", destination = "test",
            output = Utterance("entry", "region-entry", "지역에 진입했습니다."), speechActive = true)
        val next = evidence().copy(observedAtMs = now + 1_000, gearAtMs = now + 1_000, speedAtMs = now + 1_000)
        val stable = HyundaehwaEngine.reduceBatch(parked, listOf(
            ConversationEvent.VehicleEvidenceChanged(next), ConversationEvent.MediaChanged(false),
            ConversationEvent.FocusChanged(FocusKind.JUNCTION, false), ConversationEvent.RegionChanged("test", "지역", "test")))
        assertEquals(parked.output, stable.output)
        assertTrue(stable.speechActive)
        listOf(evidence(connected = false), evidence(ux = false), evidence(speed = 0.1),
            evidence(gear = Gear.D, speed = 30.0), evidence(speedAt = now - 8_001)).forEach { unsafe ->
            val result = HyundaehwaEngine.reduce(parked, ConversationEvent.VehicleEvidenceChanged(unsafe))
            assertNull(result.output, "Unsafe evidence retained speech: $unsafe")
            assertFalse(result.speechActive)
        }
        val stoppedEvidence = evidence(gear = Gear.D)
        val stopped = apply(stoppedEvidence).copy(enabled = true, mediaActive = false, regionId = "test", destination = "test",
            output = Utterance("entry-d", "region-entry", "지역에 진입했습니다."), speechActive = true)
        val stoppedAgain = HyundaehwaEngine.reduce(stopped, ConversationEvent.VehicleEvidenceChanged(stoppedEvidence))
        assertEquals(stopped.output, stoppedAgain.output)
        assertTrue(stoppedAgain.speechActive)
        val guarded = HyundaehwaEngine.reduceBatch(parked, listOf(ConversationEvent.VehicleEvidenceChanged(next),
            ConversationEvent.FocusChanged(FocusKind.JUNCTION, true)))
        assertNull(guarded.output)
        assertFalse(guarded.speechActive)
    }
    @Test fun demoRemainsExplicitAndCannotMaskPositiveParkSpeed() {
        var state = HyundaehwaEngine.simulation()
        assertTrue(HyundaehwaEngine.canConfigure(state))
        state = HyundaehwaEngine.reduce(state, ConversationEvent.GearChanged(Gear.P, 3.0))
        assertEquals(3.0, state.speedKph)
        assertFalse(HyundaehwaEngine.canConfigure(state))
    }
}
