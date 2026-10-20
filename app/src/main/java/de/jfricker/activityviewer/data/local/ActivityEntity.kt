package de.jfricker.activityviewer.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "activity",
    indices = [Index("year"), Index("typeKey"), Index("startTime")]
)
data class ActivityEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val typeKey: String,
    val startTime: String,          // startTimeLocal
    val year: Int,
    val distanceM: Double,
    val durationS: Double,
    val elevationGain: Double,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
    val locationName: String?,
    val avgSpeed: Double?,
    val maxSpeed: Double? = null,   // m/s
    val avgHr: Double?,
    val calories: Double?,
    val minElev: Double?,
    val maxElev: Double?,
    // werden im naechsten Schritt aus der GPX gefuellt
    val minLat: Double? = null,
    val maxLat: Double? = null,
    val minLon: Double? = null,
    val maxLon: Double? = null,
    val trackSimplified: String? = null,
    // Bestzeiten Rad in Sekunden aus der GPX; null = noch nicht berechnet, -1 = nicht verfuegbar
    val best5kS: Double? = null,
    val best10kS: Double? = null,
    val best20kS: Double? = null,
    val best40kS: Double? = null,
    // geschaetzte beste Leistung am Berg in Watt (5/10/20 min); null = noch nicht berechnet, -1 = n/a
    val bestP5mW: Double? = null,
    val bestP10mW: Double? = null,
    val bestP20mW: Double? = null,
    // Aufzeichnungsgeraet aus dem Garmin-JSON; null = noch nicht nachgeladen, 0 = unbekannt
    val deviceId: Long? = null
)
