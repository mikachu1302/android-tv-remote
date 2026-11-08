package io.github.mikachu1302.tvremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
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
fun ManageAppsScreen(vm: RemoteViewModel, onBack: () -> Unit, onAdd: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("App shortcuts", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "The tiles above the D-pad, in this order. Each opens an app on the TV.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
        )

        apps.forEachIndexed { i, app ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AppTile(app.name, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(app.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // The link is only interesting for shortcuts that are not from the list.
                    if (CATALOG.none { it.link == app.link }) {
                        Text(
                            app.link, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                CompactButton("↑", enabled = i > 0) { vm.moveApp(i, -1) }
                CompactButton("↓", enabled = i < apps.lastIndex) { vm.moveApp(i, 1) }
                CompactButton("Remove") { vm.removeApp(i) }
            }
        }

        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add apps…") }
            TextButton(onClick = vm::resetApps, modifier = Modifier.fillMaxWidth()) { Text("Reset to defaults") }
        }
    }
}

@Composable
private fun CompactButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.defaultMinSize(minWidth = 1.dp),
    ) { Text(text, fontSize = 14.sp) }
}

/** Pick apps from a list, like the app this replaces. Tap a row to add it; tap again to remove it. */
@Composable
fun AppPickerScreen(vm: RemoteViewModel, onBack: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val state by vm.tvState.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Add apps", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        // Whatever is open on the TV right now: works for apps that are not in the list.
        val pkg = state.appPackage
        if (pkg != null) {
            val open = shortcutForPackage(pkg, state.appLabel)
            PickerSection("Open on your TV now")
            PickerRow(open, added = apps.any { it.link == open.link }, subtitle = "Currently open") { vm.toggleApp(open) }
        } else {
            Text(
                "Tip: open an app on the TV and it shows up here, even if it is not in the list.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        PickerSection("Apps")
        CATALOG.forEach { app ->
            PickerRow(app, added = apps.any { it.link == app.link }) { vm.toggleApp(app) }
        }

        PickerSection("Something else")
        OutlinedButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth()) { Text("Add by package name or link…") }
    }

    if (custom) {
        var name by remember { mutableStateOf("") }
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("Add by package name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Use the app's Android package name, for example com.netflix.ninja " +
                            "(the id= part of its Play Store link), or a web link the app opens.",
                        fontSize = 13.sp,
                    )
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(link, { link = it.trim() }, label = { Text("Package name or link") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && link.isNotBlank(),
                    onClick = {
                        vm.addApp(AppShortcut(name.trim(), customLink(link)))
                        custom = false
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PickerSection(title: String) {
    Text(
        title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickerRow(app: AppShortcut, added: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = (if (added) "Remove " else "Add ") + app.name; role = Role.Button }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTile(app.name, Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Text as well as colour: "Added" or "Add".
        Text(
            if (added) "✓ Added" else "Add", fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = if (added) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}
