package ai.hyundaehwa.car

import ai.hyundaehwa.car.platform.AdministrativeConversationLoader
import ai.hyundaehwa.car.platform.PleosPlatformBridge
import ai.hyundaehwa.core.*
import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in acoustic-input test. Route events are simulated; question playback, first automatic
 * microphone start, recognizer callbacks, parser and answer reveal are the real Activity path.
 * Supply audio through the emulator's documented gRPC microphone or a real device microphone.
 * Never calls recognition callbacks or injects recognized text. Isolated preview/releaseQa only.
 * releaseQa uses release backend flags without shrinking; not a production optimization or binary test.
 */
@RunWith(AndroidJUnit4::class)
class FirstCardMicrophoneIntegrationTest {
    @Test(timeout = 180_000L)
    fun firstQuestionAcceptsExternalMicrophoneAnswer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires an external acoustic stimulus", arguments.getString("external_audio") == "true")
        val context = instrumentation.targetContext
        val targetPackage = arguments.getString("target_package") ?: "ai.hyundaehwa.car.preview"
        assertTrue("Production package is not a test target", targetPackage in setOf("ai.hyundaehwa.car.preview", "ai.hyundaehwa.car.releaseqa"))
        assertEquals(targetPackage, context.packageName)
        val answerCase = arguments.getString("answer_case") ?: "number"
        assertTrue("answer_case must be number, label or combined", answerCase in setOf("number", "label", "combined"))
        val requestedCardId = arguments.getString("card_id")?.takeIf { it.isNotBlank() }
        val requestedChoiceId = arguments.getString("choice_id")?.takeIf { it.isNotBlank() }
        val requestedChoiceIndex = arguments.getString("choice_index")?.takeIf { it.isNotBlank() }?.let {
            requireNotNull(it.toIntOrNull()) { "choice_index must be a 1-based integer" }
        }
        require(requestedChoiceId == null || requestedChoiceIndex == null) {
            "choice_id and choice_index are mutually exclusive"
        }
        require(requestedChoiceIndex == null || requestedChoiceIndex >= 1) { "choice_index must be at least 1" }
        val qaRelease = targetPackage == "ai.hyundaehwa.car.releaseqa"
        if (qaRelease) {
            assertFalse("QA must execute the release backend branch", BuildConfig.DEBUG)
            assertFalse("Installed QA APK must actually be non-debuggable", (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            assertFalse("Release QA cannot enable the debug relay", BuildConfig.ENABLE_GEMINI_RELAY)
        }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val prefs = context.getSharedPreferences("conversation_profile", Context.MODE_PRIVATE)
        val keys = setOf("spoken_guidance", "audio_setup_seen", "on_device_card_answers", "cabin_sound_monitor")
        val original = prefs.all.filterKeys { it in keys }
        var activity: MainActivity? = null
        var failure: Throwable? = null
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun state() = field("state").get(activity) as ConversationState
        fun input() = field("answerVoiceState").get(activity) as CardAnswerVoiceState
        fun voice() = field("voiceState").get(activity) as VoicePlaybackState
        fun retries(): Int {
            val answerInput = field("answerVoice").get(activity)!!
            return answerInput.javaClass.getDeclaredField("noSpeechRestarts").apply { isAccessible = true }.getInt(answerInput)
        }
        fun call(name: String, types: Array<Class<*>>, vararg args: Any?) = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(activity, *args)
        fun emit(event: String, vararg values: Pair<String, Any>) {
            val json = JSONObject().put("event", event).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("targetPackage", targetPackage).put("answerCase", answerCase).put("releaseQa", qaRelease)
            values.forEach { json.put(it.first, it.second) }
            Log.i("HyundaehwaMicTest", json.toString())
            instrumentation.sendStatus(0, Bundle().apply { putString("microphone_observation", json.toString()) })
        }
        fun await(description: String, timeoutMs: Long = 60_000L, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < until) {
                var matched = false
                instrumentation.runOnMainSync {
                    check(!requireNotNull(activity).isDestroyed) { "Activity was recreated while awaiting $description; first-turn continuity was lost" }
                    matched = condition()
                }
                if (matched) return
                Thread.sleep(50)
            }
            var detail = ""
            instrumentation.runOnMainSync { detail = input().toString() }
            fail("Timed out: $description; $detail")
        }
        try {
            assertTrue(prefs.edit().putBoolean("spoken_guidance", true).putBoolean("audio_setup_seen", true)
                .putBoolean("on_device_card_answers", false).putBoolean("cabin_sound_monitor", false).commit())
            val loadedCatalog = requireNotNull(AdministrativeConversationLoader.load(context.assets))
            val sourceArea = requireNotNull(loadedCatalog.areaById(Scenario.COLLEAGUE.regionId))
            val requestedCard = requestedCardId?.let { id ->
                requireNotNull(sourceArea.topics.singleOrNull { it.id == id && it.format == "quiz" && "adult" in it.audiences }) {
                    "card_id must name an actual adult quiz in the simulated route's loaded bank: $id"
                }
            }
            if (requestedChoiceId != null && requestedCard != null)
                require(requestedCard.options.any { it.id == requestedChoiceId }) { "choice_id is absent from the requested card" }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            await("isolated test activity resumed") { field("resumed").getBoolean(activity) }
            await("actual packaged catalog loaded") { field("administrativeCatalog").get(activity) != null }
            instrumentation.runOnMainSync {
                field("emulatorMode").setBoolean(activity, true)
                (field("bridge").get(activity) as? PleosPlatformBridge)?.stop()
                (field("parkedDialog").get(activity) as? AlertDialog)?.dismiss()
                field("parkedDialog").set(activity, null)
                field("automaticPassengerPromptShown").setBoolean(activity, true)
                field("relationshipChosen").setBoolean(activity, true)
                field("welcomePending").setBoolean(activity, false)
                field("emulatorTab").setBoolean(activity, false)
                field("answerVoiceOptIn").setBoolean(activity, true)
                (field("regionEntry").get(activity) as RegionEntryQueue).clear()
                if (requestedCard != null) field("difficultyMode").set(activity, when (requestedCard.difficulty) {
                    1 -> DifficultyMode.LOW; 3 -> DifficultyMode.HIGH; else -> DifficultyMode.MEDIUM
                })
                val initial = HyundaehwaEngine.simulation(Scenario.COLLEAGUE).copy(
                    // Limit only the simulated available inventory, never the packaged content.
                    usedTopicIds = if (requestedCard == null) emptySet() else sourceArea.topics.map { it.id }.filter { it != requestedCard.id }.toSet(),
                    requireEntryDelivery = true, enabled = true, gear = Gear.D, speedKph = 70.0,
                    mediaActive = false, rotationRemaining = listOf("quiz", "quiz", "quiz", "balance"))
                field("state").set(activity, initial)
                call("observeRegionEntry", arrayOf(String::class.java, String::class.java, String::class.java),
                    initial.regionId, initial.regionLabel, initial.destination)
                call("refreshRegionalTopics", emptyArray())
                call("render", arrayOf(Boolean::class.javaPrimitiveType!!), true)
                call("playPendingOutput", emptyArray())
            }
            var firstCard: AdministrativeConversationTopic? = null
            await("first quiz card displayed") {
                firstCard = field("pinnedCard").get(activity) as? AdministrativeConversationTopic
                firstCard != null
            }
            val card = requireNotNull(firstCard)
            if (requestedCardId != null) assertEquals("The requested packaged card must be first", requestedCardId, card.id)
            val requestedChoice = requestedChoiceId
            val option = when {
                requestedChoiceIndex != null -> requireNotNull(card.options.getOrNull(requestedChoiceIndex - 1)) {
                    "choice_index must name an actual rendered option: 1..${card.options.size}"
                }
                requestedChoice != null -> requireNotNull(card.options.find { it.id == requestedChoice }) { "Requested choice is not on the actual card" }
                else -> card.options.first()
            }
            val spokenNumber = "${card.options.indexOf(option) + 1}번"
            val expectedSpokenText = when (answerCase) {
                "label" -> option.label
                "combined" -> "$spokenNumber ${option.label}"
                else -> spokenNumber
            }
            emit("card", "cardId" to card.id, "label" to option.label, "choiceId" to option.id,
                "choiceIndex" to (card.options.indexOf(option) + 1), "expectedSpokenText" to expectedSpokenText)
            var questionSpeechId = ""
            var preAnswerToken = -1L
            await("actual first question playback") {
                val prompt = field("localPrompt").get(activity) as? Utterance
                if (voice().phase == VoicePhase.PLAYING && prompt?.kind == "topic" && prompt.topicId == card.id) {
                    assertFalse("No microphone may run during question speech", input().active)
                    questionSpeechId = prompt.id
                    preAnswerToken = input().token
                    true
                } else false
            }
            emit("question_playing", "speechId" to questionSpeechId, "cardId" to card.id)
            await("first automatic capture callback") {
                val next = input()
                if (next.phase in setOf(CardAnswerVoicePhase.FAILED, CardAnswerVoicePhase.UNMATCHED, CardAnswerVoicePhase.EMPTY_TIMEOUT))
                    fail("First answer failed before acoustic stimulus: $next")
                next.phase == CardAnswerVoicePhase.LISTENING
            }
            instrumentation.runOnMainSync {
                assertEquals("Test requires actual Pleos service, not Android fallback", "Pleos 기기 내 음성 인식 · ko-KR", input().source)
                assertEquals("First capture must not follow a no-speech recovery", 0, retries())
                assertEquals("First automatic attempt owns the next token", preAnswerToken + 1L, input().token)
                assertEquals("The actual question must have completed", VoicePhase.COMPLETED, voice().phase)
                assertEquals(questionSpeechId, voice().requestId)
                assertFalse("Question playback must release the speaking state", state().speechActive)
                assertFalse(field("answerVisible").getBoolean(activity))
                emit("listening", "source" to input().source, "cardId" to card.id, "token" to input().token,
                    "noSpeechRestarts" to retries(), "questionSpeechId" to questionSpeechId, "questionVoicePhase" to voice().phase.name)
            }
            await("recognized answer reaches visible answer selection", 45_000L) {
                val next = input()
                if (next.phase in setOf(CardAnswerVoicePhase.FAILED, CardAnswerVoicePhase.UNMATCHED, CardAnswerVoicePhase.EMPTY_TIMEOUT, CardAnswerVoicePhase.CANCELLED))
                    fail("Acoustic answer was not resolved: $next")
                next.phase == CardAnswerVoicePhase.RESOLVED && field("answerVisible").getBoolean(activity)
            }
            instrumentation.runOnMainSync {
                assertEquals(card.id, input().cardId)
                assertEquals("Recognition must resolve in the original session", preAnswerToken + 1L, input().token)
                assertEquals("Recognition must not recover through another capture", 0, retries())
                assertEquals("Recognition must remain on Pleos", "Pleos 기기 내 음성 인식 · ko-KR", input().source)
                assertEquals(option.id, input().answer?.choiceId)
                assertEquals(option.id, field("selectedChoiceId").get(activity))
                assertTrue(input().transcript.isNotBlank())
                emit("resolved", "transcript" to input().transcript, "choiceId" to option.id,
                    "source" to input().source, "answerVisible" to true, "token" to input().token, "noSpeechRestarts" to retries(),
                    "expectedSpokenText" to expectedSpokenText,
                    "scope" to "Isolated $targetPackage APK; simulated route; external microphone audio; no recognized-text injection; QA shrinking disabled; production optimization and binary identity not verified")
            }
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
