package de.jfricker.activityviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.activityviewer.data.PHOTO_PERMISSIONS
import de.jfricker.activityviewer.data.PhotoAccess
import de.jfricker.activityviewer.data.local.CountryStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val COUNTRY_STYLE = """
{
  "version": 8,
  "sources": {},
  "layers": [
    { "id": "water", "type": "background", "paint": { "background-color": "#CFE3EE" } }
  ]
}
"""

private val DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private fun day(ms: Long) = DAY.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Weltkarte: alle Laender, in denen Fotos (mit GPS) aufgenommen wurden, sind eingefaerbt. */
@Composable
fun CountriesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var access by remember { mutableStateOf(vm.photoAccess()) }
    var hasLocation by remember { mutableStateOf(vm.hasPhotoLocationAccess()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        access = vm.photoAccess()
        hasLocation = vm.hasPhotoLocationAccess()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = vm.photoAccess()
        hasLocation = vm.hasPhotoLocationAccess()
    }
    LaunchedEffect(access, hasLocation) {
        if (access != PhotoAccess.NONE && hasLocation) vm.scanPhotoCountries()
    }

    val stats by vm.countryStats.collectAsStateWithLifecycle()
    val gpsCount by vm.photoGpsCount.collectAsStateWithLifecycle()
    val scan by vm.countryScan.collectAsStateWithLifecycle()
    val names by produceState(emptyMap<String, String>()) { value = vm.countryNames() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var alphabetical by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        if (access == PhotoAccess.NONE || !hasLocation) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Für die Länderkarte liest die App die GPS-Positionen aus deinen Fotos. " +
                        "Dazu braucht sie Zugriff auf die Fotos inklusive Aufnahmeort.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = { launcher.launch(PHOTO_PERMISSIONS) }) { Text("Erlauben") }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${stats.size} Länder", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$gpsCount Fotos mit GPS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showList = !showList }) { Text(if (showList) "Karte" else "Liste") }
        }
        (scan as? CountryScan.Running)?.let { s ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    if (s.total == 0) "Suche Fotos …" else "Fotos werden gelesen: ${s.done} / ${s.total}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (s.total > 0) {
                    LinearProgressIndicator(
                        progress = { s.done.toFloat() / s.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        }
        (scan as? CountryScan.Error)?.let {
            Text(
                "Fehler beim Lesen der Fotos: ${it.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        if (showList) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !alphabetical,
                    onClick = { alphabetical = false },
                    label = { Text("Nach Fotos") }
                )
                FilterChip(
                    selected = alphabetical,
                    onClick = { alphabetical = true },
                    label = { Text("A–Z") }
                )
            }
            val sorted = remember(stats, names, alphabetical) {
                if (alphabetical) {
                    val collator = Collator.getInstance(Locale.GERMAN)
                    stats.sortedWith(compareBy(collator) { names[it.country] ?: it.country })
                } else {
                    // stats kommt bereits nach Anzahl Fotos sortiert (manuell eingetragene am Ende)
                    stats
                }
            }
            CountryList(sorted, names) { id -> selected = id; showList = false }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CountryMap(vm, stats.map { it.country }.toSet(), selected) { selected = it }
                selected?.let { id ->
                    CountryCard(
                        name = names[id] ?: id,
                        stat = stats.firstOrNull { it.country == id },
                        onClose = { selected = null },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CountryList(stats: List<CountryStat>, names: Map<String, String>, onClick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(stats, key = { it.country }) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(s.country) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(names[s.country] ?: s.country, fontWeight = FontWeight.Medium)
                    Text(
                        if (s.cnt == 0) "manuell eingetragen" else "${day(s.firstMs)} – ${day(s.lastMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (s.cnt > 0) Text("${s.cnt} Fotos", style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun CountryCard(name: String, stat: CountryStat?, onClose: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (stat == null) {
                        "Noch nicht besucht"
                    } else if (stat.cnt == 0) {
                        "Besucht (manuell eingetragen, keine Fotos mit GPS)"
                    } else {
                        "${stat.cnt} Fotos · erstes ${day(stat.firstMs)} · letztes ${day(stat.lastMs)}"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onClose) { Text("✕") }
        }
    }
}

private const val SRC_ALL = "countries"
private const val SRC_VISITED = "visited"
private const val SRC_SELECTED = "selected"

@Composable
private fun CountryMap(
    vm: MainViewModel,
    visited: Set<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val features by produceState<List<Feature>?>(null) {
        value = withContext(Dispatchers.Default) {
            FeatureCollection.fromJson(vm.countriesGeoJson()).features().orEmpty()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.cameraPosition = vm.countryCamera
                ?: CameraPosition.Builder().target(LatLng(35.0, 10.0)).zoom(0.8).build()
            m.addOnCameraIdleListener { vm.countryCamera = m.cameraPosition }
            m.setStyle(Style.Builder().fromJson(COUNTRY_STYLE)) { s ->
                listOf(SRC_ALL, SRC_VISITED, SRC_SELECTED).forEach { s.addSource(GeoJsonSource(it)) }
                s.addLayer(FillLayer("land", SRC_ALL).withProperties(PropertyFactory.fillColor("#F4F1EA")))
                s.addLayer(
                    FillLayer("visited-fill", SRC_VISITED).withProperties(
                        PropertyFactory.fillColor("#E53935"),
                        PropertyFactory.fillOpacity(0.7f)
                    )
                )
                s.addLayer(
                    LineLayer("borders", SRC_ALL).withProperties(
                        PropertyFactory.lineColor("#A8A196"),
                        PropertyFactory.lineWidth(0.6f)
                    )
                )
                s.addLayer(
                    LineLayer("selected-line", SRC_SELECTED).withProperties(
                        PropertyFactory.lineColor("#212121"),
                        PropertyFactory.lineWidth(2f)
                    )
                )
                m.addOnMapClickListener { ll ->
                    val f = m.queryRenderedFeatures(m.projection.toScreenLocation(ll), "land").firstOrNull()
                    currentOnSelect(f?.getStringProperty("id"))
                    true
                }
                map = m
                style = s
            }
        }
    }

    LaunchedEffect(style, features) {
        val s = style ?: return@LaunchedEffect
        val all = features ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_ALL)?.setGeoJson(FeatureCollection.fromFeatures(all))
    }

    LaunchedEffect(style, features, visited) {
        val s = style ?: return@LaunchedEffect
        val all = features ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_VISITED)?.setGeoJson(
            FeatureCollection.fromFeatures(all.filter { it.getStringProperty("id") in visited })
        )
    }

    LaunchedEffect(style, features, selected) {
        val s = style ?: return@LaunchedEffect
        val all = features ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_SELECTED)?.setGeoJson(
            FeatureCollection.fromFeatures(all.filter { it.getStringProperty("id") == selected })
        )
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}
