package de.jfricker.activityviewer.ui

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.HeatTrack
import de.jfricker.activityviewer.domain.resample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.heatmapDensity
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.rgba
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiPoint

private const val SRC_HEAT_POINTS = "heat-points"
private const val SRC_HEAT_LINES = "heat-lines"
private const val LAYER_HEAT = "heat-heatmap"
private const val LAYER_HEAT_LINES = "heat-lines"

/** Abstand der Heatmap-Punkte entlang der Tracks. */
private const val SAMPLE_STEP_M = 250.0

/** Uebergang Heatmap -> einzelne Linien (Zoomstufen). */
private const val FADE_START = 11f
private const val FADE_END = 13f

/** Ab dieser Zoomstufe zeigt ein Tipp auf eine Linie die Touren, die dort durchfuehren. */
private const val TAP_ZOOM = 11.5

/** Kamera fuer den naechsten Besuch des Tabs (nur solange die App laeuft). */
private var savedCamera: CameraPosition? = null

private class HeatData(val points: FeatureCollection, val lines: FeatureCollection, val all: List<GeoPoint>)

/**
 * Heatmap aller Tracks: weit draussen eine klassische Heatmap, beim Hineinzoomen
 * gehen sie in halbtransparente Linien ueber - oft gefahrene Wege werden dunkler.
 */
@Composable
fun HeatmapScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val tracks by vm.heatTracks.collectAsStateWithLifecycle()
    val all by vm.activities.collectAsStateWithLifecycle()
    val missing by vm.missingTrackIndex.collectAsStateWithLifecycle()
    val indexProgress by vm.indexProgress.collectAsStateWithLifecycle()

    var quick by rememberSaveable { mutableStateOf(QuickFilter.ALL) }
    var listIds by remember { mutableStateOf<List<Long>?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val byId = remember(all) { all.associateBy { it.id } }

    // Fehlende Tracks automatisch nachladen (nur im WLAN), wie bei "In der Naehe"
    LaunchedEffect(Unit) { vm.buildTrackIndex() }

    val density = LocalDensity.current
    val paddingPx = with(density) { 48.dp.roundToPx() }
    val tapPx = with(density) { 12.dp.toPx() }

    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var cameraInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.setStyle(Style.Builder().fromJson(STYLE_JSON)) { s ->
                s.showBaseMap(BaseMap.OPENTOPO)
                // Grundkarte entsaettigt, damit die Heatmap hervorsticht
                s.getLayer(BaseMap.OPENTOPO.layerId)?.setProperties(
                    PropertyFactory.rasterSaturation(-0.85f),
                    PropertyFactory.rasterOpacity(0.8f)
                )
                addHeatLayers(s)
                m.addOnMapClickListener { latLng ->
                    if (m.cameraPosition.zoom < TAP_ZOOM) {
                        listIds = null
                        return@addOnMapClickListener false
                    }
                    val p = m.projection.toScreenLocation(latLng)
                    val rect = RectF(p.x - tapPx, p.y - tapPx, p.x + tapPx, p.y + tapPx)
                    val ids = m.queryRenderedFeatures(rect, LAYER_HEAT_LINES)
                        .mapNotNull { f -> if (f.hasProperty("id")) f.getNumberProperty("id").toLong() else null }
                        .distinct()
                    listIds = ids.takeIf { it.isNotEmpty() }
                    true
                }
                savedCamera?.let {
                    m.moveCamera(CameraUpdateFactory.newCameraPosition(it))
                    cameraInitialized = true
                }
                map = m
                style = s
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { map?.let { savedCamera = it.cameraPosition } }
    }

    val visible = remember(tracks, quick) {
        val types = quick.types
        tracks.orEmpty().filter { types == null || it.typeKey in types }
    }

    // GeoJSON im Hintergrund aufbauen (einige zehntausend Punkte)
    val data by produceState<HeatData?>(null, visible) {
        value = withContext(Dispatchers.Default) { buildHeatData(visible) }
    }

    LaunchedEffect(style, data) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val d = data ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_HEAT_POINTS)?.setGeoJson(d.points)
        s.getSourceAs<GeoJsonSource>(SRC_HEAT_LINES)?.setGeoJson(d.lines)
        if (!cameraInitialized && d.all.isNotEmpty()) {
            mapView.awaitLayout()
            fit(m, coreArea(d.all), paddingPx, animate = false)
            cameraInitialized = true
        }
    }

    BackHandler(enabled = listIds != null) { listIds = null }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())

        Column(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(shape = RoundedCornerShape(50), shadowElevation = 2.dp) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuickFilter.entries.forEach {
                        FilterChip(
                            selected = quick == it,
                            onClick = { quick = it; listIds = null },
                            label = { Text(it.label) }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapButton("Alle zeigen") {
                    val d = data
                    val m = map
                    if (d != null && m != null) fit(m, coreArea(d.all), paddingPx, animate = true)
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    val small = MaterialTheme.typography.labelSmall
                    val progress = indexProgress
                    Text(
                        if (tracks == null) "Lade Tracks …"
                        else "${visible.size} Tracks · reinzoomen und Linie antippen = Touren dort",
                        style = small
                    )
                    if (progress != null) {
                        Text("Tracks werden geladen: ${progress.first} / ${progress.second}", style = small)
                    } else if (missing > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$missing Tracks fehlen noch (laden im WLAN)",
                                style = small,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = { vm.buildTrackIndex(force = true) }) {
                                Text("Jetzt laden", style = small)
                            }
                        }
                    }
                }
            }
        }

        val list = listIds
        if (list != null) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                val rows = list.mapNotNull { byId[it] }.sortedByDescending { it.startTime }
                TourListCard(
                    rows = rows,
                    onPick = { detailId = it },
                    onClose = { listIds = null },
                    title = if (rows.size == 1) "1 Tour führt hier durch" else "${rows.size} Touren führen hier durch"
                )
            }
        }

        val d = detailId
        if (d != null) {
            BackHandler { detailId = null }
            Surface(Modifier.fillMaxSize()) {
                ActivityDetailScreen(vm, d, onBack = { detailId = null })
            }
        }
    }
}

