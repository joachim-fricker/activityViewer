package de.jfricker.activityviewer.data.remote

import de.jfricker.activityviewer.data.local.ActivityEntity
import kotlinx.serialization.Serializable

@Serializable
data class TypeDto(val typeKey: String = "unknown")

@Serializable
data class ActivityDto(
    val activityId: Long,
    val activityName: String? = null,
    val startTimeLocal: String,
    val activityType: TypeDto? = null,
    val distance: Double? = null,
    val duration: Double? = null,
    val elevationGain: Double? = null,
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,
    val locationName: String? = null,
    val averageSpeed: Double? = null,
    val averageHR: Double? = null,
    val calories: Double? = null,
    val minElevation: Double? = null,
    val maxElevation: Double? = null
) {
    fun toEntity() = ActivityEntity(
        id = activityId,
        name = activityName ?: "Aktivität $activityId",
        typeKey = activityType?.typeKey ?: "unknown",
        startTime = startTimeLocal,
        year = startTimeLocal.take(4).toInt(),
        distanceM = distance ?: 0.0,
        durationS = duration ?: 0.0,
        elevationGain = elevationGain ?: 0.0,
        startLat = startLatitude,
        startLon = startLongitude,
        endLat = endLatitude,
        endLon = endLongitude,
        locationName = locationName,
        avgSpeed = averageSpeed,
        avgHr = averageHR,
        calories = calories,
        minElev = minElevation,
        maxElev = maxElevation
    )
}
