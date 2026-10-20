package de.jfricker.activityviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.activityviewer.data.local.ActivityEntity
import de.jfricker.activityviewer.data.local.ActivityRow
import de.jfricker.activityviewer.domain.CYCLING_TYPES
import de.jfricker.activityviewer.domain.HIKING_TYPES
import de.jfricker.activityviewer.domain.SKI_TYPES
import de.jfricker.activityviewer.domain.showsSpeed
import de.jfricker.activityviewer.domain.trustedMaxSpeed
import de.jfricker.activityviewer.domain.typeLabel
import java.text.Collator
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/** Schnellwahl oben; bei Radfahren und Wandern gibt es zusaetzlich die Unterwahl nach typeKey. */
enum class QuickFilter(val label: String, val types: List<String>?, val hasSubFilter: Boolean) {
    ALL("Alle", null, false),
    CYCLING("Radfahren", CYCLING_TYPES, true),
    HIKING("Wandern", HIKING_TYPES, true),
    SKI("Skitouren", SKI_TYPES, false)
}

enum class SortColumn(val label: String, val defaultAscending: Boolean) {
    DATE("Datum", false),
    NAME("Name", true),
    KM("km", false),
    ELEVATION("Hm", false),
    DURATION("Zeit", false)
}

private val DATE_W = 66.dp
private val NUM_W = 52.dp
private val TIME_W = 46.dp

internal val LOCALE = Locale.forLanguageTag("de-CH")

internal fun km1() = NumberFormat.getNumberInstance(LOCALE).apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}

// ---------------------------------------------------------------- Tabelle

