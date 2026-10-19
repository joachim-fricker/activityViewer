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
