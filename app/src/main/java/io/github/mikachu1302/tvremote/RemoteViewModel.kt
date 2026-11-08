package io.github.mikachu1302.tvremote

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

data class PairedTv(val name: String, val host: String, val pin: String)

enum class Link { Idle, Connecting, Connected, Retrying, PinChanged }

enum class VoiceUi { Idle, Starting, Listening, Unavailable }

sealed interface PairingUi {
    data object None : PairingUi
    data class Starting(val tv: Tv) : PairingUi
    data class WaitingForCode(val tv: Tv, val wrongCode: Boolean = false) : PairingUi
    data class Failed(val message: String) : PairingUi
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("tv", 0)
    private val debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Every paired TV; one of them is the active one the remote controls. */
    private val _tvs = MutableStateFlow(loadTvs())
    val tvs: StateFlow<List<PairedTv>> = _tvs

    private val _paired = MutableStateFlow(_tvs.value.let { it.getOrNull(prefs.getInt("activeTv", 0)) ?: it.firstOrNull() })
    /** The active TV, null when none is paired. */
    val paired: StateFlow<PairedTv?> = _paired

    /** The user is pairing one more TV on top of the ones they have. */
    private val _addingTv = MutableStateFlow(false)
    val addingTv: StateFlow<Boolean> = _addingTv

    private val _link = MutableStateFlow(Link.Idle)
    val link: StateFlow<Link> = _link

