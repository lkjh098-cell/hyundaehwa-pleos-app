package ai.hyundaehwa.core

import kotlin.test.*
import org.junit.Test

class HyundaehwaEngineTest {
    private fun step(s: ConversationState, e: ConversationEvent) = HyundaehwaEngine.reduce(s, e)
    private fun tick(s: ConversationState, dt: Long, wall: Long = 1000) = step(if (s.rms != null) step(s, ConversationEvent.SoundLevel(s.rms)) else s, ConversationEvent.Tick(dt, wall))
    private fun primed(s: ConversationState) = s.copy(regionStableMs = 3_000, silenceMs = s.silenceThresholdMs, rms = 0.01, soundObservedAtMs = s.wallElapsedMs)
    private fun drive(scenario: Scenario = Scenario.COLLEAGUE, relationship: Relationship? = null): ConversationState {
        var s = HyundaehwaEngine.simulation(scenario).copy(rotationRemaining = FourContentRotation.order, silenceThresholdMs = 3_000)
        if (relationship != null) s = step(s, ConversationEvent.RelationshipChanged(relationship))
        val quizId = when (scenario) { Scenario.FAMILY -> "pass-quiz"; Scenario.FRIEND -> "daegu-quiz"; else -> "anseong-brass" }
        val storyId = when (scenario) { Scenario.FAMILY -> "pass-pressure"; Scenario.FRIEND -> "daegu-food"; else -> "test-story" }
        val cards = listOf(Topic(storyId, TopicType.TRIVIA, s.regionId, "테스트 이야기"),
            Topic(quizId, TopicType.QUIZ, s.regionId, "테스트 퀴즈", "정답"),
            Topic("test-balance", TopicType.BALANCE, s.regionId, "테스트 선택"))
        s = step(s, ConversationEvent.RegionalTopicsChanged(s.regionId, cards))
        return step(step(s, ConversationEvent.Start), ConversationEvent.GearChanged(Gear.D, 80.0))
    }
    @Test fun realModeNeverInfersSeatsOrSilenceFromAbsentSignals() {
        val s = step(step(HyundaehwaEngine.initial(), ConversationEvent.Start), ConversationEvent.GearChanged(Gear.D, 80.0))
        val after = tick(s, 300_000)
        assertNull(after.output)
        assertEquals("unavailable", after.seatSource)
        assertNull(after.rms)
        assertEquals("", after.destination)
    }
    @Test fun firstTopicWaitsForQuietAndStableRegion() {
        var s = drive()
        s = step(s, ConversationEvent.SilenceThresholdChanged(5_000))
        repeat(4) { s = tick(step(s, ConversationEvent.SoundLevel(0.01)), 1000) }
        assertNull(s.output)
        s = tick(step(s, ConversationEvent.SoundLevel(0.01)), 1000)
        assertNotNull(s.topic)
        assertEquals(0L, s.silenceMs)
    }
    @Test fun enablingWhileParkedDoesNotCreateTopics() {
        val s = tick(step(HyundaehwaEngine.simulation(), ConversationEvent.Start), 3_000_000)
        assertNull(s.output)
        assertEquals(0L, s.silenceMs)
    }
    @Test fun timeDoesNotCreateAnotherTopicInSameRegion() {
        var s = tick(drive(), 300_000)
        val id = s.topic?.id
        s = step(s, ConversationEvent.OutputDone())
        s = tick(s, 599_999)
        assertEquals(1, s.usedTopicIds.size)
        s = tick(s, 1)
        assertEquals(1, s.usedTopicIds.size)
        assertEquals(id, s.topic?.id)
        assertNull(s.output)
    }
    @Test fun obsoleteSoundEventsCannotBlockOrInterruptRegionalContent() {
        var s = tick(drive(), 3000)
        val output = s.output
        s = step(s, ConversationEvent.SoundLevel(0.9))
        s = step(s, ConversationEvent.SoundUnavailable())
        assertEquals(output, s.output); assertNotEquals("conversation", HyundaehwaEngine.view(s).status)
        assertTrue(HyundaehwaEngine.view(s).canSpeak)
    }
    @Test fun passengerSoundDoesNotStopAppSpeechOrStartASilenceClock() {
        var s = step(tick(drive(), 3000), ConversationEvent.SpeechChanged(true))
        val output = s.output
        s = step(s, ConversationEvent.SoundLevel(0.8)); s = tick(s, 600000)
        assertEquals(output, s.output); assertTrue(s.speechActive); assertEquals(0L, s.silenceMs)
    }
    @Test fun eachFocusGateInterruptsSpeechAndResetsQuietPeriod() {
        FocusKind.entries.forEach { kind ->
            val before = step(tick(drive(), 300_000), ConversationEvent.SpeechChanged(true))
            var s = step(before, ConversationEvent.FocusChanged(kind, true))
            assertNull(s.output)
            assertFalse(s.speechActive)
            assertFalse(HyundaehwaEngine.view(s).canSpeak)
            s = tick(s, 600_000)
            s = step(s, ConversationEvent.FocusChanged(kind, false))
            assertNull(s.output)
            assertNull(tick(s, 300_000).output)
            assertNotNull(before.output)
        }
    }
    @Test fun soloOrRearOnlyTravelCanReceiveARealBankCardAndStillRetainsRest() {
        var s = step(drive(Scenario.SOLO), ConversationEvent.SeatsChanged(false, 2))
        s = tick(s, 300_000)
        assertEquals("anseong-brass", s.topic?.id)
        s = tick(step(s, ConversationEvent.OutputDone()), 5_700_000)
        assertTrue(s.restDue); assertNull(s.output) // Current card keeps its feedback slot.
        s = step(s, ConversationEvent.ReleaseTopic)
        assertEquals("rest", s.output?.kind)
    }
    @Test fun sixtyMinutesCreatesOneRestPrompt() {
        var s = tick(drive(Scenario.SOLO), 3_599_999)
        assertFalse(s.restDue)
        s = tick(s, 1)
        assertTrue(s.restDue)
        val id = s.output?.id
        s = tick(s, 6_000_000)
        assertEquals(id, s.output?.id)
        s = step(s, ConversationEvent.OutputDone())
        assertNull(tick(s, 6_000_000).output)
    }
    @Test fun focusDefersRestAndQuietKeepsRestSpeech() {
        var s = step(drive(), ConversationEvent.FocusChanged(FocusKind.TUNNEL, true))
        s = tick(s, 6_000_000)
        assertTrue(s.restDue)
        assertNull(s.output)
        s = step(s, ConversationEvent.FocusChanged(FocusKind.TUNNEL, false))
        s = step(s, ConversationEvent.SpeechChanged(true))
        s = step(s, ConversationEvent.QuietChanged(true))
        assertEquals("rest", s.output?.kind)
        assertTrue(s.speechActive)
    }
    @Test fun confirmedParkedRestIsTheOnlyReset() {
        var s = tick(drive(), 6_000_000)
        assertEquals(6_000_000L, step(s, ConversationEvent.RestConfirmed).drivingMs)
        s = step(s, ConversationEvent.GearChanged(Gear.P))
        assertEquals(6_000_000L, s.drivingMs)
        s = step(s, ConversationEvent.RestConfirmed)
        assertEquals(0L, s.drivingMs)
        assertFalse(s.restDue)
    }
    @Test fun relationshipAndScenarioChangesRequireP() {
        val s = drive()
        assertEquals(Relationship.COLLEAGUE, step(s, ConversationEvent.RelationshipChanged(Relationship.BOSS)).relationship)
        assertEquals(Scenario.COLLEAGUE, step(s, ConversationEvent.ScenarioChanged(Scenario.FAMILY)).scenario)
        val parked = step(s, ConversationEvent.GearChanged(Gear.P))
        assertEquals(Relationship.BOSS, step(parked, ConversationEvent.RelationshipChanged(Relationship.BOSS)).relationship)
        assertEquals(Scenario.FAMILY, step(parked, ConversationEvent.ScenarioChanged(Scenario.FAMILY)).scenario)
    }
    @Test fun mediaAndUnavailableMicrophoneBothHoldRegion() {
        var s = step(drive(), ConversationEvent.MediaChanged(true))
        s = tick(s, 600000); assertNull(s.output); assertEquals("media-hold", HyundaehwaEngine.view(s).status)
        s = step(s, ConversationEvent.SoundUnavailable())
        s = step(s, ConversationEvent.MediaChanged(false))
        assertNull(s.output); assertTrue(HyundaehwaEngine.view(s).canSpeak)
        s = tick(step(s, ConversationEvent.SoundLevel(0.01)), 3000)
        assertNotNull(s.output)
    }
    @Test fun formalRelationshipsRetainRegionalSafetyAndNoRepeatGuards() {
        listOf(Relationship.BOSS, Relationship.STRANGER).forEach { relationship ->
            var s = drive(relationship = relationship)
            val emittedIds = mutableListOf<String>()
            repeat(6) {
                s = tick(step(s, ConversationEvent.OutputDone()), 600_000)
                s.topic?.let { assertTrue(it.type in listOf(TopicType.TRIVIA, TopicType.QUIZ, TopicType.BALANCE)) }
                s.output?.topicId?.let { emittedIds.add(it) }
            }
            assertTrue(emittedIds.isNotEmpty())
            assertTrue(emittedIds.zipWithNext().all { (a, b) -> a != b })
        }
    }
    @Test fun representativeRegionalQuizFixturesAreAvailable() {
        assertEquals("pass-quiz", tick(drive(Scenario.FAMILY), 300_000).topic?.id)
        assertEquals("daegu-quiz", tick(drive(Scenario.FRIEND), 300_000).topic?.id)
        assertEquals(3, drive().regionalTopics.map { it.type }.toSet().size)
        assertTrue(HyundaehwaEngine.initial().regionalTopics.isEmpty())
    }
    @Test fun missingDestinationNeverProducesDestinationPromptsOrBorrowedScenarioContent() {
        var s = step(drive(), ConversationEvent.RegionChanged("seoul", "서울", "", "pleos-navigation"))
        s = step(s, ConversationEvent.RegionalTopicsChanged("seoul", listOf(
            Topic("seoul-test", TopicType.QUIZ, "seoul", "서울 퀴즈", "정답"))))
        val emitted = mutableListOf<Topic>()
        repeat(5) {
            s = tick(step(s, ConversationEvent.OutputDone()), 600_000)
            s.output?.let { s.topic?.let(emitted::add) }
        }
        assertTrue(emitted.isNotEmpty())
        assertTrue(emitted.all { it.regionId == "seoul" })
        assertTrue(emitted.none { "No destination" in it.text || "세종" in it.text })
        // Even an inconsistent destination-region snapshot cannot bypass the missing-value gate.
        val inconsistent = step(drive(), ConversationEvent.RegionChanged("destination", "Near destination", ""))
        assertNull(tick(inconsistent, 300_000).output)
        val unknownArea = step(drive(), ConversationEvent.RegionChanged("", "Location unavailable", ""))
        assertNull(tick(unknownArea, 300_000).output)
    }
    @Test fun negativeTimeAndStaleOutputCompletionCannotChangeProgress() {
        var s = tick(drive(), 300_000)
        val old = s.output!!.id
        s = tick(step(s, ConversationEvent.OutputDone()), 600_000)
        assertEquals(s.output, step(s, ConversationEvent.OutputDone(old)).output)
        assertEquals(s.drivingMs, tick(s, -100).drivingMs)
        assertNull(step(s, ConversationEvent.Stop).output)
    }
    @Test fun oneCardPerRegionSurvivesAnswerTimeAndPreferenceChangesAndRevisit() {
        var s = tick(enterRegion(drive(), "region-b"), 3000)
        assertEquals("region-b-story", s.output?.topicId)
        s = step(s, ConversationEvent.OutputDone())
        s = step(s, ConversationEvent.GearChanged(Gear.P))
        s = step(s, ConversationEvent.RelationshipChanged(Relationship.FAMILY))
        s = step(s, ConversationEvent.PreferencesChanged)
        s = step(s, ConversationEvent.GearChanged(Gear.D, 40.0))
        s = tick(s, 600_000)
        assertNull(s.output)
        s = tick(enterRegion(s, "region-c"), 3000)
        assertEquals("region-b-story", s.topic?.id)
        s = step(s, ConversationEvent.ReleaseTopic)
        s = tick(s, 3000)
        assertEquals("region-c-story", s.output?.topicId)
        s = tick(enterRegion(step(s, ConversationEvent.OutputDone()), "region-b"), 600_000)
        assertNull(s.output)
        val afterExternal = step(s, ConversationEvent.ExternalTopicPresented("region-b", "region-b-story"))
        assertEquals(s, afterExternal)
    }
    private fun enterRegion(s: ConversationState, id: String = "region-b"): ConversationState {
        var changed = step(s, ConversationEvent.RegionChanged(id, "테스트시", "목적지"))
        changed = step(changed, ConversationEvent.RegionalTopicsChanged(id, listOf(Topic("$id-story", TopicType.QUIZ, id, "이 지역 이야기"))))
        return changed
    }
    @Test fun parkingPreservesQuestionButCancelsPendingSpeech() {
        val original = tick(drive(), 300_000)
        val parked = step(original, ConversationEvent.GearChanged(Gear.P))
        assertEquals(original.topic, parked.topic)
        assertNull(parked.output)
        assertFalse(parked.speechActive)
        assertEquals("topic", HyundaehwaEngine.view(parked).status)
        assertEquals(parked.topic, step(parked, ConversationEvent.RelationshipChanged(Relationship.FAMILY)).topic)
    }
    @Test fun stableRegionWaitsForQuietAndRepeatedSnapshotsDoNotResetClock() {
        var s = enterRegion(drive())
        s = tick(s, 2000)
        assertNull(s.output)
        s = step(s, ConversationEvent.RegionChanged("region-b", "테스트시", "목적지"))
        s = tick(s, 1000)
        assertEquals("region-b-story", s.topic?.id)
        assertEquals("이 지역 이야기", s.output!!.text) // The adapter voices entry separately before releasing this card.
        assertEquals(0L, s.silenceMs)
    }
    @Test fun regionWaitsForSafetyAndCorrectBankAndDoesNotBorrowOldArea() {
        var s = step(drive(), ConversationEvent.FocusChanged(FocusKind.TUNNEL, true))
        s = step(s, ConversationEvent.RegionChanged("region-b", "테스트시", "목적지"))
        s = tick(s, 4000)
        s = step(s, ConversationEvent.FocusChanged(FocusKind.TUNNEL, false))
        assertNull(s.output)
        s = step(s, ConversationEvent.RegionalTopicsChanged("old", listOf(Topic("old", TopicType.QUIZ, "old", "다른 지역"))))
        assertNull(s.output)
        s = step(s, ConversationEvent.RegionalTopicsChanged("region-b", listOf(Topic("new", TopicType.QUIZ, "region-b", "새 지역"))))
        assertNull(s.output)
        s = tick(s, 3000)
        assertEquals("new", s.output?.topicId)
    }
    @Test fun unknownAndRapidRegionChangesClearStaleOutputAndAvoidDuplicateEntry() {
        var s = tick(enterRegion(drive()), 3000)
        val oldOutput = s.output!!.id
        s = step(s, ConversationEvent.RegionChanged("", "위치 미확인", "목적지"))
        assertEquals("region-b-story", s.topic?.id); assertNull(s.output)
        s = enterRegion(s)
        s = tick(s, 4000)
        assertNull(s.output)
        s = enterRegion(s, "region-c")
        s = tick(s, 2000)
        assertNull(s.output)
        s = enterRegion(s)
        s = tick(s, 4000)
        assertNull(s.output)
        s = enterRegion(s, "region-c")
        s = tick(s, 3000)
        assertEquals("region-b-story", s.topic?.id)
        s = step(s, ConversationEvent.ReleaseTopic)
        s = tick(s, 3000)
        assertEquals("region-c-story", s.output?.topicId)
        assertEquals(s.output, step(s, ConversationEvent.OutputDone(oldOutput)).output)
    }
    @Test fun unknownMediaSuppressesAndResumesPendingRegionAndRest() {
        var s = step(drive(), ConversationEvent.MediaChanged(null))
        s = tick(enterRegion(s), 4000)
        assertNull(s.output)
        assertFalse(HyundaehwaEngine.view(s).canSpeak)
        assertEquals("media-unknown", HyundaehwaEngine.view(s).status)
        s = tick(step(s, ConversationEvent.MediaChanged(false)), 3_000)
        assertEquals("region-b-story", s.output?.topicId)
        s = step(s, ConversationEvent.MediaChanged(null))
        assertNull(s.output)
        s = tick(s, 6_000_000)
        assertTrue(s.restDue)
        assertNull(s.output)
        s = tick(step(s, ConversationEvent.MediaChanged(false)), 3_000)
        assertNull(s.output); assertFalse(s.restAnnounced)
        s = step(s, ConversationEvent.ReleaseTopic)
        assertEquals("rest", s.output?.kind)
    }

