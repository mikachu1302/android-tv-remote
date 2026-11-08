package io.github.mikachu1302.tvremote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalSettings = staticCompositionLocalOf { RemoteSettings() }

@Composable
fun RemoteScreen(tv: PairedTv, vm: RemoteViewModel, onSettings: () -> Unit) {
    val link by vm.link.collectAsStateWithLifecycle()
    val state by vm.tvState.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current

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

    val status = when {
        voice == VoiceUi.Listening -> "Listening…"
        voice == VoiceUi.Starting -> "Starting voice…"
        voice == VoiceUi.Unavailable -> "Voice search isn't available on the TV right now"
        link == Link.Connected -> "Connected" + when (state.on) {
            true -> " · TV on"
            false -> " · TV off"
            null -> ""
        }
        link == Link.Connecting -> "Connecting…"
        link == Link.Retrying -> "Can't reach the TV – retrying (tap to retry now)"
        link == Link.PinChanged -> "The TV's certificate changed. Pair again."
        else -> "Not connected"
    }
    val problem = link == Link.Retrying || link == Link.PinChanged || voice == VoiceUi.Unavailable

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: settings, TV and status, power. Status is text, never only colour.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RemoteButton("Settings", null, vm, Modifier.size(52.dp), shape = CircleShape, onClick = onSettings) { GearIcon() }
                Column(Modifier.weight(1f).clickable(onClick = vm::retry).padding(horizontal = 12.dp)) {
                    Text(tv.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        status,
                        fontSize = 13.sp,
                        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (state.appLabel ?: state.appPackage?.let(::prettyPackage))?.let {
                        Text(
                            "Now: $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                RemoteButton(
                    "Power, " + when (state.on) { true -> "on"; false -> "off"; null -> "state unknown" },
                    Key.POWER, vm, Modifier.size(52.dp), shape = CircleShape, active = state.on == true,
                ) { PowerIcon(on = state.on == true) }
            }

            AppRow(apps, vm)

            Box(Modifier.fillMaxWidth().height(252.dp), contentAlignment = Alignment.Center) {
                if (keypad) Keypad(vm) else DPad(vm)
            }

            val volume = state.volume
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Rocker(
                    title = when {
                        volume == null -> "VOL"
                        volume.muted -> "Muted"
                        else -> "VOL ${volume.level}"
                    },
                    upLabel = "Volume up", upKey = Key.VOLUME_UP,
                    downLabel = "Volume down", downKey = Key.VOLUME_DOWN,
                    vm = vm,
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RemoteButton(
                            "Keyboard", null, vm, Modifier.size(54.dp), shape = CircleShape, active = keyboard != null,
                            onClick = { if (keyboard == null) openKeyboard("") else keyboard = null },
                        ) { KeyboardIcon() }
                        RemoteButton("Home", Key.HOME, vm, Modifier.size(54.dp), shape = CircleShape) { Label("⌂") }
                        RemoteButton(
                            if (keypad) "Show directional pad" else "Show number pad",
                            null, vm, Modifier.size(54.dp), shape = CircleShape, onClick = { keypad = !keypad },
                        ) { if (keypad) DpadIcon() else DialpadIcon() }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RemoteButton(
                            "Mute, " + if (volume?.muted == true) "on" else "off",
                            Key.VOLUME_MUTE, vm, Modifier.size(54.dp), shape = CircleShape, active = volume?.muted == true,
                        ) { SpeakerIcon(muted = volume?.muted == true) }
                        RemoteButton(
                            "Voice search, hold to talk", null, vm, Modifier.size(54.dp), shape = CircleShape,
                            active = voice == VoiceUi.Listening, onDown = ::micDown, onUp = vm::stopVoice,
                        ) { MicIcon() }
                        RemoteButton("Back", Key.BACK, vm, Modifier.size(54.dp), shape = CircleShape) { Label("↩") }
                    }
                    RemoteButton(
                        "Play or pause", Key.PLAY_PAUSE, vm, Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(27.dp),
                    ) { Label("⏯") }
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

/** "com.google.android.youtube.tv" -> "youtube.tv": the TV rarely sends a readable app name. */
fun prettyPackage(pkg: String): String =
    pkg.removePrefix("com.google.android.").removePrefix("com.android.").removePrefix("com.").removePrefix("org.")

// ------------------------------------------------------------------ app row

@Composable
private fun AppRow(apps: List<AppShortcut>, vm: RemoteViewModel) {
    if (apps.isEmpty()) {
        Text(
            "No app shortcuts yet – add some in Settings.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val haptic = LocalHapticFeedback.current
    val haptics = LocalSettings.current.haptics
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(apps) { _, app ->
            Column(
                Modifier
                    .width(68.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .semantics { contentDescription = "Open ${app.name} on the TV"; role = Role.Button }
                    .clickable {
                        if (haptics) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        vm.launch(app.link)
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppTile(app.name, Modifier.size(56.dp))
                Text(app.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** No logos (no internet, no trademarks in the repo): a tile in a colour taken from the name. */
@Composable
fun AppTile(name: String, modifier: Modifier = Modifier) {
    val hue = (name.fold(7) { acc, c -> acc * 31 + c.code } and 0x7FFFFFFF) % 360
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.hsv(hue.toFloat(), 0.45f, 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.filter { it.isLetterOrDigit() }.take(2).replaceFirstChar { it.uppercase() },
            color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp,
        )
    }
}

// ------------------------------------------------------------------ pads

@Composable
private fun DPad(vm: RemoteViewModel) {
    val cell = 80.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RemoteButton("Up", Key.UP, vm, Modifier.size(cell), repeat = true) { Label("▲") }
        Row {
            RemoteButton("Left", Key.LEFT, vm, Modifier.size(cell), repeat = true) { Label("◀") }
            RemoteButton("OK", Key.CENTER, vm, Modifier.size(cell), shape = CircleShape, strong = true) {
                Text("OK", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            RemoteButton("Right", Key.RIGHT, vm, Modifier.size(cell), repeat = true) { Label("▶") }
        }
        RemoteButton("Down", Key.DOWN, vm, Modifier.size(cell), repeat = true) { Label("▼") }
    }
}

@Composable
private fun Keypad(vm: RemoteViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in 0..2) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0..2) {
                    val n = row * 3 + col + 1
                    RemoteButton("Digit $n", Key.DIGIT_0 + n, vm, Modifier.weight(1f).height(58.dp)) {
                        Text("$n", fontSize = 24.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            RemoteButton("TV input", Key.TV, vm, Modifier.weight(1f).height(58.dp)) { Text("TV", fontSize = 18.sp) }
            RemoteButton("Digit 0", Key.DIGIT_0, vm, Modifier.weight(1f).height(58.dp)) { Text("0", fontSize = 24.sp) }
            RemoteButton("Menu", Key.MENU, vm, Modifier.weight(1f).height(58.dp)) { Text("Menu", fontSize = 18.sp) }
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RemoteButton(
            upLabel, upKey, vm, Modifier.size(width = 68.dp, height = 62.dp), repeat = true,
            shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
        ) { Label("+") }
        Text(title, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(vertical = 4.dp))
        RemoteButton(
            downLabel, downKey, vm, Modifier.size(width = 68.dp, height = 62.dp), repeat = true,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 34.dp, bottomEnd = 34.dp),
        ) { Label("−") }
    }
}

@Composable
private fun Label(text: String) = Text(text, fontSize = 24.sp, textAlign = TextAlign.Center)

/**
 * One remote button. Fires on press (not release) so it feels immediate.
 * With [repeat], holding it keeps the key going: "smooth press" holds the key
 * down on the TV like a physical remote, otherwise a key press is repeated.
 * A null [keyCode] makes it a plain button ([onClick] on release, [onDown]/[onUp] for hold actions);
 * [active] marks a toggled-on state.
 */
@Composable
private fun RemoteButton(
    description: String,
    keyCode: Int?,
    vm: RemoteViewModel,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    strong: Boolean = false,
    active: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    onClick: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .padding(4.dp)
            .clip(shape)
            .background(
                when {
                    pressed -> colors.primary
                    strong || active -> colors.primaryContainer
                    else -> colors.surfaceVariant
                },
            )
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(keyCode, repeat, settings.smoothPress, settings.haptics) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    if (settings.haptics) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onDown?.invoke()
                    val smooth = repeat && keyCode != null && settings.smoothPress
                    if (keyCode != null) vm.press(keyCode, if (smooth) Direction.StartLong else Direction.Short)
                    val ticker = if (repeat && keyCode != null && !smooth) scope.launch {
                        delay(400)
                        while (true) {
                            vm.press(keyCode)
                            delay(150)
                        }
                    } else null
                    try {
                        val up = waitForUpOrCancellation()
                        if (up != null) onClick?.invoke()
                    } finally {
                        // Also runs when the button leaves the screen mid-press: never leave a key held down.
                        ticker?.cancel()
                        if (smooth) vm.press(keyCode!!, Direction.EndLong)
                        pressed = false
                        onUp?.invoke()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
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
    // What we believe the TV's field holds. The field shows SENTINEL + mirror.
    var mirror by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shown = SENTINEL + mirror
    Surface(modifier.fillMaxWidth().imePadding(), tonalElevation = 8.dp, shadowElevation = 8.dp) {
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
                modifier = Modifier.weight(1f).focusRequester(focus),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    vm.press(Key.ENTER)
                    onClose()
                }),
            )
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}

// ------------------------------------------------------------------ icons (drawn: fonts lack many symbols)

@Composable
private fun iconColor() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun PowerIcon(on: Boolean) {
    val color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else iconColor()
    Canvas(Modifier.size(24.dp)) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        drawArc(
            color, startAngle = -50f, sweepAngle = 280f, useCenter = false,
            topLeft = Offset(w * 0.1f, w * 0.18f), size = Size(w * 0.8f, w * 0.8f), style = stroke,
        )
        drawLine(color, Offset(w / 2, w * 0.05f), Offset(w / 2, w * 0.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

@Composable
private fun KeyboardIcon() {
    val color = iconColor()
    Canvas(Modifier.size(width = 28.dp, height = 20.dp)) {
        val w = size.width
        val h = size.height
        val line = h * 0.09f
        drawRoundRect(
            color, topLeft = Offset(line / 2, line / 2), size = Size(w - line, h - line),
            cornerRadius = CornerRadius(h * 0.18f), style = Stroke(width = line),
        )
        for (row in 0..1) for (col in 0..3) {
            drawCircle(color, radius = h * 0.055f, center = Offset(w * (0.2f + col * 0.2f), h * (0.32f + row * 0.2f)))
        }
        drawLine(color, Offset(w * 0.3f, h * 0.76f), Offset(w * 0.7f, h * 0.76f), strokeWidth = line, cap = StrokeCap.Round)
    }
}

@Composable
private fun GearIcon() {
    val color = iconColor()
    Canvas(Modifier.size(24.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val stroke = size.minDimension * 0.1f
        drawCircle(color, radius = r * 0.5f, center = c, style = Stroke(width = stroke))
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val dx = Math.cos(a).toFloat()
            val dy = Math.sin(a).toFloat()
            drawLine(
                color, c + Offset(dx, dy) * (r * 0.68f), c + Offset(dx, dy) * (r * 0.95f),
                strokeWidth = stroke * 1.3f, cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun DialpadIcon() {
    val color = iconColor()
    Canvas(Modifier.size(22.dp)) {
        for (row in 0..3) for (col in 0..2) {
            if (row == 3 && col != 1) continue
            drawCircle(color, radius = size.minDimension * 0.08f, center = Offset(size.width * (0.2f + col * 0.3f), size.height * (0.13f + row * 0.25f)))
        }
    }
}

@Composable
private fun DpadIcon() {
    val color = iconColor()
    Canvas(Modifier.size(22.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val s = size.minDimension
        drawCircle(color, radius = s * 0.1f, center = c)
        fun arrow(dx: Float, dy: Float) {
            val tip = c + Offset(dx, dy) * (s * 0.46f)
            val base = c + Offset(dx, dy) * (s * 0.28f)
            val side = Offset(-dy, dx) * (s * 0.12f)
            val p = Path().apply { moveTo(tip.x, tip.y); lineTo(base.x + side.x, base.y + side.y); lineTo(base.x - side.x, base.y - side.y); close() }
            drawPath(p, color)
        }
        arrow(0f, -1f); arrow(0f, 1f); arrow(-1f, 0f); arrow(1f, 0f)
    }
}

@Composable
private fun SpeakerIcon(muted: Boolean) {
    val color = iconColor()
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.1f, h * 0.38f); lineTo(w * 0.32f, h * 0.38f); lineTo(w * 0.55f, h * 0.18f)
            lineTo(w * 0.55f, h * 0.82f); lineTo(w * 0.32f, h * 0.62f); lineTo(w * 0.1f, h * 0.62f); close()
        }
        drawPath(body, color)
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        if (muted) {
            drawLine(color, Offset(w * 0.68f, h * 0.36f), Offset(w * 0.92f, h * 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.92f, h * 0.36f), Offset(w * 0.68f, h * 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        } else {
            drawArc(color, -40f, 80f, false, Offset(w * 0.5f, h * 0.3f), Size(w * 0.3f, h * 0.4f), style = stroke)
            drawArc(color, -50f, 100f, false, Offset(w * 0.5f, h * 0.15f), Size(w * 0.45f, h * 0.7f), style = stroke)
        }
    }
}

@Composable
private fun MicIcon() {
    val color = iconColor()
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        drawRoundRect(color, Offset(w * 0.36f, h * 0.06f), Size(w * 0.28f, h * 0.5f), CornerRadius(w * 0.14f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.2f, h * 0.2f), Size(w * 0.6f, h * 0.5f), style = stroke)
        drawLine(color, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.9f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.92f), Offset(w * 0.66f, h * 0.92f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}
