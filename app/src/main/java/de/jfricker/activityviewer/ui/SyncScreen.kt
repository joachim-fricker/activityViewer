package de.jfricker.activityviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SyncScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val hasToken by vm.hasToken.collectAsStateWithLifecycle()
    var tokenInput by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Aktivitäten in der Datenbank: $count", style = MaterialTheme.typography.titleMedium)

        when (val s = state) {
            is SyncState.Idle -> Unit
            is SyncState.WorkflowStarting -> {
                CircularProgressIndicator()
                Text("Starte Garmin-Abruf auf GitHub …")
            }
            is SyncState.WorkflowRunning -> {
                CircularProgressIndicator()
                Text("Garmin-Abruf läuft … ${s.seconds} s")
            }
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
                s.warning?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
            is SyncState.Error -> Text("Fehler: ${s.message}", color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = vm::syncWithGarmin,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Garmin abrufen & synchronisieren") }

        OutlinedButton(
            onClick = vm::syncInternalOnly,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Nur Sync (ohne Garmin)") }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text("GitHub-Token", style = MaterialTheme.typography.titleSmall)
        if (hasToken) {
            Text("Ein Token ist hinterlegt.")
            TextButton(onClick = vm::clearToken) { Text("Token entfernen") }
        } else {
            Text(
                "Zum Starten des Workflows nötig (Fine-grained Token, nur Repo myactivities, " +
                    "Berechtigung „Actions: Read and write“).",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("Token einfügen") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    vm.saveToken(tokenInput)
                    tokenInput = ""
                },
                enabled = tokenInput.isNotBlank()
            ) { Text("Speichern") }
        }
    }
}
