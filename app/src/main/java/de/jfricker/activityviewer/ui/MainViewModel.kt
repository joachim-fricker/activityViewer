package de.jfricker.activityviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.jfricker.activityviewer.data.ActivityRepository
import de.jfricker.activityviewer.data.LocationProvider
import de.jfricker.activityviewer.data.NearbyHit
import de.jfricker.activityviewer.data.NetworkInfo
import de.jfricker.activityviewer.data.PhotoAccess
import de.jfricker.activityviewer.data.PhotoCountryRepository
import de.jfricker.activityviewer.data.PhotoRepository
import de.jfricker.activityviewer.data.TourPhoto
import de.jfricker.activityviewer.data.PosterTrack
import de.jfricker.activityviewer.data.SyncResult
import de.jfricker.activityviewer.data.TokenStore
import de.jfricker.activityviewer.data.local.ActivityEntity
import de.jfricker.activityviewer.data.local.ActivityRow
import de.jfricker.activityviewer.data.local.CountryStat
import de.jfricker.activityviewer.data.local.RecordRow
import de.jfricker.activityviewer.domain.FULL_PERIOD
import de.jfricker.activityviewer.domain.GeoPoint
import de.jfricker.activityviewer.domain.EXCLUDED_COUNTRIES
import de.jfricker.activityviewer.domain.HeatTrack
import de.jfricker.activityviewer.domain.MANUAL_VISITED_COUNTRIES
import de.jfricker.activityviewer.domain.TimedPoint
import de.jfricker.activityviewer.domain.RouteGroup
import de.jfricker.activityviewer.domain.RouteInput
import de.jfricker.activityviewer.domain.findRepeatedRoutes
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
import kotlinx.coroutines.flow.combine
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
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
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

sealed interface CountryScan {
    data object Idle : CountryScan
    data class Running(val done: Int, val total: Int) : CountryScan
    data class Error(val message: String) : CountryScan
}

sealed interface TrackState {
    data object Loading : TrackState
    /** timeline: Punkte mit Zeitstempel, nur wenn mit withTimeline angefordert (fuer die Fotos). */
    data class Loaded(val points: List<GeoPoint>, val timeline: List<TimedPoint> = emptyList()) : TrackState
    data object NoTrack : TrackState
    data class Error(val message: String) : TrackState
}

