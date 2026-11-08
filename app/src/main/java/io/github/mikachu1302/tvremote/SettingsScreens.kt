package io.github.mikachu1302.tvremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BackButton(onBack)
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        AppearanceSection(settings, vm)

        Section("Remote behavior")
        SectionCard {
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
        }

        Section("Applications")
        NavRow("App shortcuts", "${apps.size} apps", onApps)

        Section("Connected TVs")
        val tvs by vm.tvs.collectAsStateWithLifecycle()
        SectionCard {
            tvs.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item == tv) Text("In use", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if (item != tv) TTextButton(onClick = { vm.selectTv(item) }) { Text("Use") }
                    TTextButton(onClick = { vm.forgetTv(item) }) { Text("Forget") }
                }
            }
            TOutlinedButton(onClick = vm::beginAddTv, modifier = Modifier.padding(vertical = 8.dp)) { Text("Add a TV…") }
        }

        Section("About")
        SectionCard {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("TV Remote $version", fontWeight = FontWeight.Medium)
                Text("No ads. No tracking. No accounts.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A bordered panel for a section's content, so every section starts with a box at the same distance below its headline. */
@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = LocalTerm.current.controlShape
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        content = content,
    )
}

/** A row that opens another screen: bordered, with a count and a chevron, so it reads as tappable. */
@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = LocalTerm.current.controlShape
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.outline, shape)
            .semantics { contentDescription = "$title, $subtitle"; role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        Glyph("right", colors.primary, Modifier.size(22.dp), strokeWidth = 2.6f)
    }
}

@Composable
private fun Section(title: String, info: String? = null) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 4.dp))
    // A fixed-height header with or without the info icon, so the gap to the content is the same
    // in every section (the icon's touch area overflows the row instead of making it taller).
    Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        if (info != null) InfoButton(open, Modifier.requiredSize(40.dp)) { open = !open }
    }
    if (info != null && open) InfoText(info, Modifier.padding(bottom = 4.dp))
    Spacer(Modifier.height(6.dp))
}

/** A small "i" that unfolds an explanation: keeps the lists short, the detail one tap away. */
@Composable
private fun InfoButton(open: Boolean, modifier: Modifier = Modifier.size(40.dp), onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(CircleShape)
            .semantics { contentDescription = if (open) "Hide details" else "Show details"; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph("info", if (open) colors.primary else colors.onSurfaceVariant, Modifier.size(18.dp))
    }
}

@Composable
private fun InfoText(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun SwitchRow(title: String, info: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                InfoButton(open) { open = !open }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
        if (open) InfoText(info, Modifier.padding(bottom = 8.dp, end = 64.dp))
    }
}

@Composable
fun ManageAppsScreen(vm: RemoteViewModel, onBack: () -> Unit, onAdd: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BackButton(onBack)
            Text("App shortcuts", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Order of the app tiles.",
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
            TButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add apps…") }
            TTextButton(onClick = vm::resetApps, modifier = Modifier.fillMaxWidth()) { Text("Reset to defaults") }
        }
    }
}

@Composable
private fun CompactButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TTextButton(
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BackButton(onBack)
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
                "Open an app on the TV and it shows up here.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        PickerSection("Apps")
        CATALOG.forEach { app ->
            PickerRow(app, added = apps.any { it.link == app.link }) { vm.toggleApp(app) }
        }

        PickerSection("Something else")
        TOutlinedButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth()) { Text("Add by package name or link…") }
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
                        "A package name like com.netflix.ninja, or a web link.",
                        fontSize = 13.sp,
                    )
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(link, { link = it.trim() }, label = { Text("Package name or link") }, singleLine = true)
                }
            },
            confirmButton = {
                TTextButton(
                    enabled = name.isNotBlank() && link.isNotBlank(),
                    onClick = {
                        vm.addApp(AppShortcut(name.trim(), customLink(link)))
                        custom = false
                    },
                ) { Text("Add") }
            },
            dismissButton = { TTextButton(onClick = { custom = false }) { Text("Cancel") } },
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

// ------------------------------------------------------------------ appearance

