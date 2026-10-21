package de.jfricker.activityviewer.data

import android.content.Context
import de.jfricker.activityviewer.domain.COUNTRY_OVERRIDES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Laendergrenzen aus assets/countries.geojson (Natural Earth 1:50 Mio., gemeinfrei).
 * Properties je Land: id = ADM0_A3 (z. B. CHE), name = deutscher Name.
 */
class CountryLookup(private val context: Context) {

    /** Polygone als flache Arrays [lon0, lat0, lon1, lat1, ...]; erster Ring aussen, weitere = Loecher. */
    class Country(
        val id: String,
        val name: String,
        val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
        val polygons: List<List<DoubleArray>>
    )

    /** Rohes GeoJSON fuer die Karte (beim ersten Zugriff aus den Assets gelesen). */
    val geoJson: String by lazy {
        context.assets.open("countries.geojson").bufferedReader().use { it.readText() }
    }

    val countries: List<Country> by lazy { parse(geoJson) }

    val names: Map<String, String> by lazy { countries.associate { it.id to it.name } }

    /** Land an der Position; Punkte knapp ausserhalb (See, Kueste, grobe Grenze) gehen ans naechste Land. */
    fun countryAt(lat: Double, lon: Double): String? {
        COUNTRY_OVERRIDES.firstOrNull { it.contains(lat, lon) }?.let { return it.country }
        countries.firstOrNull { it.inBox(lat, lon, 0.0) && it.contains(lat, lon) }?.let { return it.id }
        val pad = NEAR_M / M_PER_DEG / max(0.2, cos(lat * PI / 180))
        return countries
            .filter { it.inBox(lat, lon, pad) }
            .map { it to it.distanceM(lat, lon) }
            .filter { it.second <= NEAR_M }
            .minByOrNull { it.second }
            ?.first?.id
    }

    private fun Country.inBox(lat: Double, lon: Double, pad: Double) =
        lat >= minLat - pad && lat <= maxLat + pad && lon >= minLon - pad && lon <= maxLon + pad

    private fun Country.contains(lat: Double, lon: Double) = polygons.any { rings ->
        inRing(rings[0], lat, lon) && (1 until rings.size).none { inRing(rings[it], lat, lon) }
    }

    /** Ray-Casting (gerade/ungerade Anzahl Schnittpunkte). */
    private fun inRing(r: DoubleArray, lat: Double, lon: Double): Boolean {
        var inside = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[2 * i]; val yi = r[2 * i + 1]
            val xj = r[2 * j]; val yj = r[2 * j + 1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Kuerzester Abstand zum Rand des Landes in Metern (lokale ebene Naeherung). */
    private fun Country.distanceM(lat: Double, lon: Double): Double {
        val kx = M_PER_DEG * cos(lat * PI / 180)
        var best = Double.MAX_VALUE
        for (rings in polygons) for (r in rings) {
            for (i in 0 until r.size / 2 - 1) {
                val ax = (r[2 * i] - lon) * kx; val ay = (r[2 * i + 1] - lat) * M_PER_DEG
                val bx = (r[2 * i + 2] - lon) * kx; val by = (r[2 * i + 3] - lat) * M_PER_DEG
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
                best = min(best, hypot(ax + t * dx, ay + t * dy))
            }
        }
        return best
    }

    private fun parse(text: String): List<Country> =
        Json.parseToJsonElement(text).jsonObject["features"]!!.jsonArray.map { f ->
            val props = f.jsonObject["properties"]!!.jsonObject
            val coords = f.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray // MultiPolygon
            var minLon = 180.0; var minLat = 90.0; var maxLon = -180.0; var maxLat = -90.0
            val polygons = coords.map { poly ->
                poly.jsonArray.map { ring ->
                    val pts = ring.jsonArray
                    DoubleArray(pts.size * 2).also { out ->
                        pts.forEachIndexed { i, p ->
                            val xy = p.jsonArray
                            val x = xy[0].jsonPrimitive.double
                            val y = xy[1].jsonPrimitive.double
                            out[2 * i] = x; out[2 * i + 1] = y
                            minLon = min(minLon, x); maxLon = max(maxLon, x)
                            minLat = min(minLat, y); maxLat = max(maxLat, y)
                        }
                    }
                }
            }
            Country(
                props["id"]!!.jsonPrimitive.content,
                props["name"]!!.jsonPrimitive.content,
                minLon, minLat, maxLon, maxLat,
                polygons
            )
        }

    private companion object {
        const val M_PER_DEG = 111_194.93
        const val NEAR_M = 3_000.0
    }
}
