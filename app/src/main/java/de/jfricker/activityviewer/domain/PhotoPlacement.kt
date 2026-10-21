package de.jfricker.activityviewer.domain

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Fotos so viel vor dem Start bzw. nach dem Ende der Tour gehoeren noch dazu. */
const val PHOTO_BUFFER_MS = 15 * 60 * 1000L

/** Liegt die GPS-Position eines Fotos weiter weg vom Track, wird sie ueber die Aufnahmezeit bestimmt. */
const val PHOTO_MAX_OFF_TRACK_M = 500.0

private val GARMIN_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Zeitfenster (UTC-Millisekunden, inkl. Puffer), in dem Fotos zur Tour gehoeren.
 * Bevorzugt die Zeitstempel aus der GPX (exakt, unabhaengig von der Zeitzone);
 * sonst Startzeit (lokal, Zeitzone des Handys) + Dauer. null = nicht bestimmbar.
 */
fun tourWindow(startTimeLocal: String, durationS: Double, timeline: List<TimedPoint>): LongRange? {
    val (start, end) = if (timeline.isNotEmpty()) {
        timeline.first().timeMs to timeline.last().timeMs
    } else {
        val s = try {
            LocalDateTime.parse(startTimeLocal.take(19), GARMIN_LOCAL)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            return null
        }
        s to s + (durationS * 1000).toLong()
    }
    return (start - PHOTO_BUFFER_MS)..(end + PHOTO_BUFFER_MS)
}

/**
 * Position auf dem Track zum Zeitpunkt t (linear zwischen den GPX-Punkten interpoliert).
 * Vor dem Start bzw. nach dem Ende: Start- bzw. Endpunkt. null = keine Zeitstempel.
 * timeline muss nach Zeit sortiert sein.
 */
fun positionAt(timeline: List<TimedPoint>, t: Long): GeoPoint? {
    if (timeline.isEmpty()) return null
    val first = timeline.first()
    val last = timeline.last()
    if (t <= first.timeMs) return GeoPoint(first.lat, first.lon)
    if (t >= last.timeMs) return GeoPoint(last.lat, last.lon)
    val found = timeline.binarySearchBy(t) { it.timeMs }
    if (found >= 0) return timeline[found].let { GeoPoint(it.lat, it.lon) }
    val i = -found - 1 // erster Punkt mit timeMs > t, 1 <= i <= size-1
    val a = timeline[i - 1]
    val b = timeline[i]
    val span = (b.timeMs - a.timeMs).toDouble()
    val f = if (span <= 0) 0.0 else (t - a.timeMs) / span
    return GeoPoint(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon))
}

/**
 * Wo das Foto auf der Karte erscheint: GPS aus dem Foto, wenn es nahe am Track liegt,
 * sonst die Position auf dem Track zur Aufnahmezeit. null = nicht bestimmbar.
 */
fun photoPosition(
    exif: GeoPoint?,
    takenMs: Long,
    track: List<GeoPoint>,
    timeline: List<TimedPoint>
): GeoPoint? {
    if (exif != null && (track.isEmpty() || distanceToTrackM(exif, track) <= PHOTO_MAX_OFF_TRACK_M)) {
        return exif
    }
    return positionAt(timeline, takenMs) ?: exif
}
