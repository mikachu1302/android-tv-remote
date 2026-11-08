package io.github.mikachu1302.tvremote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RemoteScreen(tv: PairedTv, vm: RemoteViewModel, onSettings: () -> Unit, onManageApps: () -> Unit) {
    val link by vm.link.collectAsStateWithLifecycle()
    val state by vm.tvState.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val term = LocalTerm.current

    // Keyboard bar: null = closed, otherwise the id of the session (a new id resets the field).
    var keyboard by remember { mutableStateOf<Int?>(null) }
    var keyboardInitial by remember { mutableStateOf("") }
    var sessions by remember { mutableIntStateOf(0) }
    fun openKeyboard(initial: String) {
        keyboardInitial = initial
        keyboard = ++sessions
    }
    // The TV asked for the keyboard (a text field got focus): open ours with that field's text.
    LaunchedEffect(state.keyboardRequests) {
        if (state.keyboardRequests > 0) openKeyboard(state.textField?.value.orEmpty())
    }
    BackHandler(enabled = keyboard != null) { keyboard = null }

    var keypad by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun micDown() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoice()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val chips = statusChips(link, state, voice, term.palette)
    val currentApp = state.appPackage?.let { shortcutForPackage(it, state.appLabel) }
    val on = state.on == true
    val powerLabel = "Power, " + when (state.on) { true -> "on"; false -> "off"; null -> "state unknown" }
    val volume = state.volume

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (term.crt) {
                CrtHeader(tv, vm, chips, on, powerLabel, onSettings)
            } else {
                GlassHeader(tv, vm, chips, on, powerLabel, onSettings)
            }

            AppRow(apps, currentApp, vm, onManageApps)

            Box(Modifier.fillMaxWidth().height(288.dp), contentAlignment = Alignment.Center) {
                when {
                    keypad -> Keypad(vm)
                    term.crt -> CrtPad(vm)
                    else -> GlassDial(vm)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Rocker(
                    title = when {
                        volume == null -> "VOL"
                        volume.muted -> "MUTED"
                        else -> "VOL ${volume.level}"
                    },
                    upLabel = "Volume up", upKey = Key.VOLUME_UP,
                    downLabel = "Volume down", downKey = Key.VOLUME_DOWN,
                    vm = vm,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (term.crt) 6.dp else 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ClusterKey("KBD", "Keyboard", "kbd", vm, null, active = keyboard != null,
                            onClick = { if (keyboard == null) openKeyboard("") else keyboard = null })
                        ClusterKey("HOME", "Home", "home", vm, Key.HOME)
                        ClusterKey(
                            if (keypad) "PAD" else "NUM", if (keypad) "Show directional pad" else "Show number pad",
                            if (keypad) "dpad" else "pad", vm, null, onClick = { keypad = !keypad },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ClusterKey(
                            "MUTE", "Mute, " + if (volume?.muted == true) "on" else "off",
                            if (volume?.muted == true) "speaker-off" else "speaker", vm, Key.VOLUME_MUTE,
                            active = volume?.muted == true, activeColor = term.palette.action,
                        )
                        ClusterKey(
                            "MIC", "Voice search, hold to talk", "mic", vm, null,
                            active = voice == VoiceUi.Listening, activeColor = term.palette.action,
                            onDown = ::micDown, onUp = vm::stopVoice,
                        )
                        ClusterKey("BACK", "Back", "back", vm, Key.BACK)
                    }
                    RemoteButton(
                        "Play or pause", Key.PLAY_PAUSE, vm, Modifier.fillMaxWidth().height(56.dp),
                        look = Look.Primary, shape = if (term.crt) null else RoundedCornerShape(28.dp),
                    ) { color ->
                        if (term.crt) Text("PLAY / PAUSE", color = color, fontSize = 17.sp, style = glowStyle(color))
                        else Glyph("play", color, Modifier.size(26.dp))
                    }
                }
                Rocker("CH", "Channel up", Key.CHANNEL_UP, "Channel down", Key.CHANNEL_DOWN, vm)
            }
        }
        keyboard?.let { id ->
            key(id) {
                KeyboardBar(
                    initial = keyboardInitial,
                    label = state.textField?.label.orEmpty(),
                    vm = vm,
                    onClose = { keyboard = null },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ status

private data class Chip(val text: String, val color: Color, val dot: Boolean = true)

private fun statusChips(link: Link, state: TvState, voice: VoiceUi, p: Palette): List<Chip> = buildList {
    when (voice) {
        VoiceUi.Listening -> add(Chip("listening…", p.action))
        VoiceUi.Starting -> add(Chip("starting voice…", p.dim))
        VoiceUi.Unavailable -> add(Chip("voice not available right now", p.warn))
        VoiceUi.Idle -> Unit
    }
    when (link) {
        Link.Connected -> {
            add(Chip("connected", p.primary))
            when (state.on) {
                true -> add(Chip("tv on", p.soft))
                false -> add(Chip("tv off", p.dim))
                null -> Unit
            }
        }
        Link.Connecting -> add(Chip("connecting…", p.dim))
        Link.Retrying -> add(Chip("can't reach the TV, retrying (tap to retry now)", p.warn))
        Link.PinChanged -> add(Chip("certificate changed, pair again", p.warn))
        Link.Idle -> add(Chip("not connected", p.dim))
    }
    state.appPackage?.let { add(Chip(shortcutForPackage(it, state.appLabel).name, p.action, dot = false)) }
}

/** Text glow for the CRT look. */
fun glowStyle(color: Color) = TextStyle(shadow = Shadow(color.copy(alpha = 0.75f), Offset.Zero, 14f))

private fun statusLine(chips: List<Chip>, separator: Color): AnnotatedString = buildAnnotatedString {
    chips.forEachIndexed { i, chip ->
        if (i > 0) withStyle(SpanStyle(color = separator)) { append("  ·  ") }
        withStyle(SpanStyle(color = chip.color)) { append(chip.text.uppercase()) }
    }
}

// ------------------------------------------------------------------ headers

@Composable
private fun CrtHeader(
    tv: PairedTv, vm: RemoteViewModel, chips: List<Chip>, on: Boolean,
    powerLabel: String, onSettings: () -> Unit,
) {
    val p = LocalTerm.current.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        RemoteButton("Settings", null, vm, Modifier.size(52.dp), onClick = onSettings) {
            Text("SET", color = it, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).clickable(onClick = vm::retry)) {
            TvSwitcher(tv, vm)
            Text(statusLine(chips, p.dim), fontSize = 14.sp, lineHeight = 16.sp)
        }
        RemoteButton(powerLabel, Key.POWER, vm, Modifier.size(52.dp), active = on) {
            Text("PWR", color = it, fontSize = 13.sp, style = if (on) glowStyle(it) else TextStyle())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlassHeader(
    tv: PairedTv, vm: RemoteViewModel, chips: List<Chip>, on: Boolean,
    powerLabel: String, onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().pane(RoundedCornerShape(20.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteButton("Settings", null, vm, Modifier.size(46.dp), shape = CircleShape, onClick = onSettings) {
            Glyph("gear", it, Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).clickable(onClick = vm::retry)) {
            TvSwitcher(tv, vm)
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                chips.forEach { StatusChip(it) }
            }
        }
        RemoteButton(powerLabel, Key.POWER, vm, Modifier.size(46.dp), shape = CircleShape, active = on) {
            Glyph("power", it, Modifier.size(22.dp))
        }
    }
}

@Composable
private fun StatusChip(chip: Chip) {
    Row(
        Modifier
            .background(chip.color.copy(alpha = 0.12f), CircleShape)
            .border(1.dp, chip.color.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chip.dot) {
            Box(Modifier.padding(end = 5.dp).size(6.dp).background(chip.color, CircleShape))
        }
        Text(chip.text, color = chip.color, fontSize = 12.sp, lineHeight = 14.sp)
    }
}

/** The TV's name; tap it to switch to another paired TV or add one. */
@Composable
private fun TvSwitcher(active: PairedTv, vm: RemoteViewModel) {
    val term = LocalTerm.current
    val p = term.palette
    val tvs by vm.tvs.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .semantics { contentDescription = "TV: ${active.name}. Switch TV"; role = Role.Button }
                .clickable { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val nameColor = if (term.crt) p.primary else p.text
            Text(
                if (term.crt) "> ${active.name}" else active.name,
                color = nameColor, fontWeight = FontWeight.Bold, fontSize = if (term.crt) 15.sp else 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                style = if (term.crt) glowStyle(nameColor) else TextStyle(),
            )
            Glyph("down", nameColor, Modifier.padding(start = 4.dp).size(16.dp), strokeWidth = 2.6f)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tvs.forEach { tv ->
                DropdownMenuItem(
                    text = { Text(tv.name) },
                    leadingIcon = { if (tv == active) Glyph("check", p.primary, Modifier.size(18.dp), 2.8f) },
                    onClick = {
                        open = false
                        vm.selectTv(tv)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Add a TV…") },
                onClick = {
                    open = false
                    vm.beginAddTv()
                },
            )
        }
    }
}

// ------------------------------------------------------------------ app row

@Composable
private fun AppRow(apps: List<AppShortcut>, current: AppShortcut?, vm: RemoteViewModel, onManageApps: () -> Unit) {
    val term = LocalTerm.current
    val p = term.palette
    val haptic = LocalHapticFeedback.current
    val haptics = LocalSettings.current.haptics
    val tileSize = if (term.crt) 60.dp else 58.dp
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (term.crt) 8.dp else 10.dp)) {
        items(apps) { app ->
            Column(
                Modifier
                    .width(68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "Open ${app.name} on the TV"; role = Role.Button }
                    .clickable {
                        if (haptics) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        vm.launch(app.link)
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppTile(app.name, Modifier.size(tileSize), current = current?.link == app.link)
                Text(
                    if (term.crt) app.name.uppercase() else app.name,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
        // The last entry of the list configures it: add, remove and reorder apps.
        item {
            val label = if (apps.isEmpty()) "Add apps" else "Edit"
            Column(
                Modifier
                    .width(68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "Edit app shortcuts"; role = Role.Button }
                    .clickable(onClick = onManageApps)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(tileSize).pane(if (term.crt) RoundedCornerShape(0.dp) else RoundedCornerShape(percent = 30)),
                    contentAlignment = Alignment.Center,
                ) { Glyph("gear", p.dim, Modifier.size(26.dp)) }
                Text(
                    if (term.crt) label.uppercase() else label,
                    fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ pads

@Composable
private fun CrtPad(vm: RemoteViewModel) {
    val cell = 92.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RemoteButton("Up", Key.UP, vm, Modifier.size(cell), repeat = true) { Triangle("up", it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RemoteButton("Left", Key.LEFT, vm, Modifier.size(cell), repeat = true) { Triangle("left", it) }
            RemoteButton("OK", Key.CENTER, vm, Modifier.size(cell), look = Look.Action, strong = true) {
                Text("OK", color = it, fontSize = 26.sp, fontWeight = FontWeight.Bold, style = glowStyle(it))
            }
            RemoteButton("Right", Key.RIGHT, vm, Modifier.size(cell), repeat = true) { Triangle("right", it) }
        }
        RemoteButton("Down", Key.DOWN, vm, Modifier.size(cell), repeat = true) { Triangle("down", it) }
    }
}

@Composable
private fun GlassDial(vm: RemoteViewModel) {
    val p = LocalTerm.current.palette
    Box(
        Modifier
            .size(284.dp)
            .drawBehind {
                val r = size.minDimension / 2
                val center = Offset(r, r)
                // Soft glow, then the ring fading between the two accents, then the dark face.
                drawCircle(p.action.copy(alpha = 0.10f), radius = r + 2.dp.toPx(), style = Stroke(16.dp.toPx()))
                drawCircle(p.primary.copy(alpha = 0.10f), radius = r - 2.dp.toPx(), style = Stroke(8.dp.toPx()))
                drawCircle(Brush.radialGradient(listOf(p.surface, p.bg), center = center, radius = r), radius = r - 2.dp.toPx())
                drawCircle(
                    Brush.sweepGradient(listOf(p.primary, p.action, p.primary), center = center),
                    radius = r - 2.dp.toPx(), style = Stroke(4.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        RemoteButton("Up", Key.UP, vm, Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(64.dp), repeat = true, ghost = true, shape = CircleShape) {
            Glyph("up", it, Modifier.size(32.dp), 2.4f)
        }
        RemoteButton("Down", Key.DOWN, vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(64.dp), repeat = true, ghost = true, shape = CircleShape) {
            Glyph("down", it, Modifier.size(32.dp), 2.4f)
        }
        RemoteButton("Left", Key.LEFT, vm, Modifier.align(Alignment.CenterStart).padding(start = 10.dp).size(64.dp), repeat = true, ghost = true, shape = CircleShape) {
            Glyph("left", it, Modifier.size(32.dp), 2.4f)
        }
        RemoteButton("Right", Key.RIGHT, vm, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(64.dp), repeat = true, ghost = true, shape = CircleShape) {
            Glyph("right", it, Modifier.size(32.dp), 2.4f)
        }
        RemoteButton("OK", Key.CENTER, vm, Modifier.size(100.dp), look = Look.Action, strong = true, shape = CircleShape) {
            Text("OK", color = it, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Keypad(vm: RemoteViewModel) {
    val term = LocalTerm.current
    val gap = if (term.crt) 6.dp else 8.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in 0..2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (col in 0..2) {
                    val n = row * 3 + col + 1
                    RemoteButton("Digit $n", Key.DIGIT_0 + n, vm, Modifier.weight(1f).height(60.dp)) {
                        Text("$n", color = it, fontSize = 24.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            RemoteButton("TV input", Key.TV, vm, Modifier.weight(1f).height(60.dp), look = Look.Primary) { Text("TV", color = it, fontSize = 20.sp) }
            RemoteButton("Digit 0", Key.DIGIT_0, vm, Modifier.weight(1f).height(60.dp)) { Text("0", color = it, fontSize = 24.sp) }
            RemoteButton("Menu", Key.MENU, vm, Modifier.weight(1f).height(60.dp), look = Look.Primary) { Text("MENU", color = it, fontSize = 18.sp) }
        }
    }
}

// ------------------------------------------------------------------ bottom controls

/** A key of the 3 x 3 cluster: a text label in the CRT style, an icon in the glass style. */
@Composable
private fun RowScope.ClusterKey(
    label: String,
    description: String,
    glyph: String,
    vm: RemoteViewModel,
    keyCode: Int?,
    active: Boolean = false,
    activeColor: Color? = null,
    onClick: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
) {
    val term = LocalTerm.current
    if (term.crt) {
        RemoteButton(
            description, keyCode, vm, Modifier.weight(1f).height(56.dp), active = active, activeColor = activeColor,
            onClick = onClick, onDown = onDown, onUp = onUp,
        ) { Text(label, color = it, fontSize = 13.sp, maxLines = 1, softWrap = false, style = if (active) glowStyle(it) else TextStyle()) }
    } else {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            RemoteButton(
                description, keyCode, vm, Modifier.size(56.dp), active = active, activeColor = activeColor,
                shape = CircleShape, onClick = onClick, onDown = onDown, onUp = onUp,
            ) { Glyph(glyph, it, Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun Rocker(
    title: String,
    upLabel: String, upKey: Int,
    downLabel: String, downKey: Int,
    vm: RemoteViewModel,
) {
    val term = LocalTerm.current
    val p = term.palette
    if (term.crt) {
        Column(Modifier.width(72.dp).height(184.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            RemoteButton(upLabel, upKey, vm, Modifier.fillMaxWidth().height(66.dp), repeat = true, look = Look.Primary) {
                Text("+", color = it, fontSize = 30.sp, fontWeight = FontWeight.Bold, style = glowStyle(it))
            }
            Text(title, color = p.dim, fontSize = 12.sp, maxLines = 1)
            RemoteButton(downLabel, downKey, vm, Modifier.fillMaxWidth().height(66.dp), repeat = true, look = Look.Primary) {
                Text("−", color = it, fontSize = 30.sp, fontWeight = FontWeight.Bold, style = glowStyle(it))
            }
        }
    } else {
        Column(
            Modifier.width(74.dp).height(188.dp).pane(RoundedCornerShape(37.dp)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
        ) {
            RemoteButton(upLabel, upKey, vm, Modifier.size(60.dp), repeat = true, look = Look.Primary, shape = CircleShape) {
                Text("+", color = it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text(title, color = p.dim, fontSize = 11.sp, maxLines = 1)
            RemoteButton(downLabel, downKey, vm, Modifier.size(60.dp), repeat = true, look = Look.Primary, shape = CircleShape) {
                Text("−", color = it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------------------------ keyboard

/** An invisible character that is always in the field, so backspace has something to delete. */
private const val SENTINEL = "​"

/**
 * Typing here is mirrored on the TV's focused text field, character by character.
 * Docked above the phone's keyboard so the remote stays usable.
 */
@Composable
private fun KeyboardBar(
    initial: String,
    label: String,
    vm: RemoteViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val term = LocalTerm.current
    val p = term.palette
    // What we believe the TV's field holds. The field shows SENTINEL + mirror.
    var mirror by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shown = SENTINEL + mirror
    val shape = if (term.crt) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Surface(
        modifier.fillMaxWidth().imePadding().border(if (term.crt) 2.dp else 1.dp, if (term.crt) p.primary else p.primary.copy(alpha = 0.4f), shape),
        color = p.surface, shape = shape,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = TextFieldValue(shown, selection = TextRange(shown.length)),
                onValueChange = { v ->
                    val raw = v.text
                    val content = if (raw.startsWith(SENTINEL)) raw.drop(1) else raw
                    when {
                        // Backspace over the sentinel with nothing typed: delete on the TV.
                        raw.isEmpty() && mirror.isEmpty() -> vm.press(Key.DEL)
                        content != mirror -> {
                            vm.typed(mirror, content)
                            mirror = content
                        }
                    }
                },
                label = { Text(label.ifBlank { "Type on the TV" }) },
                singleLine = true,
                shape = term.controlShape,
                modifier = Modifier.weight(1f).focusRequester(focus),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    vm.press(Key.ENTER)
                    onClose()
                }),
            )
            TTextButton(onClick = onClose) { Text("Close") }
        }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}
