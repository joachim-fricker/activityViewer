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
import de.jfricker.activityviewer.domain.Sums
import de.jfricker.activityviewer.domain.YearOverview
import java.text.NumberFormat
import java.util.Locale

private enum class Metric(val label: String, val unit: String) {
    KM("Kilometer", "km"),
    ELEVATION("Höhenmeter", "m")
}

private enum class Group(val label: String) {
    CYCLING("Radfahren"),
    HIKING("Wandern")
}

private val cyclingLabels = mapOf(
    "cycling" to "Rad",
    "road_biking" to "Rennrad",
    "mountain_biking" to "MTB",
    "gravel_cycling" to "Gravel"
)
private val cyclingColors = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00)
)
private val hikingColor = Color(0xFF6D4C41)

private const val CHART_HEIGHT = 200f

@Composable
fun StatsScreen(overview: List<YearOverview>, modifier: Modifier = Modifier) {
    if (overview.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Noch keine Daten")
        }
        return
    }

    var metric by rememberSaveable { mutableStateOf(Metric.KM) }
    var group by rememberSaveable { mutableStateOf(Group.CYCLING) }
    val fmt = remember { NumberFormat.getIntegerInstance(Locale.forLanguageTag("de-CH")) }

    fun pick(s: Sums): Double = if (metric == Metric.KM) s.km else s.elevation

    // Serien (Segmente der Balken) und Werte je Jahr
    val labels: List<String>
    val colors: List<Color>
    val valuesPerYear: List<List<Double>>
    if (group == Group.CYCLING) {
        labels = CYCLING_TYPES.map { cyclingLabels.getValue(it) }
        colors = cyclingColors
        valuesPerYear = overview.map { y -> CYCLING_TYPES.map { pick(y.cycling.getValue(it)) } }
    } else {
        labels = listOf("Wandern")
        colors = listOf(hikingColor)
        valuesPerYear = overview.map { listOf(pick(it.hiking)) }
    }

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Group.entries.forEach {
                FilterChip(
                    selected = group == it,
                    onClick = { group = it },
                    label = { Text(it.label) }
                )
            }
        }

        Text(
            "${group.label}: ${metric.label} pro Jahr (${metric.unit})",
            style = MaterialTheme.typography.titleMedium
        )

        if (group == Group.CYCLING) Legend(labels, colors)

        StackedBarChart(
            years = overview.map { it.year },
            valuesPerYear = valuesPerYear,
            colors = colors,
            format = { fmt.format(it) }
        )

        // Tabelle: neuestes Jahr oben
        val header = buildList {
            add("Jahr")
            addAll(labels)
            if (group == Group.CYCLING) add("Total")
        }
        TableRow(header, bold = true)
        HorizontalDivider()
        overview.indices.reversed().forEach { i ->
            val v = valuesPerYear[i]
            val cells = buildList {
                add(overview[i].year.toString())
                v.forEach { add(fmt.format(it)) }
                if (group == Group.CYCLING) add(fmt.format(v.sum()))
            }
            TableRow(cells)
        }
        HorizontalDivider()
        val sumCells = buildList {
            add("Alle")
            labels.indices.forEach { c -> add(fmt.format(valuesPerYear.sumOf { it[c] })) }
            if (group == Group.CYCLING) add(fmt.format(valuesPerYear.sumOf { it.sum() }))
        }
        TableRow(sumCells, bold = true)
    }
}

@Composable
private fun Legend(labels: List<String>, colors: List<Color>) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors[i])
                )
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun StackedBarChart(
    years: List<Int>,
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
        years.forEachIndexed { i, year ->
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
                Text(year.toString(), style = MaterialTheme.typography.labelMedium)
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
