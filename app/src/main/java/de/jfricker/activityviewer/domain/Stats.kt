package de.jfricker.activityviewer.domain

import de.jfricker.activityviewer.data.local.YearTypeSum

/** Radfahren: jeder Typ einzeln plus Total. */
val CYCLING_TYPES = listOf("cycling", "road_biking", "mountain_biking", "gravel_cycling")

/** Wandern: alle diese Typen werden zu einem Wert zusammengefasst. */
val HIKING_TYPES = setOf("walking", "running", "mountaineering", "hiking")

data class Sums(val km: Double = 0.0, val elevation: Double = 0.0, val count: Int = 0) {
    operator fun plus(o: Sums) = Sums(km + o.km, elevation + o.elevation, count + o.count)
}

data class YearOverview(
    val year: Int,
    val cycling: Map<String, Sums>,   // Schluessel = typeKey aus CYCLING_TYPES
    val hiking: Sums
) {
    val cyclingTotal: Sums get() = cycling.values.fold(Sums()) { a, b -> a + b }
}

private fun YearTypeSum.toSums() = Sums(distanceM / 1000.0, elevationGain, cnt)

/** Fuellt auch Jahre ohne Aktivitaeten mit 0, damit die Achse lueckenlos ist. */
fun buildOverview(rows: List<YearTypeSum>): List<YearOverview> {
    if (rows.isEmpty()) return emptyList()
    val byYear = rows.groupBy { it.year }
    val first = byYear.keys.min()
    val last = byYear.keys.max()
    return (first..last).map { year ->
        val r = byYear[year].orEmpty()
        val cycling = CYCLING_TYPES.associateWith { type ->
            r.filter { it.typeKey == type }.fold(Sums()) { acc, x -> acc + x.toSums() }
        }
        val hiking = r.filter { it.typeKey in HIKING_TYPES }
            .fold(Sums()) { acc, x -> acc + x.toSums() }
        YearOverview(year, cycling, hiking)
    }
}
