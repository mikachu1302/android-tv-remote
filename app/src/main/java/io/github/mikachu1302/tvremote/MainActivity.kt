package io.github.mikachu1302.tvremote

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.systemBarsPadding()) { App(vm) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.connect() // no-op when not paired or already connected
    }
}

@Composable
private fun App(vm: RemoteViewModel) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    when {
        pairing != PairingUi.None -> PairingScreen(pairing, vm)
        paired == null -> FindTvScreen(vm)
        else -> RemoteScreen(paired!!, vm)
    }
}

// ---------------------------------------------------------------- finding and pairing

@Composable
private fun FindTvScreen(vm: RemoteViewModel) {
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 37 ||
                context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) {
        if (!allowed) ask.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Find your TV", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (!allowed) {
            Text("The app needs access to devices on your network to find the TV.")
            Button(onClick = { ask.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) { Text("Allow") }
            return@Column
        }
        var tvs by remember { mutableStateOf(emptyList<Tv>()) }
        LaunchedEffect(Unit) { discoverTvs(context).collect { tvs = it } }
        if (tvs.isEmpty()) {
            Text("Searching on the Wi-Fi… Make sure the TV is on and on the same network.")
        } else {
            Text("Tap your TV to pair:")
            tvs.forEach { tv ->
                Button(onClick = { vm.startPairing(tv) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(tv.name)
                }
            }
        }
    }
}

@Composable
private fun PairingScreen(state: PairingUi, vm: RemoteViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Pairing", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        when (state) {
            is PairingUi.Starting -> Text("Contacting ${state.tv.name}…")
            is PairingUi.WaitingForCode -> {
                var code by remember { mutableStateOf("") }
                Text("Enter the 6-character code shown on the TV.")
                if (state.wrongCode) {
                    Text("That code was wrong. The TV shows a new one.", color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isLetterOrDigit() }.take(6).uppercase() },
                    label = { Text("Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.submitCode(code) },
                    enabled = code.length == 6,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Pair") }
            }
            is PairingUi.Failed -> {
                Text("Pairing failed: ${state.message}", color = MaterialTheme.colorScheme.error)
            }
            PairingUi.None -> Unit
        }
        TextButton(onClick = vm::cancelPairing) { Text("Cancel") }
    }
}

// ---------------------------------------------------------------- the remote

@Composable
private fun RemoteScreen(tv: PairedTv, vm: RemoteViewModel) {
    val link by vm.link.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Status line: text, not just colour.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tv.name, fontWeight = FontWeight.Bold)
                Text(
                    when (link) {
                        Link.Connected -> "Connected"
                        Link.Connecting -> "Connecting…"
                        Link.Failed -> "Not connected – tap a button to retry"
                        Link.Idle -> "Not connected"
                    },
                    fontSize = 13.sp,
                    color = if (link == Link.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RemoteButton("Power", Key.POWER, vm, Modifier.size(52.dp), shape = CircleShape) { PowerIcon() }
        }

        DPad(vm)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteButton("Back", Key.BACK, vm, Modifier.size(72.dp), shape = CircleShape) { Label("←") }
            RemoteButton("Home", Key.HOME, vm, Modifier.size(72.dp), shape = CircleShape) { Label("⌂") }
            RemoteButton("Play or pause", Key.PLAY_PAUSE, vm, Modifier.size(72.dp), shape = CircleShape) { Label("⏯") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Rocker("Volume", "Volume up", Key.VOLUME_UP, "Volume down", Key.VOLUME_DOWN, vm)
            RemoteButton("Mute", Key.VOLUME_MUTE, vm, Modifier.size(72.dp), shape = CircleShape) { Label("🔇") }
            Rocker("Channel", "Channel up", Key.CHANNEL_UP, "Channel down", Key.CHANNEL_DOWN, vm)
        }

        TextButton(onClick = vm::forget) { Text("Pair another TV", fontSize = 13.sp) }
    }
}

/** Drawn instead of the ⏻ character, which many fonts lack. */
@Composable
private fun PowerIcon() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun Label(text: String) = Text(text, fontSize = 24.sp, textAlign = TextAlign.Center)

@Composable
private fun DPad(vm: RemoteViewModel) {
    val cell = 84.dp
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
private fun Rocker(
    title: String,
    upLabel: String, upKey: Int,
    downLabel: String, downKey: Int,
    vm: RemoteViewModel,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RemoteButton(upLabel, upKey, vm, Modifier.size(width = 72.dp, height = 64.dp), repeat = true,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 8.dp, bottomEnd = 8.dp)) { Label("+") }
        Text(title, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        RemoteButton(downLabel, downKey, vm, Modifier.size(width = 72.dp, height = 64.dp), repeat = true,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 32.dp, bottomEnd = 32.dp)) { Label("−") }
    }
}

/**
 * One remote button. Fires on press (not release) so it feels immediate; with
 * [repeat], holding it keeps firing after a short delay.
 */
@Composable
private fun RemoteButton(
    description: String,
    keyCode: Int,
    vm: RemoteViewModel,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    strong: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
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
                    strong -> colors.primaryContainer
                    else -> colors.surfaceVariant
                },
            )
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(keyCode, repeat) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    vm.press(keyCode)
                    val job = if (repeat) scope.launch {
                        delay(400)
                        while (true) {
                            vm.press(keyCode)
                            delay(150)
                        }
                    } else null
                    waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}
