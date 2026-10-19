package de.jfricker.activityviewer.domain

/** Deutsche Kurzbezeichnungen der Garmin-typeKeys. */
private val TYPE_LABELS = mapOf(
    "cycling" to "Rad",
    "road_biking" to "Rennrad",
    "mountain_biking" to "MTB",
    "gravel_cycling" to "Gravel",
    "walking" to "Gehen",
    "running" to "Laufen",
    "mountaineering" to "Bergtour",
    "hiking" to "Wandern",
    "backcountry_skiing" to "Skitour",
    "other" to "Skitour" // alle "other" sind Skitouren
)

fun typeLabel(typeKey: String): String = TYPE_LABELS[typeKey] ?: typeKey
