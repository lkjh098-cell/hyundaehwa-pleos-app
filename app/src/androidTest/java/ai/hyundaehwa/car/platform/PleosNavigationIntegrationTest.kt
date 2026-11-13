package ai.hyundaehwa.car.platform

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Uses official emulator services in P at zero speed; no app-level mocks or injected callbacks. */
@RunWith(AndroidJUnit4::class)
class PleosNavigationIntegrationTest {
    @Test
    fun actualParkedSignalsAndOfficialNavigationRequestRemainDistinctFromVisibleMap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CountDownLatch(1)
        val requested = CountDownLatch(1)
        val latest = AtomicReference(PlatformSnapshot())
        val parked = AtomicReference<PlatformSnapshot?>()
        val requestAccepted = AtomicBoolean(false)
        val passed = AtomicBoolean(false)
        var bridge: PlatformBridge? = null
        try {
            assertTrue("An automotive runtime is required", context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            assertEquals("Official navigation-screen permission must be granted", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP"))
            instrumentation.runOnMainSync {
                bridge = PleosPlatformBridge(context) { snapshot ->
                    latest.set(snapshot)
                    val now = SystemClock.elapsedRealtime()
                    fun fresh(at: Long?) = at != null && now - at in 0..8_000
                    if (snapshot.vehicleConnected && snapshot.navigationConnected && snapshot.uxAllowsSetup == true &&
                        snapshot.gear == "P" && snapshot.speedKph == 0.0 &&
                        fresh(snapshot.gearUpdatedAtMs) && fresh(snapshot.speedUpdatedAtMs)) {
                        parked.set(snapshot)
                        ready.countDown()
                    }
                    if (snapshot.navigationScreenStatus == "내비게이션 화면 요청 · 표시 여부 확인 필요") requested.countDown()
                }.also { it.start() }
            }
            assertTrue("No fresh connected/P/zero-speed/UX and NaviHelper observations within 20 seconds",
                ready.await(20, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { requestAccepted.set(checkNotNull(bridge).openNavigation()) }
            assertTrue("The public SDK screen request was not accepted for submission", requestAccepted.get())
            assertTrue("The bridge did not report REQUESTED within 3 seconds", requested.await(3, TimeUnit.SECONDS))
            assertEquals("내비게이션 화면 요청 · 표시 여부 확인 필요", latest.get().navigationScreenStatus)
            // A void SDK call has no map-visible, split-window, search or routing acknowledgement.
            passed.set(true)
        } finally {
            instrumentation.runOnMainSync { bridge?.stop() }
            val observation = parked.get()
            val proof = JSONObject().apply {
                put("test", "actual-platform-navigation-request")
                put("passed", passed.get())
                put("targetPackage", context.packageName)
                put("actualBridgeUsed", true)
                put("freshParkedObservation", observation != null)
                put("vehicleConnected", observation?.vehicleConnected == true)
                put("navigationConnected", observation?.navigationConnected == true)
                put("gearP", observation?.gear == "P")
                put("speedExactlyZero", observation?.speedKph == 0.0)
                put("uxAllowsSetup", observation?.uxAllowsSetup == true)
                put("requestState", if (requestAccepted.get()) "REQUESTED" else "NOT_REQUESTED")
                put("mapVisibleVerified", false)
                put("splitWindowVerified", false)
                put("routingVerified", false)
                put("preciseLocationRecorded", false)
            }
            val output = File(context.cacheDir, "pleos-navigation-integration-proof.json")
            output.writeText(proof.toString(2))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("pleosNavigationProof", proof.toString())
                putString("pleosNavigationProofPath", output.absolutePath)
            })
        }
    }
}
