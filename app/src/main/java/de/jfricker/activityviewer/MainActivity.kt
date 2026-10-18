package de.jfricker.activityviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.jfricker.activityviewer.ui.MainViewModel
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
                    SyncScreen(viewModel)
                }
            }
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
