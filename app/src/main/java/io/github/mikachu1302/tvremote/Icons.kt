package io.github.mikachu1302.tvremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The remote's icons, drawn from SVG path data on a 24 x 24 grid (fonts lack many symbols: the
 * power sign showed as a box). Stroked glyphs use round caps; the zero-length "dots" in some
 * paths (`h.01`) become round dots that way.
 */
private val STROKED = mapOf(
    "gear" to "M18 12a6 6 0 1 1-12 0a6 6 0 1 1 12 0M14 12a2 2 0 1 1-4 0a2 2 0 1 1 4 0M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1",
    "power" to "M16.5 6.2a8 8 0 1 1-9 0M12 3v8",
    "kbd" to "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10",
    "home" to "M3 11l9-8 9 8M5 10v10h14V10",
    "pad" to "M6 4h.01M12 4h.01M18 4h.01M6 10h.01M12 10h.01M18 10h.01M6 16h.01M12 16h.01M18 16h.01M12 21h.01",
    "speaker" to "M4 9v6h4l5 4V5L8 9zM16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12",
    "speaker-off" to "M4 9v6h4l5 4V5L8 9zM17 9l5 6M22 9l-5 6",
    "mic" to "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3M8 21h8",
    "back" to "M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3",
    "play" to "M3 5l8 7-8 7zM15 5v14M20 5v14",
    "up" to "M6 15l6-6 6 6",
    "down" to "M6 9l6 6 6-6",
    "left" to "M15 6l-6 6 6 6",
    "right" to "M9 6l6 6-6 6",
    "arrow-left" to "M19 12H5M11 6l-6 6 6 6",
    "check" to "M5 12.5l5 5L19.5 7",
    "info" to "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0M12 11v5M12 8h.01",
    "dpad" to "M12 2.5l3 4h-6zM12 21.5l3-4h-6zM2.5 12l4-3v6zM21.5 12l-4-3v6zM12 10.5a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3",
)

private val FILLED = mapOf(
    "tri-up" to "M12 5l9 13H3z",
    "tri-down" to "M12 19L3 6h18z",
    "tri-left" to "M5 12l13-9v18z",
    "tri-right" to "M19 12L6 3v18z",
)

/** A stroked icon by name. */
@Composable
fun Glyph(name: String, color: Color, modifier: Modifier = Modifier.size(22.dp), strokeWidth: Float = 2f) {
    val path = remember(name) { PathParser().parsePathString(STROKED.getValue(name)).toPath() }
    Canvas(modifier) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A solid triangle arrow (the CRT style's D-pad). */
@Composable
fun Triangle(direction: String, color: Color, modifier: Modifier = Modifier.size(30.dp)) {
    val path = remember(direction) { PathParser().parsePathString(FILLED.getValue("tri-$direction")).toPath() }
    Canvas(modifier) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) { drawPath(path, color) }
    }
}

/** An app's logo from [APP_LOGOS] as a filled shape, with a faint halo when [halo] is set. */
@Composable
fun LogoMark(logo: AppLogo, color: Color, modifier: Modifier = Modifier, halo: Boolean = false) {
    val path = remember(logo) { PathParser().parsePathString(logo.path).toPath() }
    Canvas(modifier) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) {
            if (halo) drawPath(path, color.copy(alpha = 0.28f), style = Stroke(1.8f, join = StrokeJoin.Round))
            drawPath(path, color)
        }
    }
}
