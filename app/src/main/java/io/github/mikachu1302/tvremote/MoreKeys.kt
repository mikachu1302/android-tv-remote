package io.github.mikachu1302.tvremote

import androidx.compose.ui.graphics.Color

/** A key on the "More" page: shown as a [glyph] (solid shape), a colour bar, or its [label]. */
data class MoreKey(
    val label: String,
    val description: String,
    val code: Int,
    val glyph: String? = null,
    val color: Color? = null,
    /** Holding the key keeps it going (seeking). */
    val repeat: Boolean = false,
)

/**
 * The keys that do not fit on the D-pad: playback, TV keys, the four colour buttons and the stick's own
 * sleep/wake. Whether a key does anything depends on the app that is open on the TV.
 */
val MORE_KEYS: List<List<MoreKey>> = listOf(
    listOf(
        MoreKey("PREV", "Previous", Key.MEDIA_PREVIOUS, glyph = "prev"),
        MoreKey("REW", "Rewind", Key.MEDIA_REWIND, glyph = "rew", repeat = true),
        MoreKey("FF", "Fast forward", Key.MEDIA_FAST_FORWARD, glyph = "ff", repeat = true),
        MoreKey("NEXT", "Next", Key.MEDIA_NEXT, glyph = "next"),
    ),
    listOf(
        MoreKey("PLAY", "Play", Key.MEDIA_PLAY, glyph = "play"),
        MoreKey("PAUSE", "Pause", Key.MEDIA_PAUSE, glyph = "pause"),
        MoreKey("STOP", "Stop", Key.MEDIA_STOP, glyph = "stop"),
        MoreKey("CC", "Subtitles", Key.CAPTIONS),
    ),
    listOf(
        MoreKey("GUIDE", "TV guide", Key.GUIDE),
        MoreKey("INFO", "Info", Key.INFO),
        MoreKey("LAST", "Last channel", Key.LAST_CHANNEL),
        MoreKey("TXT", "Teletext", Key.TELETEXT),
    ),
    listOf(
        MoreKey("RED", "Red button", Key.PROG_RED, color = Color(0xFFFF4B4B)),
        MoreKey("GREEN", "Green button", Key.PROG_GREEN, color = Color(0xFF2FD05B)),
        MoreKey("YELLOW", "Yellow button", Key.PROG_YELLOW, color = Color(0xFFFFED00)),
        MoreKey("BLUE", "Blue button", Key.PROG_BLUE, color = Color(0xFF3D7BFF)),
    ),
    listOf(
        MoreKey("SLEEP", "Put the stick to sleep", Key.SLEEP),
        MoreKey("WAKE", "Wake the stick", Key.WAKEUP),
        MoreKey("APPS", "All apps", Key.ALL_APPS),
        MoreKey("SET", "Settings", Key.SETTINGS),
    ),
)
