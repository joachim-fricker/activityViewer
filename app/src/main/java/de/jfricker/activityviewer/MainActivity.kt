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
import androidx.compose.runtime.mutableStateOf
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

/** Ein Menuepunkt: Titel im Tab und der zugehoerige Bildschirm. */
private class MenuEntry(val title: String, val content: @Composable () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(vm: MainViewModel) {
    val overview by vm.overview.collectAsStateWithLifecycle()
    val skiSeasons by vm.skiSeasons.collectAsStateWithLifecycle()
    val untilToday by vm.untilToday.collectAsStateWithLifecycle()

    // Reihenfolge hier = Reihenfolge der Tabs; der erste Eintrag ist beim App-Start offen.
    val menu = listOf(
        MenuEntry("Aktivitäten") { ActivitiesScreen(vm) },
        MenuEntry("Sync") { SyncScreen(vm) },
        MenuEntry("Statistik") { StatsScreen(overview, skiSeasons, untilToday, vm::setUntilToday) },
        MenuEntry("Kalender") { CalendarScreen(vm) },
        MenuEntry("Heatmap") { HeatmapScreen(vm) },
        MenuEntry("Karte") { WorldMapScreen(vm) },
        MenuEntry("In der Nähe") { NearbyScreen(vm) },
        MenuEntry("Rekorde") { RecordsScreen(vm) },
        MenuEntry("Länder") { CountriesScreen(vm) },
        MenuEntry("Strecken") { RoutesScreen(vm) },

    )

    // Gemerkt wird der Titel, nicht die Position - so bleibt die Auswahl auch nach Umsortieren richtig.
    var selectedTitle by rememberSaveable { mutableStateOf(menu.first().title) }
    val index = menu.indexOfFirst { it.title == selectedTitle }.coerceAtLeast(0)

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 0.dp) {
            menu.forEachIndexed { i, entry ->
                Tab(
                    selected = i == index,
                    onClick = { selectedTitle = entry.title },
                    text = { Text(entry.title) }
                )
            }
        }
        menu[index].content()
    }
}
