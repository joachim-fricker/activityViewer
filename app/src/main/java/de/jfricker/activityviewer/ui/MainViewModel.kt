package de.jfricker.activityviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.jfricker.activityviewer.data.ActivityRepository
import de.jfricker.activityviewer.data.LocationProvider
import de.jfricker.activityviewer.data.NearbyHit
import de.jfricker.activityviewer.data.NetworkInfo
import de.jfricker.activityviewer.data.SyncResult
import de.jfricker.activityviewer.data.TokenStore
import de.jfricker.activityviewer.data.local.ActivityEntity
import de.jfricker.activityviewer.data.local.ActivityRow
import de.jfricker.activityviewer.domain.FULL_PERIOD
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.HeatTrack
import de.jfricker.activityviewer.domain.decodePolyline
import de.jfricker.activityviewer.domain.SKI_SEASON_START_MONTH
import de.jfricker.activityviewer.domain.SKI_TYPES
import de.jfricker.activityviewer.domain.YearOverview
import de.jfricker.activityviewer.domain.buildOverview
import de.jfricker.activityviewer.domain.periodCutoffKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.maplibre.android.camera.CameraPosition
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

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

sealed interface TrackState {
    data object Loading : TrackState
    data class Loaded(val points: List<GeoPoint>) : TrackState
    data object NoTrack : TrackState
    data class Error(val message: String) : TrackState
}

class MainViewModel(
    private val repo: ActivityRepository,
    private val tokenStore: TokenStore,
    private val location: LocationProvider,
    private val network: NetworkInfo
) : ViewModel() {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _hasToken = MutableStateFlow(tokenStore.get() != null)
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    val count: StateFlow<Int> =
        repo.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Jahresvergleich "bis heute": jedes Jahr / jede Saison nur bis zum heutigen Tag. */
    private val _untilToday = MutableStateFlow(false)
    val untilToday: StateFlow<Boolean> = _untilToday.asStateFlow()

    fun setUntilToday(on: Boolean) {
        _untilToday.value = on
    }

    private fun cutoff(on: Boolean, startMonth: Int) =
        if (on) periodCutoffKey(LocalDate.now(), startMonth) else FULL_PERIOD

    @OptIn(ExperimentalCoroutinesApi::class)
    val overview: StateFlow<List<YearOverview>> =
        _untilToday.flatMapLatest { repo.yearTypeSums(cutoff(it, 1)) }
            .map { buildOverview(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Skitouren nach Saison (Sep-Aug) statt Kalenderjahr. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val skiSeasons: StateFlow<List<YearOverview>> =
        _untilToday.flatMapLatest {
            repo.seasonTypeSums(SKI_TYPES, SKI_SEASON_START_MONTH, cutoff(it, SKI_SEASON_START_MONTH))
        }
            .map { buildOverview(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activities: StateFlow<List<ActivityRow>> =
        repo.activityRows()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun activity(id: Long): Flow<ActivityEntity?> = repo.activity(id)

    fun track(id: Long): Flow<TrackState> = flow {
        emit(TrackState.Loading)
        val result = try {
            val points = repo.track(id)
            if (points.isEmpty()) TrackState.NoTrack else TrackState.Loaded(points)
        } catch (e: IOException) {
            TrackState.Error(e.message ?: "Netzwerkfehler")
        }
        emit(result)
    }

    fun hasLocationPermission(): Boolean = location.hasPermission()

    /** Kameraposition der Weltkarte, damit sie beim Tab-Wechsel erhalten bleibt. */
    var worldCamera: CameraPosition? = null

    /** In der Weltkarte geoeffnete Tracks (bleiben sichtbar, z. B. fuer Mehrtagestouren). */
    private val _worldTracks = MutableStateFlow<Map<Long, List<GeoPoint>>>(emptyMap())
    val worldTracks: StateFlow<Map<Long, List<GeoPoint>>> = _worldTracks.asStateFlow()

    fun addWorldTrack(id: Long, points: List<GeoPoint>) {
        _worldTracks.update { it + (id to points) }
    }

    fun clearWorldTracks() {
        bulkJob?.cancel()
        _worldTracks.value = emptyMap()
    }

    /** Fortschritt beim Oeffnen mehrerer Tracks (fertig, gesamt); null = nichts laeuft. */
    private val _bulkProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val bulkProgress: StateFlow<Pair<Int, Int>?> = _bulkProgress.asStateFlow()
    private var bulkJob: Job? = null

    /** Laedt die Tracks aller uebergebenen Aktivitaeten (max. 4 gleichzeitig). */
    fun openWorldTracks(ids: List<Long>) {
        if (bulkJob?.isActive == true) return
        val todo = ids.filter { it !in _worldTracks.value }
        if (todo.isEmpty()) return
        bulkJob = viewModelScope.launch {
            val semaphore = Semaphore(4)
            val done = AtomicInteger(0)
            _bulkProgress.value = 0 to todo.size
            try {
                coroutineScope {
                    todo.forEach { id ->
                        launch {
                            semaphore.withPermit {
                                try {
                                    val points = repo.track(id)
                                    if (points.isNotEmpty()) addWorldTrack(id, points)
                                } catch (e: IOException) {
                                    // einzelne Fehler ignorieren, Rest weiter laden
                                }
                                _bulkProgress.value = done.incrementAndGet() to todo.size
                            }
                        }
                    }
                }
            } finally {
                _bulkProgress.value = null
            }
        }
    }

    fun cancelOpenWorldTracks() {
        bulkJob?.cancel()
    }

    // ---------------------------------------------------------------- Umkreissuche

    /** Anzahl Aktivitaeten ohne Track-Index (noch nicht durchsuchbar). */
    val missingTrackIndex: StateFlow<Int> =
        repo.missingTrackIndexCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Fortschritt beim Aufbau des Track-Index (fertig, gesamt); null = laeuft nicht. */
    private val _indexProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val indexProgress: StateFlow<Pair<Int, Int>?> = _indexProgress.asStateFlow()
    private var indexJob: Job? = null

    /**
     * Laedt fehlende Tracks fuer die Umkreissuche. Automatisch nur im WLAN,
     * mit force = true auch im Mobilnetz.
     */
    fun buildTrackIndex(force: Boolean = false) {
        if (indexJob?.isActive == true) return
        if (!force && !network.isUnmetered()) return
        indexJob = viewModelScope.launch {
            try {
                repo.buildTrackIndex { done, total -> _indexProgress.value = done to total }
            } catch (e: IOException) {
                // z. B. Netz weg; fehlende Tracks werden beim naechsten Mal nachgeladen
            } finally {
                _indexProgress.value = null
            }
        }
    }

    // ---------------------------------------------------------------- Heatmap

    /** Alle indexierten Tracks; null = noch nicht geladen. Aktualisiert sich beim Indexieren. */
    val heatTracks: StateFlow<List<HeatTrack>?> =
        repo.heatTrackRows()
            .conflate()
            .map { rows -> rows.map { HeatTrack(it.id, it.typeKey, decodePolyline(it.trackSimplified)) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun nearby(center: GeoPoint, radiusM: Double): List<NearbyHit> =
        repo.nearby(center, radiusM)

    fun myLocation(): Flow<GeoPoint?> = location.updates()


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
            // Danach Tracks neuer Aktivitaeten fuer die Umkreissuche nachladen (nur im WLAN)
            buildTrackIndex()
        }
    }

    // Am Klassenende, damit alle Felder oben bereits initialisiert sind.
    // Beim App-Start nur der interne Sync, der Garmin-Workflow laeuft nur auf Knopfdruck.
    init { sync(withGarmin = false) }
}
