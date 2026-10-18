package de.jfricker.activityviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.jfricker.activityviewer.data.ActivityRepository
import de.jfricker.activityviewer.data.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface SyncState {
    data object Checking : SyncState
    data class Loading(val done: Int, val total: Int) : SyncState
    data class Finished(val result: SyncResult) : SyncState
    data class Error(val message: String) : SyncState
}

class MainViewModel(private val repo: ActivityRepository) : ViewModel() {

    private val _state = MutableStateFlow<SyncState>(SyncState.Checking)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    val count: StateFlow<Int> =
        repo.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init { sync() }

    fun sync() {
        if (_state.value is SyncState.Loading) return
        _state.value = SyncState.Checking
        viewModelScope.launch {
            _state.value = try {
                val result = repo.sync { done, total ->
                    _state.value = SyncState.Loading(done, total)
                }
                SyncState.Finished(result)
            } catch (e: IOException) {
                SyncState.Error(e.message ?: "Netzwerkfehler")
            }
        }
    }
}
