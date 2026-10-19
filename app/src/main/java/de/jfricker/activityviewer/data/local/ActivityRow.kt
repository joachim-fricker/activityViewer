package de.jfricker.activityviewer.data.local

/** Schlanke Projektion fuer die Aktivitaeten-Tabelle (ohne Track-Daten). */
data class ActivityRow(
    val id: Long,
    val name: String,
    val typeKey: String,
    val startTime: String,
    val distanceM: Double,
    val durationS: Double,
    val elevationGain: Double,
    val locationName: String?
)
