package ai.hyundaehwa.car

import ai.hyundaehwa.car.platform.AdministrativeConversationLoader
import ai.hyundaehwa.core.*
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Preview-only: explicit simulated route events with the Activity's real local TTS backend. */
@RunWith(AndroidJUnit4::class)
class NativeRegionSpeechLifecycleIntegrationTest {
    @Test(timeout = 150_000L)
    fun localSpeechFinishesOriginalRegionThenSafetyResumesQuestion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("ai.hyundaehwa.car.preview", context.packageName)
        val prefs = context.getSharedPreferences("conversation_profile", Context.MODE_PRIVATE)
        val keys = setOf("spoken_guidance", "audio_setup_seen", "on_device_card_answers", "cabin_sound_monitor")
        val original = prefs.all.filterKeys { it in keys }
        var activity: MainActivity? = null
        var failure: Throwable? = null
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun state() = field("state").get(activity) as ConversationState
        fun voice() = field("voiceState").get(activity) as VoicePlaybackState
        fun call(name: String, types: Array<Class<*>>, vararg args: Any?) {
            MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(activity, *args)
        }
        fun dispatch(event: ConversationEvent) = call("dispatch", arrayOf(ConversationEvent::class.java), event)
        fun await(description: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < until) {
                var matched = false
                instrumentation.runOnMainSync { matched = condition() }
                if (matched) return
                Thread.sleep(100)
            }
            fail("Timed out: $description")
        }
        try {
            assertTrue(prefs.edit().putBoolean("spoken_guidance", true).putBoolean("audio_setup_seen", true)
                .putBoolean("on_device_card_answers", false).putBoolean("cabin_sound_monitor", false).commit())
            requireNotNull(AdministrativeConversationLoader.load(context.assets))
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            await("preview activity resumed") { field("resumed").getBoolean(activity) }
            instrumentation.runOnMainSync {
                field("emulatorMode").setBoolean(activity, true) // All following route signals are explicitly simulated.
                (field("bridge").get(activity) as? ai.hyundaehwa.car.platform.PleosPlatformBridge)?.stop()
                (field("parkedDialog").get(activity) as? AlertDialog)?.dismiss()
                field("automaticPassengerPromptShown").setBoolean(activity, true)
                field("relationshipChosen").setBoolean(activity, true)
                field("welcomePending").setBoolean(activity, false)
                field("emulatorTab").setBoolean(activity, false)
                (field("regionEntry").get(activity) as RegionEntryQueue).clear()
                val initial = HyundaehwaEngine.simulation(Scenario.COLLEAGUE).copy(
                    requireEntryDelivery = true, enabled = true, gear = Gear.D, speedKph = 70.0,
                    mediaActive = false, rotationRemaining = listOf("quiz", "quiz", "quiz", "balance"))
                field("state").set(activity, initial)
                call("observeRegionEntry", arrayOf(String::class.java, String::class.java, String::class.java),
                    initial.regionId, initial.regionLabel, initial.destination)
                call("refreshRegionalTopics", emptyArray())
                call("render", arrayOf(Boolean::class.javaPrimitiveType!!), true)
                call("playPendingOutput", emptyArray())
            }
            val areaA = Scenario.COLLEAGUE.regionId
            val areaB = Scenario.FAMILY.regionId
            await("actual local speech playback for region A") {
                voice().phase == VoicePhase.PLAYING && (field("localPrompt").get(activity) as? Utterance)?.kind == "region-entry"
            }
            var entryId = ""
            instrumentation.runOnMainSync {
                entryId = requireNotNull((field("regionEntry").get(activity) as RegionEntryQueue).active).speechId
                dispatch(ConversationEvent.RegionChanged(areaB, Scenario.FAMILY.regionLabel, state().destination, "simulated-test"))
                call("refreshRegionalTopics", emptyArray())
                assertEquals("Entry A remains pinned after B signal", areaA, state().pinnedEntryRegionId)
            }
            await("actual entry completion releases A question") {
                state().topic?.regionId == areaA && state().topic?.id == (field("pinnedCard").get(activity) as? AdministrativeConversationTopic)?.id
            }
            var topicId = ""
            await("actual local question speech begins") {
                val prompt = field("localPrompt").get(activity) as? Utterance
                if (prompt?.kind == "topic" && voice().phase == VoicePhase.PLAYING) { topicId = prompt.id; true } else false
            }
            instrumentation.runOnMainSync {
                // Regression: pinCard clears recognition synchronously; its callback must not
                // recursively render, duplicate the content container, or record before mount.
                val body = field("body").get(activity) as android.widget.LinearLayout
                val question = requireNotNull(state().topic)
                assertEquals("The Activity keeps exactly one mounted content pane", 1, body.childCount)
                assertTrue("The mounted question is recorded", state().usedTopicIds.contains(question.id))
                assertEquals("Only one rotation slot is consumed", 3, state().rotationRemaining.size)
                assertTrue("The original region is marked presented after mount", state().presentedRegionIds.contains(areaA))
                assertEquals("Question stays associated with entry A", areaA, question.regionId)
                // Simulate an early failed attempt while this exact question is still speaking.
                // Its real completion after safety resume must re-arm the first answer window.
                val automaticAnswer = field("answerVoiceStart").get(activity) as CardAnswerVoiceAutoStart
                automaticAnswer.attemptedCardId = question.id
                assertFalse(automaticAnswer.questionReady)
                dispatch(ConversationEvent.FocusChanged(FocusKind.JUNCTION, true))
            }
            var remaining = ""
            await("safety holds a sentence checkpoint", 8_000) {
                val pending = (field("promptResume").get(activity) as PromptResumeQueue).pending
                if (pending?.utterance?.id == topicId && pending.remainingText.isNotBlank()) { remaining = pending.remainingText; true } else false
            }
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                assertTrue(state().guards.contains(FocusKind.JUNCTION))
                assertNotNull((field("promptResume").get(activity) as PromptResumeQueue).pending)
                assertEquals("A card remains displayed during safety hold", areaA, state().topic?.regionId)
                dispatch(ConversationEvent.FocusChanged(FocusKind.JUNCTION, false))
            }
            await("actual local question speech resumes") {
                voice().requestId == topicId && voice().phase == VoicePhase.PLAYING &&
                    (field("localPrompt").get(activity) as? Utterance)?.text == remaining
            }
            await("completed resumed question clears its stale first-answer attempt") {
                val automaticAnswer = field("answerVoiceStart").get(activity) as CardAnswerVoiceAutoStart
                voice().requestId == topicId && voice().phase == VoicePhase.COMPLETED &&
                    automaticAnswer.questionReady && automaticAnswer.attemptedCardId == null
            }
            instrumentation.runOnMainSync {
                assertFalse("This test must not open the microphone", field("answerVoiceOptIn").getBoolean(activity))
                assertEquals("Real completed question remains displayed", areaA, state().topic?.regionId)
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("region_safety_observation", "SIMULATED route A=$areaA to B=$areaB; actual local TTS callbacks; entry=$entryId; pinned question=$topicId; safety checkpoint resumed and completed; stale first-answer attempt cleared. Physical audibility, actual GPS and vehicle safety not verified.")
            })
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            var cleanup: Throwable? = null
            try { activity?.let { a -> instrumentation.runOnMainSync { a.finish() } } } catch (t: Throwable) { cleanup = t }
            try {
                val edit = prefs.edit()
                keys.forEach { key -> val value = original[key]; if (value is Boolean) edit.putBoolean(key, value) else edit.remove(key) }
                assertTrue(edit.commit())
            } catch (t: Throwable) { if (cleanup == null) cleanup = t else cleanup!!.addSuppressed(t) }
            cleanup?.let { if (failure == null) throw it else failure!!.addSuppressed(it) }
        }
    }
}
