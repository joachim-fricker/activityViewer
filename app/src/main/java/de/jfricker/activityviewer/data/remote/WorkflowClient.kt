package de.jfricker.activityviewer.data.remote

import de.jfricker.activityviewer.data.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class WorkflowRun(
    val id: Long,
    val status: String,
    val conclusion: String? = null
)

@Serializable
private data class RunsResponse(
    @SerialName("workflow_runs") val runs: List<WorkflowRun> = emptyList()
)

/** Startet den GitHub-Actions-Workflow und fragt dessen Laeufe ab. */
class WorkflowClient(private val http: OkHttpClient = OkHttpClient()) {

    private val json = Json { ignoreUnknownKeys = true }
    private val base = "${Config.API_BASE}/actions/workflows/${Config.WORKFLOW_FILE}"

    /** Die letzten Laeufe des Workflows, neueste zuerst. */
    suspend fun listRuns(token: String): List<WorkflowRun> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/runs?per_page=5")
            .authed(token)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw apiError(response.code)
            val text = response.body?.string() ?: throw IOException("Leere Antwort von GitHub")
            try {
                json.decodeFromString<RunsResponse>(text).runs
            } catch (e: SerializationException) {
                throw IOException("Unerwartete Antwort von GitHub")
            }
        }
    }

    /** Loest den Workflow manuell aus (workflow_dispatch). GitHub antwortet mit 204. */
    suspend fun dispatch(token: String) = withContext(Dispatchers.IO) {
        val body = """{"ref":"${Config.BRANCH}"}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/dispatches")
            .post(body)
            .authed(token)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code != 204) throw apiError(response.code)
        }
    }

    private fun Request.Builder.authed(token: String): Request.Builder =
        header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

    private fun apiError(code: Int): IOException = IOException(
        when (code) {
            401 -> "Token ungültig oder abgelaufen (401)"
            403 -> "Token ohne Berechtigung „Actions: Read and write“ oder Limit erreicht (403)"
            404 -> "Workflow nicht gefunden oder Token ohne Zugriff auf das Repo (404)"
            422 -> "Workflow lässt sich nicht manuell starten, workflow_dispatch fehlt? (422)"
            else -> "GitHub antwortete mit HTTP $code"
        }
    )
}
