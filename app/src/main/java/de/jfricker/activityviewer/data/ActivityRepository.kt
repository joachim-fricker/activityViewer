package de.jfricker.activityviewer.data

import de.jfricker.activityviewer.data.local.ActivityDao
import de.jfricker.activityviewer.data.remote.GitHubClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class SyncResult(val remoteTotal: Int, val imported: Int, val failed: Int)

class ActivityRepository(
    private val client: GitHubClient,
    private val dao: ActivityDao
) {
    fun count() = dao.count()

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
}
