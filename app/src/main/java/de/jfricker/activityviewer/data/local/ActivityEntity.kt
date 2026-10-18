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
    val avgHr: Double?,
    val calories: Double?,
    val minElev: Double?,
    val maxElev: Double?,
    // werden im naechsten Schritt aus der GPX gefuellt
    val minLat: Double? = null,
    val maxLat: Double? = null,
    val minLon: Double? = null,
    val maxLon: Double? = null,
    val trackSimplified: String? = null
)
