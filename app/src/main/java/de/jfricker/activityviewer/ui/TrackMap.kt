package de.jfricker.activityviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.distanceToTrackM
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.NumberFormat
import java.util.Locale
import kotlin.coroutines.resume

enum class BaseMap(val label: String, val layerId: String) {
    SWISSTOPO("Landeskarte", "base-swisstopo"),
    OPENTOPO("OpenTopoMap", "base-opentopo")
}

private const val SRC_TRACK = "track"
private const val SRC_START = "track-start"
private const val SRC_END = "track-end"
private const val SRC_ME = "me"

/**
 * Raster-Style mit beiden Grundkarten; umgeschaltet wird nur die Sichtbarkeit,
 * damit Track und Standort beim Wechsel erhalten bleiben.
 */
private val STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "swisstopo": {
      "type": "raster",
      "tiles": ["https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-farbe/default/current/3857/{z}/{x}/{y}.jpeg"],
      "tileSize": 256,
      "maxzoom": 18,
      "attribution": "© swisstopo"
    },
    "opentopo": {
      "type": "raster",
      "tiles": [
        "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
        "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"
      ],
      "tileSize": 256,
      "maxzoom": 17,
      "attribution": "© OpenStreetMap-Mitwirkende, SRTM | © OpenTopoMap (CC-BY-SA)"
    }
  },
  "layers": [
    { "id": "background", "type": "background", "paint": { "background-color": "#EDEBE4" } },
    { "id": "base-swisstopo", "type": "raster", "source": "swisstopo", "layout": { "visibility": "visible" } },
    { "id": "base-opentopo", "type": "raster", "source": "opentopo", "layout": { "visibility": "none" } }
  ]
}
""".trimIndent()

/** Grob die Schweiz (inkl. Liechtenstein): dort ist die Landeskarte die bessere Wahl. */
private fun defaultBaseMap(track: List<GeoPoint>): BaseMap {
    val p = track.getOrNull(track.size / 2) ?: return BaseMap.OPENTOPO
    val inCh = p.lat in 45.8..47.85 && p.lon in 5.9..10.5
    return if (inCh) BaseMap.SWISSTOPO else BaseMap.OPENTOPO
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMap(
    track: List<GeoPoint>,
    me: GeoPoint?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val paddingPx = with(LocalDensity.current) { 40.dp.roundToPx() }

    val mapView = remember { MapView(context).also { it.onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var baseMap by remember(track) { mutableStateOf(defaultBaseMap(track)) }
    var fittedTrack by remember { mutableStateOf<List<GeoPoint>?>(null) }

    // MapView an den Lebenszyklus der Activity koppeln
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.setStyle(Style.Builder().fromJson(STYLE_JSON)) { s ->
                addOverlayLayers(s)
                map = m
                style = s
            }
        }
    }

    LaunchedEffect(style, baseMap) {
        val s = style ?: return@LaunchedEffect
        BaseMap.entries.forEach { b ->
            s.getLayer(b.layerId)?.setProperties(
                PropertyFactory.visibility(if (b == baseMap) Property.VISIBLE else Property.NONE)
            )
        }
    }

    LaunchedEffect(style, track) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_TRACK)
            ?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(track.map { it.toPoint() })))
        track.firstOrNull()?.let { s.getSourceAs<GeoJsonSource>(SRC_START)?.setGeoJson(it.toPoint()) }
        track.lastOrNull()?.let { s.getSourceAs<GeoJsonSource>(SRC_END)?.setGeoJson(it.toPoint()) }
        if (fittedTrack !== track) {
            mapView.awaitLayout()
            fit(m, track, paddingPx, animate = false)
            fittedTrack = track
        }
    }

    LaunchedEffect(style, me) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_ME) ?: return@LaunchedEffect
        if (me != null) {
            src.setGeoJson(me.toPoint())
        } else {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        }
    }

    val distance = remember(track, me) { me?.let { distanceToTrackM(it, track) } }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())

        Column(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(shape = RoundedCornerShape(50), shadowElevation = 2.dp) {
                SingleChoiceSegmentedButtonRow {
                    BaseMap.entries.forEachIndexed { i, b ->
                        SegmentedButton(
                            selected = baseMap == b,
                            onClick = { baseMap = b },
                            shape = SegmentedButtonDefaults.itemShape(i, BaseMap.entries.size),
                            icon = {}
                        ) {
                            Text(b.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (distance != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    shadowElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ) {
                    Text(
                        distanceText(distance),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            MapButton(if (expanded) "Verkleinern" else "Vollbild", onToggleExpanded)
            MapButton("Track") { map?.let { fit(it, track, paddingPx, animate = true) } }
            if (me != null) {
                MapButton("Ich + Track") {
                    map?.let { fit(it, track + me, paddingPx, animate = true) }
                }
            }
        }
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun distanceText(m: Double): String = when {
    m < 30 -> "Du bist auf dem Track"
    m < 1000 -> "Track ist ${m.toInt()} m entfernt"
    else -> {
        val f = NumberFormat.getNumberInstance(Locale.forLanguageTag("de-CH")).apply {
            maximumFractionDigits = if (m < 100_000) 1 else 0
        }
        "Track ist ${f.format(m / 1000)} km entfernt"
    }
}

private fun GeoPoint.toPoint(): Point = Point.fromLngLat(lon, lat)

private fun addOverlayLayers(s: Style) {
    listOf(SRC_TRACK, SRC_START, SRC_END, SRC_ME).forEach { s.addSource(GeoJsonSource(it)) }
    s.addLayer(
        LineLayer("track-casing", SRC_TRACK).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        LineLayer("track-line", SRC_TRACK).withProperties(
            PropertyFactory.lineColor("#E53935"),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(marker("track-start-dot", SRC_START, "#43A047", 7f))
    s.addLayer(marker("track-end-dot", SRC_END, "#212121", 7f))
    s.addLayer(marker("me-dot", SRC_ME, "#1E88E5", 9f))
}

private fun marker(id: String, source: String, color: String, radius: Float) =
    CircleLayer(id, source).withProperties(
        PropertyFactory.circleRadius(radius),
        PropertyFactory.circleColor(color),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(3f)
    )

private fun fit(map: MapLibreMap, points: List<GeoPoint>, paddingPx: Int, animate: Boolean) {
    val latLngs = points.map { LatLng(it.lat, it.lon) }.distinct()
    if (latLngs.isEmpty()) return
    val update = if (latLngs.size == 1) {
        CameraUpdateFactory.newLatLngZoom(latLngs[0], 14.0)
    } else {
        CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(latLngs).build(), paddingPx)
    }
    if (animate) map.easeCamera(update, 600) else map.moveCamera(update)
}

/** Wartet, bis die View eine Groesse hat (sonst rechnet newLatLngBounds mit 0x0). */
private suspend fun MapView.awaitLayout() {
    if (width > 0 && height > 0) return
    suspendCancellableCoroutine { cont -> doOnLayout { if (cont.isActive) cont.resume(Unit) } }
}
