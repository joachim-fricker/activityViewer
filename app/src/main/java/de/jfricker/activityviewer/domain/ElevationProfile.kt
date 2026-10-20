package de.jfricker.activityviewer.domain

/** Ein Punkt des Hoehenprofils: zurueckgelegte Distanz und (geglaettete) Hoehe in Metern. */
data class ProfilePoint(val distM: Double, val eleM: Double)

/**
 * Hoehenprofil aus den GPX-Punkten: Distanz aufsummieren, Hoehe ueber ein gleitendes
 * Fenster glaetten (GPS-/Barometer-Rauschen) und auf hoechstens [buckets] Punkte ausduennen.
 * Leere Liste, wenn die GPX keine Hoehen enthaelt.
 */
fun elevationProfile(points: List<TimedPoint>, buckets: Int = 300, smoothM: Double = 60.0): List<ProfilePoint> {
    val withEle = points.filter { it.ele != null }
    if (withEle.size < 2) return emptyList()

    val dist = DoubleArray(withEle.size)
    for (i in 1 until withEle.size) {
        val a = withEle[i - 1]
        val b = withEle[i]
        dist[i] = dist[i - 1] + distanceM(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon))
    }
    val total = dist.last()
    if (total <= 0.0) return emptyList()

    // Gleitender Mittelwert ueber +-smoothM/2 (zwei Zeiger, O(n))
    val ele = DoubleArray(withEle.size) { withEle[it].ele!! }
    val smooth = DoubleArray(ele.size)
    var lo = 0
    var hi = 0
    var sum = 0.0
    for (i in ele.indices) {
        while (hi < ele.size && dist[hi] <= dist[i] + smoothM / 2) sum += ele[hi++]
        while (dist[lo] < dist[i] - smoothM / 2) sum -= ele[lo++]
        smooth[i] = sum / (hi - lo)
    }

    // Auf gleichmaessige Distanzschritte ausduennen
    val out = ArrayList<ProfilePoint>(buckets + 1)
    var j = 0
    for (k in 0..buckets) {
        val d = total * k / buckets
        while (j < dist.size - 2 && dist[j + 1] < d) j++
        val span = dist[j + 1] - dist[j]
        val t = if (span > 0) ((d - dist[j]) / span).coerceIn(0.0, 1.0) else 0.0
        out.add(ProfilePoint(d, smooth[j] + t * (smooth[j + 1] - smooth[j])))
    }
    return out
}
