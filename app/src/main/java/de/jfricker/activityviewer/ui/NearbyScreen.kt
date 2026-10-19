package de.jfricker.activityviewer.ui

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.activityviewer.data.NearbyHit
import de.jfricker.activityviewer.data.local.ActivityRow
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.circlePolygon
import de.jfricker.activityviewer.domain.typeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Polygon
import java.text.NumberFormat

private val RADII_KM = listOf(2, 5, 10)

/** Feste Zeilenhoehe der Trefferliste, damit sich die Listenhoehe aus der Anzahl ergibt. */
private val ROW_HEIGHT = 56.dp
private val ITEM_HEIGHT = ROW_HEIGHT + 1.dp // inkl. Trennlinie

private const val SRC_CIRCLE = "nb-circle"
private const val SRC_CENTER = "nb-center"
private const val SRC_HITS = "nb-hits"
private const val SRC_SELECTED = "nb-selected"
private const val SRC_ME = "nb-me"
private const val LAYER_HITS = "nb-hits-line"

@Composable
fun NearbyScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val all by vm.activities.collectAsStateWithLifecycle()
    val byId = remember(all) { all.associateBy { it.id } }
    val missing by vm.missingTrackIndex.collectAsStateWithLifecycle()
    val indexProgress by vm.indexProgress.collectAsStateWithLifecycle()
    val me = rememberMyLocation(vm)

    var radiusKm by rememberSaveable { mutableIntStateOf(5) }
    var quick by rememberSaveable { mutableStateOf(QuickFilter.ALL) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Suchpunkt: per Langdruck gewaehlt, sonst der Standort beim Oeffnen
    // (nicht jedem GPS-Update folgen, sonst springt die Liste staendig)
    var pickedLat by rememberSaveable { mutableStateOf<Double?>(null) }
    var pickedLon by rememberSaveable { mutableStateOf<Double?>(null) }
    var anchor by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(me) { if (anchor == null && me != null) anchor = me }
    val picked = pickedLat?.let { lat -> pickedLon?.let { lon -> GeoPoint(lat, lon) } }
    val center = picked ?: anchor

    // Fehlende Tracks fuer die Suche nachladen (automatisch nur im WLAN)
    LaunchedEffect(Unit) { vm.buildTrackIndex() }

    var hits by remember { mutableStateOf<List<NearbyHit>?>(null) }
    LaunchedEffect(center, radiusKm, missing) {
        val c = center ?: run { hits = null; return@LaunchedEffect }
        delay(300) // waehrend der Index aufgebaut wird nicht bei jedem Track neu suchen
        hits = vm.nearby(c, radiusKm * 1000.0)
    }
    val results = remember(hits, quick, byId) {
        val types = quick.types
        hits.orEmpty().mapNotNull { h ->
            val row = byId[h.id] ?: return@mapNotNull null
            if (types != null && row.typeKey !in types) null else row to h
        }
    }
    LaunchedEffect(results) {
        if (selectedId != null && results.none { it.first.id == selectedId }) selectedId = null
    }

    val trackFlow: Flow<TrackState?> = remember(selectedId) {
        val id = selectedId
        if (id == null) flowOf(null) else vm.track(id)
    }
    val selectedTrack by trackFlow.collectAsStateWithLifecycle(initialValue = null)

    BackHandler(enabled = selectedId != null) { selectedId = null }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- Radius und Filter
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RADII_KM.forEach { km ->
                    FilterChip(
                        selected = radiusKm == km,
                        onClick = { radiusKm = km },
                        label = { Text("$km km") }
                    )
                }
                Spacer(Modifier.width(6.dp))
                QuickFilter.entries.forEach {
                    FilterChip(
                        selected = quick == it,
                        onClick = { quick = it },
                        label = { Text(it.label) }
                    )
                }
            }

            StatusLine(
                center = center,
                isPicked = picked != null,
                radiusKm = radiusKm,
                count = results.size,
                searched = hits != null,
                missing = missing,
                indexProgress = indexProgress,
                onLoadIndex = { vm.buildTrackIndex(force = true) }
            )

            // ---- Karte und (nur bei Treffern) Liste; die Liste waechst mit der Trefferzahl,
            // nimmt aber hoechstens die Haelfte des Platzes ein
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val listHeight = if (results.isEmpty()) 0.dp
                else minOf(ITEM_HEIGHT * results.size, maxHeight * 0.5f)
                Column(Modifier.fillMaxSize()) {
                    NearbyMap(
                        center = center,
                        radiusM = radiusKm * 1000.0,
                        hits = results.map { it.second },
                        selectedId = selectedId,
                        selectedTrack = (selectedTrack as? TrackState.Loaded)?.points,
                        me = me,
                        onLongPress = { p ->
                            pickedLat = p.lat
                            pickedLon = p.lon
                            selectedId = null
                        },
                        onTrackTap = { selectedId = it },
                        onBackToMe = if (me != null) ({
                            pickedLat = null
                            pickedLon = null
                            anchor = me
                            selectedId = null
                        }) else null,
                        layoutKey = listHeight,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )

                    if (results.isNotEmpty()) {
                        HorizontalDivider()
                        ResultList(
                            results = results,
                            selectedId = selectedId,
                            onSelect = { id -> if (selectedId == id) detailId = id else selectedId = id },
                            onDetails = { detailId = it },
                            modifier = Modifier.fillMaxWidth().height(listHeight)
                        )
                    }
                }
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

