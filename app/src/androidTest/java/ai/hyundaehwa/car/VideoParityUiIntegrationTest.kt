package ai.hyundaehwa.car

import ai.hyundaehwa.car.platform.PlatformSnapshot
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
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
        fun mainButton(label: String): View? = find(activity!!.window.decorView, label, true)
        fun settingsButton(label: String): View? {
            val dialog = field("settingsDialog") as? AlertDialog ?: return null
            return if (dialog.isShowing) dialog.window?.decorView?.let { find(it, label, true) } else null
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
        try {
            assertTrue(prefs.edit().putBoolean("spoken_guidance", false).putString("difficulty", "AUTO")
                .putInt("silence_minutes", 5).putBoolean("audio_setup_seen", true).commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("automatic relationship dialog from actual parked vehicle state") {
                val dialog = field("parkedDialog") as? AlertDialog
                dialog?.isShowing == true && find(dialog.window!!.decorView, "가족", true) != null
            }
            // The actual OEM navigation Activity backgrounds this panel, as during destination setup.
            val firstDialog = arrayOfNulls<AlertDialog>(1)
            instrumentation.runOnMainSync {
                firstDialog[0] = field("parkedDialog") as AlertDialog
                context.startActivity(Intent().setClassName("ai.umos.maps.android.navigation.app", "ai.umos.maps.android.navigation.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            waitFor("panel paused while OEM navigation is in front") { field("resumed") == false }
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            waitFor("unfinished relationship chooser restored after returning from navigation") {
                val dialog = field("parkedDialog") as? AlertDialog
                dialog !== firstDialog[0] && dialog?.isShowing == true && find(dialog.window!!.decorView, "가족", true) != null
            }
            val family = arrayOfNulls<View>(1)
            instrumentation.runOnMainSync {
                val actual = field("currentSnapshot") as PlatformSnapshot
                assertTrue("Real vehicle connection is required", actual.vehicleConnected)
                assertEquals("P", actual.gear)
                assertEquals("Real vehicle speed must be zero", 0.0, actual.speedKph ?: Double.NaN, 0.001)
                assertEquals(true, actual.uxAllowsSetup)
                val dialog = field("parkedDialog") as AlertDialog
                family[0] = find(dialog.window!!.decorView, "가족", true)
            }
            tap(family[0]!!)
            waitFor("persistent family relationship chip") {
                val chip = field("passengerStatus") as TextView
                chip.isShown && chip.text.toString().contains("가족") && field("relationshipChosen") == true
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
            instrumentation.runOnMainSync { high[0] = settingsButton("상") }
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
