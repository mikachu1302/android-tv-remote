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
private const val F_IME_KEY_INJECT = 20
private const val F_IME_BATCH_EDIT = 21
private const val F_IME_SHOW_REQUEST = 22
private const val F_VOICE_BEGIN = 30
private const val F_VOICE_PAYLOAD = 31
private const val F_VOICE_END = 32
private const val F_START = 40
private const val F_SET_VOLUME = 50
private const val F_APP_LINK_LAUNCH = 90

/** Feature flags exchanged in the configure message. */
object Feature {
    const val PING = 1L
    const val KEY = 2L
    const val IME = 4L
    const val VOICE = 8L
    const val POWER = 32L
    const val VOLUME = 64L
    const val APP_LINK = 512L

    /** What we ask for; the TV answers with the subset it supports. */
    const val WANTED = PING or KEY or IME or VOICE or POWER or VOLUME or APP_LINK
}

enum class Direction(val wire: Long) { Short(3), StartLong(1), EndLong(2) }

/** Android key codes the TV understands. */
object Key {
    const val HOME = 3
    const val DIGIT_0 = 7 // DIGIT_0 + n for n in 0..9
    const val BACK = 4
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val ENTER = 66
    const val DEL = 67
    const val SEARCH = 84
    const val TV = 170
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MEDIA_STOP = 86
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val INFO = 165
    const val GUIDE = 172
    const val CAPTIONS = 175
    const val SETTINGS = 176
    const val PROG_RED = 183
    const val PROG_GREEN = 184
    const val PROG_YELLOW = 185
    const val PROG_BLUE = 186
    const val SLEEP = 223
    const val WAKEUP = 224
    const val LAST_CHANNEL = 229
    const val TELETEXT = 233
    const val ALL_APPS = 284
    const val MENU = 82
    const val PLAY_PAUSE = 85
    const val VOLUME_MUTE = 164
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
}

data class Volume(val level: Int, val max: Int, val muted: Boolean)

/** The TV's focused text field, as last reported. */
data class TextFieldInfo(val value: String, val label: String)

/**
 * Everything the TV tells us about itself. Fields stay null/zero until the TV
 * reports them – a stick whose volume is handled by the TV over HDMI-CEC may
 * never send [volume].
 */
data class TvState(
    val on: Boolean? = null,
    val volume: Volume? = null,
    val appPackage: String? = null,
    val appLabel: String? = null,
    val textField: TextFieldInfo? = null,
    /** Counts how often the TV asked for the keyboard (a text field got focus). */
    val keyboardRequests: Int = 0,
    val features: Long = 0,
    val device: String? = null,
    /** The TV opened a voice session after the Search key; [voiceBegins] counts them. */
    val voiceSession: Int = 0,
    val voiceBegins: Int = 0,
    // Counters the TV wants echoed back with every text edit.
    val imeCounter: Int = 0,
    val imeFieldCounter: Int = 0,
)

/** Applies one message from the TV to the state. Pure, so it is unit-tested. */
fun TvState.apply(msg: PbMessage): TvState = when {
    msg.has(F_CONFIGURE) -> {
        val cfg = msg.message(F_CONFIGURE)
        val info = cfg?.message(2)
        copy(
            features = cfg?.long(1) ?: 0,
            device = listOfNotNull(info?.string(2)?.ifBlank { null }, info?.string(1)?.ifBlank { null })
                .joinToString(" ").ifBlank { null },
        )
    }
    // `started` is a bool: when false the field is absent, the message still present.
    msg.has(F_START) -> copy(on = msg.message(F_START)?.long(1) == 1L)
    // Some sticks send this message without a level (volume lives on the TV, over HDMI-CEC):
    // only a message with a maximum counts as a volume report.
    msg.has(F_SET_VOLUME) -> msg.message(F_SET_VOLUME)?.takeIf { it.long(6) > 0 }?.let {
        copy(volume = Volume(level = it.long(7).toInt(), max = it.long(6).toInt(), muted = it.long(8) == 1L))
    } ?: this
    msg.has(F_VOICE_BEGIN) ->
        copy(voiceSession = msg.message(F_VOICE_BEGIN)?.long(1)?.toInt() ?: 0, voiceBegins = voiceBegins + 1)
    msg.has(F_IME_KEY_INJECT) -> {
        val inject = msg.message(F_IME_KEY_INJECT)
        val app = inject?.message(1)
        val field = inject?.message(2)
        copy(
            appPackage = app?.string(12)?.ifBlank { null } ?: appPackage,
            appLabel = app?.string(10)?.ifBlank { null } ?: appLabel,
            imeCounter = app?.long(1)?.toInt()?.takeIf { it != 0 } ?: imeCounter,
            imeFieldCounter = field?.long(1)?.toInt()?.takeIf { it != 0 } ?: imeFieldCounter,
            textField = field?.let { TextFieldInfo(it.string(2), it.string(6)) } ?: textField,
        )
    }
    msg.has(F_IME_SHOW_REQUEST) -> {
        val field = msg.message(F_IME_SHOW_REQUEST)?.message(2)
        copy(
            keyboardRequests = keyboardRequests + 1,
            imeFieldCounter = field?.long(1)?.toInt()?.takeIf { it != 0 } ?: imeFieldCounter,
            textField = field?.let { TextFieldInfo(it.string(2), it.string(6)) } ?: textField,
        )
    }
    msg.has(F_IME_BATCH_EDIT) -> {
        val edit = msg.message(F_IME_BATCH_EDIT)
        copy(
            imeCounter = if (imeCounter == 0) edit?.long(1)?.toInt() ?: 0 else imeCounter,
            imeFieldCounter = if (imeFieldCounter == 0) edit?.long(2)?.toInt() ?: 0 else imeFieldCounter,
        )
    }
    else -> this
}

