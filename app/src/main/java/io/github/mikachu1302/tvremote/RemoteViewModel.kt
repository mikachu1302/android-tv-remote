package io.github.mikachu1302.tvremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PairedTv(val name: String, val host: String, val pin: String)

enum class Link { Idle, Connecting, Connected, Failed }

sealed interface PairingUi {
    data object None : PairingUi
    data class Starting(val tv: Tv) : PairingUi
    data class WaitingForCode(val tv: Tv, val wrongCode: Boolean = false) : PairingUi
    data class Failed(val message: String) : PairingUi
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("tv", 0)

    private val _paired = MutableStateFlow(loadPaired())
    val paired: StateFlow<PairedTv?> = _paired

    private val _link = MutableStateFlow(Link.Idle)
    val link: StateFlow<Link> = _link

    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.None)
    val pairing: StateFlow<PairingUi> = _pairing

    private var connection: RemoteConnection? = null
    private var pairingSession: Pairing? = null

    private fun loadPaired(): PairedTv? {
        val host = prefs.getString("host", null) ?: return null
        val pin = prefs.getString("pin", null) ?: return null
        return PairedTv(prefs.getString("name", host)!!, host, pin)
    }

    /** Connects to the paired TV unless a connection is already live or being made. */
    fun connect() {
        val tv = _paired.value ?: return
        if (_link.value == Link.Connecting || connection != null) return
        _link.value = Link.Connecting
        viewModelScope.launch(Dispatchers.IO) {
            try {
                lateinit var conn: RemoteConnection
                conn = RemoteConnection(
                    tv.host, tv.pin,
                    onClosed = {
                        if (connection === conn) {
                            connection = null
                            _link.value = Link.Failed
                        }
                    },
                    onReady = { if (connection === conn) _link.value = Link.Connected },
                )
                connection = conn
            } catch (e: Exception) {
                connection = null
                _link.value = Link.Failed
            }
        }
    }

    fun press(keyCode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val conn = connection
            if (conn == null) {
                connect()
                return@launch
            }
            try {
                conn.send(keyCode)
            } catch (e: Exception) {
                // The link dropped (TV asleep or Wi-Fi hiccup): reconnect for the next press.
                connection = null
                _link.value = Link.Failed
                connect()
            }
        }
    }

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
                connect()
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
        connection?.close()
        connection = null
        prefs.edit().clear().apply()
        _paired.value = null
        _link.value = Link.Idle
    }

    override fun onCleared() {
        connection?.close()
        pairingSession?.close()
    }
}
