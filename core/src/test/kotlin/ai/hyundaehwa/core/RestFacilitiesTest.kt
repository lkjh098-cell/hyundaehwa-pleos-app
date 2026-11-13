package ai.hyundaehwa.core

import org.junit.Test
import kotlin.test.*

class RestFacilitiesTest {
    @Test fun onlyForwardNamedSouthboundRestInstructionsAreOffered() {
        val hints = listOf(RestInstruction("천안호두휴게소 (부산방향)", 24430.0),
            RestInstruction("망향 휴게소 (부산 방향)", 5059.0), RestInstruction("망향휴게소 부산방향", 5200.0))
        assertEquals(listOf("망향휴게소","천안호두휴게소"), RestFacilities.ahead(hints).map { it.entrance.label })
        assertEquals(5059.0, RestFacilities.ahead(hints)[0].meters)
        assertTrue(RestFacilities.ahead(listOf(RestInstruction("망향휴게소", 5059.0),
            RestInstruction("망향휴게소 서울방향", 5059.0), RestInstruction("망향휴게소 · 부산까지 350 km", 5059.0), RestInstruction("천안호두휴게소 부산방향", -1.0))).isEmpty())
    }
    @Test fun matchesOnlyForecastNearArrivalAndNeverUsesMissingTemperature() {
        val rows = listOf(HourlyForecast(10000, 12.2, 0.0), HourlyForecast(13600, 25.4, 1.0))
        assertTrue(arrivalForecast(rows, 10120)!!.advice.contains("겉옷"))
        assertTrue(arrivalForecast(rows, 13800)!!.advice.contains("우산"))
        assertNull(arrivalForecast(rows, 18001))
        assertNull(arrivalForecast(listOf(HourlyForecast(10000,Double.NaN,null)),10000))
    }
}
