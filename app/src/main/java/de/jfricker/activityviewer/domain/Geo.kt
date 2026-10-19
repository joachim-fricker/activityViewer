package de.jfricker.activityviewer.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

data class BBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

private const val EARTH_RADIUS_M = 6_371_000.0
private const val M_PER_DEG = 111_320.0

/** Grosskreis-Distanz in Metern (Haversine). */
fun distanceM(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/**
 * Kuerzester Abstand vom Punkt p zum Linienzug in Metern.
 * Der naechste Punkt je Segment wird in einer lokalen ebenen Projektion bestimmt,
 * der Abstand dazu dann exakt per Haversine berechnet.
 */
fun distanceToTrackM(p: GeoPoint, track: List<GeoPoint>): Double {
    if (track.isEmpty()) return Double.POSITIVE_INFINITY
    if (track.size == 1) return distanceM(p, track[0])
    var best = Double.POSITIVE_INFINITY
    for (i in 0 until track.size - 1) {
        best = min(best, distanceM(p, closestOnSegment(p, track[i], track[i + 1])))
    }
    return best
}

private fun closestOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): GeoPoint {
    val kx = cos(Math.toRadians(p.lat))
    val ax = (a.lon - p.lon) * kx
    val ay = a.lat - p.lat
    val dx = (b.lon - a.lon) * kx
    val dy = b.lat - a.lat
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
    return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
}

fun boundingBox(points: List<GeoPoint>): BBox? {
    if (points.isEmpty()) return null
    return BBox(
        minLat = points.minOf { it.lat },
        maxLat = points.maxOf { it.lat },
        minLon = points.minOf { it.lon },
        maxLon = points.maxOf { it.lon }
    )
}

/** Douglas-Peucker: entfernt Punkte, die weniger als toleranceM von der Linie abweichen. */
fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
    val n = points.size
    if (n < 3) return points
    val kx = M_PER_DEG * cos(Math.toRadians(points[0].lat))
    val xs = DoubleArray(n) { points[it].lon * kx }
    val ys = DoubleArray(n) { points[it].lat * M_PER_DEG }
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true

    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, n - 1))
    while (stack.isNotEmpty()) {
        val (s, e) = stack.removeLast()
        var maxD = 0.0
        var idx = -1
        for (i in s + 1 until e) {
            val d = segmentDistance(xs[i], ys[i], xs[s], ys[s], xs[e], ys[e])
            if (d > maxD) {
                maxD = d
                idx = i
            }
        }
        if (idx >= 0 && maxD > toleranceM) {
            keep[idx] = true
            stack.addLast(intArrayOf(s, idx))
            stack.addLast(intArrayOf(idx, e))
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    val cx = ax + t * dx - px
    val cy = ay + t * dy - py
    return sqrt(cx * cx + cy * cy)
}

// ---- Encoded Polyline (Google-Format, 5 Nachkommastellen ~ 1 m): kompakte Speicherung in der DB

fun encodePolyline(points: List<GeoPoint>): String {
    val sb = StringBuilder()
    var prevLat = 0L
    var prevLon = 0L
    for (p in points) {
        val lat = (p.lat * 1e5).roundToLong()
        val lon = (p.lon * 1e5).roundToLong()
        encodeValue(lat - prevLat, sb)
        encodeValue(lon - prevLon, sb)
        prevLat = lat
        prevLon = lon
    }
    return sb.toString()
}

private fun encodeValue(value: Long, sb: StringBuilder) {
    var v = if (value < 0) (value shl 1).inv() else (value shl 1)
    while (v >= 0x20) {
        sb.append(((0x20L or (v and 0x1f)) + 63).toInt().toChar())
        v = v shr 5
    }
    sb.append((v + 63).toInt().toChar())
}

fun decodePolyline(encoded: String): List<GeoPoint> {
    val out = ArrayList<GeoPoint>()
    var i = 0
    fun next(): Long {
        var result = 0L
        var shift = 0
        var b: Int
        do {
            b = encoded[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
        } while (b >= 0x20)
        return if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
    }
    var lat = 0L
    var lon = 0L
    while (i < encoded.length) {
        lat += next()
        lon += next()
        out.add(GeoPoint(lat / 1e5, lon / 1e5))
    }
    return out
}
