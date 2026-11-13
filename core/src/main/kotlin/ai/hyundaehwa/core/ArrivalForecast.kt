package ai.hyundaehwa.core

import kotlin.math.abs
import kotlin.math.roundToInt

data class HourlyForecast(val epochSeconds: Long, val temperatureC: Double, val precipitationMm: Double?)
data class ArrivalForecast(val epochSeconds: Long, val advice: String)

fun arrivalForecast(rows: List<HourlyForecast>, eta: Long): ArrivalForecast? {
    val row = rows.filter { it.temperatureC.isFinite() && it.temperatureC in -65.0..65.0 &&
        abs(it.epochSeconds - eta) <= 3600 }.minByOrNull { abs(it.epochSeconds - eta) } ?: return null
    val advice = when {
        row.precipitationMm != null && row.precipitationMm >= 0.5 -> "비가 올 수 있으니 우산을 챙겨 보세요."
        row.temperatureC < 18 -> "내리실 때 걸칠 겉옷을 챙겨 보세요."
        row.temperatureC >= 27 -> "가볍게 입고 마실 물도 챙겨 보세요."
        else -> "가볍게 걸칠 옷을 준비해 두셔도 좋아요."
    }
    return ArrivalForecast(row.epochSeconds, "도착할 무렵 목적지의 예상 기온은 ${row.temperatureC.roundToInt()}도예요. $advice")
}