    @Test fun snapshotBatchCannotConsumeOldRegionBeforeSafetyAndLocationAreApplied() {
        val a = enterRegion(drive(), "region-a").copy(regionStableMs = 3_000, silenceMs = 0)
        val oldCards = a.usedTopicIds
        val batch = HyundaehwaEngine.reduceBatch(a, listOf(
            ConversationEvent.GearChanged(Gear.D, 70.0),
            ConversationEvent.MediaChanged(null),
            ConversationEvent.FocusChanged(FocusKind.JUNCTION, true),
            ConversationEvent.RegionChanged("region-b", "새 지역", "목적지")
        ))
        assertNull(batch.output)
        assertNull(batch.topic)
        assertEquals(oldCards, batch.usedTopicIds)
        assertEquals("region-b", batch.pendingRegionId)
        assertNull(batch.mediaActive)
        assertTrue(FocusKind.JUNCTION in batch.guards)
    }

    @Test fun externalPresentationConsumesPendingRegionWithoutDuplicateSpeech() {
        val initial = primed(enterRegion(drive()))
        val confirmed = step(initial, ConversationEvent.ExternalTopicPresented("region-b", "region-b-story"))
        assertNull(confirmed.output)
        assertEquals("region-b", confirmed.lastAnnouncedRegionId)
        assertEquals("region-b-story", confirmed.lastTopicId)
        assertTrue("region-b-story" in confirmed.usedTopicIds)
        assertEquals(1, confirmed.topicFormatCursor)
        assertNull(tick(confirmed, 3000).output)
    }
    @Test fun invalidExternalPresentationCannotConsumePendingRegion() {
        val initial = enterRegion(drive())
        assertEquals(initial, step(initial, ConversationEvent.ExternalTopicPresented("region-c", "region-b-story")))
        assertEquals(initial, step(initial, ConversationEvent.ExternalTopicPresented("region-b", "unknown")))
        assertEquals("region-b-story", tick(initial, 3000).output?.topicId)
    }

