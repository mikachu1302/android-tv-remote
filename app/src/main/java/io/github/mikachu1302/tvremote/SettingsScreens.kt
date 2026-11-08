package io.github.mikachu1302.tvremote

import androidx.compose.foundation.layout.Arrangement
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

        TvVolumeSection(settings, vm)

        Section("About")
        SectionCard {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("TV Remote $version", fontWeight = FontWeight.Medium)
                Text("No ads. No tracking. No accounts.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The TV's own volume and wake-up: pick the TV once, then the volume keys control it. */
@Composable
private fun TvVolumeSection(settings: RemoteSettings, vm: RemoteViewModel) {
    val tv by vm.audioTv.collectAsStateWithLifecycle()
    val audio by vm.audio.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    Section(
        "TV volume and power",
        "TVs with a UPnP media renderer (Hisense VIDAA has one) let apps read and set their volume over the network, " +
            "so the volume keys control the TV itself and show its level. The first time, the TV asks on screen whether " +
            "this phone may control it: allow it with the TV's remote. To switch the TV on from standby the app sends " +
            "a Wake-on-LAN packet: turn that on in the TV's settings and enter the TV's MAC address here (a network " +
            "address of six hex pairs; the TV lists it under network information).",
    )
    SectionCard {
        val current = tv
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: "No TV chosen", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when {
                    current == null -> "Volume: the stick"
                    audio != null -> "Connected, volume ${audio?.level}"
                    else -> "Not answering (TV off?)"
                }
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TTextButton(onClick = { choosing = true }) { Text(if (current == null) "Choose" else "Change") }
            if (current != null) TTextButton(onClick = { vm.setAudioTv(null) }) { Text("Remove") }
        }
        if (current != null) {
            SwitchRow("Volume keys control the TV", "Off: the volume keys go to the stick again.", settings.tvVolume) {
                vm.updateSettings { s -> s.copy(tvVolume = it) }
            }
            key(current.name) {
                var text by remember { mutableStateOf(current.mac.orEmpty()) }
                val valid = text.isBlank() || Wol.parseMac(text) != null
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (it.isBlank() || Wol.parseMac(it) != null) vm.setWakeAddress(it)
                    },
                    label = { Text("TV MAC address (Wake-on-LAN)") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    isError = !valid,
                    supportingText = if (valid) null else ({ Text("Six pairs of hex digits, like AA:BB:CC:DD:EE:FF") }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
    if (choosing) ChooseAudioTvDialog(vm, onDismiss = { choosing = false })
}

/** Searches the network for TVs with a UPnP renderer; each is chosen with its own button. */
@Composable
private fun ChooseAudioTvDialog(vm: RemoteViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var round by remember { mutableStateOf(0) }
    var found by remember { mutableStateOf<List<AudioTv>?>(null) }
    val previous by vm.audioTv.collectAsStateWithLifecycle()
    LaunchedEffect(round) {
        found = null
        found = runCatching { discoverAudioTvs(context) }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the TV") },
        text = {
            Column {
                val list = found
                when {
                    list == null -> Text("Searching…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    list.isEmpty() -> Text("Nothing found. The TV must be on and on the same Wi-Fi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> list.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.host, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TOutlinedButton(onClick = {
                                // Keep the wake address when the same TV is chosen again.
                                vm.setAudioTv(item.copy(mac = previous?.takeIf { it.name == item.name }?.mac))
                                onDismiss()
                            }) { Text("Use") }
                        }
                    }
                }
            }
        },
        confirmButton = { TTextButton(onClick = { round++ }) { Text("Search again") } },
        dismissButton = { TTextButton(onClick = onDismiss) { Text("Close") } },
    )
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

/**
 * Everything about the app row in one place: your apps (drag to reorder, remove) and the apps to add.
 * A row in the lists does nothing when tapped; only its buttons change anything.
 */
@Composable
fun AppShortcutsScreen(vm: RemoteViewModel, onBack: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val state by vm.tvState.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BackButton(onBack)
            Text("App shortcuts", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        PickerSection("Your apps (${apps.size})")
        if (apps.isEmpty()) {
            Text("None yet. Add some below.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Drag the handle to reorder.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            ReorderableApps(apps, vm)
        }

        // Apps already in your list are not offered again: they are above, with their Remove button.
        // Whatever is open on the TV right now is offered too, even when it is not in the catalog.
        val pkg = state.appPackage
        val open = pkg?.let { shortcutForPackage(it, state.appLabel) }
        if (open != null && apps.none { it.link == open.link }) {
            PickerSection("Open on your TV now")
            CatalogRow(open, subtitle = "Currently open") { vm.addApp(open) }
        }

        PickerSection("Add apps")
        val available = CATALOG.filter { entry -> apps.none { it.link == entry.link } }
        if (available.isEmpty()) {
            Text("All apps are in your list.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        available.forEach { app -> CatalogRow(app, subtitle = null) { vm.addApp(app) } }

        PickerSection("Something else")
        TOutlinedButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth()) { Text("Add by package name or link…") }
        TTextButton(onClick = vm::resetApps, modifier = Modifier.fillMaxWidth()) { Text("Reset to defaults") }
    }

    if (custom) {
        var name by remember { mutableStateOf("") }
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("Add by package name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A package name like com.netflix.ninja, or a web link.", fontSize = 13.sp)
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

/**
 * The app list with drag handles: hold and drag a handle past its neighbours to move the app. Rows are
 * keyed by link, so the dragged row keeps its gesture while the list reorders under it. For TalkBack
 * each row offers "Move up" and "Move down" instead.
 */
@Composable
private fun ReorderableApps(apps: List<AppShortcut>, vm: RemoteViewModel) {
    val colors = MaterialTheme.colorScheme
    val shape = LocalTerm.current.controlShape
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val lastIndex by rememberUpdatedState(apps.lastIndex)

    Column {
        apps.forEachIndexed { index, app ->
            key(app.link) {
                val current by rememberUpdatedState(index)
                val isDragging = dragging == app.link
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset { IntOffset(0, if (isDragging) offset.roundToInt() else 0) }
                        .then(if (isDragging) Modifier.background(colors.surfaceVariant, shape).border(1.dp, colors.primary, shape) else Modifier)
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Move up") { vm.moveApp(current, -1); true },
                                CustomAccessibilityAction("Move down") { vm.moveApp(current, 1); true },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Reorder ${app.name}" }
                            .pointerInput(app.link) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = app.link
                                        offset = 0f
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        offset += drag.y
                                        val target = (current + (offset / rowPx).roundToInt()).coerceIn(0, lastIndex)
                                        if (target != current) {
                                            vm.moveApp(current, target - current)
                                            offset -= (target - current) * rowPx
                                        }
                                    },
                                    onDragEnd = {
                                        dragging = null
                                        offset = 0f
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        offset = 0f
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) { Glyph("grip", colors.onSurfaceVariant, Modifier.size(22.dp)) }
                    AppTile(app.name, Modifier.size(40.dp))
                    Text(
                        app.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    TTextButton(
                        onClick = { vm.removeApp(current) },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.semantics { contentDescription = "Remove ${app.name}" },
                    ) { Text("Remove", fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun PickerSection(title: String) {
    Text(
        title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

/** An app that can be added. Only the button does anything: tapping the row does nothing. */
@Composable
private fun CatalogRow(app: AppShortcut, subtitle: String?, onAdd: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppTile(app.name, Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        TOutlinedButton(
            onClick = onAdd,
            modifier = Modifier.widthIn(min = 96.dp).semantics { contentDescription = "Add ${app.name}" },
        ) { Text("Add") }
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
    IconChoice.entries.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { choice ->
                IconOption(
                    choice.label, choice.iconId ?: Palettes.byId(settings.palette).id, settings.icon == choice,
                    Modifier.weight(1f).fillMaxHeight(),
                ) { vm.updateSettings { it.copy(icon = choice) } }
            }
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
            // Pride shows its six stripes, the others their four main colours.
            val dots = palette.stripes ?: listOf(palette.primary, palette.action, palette.soft, palette.text)
            Row(horizontalArrangement = Arrangement.spacedBy(if (dots.size > 4) 4.dp else 6.dp)) {
                dots.forEach { Box(Modifier.size(if (dots.size > 4) 16.dp else 18.dp).background(it, CircleShape)) }
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
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LauncherPreview(iconId, Modifier.size(64.dp))
            Text(
                label, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2,
                color = if (selected) colors.primary else colors.onSurface,
            )
        }
        if (selected) SelectedMark(Modifier.align(Alignment.TopEnd))
    }
}

/**
 * The real launcher icon, drawn as an adaptive icon through the device's own mask: what you see here
 * is what the home screen shows (including what the mask cuts off).
 */
@Composable
private fun LauncherPreview(iconId: String, modifier: Modifier) {
    val resources = LocalResources.current
    val theme = LocalContext.current.theme
    val packageName = LocalContext.current.packageName
    val drawable = remember(iconId, resources) {
        val id = resources.getIdentifier("ic_launcher_$iconId", "mipmap", packageName)
        if (id == 0) null else resources.getDrawable(id, theme)?.mutate()
    }
    Canvas(modifier) {
        drawable?.let { d ->
            d.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawIntoCanvas { d.draw(it.nativeCanvas) }
        }
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
