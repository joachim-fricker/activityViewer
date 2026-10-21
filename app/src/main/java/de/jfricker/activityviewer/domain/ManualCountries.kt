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