private fun buildHeatData(tracks: List<HeatTrack>): HeatData {
    val pointFeatures = ArrayList<Feature>(tracks.size)
    val lineFeatures = ArrayList<Feature>(tracks.size)
    val starts = ArrayList<GeoPoint>(tracks.size)
    tracks.forEach { t ->
        if (t.points.size < 2) return@forEach
        starts.add(t.points.first())
        val sampled = resample(t.points, SAMPLE_STEP_M)
        pointFeatures.add(Feature.fromGeometry(MultiPoint.fromLngLats(sampled.map { it.toPoint() })))
        lineFeatures.add(
            Feature.fromGeometry(LineString.fromLngLats(t.points.map { it.toPoint() })).apply {
                addNumberProperty("id", t.id)
            }
        )
    }
    return HeatData(
        FeatureCollection.fromFeatures(pointFeatures),
        FeatureCollection.fromFeatures(lineFeatures),
        starts
    )
}

private fun addHeatLayers(s: Style) {
    s.addSource(GeoJsonSource(SRC_HEAT_POINTS))
    s.addSource(GeoJsonSource(SRC_HEAT_LINES))

    s.addLayer(
        HeatmapLayer(LAYER_HEAT, SRC_HEAT_POINTS).withProperties(
            PropertyFactory.heatmapRadius(
                interpolate(linear(), zoom(), stop(0, 2f), stop(6, 4f), stop(9, 7f), stop(13, 14f))
            ),
            PropertyFactory.heatmapIntensity(
                interpolate(linear(), zoom(), stop(0, 0.3f), stop(9, 0.8f), stop(13, 1.5f))
            ),
            PropertyFactory.heatmapOpacity(
                interpolate(linear(), zoom(), stop(FADE_START, 0.9f), stop(FADE_END, 0f))
            ),
            PropertyFactory.heatmapColor(
                interpolate(
                    linear(), heatmapDensity(),
                    stop(0, rgba(0, 0, 0, 0)),
                    stop(0.1, rgb(65, 105, 225)),
                    stop(0.3, rgb(0, 190, 220)),
                    stop(0.5, rgb(120, 220, 80)),
                    stop(0.7, rgb(255, 215, 0)),
                    stop(0.9, rgb(255, 120, 0)),
                    stop(1, rgb(220, 20, 20))
                )
            )
        )
    )

    // Halbtransparente Linien: wo viele Touren uebereinander liegen, wird es kraeftiger
    s.addLayer(
        LineLayer(LAYER_HEAT_LINES, SRC_HEAT_LINES).withProperties(
            PropertyFactory.lineColor("#D84315"),
            PropertyFactory.lineOpacity(
                interpolate(linear(), zoom(), stop(FADE_START, 0f), stop(FADE_END, 0.35f))
            ),
            PropertyFactory.lineWidth(
                interpolate(linear(), zoom(), stop(FADE_START, 1.5f), stop(16, 4f))
            ),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
}
