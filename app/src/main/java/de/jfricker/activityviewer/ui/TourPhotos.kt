package de.jfricker.activityviewer.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.jfricker.activityviewer.data.PHOTO_PERMISSIONS
import de.jfricker.activityviewer.data.PhotoAccess
import de.jfricker.activityviewer.data.TourPhoto
import de.jfricker.activityviewer.data.local.ActivityEntity
import de.jfricker.activityviewer.domain.photoPosition
import de.jfricker.activityviewer.domain.tourWindow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Zustand der Fotos einer Tour; photos = null solange noch gesucht wird. */
internal class TourPhotosState(
    val access: PhotoAccess,
    val denied: Boolean,
    val photos: List<TourPhoto>?,
    val request: () -> Unit,
    val openSettings: () -> Unit
)

/**
 * Sucht die Fotos im Zeitraum der Tour (alle Ordner des Handys).
 * Das Zeitfenster kommt aus der GPX, daher wird gewartet, bis der Track geladen ist.
 */
@Composable
internal fun rememberTourPhotos(
    vm: MainViewModel,
    activity: ActivityEntity?,
    track: TrackState
): TourPhotosState {
    val context = LocalContext.current
    var access by remember { mutableStateOf(vm.photoAccess()) }
    var denied by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        access = vm.photoAccess()
        denied = access == PhotoAccess.NONE
        refresh++
    }
    // Berechtigung kann in den Einstellungen geaendert werden, neue Fotos koennen dazukommen
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = vm.photoAccess()
        refresh++
    }

    val window = remember(activity?.id, track) {
        if (activity == null || track is TrackState.Loading) {
            null
        } else {
            tourWindow(
                activity.startTime,
                activity.durationS,
                (track as? TrackState.Loaded)?.timeline.orEmpty()
            )
        }
    }
    val photos by produceState<List<TourPhoto>?>(null, window, access, refresh) {
        if (window != null && access != PhotoAccess.NONE) value = vm.photosBetween(window)
    }

    return TourPhotosState(
        access = access,
        denied = denied,
        photos = photos,
        request = { launcher.launch(PHOTO_PERMISSIONS) },
        openSettings = {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
            runCatching { context.startActivity(intent) }
        }
    )
}

/** Foto-Marker fuer die Karte: zuerst als Punkte, dann mit runden Vorschaubildern. */
@Composable
internal fun rememberPhotoMarkers(
    vm: MainViewModel,
    photos: List<TourPhoto>?,
    track: TrackState
): List<PhotoMarker> {
    val iconPx = with(LocalDensity.current) { 36.dp.roundToPx() }
    val loaded = track as? TrackState.Loaded
    val positions = remember(photos, loaded) {
        if (photos == null || loaded == null) {
            emptyList()
        } else {
            photos.mapIndexedNotNull { i, p ->
                photoPosition(p.exifPos, p.takenMs, loaded.points, loaded.timeline)?.let { i to it }
            }
        }
    }
    val markers by produceState(emptyList<PhotoMarker>(), positions) {
        val list = photos ?: return@produceState
        value = positions.map { (i, pos) -> PhotoMarker(i, pos, null) }
        if (positions.isNotEmpty()) {
            value = positions.map { (i, pos) -> PhotoMarker(i, pos, vm.photoMapIcon(list[i], iconPx)) }
        }
    }
    return markers
}

/** Abschnitt in den Tourdetails: Berechtigung anfragen bzw. Vorschaubilder. */
@Composable
internal fun PhotosSection(vm: MainViewModel, state: TourPhotosState, onOpen: (index: Int) -> Unit) {
    val hint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val photos = state.photos
        when {
            state.access == PhotoAccess.NONE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.denied) {
                            "Kein Zugriff auf die Fotos. Bitte in den App-Einstellungen erlauben."
                        } else {
                            "Fotos vom Handy zu dieser Tour anzeigen"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = if (state.denied) state.openSettings else state.request) {
                        Text(if (state.denied) "Einstellungen" else "Erlauben")
                    }
                }
            }
            photos == null -> Text("Suche Fotos …", style = MaterialTheme.typography.bodySmall, color = hint)
            photos.isEmpty() -> Text(
                "Keine Fotos im Zeitraum der Tour gefunden.",
                style = MaterialTheme.typography.bodySmall,
                color = hint
            )
            else -> {
                Text("Fotos (${photos.size})", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
                        PhotoThumb(
                            vm, p,
                            Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpen(i) }
                        )
                    }
                }
            }
        }
        if (state.access == PhotoAccess.PARTIAL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Nur freigegebene Fotos sichtbar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = hint,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = state.request) { Text("Auswahl ändern") }
            }
        }
    }
}

@Composable
private fun PhotoThumb(vm: MainViewModel, photo: TourPhoto, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, photo.id) { value = vm.photoThumbnail(photo) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private val PHOTO_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** Vollbild mit Wischen zwischen den Fotos; Zoom mit zwei Fingern oder Doppeltipp. */
@Composable
internal fun PhotoViewer(
    vm: MainViewModel,
    photos: List<TourPhoto>,
    startIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex) { photos.size }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].id }) { page ->
            val photo = photos[page]
            val bmp by produceState<Bitmap?>(null, photo.id) { value = vm.photoFull(photo, FULL_IMAGE_PX) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b == null) CircularProgressIndicator(color = Color.White) else ZoomableImage(b)
            }
        }

        val current = photos[pager.currentPage]
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Zurück", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Text(
                "${pager.currentPage + 1} / ${photos.size} · " +
                    PHOTO_TIME.format(Instant.ofEpochMilli(current.takenMs).atZone(ZoneId.systemDefault())),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 12.dp)
            )
        }

        FilledTonalButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(current.uri, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // keine Galerie-App vorhanden
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
        ) {
            Text("In Galerie öffnen")
        }
    }
}

private const val FULL_IMAGE_PX = 2048

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            }
            .pointerInput(Unit) {
                // Nur bei zwei Fingern oder gezoomtem Bild selbst verarbeiten,
                // sonst bleibt das Wischen fuer den Pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    )
}
