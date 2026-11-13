package ai.hyundaehwa.core

data class FacilityEntrance(val id: String, val label: String, val latitude: Double, val longitude: Double)
data class AheadFacility(val entrance: FacilityEntrance, val meters: Double)
data class RestInstruction(val description: String, val meters: Double)

/** Entrance points are at the downstream end of a directional motorway ramp, inside the facility. */
object RestFacilities {
    val known = listOf(
        FacilityEntrance("manghyang-south", "망향휴게소", 36.8558990, 127.1824692),
        FacilityEntrance("cheonan-hodu-south", "천안호두휴게소", 36.7308416, 127.2624682)
    )
    fun ahead(restInstructions: List<RestInstruction>): List<AheadFacility> = known.mapNotNull { facility ->
        restInstructions.filter {
            val text = it.description.replace(Regex("\\s+"), "")
            val direction = Regex("(?:부산(?:방향|행|방면)|[（(]부산[)）])")
            it.meters.isFinite() && it.meters > 0 && facility.label in text &&
                direction.containsMatchIn(text) && "서울" !in text && "상행" !in text
        }.minByOrNull { it.meters }?.let { AheadFacility(facility, it.meters) }
    }.sortedBy { it.meters }
}
