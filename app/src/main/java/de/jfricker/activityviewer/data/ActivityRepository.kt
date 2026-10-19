package de.jfricker.activityviewer.data

import de.jfricker.activityviewer.data.local.ActivityDao
import de.jfricker.activityviewer.data.remote.GitHubClient
import de.jfricker.activityviewer.data.remote.WorkflowClient
import de.jfricker.activityviewer.data.remote.WorkflowRun
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class SyncResult(val remoteTotal: Int, val imported: Int, val failed: Int)

class ActivityRepository(
    private val client: GitHubClient,
    private val workflow: WorkflowClient,
    private val dao: ActivityDao
) {
    fun count() = dao.count()

    fun yearTypeSums() = dao.yearTypeSums()

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
    }
}
