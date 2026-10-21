package de.jfricker.activityviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.jfricker.activityviewer.ui.ActivitiesScreen
import de.jfricker.activityviewer.ui.CalendarScreen
import de.jfricker.activityviewer.ui.CountriesScreen
import de.jfricker.activityviewer.ui.HeatmapScreen
import de.jfricker.activityviewer.ui.MainViewModel
import de.jfricker.activityviewer.ui.NearbyScreen
import de.jfricker.activityviewer.ui.RecordsScreen
import de.jfricker.activityviewer.ui.RoutesScreen
import de.jfricker.activityviewer.ui.StatsScreen
import de.jfricker.activityviewer.ui.SyncScreen
import de.jfricker.activityviewer.ui.WorldMapScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as ActivityViewerApp
                MainViewModel(
                    app.repository, app.tokenStore, app.locationProvider, app.networkInfo, app.photoRepository,
                    app.photoCountryRepository
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppContent(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val overview by vm.overview.collectAsStateWithLifecycle()
    val skiSeasons by vm.skiSeasons.collectAsStateWithLifecycle()
    val untilToday by vm.untilToday.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Aktivitäten") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Sync") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Statistik") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Kalender") })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Heatmap") })
            Tab(selected = tab == 5, onClick = { tab = 5 }, text = { Text("In der Nähe") })
            Tab(selected = tab == 6, onClick = { tab = 6 }, text = { Text("Rekorde") })
            Tab(selected = tab == 7, onClick = { tab = 7 }, text = { Text("Karte") })
            Tab(selected = tab == 8, onClick = { tab = 8 }, text = { Text("Strecken") })
            Tab(selected = tab == 9, onClick = { tab = 9 }, text = { Text("Länder") })
        }
        when (tab) {
            0 -> ActivitiesScreen(vm)
            1 -> SyncScreen(vm)
            2 -> StatsScreen(overview, skiSeasons, untilToday, vm::setUntilToday)
            3 -> CalendarScreen(vm)
            4 -> HeatmapScreen(vm)
            5 -> NearbyScreen(vm)
            6 -> RecordsScreen(vm)
            7 -> WorldMapScreen(vm)
            8 -> RoutesScreen(vm)
            else -> CountriesScreen(vm)
        }
    }
}