    private val _tvState = MutableStateFlow(TvState())
    val tvState: StateFlow<TvState> = _tvState

    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.None)
    val pairing: StateFlow<PairingUi> = _pairing

    private val _settings = MutableStateFlow(prefs.loadSettings())
    val settings: StateFlow<RemoteSettings> = _settings

    private val _apps = MutableStateFlow(parseApps(prefs.getString("apps", null)))
    val apps: StateFlow<List<AppShortcut>> = _apps

    /** The TV whose own volume the volume keys control (UPnP), and what it last reported (null: not reachable). */
    private val _audioTv = MutableStateFlow(parseAudioTv(prefs.getString("audioTv", null)))
    val audioTv: StateFlow<AudioTv?> = _audioTv
    private val _audio = MutableStateFlow<TvAudio?>(null)
    val audio: StateFlow<TvAudio?> = _audio
    private var audioLoop: Job? = null
    private var ramp: Job? = null
    @Volatile private var lastAudioSet = 0L
    private val audioSender = Dispatchers.IO.limitedParallelism(1)

    private val _voice = MutableStateFlow(VoiceUi.Idle)
    val voice: StateFlow<VoiceUi> = _voice
    @Volatile private var voiceHeld = false
    private var voiceStreamer: VoiceStreamer? = null

    @Volatile private var connection: RemoteConnection? = null
    private var pairingSession: Pairing? = null
    private var loop: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    // One sender at a time, in submission order: fast typing must not reorder edits.
    private val sender = Dispatchers.IO.limitedParallelism(1)

    init {
        // Make the launcher icon match the saved settings (also after an update).
        viewModelScope.launch(Dispatchers.IO) { LauncherIcon.apply(getApplication(), _settings.value) }
    }

    private fun loadTvs(): List<PairedTv> {
        prefs.getString("tvs", null)?.let { return parseTvs(it) }
        // Older versions stored a single TV under three keys.
        val host = prefs.getString("host", null) ?: return emptyList()
        val pin = prefs.getString("pin", null) ?: return emptyList()
        return listOf(PairedTv(prefs.getString("name", host)!!, host, pin))
    }

    private fun saveTvs(list: List<PairedTv>, active: PairedTv?) {
        _tvs.value = list
        _paired.value = active
        prefs.edit()
            .putString("tvs", tvsToJson(list))
            .putInt("activeTv", list.indexOf(active).coerceAtLeast(0))
            .remove("host").remove("pin").remove("name") // the old single-TV keys
            .apply()
    }

    // ------------------------------------------------------------ connection

    /** The app is visible: keep a connection to the paired TV, reconnecting when it drops. */
    fun onForeground() {
        startAudio()
        if (loop?.isActive == true || _paired.value == null) return
        loop = viewModelScope.launch(Dispatchers.IO) { connectionLoop() }
    }

    /** The app left the screen: close the connection (the TV frees the session; no battery use). */
    fun onBackground() {
        stopConnection()
        stopAudio()
    }

    private suspend fun connectionLoop() {
        var wait = 500L
        var failures = 0
        while (true) {
            val tv = _paired.value ?: return
            _link.value = if (failures == 0) Link.Connecting else Link.Retrying
            val closed = CompletableDeferred<Unit>()
            try {
                connection = RemoteConnection(
                    tv.host, tv.pin, appVersion,
                    onState = { _tvState.value = it },
                    onClosed = { closed.complete(Unit) },
                    trace = if (debug) { m -> Log.d("TvRemote", m) } else null,
                )
                _link.value = Link.Connected
                failures = 0
                wait = 500
                closed.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generateSequence<Throwable>(e) { it.cause }.any { it is PinMismatchException }) {
                    // Someone else answers on the TV's address, or the TV was reset: don't retry.
                    _link.value = Link.PinChanged
                    return
                }
                failures++
                if (failures % 2 == 0) rediscover(tv) // the TV's address may have changed
            }
            connection = null
            _tvState.value = TvState()
            _link.value = Link.Retrying
            withTimeoutOrNull(wait) { retryNow.receive() }
            wait = minOf(wait * 2, 8_000)
        }
    }

    /** Looks for the paired TV by its mDNS name; remembers a new address. */
    private suspend fun rediscover(tv: PairedTv) {
        val found = runCatching {
            withTimeoutOrNull(5_000) {
                discoverTvs(getApplication()).first { list -> list.any { it.name == tv.name } }
            }
        }.getOrNull()?.firstOrNull { it.name == tv.name } ?: return
        if (found.host == tv.host) return
        val moved = tv.copy(host = found.host)
        saveTvs(_tvs.value.map { if (it.name == tv.name) moved else it }, moved)
    }

    /** Called when the user taps the status: retry right now instead of waiting. */
    fun retry() {
        if (_link.value == Link.PinChanged) return
        retryNow.trySend(Unit)
        onForeground()
    }

    // ------------------------------------------------------------ sending

    private fun withConnection(action: (RemoteConnection) -> Unit) {
        val conn = connection
        if (conn == null) {
            retry() // a press while disconnected wakes the reconnect loop
            return
        }
        viewModelScope.launch(sender) {
            try {
                action(conn)
            } catch (e: IOException) {
                // The connection closed itself; the loop reconnects.
            }
        }
    }

    fun press(keyCode: Int, direction: Direction = Direction.Short) {
        if (audioKey(keyCode, direction)) return
        withConnection { it.send(keyCode, direction) }
    }

    // ------------------------------------------------------------ the TV's own volume and power

    /** Polls the TV's volume while the app is visible; null while it does not answer (off, or another network). */
    private fun startAudio() {
        if (audioLoop?.isActive == true) return
        audioLoop = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val tv = _audioTv.value
                if (tv == null || !_settings.value.tvVolume) {
                    _audio.value = null
                    delay(1_000)
                    continue
                }
                var wait = 2_500L
                try {
                    val now = AudioClient(tv).read()
                    // A reading taken just before our own change would flicker the display back.
                    if (SystemClock.elapsedRealtime() - lastAudioSet > 1_500) _audio.value = now
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _audio.value = null
                    wait = 1_500
                }
                delay(wait)
            }
        }
    }

    private fun stopAudio() {
        ramp?.cancel()
        audioLoop?.cancel()
        audioLoop = null
        _audio.value = null
    }

    /**
     * Volume and mute go to the TV when it answers; else they stay keys for the stick (HDMI-CEC).
     * Returns true when the key was handled here.
     */
    private fun audioKey(code: Int, direction: Direction): Boolean {
        if (_audioTv.value == null || !_settings.value.tvVolume || _audio.value == null) return false
        when (code) {
            Key.VOLUME_UP, Key.VOLUME_DOWN -> {
                val step = if (code == Key.VOLUME_UP) 1 else -1
                when (direction) {
                    Direction.Short -> nudgeVolume(step)
                    Direction.StartLong -> {
                        ramp?.cancel()
                        ramp = viewModelScope.launch {
                            nudgeVolume(step)
                            delay(400)
                            while (true) {
                                nudgeVolume(step)
                                delay(120)
                            }
                        }
                    }
                    Direction.EndLong -> ramp?.cancel()
                }
            }
            Key.VOLUME_MUTE -> if (direction != Direction.EndLong) toggleTvMute()
            else -> return false
        }
        return true
    }

    private fun nudgeVolume(step: Int) {
        val tv = _audioTv.value ?: return
        viewModelScope.launch(audioSender) {
            val current = _audio.value ?: return@launch
            val next = (current.level + step).coerceIn(0, 100)
            if (next == current.level) return@launch
            lastAudioSet = SystemClock.elapsedRealtime()
            _audio.value = current.copy(level = next)
            try {
                AudioClient(tv).setVolume(next)
            } catch (e: IOException) {
                _audio.value = null
            }
        }
    }

    private fun toggleTvMute() {
        val tv = _audioTv.value ?: return
        viewModelScope.launch(audioSender) {
            val current = _audio.value ?: return@launch
            lastAudioSet = SystemClock.elapsedRealtime()
            _audio.value = current.copy(muted = !current.muted)
            try {
                AudioClient(tv).setMute(!current.muted)
            } catch (e: IOException) {
                _audio.value = null
            }
        }
    }

    /** Chooses the TV for volume (and the address to wake); null forgets it. */
    fun setAudioTv(tv: AudioTv?) {
        _audioTv.value = tv
        _audio.value = null
        prefs.edit().apply { if (tv == null) remove("audioTv") else putString("audioTv", audioTvToJson(tv)) }.apply()
    }

    /** The user edited the keyboard field from [old] to [new]; mirror that on the TV. */
    fun typed(old: String, new: String) {
        val edit = textEdit(old, new)
        withConnection { conn ->
            repeat(edit.deletes) { conn.send(Key.DEL) }
            conn.sendText(edit.insert)
        }
    }

    fun launch(link: String) = withConnection { it.launch(link) }

    // ------------------------------------------------------------ voice

    /** The mic button went down: open a voice session on the TV and stream the microphone. */
    fun startVoice() {
        val conn = connection
        if (conn == null) {
            retry()
            return
        }
        if (voiceHeld) return
        voiceHeld = true
        _voice.value = VoiceUi.Starting
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val before = conn.state.voiceBegins
                conn.send(Key.SEARCH) // the TV answers by opening a voice session
                val session = withTimeoutOrNull(2_500) {
                    while (conn.state.voiceBegins == before) delay(20)
                    conn.state.voiceSession
                }
                if (session == null) {
                    voiceHeld = false
                    _voice.value = VoiceUi.Unavailable
                    return@launch
                }
                conn.voiceBegin(session)
                voiceStreamer = VoiceStreamer(conn, session).also { it.start() }
                _voice.value = VoiceUi.Listening
                if (!voiceHeld) stopVoice() // released while the session was opening
            } catch (e: IOException) {
                voiceHeld = false
                _voice.value = VoiceUi.Idle
            }
        }
    }

    /** The mic button went up. */
    fun stopVoice() {
        voiceHeld = false
        voiceStreamer?.stop()
        voiceStreamer = null
        if (_voice.value != VoiceUi.Unavailable) _voice.value = VoiceUi.Idle
    }

    // ------------------------------------------------------------ settings and apps

    private var iconJob: Job? = null

    fun updateSettings(change: (RemoteSettings) -> RemoteSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit().putSettings(next).apply()
        // The launcher icon follows the colours, a moment after the last change (the launcher
        // flickers on every switch, and the user is usually still trying palettes).
        iconJob?.cancel()
        iconJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1_500)
            LauncherIcon.apply(getApplication(), next)
        }
    }

    private fun saveApps(list: List<AppShortcut>) {
        _apps.value = list
        prefs.edit().putString("apps", appsToJson(list)).apply()
    }

    fun addApp(app: AppShortcut) = saveApps(_apps.value + app)

    fun resetApps() = saveApps(DEFAULT_APPS)

    /** Picker tap: adds the app, or removes it when it is already in the row. */
    fun toggleApp(app: AppShortcut) {
        val i = _apps.value.indexOfFirst { it.link == app.link }
        if (i >= 0) removeApp(i) else addApp(app)
    }

    fun removeApp(index: Int) = saveApps(_apps.value.filterIndexed { i, _ -> i != index })

    fun moveApp(index: Int, by: Int) {
        val target = index + by
        val list = _apps.value.toMutableList()
        if (index !in list.indices || target !in list.indices) return
        list.add(target, list.removeAt(index))
        saveApps(list)
    }

    // ------------------------------------------------------------ pairing

    fun startPairing(tv: Tv, wrongCode: Boolean = false) {
        pairingSession?.close()
        _pairing.value = PairingUi.Starting(tv)
        val session = Pairing(tv.host).also { pairingSession = it }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                session.start()
                _pairing.value = PairingUi.WaitingForCode(tv, wrongCode)
            } catch (e: Exception) {
                session.close()
                _pairing.value = PairingUi.Failed(e.message ?: "Could not reach the TV")
            }
        }
    }

    fun submitCode(code: String) {
        val state = _pairing.value as? PairingUi.WaitingForCode ?: return
        val session = pairingSession ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                session.finish(code)
            } catch (e: Exception) {
                false
            }
            val pin = session.serverPin
            if (ok && pin != null) {
                val tv = PairedTv(state.tv.name, state.tv.host, pin)
                pairingSession = null
                _pairing.value = PairingUi.None
                _addingTv.value = false
                // Pairing a TV again replaces its entry; a new one is added and becomes active.
                stopConnection()
                saveTvs(_tvs.value.filter { it.name != tv.name } + tv, tv)
                onForeground()
            } else {
                // The TV closes the session after a bad code: start over, it shows a fresh one.
                session.close()
                startPairing(state.tv, wrongCode = true)
            }
        }
    }

    fun cancelPairing() {
        pairingSession?.close()
        pairingSession = null
        _pairing.value = PairingUi.None
    }

    /** Opens the "find a TV" screen on top of the TVs already paired. */
    fun beginAddTv() { _addingTv.value = true }

    fun cancelAddTv() { _addingTv.value = false }

    /** Makes [tv] the one the remote controls. */
    fun selectTv(tv: PairedTv) {
        if (tv == _paired.value) return
        stopConnection()
        saveTvs(_tvs.value, tv)
        onForeground()
    }

    /** Forgets a TV. Only the TV's entry goes: settings and app shortcuts stay. */
    fun forgetTv(tv: PairedTv) {
        val rest = _tvs.value.filter { it.name != tv.name }
        val wasActive = _paired.value?.name == tv.name
        if (wasActive) stopConnection()
        saveTvs(rest, if (wasActive) rest.firstOrNull() else _paired.value)
        if (wasActive && rest.isNotEmpty()) onForeground()
    }

    /** Closes the connection and stops reconnecting, without touching what is paired. */
    private fun stopConnection() {
        stopVoice()
        loop?.cancel()
        loop = null
        connection?.close()
        connection = null
        _tvState.value = TvState()
        _link.value = Link.Idle
    }

    override fun onCleared() {
        voiceStreamer?.stop()
        connection?.close()
        pairingSession?.close()
    }
}
