package de.jfricker.activityviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.jfricker.activityviewer.ui.ActivitiesScreen
import de.jfricker.activityviewer.ui.MainViewModel
import de.jfricker.activityviewer.ui.StatsScreen
import de.jfricker.activityviewer.ui.SyncScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as ActivityViewerApp
                MainViewModel(app.repository, app.tokenStore)
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

@Composable
fun AppContent(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val overview by vm.overview.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Statistik") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Aktivitäten") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Sync") })
        }
        when (tab) {
            0 -> StatsScreen(overview)
            1 -> ActivitiesScreen(vm)
            else -> SyncScreen(vm)
        }
    }
}
