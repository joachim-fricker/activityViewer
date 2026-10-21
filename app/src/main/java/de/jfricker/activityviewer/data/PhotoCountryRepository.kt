package de.jfricker.activityviewer.data

import de.jfricker.activityviewer.data.local.PhotoLocationDao
import de.jfricker.activityviewer.data.local.PhotoLocationEntity
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
    private val photos: PhotoRepository,
    private val dao: PhotoLocationDao,
    val lookup: CountryLookup
) {
    fun stats() = dao.countryStats()

    fun withGpsCount() = dao.withGpsCount()

    /** Gleicht den Cache mit der Galerie ab; liefert die Anzahl neu gelesener Fotos. */
    suspend fun scan(onProgress: (done: Int, total: Int) -> Unit): Int {
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

    /** Alles neu einlesen (z. B. nachdem der Zugriff auf die Foto-Positionen erlaubt wurde). */
    suspend fun reset() = dao.clear()
}
