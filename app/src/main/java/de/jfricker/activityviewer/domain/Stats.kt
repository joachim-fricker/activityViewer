package de.jfricker.activityviewer.domain

import de.jfricker.activityviewer.data.local.YearTypeSum

val CYCLING_TYPES = listOf("cycling", "road_biking", "mountain_biking", "gravel_cycling")
val HIKING_TYPES = listOf("walking", "running", "mountaineering", "hiking")
/** Garmin-Typ "other" wird hier bewusst als Skitour gezaehlt (alle bisherigen "other" sind Skitouren). */
val SKI_TYPES = listOf("backcountry_skiing", "other")

private val GROUPED_TYPES = (CYCLING_TYPES + HIKING_TYPES + SKI_TYPES).toSet()

data class Sums(val km: Double = 0.0, val elevation: Double = 0.0, val count: Int = 0) {
    operator fun plus(o: Sums) = Sums(km + o.km, elevation + o.elevation, count + o.count)
}

data class YearOverview(
    val year: Int,
    /** Summen je bekanntem typeKey (alle Typen aus den obigen Listen sind enthalten). */
    val byType: Map<String, Sums>,
    /** Alle uebrigen Aktivitaetstypen zusammen (ausserhalb der Gruppen). */
    val rest: Sums
) {
    fun sum(types: List<String>): Sums =
        types.fold(Sums()) { acc, t -> acc + (byType[t] ?: Sums()) }

    /** Summe ueber wirklich alle Aktivitaeten des Jahres. */
    val total: Sums get() = byType.values.fold(rest) { a, b -> a + b }
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
        val byType = GROUPED_TYPES.associateWith { type ->
            r.filter { it.typeKey == type }.fold(Sums()) { acc, x -> acc + x.toSums() }
        }
        val rest = r.filter { it.typeKey !in GROUPED_TYPES }
            .fold(Sums()) { acc, x -> acc + x.toSums() }
        YearOverview(year, byType, rest)
    }
}