@Composable
private fun AppearanceSection(settings: RemoteSettings, vm: RemoteViewModel) {
    val colors = MaterialTheme.colorScheme
    val term = LocalTerm.current

    Section("Style")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        UiStyle.entries.forEach { style ->
            val selected = settings.style == style
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(term.controlShape)
                    .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline, term.controlShape)
                    .semantics { contentDescription = style.label + if (selected) ", selected" else ""; role = Role.RadioButton }
                    .clickable { vm.updateSettings { it.copy(style = style) } },
            ) {
                Column(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 30.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(style.label, fontWeight = FontWeight.Bold, color = if (selected) colors.primary else colors.onSurface)
                    Text(style.blurb, fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
                if (selected) SelectedMark(Modifier.align(Alignment.TopEnd))
            }
        }
    }
    if (settings.style == UiStyle.Crt) {
        Spacer(Modifier.height(10.dp))
        SectionCard {
            SwitchRow("Scanlines", "Fine horizontal lines and a darker rim, like an old monitor.", settings.scanlines) {
                vm.updateSettings { s -> s.copy(scanlines = it) }
            }
        }
    }

    Section("Colors")
    Palettes.all.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { palette ->
                PaletteChip(palette, palette.id == settings.palette, Modifier.weight(1f).fillMaxHeight()) {
                    vm.updateSettings { it.copy(palette = palette.id) }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }

    Section(
        "App icon",
        info = "The launcher icon changes a moment after you choose. Your launcher may move it, or briefly show a blank spot on the home screen.",
    )
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconOption("Match colors", Palettes.byId(settings.palette).id, settings.icon == IconChoice.Match, Modifier.weight(1f).fillMaxHeight()) {
            vm.updateSettings { it.copy(icon = IconChoice.Match) }
        }
        IconOption("Pride", "pride", settings.icon == IconChoice.Pride, Modifier.weight(1f).fillMaxHeight()) {
            vm.updateSettings { it.copy(icon = IconChoice.Pride) }
        }
    }
}

/** A palette drawn in its own colours: name, four swatches, a check when selected. */
@Composable
private fun PaletteChip(palette: Palette, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = LocalTerm.current.controlShape
    Box(
        modifier
            .clip(shape)
            .background(palette.bg)
            .border(if (selected) 3.dp else 1.dp, if (selected) palette.primary else palette.border, shape)
            .semantics { contentDescription = palette.name + if (selected) ", selected" else ""; role = Role.RadioButton }
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 30.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(palette.name, color = palette.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(palette.primary, palette.action, palette.soft, palette.text).forEach {
                    Box(Modifier.size(18.dp).background(it, CircleShape))
                }
            }
        }
        if (selected) SelectedMark(Modifier.align(Alignment.TopEnd), palette.primary)
    }
}

/** The check in a selected card's corner (drawn: the font's check mark looks like a root sign). */
@Composable
private fun SelectedMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Glyph("check", color, modifier.padding(8.dp).size(20.dp), strokeWidth = 2.8f)
}

@Composable
private fun IconOption(label: String, iconId: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val term = LocalTerm.current
    Box(
        modifier
            .clip(term.controlShape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline, term.controlShape)
            .semantics { contentDescription = "App icon: $label" + if (selected) ", selected" else ""; role = Role.RadioButton }
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LauncherPreview(iconId, Modifier.size(72.dp))
            Text(label, fontWeight = FontWeight.Medium, color = if (selected) colors.primary else colors.onSurface, maxLines = 1)
        }
        if (selected) SelectedMark(Modifier.align(Alignment.TopEnd))
    }
}

/** The real launcher icon (foreground drawable on its background colour), as the launcher shows it. */
@Composable
private fun LauncherPreview(iconId: String, modifier: Modifier) {
    val context = LocalContext.current
    val resId = remember(iconId) { context.resources.getIdentifier("ic_launcher_fg_$iconId", "drawable", context.packageName) }
    val bg = if (iconId == "pride") Color(0xFF0E0E14) else Palettes.byId(iconId).bg
    Box(modifier.clip(RoundedCornerShape(percent = 28)).background(bg)) {
        if (resId != 0) Image(painterResource(resId), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** The way back from a settings screen: a large icon button (a text link is a small target). */
@Composable
private fun BackButton(onClick: () -> Unit) {
    val term = LocalTerm.current
    val shape = if (term.crt) term.controlShape else CircleShape
    Box(
        Modifier
            .size(52.dp)
            .clip(shape)
            .pane(shape)
            .semantics { contentDescription = "Back"; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph("arrow-left", MaterialTheme.colorScheme.primary, Modifier.size(26.dp), strokeWidth = 2.6f)
    }
}
