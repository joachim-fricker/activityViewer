package de.jfricker.activityviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.Bitmap
import android.graphics.RectF
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.distanceToTrackM
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import java.text.NumberFormat
import java.util.Locale

private const val SRC_TRACK = "track"
private const val SRC_START = "track-start"
private const val SRC_END = "track-end"
private const val SRC_ME = "me"
private const val SRC_PHOTOS = "photos"
private const val LAYER_PHOTO_DOTS = "photo-dots"
private const val LAYER_PHOTO_ICONS = "photo-icons"

/** Foto auf der Karte: index in der Fotoliste, Position, rundes Vorschaubild (null = nur Punkt). */
data class PhotoMarker(val index: Int, val pos: GeoPoint, val icon: Bitmap?)

/** Karte mit einem einzelnen Track (Detailansicht). */
@Composable
fun TrackMap(
    track: List<GeoPoint>,
    me: GeoPoint?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    photos: List<PhotoMarker> = emptyList(),
    onPhotoClick: (index: Int) -> Unit = {}
) {
    val touchPx = with(LocalDensity.current) { 16.dp.toPx() }
    val currentOnPhotoClick by rememberUpdatedState(onPhotoClick)
    val paddingPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var baseMap by remember(track) { mutableStateOf(defaultBaseMap(track)) }
    var fittedTrack by remember { mutableStateOf<List<GeoPoint>?>(null) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.setStyle(Style.Builder().fromJson(STYLE_JSON)) { s ->
                listOf(SRC_TRACK, SRC_START, SRC_END, SRC_PHOTOS, SRC_ME).forEach { s.addSource(GeoJsonSource(it)) }
                trackLineLayers(SRC_TRACK, "track").forEach { s.addLayer(it) }
                s.addLayer(markerLayer("track-start-dot", SRC_START, "#43A047", 7f))
                s.addLayer(markerLayer("track-end-dot", SRC_END, "#212121", 7f))
                s.addLayer(markerLayer(LAYER_PHOTO_DOTS, SRC_PHOTOS, "#FB8C00", 6f))
                s.addLayer(
                    SymbolLayer(LAYER_PHOTO_ICONS, SRC_PHOTOS).withProperties(
                        PropertyFactory.iconImage(Expression.get("img")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true)
                    )
                )
                s.addLayer(markerLayer("me-dot", SRC_ME, "#1E88E5", 9f))
                m.addOnMapClickListener { latLng ->
                    val p = m.projection.toScreenLocation(latLng)
                    val hit = m.queryRenderedFeatures(
                        RectF(p.x - touchPx, p.y - touchPx, p.x + touchPx, p.y + touchPx),
                        LAYER_PHOTO_ICONS, LAYER_PHOTO_DOTS
                    ).firstOrNull()?.getNumberProperty("idx")?.toInt()
                    if (hit != null) currentOnPhotoClick(hit)
                    hit != null
                }
                map = m
                style = s
            }
        }
    }

    LaunchedEffect(style, baseMap) { style?.showBaseMap(baseMap) }

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

    LaunchedEffect(style, photos) {
        val s = style ?: return@LaunchedEffect
        val features = photos.map { pm ->
            Feature.fromGeometry(pm.pos.toPoint()).apply {
                addNumberProperty("idx", pm.index)
                if (pm.icon != null) {
                    val name = "photo-${pm.index}"
                    s.addImage(name, pm.icon)
                    addStringProperty("img", name)
                }
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC_PHOTOS)?.setGeoJson(FeatureCollection.fromFeatures(features))
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
            BaseMapSwitch(baseMap) { baseMap = it }
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
