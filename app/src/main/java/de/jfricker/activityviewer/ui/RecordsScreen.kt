package de.jfricker.activityviewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.activityviewer.data.local.RecordRow
import de.jfricker.activityviewer.domain.CYCLING_TYPES
import de.jfricker.activityviewer.domain.SYSTEM_MASS_KG
import de.jfricker.activityviewer.domain.isIndoor
import de.jfricker.activityviewer.domain.showsSpeed
import de.jfricker.activityviewer.domain.trustedMaxSpeed
import de.jfricker.activityviewer.domain.typeLabel
import java.text.NumberFormat
import kotlin.math.roundToLong

private const val TOP_N = 3

/** Ein Rekord: sortierbarer Wert je Tour (null = zaehlt nicht) und Anzeige des Werts. */
private class Record(
    val title: String,
    val ascending: Boolean = false,
    val cyclingOnly: Boolean = false,
    val value: (RecordRow) -> Double?,
    val format: (RecordRow, Double) -> String
)

@Composable
fun RecordsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val rows by vm.records.collectAsStateWithLifecycle()
    val missing by vm.missingBestEfforts.collectAsStateWithLifecycle()
    val progress by vm.effortProgress.collectAsStateWithLifecycle()

    var quick by rememberSaveable { mutableStateOf(QuickFilter.ALL) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openRecord by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.buildBestEfforts() }

    val id = detailId
    if (id != null) {
        BackHandler { detailId = null }
        ActivityDetailScreen(vm, id, onBack = { detailId = null }, modifier)
        return
    }

    val fmtKm = remember { km1() }
    val fmtInt = remember { NumberFormat.getIntegerInstance(LOCALE) }

    val records = remember {
        fun effort(pick: (RecordRow) -> Double?, km: Double) = Record(
            title = "Schnellste ${km.toInt()} km",
            ascending = true,
            cyclingOnly = true,
            value = { r -> pick(r)?.takeIf { it > 0 } },
            format = { _, s -> "${formatClock(s)} · ${fmtKm.format(km * 3600.0 / s)} km/h" }
        )
        fun power(pick: (RecordRow) -> Double?, minutes: Int) = Record(
            title = "Max. Leistung am Berg $minutes min (geschätzt)",
            cyclingOnly = true,
            value = { r -> pick(r)?.takeIf { it > 0 } },
            format = { _, w -> "${fmtInt.format(w)} W" }
        )
        listOf(
            Record("Längste Tour", value = { it.distanceM.takeIf { d -> d > 0 } }) { _, v ->
                "${fmtKm.format(v / 1000.0)} km"
            },
            Record("Meiste Höhenmeter", value = { it.elevationGain.takeIf { e -> e > 0 } }) { _, v ->
                "${fmtInt.format(v)} Hm"
            },
            Record("Höchster Punkt", value = { it.maxElev }) { _, v -> "${fmtInt.format(v)} m ü. M." },
            Record(
                "Max. Geschwindigkeit",
                value = { r -> if (showsSpeed(r.typeKey)) trustedMaxSpeed(r.typeKey, r.maxSpeed, r.deviceId) else null }
            ) { _, v ->
                "${fmtKm.format(v * 3.6)} km/h"
            },
            Record("Längste Dauer", value = { it.durationS.takeIf { d -> d > 0 } }) { _, v ->
                "${formatDuration(v)} h"
            },
            effort({ it.best5kS }, 5.0),
            effort({ it.best10kS }, 10.0),
            effort({ it.best20kS }, 20.0),
            effort({ it.best40kS }, 40.0),
            power({ it.bestP5mW }, 5),
            power({ it.bestP10mW }, 10),
            power({ it.bestP20mW }, 20)
        )
    }

    // Indoor-/virtuelle Fahrten zaehlen fuer Rekorde nicht
    val visible = remember(rows, quick) {
        val types = quick.types
        rows.filter { (types == null || it.typeKey in types) && !isIndoor(it.typeKey, it.name) }
    }
    val showCycling = quick == QuickFilter.ALL || quick == QuickFilter.CYCLING

    /** Vollstaendige Rangliste eines Rekords (bester zuerst). */
    fun ranking(rec: Record): List<Pair<RecordRow, Double>> {
        val pool = if (rec.cyclingOnly) visible.filter { it.typeKey in CYCLING_TYPES } else visible
        val list = pool.mapNotNull { r -> rec.value(r)?.let { r to it } }
        return if (rec.ascending) list.sortedBy { it.second } else list.sortedByDescending { it.second }
    }

    // Ganze Rangliste eines Rekords
    val open = records.firstOrNull { it.title == openRecord }
    if (open != null) {
        BackHandler { openRecord = null }
        RankingList(open, ranking(open), onBack = { openRecord = null }, onOpen = { detailId = it }, modifier = modifier)
        return
    }

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
                    selected = quick == it,
                    onClick = { quick = it },
                    label = { Text(it.label) }
                )
            }
        }

        records.filter { !it.cyclingOnly || showCycling }.forEach { rec ->
            val all = ranking(rec)
            RecordCard(
                rec,
                all.take(TOP_N),
                total = all.size,
                onOpen = { detailId = it },
                onShowAll = { openRecord = rec.title }
            )
        }

        if (showCycling) {
            val small = MaterialTheme.typography.bodySmall
            val p = progress
            when {
                p != null -> Text("Bestzeiten werden berechnet: ${p.first} / ${p.second}", style = small)
                missing > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Für $missing Radtouren fehlen die Bestzeiten noch (werden im WLAN berechnet).",
                        style = small,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.buildBestEfforts(force = true) }) { Text("Jetzt") }
                }
            }
            Text(
                "Bestzeiten: schnellstes Teilstück innerhalb einer Tour, aus den GPX-Punkten berechnet " +
                    "(Pausen zählen mit). Leistung am Berg: geschätzt aus Höhengewinn, Tempo und " +
                    "${SYSTEM_MASS_KG.toInt()} kg (Fahrer + Rad), nur Anstiege mit mind. 3 % Steigung, kein Powermeter.",
                style = small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecordCard(
    rec: Record,
    top: List<Pair<RecordRow, Double>>,
    total: Int,
    onOpen: (Long) -> Unit,
    onShowAll: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                rec.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (top.isEmpty()) {
                Text(
                    "Noch keine Daten",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            top.forEachIndexed { i, (r, v) -> RankRow(i + 1, rec, r, v, onOpen) }
            if (total > top.size) {
                TextButton(onClick = onShowAll, modifier = Modifier.padding(start = 4.dp)) {
                    Text("Alle $total anzeigen ›")
                }
            }
        }
    }
}

@Composable
private fun RankRow(rank: Int, rec: Record, r: RecordRow, v: Double, onOpen: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(r.id) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$rank.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(36.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                rec.format(r, v),
                style = if (rank == 1) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.bodyMedium,
                fontWeight = if (rank == 1) FontWeight.Bold else null
            )
            Text(
                "${shortDate(r.startTime)} · ${typeLabel(r.typeKey)} · ${r.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(4.dp))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Ganze Rangliste eines Rekords. */
@Composable
private fun RankingList(
    rec: Record,
    all: List<Pair<RecordRow, Double>>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text(
                rec.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 16.dp)
            )
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(all, key = { _, it -> it.first.id }) { i, (r, v) ->
                RankRow(i + 1, rec, r, v, onOpen)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 7:45 bzw. 1:02:03 */
private fun formatClock(seconds: Double): String {
    val t = seconds.roundToLong()
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d min".format(m, s)
}
