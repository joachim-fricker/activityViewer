package de.jfricker.activityviewer.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import de.jfricker.activityviewer.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Ein Foto aus der Galerie des Handys. exifPos = GPS-Position aus dem Foto (falls vorhanden). */
data class TourPhoto(val id: Long, val uri: Uri, val takenMs: Long, val exifPos: GeoPoint?)

enum class PhotoAccess { NONE, PARTIAL, FULL }

/** Berechtigungen fuer den Zugriff auf die Fotos (Android 14+: auch Teilzugriff moeglich). */
val PHOTO_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    Manifest.permission.ACCESS_MEDIA_LOCATION
)

/**
 * Liest Fotos ueber den MediaStore (alle Ordner) anhand des Aufnahmezeitpunkts.
 * Die Fotos werden nicht kopiert oder gespeichert, nur Vorschaubilder im Speicher gecacht.
 */
class PhotoRepository(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Vorschaubilder und Karten-Icons; Groesse in KB. */
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }

    private fun granted(p: String) =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun access(): PhotoAccess = when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
        else -> PhotoAccess.NONE
    }

    /** Alle Fotos mit Aufnahmezeitpunkt im Bereich (UTC-Millisekunden), aelteste zuerst. */
    suspend fun photosBetween(window: LongRange): List<TourPhoto> = withContext(Dispatchers.IO) {
        if (access() == PhotoAccess.NONE) return@withContext emptyList()
        // Alle vom MediaStore erfassten Ordner (DCIM/Camera, Data/images, Pictures, ...) ausser WhatsApp.
        // Bilder ohne Aufnahmezeit (z. B. kopiert, ohne EXIF) werden ueber das Aenderungsdatum gefunden.
        val taken = MediaStore.Images.Media.DATE_TAKEN       // Millisekunden
        val modified = MediaStore.Images.Media.DATE_MODIFIED // Sekunden
        val projection = arrayOf(MediaStore.Images.Media._ID, taken, modified)
        val path = MediaStore.Images.Media.RELATIVE_PATH
        // Keine WhatsApp-Bilder (empfangen oder gesendet, liegen unter .../WhatsApp/Media/...)
        val selection = "(($taken BETWEEN ? AND ?) OR " +
            "(($taken IS NULL OR $taken = 0) AND $modified BETWEEN ? AND ?)) " +
            "AND ($path IS NULL OR $path NOT LIKE '%WhatsApp%')"
        val args = arrayOf(
            window.first.toString(), window.last.toString(),
            (window.first / 1000).toString(), (window.last / 1000).toString()
        )
        val raw = ArrayList<Pair<Long, Long>>()
        try {
            resolver.query(collection, projection, selection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = c.getColumnIndexOrThrow(taken)
                val modCol = c.getColumnIndexOrThrow(modified)
                while (c.moveToNext()) {
                    val t = if (c.isNull(takenCol) || c.getLong(takenCol) == 0L) {
                        c.getLong(modCol) * 1000
                    } else {
                        c.getLong(takenCol)
                    }
                    raw += c.getLong(idCol) to t
                }
            }
        } catch (e: SecurityException) {
            return@withContext emptyList()
        }
        raw.sortBy { it.second }
        val withLocation = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)
        raw.map { (id, taken) ->
            val uri = ContentUris.withAppendedId(collection, id)
            TourPhoto(id, uri, taken, if (withLocation) exifPosition(uri) else null)
        }
    }

    /** GPS aus den EXIF-Daten; braucht ACCESS_MEDIA_LOCATION, sonst entfernt Android die Position. */
    private fun exifPosition(uri: Uri): GeoPoint? = try {
        resolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use { s ->
            val ll = FloatArray(2)
            if (ExifInterface(s).getLatLong(ll) && (ll[0] != 0f || ll[1] != 0f)) {
                GeoPoint(ll[0].toDouble(), ll[1].toDouble())
            } else {
                null
            }
        }
    } catch (e: Exception) { // IOException, SecurityException, UnsupportedOperationException
        null
    }

    /** Quadratisches Vorschaubild (vom System, bereits richtig gedreht); null bei Fehler. */
    suspend fun thumbnail(photo: TourPhoto, sizePx: Int): Bitmap? {
        val key = "t${photo.id}-$sizePx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                resolver.loadThumbnail(photo.uri, Size(sizePx, sizePx), null)
                    .let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                    .also { cache.put(key, it) }
            } catch (e: IOException) {
                null
            }
        }
    }

    /** Rundes Icon mit weissem Rand fuer die Karte. */
    suspend fun mapIcon(photo: TourPhoto, sizePx: Int): Bitmap? {
        val key = "i${photo.id}-$sizePx"
        cache.get(key)?.let { return it }
        val thumb = thumbnail(photo, THUMB_PX) ?: return null
        return withContext(Dispatchers.Default) {
            circleIcon(thumb, sizePx, max(2f, sizePx / 14f)).also { cache.put(key, it) }
        }
    }

    /** Ganzes Bild, auf maxSide Pixel verkleinert (EXIF-Drehung wird beruecksichtigt). */
    suspend fun fullImage(photo: TourPhoto, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, photo.uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = min(1.0, maxSide.toDouble() / max(w, h))
                if (scale < 1.0) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        const val THUMB_PX = 256
    }
}

private fun circleIcon(src: Bitmap, sizePx: Int, borderPx: Float): Bitmap {
    val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val r = sizePx / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    paint.color = Color.WHITE
    canvas.drawCircle(r, r, r, paint)

    // Quelle mittig quadratisch zuschneiden und in den inneren Kreis einpassen
    val inner = r - borderPx
    val side = min(src.width, src.height).toFloat()
    val scale = 2 * inner / side
    val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    shader.setLocalMatrix(Matrix().apply {
        setTranslate(-(src.width - side) / 2f, -(src.height - side) / 2f)
        postScale(scale, scale)
        postTranslate(r - inner, r - inner)
    })
    paint.shader = shader
    canvas.drawCircle(r, r, inner, paint)
    return out
}
