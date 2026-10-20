package de.jfricker.activityviewer.ui

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.activityviewer.data.local.ActivityRow
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.isIndoor
import de.jfricker.activityviewer.domain.typeLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.NumberFormat

private const val SRC_STARTS = "starts"
private const val SRC_OPENED_STARTS = "opened-starts"
private const val SRC_SELECTED = "wm-selected"
private const val SRC_TRACK = "wm-track"
private const val SRC_ME = "wm-me"
private const val LAYER_CLUSTERS = "starts-clusters"
private const val LAYER_POINTS = "starts-points"
private const val LAYER_OPENED_POINTS = "opened-starts-points"
private const val LAYER_TRACKS = "wm-track-line"

/** Bis zu dieser Zoomstufe werden nahe Startpunkte zu Clustern zusammengefasst. */
private const val CLUSTER_MAX_ZOOM = 16

/**
 * Ab dieser Zoomstufe zeigt ein Tipp auf einen Cluster direkt die Liste der Touren,
 * statt weiter hineinzuzoomen (z. B. die vielen Touren ab der Haustuer).
 */
private const val LIST_ZOOM = 13.5

@Composable
fun WorldMapScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val all by vm.activities.collectAsStateWithLifecycle()
    val openedTracks by vm.worldTracks.collectAsStateWithLifecycle()
    val bulkProgress by vm.bulkProgress.collectAsStateWithLifecycle()
    var quick by rememberSaveable { mutableStateOf(QuickFilter.ALL) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var listIds by remember { mutableStateOf<List<Long>?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Nur Outdoor-Touren mit Startpunkt (Indoor/Rouvy gehoert nicht auf die Karte)
    val withStart = remember(all) {
        all.filter { it.startLat != null && it.startLon != null && !isIndoor(it.typeKey, it.name) }
    }
    val visible = remember(withStart, quick) {
        val types = quick.types
        if (types == null) withStart else withStart.filter { it.typeKey in types }
    }
    val byId = remember(all) { all.associateBy { it.id } }
    val me = rememberMyLocation(vm)

    val density = LocalDensity.current
    val paddingPx = with(density) { 48.dp.roundToPx() }
    val cardPaddingPx = with(density) { 230.dp.roundToPx() }
    val tapPx = with(density) { 14.dp.toPx() }

    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var cameraInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.setStyle(Style.Builder().fromJson(STYLE_JSON)) { s ->
                addWorldLayers(s)
                m.addOnMapClickListener { latLng ->
                    handleTap(
                        m, s, latLng, tapPx,
                        onPoint = { id -> selectedId = id },
                        onList = { ids -> listIds = ids; selectedId = null },
                        onNothing = { selectedId = null; listIds = null }
                    )
                }
                vm.worldCamera?.let {
                    m.moveCamera(CameraUpdateFactory.newCameraPosition(it))
                    cameraInitialized = true
                }
                map = m
                style = s
            }
        }
    }

    // Kamera fuer den naechsten Besuch des Tabs merken
    DisposableEffect(Unit) {
        onDispose { map?.let { vm.worldCamera = it.cameraPosition } }
    }

    // Weltkarte immer mit OpenTopoMap (weltweit verfuegbar)
    LaunchedEffect(style) { style?.showBaseMap(BaseMap.OPENTOPO) }

    LaunchedEffect(style, visible, openedTracks) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        // Touren mit geoeffnetem Track zaehlen nicht mehr in den Clustern mit,
        // ihre Startpunkte werden separat (ungeclustert) gezeichnet.
        val (opened, closed) = visible.partition { it.id in openedTracks }
        s.getSourceAs<GeoJsonSource>(SRC_STARTS)
            ?.setGeoJson(FeatureCollection.fromFeatures(closed.map { it.toFeature() }))
        val openedStarts = opened + openedTracks.keys
            .filter { id -> opened.none { it.id == id } }
            .mapNotNull { byId[it] }
            .filter { it.startLat != null && it.startLon != null }
        s.getSourceAs<GeoJsonSource>(SRC_OPENED_STARTS)
            ?.setGeoJson(FeatureCollection.fromFeatures(openedStarts.map { it.toFeature() }))
        if (!cameraInitialized && visible.isNotEmpty()) {
            mapView.awaitLayout()
            fit(m, coreArea(visible.map { it.start() }), paddingPx, animate = false)
            cameraInitialized = true
        }
        // Auswahl verwerfen, wenn sie durch den Filter verschwunden ist
        val ids = visible.mapTo(HashSet()) { it.id }
        if (selectedId != null && selectedId !in ids && selectedId !in openedTracks) selectedId = null
        listIds = listIds?.filter { it in ids && it !in openedTracks }?.takeIf { it.isNotEmpty() }
    }

    val trackFlow: Flow<TrackState?> = remember(selectedId) {
        val id = selectedId
        val cached = id?.let { openedTracks[it] }
        when {
            id == null -> flowOf(null)
            cached != null -> flowOf(TrackState.Loaded(cached)) // schon geoeffnet: nicht neu laden
            else -> vm.track(id)
        }
    }
    val trackState by trackFlow.collectAsStateWithLifecycle(initialValue = null)

    // Neu geladenen Track merken und darauf zoomen
    LaunchedEffect(map, trackState) {
        val m = map ?: return@LaunchedEffect
        val id = selectedId ?: return@LaunchedEffect
        val t = trackState
        if (t is TrackState.Loaded) {
            vm.addWorldTrack(id, t.points)
            fit(m, t.points, paddingPx, animate = true, bottomPaddingPx = cardPaddingPx)
        }
    }

    LaunchedEffect(style, selectedId) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_SELECTED) ?: return@LaunchedEffect
        val selected = selectedId?.let { byId[it] }
        if (selected != null) src.setGeoJson(selected.start().toPoint()) else src.clearData()
    }

    // Alle geoeffneten Tracks zeichnen; der ausgewaehlte rot und zuoberst
    LaunchedEffect(style, openedTracks, selectedId) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_TRACK) ?: return@LaunchedEffect
        val features = openedTracks.entries
            .sortedBy { it.key == selectedId }
            .map { (id, points) ->
                Feature.fromGeometry(LineString.fromLngLats(points.map { it.toPoint() })).apply {
                    addNumberProperty("id", id)
                    addBooleanProperty("selected", id == selectedId)
                }
            }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(style, me) {
        val src = style?.getSourceAs<GeoJsonSource>(SRC_ME) ?: return@LaunchedEffect
        if (me != null) src.setGeoJson(me.toPoint()) else src.clearData()
    }

    BackHandler(enabled = selectedId != null || listIds != null) {
        if (selectedId != null) selectedId = null else listIds = null
    }

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
                            onClick = { quick = it },
                            label = { Text(it.label) }
                        )
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MapButton("Tracks im Bereich öffnen") {
                    val m = map ?: return@MapButton
                    val bounds = m.projection.visibleRegion.latLngBounds
                    val ids = visible
                        .filter { bounds.contains(LatLng(it.startLat ?: 0.0, it.startLon ?: 0.0)) }
                        .map { it.id }
                    vm.openWorldTracks(ids)
                }
                MapButton("Alle zeigen") {
                    map?.let { fit(it, coreArea(visible.map { r -> r.start() }), paddingPx, animate = true) }
                }
                if (openedTracks.isNotEmpty()) {
                    MapButton("Tracks ausblenden (${openedTracks.size})") {
                        vm.clearWorldTracks()
                        selectedId = null
                    }
                }
                if (me != null) {
                    MapButton("Mein Standort") {
                        map?.easeCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(me.lat, me.lon), 13.0), 600
                        )
                    }
                }
            }
            bulkProgress?.let { (done, total) ->
                Surface(
                    shape = RoundedCornerShape(50),
                    shadowElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ) {
                    Row(
                        Modifier.padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Lade Tracks $done / $total …",
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextButton(onClick = vm::cancelOpenWorldTracks) { Text("Abbrechen") }
                    }
                }
            }
        }

        val selected = selectedId?.let { byId[it] }
        val list = listIds
        Box(Modifier.align(Alignment.BottomCenter)) {
            when {
                selected != null -> SelectionCard(
                    row = selected,
                    trackState = trackState,
                    onOpen = { detailId = selected.id },
                    onClose = { selectedId = null; listIds = null },
                    onBackToList = if (list != null) ({ selectedId = null }) else null
                )
                list != null -> TourListCard(
                    rows = list.mapNotNull { byId[it] }.sortedByDescending { it.startTime },
                    onPick = { selectedId = it },
                    onClose = { listIds = null }
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

// ---------------------------------------------------------------- Karten-Logik

private fun ActivityRow.start() = GeoPoint(startLat ?: 0.0, startLon ?: 0.0)

private fun ActivityRow.toFeature(): Feature =
    Feature.fromGeometry(start().toPoint()).apply {
        addNumberProperty("id", id)
        addStringProperty("color", groupColor(typeKey))
    }

private fun Feature.activityId(): Long? =
    if (hasProperty("id")) getNumberProperty("id").toLong() else null

private fun GeoJsonSource.clearData() =
    setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))

/**
 * Bereich, in dem die meisten Touren liegen (5 %- bis 95 %-Quantil), damit einzelne
 * Ferientouren weit weg die Startansicht nicht auf ganz Europa aufziehen.
 */
internal fun coreArea(points: List<GeoPoint>): List<GeoPoint> {
    if (points.size < 20) return points
    val lats = points.map { it.lat }.sorted()
    val lons = points.map { it.lon }.sorted()
    val lo = (points.size * 0.05).toInt()
    val hi = (points.size * 0.95).toInt().coerceAtMost(points.size - 1)
    return listOf(GeoPoint(lats[lo], lons[lo]), GeoPoint(lats[hi], lons[hi]))
}

private fun addWorldLayers(s: Style) {
    s.addSource(
        GeoJsonSource(
            SRC_STARTS,
            GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(40)
                .withClusterMaxZoom(CLUSTER_MAX_ZOOM)
        )
    )
    listOf(SRC_OPENED_STARTS, SRC_SELECTED, SRC_TRACK, SRC_ME).forEach { s.addSource(GeoJsonSource(it)) }

    val isSelected = eq(get("selected"), literal(true))
    s.addLayer(
        LineLayer("wm-track-casing", SRC_TRACK).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        LineLayer(LAYER_TRACKS, SRC_TRACK).withProperties(
            PropertyFactory.lineColor(switchCase(isSelected, literal("#E53935"), literal("#7B1FA2"))),
            PropertyFactory.lineWidth(switchCase(isSelected, literal(4.5f), literal(3.5f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    s.addLayer(
        CircleLayer(LAYER_CLUSTERS, SRC_STARTS)
            .withFilter(has("point_count"))
            .withProperties(
                PropertyFactory.circleColor("#5E35B1"),
                PropertyFactory.circleOpacity(0.85f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleRadius(
                    step(get("point_count"), literal(14f), stop(10, 17f), stop(50, 21f), stop(200, 26f))
                )
            )
    )
    s.addLayer(
        SymbolLayer("starts-cluster-count", SRC_STARTS)
            .withFilter(has("point_count"))
            .withProperties(
                PropertyFactory.textField(get("point_count_abbreviated")),
                PropertyFactory.textFont(arrayOf(FONT)),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true)
            )
    )
    s.addLayer(
        CircleLayer(LAYER_POINTS, SRC_STARTS)
            .withFilter(not(has("point_count")))
            .withProperties(
                PropertyFactory.circleColor(get("color")),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f)
            )
    )
    s.addLayer(
        CircleLayer(LAYER_OPENED_POINTS, SRC_OPENED_STARTS).withProperties(
            PropertyFactory.circleColor(get("color")),
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleStrokeColor("#7B1FA2"),
            PropertyFactory.circleStrokeWidth(2.5f)
        )
    )
    s.addLayer(
        CircleLayer("wm-selected-ring", SRC_SELECTED).withProperties(
            PropertyFactory.circleRadius(12f),
            PropertyFactory.circleOpacity(0f),
            PropertyFactory.circleStrokeColor("#212121"),
            PropertyFactory.circleStrokeWidth(3f)
        )
    )
    s.addLayer(markerLayer("wm-me-dot", SRC_ME, "#1E88E5", 9f))
}

/**
 * Tipp auf die Karte:
 * - Cluster, der sich noch aufteilen laesst und wir sind weit draussen -> hineinzoomen
 * - Cluster nah dran oder Punkte an (fast) derselben Stelle -> Liste der Touren
 * - einzelner Startpunkt oder geoeffneter Track -> auswaehlen
 */
private fun handleTap(
    m: MapLibreMap,
    s: Style,
    latLng: LatLng,
    tapPx: Float,
    onPoint: (Long) -> Unit,
    onList: (List<Long>) -> Unit,
    onNothing: () -> Unit
): Boolean {
    val p = m.projection.toScreenLocation(latLng)
    val rect = RectF(p.x - tapPx, p.y - tapPx, p.x + tapPx, p.y + tapPx)
    val src = s.getSourceAs<GeoJsonSource>(SRC_STARTS)

    val cluster = m.queryRenderedFeatures(rect, LAYER_CLUSTERS).firstOrNull()
    if (cluster != null && src != null) {
        val expansionZoom = src.getClusterExpansionZoom(cluster)
        val center = cluster.geometry() as? Point
        if (expansionZoom <= CLUSTER_MAX_ZOOM && m.cameraPosition.zoom < LIST_ZOOM && center != null) {
            m.easeCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(center.latitude(), center.longitude()),
                    expansionZoom.toDouble()
                ),
                500
            )
        } else {
            val leaves = src.getClusterLeaves(cluster, 10_000, 0).features().orEmpty()
            onList(leaves.mapNotNull { it.activityId() })
        }
        return true
    }

    val ids = m.queryRenderedFeatures(rect, LAYER_POINTS, LAYER_OPENED_POINTS)
        .mapNotNull { it.activityId() }
        .distinct()
    if (ids.size == 1) { onPoint(ids[0]); return true }
    if (ids.size > 1) { onList(ids); return true }

    // Tipp auf einen bereits geoeffneten Track waehlt diese Tour aus
    val trackId = m.queryRenderedFeatures(rect, LAYER_TRACKS).firstNotNullOfOrNull { it.activityId() }
    if (trackId != null) { onPoint(trackId); return true }

    onNothing()
    return false
}

// ---------------------------------------------------------------- Overlays

@Composable
internal fun TypeDot(typeKey: String) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(Color(android.graphics.Color.parseColor(groupColor(typeKey))))
    )
}

@Composable
private fun SelectionCard(
    row: ActivityRow,
    trackState: TrackState?,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onBackToList: (() -> Unit)?
) {
    val fmtKm = remember { km1() }
    val fmtInt = remember { NumberFormat.getIntegerInstance(LOCALE) }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeDot(row.typeKey)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${typeLabel(row.typeKey)} · ${longDate(row.startTime)}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose) { Text("✕") }
            }
            Text(
                row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            row.locationName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                "${fmtKm.format(row.distanceM / 1000.0)} km · " +
                    "${fmtInt.format(row.elevationGain)} Hm · ${formatDuration(row.durationS)} h",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBackToList != null) {
                    TextButton(onClick = onBackToList) { Text("← Liste") }
                }
                val status = when (trackState) {
                    is TrackState.Loading -> "Track wird geladen …"
                    is TrackState.NoTrack -> "kein GPS-Track"
                    is TrackState.Error -> "Track nicht geladen"
                    else -> ""
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = if (onBackToList == null) 0.dp else 4.dp)
                )
                Text(
                    "Details ›",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp, top = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun TourListCard(
    rows: List<ActivityRow>,
    onPick: (Long) -> Unit,
    onClose: () -> Unit,
    title: String = "${rows.size} Touren an diesem Ort"
) {
    val fmtKm = remember { km1() }
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) { Text("✕") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(rows, key = { it.id }) { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(r.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                            "${shortDate(r.startTime)} · ${typeLabel(r.typeKey)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("${fmtKm.format(r.distanceM / 1000.0)} km", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
