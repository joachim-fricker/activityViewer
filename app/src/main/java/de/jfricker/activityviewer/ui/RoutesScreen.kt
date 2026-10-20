package de.jfricker.activityviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.activityviewer.data.local.ActivityRow
import de.jfricker.activityviewer.domain.RouteGroup
import de.jfricker.activityviewer.domain.typeLabel
import java.text.NumberFormat

/** Eine erkannte Strecke mit ihren Touren (neueste zuerst). */
private class RouteView(val group: RouteGroup, val rows: List<ActivityRow>) {
    val key: Long get() = group.representative.id
    val count: Int get() = rows.size
    val latest: ActivityRow get() = rows.first()
    /** Schnellste Tour (nur Touren mit Zeit). */
    val best: ActivityRow? get() = rows.filter { it.durationS > 0 }.minByOrNull { it.durationS }

    /** Haeufigster Name, sonst der der neuesten Tour. */
    val title: String
        get() = rows.groupingBy { it.name }.eachCount().maxByOrNull { it.value }
            ?.takeIf { it.value > 1 }?.key ?: latest.name
}

/** Wiederholte Touren: Strecken, die mindestens zweimal gemacht wurden. */
@Composable
fun RoutesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val groups by vm.repeatedRoutes.collectAsStateWithLifecycle()
    val all by vm.activities.collectAsStateWithLifecycle()
    val missing by vm.missingTrackIndex.collectAsStateWithLifecycle()
    val indexProgress by vm.indexProgress.collectAsStateWithLifecycle()

    var quick by rememberSaveable { mutableStateOf(QuickFilter.ALL) }
    var selectedKey by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { vm.buildTrackIndex() }

    val byId = remember(all) { all.associateBy { it.id } }
    val routes = remember(groups, byId, quick) {
        val types = quick.types
        groups.orEmpty()
            .filter { types == null || it.representative.typeKey in types }
            .map { g -> RouteView(g, g.ids.mapNotNull { byId[it] }.sortedByDescending { it.startTime }) }
            .filter { it.count >= 2 }
            .sortedWith(compareByDescending<RouteView> { it.count }.thenByDescending { it.latest.startTime })
    }

    val id = detailId
    if (id != null) {
        BackHandler { detailId = null }
        ActivityDetailScreen(vm, id, onBack = { detailId = null }, modifier)
        return
    }

    val selected = routes.firstOrNull { it.key == selectedKey }
    if (selected != null) {
        BackHandler { selectedKey = null }
        RouteDetail(selected, onBack = { selectedKey = null }, onOpen = { detailId = it }, modifier = modifier)
        return
    }

    val fmtKm = remember { km1() }
    val fmtInt = remember { NumberFormat.getIntegerInstance(LOCALE) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickFilter.entries.forEach {
                FilterChip(
                    selected = quick == it,
                    onClick = { quick = it },
                    label = { Text(it.label) }
                )
            }
        }
        val small = MaterialTheme.typography.bodySmall
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (groups != null) {
                Text(
                    "${routes.size} Strecken mehrfach gemacht · ${routes.sumOf { it.count }} Touren",
                    style = small,
                    color = muted
                )
            }
            val progress = indexProgress
            if (progress != null) {
                Text("Tracks werden geladen: ${progress.first} / ${progress.second}", style = small, color = muted)
            } else if (missing > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Für $missing Touren fehlt der Track noch (lädt im WLAN).",
                        style = small,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.buildTrackIndex(force = true) }) { Text("Jetzt laden") }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))

        if (groups == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Vergleiche Strecken …", style = small)
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(routes, key = { it.key }) { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selectedKey = r.key }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TypeDot(r.latest.typeKey)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${fmtKm.format(r.latest.distanceM / 1000.0)} km · " +
                                "${fmtInt.format(r.latest.elevationGain)} Hm · zuletzt ${shortDate(r.latest.startTime)}",
                            style = small,
                            color = muted
                        )
                        r.best?.let {
                            Text(
                                "Bestzeit ${formatDuration(it.durationS)} h (${shortDate(it.startTime)})",
                                style = small,
                                color = muted
                            )
                        }
                    }
                    Text(
                        "${r.count}×",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private val DATE_W = 70.dp
private val NUM_W = 54.dp

@Composable
private fun RouteDetail(
    r: RouteView,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val fmtKm = remember { km1() }
    val fmtInt = remember { NumberFormat.getIntegerInstance(LOCALE) }
    var mapExpanded by rememberSaveable { mutableStateOf(false) }
    val best = r.best

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text(
                r.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 16.dp)
            )
        }
        TrackMap(
            track = r.group.representative.points,
            me = null,
            expanded = mapExpanded,
            onToggleExpanded = { mapExpanded = !mapExpanded },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mapExpanded) 420.dp else 220.dp)
        )
        Text(
            "${r.count}× gemacht · ${typeLabel(r.latest.typeKey)} · " +
                "Ø ${formatDuration(r.rows.map { it.durationS }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0)} h",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            HeaderCell("Datum", DATE_W, TextAlign.Start)
            HeaderCell("Zeit", NUM_W, TextAlign.End)
            HeaderCell("+/-", NUM_W, TextAlign.End)
            HeaderCell("km/h", NUM_W, TextAlign.End)
            HeaderCell("Hm", NUM_W, TextAlign.End)
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(r.rows, key = { it.id }) { a ->
                val isBest = best != null && a.id == best.id
                val weight = if (isBest) FontWeight.Bold else null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(a.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BodyCell(shortDate(a.startTime) + if (isBest) " ★" else "", DATE_W, TextAlign.Start, weight)
                    BodyCell(formatDuration(a.durationS), NUM_W, TextAlign.End, weight)
                    val delta = if (best != null && a.durationS > 0 && !isBest) {
                        "+" + formatDuration(a.durationS - best.durationS)
                    } else {
                        ""
                    }
                    BodyCell(delta, NUM_W, TextAlign.End, null)
                    val kmh = if (a.durationS > 0) fmtKm.format(a.distanceM / a.durationS * 3.6) else "–"
                    BodyCell(kmh, NUM_W, TextAlign.End, weight)
                    BodyCell(fmtInt.format(a.elevationGain), NUM_W, TextAlign.End, null)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, align: TextAlign) {
    Text(
        text,
        modifier = Modifier.width(width),
        textAlign = align,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun BodyCell(text: String, width: Dp, align: TextAlign, weight: FontWeight?) {
    Text(
        text,
        modifier = Modifier.width(width),
        textAlign = align,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = weight,
        maxLines = 1
    )
}
