package ai.hyundaehwa.car

import ai.hyundaehwa.car.platform.PlatformSnapshot
import ai.hyundaehwa.core.ConversationState
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises only real SDK-backed parked UI in the isolated preview package. */
@RunWith(AndroidJUnit4::class)
class VideoParityUiIntegrationTest {
    @Test(timeout = 90_000L)
    fun parkedRelationshipAndSettingsAreUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Run only the isolated preview build", context.packageName == "ai.hyundaehwa.car.preview")
        val prefs = context.getSharedPreferences("conversation_profile", Context.MODE_PRIVATE)
        val keys = listOf("spoken_guidance", "youngest", "preference", "difficulty", "silence_minutes", "audio_setup_seen")
        val original = prefs.all.filterKeys { it in keys }
        val deadline = SystemClock.elapsedRealtime() + 70_000L
        var activity: MainActivity? = null
        var primaryFailure: Throwable? = null
        fun field(name: String): Any? {
            val f = MainActivity::class.java.getDeclaredField(name)
            f.isAccessible = true
            return f.get(activity)
        }
        fun find(root: View, label: String, buttonsOnly: Boolean = false): View? {
            if (root.visibility != View.VISIBLE) return null
            if (root is TextView && (!buttonsOnly || root is Button) && root.text.toString() == label) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), label, buttonsOnly)?.let { return it }
            return null
        }
        fun waitFor(description: String, condition: () -> Boolean) {
            while (SystemClock.elapsedRealtime() < deadline) {
                var ok = false
                instrumentation.runOnMainSync { ok = condition() }
                if (ok) return
                Thread.sleep(100)
            }
            fail("Timed out waiting for $description; real P / zero speed and unrestricted UX are required")
        }
        fun observeFor(ms: Long, condition: () -> Boolean): Boolean {
            val until = minOf(deadline, SystemClock.elapsedRealtime() + ms)
            while (SystemClock.elapsedRealtime() < until) {
                var ok = false
                instrumentation.runOnMainSync { ok = condition() }
                if (ok) return true
                Thread.sleep(100)
            }
            return false
        }
        fun mainButton(label: String): View? = find(activity!!.window.decorView, label, true)
        fun settingsButton(label: String): View? {
            val dialog = field("settingsDialog") as? AlertDialog ?: return null
            return if (dialog.isShowing) dialog.window?.decorView?.let { find(it, label, true) } else null
        }
        fun tapPoint(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
            try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) }
            finally { down.recycle(); up.recycle() }
            instrumentation.waitForIdleSync()
        }
        fun tapNavigationPane() {
            val bounds = Rect()
            instrumentation.runOnMainSync { assertTrue(activity!!.window.decorView.getGlobalVisibleRect(bounds)) }
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val width = screen.width; val height = screen.height; screen.recycle()
            // On the official three-pane fixture, the left third is the driving view.
            // The navigation pane is the half of the remaining screen opposite the preview.
            val navigationX = if (bounds.centerX() < width * 2 / 3) (bounds.right + width) / 2f
                else (width / 3f + bounds.left) / 2f
            tapPoint(navigationX, height * .32f)
        }
        fun tap(view: View) {
            instrumentation.runOnMainSync {
                assertTrue("Button must be enabled: ${(view as? TextView)?.text}", view.isEnabled)
                view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            }
            instrumentation.waitForIdleSync()
            val location = IntArray(2)
            instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
            val x = location[0] + view.width / 2f
            val y = location[1] + view.height / 2f
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
            try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up) }
            finally { down.recycle(); up.recycle() }
            instrumentation.waitForIdleSync()
        }
        fun tapChooserHeader() {
            val title = arrayOfNulls<View>(1)
            instrumentation.runOnMainSync {
                val dialog = field("parkedDialog") as AlertDialog
                title[0] = find(dialog.window!!.decorView, "누구와 함께 가세요?")
            }
            tap(requireNotNull(title[0]))
        }
        try {
            assertTrue(prefs.edit().putBoolean("spoken_guidance", false).putString("difficulty", "AUTO")
                .putInt("silence_minutes", 5).putBoolean("audio_setup_seen", true).commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("automatic relationship dialog from actual parked vehicle state") {
                val dialog = field("parkedDialog") as? AlertDialog
                dialog?.isShowing == true && find(dialog.window!!.decorView, "가족", true) != null
            }
            // Existing split-screen tasks may start visible without focused input.
            tapChooserHeader()
            val initialFocusObserved = observeFor(2_000L) {
                (field("parkedDialog") as? AlertDialog)?.window?.decorView?.hasWindowFocus() == true
            }
            // In split screen the actual OEM navigation Activity can take focus without pausing this panel.
            val firstDialog = arrayOfNulls<AlertDialog>(1)
            instrumentation.runOnMainSync {
                firstDialog[0] = field("parkedDialog") as AlertDialog
                context.startActivity(Intent().setClassName("ai.umos.maps.android.navigation.app", "ai.umos.maps.android.navigation.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            tapNavigationPane()
            val navigationFocusChangeObserved = observeFor(3_000L) {
                field("resumed") == false || firstDialog[0]?.window?.decorView?.hasWindowFocus() != true
            }
            // Let a possible onPause arrive before recording which lifecycle path was exercised.
            Thread.sleep(250L)
            var wasPaused = false
            instrumentation.runOnMainSync {
                wasPaused = field("resumed") == false
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            waitFor("unfinished relationship chooser restored after returning from navigation") {
                val dialog = field("parkedDialog") as? AlertDialog
                (!wasPaused || dialog !== firstDialog[0]) && dialog?.isShowing == true &&
                    find(dialog.window!!.decorView, "가족", true) != null
            }
            tapChooserHeader()
            val returnFocusObserved = observeFor(2_000L) {
                (field("parkedDialog") as? AlertDialog)?.window?.decorView?.hasWindowFocus() == true
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("navigation_return_lifecycle", "paused=$wasPaused; initialChooserFocus=$initialFocusObserved; " +
                    "navigationFocusChange=$navigationFocusChangeObserved; returnedChooserFocus=$returnFocusObserved; " +
                    "subsequent family selection verifies real touch handling")
            })
            val family = arrayOfNulls<View>(1)
            instrumentation.runOnMainSync {
                val actual = field("currentSnapshot") as PlatformSnapshot
                assertTrue("Real vehicle connection is required", actual.vehicleConnected)
                assertEquals("P", actual.gear)
                assertEquals("Real vehicle speed must be zero", 0.0, actual.speedKph ?: Double.NaN, 0.001)
                assertEquals(true, actual.uxAllowsSetup)
                val dialog = field("parkedDialog") as AlertDialog
                family[0] = find(dialog.window!!.decorView, "가족", true)
                val familyButton = family[0] as TextView
                val scaledDensity = context.resources.displayMetrics.scaledDensity
                assertTrue("Relationship choices need readable type", familyButton.textSize / scaledDensity >= 19f)
                assertTrue("Relationship choices need a large touch target", familyButton.height / context.resources.displayMetrics.density >= 60f)
            }
            tap(family[0]!!)
            waitFor("persistent family relationship chip") {
                val chip = field("passengerStatus") as TextView
                chip.isShown && chip.text.toString().contains("가족") && field("relationshipChosen") == true
            }
            instrumentation.runOnMainSync {
                assertTrue("Selecting a relationship starts guidance", (field("state") as ConversationState).enabled)
                val status = field("activityStatusTextView") as TextView
                assertTrue("Activity status remains visible and readable", status.isShown && status.textSize / context.resources.displayMetrics.scaledDensity >= 17f)
                assertTrue("Answer input remains available at the bottom", (field("composerInput") as View).isShown)
            }
            waitFor("settings gear") { mainButton("⚙ 설정")?.isShown == true }
            val gear = arrayOfNulls<View>(1)
            instrumentation.runOnMainSync { gear[0] = mainButton("⚙ 설정") }
            tap(gear[0]!!)
            waitFor("difficulty and silence controls") {
                listOf("자동", "하", "중", "상", "1분", "3분", "5분", "10분", "15분")
                    .all { settingsButton(it) != null }
            }
            val high = arrayOfNulls<View>(1)
            instrumentation.runOnMainSync {
                high[0] = settingsButton("상")
                val highButton = high[0] as TextView
                assertTrue("Difficulty choice must use readable type", highButton.textSize / context.resources.displayMetrics.scaledDensity >= 19f)
            }
            tap(high[0]!!)
            waitFor("high difficulty selection") { prefs.getString("difficulty", null) == "HIGH" }
            val threeMinutes = arrayOfNulls<View>(1)
            instrumentation.runOnMainSync { threeMinutes[0] = settingsButton("3분") }
            tap(threeMinutes[0]!!)
            waitFor("three minute silence selection") { prefs.getInt("silence_minutes", -1) == 3 }
            instrumentation.runOnMainSync {
                val chip = field("passengerStatus") as TextView
                assertTrue("Family chip remains visible while settings are open", chip.isShown && chip.text.toString().contains("가족"))
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            } catch (failure: Throwable) { cleanupFailure = failure }
            try {
                val editor = prefs.edit()
                keys.forEach { key ->
                    when (val value = original[key]) {
                        is Boolean -> editor.putBoolean(key, value)
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        null -> editor.remove(key)
                        else -> throw AssertionError("Unexpected original preference type for $key")
                    }
                }
                assertTrue("Preview preferences should be restored", editor.commit())
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
            }
            cleanupFailure?.let { failure ->
                if (primaryFailure != null) primaryFailure!!.addSuppressed(failure) else throw failure
            }
        }
    }
}
