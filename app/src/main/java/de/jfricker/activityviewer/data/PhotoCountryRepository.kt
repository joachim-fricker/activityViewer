package de.jfricker.activityviewer.data

import de.jfricker.activityviewer.data.local.PhotoLocationDao
import de.jfricker.activityviewer.data.local.PhotoLocationEntity
import android.content.Context
import de.jfricker.activityviewer.domain.COUNTRY_RULES_VERSION
import de.jfricker.activityviewer.domain.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Besuchte Laender anhand der GPS-Positionen in den Fotos.
 * Die Fotos werden einmal gescannt, danach nur noch neue oder geaenderte.
 */
class PhotoCountryRepository(
    context: Context,
    private val photos: PhotoRepository,
    private val dao: PhotoLocationDao,
    val lookup: CountryLookup
) {
    fun stats() = dao.countryStats()

    fun withGpsCount() = dao.withGpsCount()

    /** Gleicht den Cache mit der Galerie ab; liefert die Anzahl neu gelesener Fotos. */
    private val prefs = context.getSharedPreferences("photo_countries", Context.MODE_PRIVATE)

    suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): Int {
        reassignIfRulesChanged()
        val all = photos.allPhotos()
        val known = dao.keys().associate { it.id to it.modified }
        val ids = all.mapTo(HashSet()) { it.id }
        known.keys.filter { it !in ids }.chunked(500).forEach { dao.delete(it) }

        val todo = all.filter { known[it.id] != it.modified }
        if (todo.isEmpty()) return 0
        withContext(Dispatchers.Default) { lookup.countries } // Grenzen einmal laden

        val gate = Semaphore(4)
        var done = 0
        onProgress(0, todo.size)
        for (chunk in todo.chunked(200)) {
            val rows = coroutineScope {
                chunk.map { p ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val pos = photos.positionOf(p.id)
                            PhotoLocationEntity(
                                id = p.id,
                                modified = p.modified,
                                takenMs = p.takenMs,
                                lat = pos?.lat,
                                lon = pos?.lon,
                                country = pos?.let { lookup.countryAt(it.lat, it.lon) }
                            )
                        }
                    }
                }.awaitAll()
            }
            dao.upsertAll(rows)
            done += chunk.size
            onProgress(done, todo.size)
        }
        return todo.size
    }

    /** Neue Grenzregeln: Land fuer alle gespeicherten Positionen neu bestimmen. */
    private suspend fun reassignIfRulesChanged() {
        if (prefs.getInt(KEY_RULES, 0) == COUNTRY_RULES_VERSION) return
        val rows = dao.withPosition()
        val updated = withContext(Dispatchers.Default) {
            rows.map { it.copy(country = lookup.countryAt(it.lat!!, it.lon!!)) }
        }
        updated.chunked(500).forEach { dao.upsertAll(it) }
        prefs.edit().putInt(KEY_RULES, COUNTRY_RULES_VERSION).apply()
    }

    private companion object {
        const val KEY_RULES = "rulesVersion"
    }

    /** Fotos aus einem Land, neueste zuerst. */
    suspend fun photosIn(country: String): List<TourPhoto> =
        dao.inCountry(country).map { r ->
            TourPhoto(r.id, photos.uriOf(r.id), r.takenMs, r.lat?.let { lat -> r.lon?.let { GeoPoint(lat, it) } })
        }

    /** Alles neu einlesen (z. B. nachdem der Zugriff auf die Foto-Positionen erlaubt wurde). */
    suspend fun reset() = dao.clear()
}
