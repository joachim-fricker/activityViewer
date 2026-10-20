package de.jfricker.activityviewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import de.jfricker.activityviewer.data.PosterTrack
import de.jfricker.activityviewer.data.local.ActivityEntity
import de.jfricker.activityviewer.domain.CYCLING_TYPES
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.ProfilePoint
import de.jfricker.activityviewer.domain.typeLabel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.IOException
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/*
 * Tour-Poster im Stil eines Kunstdrucks: ruhiger Hintergrund, der Track als einzige Linie,
 * grosse Kennzahlen und das Hoehenprofil. Format 9:16 (1080 x 1920) fuer den WhatsApp-Status.
 * Oben und unten bleibt Rand frei, weil WhatsApp dort Name bzw. Antwortfeld einblendet.
 */

const val POSTER_W = 1080
const val POSTER_H = 1920
private const val MARGIN = 90f

enum class PosterTheme(
    val label: String,
    val bg: Int,
    val accent: Int,
    val text: Int,
    val muted: Int,
    /** Wie deutlich die Karte im Hintergrund durchscheint (0..1). */
    val mapStrength: Float
) {
    NIGHT("Nacht", 0xFF101820.toInt(), 0xFFF2AA4C.toInt(), 0xFFF5F2EB.toInt(), 0xFF96A0AA.toInt(), 0.32f),
    PAPER("Papier", 0xFFF3EEE3.toInt(), 0xFFC44536.toInt(), 0xFF1F2A30.toInt(), 0xFF78746C.toInt(), 0.42f),
    FOREST("Wald", 0xFF1F3B2D.toInt(), 0xFFF4E9CD.toInt(), 0xFFF4E9CD.toInt(), 0xFFA0B4A0.toInt(), 0.30f),
    GLACIER("Gletscher", 0xFFE9F0F3.toInt(), 0xFF0B4F6C.toInt(), 0xFF0E2A3A.toInt(), 0xFF6B7F8A.toInt(), 0.42f)
}

data class PosterStat(val value: String, val label: String)

data class PosterContent(
    val kicker: String,
    val title: String,
    val location: String?,
    val stats: List<PosterStat>,
    val track: List<GeoPoint>,
    val profile: List<ProfilePoint>,
    val totalKm: String
)

/** Gerenderte Hintergrundkarte und die darauf exakt passenden Track-Pixel. */
class PosterMap(val bitmap: Bitmap, val trackPx: List<PointF>, val attribution: String)

// ---------------------------------------------------------------- Inhalt

private val DATE_FMT = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN)

fun posterContent(a: ActivityEntity, track: PosterTrack): PosterContent {
    val fmtKm = km1()
    val fmtInt = NumberFormat.getIntegerInstance(LOCALE)
    val date = try {
        LocalDate.parse(a.startTime.take(10)).format(DATE_FMT)
    } catch (e: Exception) {
        longDate(a.startTime)
    }
    val km = fmtKm.format(a.distanceM / 1000.0)
    val stats = buildList {
        add(PosterStat(km, "KILOMETER"))
        add(PosterStat(fmtInt.format(a.elevationGain), "HÖHENMETER"))
        add(PosterStat(formatDuration(a.durationS), "STUNDEN"))
        val avg = a.avgSpeed?.takeIf { it > 0 }
        when {
            a.typeKey in CYCLING_TYPES && avg != null ->
                add(PosterStat(fmtKm.format(avg * 3.6), "Ø KM/H"))
            a.maxElev != null -> add(PosterStat(fmtInt.format(a.maxElev), "MAX. HÖHE"))
        }
    }
    return PosterContent(
        kicker = "${typeLabel(a.typeKey)}  ·  $date".uppercase(Locale.GERMAN),
        title = a.name,
        location = a.locationName?.takeIf { it.isNotBlank() },
        stats = stats,
        track = track.points,
        profile = track.profile,
        totalKm = "$km km"
    )
}

// ---------------------------------------------------------------- Layout

private fun mercY(lat: Double) = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))

/**
 * Feste Geometrie des Posters (unabhaengig vom Farbthema), damit die Hintergrundkarte
 * nur einmal geladen werden muss.
 */
class PosterLayout(content: PosterContent) {
    val kickerBaseline = 190f
    val titleTop = 225f
    val title: StaticLayout
    val locationBaseline: Float
    val mapRect: RectF
    val trackRect: RectF
    val statsTop: Float
    val profileRect: RectF?

    // Projektion (Web Mercator, wie die Karte)
    private val scale: Double
    private val cx: Double
    private val cy: Double

