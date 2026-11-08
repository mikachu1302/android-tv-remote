package io.github.mikachu1302.tvremote

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()
    private var lastTouch = SystemClock.uptimeMillis()
    private var dimmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            TvRemoteTheme(settings.style, Palettes.byId(settings.palette), settings.scanlines) {
                val term = LocalTerm.current
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .termBackground()
                            .let { if (term.crt && term.scanlines) it.crtOverlay() else it }
                            .systemBarsPadding(),
                    ) { App(vm) }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // "Keep screen awake": no screen timeout while the remote is in front.
                    vm.settings.collect { s ->
                        if (s.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                launch {
                    // "Theater mode": dim the screen when nothing was touched for a while.
                    while (true) {
                        delay(1_000)
                        val idle = SystemClock.uptimeMillis() - lastTouch
                        setDim(vm.settings.value.theater && idle > THEATER_IDLE_MS)
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        setDim(false)
        return super.dispatchTouchEvent(ev)
    }

    private fun setDim(on: Boolean) {
        if (on == dimmed) return
        dimmed = on
        window.attributes = window.attributes.also {
            it.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground() // no-op when not paired or already connecting
    }

    override fun onStop() {
        super.onStop()
        setDim(false)
        vm.onBackground()
    }

    private companion object {
        const val THEATER_IDLE_MS = 10_000L
    }
}

private enum class Screen { Remote, Settings, Apps }

@Composable
private fun App(vm: RemoteViewModel) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val adding by vm.addingTv.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Remote) }
    // Back from the app shortcuts: Settings, or the remote when "+" in the app row opened it.
    var appsBack by remember { mutableStateOf(Screen.Settings) }
    LaunchedEffect(paired == null) { screen = Screen.Remote }
    BackHandler(enabled = adding && pairing == PairingUi.None) { vm.cancelAddTv() }
    BackHandler(enabled = paired != null && screen != Screen.Remote) {
        screen = if (screen == Screen.Apps) appsBack else Screen.Remote
    }
    CompositionLocalProvider(LocalSettings provides settings) {
        val tv = paired
        when {
            pairing != PairingUi.None -> PairingScreen(pairing, vm)
            tv == null || adding -> FindTvScreen(vm, canCancel = tv != null)
            screen == Screen.Remote -> RemoteScreen(
                tv, vm, onSettings = { screen = Screen.Settings },
                onAddApp = { appsBack = Screen.Remote; screen = Screen.Apps },
            )
            screen == Screen.Settings -> SettingsScreen(
                tv, vm, onBack = { screen = Screen.Remote },
                onApps = { appsBack = Screen.Settings; screen = Screen.Apps },
            )
            else -> AppShortcutsScreen(vm, onBack = { screen = appsBack })
        }
    }
}

// ---------------------------------------------------------------- finding and pairing

@Composable
private fun FindTvScreen(vm: RemoteViewModel, canCancel: Boolean) {
    val context = LocalContext.current
    val paired by vm.tvs.collectAsStateWithLifecycle()
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
        Text(if (canCancel) "Add a TV" else "Find your TV", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (!allowed) {
            Text("The app needs access to devices on your network to find the TV.")
            TButton(onClick = { ask.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) { Text("Allow") }
            return@Column
        }
        var tvs by remember { mutableStateOf(emptyList<Tv>()) }
        LaunchedEffect(Unit) { discoverTvs(context).collect { tvs = it } }
        if (tvs.isEmpty()) {
            Text("Searching on the Wi-Fi… Make sure the TV is on and on the same network.")
        } else {
            Text("Tap your TV to pair:")
            tvs.forEach { tv ->
                val already = paired.any { it.name == tv.name }
                TButton(onClick = { vm.startPairing(tv) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (already) "${tv.name} (paired – pair again)" else tv.name)
                }
            }
        }
        if (canCancel) TTextButton(onClick = vm::cancelAddTv) { Text("Cancel") }
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
                TButton(
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
        TTextButton(onClick = vm::cancelPairing) { Text("Cancel") }
    }
}

