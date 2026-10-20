package de.jfricker.activityviewer.ui

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.jfricker.activityviewer.data.PosterTrack
import de.jfricker.activityviewer.data.local.ActivityEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Vorschau des Tour-Posters mit Farbthema, optionaler Hintergrundkarte, Teilen und Speichern. */
@Composable
internal fun PosterScreen(
    vm: MainViewModel,
    activity: ActivityEntity,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var theme by rememberSaveable { mutableStateOf(PosterTheme.NIGHT) }
    var withMap by rememberSaveable { mutableStateOf(false) }

    // Track + Hoehenprofil laden
    var retry by remember { mutableIntStateOf(0) }
    var track by remember { mutableStateOf<PosterTrack?>(null) }
    var trackError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activity.id, retry) {
        trackError = null
        try {
            track = vm.posterTrack(activity.id)
        } catch (e: IOException) {
            trackError = e.message ?: "Netzwerkfehler"
        }
    }

    val t = track
    val content = remember(activity, t) { t?.let { posterContent(activity, it) } }
    val layout = remember(content) { content?.let { PosterLayout(it) } }

    // Hintergrundkarte nur einmal laden; sie ist vom Farbthema unabhaengig
    var map by remember { mutableStateOf<PosterMap?>(null) }
    var mapLoading by remember { mutableStateOf(false) }
    LaunchedEffect(withMap, layout) {
        val c = content ?: return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        if (!withMap || map != null || c.track.isEmpty()) return@LaunchedEffect
        mapLoading = true
        try {
            map = loadPosterMap(context, l, c.track)
        } catch (e: IOException) {
            Toast.makeText(context, "Karte konnte nicht geladen werden", Toast.LENGTH_SHORT).show()
            withMap = false
        } finally {
            mapLoading = false
        }
    }

    var poster by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(content, layout, theme, withMap, map) {
        val c = content ?: return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        val m = if (withMap) map else null
        poster = withContext(Dispatchers.Default) { renderPoster(c, l, theme, m) }
    }

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 16.dp)) {
            Text("← Zurück zur Tour")
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            val bmp = poster
            when {
                trackError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Track konnte nicht geladen werden: $trackError",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { retry++ }) { Text("Erneut") }
                }
                bmp == null -> CircularProgressIndicator()
                else -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Tour-Poster",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .shadow(6.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PosterTheme.entries.forEach {
                    FilterChip(selected = theme == it, onClick = { theme = it }, label = { Text(it.label) })
                }
            }
            if (content?.track?.isNotEmpty() == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = withMap, onCheckedChange = { withMap = it })
                    Spacer(Modifier.width(12.dp))
                    Text("Karte dezent im Hintergrund", Modifier.weight(1f))
                    if (mapLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val bmp = poster
                Button(
                    onClick = { bmp?.let { scope.launch { sharePoster(context, it, activity.id) } } },
                    enabled = bmp != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Teilen") }
                OutlinedButton(
                    onClick = {
                        bmp?.let {
                            scope.launch {
                                val ok = savePoster(context, it, activity)
                                Toast.makeText(
                                    context,
                                    if (ok) "In Bilder/ActivityViewer gespeichert" else "Speichern fehlgeschlagen",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = bmp != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Speichern") }
            }
        }
    }
}

/** Poster in den Cache schreiben und ueber das Teilen-Menue anbieten (z. B. WhatsApp → Mein Status). */
private suspend fun sharePoster(context: Context, bmp: Bitmap, id: Long) {
    val uri: Uri = try {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "posters").apply { mkdirs() }
            val file = File(dir, "tour_$id.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    } catch (e: IOException) {
        Toast.makeText(context, "Bild konnte nicht erstellt werden", Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Tour teilen"))
}

/** Poster in die Galerie speichern (Bilder/ActivityViewer); braucht ab Android 10 keine Berechtigung. */
private suspend fun savePoster(context: Context, bmp: Bitmap, a: ActivityEntity): Boolean =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Tour_${a.startTime.take(10)}_${a.id}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ActivityViewer")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: throw IOException("kein Ausgabestrom")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            false
        }
    }