    init {
        title = titleLayout(content.title)
        val titleBottom = titleTop + title.height
        locationBaseline = titleBottom + 52f
        val headerBottom = if (content.location != null) locationBaseline + 20f else titleBottom + 10f

        val hasProfile = content.profile.size >= 2
        statsTop = if (hasProfile) 1330f else 1520f
        profileRect = if (hasProfile) RectF(MARGIN, 1500f, POSTER_W - MARGIN, 1730f) else null
        mapRect = RectF(0f, headerBottom + 20f, POSTER_W.toFloat(), statsTop - 40f)
        trackRect = RectF(mapRect.left + MARGIN, mapRect.top + 60f, mapRect.right - MARGIN, mapRect.bottom - 60f)

        val pts = content.track
        if (pts.isEmpty()) {
            scale = 1.0; cx = 0.0; cy = 0.0
        } else {
            val xs = pts.map { Math.toRadians(it.lon) }
            val ys = pts.map { mercY(it.lat) }
            val spanX = max(xs.max() - xs.min(), 1e-7)
            val spanY = max(ys.max() - ys.min(), 1e-7)
            scale = min(trackRect.width() / spanX, trackRect.height() / spanY)
            cx = (xs.max() + xs.min()) / 2
            cy = (ys.max() + ys.min()) / 2
        }
    }

    fun toPx(p: GeoPoint) = PointF(
        (trackRect.centerX() + (Math.toRadians(p.lon) - cx) * scale).toFloat(),
        (trackRect.centerY() - (mercY(p.lat) - cy) * scale).toFloat()
    )

    fun toGeo(x: Float, y: Float): GeoPoint {
        val lon = Math.toDegrees((x - trackRect.centerX()) / scale + cx)
        val my = cy - (y - trackRect.centerY()) / scale
        return GeoPoint(Math.toDegrees(atan(sinh(my))), lon)
    }

    private fun titleLayout(text: String): StaticLayout {
        val width = (POSTER_W - 2 * MARGIN).toInt()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = HEAVY }
        // Schrift verkleinern, bis der Name in zwei Zeilen passt
        var size = 96f
        while (size > 60f) {
            paint.textSize = size
            if (build(text, paint, width, Int.MAX_VALUE).lineCount <= 2) break
            size -= 4f
        }
        paint.textSize = size
        return build(text, paint, width, 2)
    }

    private fun build(text: String, paint: TextPaint, width: Int, maxLines: Int) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 0.95f)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
}

// ---------------------------------------------------------------- Rendern

private val HEAVY: Typeface = Typeface.create(Typeface.SANS_SERIF, 800, false)
private val BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, 700, false)
private val MEDIUM: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
private val REGULAR: Typeface = Typeface.create(Typeface.SANS_SERIF, 400, false)

private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

private fun textPaint(color: Int, size: Float, face: Typeface, spacing: Float = 0f) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = face
        letterSpacing = spacing
    }