    @Test fun parkedCardSurvivesUnknownFocusAndMissingMicrophone() {
        val original = tick(drive(), 300_000)
        var s = step(original, ConversationEvent.GearChanged(Gear.P))
        s = step(s, ConversationEvent.FocusChanged(FocusKind.JUNCTION, true))
        s = step(s, ConversationEvent.SoundUnavailable())
        assertEquals(original.topic, s.topic)
        assertEquals("topic", HyundaehwaEngine.view(s).status)
        assertFalse(HyundaehwaEngine.view(s).canSpeak)
        val moving = step(s, ConversationEvent.GearChanged(Gear.D, 50.0))
        assertEquals("focus", HyundaehwaEngine.view(moving).status)
        assertFalse(HyundaehwaEngine.view(moving).canSpeak)
        assertEquals(s.topic, step(s, ConversationEvent.RegionChanged("", "위치 미확인", "")).topic)
    }

    @Test fun parkedPreferenceChangeKeepsCardAndHistory() {
        val displayed = tick(drive(), 300_000)
        val parked = step(displayed, ConversationEvent.GearChanged(Gear.P))
        val updated = step(parked, ConversationEvent.PreferencesChanged)
        assertEquals(parked.topic, updated.topic); assertNull(updated.output); assertFalse(updated.speechActive)
        assertEquals(0L, updated.silenceMs)
        assertEquals(parked.usedTopicIds, updated.usedTopicIds)
        assertEquals(parked.pendingRegionId, updated.pendingRegionId)
    }
    @Test fun drivingPreferenceChangeIsIgnoredWithNotice() {
        val moving = drive()
        val updated = step(moving, ConversationEvent.PreferencesChanged)
        assertEquals(moving.topic, updated.topic)
        assertEquals(moving.usedTopicIds, updated.usedTopicIds)
        assertEquals(moving.pendingRegionId, updated.pendingRegionId)
        assertTrue(updated.notice.contains("정차"))
    }

