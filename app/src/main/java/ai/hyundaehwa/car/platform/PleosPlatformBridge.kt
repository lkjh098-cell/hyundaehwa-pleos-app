package ai.hyundaehwa.car.platform

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.*
import ai.hyundaehwa.core.RestFacilities
import ai.hyundaehwa.core.RestInstruction
import ai.hyundaehwa.core.PlaybackUsage
import android.media.AudioAttributes
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Typed Vehicle 2.3.2 + NaviHelper 3.0.5 bridge; never manufactures sample telemetry. */
class PleosPlatformBridge(context: Context, private val listener: (PlatformSnapshot) -> Unit) : PlatformBridge {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var running = false
    private var epoch = 0L
    private var vehicle: Vehicle? = null
    private var seat: Seat? = null
    private var aaos: AaosVehicleSignals? = null
    private var navi: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null
    private var navigationConnected = false
    private val seatListeners = mutableMapOf<SeatArea, SeatOccupancyListener>()
    private val seatTimes = mutableMapOf<String, Long>()
    private var routeAt: Long? = null
    private var locationAt: Long? = null
    private var destinationAt: Long? = null
    private var maneuverAt: Long? = null
    private var pending: PendingWaypoint? = null
    private data class PendingWaypoint(val request: WaypointRequest, val callback: (WaypointResult) -> Unit, val epoch: Long)
    override var snapshot = PlatformSnapshot()
        private set
    private fun now() = SystemClock.elapsedRealtime()
    private fun isEmulator() = Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true) || Build.HARDWARE in listOf("ranchu", "goldfish")
    private fun emit(session: Long = epoch, transform: (PlatformSnapshot) -> PlatformSnapshot) {
        main.post {
            if (!running || session != epoch) return@post
            snapshot = transform(snapshot)
            listener(snapshot)
        }
    }
    override fun start() {
        if (running) return
        running = true; val session = ++epoch
        snapshot = PlatformSnapshot(sourceLabel = if (isEmulator()) "SDK 에뮬레이터 · 실제 차량 검증 아님" else "SDK 기기 · 실제 차량 여부 미검증")
        listener(snapshot)
        startVehicle(session)
        startNavigation(session)
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            runCatching {
                aaos = AaosVehicleSignals(context, { gear, at, eventAt -> emit(session) { current ->
                    if (gear != null && eventAt != null && current.gearEventAtMs != null && eventAt < current.gearEventAtMs) current
                    else current.copy(gear = gear, gearUpdatedAtMs = if (gear != null) at else null, gearEventAtMs = eventAt, vehicleUpdatedAtMs = now()) } },
                    { speed, at, eventAt -> emit(session) { current ->
                    if (speed != null && eventAt != null && current.speedEventAtMs != null && eventAt < current.speedEventAtMs) current
                    else current.copy(speedKph = speed, speedUpdatedAtMs = if (speed != null) at else null, speedEventAtMs = eventAt, vehicleUpdatedAtMs = now()) } },
                    { connected -> emit(session) { it.copy(vehicleConnected = connected, uxAllowsSetup = if (connected) it.uxAllowsSetup else null) } },
                    { allowed -> emit(session) { it.copy(uxAllowsSetup = allowed) } }).also { it.start() }
            }.onFailure { emit(session) { it.copy(status = "차량 주행 신호 미지원 · 대화 자동 시작 대기") } }
        }
        main.post(tick)
    }
    private fun startVehicle(session: Long) {
        runCatching {
            val sdk = Vehicle(context); vehicle = sdk; sdk.initialize(); seat = sdk.getSeat()
            seat?.checkSeatOccupancyCapabilities({ config, areas ->
                emit(session) { current ->
                    if (config.accessType !in setOf(AccessType.READ, AccessType.READ_WRITE)) current.copy(status = "좌석 감지 권한·지원 미확인")
                    else {
                        areas.filter { it.values.size == 1 }.forEach { registerSeat(it, session) }
                        current.copy(status = "좌석 지원 확인 · 실제 감지값 대기")
                    }
                }
            }, { _ -> emit(session) { it.copy(status = "좌석 감지 미지원·연결 미확인") } })
        }.onFailure { emit(session) { it.copy(status = "Vehicle SDK 연결 미확인") } }
    }
    private fun registerSeat(area: SeatArea, session: Long) {
        if (seatListeners.containsKey(area)) return
        val callback = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) { acceptSeat(area.values.single().name, occupancy, session) }
            override fun onFailed(e: Exception) { acceptSeat(area.values.single().name, null, session) }
        }
        seatListeners[area] = callback
        emit(session) { it.copy(seatSignals = it.seatSignals + (area.values.single().name to null)) }
        runCatching { seat?.registerSeatOccupancy(area, callback); seat?.getCurrentSeatOccupancy(area, { callback.onSeatOccupancyUpdated(it) }, { callback.onFailed(it) }) }
            .onFailure { acceptSeat(area.values.single().name, null, session) }
    }
    private fun acceptSeat(key: String, value: Boolean?, session: Long) {
        emit(session) { current ->
            if (value != null) seatTimes[key] = now() else seatTimes.remove(key)
            summarizeSeats(current.copy(seatSignals = current.seatSignals + (key to value)))
        }
    }
    private fun summarizeSeats(current: PlatformSnapshot): PlatformSnapshot {
        val requiredRear = listOf("ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT") + current.seatSignals.keys.filter { it.startsWith("ROW_3_") }
        val rear = requiredRear.map { current.seatSignals[it] }
        return current.copy(frontPassengerOccupied = current.seatSignals["ROW_1_RIGHT"], rearOccupantCount = if (rear.all { it != null }) rear.count { it == true } else null,
            seatUpdatedAtMs = seatTimes.values.maxOrNull())
    }
    private fun startNavigation(session: Long) {
        runCatching {
            val helper = NaviHelper(context)
            navi = helper
            val events = object : NaviHelperEventListener {
                override fun onRouteStateInfo(info: RouteStateInfo) { emit(session) { routeAt = now(); it.copy(routeActive = info.isRouting, navigationUpdatedAtMs = now(), status = "NaviHelper 경로 상태 수신") } }
                override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
                    if (!info.latitude.isFinite() || !info.longitude.isFinite() || info.latitude !in -90.0..90.0 || info.longitude !in -180.0..180.0) return
                    emit(session) { locationAt = now(); it.copy(latitude = info.latitude, longitude = info.longitude, address = info.address, locationUpdatedAtMs = now(), navigationUpdatedAtMs = now()) }
                }
                override fun onDestinationInfo(info: DestinationInfo) { emit(session) { destinationAt = now(); it.copy(destination = info.name, destinationLatitude = info.latitude, destinationLongitude = info.longitude, destinationCoordinatesAtMs = now(), remainingMeters = info.remainDistance.takeIf { d -> d >= 0 }?.toDouble(), remainingSeconds = info.remainTime.takeIf { t -> t >= 0 }?.toDouble(), navigationUpdatedAtMs = now()) } }
                override fun onDrivingInfo(info: DrivingInfo) {
                    // DrivingState.distance is documented as distance from start, not remaining.
                    emit(session) { destinationAt = now(); it.copy(destination = info.destination.name, remainingSeconds = info.destination.duration.takeIf { t -> t >= 0 }?.toDouble(), navigationUpdatedAtMs = now()) }
                }
                override fun onTBTInfo(info: List<TBTInfo>) {
                    val valid = info.filter { it.distance.isFinite() && it.distance >= 0 }.sortedBy { it.distance }
                    val ordinary = setOf(TurnType.TURN_STRAIGHT, TurnType.TURN_WAY, TurnType.TURN_ARRIVE)
                    val significant = valid.filter { it.type !in ordinary && it.type != TurnType.TURN_UNKNOWN }
                    val next = significant.firstOrNull() ?: valid.firstOrNull()
                    val upcoming = RestFacilities.ahead(valid.filter { it.type == TurnType.TURN_RESTAREA || it.type == TurnType.TURN_ENTER_SERVICEAREA }
                        .map { RestInstruction(it.description.orEmpty(), it.distance) })
                    emit(session) {
                        maneuverAt = now()
                        val known = next != null && next.type != TurnType.TURN_UNKNOWN
                        // A nearer straight instruction must not hide a branch/tunnel also in the list.
                        val near = when {
                            significant.any { it.distance <= 650 } -> true
                            valid.any { it.type == TurnType.TURN_UNKNOWN && it.distance <= 650 } -> null
                            known -> false
                            else -> null
                        }
                        it.copy(upcomingFacilities = upcoming, nextManeuverMeters = next?.distance, nextManeuverType = next?.type?.name, nearManeuver = near,
                            tunnel = if (known) valid.any { it.type == TurnType.TURN_TUNNEL && it.distance <= 650 } else null,
                            maneuverUpdatedAtMs = maneuverAt, navigationUpdatedAtMs = now())
                    }
                }
                override fun onNavigationStatus(status: NavigationStatus) {
                    if (status == NavigationStatus.INACTIVE || status == NavigationStatus.FREE_DRIVE) emit(session) { routeAt = now(); it.copy(routeActive = false, destination = null, destinationLatitude = null, destinationLongitude = null, destinationCoordinatesAtMs = null, remainingMeters = null, remainingSeconds = null, nearManeuver = null, tunnel = null, navigationUpdatedAtMs = now()) }
                }
                override fun onDestinationArrived(info: DestinationArrivedInfo) {
                    emit(session) { it.copy(destinationArrived = true, upcomingFacilities = emptyList()) }
                }
                override fun onRouteStarted(info: RouteStartInfo) {
                    emit(session) { it.copy(destinationArrived = false, upcomingFacilities = emptyList(), nearManeuver = null, maneuverUpdatedAtMs = null) }
                }
                override fun onRouteCancelled() { emit(session) { routeAt = now(); it.copy(destinationArrived = false, upcomingFacilities = emptyList(), routeActive = false, destination = null, destinationLatitude = null, destinationLongitude = null, destinationCoordinatesAtMs = null, remainingMeters = null, remainingSeconds = null, nearManeuver = null, tunnel = null) } }
                override fun onDestinationChanged() { if (session == epoch && running) runCatching { helper.getDestinationInfo() } }
                override fun onWayPointInfo(info: List<WaypointInfo>) { main.post { confirmWaypoint(info, session) } }
                override fun onWaypointChanged(info: WaypointChangedInfo) { if (session == epoch && running) runCatching { helper.getWaypointInfo() } }
                override fun onError(errorCode: NaviErrorCode) { emit(session) { it.copy(status = "내비게이션 응답 오류 · ${errorCode.name}", navigationScreenStatus = if (it.navigationScreenStatus != null) "내비게이션 응답 오류 · 화면을 확인해 주세요" else null) } }
            }
            naviListener = events; helper.addListener(events)
            helper.initialize({ emit(session) { navigationConnected = true; it.copy(navigationConnected = true, status = "NaviHelper 연결 · 경로 신호 대기") }; main.post { if (session == epoch && running) queryNavigation() } },
                { _ -> emit(session) { navigationConnected = false; it.copy(navigationConnected = false, status = "NaviHelper 서비스 연결 미확인") } })
        }.onFailure { emit(session) { it.copy(status = "NaviHelper 서비스 미지원·연결 미확인") } }
    }
    private fun queryNavigation() {
        if (!navigationConnected) return
        runCatching { navi?.getRouteStateInfo(); navi?.getCurrentLocationInfo(); navi?.getDestinationInfo(); navi?.getTBTInfo() }
            .onFailure { emit { it.copy(status = "내비게이션 조회 권한·연결 미확인") } }
    }
    private val tick = object : Runnable { override fun run() { if (!running) return; refresh(); main.postDelayed(this, 2000) } }
    override fun refresh() {
        if (!running) return
        aaos?.read()
        seatListeners.forEach { (area, callback) -> runCatching { seat?.getCurrentSeatOccupancy(area, { callback.onSeatOccupancyUpdated(it) }, { callback.onFailed(it) }) } }
        queryNavigation()
        emit { current ->
            val time = now(); fun stale(at: Long?) = at == null || time - at !in 0..8000
            // This flag only observes Android music tracks; vehicle FM or other
            // external sources can be invisible and require the manual hold.
            val media = runCatching { audio.isMusicActive }.getOrNull()
            val playbackUsages = runCatching { audio.activePlaybackConfigurations.map { config ->
                when (config.audioAttributes.usage) {
                    AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME -> PlaybackUsage.MEDIA
                    AudioAttributes.USAGE_ASSISTANT -> PlaybackUsage.ASSISTANT
                    else -> PlaybackUsage.UNKNOWN
                }
            } }.getOrNull()
            val freshSeats = current.seatSignals.mapValues { (key, value) -> if (stale(seatTimes[key])) null else value }
            summarizeSeats(current.copy(gear = current.gear.takeUnless { stale(current.gearUpdatedAtMs) }, speedKph = current.speedKph.takeUnless { stale(current.speedUpdatedAtMs) }, seatSignals = freshSeats,
                routeActive = current.routeActive.takeUnless { stale(routeAt) },
                upcomingFacilities = if (stale(maneuverAt) || stale(routeAt) || current.routeActive != true) emptyList() else current.upcomingFacilities,
                destination = current.destination.takeUnless { stale(destinationAt) }, remainingMeters = current.remainingMeters.takeUnless { stale(destinationAt) }, remainingSeconds = current.remainingSeconds.takeUnless { stale(destinationAt) },
                latitude = current.latitude.takeUnless { stale(locationAt) }, longitude = current.longitude.takeUnless { stale(locationAt) }, address = current.address.takeUnless { stale(locationAt) },
                nextManeuverMeters = current.nextManeuverMeters.takeUnless { stale(maneuverAt) }, nextManeuverType = current.nextManeuverType.takeUnless { stale(maneuverAt) },
                nearManeuver = current.nearManeuver.takeUnless { stale(maneuverAt) }, tunnel = current.tunnel.takeUnless { stale(maneuverAt) },
                mediaActive = media, playbackUsages = playbackUsages, mediaUpdatedAtMs = if (media != null) time else null))
        }
    }
    override fun openNavigation(): Boolean {
        if (!running || !navigationConnected || context.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            val helper = navi ?: return false
            helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
            emit { it.copy(navigationScreenStatus = "내비게이션 화면 요청 · 표시 여부 확인 필요") }
            true
        }.getOrElse {
            emit { it.copy(navigationScreenStatus = "내비게이션 화면을 요청하지 못했어요") }
            false
        }
    }
    override fun addWaypoint(request: WaypointRequest, onResult: (WaypointResult) -> Unit) {
        fun reject(message: String) = onResult(WaypointResult(WaypointResultStatus.REJECTED, message))
        if (!running || !navigationConnected) { reject("NaviHelper 연결 미확인"); return }
        if (!request.userConfirmed || !request.verifiedEntrance) { reject("사용자 확정과 실제 진입점 확인이 필요해요"); return }
        if (snapshot.routeActive != true || snapshot.gear == null || snapshot.speedKph == null || listOf(routeAt, snapshot.gearUpdatedAtMs, snapshot.speedUpdatedAtMs).any { it == null || now() - it > 8000 }) { reject("현재 경로·주행 신호 미확인"); return }
        if (snapshot.nearManeuver != false || maneuverAt == null || now() - maneuverAt!! > 8000) { reject("도로 전환 정보 미확인·접근 구간 · 경유 요청 대기"); return }
        if (request.latitude !in -90.0..90.0 || request.longitude !in -180.0..180.0 || !request.latitude.isFinite() || !request.longitude.isFinite() || request.name.isBlank()) { reject("경유지 좌표·이름을 확인해 주세요"); return }
        if (pending != null) { reject("이전 경유지 응답을 기다리고 있어요"); return }
        if (snapshot.upcomingFacilities.none { it.entrance.label == request.name && it.entrance.latitude == request.latitude && it.entrance.longitude == request.longitude }) { reject("현재 경로 전방의 진입점을 다시 확인해 주세요"); return }
        val call = PendingWaypoint(request, onResult, epoch); pending = call
        runCatching {
            navi?.addWaypoint(RequestWaypointInfo(latitude = request.latitude, longitude = request.longitude, poiName = request.name, waypointIndex = WaypointIndex.FIRST,
                poiId = request.poiId, address = request.address, poiSubId = request.poiSubId, poiProvider = request.poiProvider)) ?: error("NaviHelper unavailable")
            onResult(WaypointResult(WaypointResultStatus.REQUESTED, "경유지 추가 요청 · 적용 응답 대기"))
            emit { it.copy(waypointStatus = "요청 응답 대기") }
            main.postDelayed({ if (pending === call) { pending = null; onResult(WaypointResult(WaypointResultStatus.UNCONFIRMED, "요청은 전달했으나 적용 여부 미확인 · 내비게이션에서 확인해 주세요")); emit { it.copy(waypointStatus = "적용 여부 미확인") } } }, 10000)
            navi?.getWaypointInfo()
        }.onFailure { pending = null; onResult(WaypointResult(WaypointResultStatus.FAILED, "경유지 요청 실패 · 기존 경로 확인 필요")) }
    }
    private fun confirmWaypoint(points: List<WaypointInfo>, session: Long) {
        val call = pending ?: return
        if (session != epoch || call.epoch != session || !running) return
        if (points.any { kotlin.math.abs(it.latitude - call.request.latitude) < .0005 && kotlin.math.abs(it.longitude - call.request.longitude) < .0005 }) {
            pending = null; call.callback(WaypointResult(WaypointResultStatus.CONFIRMED, "NaviHelper 경유지 목록에서 적용 확인")); emit { it.copy(waypointStatus = "경유지 적용 확인") }
        }
    }
    override fun stop() {
        if (!running) return
        running = false; ++epoch; main.removeCallbacks(tick)
        pending?.callback?.invoke(WaypointResult(WaypointResultStatus.UNCONFIRMED, "연결 종료 · 경유지 적용 여부 미확인")); pending = null
        seatListeners.forEach { (area, callback) -> runCatching { seat?.unregisterSeatOccupancy(area, callback) } }; seatListeners.clear(); seatTimes.clear()
        runCatching { vehicle?.release() }; vehicle = null; seat = null
        aaos?.stop(); aaos = null
        naviListener?.let { runCatching { navi?.removeListener(it) } }; runCatching { navi?.release() }; navi = null; naviListener = null; navigationConnected = false
        routeAt = null; locationAt = null; destinationAt = null; maneuverAt = null
        snapshot = PlatformSnapshot(status = "실제 신호 연결 종료"); listener(snapshot)
    }
}