@Composable
private fun StatusLine(
    center: GeoPoint?,
    isPicked: Boolean,
    radiusKm: Int,
    count: Int,
    searched: Boolean,
    missing: Int,
    indexProgress: Pair<Int, Int>?,
    onLoadIndex: () -> Unit
) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        val text = when {
            center == null -> "Standort unbekannt – lange auf die Karte drücken, um einen Suchpunkt zu setzen."
            !searched -> "Suche …"
            else -> "$count Touren im Umkreis von $radiusKm km um " +
                (if (isPicked) "den gewählten Punkt" else "deinen Standort") +
                " · Langdruck auf die Karte = anderer Suchpunkt"
        }
        Text(text, style = small, color = muted)

        if (indexProgress != null) {
            val (done, total) = indexProgress
            Text("Tracks für die Suche werden geladen: $done / $total", style = small, color = muted)
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done / total.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        } else if (missing > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Für $missing Touren fehlt der Track noch (lädt automatisch im WLAN).",
                    style = small,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onLoadIndex) { Text("Jetzt laden") }
            }
        }
    }
}

@Composable
private fun ResultList(
    results: List<Pair<ActivityRow, NearbyHit>>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDetails: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val fmtKm = remember { km1() }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId) {
        val i = results.indexOfFirst { it.first.id == selectedId }
        if (i >= 0) listState.animateScrollToItem(i)
    }
    LazyColumn(modifier, state = listState) {
        items(results, key = { it.first.id }) { (r, h) ->
            val selected = r.id == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable { onSelect(r.id) }
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TypeDot(r.typeKey)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${shortDate(r.startTime)} · ${typeLabel(r.typeKey)} · " +
                            "${fmtKm.format(r.distanceM / 1000.0)} km",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selected) {
                    TextButton(onClick = { onDetails(r.id) }) { Text("Details ›") }
                } else {
                    Text(
                        distanceLabel(h.distanceM, fmtKm),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Abstand vom Suchpunkt zum Track (auf Basis des vereinfachten Tracks, daher gerundet). */
private fun distanceLabel(m: Double, fmtKm: NumberFormat): String = when {
    m < 150 -> "am Track"
    m < 1000 -> "${(m / 50).toInt() * 50} m"
    else -> "${fmtKm.format(m / 1000)} km"
}

// ---------------------------------------------------------------- Karte

@Composable
private fun NearbyMap(
    center: GeoPoint?,
    radiusM: Double,
    hits: List<NearbyHit>,
    selectedId: Long?,
    selectedTrack: List<GeoPoint>?,
    me: GeoPoint?,
    onLongPress: (GeoPoint) -> Unit,
    onTrackTap: (Long) -> Unit,
    onBackToMe: (() -> Unit)?,
    layoutKey: Any?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val paddingPx = with(density) { 24.dp.roundToPx() }
    val tapPx = with(density) { 14.dp.toPx() }
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var baseMapChoice by rememberSaveable { mutableStateOf<BaseMap?>(null) }
    val baseMap = baseMapChoice ?: defaultBaseMap(listOfNotNull(center))

    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnTrackTap by rememberUpdatedState(onTrackTap)

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.setStyle(Style.Builder().fromJson(STYLE_JSON)) { s ->
                addNearbyLayers(s)
                m.addOnMapLongClickListener { latLng ->
                    currentOnLongPress(GeoPoint(latLng.latitude, latLng.longitude))
                    true
                }
                m.addOnMapClickListener { latLng ->
                    val p = m.projection.toScreenLocation(latLng)
                    val rect = RectF(p.x - tapPx, p.y - tapPx, p.x + tapPx, p.y + tapPx)
                    val id = m.queryRenderedFeatures(rect, LAYER_HITS, "nb-selected-line")
                        .firstNotNullOfOrNull { f ->
                            if (f.hasProperty("id")) f.getNumberProperty("id").toLong() else null
                        }
                    if (id != null) currentOnTrackTap(id)
                    id != null
                }
                // Ohne Suchpunkt: Uebersicht Schweiz
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(46.8, 8.23), 7.0))
                map = m
                style = s
            }
        }
    }

    LaunchedEffect(style, baseMap) { style?.showBaseMap(baseMap) }

    // Suchkreis und Mittelpunkt; Kamera auf den Kreis (auch wenn sich die Kartengroesse aendert)
    LaunchedEffect(style, center, radiusM, layoutKey) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val circleSrc = s.getSourceAs<GeoJsonSource>(SRC_CIRCLE)
        val centerSrc = s.getSourceAs<GeoJsonSource>(SRC_CENTER)
        if (center == null) {
            circleSrc?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            centerSrc?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return@LaunchedEffect
        }
        val ring = circlePolygon(center, radiusM)
        circleSrc?.setGeoJson(Polygon.fromLngLats(listOf(ring.map { it.toPoint() })))
        centerSrc?.setGeoJson(center.toPoint())
        mapView.awaitLayout()
        delay(150) // neue Kartengroesse abwarten, falls sich die Liste gerade veraendert hat
        fit(m, ring, paddingPx, animate = true)
    }

    // Gefundene Tracks (vereinfacht, violett); der ausgewaehlte kommt detailliert und rot
    LaunchedEffect(style, hits, selectedId) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_HITS) ?: return@LaunchedEffect
        val features = hits.filter { it.id != selectedId }.map { h ->
            Feature.fromGeometry(LineString.fromLngLats(h.track.map { it.toPoint() })).apply {
                addNumberProperty("id", h.id)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(style, selectedId, selectedTrack) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val src = s.getSourceAs<GeoJsonSource>(SRC_SELECTED) ?: return@LaunchedEffect
        val id = selectedId
        val track = selectedTrack ?: hits.firstOrNull { it.id == id }?.track
        if (id == null || track == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return@LaunchedEffect
        }
        src.setGeoJson(
            Feature.fromGeometry(LineString.fromLngLats(track.map { it.toPoint() })).apply {
                addNumberProperty("id", id)
            }
        )
        if (selectedTrack != null) fit(m, track + listOfNotNull(center), paddingPx, animate = true)
    }

    LaunchedEffect(style, me) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_ME) ?: return@LaunchedEffect
        if (me != null) {
            src.setGeoJson(me.toPoint())
        } else {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        Column(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BaseMapSwitch(baseMap) { baseMapChoice = it }
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (center != null) {
                MapButton("Suchkreis") {
                    map?.let { fit(it, circlePolygon(center, radiusM), paddingPx, animate = true) }
                }
            }
            if (onBackToMe != null) {
                MapButton("Mein Standort", onBackToMe)
            }
        }
    }
}

private fun addNearbyLayers(s: Style) {
    listOf(SRC_CIRCLE, SRC_CENTER, SRC_HITS, SRC_SELECTED, SRC_ME).forEach { s.addSource(GeoJsonSource(it)) }
    s.addLayer(
        FillLayer("nb-circle-fill", SRC_CIRCLE).withProperties(
            PropertyFactory.fillColor("#1E88E5"),
            PropertyFactory.fillOpacity(0.08f)
        )
    )
    s.addLayer(
        LineLayer("nb-circle-line", SRC_CIRCLE).withProperties(
            PropertyFactory.lineColor("#1E88E5"),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f))
        )
    )
    s.addLayer(
        LineLayer(LAYER_HITS, SRC_HITS).withProperties(
            PropertyFactory.lineColor("#7B1FA2"),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineOpacity(0.8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        LineLayer("nb-selected-casing", SRC_SELECTED).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        LineLayer("nb-selected-line", SRC_SELECTED).withProperties(
            PropertyFactory.lineColor("#E53935"),
            PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(markerLayer("nb-center-dot", SRC_CENTER, "#212121", 6f))
    s.addLayer(markerLayer("nb-me-dot", SRC_ME, "#1E88E5", 9f))
}
