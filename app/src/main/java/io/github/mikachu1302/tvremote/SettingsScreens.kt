package io.github.mikachu1302.tvremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(tv: PairedTv, vm: RemoteViewModel, onBack: () -> Unit, onApps: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        Section("Remote behavior")
        SwitchRow("Haptic feedback", "A light vibration when remote buttons are pressed.", settings.haptics) {
            vm.updateSettings { s -> s.copy(haptics = it) }
        }
        SwitchRow(
            "Smooth press",
            "Holding volume, channel or a direction acts like holding a key on a physical remote.",
            settings.smoothPress,
        ) { vm.updateSettings { s -> s.copy(smoothPress = it) } }
        SwitchRow("Keep screen awake", "The screen stays on while the remote is open.", settings.keepAwake) {
            vm.updateSettings { s -> s.copy(keepAwake = it) }
        }
        SwitchRow("Theater mode", "Dims the screen when idle, to save battery and reduce glare while watching.", settings.theater) {
            vm.updateSettings { s -> s.copy(theater = it) }
        }

        Section("Applications")
        TextButton(onClick = onApps, modifier = Modifier.fillMaxWidth()) {
            Text("Manage app shortcuts (${apps.size})", Modifier.fillMaxWidth())
        }

        Section("TV")
        Text(tv.name, fontWeight = FontWeight.Medium)
        OutlinedButton(onClick = vm::forget, modifier = Modifier.padding(top = 8.dp)) { Text("Pair another TV") }

        Section("About")
        Text("TV Remote $version", fontWeight = FontWeight.Medium)
        Text(
            "No ads, no tracking, no accounts. The app only talks to your TV on your own network.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
    Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ManageAppsScreen(vm: RemoteViewModel, onBack: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val state by vm.tvState.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("App shortcuts", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Tiles in the row above the D-pad. Each opens an app on the TV.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
        )

        apps.forEachIndexed { i, app ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AppTile(app.name, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(app.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        app.link, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.moveApp(i, -1) }, enabled = i > 0) { Text("↑") }
                TextButton(onClick = { vm.moveApp(i, 1) }, enabled = i < apps.lastIndex) { Text("↓") }
                TextButton(onClick = { vm.removeApp(i) }) { Text("Remove") }
            }
        }

        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = state.appLabel ?: state.appPackage?.let(::prettyPackage)
            Button(
                onClick = { vm.addCurrentApp() },
                enabled = state.appPackage != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (current != null) "Add the app open on the TV ($current)" else "Add the app open on the TV") }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add custom shortcut…") }
            TextButton(onClick = { vm.resetApps() }, modifier = Modifier.fillMaxWidth()) { Text("Reset to defaults") }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add shortcut") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(
                        link, { link = it.trim() }, singleLine = true,
                        label = { Text("Link or market://launch?id=package") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && link.isNotBlank(),
                    onClick = {
                        vm.addApp(AppShortcut(name.trim(), link))
                        adding = false
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