fun renderPoster(c: PosterContent, l: PosterLayout, theme: PosterTheme, map: PosterMap?): Bitmap {
    val bmp = Bitmap.createBitmap(POSTER_W, POSTER_H, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.drawColor(theme.bg)

    if (map != null) drawMap(cv, map.bitmap, l.mapRect, theme)

    // Kopf
    val kicker = textPaint(theme.accent, 30f, MEDIUM, 0.1f)
    cv.drawText(TextUtils.ellipsize(c.kicker, kicker, POSTER_W - 2 * MARGIN, TextUtils.TruncateAt.END).toString(),
        MARGIN, l.kickerBaseline, kicker)
    l.title.paint.color = theme.text
    cv.save()
    cv.translate(MARGIN, l.titleTop)
    l.title.draw(cv)
    cv.restore()
    c.location?.let {
        val p = textPaint(theme.muted, 38f, REGULAR)
        cv.drawText(TextUtils.ellipsize(it, p, POSTER_W - 2 * MARGIN, TextUtils.TruncateAt.END).toString(),
            MARGIN, l.locationBaseline, p)
    }

    // Track
    val px = map?.trackPx ?: c.track.map(l::toPx)
    if (px.size >= 2) drawTrack(cv, px, theme, onMap = map != null)

    drawStats(cv, c.stats, l.statsTop, theme)
    l.profileRect?.let { drawProfile(cv, c.profile, it, c.totalKm, theme) }

    map?.let {
        val p = textPaint(withAlpha(theme.muted, 200), 20f, REGULAR).apply { textAlign = Paint.Align.CENTER }
        cv.drawText(it.attribution, POSTER_W / 2f, POSTER_H - 70f, p)
    }
    return bmp
}

/**
 * Karte entsaettigt und in die Themenfarben umgefaerbt: helles Papier wird zur
 * Hintergrundfarbe, dunkle Linien gehen leicht in Richtung Textfarbe. Ränder blenden aus.
 */
private fun drawMap(cv: Canvas, map: Bitmap, rect: RectF, theme: PosterTheme) {
    val s = theme.mapStrength
    fun row(bg: Int, fg: Int): FloatArray {
        val d = (fg - bg) * s
        return floatArrayOf(-d * 0.30f / 255f, -d * 0.59f / 255f, -d * 0.11f / 255f, 0f, bg + d)
    }
    val bg = theme.bg
    val fg = theme.text
    val m = ColorMatrix(
        row((bg shr 16) and 0xFF, (fg shr 16) and 0xFF) +
            row((bg shr 8) and 0xFF, (fg shr 8) and 0xFF) +
            row(bg and 0xFF, fg and 0xFF) +
            floatArrayOf(0f, 0f, 0f, 1f, 0f)
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(m)
    }
    cv.drawBitmap(map, null, rect, paint)

    // weiche Raender in die Hintergrundfarbe
    val clear = withAlpha(bg, 0)
    val fade = 170f
    val side = 110f
    val p = Paint()
    p.shader = LinearGradient(0f, rect.top, 0f, rect.top + fade, bg, clear, Shader.TileMode.CLAMP)
    cv.drawRect(rect.left, rect.top, rect.right, rect.top + fade, p)
    p.shader = LinearGradient(0f, rect.bottom, 0f, rect.bottom - fade, bg, clear, Shader.TileMode.CLAMP)
    cv.drawRect(rect.left, rect.bottom - fade, rect.right, rect.bottom, p)
    p.shader = LinearGradient(rect.left, 0f, rect.left + side, 0f, bg, clear, Shader.TileMode.CLAMP)
    cv.drawRect(rect.left, rect.top, rect.left + side, rect.bottom, p)
    p.shader = LinearGradient(rect.right, 0f, rect.right - side, 0f, bg, clear, Shader.TileMode.CLAMP)
    cv.drawRect(rect.right - side, rect.top, rect.right, rect.bottom, p)
}

private fun drawTrack(cv: Canvas, px: List<PointF>, theme: PosterTheme, onMap: Boolean) {
    val path = Path().apply {
        moveTo(px[0].x, px[0].y)
        for (i in 1 until px.size) lineTo(px[i].x, px[i].y)
    }
    fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        strokeWidth = width
    }
    // Schein unter der Linie; auf der Karte zusaetzlich ein Rand in Hintergrundfarbe
    cv.drawPath(path, stroke(withAlpha(theme.accent, 90), 26f).apply {
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    })
    if (onMap) cv.drawPath(path, stroke(withAlpha(theme.bg, 210), 17f))
    cv.drawPath(path, stroke(theme.accent, 9f))

    // Start = Punkt, Ziel = Ring; bei Rundtouren beides uebereinander
    val start = px.first()
    val end = px.last()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.text }
    val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.bg }
    val ring = stroke(theme.text, 6f)
    if (hypot(end.x - start.x, end.y - start.y) < 30f) {
        cv.drawCircle(start.x, start.y, 22f, ringFill)
        cv.drawCircle(start.x, start.y, 22f, ring)
        cv.drawCircle(start.x, start.y, 10f, fill)
    } else {
        cv.drawCircle(end.x, end.y, 16f, ringFill)
        cv.drawCircle(end.x, end.y, 16f, ring)
        cv.drawCircle(start.x, start.y, 14f, fill)
    }
}

private fun drawStats(cv: Canvas, stats: List<PosterStat>, top: Float, theme: PosterTheme) {
    if (stats.isEmpty()) return
    val colW = (POSTER_W - 2 * MARGIN) / stats.size
    stats.forEachIndexed { i, s ->
        val x = MARGIN + i * colW
        val value = textPaint(theme.text, 64f, BOLD)
        while (value.measureText(s.value) > colW - 16f && value.textSize > 36f) value.textSize -= 2f
        cv.drawText(s.value, x, top + 62f, value)
        val label = textPaint(theme.muted, 24f, MEDIUM, 0.12f)
        while (label.measureText(s.label) > colW - 12f && label.textSize > 16f) label.textSize -= 1f
        cv.drawText(s.label, x, top + 104f, label)
    }
}

