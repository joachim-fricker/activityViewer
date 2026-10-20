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
import de.jfricker.activityviewer.ui.MainViewModel
import de.jfricker.activityviewer.ui.NearbyScreen
import de.jfricker.activityviewer.ui.StatsScreen
import de.jfricker.activityviewer.ui.SyncScreen
import de.jfricker.activityviewer.ui.WorldMapScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as ActivityViewerApp
                MainViewModel(app.repository, app.tokenStore, app.locationProvider, app.networkInfo)
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
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Statistik") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Kalender") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Aktivitäten") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Karte") })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("In der Nähe") })
            Tab(selected = tab == 5, onClick = { tab = 5 }, text = { Text("Sync") })
        }
        when (tab) {
            0 -> StatsScreen(overview, skiSeasons, untilToday, vm::setUntilToday)
            1 -> CalendarScreen(vm)
            2 -> ActivitiesScreen(vm)
            3 -> WorldMapScreen(vm)
            4 -> NearbyScreen(vm)
            else -> SyncScreen(vm)
        }
    }
}