class MainViewModel(
    private val repo: ActivityRepository,
    private val tokenStore: TokenStore,
    private val location: LocationProvider,
    private val network: NetworkInfo,
    private val photos: PhotoRepository,
    private val countries: PhotoCountryRepository
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

    fun track(id: Long, withTimeline: Boolean = false): Flow<TrackState> = flow {
        emit(TrackState.Loading)
        val result = try {
            val (points, timeline) = if (withTimeline) repo.trackWithTimeline(id) else repo.track(id) to emptyList<TimedPoint>()
            if (points.isEmpty()) TrackState.NoTrack else TrackState.Loaded(points, timeline)
        } catch (e: IOException) {
            TrackState.Error(e.message ?: "Netzwerkfehler")
        }
        emit(result)
    }

    // ------------------------------------------------------------ Fotos vom Handy

    fun photoAccess(): PhotoAccess = photos.access()

    suspend fun photosBetween(window: LongRange): List<TourPhoto> = photos.photosBetween(window)

    suspend fun photoThumbnail(photo: TourPhoto): Bitmap? = photos.thumbnail(photo, PhotoRepository.THUMB_PX)

    suspend fun photoMapIcon(photo: TourPhoto, sizePx: Int): Bitmap? = photos.mapIcon(photo, sizePx)

    fun hasPhotoLocationAccess(): Boolean = photos.hasLocationAccess()

    // ------------------------------------------------------------ Laenderkarte (GPS aus den Fotos)

    val countryStats: StateFlow<List<CountryStat>> =
        countries.stats()
            .map { stats ->
                // Fotos aus ausgeschlossenen Laendern ignorieren, manuell eingetragene ergaenzen (cnt = 0)
                val fromPhotos = stats.filter { it.country !in EXCLUDED_COUNTRIES }
                val known = fromPhotos.mapTo(HashSet()) { it.country }
                fromPhotos + (MANUAL_VISITED_COUNTRIES - known).map { CountryStat(it, 0, 0L, 0L) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val photoGpsCount: StateFlow<Int> =
        countries.withGpsCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _countryScan = MutableStateFlow<CountryScan>(CountryScan.Idle)
    val countryScan: StateFlow<CountryScan> = _countryScan.asStateFlow()

    /** Kameraposition der Laenderkarte, damit sie beim Tab-Wechsel erhalten bleibt. */
    var countryCamera: CameraPosition? = null

    /** Neue/geaenderte Fotos einlesen; laeuft im ViewModel weiter, auch wenn der Tab gewechselt wird. */
    fun scanPhotoCountries() {
        if (_countryScan.value is CountryScan.Running) return
        _countryScan.value = CountryScan.Running(0, 0)
        viewModelScope.launch {
            _countryScan.value = try {
                countries.scan { done, total -> _countryScan.value = CountryScan.Running(done, total) }
                CountryScan.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CountryScan.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun countriesGeoJson(): String = withContext(Dispatchers.IO) { countries.lookup.geoJson }

    suspend fun countryNames(): Map<String, String> = withContext(Dispatchers.Default) { countries.lookup.names }

    suspend fun countryPhotos(country: String): List<TourPhoto> = countries.photosIn(country)

    suspend fun photoFull(photo: TourPhoto, maxSide: Int): Bitmap? = photos.fullImage(photo, maxSide)

    /** Track mit Hoehenprofil fuer das Tour-Poster (wirft IOException bei Netzwerkfehlern). */
    suspend fun posterTrack(id: Long): PosterTrack = repo.posterTrack(id)

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

    /** Mehrfach gemachte Strecken; null = Tracks noch nicht geladen. */
    val repeatedRoutes: StateFlow<List<RouteGroup>?> =
        combine(heatTracks, activities) { tracks, rows ->
            if (tracks == null) return@combine null
            val byId = rows.associateBy { it.id }
            findRepeatedRoutes(
                tracks.mapNotNull { t ->
                    byId[t.id]?.let { RouteInput(t.id, t.typeKey, it.distanceM, it.startTime, t.points) }
                }
            )
        }
            .conflate()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------------------------------------------------------------- Rekorde

    val records: StateFlow<List<RecordRow>> =
        repo.recordRows().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Radtouren ohne berechnete Bestzeiten (5/10/20 km). */
    val missingBestEfforts: StateFlow<Int> =
        repo.missingBestEffortsCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _effortProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val effortProgress: StateFlow<Pair<Int, Int>?> = _effortProgress.asStateFlow()
    private var effortJob: Job? = null

    /** Bestzeiten nachrechnen (GPX laden). Automatisch nur im WLAN, mit force auch mobil. */
    fun buildBestEfforts(force: Boolean = false) {
        if (effortJob?.isActive == true) return
        if (!force && !network.isUnmetered()) return
        effortJob = viewModelScope.launch {
            try {
                repo.backfillDeviceIds()
                repo.buildBestEfforts { done, total -> _effortProgress.value = done to total }
            } catch (e: IOException) {
                // naechstes Mal erneut
            } finally {
                _effortProgress.value = null
            }
        }
    }

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
            // Geraet (deviceId) bestehender Radtouren nachladen - nur kleine JSONs, auch mobil
            try {
                repo.backfillDeviceIds()
            } catch (e: IOException) {
                // naechstes Mal erneut
            }
            // Danach Tracks neuer Aktivitaeten fuer die Umkreissuche nachladen (nur im WLAN)
            buildTrackIndex()
        }
    }

    // Am Klassenende, damit alle Felder oben bereits initialisiert sind.
    // Beim App-Start nur der interne Sync, der Garmin-Workflow laeuft nur auf Knopfdruck.
    init { sync(withGarmin = false) }
}
