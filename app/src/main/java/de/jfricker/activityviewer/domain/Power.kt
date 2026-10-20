package de.jfricker.activityviewer.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Gewicht Fahrer + Rad. */
const val SYSTEM_MASS_KG = 92.0

/** Zeitfenster fuer die beste Durchschnittsleistung am Berg: 5, 10 und 20 Minuten. */
val POWER_WINDOWS_S = listOf(5 * 60, 10 * 60, 20 * 60)

private const val G = 9.81
private const val DRIVETRAIN_EFFICIENCY = 0.976

/** Laengere Luecken zwischen GPS-Punkten gelten als Pause. */
private const val MAX_GAP_S = 10

/** Mehr als 90 km/h ist ein GPS-Sprung. */
private const val MAX_SPEED_MS = 25.0

/** Einzelner Tempo-Ausreisser (m/s gegenueber beiden Nachbarn) = GPS-Sprung. */
private const val SPIKE_MS = 4.0

private const val SPEED_SMOOTH_S = 5

/** Hoehe: Ausreisser gegen den 1-Minuten-Median erkennen, dann glaetten. */
private const val ELE_MEDIAN_HALF_S = 30
private const val ELE_OUTLIER_M = 5.0
private const val ELE_SMOOTH_S = 15

/** Nur Anstiege: das Fenster muss mind. 3 % steigen, die Umgebung (+/- 30 s) ebenfalls. */
private const val MIN_WINDOW_GRADE = 0.03
private const val MIN_CONTEXT_GRADE = 0.03
private const val CONTEXT_S = 30

/** Mehr Hoehengewinn pro Sekunde (2'900 Hm/h) ist ein Messfehler des Hoehenmessers. */
private const val MAX_VERTICAL_MS = 0.8

/**
 * Mehr als 1'800 Hm/h ueber mehrere Minuten schaffen nur Profis - das waere eher eine
 * Bahn oder ein Lift waehrend der Aufzeichnung; solche Fenster zaehlen nicht.
 */
private const val MAX_WINDOW_VAM_MS = 0.5

/** Rollwiderstand und Luftwiderstandsflaeche je Radtyp (typische Werte). */
private data class BikeParams(val crr: Double, val cda: Double)

private fun bikeParams(typeKey: String) = when (typeKey) {
    "road_biking" -> BikeParams(0.004, 0.30)
    "gravel_cycling" -> BikeParams(0.007, 0.35)
    "mountain_biking" -> BikeParams(0.010, 0.40)
    else -> BikeParams(0.005, 0.33)
}

/**
 * Geschaetzte beste Durchschnittsleistung (Watt) am Berg fuer jedes Zeitfenster.
 *
 * Am Anstieg dominiert die Hubarbeit (Masse * g * Hoehengewinn), die sich aus dem
 * Hoehenprofil zuverlaessig bestimmen laesst. Gerechnet wird pro Fenster ueber die
 * Energie: Hubarbeit + Rollwiderstand + Luftwiderstand + Aenderung der Bewegungsenergie,
 * geteilt durch die Zeit. Flach- und Abfahrtsstuecke zaehlen nicht.
 * NO_EFFORT, wenn es keine Zeitstempel oder keinen passenden Anstieg gibt.
 */
