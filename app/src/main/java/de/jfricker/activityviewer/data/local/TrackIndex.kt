package de.jfricker.activityviewer.data.local

/** Aktivitaet, fuer die der Track-Index noch fehlt. */
data class TrackIndexTodo(
    val id: Long,
    val typeKey: String,
    val name: String,
    val distanceM: Double
)

/** Vereinfachter Track einer Aktivitaet fuer die Umkreissuche. */
data class TrackIndexRow(
    val id: Long,
    val trackSimplified: String
)

/** Vereinfachter Track fuer die Heatmap. */
data class HeatTrackRow(
    val id: Long,
    val typeKey: String,
    val trackSimplified: String
)

/** Radtour, fuer die die Bestzeiten noch fehlen. */
data class BestEffortTodo(
    val id: Long,
    val typeKey: String,
    val name: String
)

/** Projektion fuer die Rekorde. */
data class RecordRow(
    val id: Long,
    val name: String,
    val typeKey: String,
    val startTime: String,
    val distanceM: Double,
    val durationS: Double,
    val elevationGain: Double,
    val maxElev: Double?,
    val maxSpeed: Double?,
    val best5kS: Double?,
    val best10kS: Double?,
    val best20kS: Double?,
    val best40kS: Double?,
    val bestP5mW: Double?,
    val bestP10mW: Double?,
    val bestP20mW: Double?,
    val deviceId: Long?
)
