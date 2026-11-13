package ai.hyundaehwa.car.platform

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A successful current-value query is an observation, distinct from the property's change time. */
internal class AaosVehicleSignals(private val context: Context,
    private val onGear: (String?, Long?, Long?) -> Unit,
    private val onSpeed: (Double?, Long?, Long?) -> Unit,
    private val onConnection: (Boolean) -> Unit, private val onUx: (Boolean?) -> Unit) {
    private var car: Car? = null
    @Volatile private var manager: CarPropertyManager? = null
    @Volatile private var active = false
    private var uxManager: CarUxRestrictionsManager? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val reading = AtomicBoolean(false)
    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
        if (active) onUx(!restrictions.isRequiresDistractionOptimization)
    }
    private fun accept(value: CarPropertyValue<*>, queried: Boolean) {
        if (!active) return
        if (value.status != CarPropertyValue.STATUS_AVAILABLE) { unavailable(value.propertyId); return }
        val eventAt = value.timestamp / 1_000_000L
        val observedAt = if (queried) SystemClock.elapsedRealtime() else eventAt
        when (value.propertyId) {
            VehiclePropertyIds.GEAR_SELECTION -> {
                val gear = gear((value.value as? Number)?.toInt())
                onGear(gear, if (gear != null) observedAt else null, if (gear != null) eventAt else null)
            }
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                val speed = (value.value as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) * 3.6 }
                onSpeed(speed, if (speed != null) observedAt else null, if (speed != null) eventAt else null)
            }
        }
    }
    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = accept(value, false)
        override fun onErrorEvent(propertyId: Int, areaId: Int) { if (active) unavailable(propertyId) }
    }
    private fun unavailable(id: Int) { if (id == VehiclePropertyIds.GEAR_SELECTION) onGear(null, null, null); if (id == VehiclePropertyIds.PERF_VEHICLE_SPEED) onSpeed(null, null, null) }
    private fun gear(value: Int?): String? = when (value) { VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_DRIVE -> "D"; VehicleGear.GEAR_NEUTRAL -> "N"; VehicleGear.GEAR_REVERSE -> "R"; else -> null }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!active) return
            runCatching {
                manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                uxManager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                uxManager?.registerListener(uxListener)
                onConnection(manager != null)
                onUx(uxManager?.currentCarUxRestrictions?.let { !it.isRequiresDistractionOptimization })
                runCatching { manager?.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                    .onFailure { onGear(null, null, null) }
                runCatching { manager?.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL) }
                    .onFailure { onSpeed(null, null, null) }
                read()
            }.onFailure { onConnection(false); onUx(null); onGear(null, null, null); onSpeed(null, null, null) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { manager = null; uxManager = null; if (active) { onConnection(false); onUx(null); onGear(null, null, null); onSpeed(null, null, null) } }
    }
    @Suppress("DEPRECATION")
    fun start() { active = true; runCatching { car = Car.createCar(context, connection); car?.connect() }.onFailure { onConnection(false); onUx(null); onGear(null, null, null); onSpeed(null, null, null) } }
    fun read() {
        if (!active) return
        onUx(runCatching { uxManager?.currentCarUxRestrictions?.let { !it.isRequiresDistractionOptimization } }.getOrNull())
        val p = manager ?: return
        if (!reading.compareAndSet(false, true)) return
        worker.execute {
            try {
                runCatching { p.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0) }
                    .onSuccess { if (active && manager === p) accept(it, true) }.onFailure { if (active && manager === p) onGear(null, null, null) }
                runCatching { p.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0) }
                    .onSuccess { if (active && manager === p) accept(it, true) }.onFailure { if (active && manager === p) onSpeed(null, null, null) }
            } finally { reading.set(false) }
        }
    }
    fun stop() { active = false; runCatching { uxManager?.unregisterListener() }; uxManager = null; runCatching { manager?.unregisterCallback(callback) }; manager = null; runCatching { car?.disconnect() }; car = null; worker.shutdownNow() }
}
