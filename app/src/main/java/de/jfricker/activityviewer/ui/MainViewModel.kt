package de.jfricker.activityviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.jfricker.activityviewer.data.ActivityRepository
import de.jfricker.activityviewer.data.SyncResult
import de.jfricker.activityviewer.data.TokenStore
import de.jfricker.activityviewer.data.local.ActivityEntity
import de.jfricker.activityviewer.data.local.ActivityRow
import de.jfricker.activityviewer.domain.YearOverview
import de.jfricker.activityviewer.domain.buildOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface SyncState {
    data object Idle : SyncState
    data object WorkflowStarting : SyncState
    data class WorkflowRunning(val seconds: Int) : SyncState
    data object Checking : SyncState
    data class Loading(val done: Int, val total: Int) : SyncState
    /** warning: z. B. wenn der Garmin-Abruf fehlschlug, der interne Sync aber trotzdem lief. */
    data class Finished(val result: SyncResult, val warning: String? = null) : SyncState
    data class Error(val message: String) : SyncState

    val busy: Boolean
        get() = this is WorkflowStarting || this is WorkflowRunning ||
            this is Checking || this is Loading
}

class MainViewModel(
    private val repo: ActivityRepository,
    private val tokenStore: TokenStore
) : ViewModel() {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _hasToken = MutableStateFlow(tokenStore.get() != null)
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    val count: StateFlow<Int> =
        repo.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val overview: StateFlow<List<YearOverview>> =
        repo.yearTypeSums()
            .map { buildOverview(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activities: StateFlow<List<ActivityRow>> =
        repo.activityRows()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun activity(id: Long): Flow<ActivityEntity?> = repo.activity(id)

    // Beim App-Start nur der interne Sync, der Garmin-Workflow laeuft nur auf Knopfdruck.
    init { sync(withGarmin = false) }

    fun syncInternalOnly() = sync(withGarmin = false)

    fun syncWithGarmin() = sync(withGarmin = true)

    fun saveToken(token: String) {
        if (token.isBlank()) return
        tokenStore.set(token)
        _hasToken.value = true
    }

    fun clearToken() {
        tokenStore.clear()
        _hasToken.value = false
    }

    private fun sync(withGarmin: Boolean) {
        if (_state.value.busy) return
        viewModelScope.launch {
            var warning: String? = null

            if (withGarmin) {
                val token = tokenStore.get()
                if (token == null) {
                    warning = "Kein GitHub-Token hinterlegt, Garmin-Abruf übersprungen."
                } else {
                    _state.value = SyncState.WorkflowStarting
                    try {
                        repo.runGarminWorkflow(token) { sec ->
                            _state.value = SyncState.WorkflowRunning(sec)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Interner Sync laeuft trotzdem, damit vorhandene Daten aktuell bleiben.
                        warning = "Garmin-Abruf fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}"
                    }
                }
            }

            _state.value = SyncState.Checking
            _state.value = try {
                val result = repo.sync { done, total ->
                    _state.value = SyncState.Loading(done, total)
                }
                SyncState.Finished(result, warning)
            } catch (e: IOException) {
                SyncState.Error(e.message ?: "Netzwerkfehler")
            }
        }
    }
}
