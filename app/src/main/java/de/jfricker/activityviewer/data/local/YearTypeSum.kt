package de.jfricker.activityviewer.data.local

/** Ergebnis der Gruppierung nach Jahr und Aktivitaetstyp. */
data class YearTypeSum(
    val year: Int,
    val typeKey: String,
    val distanceM: Double,
    val elevationGain: Double,
    val cnt: Int
)
