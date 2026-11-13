package ai.hyundaehwa.car

import ai.hyundaehwa.car.platform.PlatformSnapshot
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only against the isolated preview app on the actual Pleos emulator, without fake SDK events. */
@RunWith(AndroidJUnit4::class)
class MainWelcomePlaybackIntegrationTest {
    @Test(timeout = 90_000L) fun parkedLaunchProducesOneSustainedAssistantPlaybackAndStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Only the isolated preview variant may change test preferences", context.packageName.endsWith(".preview"))
        val prefs = context.getSharedPreferences("conversation_profile", Context.MODE_PRIVATE)
        val originalVoice = prefs.getBoolean("spoken_guidance", true)
        val originalHold = prefs.getBoolean("external_media_hold", false)
        val hadVoice = prefs.contains("spoken_guidance")
        val hadHold = prefs.contains("external_media_hold")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun assistantActive() = audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANT }
        assertFalse("Test requires no pre-existing assistant playback", assistantActive())
        assertTrue(prefs.edit().putBoolean("spoken_guidance", true).putBoolean("external_media_hold", false).commit())
        var activity: Activity? = null
        try {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val startedAt = SystemClock.elapsedRealtime()
            activity = instrumentation.startActivitySync(intent)
            instrumentation.waitForIdleSync()
            var firstStart = 0L
            var lastActive = 0L
            var activeSamples = 0
            var assistantTransitions = 0
            var wasActive = false
            var observedMusic = false
            var sawParked = false
            var sawUnrestrictedScreen = false
            val deadline = startedAt + 70_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val now = SystemClock.elapsedRealtime()
                val active = assistantActive()
                observedMusic = observedMusic || audio.isMusicActive
                if (active) {
                    if (!wasActive) assistantTransitions++
                    if (firstStart == 0L) firstStart = now
                    lastActive = now
                    activeSamples++
                }
                wasActive = active
                instrumentation.runOnMainSync {
                    val field = MainActivity::class.java.getDeclaredField("currentSnapshot").apply { isAccessible = true }
                    val snapshot = field.get(activity) as PlatformSnapshot
                    val nowMs = SystemClock.elapsedRealtime()
                    fun fresh(at: Long?) = at != null && nowMs - at in 0..8_000L
                    val verifiedParked = snapshot.vehicleConnected && snapshot.gear == "P" &&
                        snapshot.speedKph != null && kotlin.math.abs(snapshot.speedKph!!) < .001 &&
                        fresh(snapshot.gearUpdatedAtMs) && fresh(snapshot.speedUpdatedAtMs) && snapshot.uxAllowsSetup == true
                    if (verifiedParked) sawParked = true
                    var enabledSettings = false
                    fun visit(v: View) {
                        if (v.visibility != View.VISIBLE) return
                        if (v is Button && v.text.toString() == "⚙ 설정" && v.isShown && v.isEnabled && v.isClickable) enabledSettings = true
                        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                    }
                    visit(activity!!.window.decorView)
                    if (verifiedParked && enabledSettings) sawUnrestrictedScreen = true
                }
                if (firstStart != 0L && !active && now - lastActive >= 3_000L) break
                Thread.sleep(100L)
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("welcome_playback_observation", "parked=$sawParked unrestricted=$sawUnrestrictedScreen firstStartMs=${if (firstStart == 0L) -1 else firstStart - startedAt} activeSpanMs=${if (firstStart == 0L) 0 else lastActive - firstStart} activeSamples=$activeSamples transitions=$assistantTransitions musicFlag=$observedMusic; audio attribution and physical audibility are not verified")
            })
            assertTrue("Fresh P vehicle state was not observed", sawParked)
            assertTrue("Unrestricted parked UI was not observed", sawUnrestrictedScreen)
            assertTrue("No assistant-usage playback began within the timeout", firstStart != 0L)
            assertTrue("Assistant playback did not last at least 3 seconds", lastActive - firstStart >= 3_000L && activeSamples >= 25)
            assertTrue("Assistant playback did not finish and remain idle", !assistantActive() && SystemClock.elapsedRealtime() - lastActive >= 3_000L)
            assertTrue("Assistant playback repeatedly restarted (transitions=$assistantTransitions)", assistantTransitions <= 2)
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            val edit = prefs.edit()
            if (hadVoice) edit.putBoolean("spoken_guidance", originalVoice) else edit.remove("spoken_guidance")
            if (hadHold) edit.putBoolean("external_media_hold", originalHold) else edit.remove("external_media_hold")
            edit.commit()
        }
    }
}
