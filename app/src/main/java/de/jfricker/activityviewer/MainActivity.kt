package de.jfricker.activityviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.jfricker.activityviewer.ui.MainViewModel
import de.jfricker.activityviewer.ui.StatsScreen
import de.jfricker.activityviewer.ui.SyncState

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel((application as ActivityViewerApp).repository)
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
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Sync") })
        }
        when (tab) {
            0 -> StatsScreen(overview)
            else -> SyncScreen(vm)
        }
    }
}

@Composable
fun SyncScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Aktivitäten in der Datenbank: $count", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        when (val s = state) {
            is SyncState.Checking -> {
                CircularProgressIndicator()
                Text("Prüfe ids.txt …")
            }
            is SyncState.Loading -> {
                LinearProgressIndicator(
                    progress = { s.done / s.total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Lade ${s.done} / ${s.total}")
            }
            is SyncState.Finished -> {
                val r = s.result
                Text(
                    if (r.imported == 0 && r.failed == 0) "Alles aktuell (${r.remoteTotal} auf GitHub)"
                    else "${r.imported} neu geladen, ${r.failed} fehlgeschlagen"
                )
            }
            is SyncState.Error -> Text("Fehler: ${s.message}")
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = vm::sync) { Text("Erneut prüfen") }
    }
}
