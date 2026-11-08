package io.github.mikachu1302.tvremote

import android.app.Application
import android.content.pm.ApplicationInfo
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

    private val _paired = MutableStateFlow(loadPaired())
    val paired: StateFlow<PairedTv?> = _paired

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

    private fun loadPaired(): PairedTv? {
        val host = prefs.getString("host", null) ?: return null
        val pin = prefs.getString("pin", null) ?: return null
        return PairedTv(prefs.getString("name", host)!!, host, pin)
    }

    // ------------------------------------------------------------ connection

    /** The app is visible: keep a connection to the paired TV, reconnecting when it drops. */
    fun onForeground() {
        if (loop?.isActive == true || _paired.value == null) return
        loop = viewModelScope.launch(Dispatchers.IO) { connectionLoop() }
    }

    /** The app left the screen: close the connection (the TV frees the session; no battery use). */
    fun onBackground() {
        stopVoice()
        loop?.cancel()
        loop = null
        connection?.close()
        connection = null
        _tvState.value = TvState()
        if (_paired.value != null) _link.value = Link.Idle
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
                    tv.host, tv.pin,
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
        prefs.edit().putString("host", found.host).apply()
        _paired.value = tv.copy(host = found.host)
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

    fun press(keyCode: Int, direction: Direction = Direction.Short) = withConnection { it.send(keyCode, direction) }

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

    fun updateSettings(change: (RemoteSettings) -> RemoteSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit().putSettings(next).apply()
    }

    private fun saveApps(list: List<AppShortcut>) {
        _apps.value = list
        prefs.edit().putString("apps", appsToJson(list)).apply()
    }

    fun addApp(app: AppShortcut) = saveApps(_apps.value + app)

    fun resetApps() = saveApps(DEFAULT_APPS)

    fun removeApp(index: Int) = saveApps(_apps.value.filterIndexed { i, _ -> i != index })

    fun moveApp(index: Int, by: Int) {
        val target = index + by
        val list = _apps.value.toMutableList()
        if (index !in list.indices || target !in list.indices) return
        list.add(target, list.removeAt(index))
        saveApps(list)
    }

    /** Adds whatever app is open on the TV right now as a shortcut (launched by package name). */
    fun addCurrentApp(): Boolean {
        val state = _tvState.value
        val pkg = state.appPackage ?: return false
        val name = state.appLabel ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        val link = "market://launch?id=$pkg"
        if (_apps.value.any { it.link == link }) return true
        addApp(AppShortcut(name, link))
        return true
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
                prefs.edit()
                    .putString("host", state.tv.host)
                    .putString("name", state.tv.name)
                    .putString("pin", pin)
                    .apply()
                pairingSession = null
                _pairing.value = PairingUi.None
                _paired.value = PairedTv(state.tv.name, state.tv.host, pin)
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

    /** Forgets the TV (to pair with another one). */
    fun forget() {
        loop?.cancel()
        loop = null
        connection?.close()
        connection = null
        prefs.edit().clear().apply()
        _paired.value = null
        _tvState.value = TvState()
        _link.value = Link.Idle
    }

    override fun onCleared() {
        voiceStreamer?.stop()
        connection?.close()
        pairingSession?.close()
    }
}
