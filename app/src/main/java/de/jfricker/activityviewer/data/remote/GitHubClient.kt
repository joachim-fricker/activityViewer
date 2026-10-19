package de.jfricker.activityviewer.data.remote

import de.jfricker.activityviewer.data.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class GitHubClient(private val http: OkHttpClient = OkHttpClient()) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchIds(): List<Long> = withContext(Dispatchers.IO) {
        // Der Zeitstempel umgeht den CDN-Cache von raw.githubusercontent.com (ca. 5 Minuten),
        // damit eine frisch vom Workflow aktualisierte ids.txt sofort sichtbar ist.
        get("ids.txt?nocache=${System.currentTimeMillis()}")
            .lineSequence()
            .mapNotNull { it.trim().toLongOrNull() }
            .toList()
    }

    suspend fun fetchActivity(id: Long): ActivityDto = withContext(Dispatchers.IO) {
        json.decodeFromString<ActivityDto>(get("$id.json"))
    }

    /** GPX-Datei als Text; null, wenn es keine gibt (HTTP 404, z. B. Aktivitaet ohne GPS). */
    suspend fun fetchGpx(id: Long): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${Config.RAW_BASE}/$id.gpx").build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("HTTP ${response.code} bei $id.gpx")
                else -> response.body?.string() ?: throw IOException("Leere Antwort bei $id.gpx")
            }
        }
    }

    private fun get(file: String): String {
        val request = Request.Builder()
            .url("${Config.RAW_BASE}/$file")
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} bei $file")
            return response.body?.string() ?: throw IOException("Leere Antwort bei $file")
        }
    }
}