@Composable
fun ActivitiesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val all by vm.activities.collectAsStateWithLifecycle()

    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var quick by rememberSaveable { mutableStateOf(QuickFilter.ALL) }
    var subType by rememberSaveable { mutableStateOf<String?>(null) }
    var sortBy by rememberSaveable { mutableStateOf(SortColumn.DATE) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Bei geaenderter Auswahl/Sortierung wieder nach oben (nicht beim Zurueckkehren aus dem Detail).
    LaunchedEffect(quick, subType, sortBy, ascending) { listState.scrollToItem(0) }

    val visible = remember(all, quick, subType, sortBy, ascending) {
        val types = quick.types
        val sub = subType
        val filtered = all.filter { a ->
            when {
                sub != null -> a.typeKey == sub
                types != null -> a.typeKey in types
                else -> true
            }
        }
        val collator = Collator.getInstance(Locale.GERMAN)
        val cmp: Comparator<ActivityRow> = when (sortBy) {
            SortColumn.DATE -> compareBy<ActivityRow> { it.startTime }
            SortColumn.NAME -> compareBy<ActivityRow, String>(collator) { it.name }
            SortColumn.KM -> compareBy<ActivityRow> { it.distanceM }
            SortColumn.ELEVATION -> compareBy<ActivityRow> { it.elevationGain }
            SortColumn.DURATION -> compareBy<ActivityRow> { it.durationS }
        }
        filtered.sortedWith(if (ascending) cmp else cmp.reversed())
    }

    val id = selectedId
    if (id != null) {
        BackHandler { selectedId = null }
        ActivityDetailScreen(vm, id, onBack = { selectedId = null }, modifier)
        return
    }

    val fmtKm = remember { km1() }
    val fmtInt = remember { NumberFormat.getIntegerInstance(LOCALE) }
    // Typ unter dem Namen nur zeigen, wenn mehrere Typen in der Liste sein koennen
    val showType = subType == null && quick != QuickFilter.SKI

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickFilter.entries.forEach {
                FilterChip(
                    selected = quick == it,
                    onClick = { quick = it; subType = null },
                    label = { Text(it.label) }
                )
            }
        }

        val types = quick.types
        if (quick.hasSubFilter && types != null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = subType == null,
                    onClick = { subType = null },
                    label = { Text("Alle") }
                )
                types.forEach { t ->
                    FilterChip(
                        selected = subType == t,
                        onClick = { subType = t },
                        label = { Text(typeLabel(t)) }
                    )
                }
            }
        }

        Text(
            "${visible.size} Aktivitäten · " +
                "${fmtKm.format(visible.sumOf { it.distanceM } / 1000.0)} km · " +
                "${fmtInt.format(visible.sumOf { it.elevationGain })} Hm",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        HeaderRow(sortBy, ascending) { col ->
            if (col == sortBy) {
                ascending = !ascending
            } else {
                sortBy = col
                ascending = col.defaultAscending
            }
        }
        HorizontalDivider()

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (all.isEmpty()) "Noch keine Daten" else "Keine Aktivitäten für diese Auswahl")
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(visible, key = { it.id }) { a ->
                    ActivityTableRow(a, showType, fmtKm, fmtInt) { selectedId = a.id }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(sortBy: SortColumn, ascending: Boolean, onSort: (SortColumn) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SortColumn.entries.forEach { col ->
            val active = col == sortBy
            val arrow = if (!active) "" else if (ascending) " ▲" else " ▼"
            val cellModifier = when (col) {
                SortColumn.DATE -> Modifier.width(DATE_W)
                SortColumn.NAME -> Modifier.weight(1f)
                SortColumn.DURATION -> Modifier.width(TIME_W)
                else -> Modifier.width(NUM_W)
            }
            Text(
                col.label + arrow,
                modifier = cellModifier
                    .clickable { onSort(col) }
                    .padding(vertical = 10.dp, horizontal = 2.dp),
                textAlign = if (col == SortColumn.DATE || col == SortColumn.NAME) TextAlign.Start else TextAlign.End,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActivityTableRow(
    a: ActivityRow,
    showType: Boolean,
    fmtKm: NumberFormat,
    fmtInt: NumberFormat,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cell(shortDate(a.startTime), DATE_W, TextAlign.Start)
        Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
            Text(
                a.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = listOfNotNull(
                if (showType) typeLabel(a.typeKey) else null,
                a.locationName?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Cell(fmtKm.format(a.distanceM / 1000.0), NUM_W, TextAlign.End)
        Cell(fmtInt.format(a.elevationGain), NUM_W, TextAlign.End)
        Cell(formatDuration(a.durationS), TIME_W, TextAlign.End)
    }
}

@Composable
private fun Cell(text: String, width: Dp, align: TextAlign) {
    Text(
        text,
        modifier = Modifier.width(width).padding(horizontal = 2.dp),
        textAlign = align,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1
    )
}

// ---------------------------------------------------------------- Detail

@Composable
internal fun ActivityDetailScreen(
    vm: MainViewModel,
    id: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val flow = remember(id) { vm.activity(id) }
    val activity by flow.collectAsStateWithLifecycle(initialValue = null)
    val fmtKm = remember { km1() }
    val fmtInt = remember { NumberFormat.getIntegerInstance(LOCALE) }
    var mapExpanded by rememberSaveable { mutableStateOf(false) }
    var showPoster by rememberSaveable { mutableStateOf(false) }

    val current = activity
    if (showPoster && current != null) {
        BackHandler { showPoster = false }
        PosterScreen(vm, current, onBack = { showPoster = false }, modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            Text("← Zurück zur Liste")
        }

        val mapModifier = if (mapExpanded) {
            Modifier.fillMaxWidth().weight(1f)
        } else {
            Modifier.fillMaxWidth().height(320.dp)
        }
        TrackSection(vm, id, mapExpanded, { mapExpanded = !mapExpanded }, mapModifier)

        if (!mapExpanded) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val a = activity
                if (a == null) Text("Lade …") else ActivityDetails(a, fmtKm, fmtInt, onPoster = { showPoster = true })
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TrackSection(
    vm: MainViewModel,
    id: Long,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    mapModifier: Modifier
) {
    var retry by remember { mutableIntStateOf(0) }
    val trackFlow = remember(id, retry) { vm.track(id) }
    val state by trackFlow.collectAsStateWithLifecycle(initialValue = TrackState.Loading)

    val me = rememberMyLocation(vm)

    when (val s = state) {
        is TrackState.Loading -> Box(mapModifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Lade Track …", style = MaterialTheme.typography.bodySmall)
            }
        }
        is TrackState.Loaded -> TrackMap(
            track = s.points,
            me = me,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            modifier = mapModifier
        )
        is TrackState.NoTrack -> Text(
            "Für diese Aktivität gibt es keinen GPS-Track.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        is TrackState.Error -> Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Track konnte nicht geladen werden: ${s.message}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { retry++ }) { Text("Erneut") }
        }
    }
}

@Composable
private fun ActivityDetails(
    a: ActivityEntity,
    fmtKm: NumberFormat,
    fmtInt: NumberFormat,
    onPoster: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(a.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            "${typeLabel(a.typeKey)} · ${longDate(a.startTime)} ${timeOfDay(a.startTime)}".trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        a.locationName?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Distanz", "${fmtKm.format(a.distanceM / 1000.0)} km", Modifier.weight(1f))
            StatTile("Aufstieg", "${fmtInt.format(a.elevationGain)} m", Modifier.weight(1f))
            StatTile("Dauer", formatDuration(a.durationS), Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        Text("Details", style = MaterialTheme.typography.titleMedium)

        DetailLine("Dauer", formatDuration(a.durationS, withSeconds = true))
        if (showsSpeed(a.typeKey)) {
            a.avgSpeed?.takeIf { it > 0 }?.let { mps ->
                DetailLine("Ø Geschwindigkeit", "${fmtKm.format(mps * 3.6)} km/h")
                if (a.typeKey == "running") {
                    DetailLine("Ø Pace", "${formatPace(1000.0 / mps)} /km")
                }
            }
            trustedMaxSpeed(a.typeKey, a.maxSpeed, a.deviceId)?.let { mps ->
                DetailLine("Max. Geschwindigkeit", "${fmtKm.format(mps * 3.6)} km/h")
            }
        }
        a.minElev?.let { DetailLine("Tiefster Punkt", "${fmtInt.format(it)} m") }
        a.maxElev?.let { DetailLine("Höchster Punkt", "${fmtInt.format(it)} m") }
        a.avgHr?.let { DetailLine("Ø Puls", "${fmtInt.format(it)} bpm") }
        a.calories?.let { DetailLine("Kalorien", "${fmtInt.format(it)} kcal") }
        DetailLine("Aktivitäts-ID", a.id.toString())

        // Bestzeiten und geschaetzte Leistung am Berg (nur Rad, sobald berechnet)
        if (a.typeKey in CYCLING_TYPES) {
            val efforts = listOf(
                5 to a.best5kS, 10 to a.best10kS, 20 to a.best20kS, 40 to a.best40kS
            ).mapNotNull { (km, s) -> s?.takeIf { it > 0 }?.let { km to it } }
            val powers = listOf(
                5 to a.bestP5mW, 10 to a.bestP10mW, 20 to a.bestP20mW
            ).mapNotNull { (min, w) -> w?.takeIf { it > 0 }?.let { min to it } }
            if (efforts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Bestzeiten", style = MaterialTheme.typography.titleMedium)
                efforts.forEach { (km, s) ->
                    DetailLine(
                        "Schnellste $km km",
                        "${formatDuration(s, withSeconds = true)} · ${fmtKm.format(km * 3600.0 / s)} km/h"
                    )
                }
            }
            if (powers.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Leistung am Berg (geschätzt)", style = MaterialTheme.typography.titleMedium)
                powers.forEach { (min, w) -> DetailLine("Beste $min min", "${fmtInt.format(w)} W") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = onPoster) {
            Text("Als Bild teilen (Status)")
        }
        OutlinedButton(onClick = {
            uriHandler.openUri("https://connect.garmin.com/modern/activity/${a.id}")
        }) {
            Text("In Garmin Connect öffnen")
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            maxLines = 1)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, textAlign = TextAlign.End)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ---------------------------------------------------------------- Formatierung
// startTime kommt von Garmin als "yyyy-MM-dd HH:mm:ss" (lokale Zeit)

internal fun shortDate(s: String): String =
    if (s.length >= 10) "${s.substring(8, 10)}.${s.substring(5, 7)}.${s.substring(2, 4)}" else s

internal fun longDate(s: String): String =
    if (s.length >= 10) "${s.substring(8, 10)}.${s.substring(5, 7)}.${s.substring(0, 4)}" else s

private fun timeOfDay(s: String): String =
    if (s.length >= 16) s.substring(11, 16) else ""

internal fun formatDuration(seconds: Double, withSeconds: Boolean = false): String {
    val t = seconds.roundToLong()
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (withSeconds) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(h, m)
}

private fun formatPace(secondsPerKm: Double): String {
    val t = secondsPerKm.roundToLong()
    return "%d:%02d".format(t / 60, t % 60)
}