/** The TV's certificate no longer matches the one pinned at pairing. */
class PinMismatchException(message: String) : java.security.cert.CertificateException(message)

/**
 * A live control connection to the TV. Runs a reader thread that answers the
 * TV's handshake and pings and keeps [state] current; the send functions are
 * safe to call from any thread.
 */
class RemoteConnection(
    host: String,
    pin: String,
    private val appVersion: String,
    private val onState: (TvState) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
    /** Debug logging of every message from the TV; null in release builds. */
    private val trace: ((String) -> Unit)? = null,
) {
    private val socket: SSLSocket = openTls(host, REMOTE_PORT, pin)
    private val out = socket.getOutputStream()
    @Volatile private var closed = false
    @Volatile var state = TvState()
        private set

    /** Features both sides support; what we answer in set_active. */
    private var active = Feature.WANTED

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
                trace?.invoke("rx " + msg.describe())
                when {
                    msg.has(F_CONFIGURE) -> {
                        active = Feature.WANTED and (msg.message(F_CONFIGURE)?.long(1, Feature.WANTED) ?: Feature.WANTED)
                        write(PbWriter().message(F_CONFIGURE) {
                            varint(1, active)
                            message(2) {
                                string(1, "Android") // model
                                string(2, "TV Remote") // vendor
                                varint(3, 1)
                                string(4, "1")
                                string(5, "io.github.mikachu1302.tvremote")
                                string(6, appVersion)
                            }
                        }.toByteArray())
                    }
                    msg.has(F_SET_ACTIVE) ->
                        write(PbWriter().message(F_SET_ACTIVE) { varint(1, active) }.toByteArray())
                    msg.has(F_PING_REQUEST) -> {
                        val v = msg.message(F_PING_REQUEST)?.long(1) ?: 0
                        write(PbWriter().message(F_PING_RESPONSE) { varint(1, v) }.toByteArray())
                    }
                }
                val next = state.apply(msg)
                if (next != state) {
                    state = next
                    onState(next)
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

    private fun send(payload: ByteArray) {
        if (closed) throw IOException("not connected")
        try {
            write(payload)
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    fun send(keyCode: Int, direction: Direction = Direction.Short) = send(
        PbWriter().message(F_KEY_INJECT) {
            varint(1, keyCode.toLong())
            varint(2, direction.wire)
        }.toByteArray(),
    )

    /** Inserts [text] at the cursor of the focused text field. */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val s = state
        send(PbWriter().message(F_IME_BATCH_EDIT) {
            varint(1, s.imeCounter.toLong())
            varint(2, s.imeFieldCounter.toLong())
            message(3) { // one edit: insert
                varint(1, 1)
                message(2) {
                    varint(1, text.length.toLong()) // cursor after the inserted text
                    varint(2, text.length.toLong())
                    string(3, text)
                }
            }
        }.toByteArray())
    }

    /** Confirms the TV's voice session; audio chunks may follow. */
    fun voiceBegin(session: Int) = send(PbWriter().message(F_VOICE_BEGIN) { varint(1, session.toLong()) }.toByteArray())

    /** 16-bit PCM, 8 kHz, mono; the TV wants chunks of at least 8 KB. */
    fun voiceChunk(session: Int, samples: ByteArray) = send(
        PbWriter().message(F_VOICE_PAYLOAD) {
            varint(1, session.toLong())
            bytes(2, samples)
        }.toByteArray(),
    )

    fun voiceEnd(session: Int) = send(PbWriter().message(F_VOICE_END) { varint(1, session.toLong()) }.toByteArray())

    /** Opens a deep link on the TV, e.g. an https URL an app handles. */
    fun launch(link: String) = send(PbWriter().message(F_APP_LINK_LAUNCH) { string(1, link) }.toByteArray())

    fun close() {
        closed = true
        runCatching { socket.close() }
    }
}

/**
 * What typing in a local text field means for the TV's field: delete
 * [deletes] characters before the cursor, then insert [insert]. Only edits at
 * the end of the text are supported (that is what typing is).
 */
data class TextEdit(val deletes: Int, val insert: String)

fun textEdit(old: String, new: String): TextEdit {
    var prefix = old.commonPrefixWith(new).length
    // Never split a surrogate pair (emoji): back up to its start.
    if (prefix > 0 && prefix < old.length && Character.isHighSurrogate(old[prefix - 1])) prefix--
    if (prefix > 0 && prefix < new.length && Character.isHighSurrogate(new[prefix - 1])) prefix--
    val removed = old.substring(prefix)
    return TextEdit(deletes = removed.codePointCount(0, removed.length), insert = new.substring(prefix))
}
