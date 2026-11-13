package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.AheadFacility
import ai.hyundaehwa.core.PlaybackUsage

/** Null is unavailable or expired, never an inferred zero/empty seat. Times are elapsedRealtime. */
data class PlatformSnapshot(
    val sourceLabel: String = "실제 신호 미확인",
    val vehicleConnected: Boolean = false,
    val uxAllowsSetup: Boolean? = null,
    val gear: String? = null,
    val speedKph: Double? = null,
    val frontPassengerOccupied: Boolean? = null,
    val rearOccupantCount: Int? = null,
    val seatSignals: Map<String, Boolean?> = emptyMap(),
    val vehicleUpdatedAtMs: Long? = null,
    val gearUpdatedAtMs: Long? = null,
    val gearEventAtMs: Long? = null,
    val speedEventAtMs: Long? = null,
    val speedUpdatedAtMs: Long? = null,
    val seatUpdatedAtMs: Long? = null,
    val navigationUpdatedAtMs: Long? = null,
    val maneuverUpdatedAtMs: Long? = null,
    val locationUpdatedAtMs: Long? = null,
    val navigationConnected: Boolean = false,
    val navigationScreenStatus: String? = null,
    val routeActive: Boolean? = null,
    val upcomingFacilities: List<AheadFacility> = emptyList(),
    val destinationArrived: Boolean? = null,
    val destination: String? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    val destinationCoordinatesAtMs: Long? = null,
    val remainingMeters: Double? = null,
    val remainingSeconds: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val nextManeuverMeters: Double? = null,
    val nextManeuverType: String? = null,
    val nearManeuver: Boolean? = null,
    /** Upcoming tunnel TBT within 650m; not proof the vehicle is inside a tunnel. */
    val tunnel: Boolean? = null,
    /** NaviHelper 3.0.5 has no navigation speech-active event. */
    val navigationVoiceActive: Boolean? = null,
    val mediaActive: Boolean? = null,
    val playbackUsages: List<PlaybackUsage>? = null,
    val mediaUpdatedAtMs: Long? = null,
    val waypointStatus: String? = null,
    val status: String = "실제 신호 미확인",
)

data class WaypointRequest(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val address: String,
    val poiId: String = "",
    val poiSubId: String = "",
    val poiProvider: String = "",
    /** Explicit UI confirmation is mandatory; an ordinary candidate is not authorization. */
    val userConfirmed: Boolean = false,
    /** Facility entrance must be verified separately; simulation road points are rejected. */
    val verifiedEntrance: Boolean = false,
)

enum class WaypointResultStatus { REJECTED, REQUESTED, CONFIRMED, UNCONFIRMED, FAILED }
data class WaypointResult(val status: WaypointResultStatus, val message: String)

interface PlatformBridge {
    val snapshot: PlatformSnapshot
    fun start()
    fun stop()
    fun refresh()
    /** Sends a request only; a successful return does not prove that a screen appeared. */
    fun openNavigation(): Boolean
    fun addWaypoint(request: WaypointRequest, onResult: (WaypointResult) -> Unit)
}