    @Test fun skippingRestKeepsDriveTimeAndReleasesThePrompt() {
        var s = tick(drive(), 3_600_000L)
        assertTrue(s.restDue)
        s = step(s, ConversationEvent.RestCancelled)
        assertFalse(s.restDue); assertNull(s.output); assertEquals(3_600_000L,s.drivingMs)
        assertEquals("topic", tick(s, 300_000).output?.kind)
    }
    @Test fun arrivalGreetedOnceOnlyAfterPriorityHoldsLift() {
        var s = step(drive(), ConversationEvent.MediaChanged(true))
        s = step(s, ConversationEvent.ArrivalChanged(true))
        assertNull(s.output)
        s = tick(step(s, ConversationEvent.MediaChanged(false)), 3_000)
        assertEquals("arrival", s.output?.kind)
        s = step(s, ConversationEvent.OutputDone())
        assertNull(step(s, ConversationEvent.ArrivalChanged(true)).output)
        assertFalse(step(s, ConversationEvent.ArrivalChanged(false)).arrived)
    }
    @Test fun sharedQuestionIdDoesNotRepeatInAnotherRegionEvenWhenExhausted() {
        var s = step(drive(), ConversationEvent.RegionChanged("one", "첫 지역", "목적지"))
        s = step(s, ConversationEvent.RegionalTopicsChanged("one", listOf(Topic("shared", TopicType.QUIZ, "one", "공통 문제"))))
        s = tick(s, 3000)
        assertEquals("shared", s.topic?.id)
        s = step(s, ConversationEvent.RegionChanged("two", "다음 지역", "목적지"))
        s = step(s, ConversationEvent.RegionalTopicsChanged("two", listOf(Topic("shared", TopicType.QUIZ, "two", "공통 문제"))))
        s = tick(s, 3000)
        s = step(s, ConversationEvent.ReleaseTopic)
        assertNull(s.topic)
        assertNull(s.output)
        assertTrue("shared" in s.usedTopicIds)
    }
}
