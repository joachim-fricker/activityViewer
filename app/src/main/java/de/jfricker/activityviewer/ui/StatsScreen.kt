package de.jfricker.activityviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.jfricker.activityviewer.domain.CYCLING_TYPES
import de.jfricker.activityviewer.domain.HIKING_TYPES
import de.jfricker.activityviewer.domain.SKI_TYPES
import de.jfricker.activityviewer.domain.Sums
import de.jfricker.activityviewer.domain.YearOverview
import de.jfricker.activityviewer.domain.seasonLabel
import de.jfricker.activityviewer.domain.seasonLabelShort
import java.text.NumberFormat
import java.util.Locale

private enum class Metric(val label: String, val unit: String) {
    KM("Kilometer", "km"),
    ELEVATION("Höhenmeter", "m")
}

private enum class Group(val label: String, val title: String) {
    CYCLING("Radfahren", "Radfahren"),
    HIKING("Wandern", "Wandern"),
    SKI("Skitouren", "Skitouren"),
    TOTAL("Total", "Alle Aktivitäten")
}

/** Eine Serie = ein Segment im gestapelten Balken und eine Spalte in der Tabelle. */
private class Series(
    val label: String,
    val color: Color,
    val pick: (YearOverview) -> Sums
)

private fun typeSeries(type: String, label: String, color: Color) =
    Series(label, color) { it.byType.getValue(type) }

private val groupSeries: Map<Group, List<Series>> = mapOf(
    Group.CYCLING to listOf(
        typeSeries(CYCLING_TYPES[0], "Rad", Color(0xFF1E88E5)),
        typeSeries(CYCLING_TYPES[1], "Rennrad", Color(0xFFE53935)),
        typeSeries(CYCLING_TYPES[2], "MTB", Color(0xFF43A047)),
        typeSeries(CYCLING_TYPES[3], "Gravel", Color(0xFFFB8C00))
    ),
    Group.HIKING to listOf(
        typeSeries(HIKING_TYPES[0], "Gehen", Color(0xFF6D4C41)),
        typeSeries(HIKING_TYPES[1], "Laufen", Color(0xFF8E24AA)),
        typeSeries(HIKING_TYPES[2], "Bergtour", Color(0xFF00897B)),
        typeSeries(HIKING_TYPES[3], "Wandern", Color(0xFFFDD835))
    ),
    Group.SKI to listOf(
        Series("Skitouren", Color(0xFF00ACC1)) { it.sum(SKI_TYPES) }
    ),
    Group.TOTAL to listOf(
        Series("Rad", Color(0xFF1E88E5)) { it.sum(CYCLING_TYPES) },
        Series("Wandern", Color(0xFF6D4C41)) { it.sum(HIKING_TYPES) },
        Series("Ski", Color(0xFF00ACC1)) { it.sum(SKI_TYPES) },
        Series("Sonstige", Color(0xFF9E9E9E)) { it.rest }
    )
)

/** Gruppen, bei denen die Tabelle zusaetzlich die Anzahl der Aktivitaeten zeigt. */
private val groupsWithCount = setOf(Group.SKI)

private const val CHART_HEIGHT = 200f

@Composable
fun StatsScreen(
    yearOverview: List<YearOverview>,
    skiSeasons: List<YearOverview>,
    modifier: Modifier = Modifier
) {
    if (yearOverview.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Noch keine Daten")
        }
        return
    }

    var metric by rememberSaveable { mutableStateOf(Metric.KM) }
    var group by rememberSaveable { mutableStateOf(Group.CYCLING) }
    val fmt = remember { NumberFormat.getIntegerInstance(Locale.forLanguageTag("de-CH")) }

    val series = groupSeries.getValue(group)
    // Skitouren nach Saison (z. B. 2023/24), alle anderen nach Kalenderjahr
    val bySeason = group == Group.SKI
    val overview = if (bySeason) skiSeasons else yearOverview
    val periodName = if (bySeason) "Saison" else "Jahr"
    fun label(y: YearOverview) = if (bySeason) seasonLabel(y.year) else y.year.toString()
    fun axisLabel(y: YearOverview) = if (bySeason) seasonLabelShort(y.year) else y.year.toString()
    val showTotal = series.size > 1
    val showCount = group in groupsWithCount

    fun countOf(y: YearOverview): Int = series.sumOf { it.pick(y).count }

    fun pick(s: Sums): Double = if (metric == Metric.KM) s.km else s.elevation

    val valuesPerYear: List<List<Double>> =
        overview.map { y -> series.map { pick(it.pick(y)) } }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric.entries.forEach {
                FilterChip(
                    selected = metric == it,
                    onClick = { metric = it },
                    label = { Text(it.label) }
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Group.entries.forEach {
                FilterChip(
                    selected = group == it,
                    onClick = { group = it },
                    label = { Text(it.label) }
                )
            }
        }

        Text(
            "${group.title}: ${metric.label} pro $periodName (${metric.unit})",
            style = MaterialTheme.typography.titleMedium
        )

        if (showTotal) Legend(series)

        StackedBarChart(
            labels = overview.map { axisLabel(it) },
            valuesPerYear = valuesPerYear,
            colors = series.map { it.color },
            format = { fmt.format(it) }
        )

        // Tabelle: neuestes Jahr oben
        val header = buildList {
            add(periodName)
            series.forEach { add(it.label) }
            if (showTotal) add("Total")
            if (showCount) add("Touren")
        }
        TableRow(header, bold = true)
        HorizontalDivider()
        overview.indices.reversed().forEach { i ->
            val v = valuesPerYear[i]
            val cells = buildList {
                add(label(overview[i]))
                v.forEach { add(fmt.format(it)) }
                if (showTotal) add(fmt.format(v.sum()))
                if (showCount) add(countOf(overview[i]).toString())
            }
            TableRow(cells)
        }
        HorizontalDivider()
        val sumCells = buildList {
            add("Alle")
            series.indices.forEach { c -> add(fmt.format(valuesPerYear.sumOf { it[c] })) }
            if (showTotal) add(fmt.format(valuesPerYear.sumOf { it.sum() }))
            if (showCount) add(overview.sumOf { countOf(it) }.toString())
        }
        TableRow(sumCells, bold = true)
    }
}

@Composable
private fun Legend(series: List<Series>) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(s.color)
                )
                Spacer(Modifier.width(6.dp))
                Text(s.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun StackedBarChart(
    labels: List<String>,
    valuesPerYear: List<List<Double>>,
    colors: List<Color>,
    format: (Double) -> String
) {
    val max = valuesPerYear.maxOfOrNull { it.sum() }?.takeIf { it > 0 } ?: 1.0

    Row(
        Modifier
            .fillMaxWidth()
            .height((CHART_HEIGHT + 56f).dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        labels.forEachIndexed { i, label ->
            val values = valuesPerYear[i]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(format(values.sum()), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(2.dp))
                // oberstes Segment zuerst zeichnen => Reihenfolge umkehren
                Column(Modifier.width(44.dp)) {
                    values.indices.reversed().forEach { s ->
                        val h = (values[s] / max * CHART_HEIGHT).toFloat()
                        if (h > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(h.dp)
                                    .background(colors[s])
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        cells.forEachIndexed { i, text ->
            Text(
                text,
                modifier = Modifier.weight(1f),
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (bold) FontWeight.Bold else null
            )
        }
    }
}
