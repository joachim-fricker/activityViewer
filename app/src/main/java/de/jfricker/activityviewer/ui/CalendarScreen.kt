package de.jfricker.activityviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.activityviewer.data.local.ActivityRow
import de.jfricker.activityviewer.domain.SKI_SEASON_START_MONTH
import de.jfricker.activityviewer.domain.seasonLabel
import de.jfricker.activityviewer.domain.typeLabel
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import kotlin.math.roundToInt

private enum class HeatMetric(val label: String, val unit: String) {
    KM("Kilometer", "km"),
    ELEVATION("Höhenmeter", "m")
}

private fun groupColor(g: QuickFilter): Color = when (g) {
    QuickFilter.ALL -> Color(0xFF43A047)
    QuickFilter.CYCLING -> Color(0xFF1E88E5)
    QuickFilter.HIKING -> Color(0xFF6D4C41)
    QuickFilter.SKI -> Color(0xFF00ACC1)
}

/** Deckkraft je Stufe (Stufe 0 = wenig ... 3 = viel). */
private val LEVEL_ALPHA = floatArrayOf(0.3f, 0.5f, 0.75f, 1f)

private val WEEKDAYS = listOf("M", "D", "M", "D", "F", "S", "S")
private val GAP = 2.dp

/**
 * Kalender-Heatmap: ein Jahr (bei Skitouren eine Saison Sep-Aug) als Monatskalender,
 * Faerbung nach km bzw. Hm pro Tag. Antippen eines Tages oeffnet die Details der Tour;
 * bei mehreren Touren am selben Tag erscheint zuerst eine Auswahlliste.
 */
