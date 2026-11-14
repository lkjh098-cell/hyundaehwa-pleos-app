package ai.hyundaehwa.car.platform

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Official emulator SDK location -> bundled catalog; no injected location or physical-GPS claim. */
@RunWith(AndroidJUnit4::class)
class NativeLocationBankIntegrationTest {
    @Test(timeout = 90_000L)
    fun freshSdkLocationMapsToRealAudienceAndDifficultyInventory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("ai.hyundaehwa.car.preview", context.packageName)
        assertTrue("An automotive emulator environment is required",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        val loadStartedAt = SystemClock.elapsedRealtime()
        val catalog = requireNotNull(AdministrativeConversationLoader.load(context.assets)) {
            "Bundled administrative and question data could not be loaded"
        }
        val loadMs = SystemClock.elapsedRealtime() - loadStartedAt
        val freshLocation = CountDownLatch(1)
        val latest = AtomicReference<PlatformSnapshot?>()
        var bridge: PleosPlatformBridge? = null
        val proof = JSONObject().apply {
            put("test", "actual-emulator-sdk-location-to-bundled-bank")
            put("injectedLocation", false)
            put("physicalGpsVerified", false)
            put("preciseCoordinatesRecorded", false)
            put("passed", false)
            put("loaderMs", loadMs)
            put("loaderCacheState", "not independently established")
        }
        try {
            instrumentation.runOnMainSync {
                bridge = PleosPlatformBridge(context) { snapshot ->
                    val now = SystemClock.elapsedRealtime()
                    val at = snapshot.locationUpdatedAtMs
                    val latitude = snapshot.latitude; val longitude = snapshot.longitude
                    if (snapshot.navigationConnected && at != null && now - at in 0..8_000 &&
                        latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite()) {
                        latest.set(snapshot)
                        freshLocation.countDown()
                    }
                }.also { it.start() }
            }
            assertTrue("No fresh location from the official emulator NaviHelper within 30 seconds",
                freshLocation.await(30, TimeUnit.SECONDS))
            val snapshot = requireNotNull(latest.get())
            assertTrue("Bridge must identify the source as an SDK emulator", snapshot.sourceLabel.contains("에뮬레이터"))
            assertTrue("SDK position must still be fresh at lookup",
                snapshot.locationUpdatedAtMs?.let { SystemClock.elapsedRealtime() - it in 0..8_000 } == true)
            // Exact coordinates remain only in memory; they are never put into the status or any file.
            val area = requireNotNull(catalog.areaAt(snapshot.longitude, snapshot.latitude)) {
                "Fresh emulator SDK location did not match a bundled administrative polygon"
            }
            assertTrue("Mapped region identifier must be nonempty", area.id.isNotBlank())
            val coverage = JSONObject()
            for (audience in listOf("family", "adult")) {
                val levels = JSONObject()
                for (difficulty in 1..3) {
                    val cards = catalog.available(area, audience, difficulty)
                    val quizzes = cards.filter { it.format == "quiz" }
                    val balances = cards.filter { it.format == "balance" }
                    assertTrue("No $audience difficulty $difficulty quiz in the mapped region", quizzes.isNotEmpty())
                    assertTrue("No $audience balance content in the mapped region", balances.isNotEmpty())
                    assertTrue("Quiz content must be sourced, answered and explained", quizzes.all {
                        it.sourceKind == "official-place-fact" && it.difficulty == difficulty &&
                            audience in it.audiences && it.options.size >= 2 &&
                            it.correctChoiceId != null && it.options.any { option -> option.id == it.correctChoiceId } &&
                            it.explanation.isNotBlank() && it.sourceUrl.startsWith("https://")
                    })
                    assertTrue("Balance choices must be genuine eligible bank records", balances.all {
                        audience in it.audiences && it.options.size == 2 && it.sourceUrl.startsWith("https://") &&
                            it.options.all { option -> !it.replyByChoice[option.id].isNullOrBlank() }
                    })
                    levels.put(difficulty.toString(), JSONObject().put("quizCount", quizzes.size).put("balanceCount", balances.size))
                }
                coverage.put(audience, levels)
            }
            proof.put("source", "SDK emulator NaviHelper callback")
                .put("freshLocationReceived", true)
                .put("areaId", area.id)
                .put("areaLabel", area.label)
                .put("inventory", coverage)
                .put("passed", true)
        } finally {
            instrumentation.runOnMainSync { bridge?.stop() }
            instrumentation.sendStatus(0, Bundle().apply { putString("nativeLocationBankProof", proof.toString()) })
        }
    }
}
