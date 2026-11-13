package ai.hyundaehwa.car.platform

import ai.hyundaehwa.core.*
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** In-memory coarse-area cache. No journey coordinates or spoken data are written to disk. */
object DestinationWeather {
    private data class Cache(val rows: List<HourlyForecast>, val refreshAt: Long)
    private val forecasts = mutableMapOf<String, Cache>()
    fun key(lat: Double, lon: Double): String? = if (lat in 32.0..40.0 && lon in 124.0..132.0)
        String.format(Locale.US, "lat=%.1f&lon=%.1f", lat, lon) else null
    @Synchronized fun fetch(key: String, eta: Long): ArrivalForecast? {
        val now = System.currentTimeMillis()
        val cached = forecasts[key]
        if (cached != null && cached.refreshAt > now) return arrivalForecast(cached.rows, eta)
        forecasts.entries.removeAll { it.value.refreshAt <= now }
        val connection = URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?$key").openConnection() as HttpsURLConnection
        val rows = mutableListOf<HourlyForecast>()
        var expires = now + 3 * 3600_000
        try {
            connection.connectTimeout = 4000; connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", "Hyundaehwa/0.3.0 github.com/lkjh098-cell")
            if (connection.responseCode != 200) return null
            expires = maxOf(expires, connection.expiration)
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val props = JSONObject(json).getJSONObject("properties")
            val age = now / 1000 - Instant.parse(props.getJSONObject("meta").getString("updated_at")).epochSecond
            if (age !in -3600..86400) return null
            val hourly = props.getJSONArray("timeseries")
            for (i in 0 until hourly.length()) {
                val point = hourly.getJSONObject(i)
                val data = point.getJSONObject("data")
                rows += HourlyForecast(Instant.parse(point.getString("time")).epochSecond,
                    data.getJSONObject("instant").getJSONObject("details").optDouble("air_temperature"),
                    data.optJSONObject("next_1_hours")?.optJSONObject("details")?.optDouble("precipitation_amount")?.takeIf { it.isFinite() })
            }
            return arrivalForecast(rows, eta)
        } finally {
            forecasts[key] = Cache(rows, expires)
            connection.disconnect()
        }
    }
}
