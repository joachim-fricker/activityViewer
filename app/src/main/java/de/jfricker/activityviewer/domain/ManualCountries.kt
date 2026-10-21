package de.jfricker.activityviewer.domain

/**
 * Laender, die ohne (GPS-)Fotos als besucht gelten. Codes = ADM0_A3 aus assets/countries.geojson.
 * GBR = ganzes Vereinigtes Koenigreich (England ist in den Grenzdaten kein eigenes Land).
 */
val MANUAL_VISITED_COUNTRIES = setOf(
    "CAN", // Kanada
    "USA", // USA
    "MEX", // Mexiko
    "TUN", // Tunesien
    "MAR", // Marokko
    "GBR", // England
    "IRL", // Irland
    "NLD", // Holland
    "BEL", // Belgien
    "AND", // Andorra
    "HUN", // Ungarn
    "DNK", // Daenemark
    "SWE", // Schweden
    "CUB", // Kuba
    "ECU", // Equador
    "BOL", // Bolivien
    "PER", // Peru
    "FIN", // Finnland
    "DMA"  // Dominica
)

/** Laender, die trotz Fotos nicht als besucht gelten (Fotos stammen nicht von einer eigenen Reise). */
val EXCLUDED_COUNTRIES = setOf(
    "IND"  // Indien
)

/** Gebiet, in dem das Land fest vorgegeben ist (Grenzdaten dort zu ungenau). */
class CountryOverride(
    val minLat: Double, val maxLat: Double,
    val minLon: Double, val maxLon: Double,
    val country: String
) {
    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
}

val COUNTRY_OVERRIDES = listOf(
    // Khumbu-Tal bis Everest Base Camp: Die Grenze in den Natural-Earth-Daten verlaeuft
    // zu weit suedlich, das Basislager laege sonst in China.
    CountryOverride(27.80, 28.02, 86.60, 86.88, "NPL")
)

/**
 * Erhoehen, wenn sich COUNTRY_OVERRIDES, EXCLUDED/... oder die Grenzdaten aendern:
 * Beim naechsten Scan wird dann das Land aller Fotos neu bestimmt (ohne die Fotos neu zu lesen).
 */
const val COUNTRY_RULES_VERSION = 1
