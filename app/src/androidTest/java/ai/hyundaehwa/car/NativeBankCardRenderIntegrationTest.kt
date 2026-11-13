package ai.hyundaehwa.car

import ai.hyundaehwa.car.platform.AdministrativeConversationLoader
import ai.hyundaehwa.car.platform.PlatformSnapshot
import ai.hyundaehwa.core.AdministrativeConversationTopic
import ai.hyundaehwa.core.AnswerExplanation
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual bundled card rendered in isolation; this is not a driving or speech test. */
@RunWith(AndroidJUnit4::class)
class NativeBankCardRenderIntegrationTest {
    @Test(timeout = 90_000L)
    fun realBundledQuizAndAnswerFitNativePanel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("ai.hyundaehwa.car.preview", context.packageName)
        val prefs = context.getSharedPreferences("conversation_profile", Context.MODE_PRIVATE)
        val original = prefs.all.filterKeys { it in setOf("spoken_guidance", "audio_setup_seen") }
        var activity: MainActivity? = null
        var primaryFailure: Throwable? = null
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun find(root: View, expected: String): TextView? {
            if (root.visibility != View.VISIBLE) return null
            if (root is TextView && root.text.toString() == expected) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), expected)?.let { return it }
            return null
        }
        fun saveScreenshot(name: String) {
            // Wait for compositor and card dissolve, not just the app main queue.
            SystemClock.sleep(550L)
            instrumentation.waitForIdleSync()
            val directory = requireNotNull(context.getExternalFilesDir(null))
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                listOf(directory, context.filesDir).forEach { target ->
                    FileOutputStream(File(target, name)).use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            } finally { screenshot.recycle() }
        }
        try {
            assertTrue(prefs.edit().putBoolean("spoken_guidance", false).putBoolean("audio_setup_seen", true).commit())
            val catalog = requireNotNull(AdministrativeConversationLoader.load(context.assets)) { "Bundled administrative data could not be loaded" }
            val area = requireNotNull(catalog.areaById("kr-11440")) { "Bundled Mapo area missing" }
            val card = requireNotNull(catalog.available(area, "adult", 2).firstOrNull {
                it.format == "quiz" && it.options.size == 3 && it.question.length <= 100 &&
                    it.title.isNotBlank() && it.options.all { option -> option.label.length <= 30 }
            }) { "No eligible three-choice Mapo quiz available for rendering" }
            val chosen = requireNotNull(card.options.firstOrNull { it.id == card.correctChoiceId })
            val explanation = requireNotNull(AnswerExplanation.text(card, chosen.id, false))
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val parkedDeadline = SystemClock.elapsedRealtime() + 30_000L
            var realParkedReady = false
            while (SystemClock.elapsedRealtime() < parkedDeadline && !realParkedReady) {
                instrumentation.runOnMainSync {
                    val snapshot = field("currentSnapshot").get(activity) as PlatformSnapshot
                    val now = SystemClock.elapsedRealtime()
                    fun fresh(at: Long?) = at != null && now - at in 0..8_000L
                    realParkedReady = snapshot.vehicleConnected && snapshot.gear == "P" &&
                        snapshot.speedKph != null && kotlin.math.abs(snapshot.speedKph!!) < .001 &&
                        snapshot.uxAllowsSetup == true && fresh(snapshot.gearUpdatedAtMs) && fresh(snapshot.speedUpdatedAtMs) &&
                        (field("composerInput").get(activity) as View).isShown
                }
                if (!realParkedReady) Thread.sleep(100)
            }
            assertTrue("Actual fresh P, zero speed, permitted UX and visible composer are required", realParkedReady)
            // Use the actual Pleos split-screen gesture; never fabricate vehicle telemetry.
            val screenBounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val maxPanelWidth = minOf(900, (screenBounds.width() * .4f).toInt())
            var panelWidth = 0
            instrumentation.runOnMainSync { panelWidth = activity!!.window.decorView.width }
            if (panelWidth > maxPanelWidth) {
                val startX = screenBounds.right - 17f
                val endX = screenBounds.left + screenBounds.width() * (2f / 3f) - 7f
                val y = screenBounds.top + screenBounds.height() / 2f
                val downTime = SystemClock.uptimeMillis()
                fun send(action: Int, x: Float) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                    try { assertTrue("System window gesture must be injected", instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                send(MotionEvent.ACTION_DOWN, startX)
                for (step in 1..24) {
                    SystemClock.sleep(16)
                    send(MotionEvent.ACTION_MOVE, startX + (endX - startX) * step / 24f)
                }
                send(MotionEvent.ACTION_UP, endX)
                val splitDeadline = SystemClock.elapsedRealtime() + 15_000L
                while (SystemClock.elapsedRealtime() < splitDeadline) {
                    instrumentation.runOnMainSync { panelWidth = activity!!.window.decorView.width }
                    if (panelWidth in 1..maxPanelWidth) break
                    Thread.sleep(100)
                }
            }
            assertTrue("Test requires a narrow Pleos panel: width=$panelWidth, maximum=$maxPanelWidth", panelWidth in 1..maxPanelWidth)
            // Allow the system split transition to finish before placing the card.
            SystemClock.sleep(1_500L)
            instrumentation.waitForIdleSync()
            // A window transition may change UX restrictions; check real evidence again before rendering.
            instrumentation.runOnMainSync {
                val snapshot = field("currentSnapshot").get(activity) as PlatformSnapshot
                val now = SystemClock.elapsedRealtime()
                assertTrue("Fresh parked/UX evidence must remain valid after resizing", snapshot.vehicleConnected && snapshot.gear == "P" &&
                    snapshot.speedKph != null && kotlin.math.abs(snapshot.speedKph!!) < .001 && snapshot.uxAllowsSetup == true &&
                    snapshot.gearUpdatedAtMs?.let { now - it in 0..8_000L } == true &&
                    snapshot.speedUpdatedAtMs?.let { now - it in 0..8_000L } == true &&
                    (field("composerInput").get(activity) as View).isShown)
            }
            var cardContent: LinearLayout? = null
            var scroll: ScrollView? = null
            fun mountCard(answered: Boolean) {
                instrumentation.runOnMainSync {
                    (field("parkedDialog").get(activity) as? AlertDialog)?.dismiss()
                    // Suppress only the setup popup so this isolated visual can be photographed.
                    // No vehicle, location, sound, navigation, or safety state is injected.
                    field("automaticPassengerPromptShown").setBoolean(activity, true)
                    field("choiceCardId").set(activity, card.id)
                    field("pinnedRegionId").set(activity, area.id)
                    field("selectedChoiceId").set(activity, if (answered) chosen.id else null)
                    field("heardChoice").set(activity, if (answered) chosen.label else null)
                    field("answerVisible").setBoolean(activity, answered)
                    field("unansweredReveal").setBoolean(activity, false)
                    val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12) }
                    val render = MainActivity::class.java.getDeclaredMethod("renderBankCard", LinearLayout::class.java, AdministrativeConversationTopic::class.java)
                    render.isAccessible = true
                    render.invoke(activity, content, card)
                    val scrollView = ScrollView(activity).apply { isFillViewport = true; setBackgroundColor(Color.rgb(245, 249, 250)); addView(content) }
                    val body = field("body").get(activity) as LinearLayout
                    val host = activity!!.findViewById<FrameLayout>(android.R.id.content)
                    scroll?.let { (it.parent as? ViewGroup)?.removeView(it) }
                    val bodyLocation = IntArray(2); val hostLocation = IntArray(2)
                    body.getLocationOnScreen(bodyLocation); host.getLocationOnScreen(hostLocation)
                    // Keep this isolated UI fixture above live body redraws without changing telemetry.
                    host.addView(scrollView, FrameLayout.LayoutParams(body.width, body.height).apply {
                        leftMargin = bodyLocation[0] - hostLocation[0]; topMargin = bodyLocation[1] - hostLocation[1]
                    })
                    cardContent = content; scroll = scrollView
                }
                instrumentation.waitForIdleSync()
            }
            mountCard(false)
            instrumentation.runOnMainSync {
                val content = requireNotNull(cardContent)
                val firstCard = content.getChildAt(0)
                assertEquals("Question card should be white", Color.WHITE, (firstCard.background as GradientDrawable).color?.defaultColor)
                assertNotNull("Actual database title missing", find(content, card.title))
                assertNotNull("Actual database question missing", find(content, card.question))
                val viewport = Rect()
                assertTrue("Scroll region must be visible", requireNotNull(scroll).getGlobalVisibleRect(viewport))
                assertTrue("Card viewport must be a real narrow split panel: ${viewport.width()}px", viewport.width() in 1..maxPanelWidth)
                card.options.forEachIndexed { index, option ->
                    val optionView = requireNotNull(find(content, "${index + 1}  ${option.label}"))
                    assertTrue("Option is a native button", optionView is Button)
                    assertTrue("Option type must be readable", optionView.textSize / context.resources.displayMetrics.scaledDensity >= 21f)
                    val bounds = Rect()
                    assertTrue("Option ${index + 1} must be visible within the panel", optionView.getGlobalVisibleRect(bounds))
                    assertTrue("Option ${index + 1} is clipped by the viewport", bounds.height() >= optionView.height - 2 && bounds.bottom <= viewport.bottom)
                }
                assertTrue("Composer must remain visible", (field("composerInput").get(activity) as View).isShown)
                val display = context.resources.displayMetrics
                instrumentation.sendStatus(0, Bundle().apply { putString("native_bank_card_observation", "isolated render; area=${area.id}; card=${card.id}; viewport=${viewport.width()}x${viewport.height()}px; density=${display.density}; no telemetry or voice tested") })
            }
            saveScreenshot("ui-parity-quiz.png")
            mountCard(true)
            instrumentation.runOnMainSync {
                val content = requireNotNull(cardContent)
                val human = requireNotNull(find(content, chosen.label))
                assertEquals("User response should use white text", Color.WHITE, human.currentTextColor)
                assertEquals("User response should use navy fill", Color.rgb(11, 38, 72), (human.background as GradientDrawable).color?.defaultColor)
                assertNotNull("Full database explanation is displayed", find(content, explanation))
            }
            // Scroll to the real response and explanation before taking the answer screenshot.
            instrumentation.runOnMainSync { requireNotNull(scroll).fullScroll(View.FOCUS_DOWN) }
            instrumentation.waitForIdleSync()
            saveScreenshot("ui-parity-answer.png")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            try { activity?.let { current -> instrumentation.runOnMainSync { current.finish() } } }
            catch (failure: Throwable) { cleanupFailure = failure }
            try {
                val editor = prefs.edit()
                listOf("spoken_guidance", "audio_setup_seen").forEach { key ->
                    val value = original[key]
                    if (value is Boolean) editor.putBoolean(key, value) else editor.remove(key)
                }
                assertTrue("Preview preferences must be restored", editor.commit())
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
            }
            cleanupFailure?.let { failure -> if (primaryFailure != null) primaryFailure!!.addSuppressed(failure) else throw failure }
        }
    }
}
