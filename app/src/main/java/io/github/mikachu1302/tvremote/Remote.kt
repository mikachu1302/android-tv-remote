package io.github.mikachu1302.tvremote

import java.io.IOException
import javax.net.ssl.SSLSocket

private const val REMOTE_PORT = 6466

// RemoteMessage field numbers (remotemessage.proto).
private const val F_CONFIGURE = 1
private const val F_SET_ACTIVE = 2
private const val F_PING_REQUEST = 8
private const val F_PING_RESPONSE = 9
private const val F_KEY_INJECT = 10
private const val F_START = 40

// Features we offer: ping, key events, power, volume.
private const val FEATURES = 1L or 2L or 32L or 64L
private const val DIRECTION_SHORT = 3L

/** Android key codes the TV understands. */
object Key {
    const val HOME = 3
    const val BACK = 4
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val MENU = 82
    const val PLAY_PAUSE = 85
    const val VOLUME_MUTE = 164
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
}

/**
 * A live control connection to the TV. Runs a reader thread that answers the
 * TV's handshake and pings; [send] is safe to call from any thread.
 */
class RemoteConnection(
    host: String,
    pin: String,
    private val onClosed: (Throwable?) -> Unit,
    private val onReady: () -> Unit,
) {
    private val socket: SSLSocket = openTls(host, REMOTE_PORT, pin)
    private val out = socket.getOutputStream()
    @Volatile private var closed = false

    init {
        // Pings arrive every ~5 s; silence for much longer means the link is dead.
        socket.soTimeout = 30_000
        Thread({ readLoop() }, "tv-remote-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            val input = socket.getInputStream()
            while (!closed) {
                val msg = parsePb(input.readFrame())
                when {
                    msg.has(F_CONFIGURE) -> {
                        val supported = msg.message(F_CONFIGURE)?.long(1, FEATURES) ?: FEATURES
                        write(PbWriter().message(F_CONFIGURE) {
                            varint(1, FEATURES and supported)
                            message(2) {
                                string(1, "Android") // model
                                string(2, "TV Remote") // vendor
                                varint(3, 1)
                                string(4, "1")
                                string(5, "io.github.mikachu1302.tvremote")
                                string(6, "0.1.0")
                            }
                        }.toByteArray())
                    }
                    msg.has(F_SET_ACTIVE) ->
                        write(PbWriter().message(F_SET_ACTIVE) { varint(1, FEATURES) }.toByteArray())
                    msg.has(F_PING_REQUEST) -> {
                        val v = msg.message(F_PING_REQUEST)?.long(1) ?: 0
                        write(PbWriter().message(F_PING_RESPONSE) { varint(1, v) }.toByteArray())
                    }
                    msg.has(F_START) -> onReady()
                }
            }
        } catch (e: Throwable) {
            if (!closed) failure = e
        } finally {
            close()
            onClosed(failure)
        }
    }

    @Synchronized
    private fun write(payload: ByteArray) {
        out.writeFrame(payload)
    }

    fun send(keyCode: Int) {
        if (closed) throw IOException("not connected")
        try {
            write(PbWriter().message(F_KEY_INJECT) {
                varint(1, keyCode.toLong())
                varint(2, DIRECTION_SHORT)
            }.toByteArray())
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
    }
}
