package de.jfricker.activityviewer.domain

/** Trackpunkt mit Zeitstempel (Millisekunden seit 1970). */
data class TimedPoint(val lat: Double, val lon: Double, val timeMs: Long, val ele: Double? = null)

/** Distanzen fuer die Bestzeiten beim Radfahren. */
val BEST_EFFORT_DISTANCES_M = listOf(5_000.0, 10_000.0, 20_000.0, 40_000.0)

/** Wert fuer "nicht verfuegbar" (Tour zu kurz, keine Zeitstempel, Indoor). */
const val NO_EFFORT = -1.0

/**
 * Schnellste Zeit in Sekunden fuer jede Distanz, irgendwo innerhalb der Tour
 * (gleitendes Fenster, Start interpoliert; Pausen zaehlen mit wie bei Strava).
 */
fun bestEfforts(points: List<TimedPoint>, distances: List<Double> = BEST_EFFORT_DISTANCES_M): List<Double> {
    val pts = points.filter { it.timeMs > 0 }
    val n = pts.size
    if (n < 2) return distances.map { NO_EFFORT }
    val cum = DoubleArray(n)
    for (k in 1 until n) {
        cum[k] = cum[k - 1] + distanceM(
            GeoPoint(pts[k - 1].lat, pts[k - 1].lon),
            GeoPoint(pts[k].lat, pts[k].lon)
        )
    }
    return distances.map { d ->
        if (cum[n - 1] < d) return@map NO_EFFORT
        var best = Double.MAX_VALUE
        var i = 0
        for (j in 1 until n) {
            if (cum[j] < d) continue
            val target = cum[j] - d
            while (cum[i + 1] <= target) i++ // cum[i] <= target < cum[i+1]
            val seg = cum[i + 1] - cum[i]
            val f = if (seg > 0) (target - cum[i]) / seg else 0.0
            val tStart = pts[i].timeMs + f * (pts[i + 1].timeMs - pts[i].timeMs)
            val dt = (pts[j].timeMs - tStart) / 1000.0
            if (dt > 0 && dt < best) best = dt
        }
        if (best == Double.MAX_VALUE) NO_EFFORT else best
    }
}
