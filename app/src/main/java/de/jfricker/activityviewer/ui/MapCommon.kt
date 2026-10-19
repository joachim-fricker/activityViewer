package de.jfricker.activityviewer.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.activityviewer.domain.CYCLING_TYPES
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.HIKING_TYPES
import de.jfricker.activityviewer.domain.SKI_TYPES
import kotlinx.coroutines.flow.flowOf
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
import org.maplibre.geojson.Point
import kotlin.coroutines.resume

enum class BaseMap(val label: String, val layerId: String) {
    SWISSTOPO("Landeskarte", "base-swisstopo"),
    OPENTOPO("OpenTopoMap", "base-opentopo")
}

/** Farben je Aktivitaetsgruppe (wie in der Statistik). */
internal fun groupColor(typeKey: String): String = when (typeKey) {
    in CYCLING_TYPES -> "#1E88E5"
    in HIKING_TYPES -> "#6D4C41"
    in SKI_TYPES -> "#00ACC1"
    else -> "#9E9E9E"
}

/**
 * Raster-Style mit beiden Grundkarten; umgeschaltet wird nur die Sichtbarkeit,
 * damit eigene Layer beim Wechsel erhalten bleiben. Glyphs nur fuer die Cluster-Zahlen.
 */
internal val STYLE_JSON = """
{
  "version": 8,
  "glyphs": "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
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

internal const val FONT = "Open Sans Semibold"

private fun inSwitzerland(p: GeoPoint) = p.lat in 45.8..47.85 && p.lon in 5.9..10.5

/** Landeskarte, wenn die Mehrheit der Punkte in der Schweiz liegt, sonst OpenTopoMap. */
internal fun defaultBaseMap(points: List<GeoPoint>): BaseMap {
    if (points.isEmpty()) return BaseMap.SWISSTOPO
    val inCh = points.count(::inSwitzerland)
    return if (inCh * 2 >= points.size) BaseMap.SWISSTOPO else BaseMap.OPENTOPO
}

internal fun Style.showBaseMap(baseMap: BaseMap) {
    BaseMap.entries.forEach { b ->
        getLayer(b.layerId)?.setProperties(
            PropertyFactory.visibility(if (b == baseMap) Property.VISIBLE else Property.NONE)
        )
    }
}

/** MapView, die an den Lebenszyklus der Activity gekoppelt ist. */
@Composable
internal fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).also { it.onCreate(null) } }
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
    return mapView
}

/** Wartet, bis die View eine Groesse hat (sonst rechnet newLatLngBounds mit 0x0). */
internal suspend fun MapView.awaitLayout() {
    if (width > 0 && height > 0) return
    suspendCancellableCoroutine { cont -> doOnLayout { if (cont.isActive) cont.resume(Unit) } }
}

internal fun GeoPoint.toPoint(): Point = Point.fromLngLat(lon, lat)

internal fun fit(
    map: MapLibreMap,
    points: List<GeoPoint>,
    paddingPx: Int,
    animate: Boolean,
    bottomPaddingPx: Int = paddingPx
) {
    val latLngs = points.map { LatLng(it.lat, it.lon) }.distinct()
    if (latLngs.isEmpty()) return
    val update = if (latLngs.size == 1) {
        CameraUpdateFactory.newLatLngZoom(latLngs[0], 14.0)
    } else {
        CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.Builder().includes(latLngs).build(),
            paddingPx, paddingPx, paddingPx, bottomPaddingPx
        )
    }
    if (animate) map.easeCamera(update, 600) else map.moveCamera(update)
}

internal fun trackLineLayers(source: String, prefix: String) = listOf(
    LineLayer("$prefix-casing", source).withProperties(
        PropertyFactory.lineColor("#FFFFFF"),
        PropertyFactory.lineWidth(7f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    ),
    LineLayer("$prefix-line", source).withProperties(
        PropertyFactory.lineColor("#E53935"),
        PropertyFactory.lineWidth(4f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    )
)

internal fun markerLayer(id: String, source: String, color: String, radius: Float) =
    CircleLayer(id, source).withProperties(
        PropertyFactory.circleRadius(radius),
        PropertyFactory.circleColor(color),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(3f)
    )

/** Nur einmal pro App-Start nach der Standort-Berechtigung fragen. */
private var locationPermissionAsked = false

/** Aktueller Standort (fragt beim ersten Mal nach der Berechtigung); null = unbekannt. */
@Composable
internal fun rememberMyLocation(vm: MainViewModel): GeoPoint? {
    var hasPermission by remember { mutableStateOf(vm.hasLocationPermission()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPermission = result.values.any { it } }
    LaunchedEffect(Unit) {
        if (!hasPermission && !locationPermissionAsked) {
            locationPermissionAsked = true
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
    val flow = remember(hasPermission) { if (hasPermission) vm.myLocation() else flowOf(null) }
    val me by flow.collectAsStateWithLifecycle(initialValue = null)
    return me
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BaseMapSwitch(selected: BaseMap, onSelect: (BaseMap) -> Unit) {
    Surface(shape = RoundedCornerShape(50), shadowElevation = 2.dp) {
        SingleChoiceSegmentedButtonRow {
            BaseMap.entries.forEachIndexed { i, b ->
                SegmentedButton(
                    selected = selected == b,
                    onClick = { onSelect(b) },
                    shape = SegmentedButtonDefaults.itemShape(i, BaseMap.entries.size),
                    icon = {}
                ) {
                    Text(b.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun MapButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