fun bestPowers(
    points: List<TimedPoint>,
    typeKey: String,
    windows: List<Int> = POWER_WINDOWS_S
): List<Double> {
    val pts = points.filter { it.timeMs > 0 }.sortedBy { it.timeMs }
        .distinctBy { it.timeMs / 1000 }
    if (pts.size < 2 || pts.none { it.ele != null }) return windows.map { NO_EFFORT }

    // Kumulierte Distanz und Hoehe je Originalpunkt
    val n = pts.size
    val dist = DoubleArray(n)
    val ele = DoubleArray(n)
    var lastEle = pts.firstNotNullOfOrNull { it.ele } ?: 0.0
    for (k in 0 until n) {
        if (k > 0) {
            dist[k] = dist[k - 1] + distanceM(
                GeoPoint(pts[k - 1].lat, pts[k - 1].lon),
                GeoPoint(pts[k].lat, pts[k].lon)
            )
        }
        lastEle = pts[k].ele ?: lastEle
        ele[k] = lastEle
    }

    // Auf 1-Sekunden-Raster interpolieren; Luecken > MAX_GAP_S markieren
    val t0 = pts.first().timeMs / 1000
    val total = (pts.last().timeMs / 1000 - t0).toInt()
    if (total <= (windows.minOrNull() ?: 1)) return windows.map { NO_EFFORT }
    val d = DoubleArray(total + 1)
    val e = DoubleArray(total + 1)
    val moving = BooleanArray(total + 1)
    var i = 0
    for (s in 0..total) {
        val ts = (t0 + s) * 1000.0
        while (i < n - 2 && pts[i + 1].timeMs <= ts) i++
        val a = pts[i]
        val b = pts[i + 1]
        val span = (b.timeMs - a.timeMs).toDouble()
        val f = if (span > 0) ((ts - a.timeMs) / span).coerceIn(0.0, 1.0) else 0.0
        d[s] = dist[i] + f * (dist[i + 1] - dist[i])
        e[s] = ele[i] + f * (ele[i + 1] - ele[i])
        moving[s] = span <= MAX_GAP_S * 1000.0
    }

    // Tempo je Sekunde, GPS-Spruenge durch den vorherigen Wert ersetzen
    val vRaw = DoubleArray(total + 1)
    for (s in 0 until total) vRaw[s] = d[s + 1] - d[s]
    val v = vRaw.copyOf()
    for (s in 0 until total) {
        val prev = if (s > 0) vRaw[s - 1] else vRaw[min(s + 1, total)]
        val next = if (s + 1 < total) vRaw[s + 1] else prev
        val spike = abs(vRaw[s] - prev) > SPIKE_MS && abs(vRaw[s] - next) > SPIKE_MS
        if (vRaw[s] > MAX_SPEED_MS || spike) v[s] = if (s > 0) v[s - 1] else next
    }
    val vSmooth = movingAverage(v, SPEED_SMOOTH_S)

    // Hoehe bereinigen: Ausreisser (Abweichung vom Median) linear ueberbruecken, dann glaetten
    val eSmooth = movingAverage(removeElevationOutliers(e), ELE_SMOOTH_S)

    // Praefixsummen: bereinigte Distanz, Luftwiderstandsarbeit, Pausen, unplausible Hoehenspruenge
    val p = bikeParams(typeKey)
    val m = SYSTEM_MASS_KG
    val distP = DoubleArray(total + 2)
    val aeroP = DoubleArray(total + 2)
    val badP = IntArray(total + 2)
    for (s in 0..total) {
        val rho = 1.225 * exp(-eSmooth[s] / 8400.0)
        distP[s + 1] = distP[s] + v[s]
        aeroP[s + 1] = aeroP[s] + 0.5 * rho * p.cda * v[s] * v[s] * v[s]
        val jump = s < total && abs(eSmooth[s + 1] - eSmooth[s]) > MAX_VERTICAL_MS
        badP[s + 1] = badP[s] + if (!moving[s] || jump) 1 else 0
    }

    return windows.map { w ->
        if (w >= total) return@map NO_EFFORT
        var best = 0.0
        for (s in 0..(total - w)) {
            val end = s + w
            if (badP[end] - badP[s] > 0) continue
            val dd = distP[end] - distP[s]
            val dh = eSmooth[end] - eSmooth[s]
            if (dd < 10.0 || dh / dd < MIN_WINDOW_GRADE || dh / w > MAX_WINDOW_VAM_MS) continue
            // Fenster muss in einem Anstieg liegen, nicht nur eine kurze Kuppe sein
            val a = max(0, s - CONTEXT_S)
            val z = min(total, end + CONTEXT_S)
            val ctxDist = distP[z] - distP[a]
            if (ctxDist <= 0 || (eSmooth[z] - eSmooth[a]) / ctxDist < MIN_CONTEXT_GRADE) continue

            val work = m * G * dh +
                m * G * p.crr * dd +
                (aeroP[end] - aeroP[s]) +
                0.5 * m * (vSmooth[end] * vSmooth[end] - vSmooth[s] * vSmooth[s])
            best = max(best, work / w / DRIVETRAIN_EFFICIENCY)
        }
        if (best > 0) best else NO_EFFORT
    }
}

/** Hoehenwerte, die mehr als ELE_OUTLIER_M vom 1-Minuten-Median abweichen, linear ersetzen. */
private fun removeElevationOutliers(e: DoubleArray): DoubleArray {
    val n = e.size
    val bad = BooleanArray(n)
    val buf = DoubleArray(2 * ELE_MEDIAN_HALF_S + 1)
    for (k in 0 until n) {
        val lo = max(0, k - ELE_MEDIAN_HALF_S)
        val hi = min(n - 1, k + ELE_MEDIAN_HALF_S)
        val len = hi - lo + 1
        System.arraycopy(e, lo, buf, 0, len)
        buf.sort(0, len)
        bad[k] = abs(e[k] - buf[len / 2]) > ELE_OUTLIER_M
    }
    val out = e.copyOf()
    var k = 0
    while (k < n) {
        if (!bad[k]) { k++; continue }
        var j = k
        while (j < n && bad[j]) j++
        val a = if (k > 0) e[k - 1] else if (j < n) e[j] else e[k]
        val b = if (j < n) e[j] else a
        for (q in k until j) out[q] = a + (b - a) * (q - k + 1) / (j - k + 1)
        k = j
    }
    return out
}

private fun movingAverage(x: DoubleArray, window: Int): DoubleArray {
    val half = window / 2
    val prefix = DoubleArray(x.size + 1)
    for (k in x.indices) prefix[k + 1] = prefix[k] + x[k]
    return DoubleArray(x.size) { k ->
        val lo = max(0, k - half)
        val hi = min(x.size - 1, k + half)
        (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
    }
}
