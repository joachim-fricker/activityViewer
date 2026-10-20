package de.jfricker.activityviewer.data

import de.jfricker.activityviewer.data.local.ActivityDao
import de.jfricker.activityviewer.data.remote.GitHubClient
import de.jfricker.activityviewer.data.remote.GpxParser
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.boundingBox
import de.jfricker.activityviewer.domain.boxAround
import de.jfricker.activityviewer.domain.decodePolyline
import de.jfricker.activityviewer.domain.distanceToTrackM
import de.jfricker.activityviewer.domain.encodePolyline
import de.jfricker.activityviewer.domain.isIndoor
import de.jfricker.activityviewer.domain.simplify
import de.jfricker.activityviewer.data.remote.WorkflowClient
import de.jfricker.activityviewer.data.remote.WorkflowRun
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class SyncResult(val remoteTotal: Int, val imported: Int, val failed: Int)

/** Treffer der Umkreissuche: Abstand vom Suchpunkt zum Track und der (grobe) Track. */
data class NearbyHit(val id: Long, val distanceM: Double, val track: List<GeoPoint>)

class ActivityRepository(
    private val client: GitHubClient,
    private val workflow: WorkflowClient,
    private val dao: ActivityDao
) {
    fun count() = dao.count()

    fun yearTypeSums(cutoffKey: Int) = dao.yearTypeSums(cutoffKey)

    fun seasonTypeSums(types: List<String>, seasonStartMonth: Int, cutoffKey: Int) =
        dao.seasonTypeSums(types, seasonStartMonth, cutoffKey)

    fun activityRows() = dao.activityRows()

    fun activity(id: Long) = dao.activity(id)

    /**
     * Laedt die GPX einer Aktivitaet von GitHub und liefert den vereinfachten Track
     * (nur fuer die Anzeige, wird nicht gespeichert). Leere Liste = kein GPS-Track vorhanden.
     */
    suspend fun track(id: Long): List<GeoPoint> {
        val gpx = client.fetchGpx(id) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            try {
                simplify(GpxParser.parse(gpx), TRACK_TOLERANCE_M)
            } catch (e: XmlPullParserException) {
                throw IOException("GPX-Datei nicht lesbar", e)
            }
        }
    }

    /**
     * Startet den GitHub-Workflow (laedt neue Aktivitaeten von Garmin ins Repo)
     * und wartet, bis er fertig ist. Wirft IOException bei Fehlern oder Zeitueberschreitung.
     */
    suspend fun runGarminWorkflow(token: String, onElapsed: (seconds: Int) -> Unit) {
        // Hoechste bekannte Run-ID merken, damit wir unseren eigenen neuen Lauf erkennen.
        val before = workflow.listRuns(token).maxOfOrNull { it.id } ?: 0L
        workflow.dispatch(token)

        val start = System.nanoTime()
        var run: WorkflowRun? = null
        var polls = 0
        while (run == null || run.status != "completed") {
            if (++polls > MAX_POLLS) {
                throw IOException("Zeitüberschreitung: Workflow nach 5 Minuten nicht beendet")
            }
            delay(POLL_INTERVAL_MS)
            run = workflow.listRuns(token).filter { it.id > before }.maxByOrNull { it.id }
            onElapsed(((System.nanoTime() - start) / 1_000_000_000L).toInt())
        }
        val finished = run ?: throw IOException("Workflow-Lauf nicht gefunden")
        if (finished.conclusion != "success") {
            throw IOException("Workflow endete mit Status „${finished.conclusion}“")
        }
    }

    fun missingTrackIndexCount() = dao.missingTrackIndexCount()

    fun heatTrackRows() = dao.heatTracks()

    /**
     * Track-Index fuer die Umkreissuche aufbauen: fuer jede Aktivitaet ohne Index die GPX laden,
     * stark vereinfachen (100 m, bei Touren ueber 50 km 1 km) und nur Bounding-Box + Kurzform
     * speichern. Indoor-Touren und Aktivitaeten ohne GPS bekommen "" (nicht durchsuchbar).
     */
    suspend fun buildTrackIndex(onProgress: (done: Int, total: Int) -> Unit) {
        val todo = dao.missingTrackIndex()
        if (todo.isEmpty()) return
        val semaphore = Semaphore(4)
        val done = AtomicInteger(0)
        coroutineScope {
            todo.map { a ->
                async {
                    semaphore.withPermit {
                        try {
                            indexOne(a.id, a.typeKey, a.name, a.distanceM)
                        } catch (e: IOException) {
                            // bleibt null und wird beim naechsten Mal erneut versucht
                        }
                        onProgress(done.incrementAndGet(), todo.size)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun indexOne(id: Long, typeKey: String, name: String, distanceM: Double) {
        if (isIndoor(typeKey, name)) {
            dao.updateTrackIndex(id, null, null, null, null, "")
            return
        }
        val gpx = client.fetchGpx(id)
        val tolerance = if (distanceM > 50_000) INDEX_TOLERANCE_LONG_M else INDEX_TOLERANCE_M
        val points = if (gpx == null) emptyList() else withContext(Dispatchers.Default) {
            try {
                simplify(GpxParser.parse(gpx), tolerance)
            } catch (e: XmlPullParserException) {
                emptyList() // defekte Datei: als "kein Track" markieren
            }
        }
        val box = boundingBox(points)
        dao.updateTrackIndex(id, box?.minLat, box?.maxLat, box?.minLon, box?.maxLon, encodePolyline(points))
    }

    /** Alle Touren, deren Track naeher als radiusM am Suchpunkt vorbeifuehrt, naechste zuerst. */
    suspend fun nearby(center: GeoPoint, radiusM: Double): List<NearbyHit> {
        val box = boxAround(center, radiusM)
        val candidates = dao.tracksInBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
        return withContext(Dispatchers.Default) {
            candidates.mapNotNull { row ->
                val track = decodePolyline(row.trackSimplified)
                val d = distanceToTrackM(center, track)
                if (d <= radiusM) NearbyHit(row.id, d, track) else null
            }.sortedBy { it.distanceM }
        }
    }

    /** Liest ids.txt, laedt nur fehlende Aktivitaeten. */
    suspend fun sync(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): SyncResult {
        val remote = client.fetchIds().toSet()
        val local = dao.allIds().toSet()
        val missing = (remote - local).toList()

        if (missing.isEmpty()) return SyncResult(remote.size, 0, 0)

        val semaphore = Semaphore(8)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)

        coroutineScope {
            missing.map { id ->
                async {
                    semaphore.withPermit {
                        try {
                            dao.upsert(client.fetchActivity(id).toEntity())
                        } catch (e: IOException) {
                            failed.incrementAndGet()
                        } catch (e: SerializationException) {
                            failed.incrementAndGet()
                        }
                        onProgress(done.incrementAndGet(), missing.size)
                    }
                }
            }.awaitAll()
        }
        return SyncResult(remote.size, missing.size - failed.get(), failed.get())
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4_000L
        const val MAX_POLLS = 75          // ca. 5 Minuten
        const val TRACK_TOLERANCE_M = 5.0 // Vereinfachung: max. Abweichung vom Original
        const val INDEX_TOLERANCE_M = 100.0       // Track-Index fuer die Umkreissuche
        const val INDEX_TOLERANCE_LONG_M = 1000.0 // ... bei Touren ueber 50 km
    }
}