@Composable
fun CalendarScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val all by vm.activities.collectAsStateWithLifecycle()

    var group by rememberSaveable { mutableStateOf(QuickFilter.ALL) }
    var metric by rememberSaveable { mutableStateOf(HeatMetric.KM) }
    var chosenPeriod by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }

    val id = selectedId
    if (id != null) {
        BackHandler { selectedId = null }
        ActivityDetailScreen(vm, id, onBack = { selectedId = null }, modifier)
        return
    }

    val startMonth = if (group == QuickFilter.SKI) SKI_SEASON_START_MONTH else 1
    fun periodOf(d: LocalDate) = if (d.monthValue >= startMonth) d.year else d.year - 1

    val byDay: Map<LocalDate, List<ActivityRow>> = remember(all, group) {
        val types = group.types
        all.asSequence()
            .filter { types == null || it.typeKey in types }
            .filter { it.startTime.length >= 10 }
            .groupBy { LocalDate.parse(it.startTime.substring(0, 10)) }
    }

    val today = remember { LocalDate.now() }
    val current = periodOf(today)
    val first = minOf(byDay.keys.minOfOrNull { periodOf(it) } ?: current, current)
    val period = (chosenPeriod ?: current).coerceIn(first, current)
    val from = LocalDate.of(period, startMonth, 1)
    val to = from.plusYears(1) // exklusiv

    fun valueOf(list: List<ActivityRow>): Double =
        if (metric == HeatMetric.KM) list.sumOf { it.distanceM } / 1000.0
        else list.sumOf { it.elevationGain }

    val inPeriod = byDay.filterKeys { !it.isBefore(from) && it.isBefore(to) }
    val dayValues = inPeriod.mapValues { valueOf(it.value) }

    // Stufen nach Quartilen der aktiven Tage => Ausreisser machen die Karte nicht "blass"
    val thresholds = remember(dayValues) {
        val s = dayValues.values.filter { it > 0 }.sorted()
        if (s.isEmpty()) emptyList()
        else listOf(0.25, 0.5, 0.75).map { p -> s[((s.size - 1) * p).roundToInt()] }
    }

    fun levelOf(d: LocalDate): Int {
        val v = dayValues[d] ?: return -1
        return thresholds.count { v > it }.coerceAtMost(LEVEL_ALPHA.size - 1)
    }

    val base = groupColor(group)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val fmtInt = remember { NumberFormat.getIntegerInstance(LOCALE) }
    val fmtKm = remember { km1() }
    val periodLabel = if (startMonth == 1) period.toString() else seasonLabel(period)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickFilter.entries.forEach {
                FilterChip(
                    selected = group == it,
                    onClick = { group = it; selectedDay = null },
                    label = { Text(it.label) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeatMetric.entries.forEach {
                FilterChip(
                    selected = metric == it,
                    onClick = { metric = it },
                    label = { Text(it.label) }
                )
            }
        }

        // Jahr / Saison waehlen
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { chosenPeriod = period - 1; selectedDay = null },
                enabled = period > first
            ) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Text(
                periodLabel,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge
            )
            TextButton(
                onClick = { chosenPeriod = period + 1; selectedDay = null },
                enabled = period < current
            ) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }

        val activityCount = inPeriod.values.sumOf { it.size }
        val total = dayValues.values.sum()
        Text(
            "${inPeriod.size} Tage · $activityCount Aktivitäten · " +
                "${fmtInt.format(total)} ${metric.unit}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HeatLegend(base, empty)

        // 12 Monate in zwei Spalten
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val colGap = 16.dp
            val monthWidth = (maxWidth - colGap) / 2
            val cell = (monthWidth - GAP * 6) / 7
            val months = (0 until 12).map { YearMonth.from(from).plusMonths(it.toLong()) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                months.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(colGap)) {
                        pair.forEach { ym ->
                            MonthGrid(
                                ym = ym,
                                showYear = startMonth != 1,
                                cell = cell,
                                today = today,
                                selected = selectedDay?.let { LocalDate.parse(it) },
                                colorOf = { d ->
                                    val l = levelOf(d)
                                    if (l < 0) empty else base.copy(alpha = LEVEL_ALPHA[l])
                                },
                                strong = { levelOf(it) >= 2 },
                                onClick = { d ->
                                    selectedDay = d.toString()
                                    // Nur eine Tour an dem Tag => direkt die Details oeffnen
                                    byDay[d]?.singleOrNull()?.let { selectedId = it.id }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Aktivitaeten des gewaehlten Tages
        val day = selectedDay?.let { LocalDate.parse(it) }
        if (day != null) {
            HorizontalDivider()
            Text(
                "${day.dayOfMonth}.${day.monthValue}.${day.year}",
                style = MaterialTheme.typography.titleMedium
            )
            val list = byDay[day].orEmpty().sortedBy { it.startTime }
            if (list.isEmpty()) {
                Text("Keine Aktivitäten", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            list.forEach { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selectedId = a.id }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(
                            typeLabel(a.typeKey),
                            a.locationName?.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "${fmtKm.format(a.distanceM / 1000.0)} km",
                        modifier = Modifier.width(72.dp),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${fmtInt.format(a.elevationGain)} m",
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    ym: YearMonth,
    showYear: Boolean,
    cell: Dp,
    today: LocalDate,
    selected: LocalDate?,
    colorOf: (LocalDate) -> Color,
    strong: (LocalDate) -> Boolean,
    onClick: (LocalDate) -> Unit
) {
    val name = ym.month.getDisplayName(TextStyle.FULL_STANDALONE, LOCALE)
    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
        Text(
            if (showYear) "$name ${ym.year}" else name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            WEEKDAYS.forEach {
                Text(
                    it,
                    modifier = Modifier.width(cell),
                    textAlign = TextAlign.Center,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val offset = ym.atDay(1).dayOfWeek.value - 1 // Montag = 0
        val cells = offset + ym.lengthOfMonth()
        (0 until (cells + 6) / 7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                (0 until 7).forEach { col ->
                    val dayNo = week * 7 + col - offset + 1
                    if (dayNo < 1 || dayNo > ym.lengthOfMonth()) {
                        Spacer(Modifier.size(cell))
                    } else {
                        val d = ym.atDay(dayNo)
                        val border = when (d) {
                            selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            today -> BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)
                            else -> null
                        }
                        Box(
                            Modifier
                                .size(cell)
                                .clip(RoundedCornerShape(3.dp))
                                .background(colorOf(d))
                                .then(
                                    if (border != null) {
                                        Modifier.border(border, RoundedCornerShape(3.dp))
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable(enabled = !d.isAfter(today)) { onClick(d) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                dayNo.toString(),
                                fontSize = 9.sp,
                                color = if (strong(d)) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatLegend(base: Color, empty: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "weniger",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        (listOf(empty) + LEVEL_ALPHA.map { base.copy(alpha = it) }).forEach { c ->
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "mehr",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
