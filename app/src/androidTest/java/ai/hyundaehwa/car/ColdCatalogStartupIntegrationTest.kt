package ai.hyundaehwa.car

import ai.hyundaehwa.car.platform.AdministrativeConversationLoader
import ai.hyundaehwa.car.platform.PlatformSnapshot
import ai.hyundaehwa.core.AdministrativeConversationCatalog
import ai.hyundaehwa.core.ConversationState
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run alone in a fresh preview process; checks responsiveness, not a mocked parse delay. */
@RunWith(AndroidJUnit4::class)
class ColdCatalogStartupIntegrationTest {
    @Test(timeout = 90_000L)
    fun firstPanelRespondsBeforeCatalogPreparationFinishes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("ai.hyundaehwa.car.preview", context.packageName)
        val cachedField = AdministrativeConversationLoader::class.java.getDeclaredField("cached").apply { isAccessible = true }
        assumeTrue("Run this test in a fresh process; a warm catalog cannot prove cold startup", cachedField.get(null) == null)
        val prefs = context.getSharedPreferences("conversation_profile", Context.MODE_PRIVATE)
        val hadVoice = prefs.contains("spoken_guidance")
        val originalVoice = prefs.getBoolean("spoken_guidance", true)
        var activity: MainActivity? = null
        var primaryFailure: Throwable? = null
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            assertTrue(prefs.edit().putBoolean("spoken_guidance", false).commit())
            val startedAt = SystemClock.elapsedRealtime()
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            var panelVisible = false
            var status = ""
            val firstDeadline = startedAt + 10_000L
            while (SystemClock.elapsedRealtime() < firstDeadline && !panelVisible) {
                instrumentation.runOnMainSync {
                    val root = field("root").get(activity) as View
                    panelVisible = root.isAttachedToWindow && root.isShown && root.width > 0 && root.height > 0
                    status = field("catalogLoadState").get(activity).toString()
                    if (status == "LOADING") {
                        val label = field("catalogStatusTextView").get(activity) as TextView
                        assertTrue("Loading must be disclosed", label.isShown && label.text.toString().contains("준비"))
                    }
                }
                if (!panelVisible) Thread.sleep(50)
            }
            val panelMs = SystemClock.elapsedRealtime() - startedAt
            assertTrue("The initial panel must become visible within 10 seconds", panelVisible && panelMs < 10_000L)
            assertTrue("The catalog may be loading or already ready", status in setOf("LOADING", "READY"))
            val pingStart = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { assertNotNull(field("body").get(activity)) }
            val pingMs = SystemClock.elapsedRealtime() - pingStart
            assertTrue("Main thread should remain responsive during background preparation", pingMs < 3_000L)
            val loadDeadline = startedAt + 75_000L
            var finalState = status
            while (SystemClock.elapsedRealtime() < loadDeadline && finalState == "LOADING") {
                Thread.sleep(100)
                instrumentation.runOnMainSync { finalState = field("catalogLoadState").get(activity).toString() }
            }
            assertEquals("The bundled catalog must finish loading", "READY", finalState)
            var freshLocationCompared = false
            instrumentation.runOnMainSync {
                val snapshot = field("currentSnapshot").get(activity) as PlatformSnapshot
                val age = snapshot.locationUpdatedAtMs?.let { SystemClock.elapsedRealtime() - it }
                val catalog = field("administrativeCatalog").get(activity) as AdministrativeConversationCatalog
                if (age != null && age in 0..8_000L) {
                    val area = catalog.areaAt(snapshot.longitude, snapshot.latitude)
                    if (area != null) {
                        assertEquals("Fresh current SDK position is re-resolved after load", area.id, (field("state").get(activity) as ConversationState).regionId)
                        freshLocationCompared = true
                    }
                }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("cold_catalog_startup", "freshProcess=true; panelMs=$panelMs; firstState=$status; mainPingMs=$pingMs; " +
                    "catalogReadyMs=${SystemClock.elapsedRealtime() - startedAt}; freshSdkPositionCompared=$freshLocationCompared; no coordinates recorded")
            })
        } catch (t: Throwable) { primaryFailure = t; throw t }
        finally {
            var cleanup: Throwable? = null
            try { activity?.let { a -> instrumentation.runOnMainSync { a.finish() } } } catch (t: Throwable) { cleanup = t }
            try {
                val edit = prefs.edit()
                if (hadVoice) edit.putBoolean("spoken_guidance", originalVoice) else edit.remove("spoken_guidance")
                assertTrue(edit.commit())
            } catch (t: Throwable) { if (cleanup == null) cleanup = t else cleanup!!.addSuppressed(t) }
            cleanup?.let { if (primaryFailure == null) throw it else primaryFailure!!.addSuppressed(it) }
        }
    }
}
