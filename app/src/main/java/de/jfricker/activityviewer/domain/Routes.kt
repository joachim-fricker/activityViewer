package de.jfricker.activityviewer.domain

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Start und Ende duerfen hoechstens so weit auseinander liegen. */
private const val ENDPOINT_TOLERANCE_M = 500.0

/** Erlaubte Abweichung der Distanz (15 %). */
private const val LENGTH_TOLERANCE = 0.15

/** Rastergroesse fuer den Streckenvergleich; mit Nachbarzellen ca. 100-200 m Toleranz. */
private const val CELL_M = 100.0

/** Abstand der Vergleichspunkte entlang des Tracks. */
private const val SAMPLE_M = 50.0

/** So viel jeder Strecke muss auf der anderen liegen (in beide Richtungen). */
private const val MIN_COVERAGE = 0.9

/** Eingabe: eine Tour mit vereinfachtem Track. */
data class RouteInput(
    val id: Long,
    val typeKey: String,
    val distanceM: Double,
    val startTime: String,
    val points: List<GeoPoint>
)

/** Gruppe von Touren auf (fast) derselben Strecke, aelteste zuerst. */
data class RouteGroup(val ids: List<Long>, val representative: RouteInput)

/** Grobe Kategorie: nur Touren derselben Kategorie werden verglichen. */
private fun category(typeKey: String): String = when (typeKey) {
    in CYCLING_TYPES -> "rad"
    in HIKING_TYPES -> "wandern"
    in SKI_TYPES -> "ski"
    else -> typeKey
}

private class Prepared(val input: RouteInput) {
    val category = category(input.typeKey)
    val start = input.points.first()
    val end = input.points.last()
    val samples: List<GeoPoint> = resample(input.points, SAMPLE_M)
    val cells: Set<Long> = samples.mapTo(HashSet()) { cellOf(it) }
    val sampleCells: LongArray = LongArray(samples.size) { cellOf(samples[it]) }
}

private fun cellOf(p: GeoPoint): Long {
    val cy = floor(p.lat * 111_195.0 / CELL_M).toLong()
    val cx = floor(p.lon * 111_195.0 * cos(Math.toRadians(p.lat)) / CELL_M).toLong()
    return (cx shl 32) or (cy and 0xFFFFFFFFL)
}

/** Anteil der Punkte von a, die (inkl. Nachbarzellen) auf der Strecke b liegen; bricht frueh ab. */
private fun coverage(a: Prepared, b: Prepared): Double {
    val n = a.sampleCells.size
    if (n == 0) return 0.0
    val maxMisses = ((1.0 - MIN_COVERAGE) * n).toInt()
    var misses = 0
    for (c in a.sampleCells) {
        if (!nearCell(c, b.cells)) {
            misses++
            if (misses > maxMisses) return 0.0
        }
    }
    return 1.0 - misses.toDouble() / n
}

private fun nearCell(c: Long, cells: Set<Long>): Boolean {
    val cx = c shr 32
    val cy = c.toInt().toLong()
    for (dx in -1L..1L) for (dy in -1L..1L) {
        if ((((cx + dx) shl 32) or ((cy + dy) and 0xFFFFFFFFL)) in cells) return true
    }
    return false
}

private fun similar(a: Prepared, b: Prepared): Boolean {
    if (a.category != b.category) return false
    val la = a.input.distanceM
    val lb = b.input.distanceM
    if (la <= 0 || lb <= 0) return false
    if (min(la, lb) / max(la, lb) < 1.0 - LENGTH_TOLERANCE) return false
    if (distanceM(a.start, b.start) > ENDPOINT_TOLERANCE_M) return false
    if (distanceM(a.end, b.end) > ENDPOINT_TOLERANCE_M) return false
    return coverage(a, b) >= MIN_COVERAGE && coverage(b, a) >= MIN_COVERAGE
}

/**
 * Findet Strecken, die mindestens zweimal gemacht wurden. Gleiche Richtung wird nicht
 * verlangt, Start und Ziel muessen aber uebereinstimmen. Jede Gruppe wird mit ihrer
 * aeltesten Tour als Referenz verglichen.
 */
fun findRepeatedRoutes(inputs: List<RouteInput>): List<RouteGroup> {
    val groups = ArrayList<Pair<Prepared, MutableList<Long>>>()
    inputs
        .filter { it.points.size >= 2 }
        .sortedBy { it.startTime }
        .forEach { input ->
            val p = Prepared(input)
            val match = groups.firstOrNull { similar(it.first, p) }
            if (match != null) match.second.add(input.id) else groups.add(p to mutableListOf(input.id))
        }
    return groups
        .filter { it.second.size >= 2 }
        .map { RouteGroup(it.second, it.first.input) }
}