private fun drawProfile(cv: Canvas, profile: List<ProfilePoint>, r: RectF, totalKm: String, theme: PosterTheme) {
    val total = profile.last().distM.takeIf { it > 0 } ?: return
    val eMin = profile.minOf { it.eleM }
    val eMax = profile.maxOf { it.eleM }
    // flache Touren nicht kuenstlich aufblasen: mind. 150 m Hoehenspanne
    val range = max(eMax - eMin, 150.0)
    val plotTop = r.top + 50f // Platz fuer die Gipfel-Beschriftung
    fun x(p: ProfilePoint) = (r.left + p.distM / total * r.width()).toFloat()
    fun y(p: ProfilePoint) = (r.bottom - (p.eleM - eMin) / range * (r.bottom - plotTop)).toFloat()

    val line = Path()
    profile.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p), y(p)) else line.lineTo(x(p), y(p)) }
    val area = Path(line).apply {
        lineTo(r.right, r.bottom)
        lineTo(r.left, r.bottom)
        close()
    }
    cv.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, plotTop, 0f, r.bottom,
            withAlpha(theme.accent, 150), withAlpha(theme.accent, 18), Shader.TileMode.CLAMP)
    })
    cv.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 5f
        color = theme.accent
    })
    cv.drawLine(r.left, r.bottom, r.right, r.bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(theme.muted, 160)
        strokeWidth = 2f
    })

    // Hoechster Punkt beschriften
    val peak = profile.maxBy { it.eleM }
    val peakLabel = "${NumberFormat.getIntegerInstance(LOCALE).format(eMax)} m"
    val pp = textPaint(theme.text, 28f, MEDIUM).apply { textAlign = Paint.Align.CENTER }
    val half = pp.measureText(peakLabel) / 2
    val px = x(peak).coerceIn(r.left + half, r.right - half)
    cv.drawText(peakLabel, px, y(peak) - 18f, pp)

    val axis = textPaint(theme.muted, 24f, REGULAR)
    cv.drawText("0", r.left, r.bottom + 40f, axis)
    axis.textAlign = Paint.Align.RIGHT
    cv.drawText(totalKm, r.right, r.bottom + 40f, axis)
}

// ---------------------------------------------------------------- Hintergrundkarte

private fun posterStyleJson(base: BaseMap): String {
    val (tiles, maxZoom) = when (base) {
        // Graue Landeskarte: ruhiger als die farbige
        BaseMap.SWISSTOPO -> "\"https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-grau/default/current/3857/{z}/{x}/{y}.jpeg\"" to 18
        BaseMap.OPENTOPO -> listOf("a", "b", "c")
            .joinToString(",") { "\"https://$it.tile.opentopomap.org/{z}/{x}/{y}.png\"" } to 17
    }
    return """
    {
      "version": 8,
      "sources": { "base": { "type": "raster", "tiles": [$tiles], "tileSize": 256, "maxzoom": $maxZoom } },
      "layers": [
        { "id": "background", "type": "background", "paint": { "background-color": "#FFFFFF" } },
        { "id": "base", "type": "raster", "source": "base" }
      ]
    }
    """.trimIndent()
}

/** Rendert die Karte fuer den Kartenbereich des Posters (muss auf dem Main-Thread starten). */
suspend fun loadPosterMap(context: Context, layout: PosterLayout, track: List<GeoPoint>): PosterMap =
    suspendCancellableCoroutine { cont ->
        val r = layout.mapRect
        val nw = layout.toGeo(r.left, r.top)
        val se = layout.toGeo(r.right, r.bottom)
        val base = defaultBaseMap(track)
        val attribution = when (base) {
            BaseMap.SWISSTOPO -> "Karte © swisstopo"
            BaseMap.OPENTOPO -> "Karte © OpenStreetMap-Mitwirkende, © OpenTopoMap (CC-BY-SA)"
        }
        val options = MapSnapshotter.Options(r.width().toInt(), r.height().toInt())
            .withStyleBuilder(Style.Builder().fromJson(posterStyleJson(base)))
            .withRegion(LatLngBounds.from(nw.lat, se.lon, se.lat, nw.lon))
            .withPixelRatio(1f)
            .withLogo(false)
        val snapshotter = MapSnapshotter(context, options)
        snapshotter.start(
            object : MapSnapshotter.SnapshotReadyCallback {
                override fun onSnapshotReady(snapshot: MapSnapshot) {
                    if (!cont.isActive) return
                    // Track ueber die Karte projizieren, damit er exakt auf der Karte liegt
                    val sx = r.width() / snapshot.bitmap.width
                    val sy = r.height() / snapshot.bitmap.height
                    val px = track.map {
                        val p = snapshot.pixelForLatLng(LatLng(it.lat, it.lon))
                        PointF(r.left + p.x * sx, r.top + p.y * sy)
                    }
                    cont.resume(PosterMap(snapshot.bitmap, px, attribution))
                }
            },
            object : MapSnapshotter.ErrorHandler {
                override fun onError(error: String) {
                    if (cont.isActive) cont.resumeWithException(IOException(error))
                }
            }
        )
        cont.invokeOnCancellation { snapshotter.cancel() }
    }
