package io.github.mikachu1302.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/** The two looks. Layout stays the same; shapes, borders, fonts and glow differ. */
enum class UiStyle(val label: String, val blurb: String) {
    Crt("Neon CRT", "Square, glowing, scanlines"),
    Glass("Glass", "Round, soft, dial"),
}

/**
 * One colour scheme. [primary] means "alive" (on, connected, focus, plus and minus keys),
 * [action] is the action (OK, the app that is open, the mic), [soft] a quiet third colour.
 */
data class Palette(
    val id: String,
    val name: String,
    val bg: Color,
    val surface: Color,
    /** Borders of controls: at least 3:1 against [bg]. */
    val border: Color,
    val text: Color,
    /** Secondary text: at least 4.5:1 against [bg]. */
    val dim: Color,
    val primary: Color,
    val action: Color,
    val soft: Color,
    val warn: Color,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

object Palettes {
    val Neon = Palette(
        "neon", "Neon", bg = c(0x07090A), surface = c(0x0E1411), border = c(0x3A6B49),
        text = c(0xC9FFD6), dim = c(0x86B995), primary = c(0x39FF14), action = c(0xFF2E97),
        soft = c(0xFF8FB7), warn = c(0xFFB454),
    )
    val Dracula = Palette(
        "dracula", "Dracula", bg = c(0x1E1F29), surface = c(0x282A36), border = c(0x6272A4),
        text = c(0xF8F8F2), dim = c(0xA4ADCF), primary = c(0x50FA7B), action = c(0xFF79C6),
        soft = c(0xBD93F9), warn = c(0xF1FA8C),
    )
    val Synthwave = Palette(
        "synthwave", "Synthwave", bg = c(0x120B24), surface = c(0x1D1238), border = c(0x7A58C9),
        text = c(0xF0E6FF), dim = c(0xB9A7E6), primary = c(0x36F9F6), action = c(0xFF3CAC),
        soft = c(0xFFE66D), warn = c(0xFFB454),
    )
    val Catppuccin = Palette(
        "catppuccin", "Catppuccin", bg = c(0x1E1E2E), surface = c(0x313244), border = c(0x6C7086),
        text = c(0xCDD6F4), dim = c(0xA6ADC8), primary = c(0xA6E3A1), action = c(0xCBA6F7),
        soft = c(0xF5C2E7), warn = c(0xF9E2AF),
    )
    val Gruvbox = Palette(
        "gruvbox", "Gruvbox", bg = c(0x1D2021), surface = c(0x282828), border = c(0x7C6F64),
        text = c(0xEBDBB2), dim = c(0xA89984), primary = c(0xB8BB26), action = c(0xFE8019),
        soft = c(0xFABD2F), warn = c(0xFF6B57),
    )
    val Tokyo = Palette(
        "tokyo", "Tokyo Night", bg = c(0x1A1B26), surface = c(0x24283B), border = c(0x6A73A0),
        text = c(0xC0CAF5), dim = c(0x9AA5CE), primary = c(0x7AA2F7), action = c(0xBB9AF7),
        soft = c(0x7DCFFF), warn = c(0xE0AF68),
    )
    val Nord = Palette(
        "nord", "Nord", bg = c(0x2E3440), surface = c(0x3B4252), border = c(0x7B88A1),
        text = c(0xECEFF4), dim = c(0xAEB7C8), primary = c(0xA3BE8C), action = c(0x88C0D0),
        soft = c(0xEBCB8B), warn = c(0xE8A08A),
    )
    val Amber = Palette(
        "amber", "Amber CRT", bg = c(0x0C0A05), surface = c(0x14100A), border = c(0x7A5A12),
        text = c(0xFFD98A), dim = c(0xC49A3C), primary = c(0xFFB000), action = c(0xFF6A00),
        soft = c(0xFFD166), warn = c(0xFF4D4D),
    )
    val Matrix = Palette(
        "matrix", "Matrix", bg = c(0x020A04), surface = c(0x06140A), border = c(0x1F7A3A),
        text = c(0xB8FFC8), dim = c(0x5FD47B), primary = c(0x00FF41), action = c(0xCCFF00),
        soft = c(0x00CC66), warn = c(0xFFD23F),
    )

    val all = listOf(Neon, Dracula, Synthwave, Catppuccin, Gruvbox, Tokyo, Nord, Amber, Matrix)

    fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: Neon
}

val VT323 = FontFamily(Font(R.font.vt323_regular, FontWeight.Normal))

val FiraCode = FontFamily(
    Font(R.font.fira_code, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.fira_code, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** What every composable asks for: the style, the colours, and what follows from them. */
class TermTheme(val style: UiStyle, val palette: Palette, val scanlines: Boolean) {
    val crt get() = style == UiStyle.Crt
    val font: FontFamily get() = if (crt) VT323 else FiraCode

    /** Shape of keys, tiles and panes. */
    val controlShape: Shape get() = if (crt) RectangleShape else RoundedCornerShape(16.dp)

    val buttonShape: Shape get() = if (crt) RectangleShape else RoundedCornerShape(12.dp)

    /** VT323 is drawn small: text is scaled up so its size matches the other font. */
    val fontScale: Float get() = if (crt) 1.3f else 1f
}

val LocalTerm = staticCompositionLocalOf { TermTheme(UiStyle.Crt, Palettes.Neon, true) }

private fun typography(font: FontFamily): Typography {
    val b = Typography()
    // Line heights follow the font: the defaults assume a different font and get scaled up with it.
    fun TextStyle.f() = copy(fontFamily = font, lineHeight = TextUnit.Unspecified)
    return Typography(
        displayLarge = b.displayLarge.f(), displayMedium = b.displayMedium.f(), displaySmall = b.displaySmall.f(),
        headlineLarge = b.headlineLarge.f(), headlineMedium = b.headlineMedium.f(), headlineSmall = b.headlineSmall.f(),
        titleLarge = b.titleLarge.f(), titleMedium = b.titleMedium.f(), titleSmall = b.titleSmall.f(),
        bodyLarge = b.bodyLarge.f(), bodyMedium = b.bodyMedium.f(), bodySmall = b.bodySmall.f(),
        labelLarge = b.labelLarge.f(), labelMedium = b.labelMedium.f(), labelSmall = b.labelSmall.f(),
    )
}

private fun colorScheme(p: Palette) = darkColorScheme(
    primary = p.primary, onPrimary = p.bg,
    primaryContainer = p.surface, onPrimaryContainer = p.primary,
    secondary = p.action, onSecondary = p.bg,
    tertiary = p.soft, onTertiary = p.bg,
    background = p.bg, onBackground = p.text,
    surface = p.bg, onSurface = p.text,
    surfaceVariant = p.surface, onSurfaceVariant = p.dim,
    surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface,
    outline = p.border, outlineVariant = p.border,
    error = p.warn, onError = p.bg,
)

private fun shapes(crt: Boolean): Shapes = if (crt) {
    val square = RoundedCornerShape(0.dp)
    Shapes(square, square, square, square, square)
} else {
    Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp),
    )
}

@Composable
fun TvRemoteTheme(style: UiStyle, palette: Palette, scanlines: Boolean, content: @Composable () -> Unit) {
    val term = TermTheme(style, palette, scanlines)
    val d = LocalDensity.current
    // Scaling the font scale makes every `sp` in the app follow the style's font.
    val density = if (term.fontScale == 1f) d else Density(d.density, d.fontScale * term.fontScale)
    CompositionLocalProvider(LocalTerm provides term, LocalDensity provides density) {
        MaterialTheme(colorScheme = colorScheme(palette), typography = typography(term.font), shapes = shapes(term.crt), content = content)
    }
}

// ------------------------------------------------------------------ drawing helpers

/** A glow around [shape]: a few wide, faint strokes of [color] drawn behind the element. */
fun Modifier.glow(color: Color, shape: Shape, width: Dp = 2.dp): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val w = width.toPx()
    for ((mult, alpha) in listOf(9f to 0.05f, 6f to 0.08f, 3.5f to 0.16f)) {
        strokeOutline(outline, color.copy(alpha = alpha), w * mult)
    }
}

private fun DrawScope.strokeOutline(outline: Outline, color: Color, width: Float) {
    when (outline) {
        is Outline.Rectangle -> drawRect(color, outline.rect.topLeft, outline.rect.size, style = Stroke(width))
        is Outline.Rounded -> {
            val r = outline.roundRect
            drawRoundRect(
                color, Offset(r.left, r.top), Size(r.width, r.height),
                CornerRadius(r.topLeftCornerRadius.x, r.topLeftCornerRadius.y), style = Stroke(width),
            )
        }
        is Outline.Generic -> drawPath(outline.path, color, style = Stroke(width))
    }
}

/** A pane in the current style: surface fill and border, glowing when [glowColor] is given. */
@Composable
fun Modifier.pane(shape: Shape = LocalTerm.current.controlShape, glowColor: Color? = null): Modifier {
    val t = LocalTerm.current
    val border = glowColor ?: if (t.crt) t.palette.border else t.palette.primary.copy(alpha = 0.30f)
    val glowing = if (glowColor != null) glow(glowColor, shape) else this
    return glowing.background(t.palette.surface, shape).border(if (t.crt) 2.dp else 1.dp, border, shape)
}

/** CRT scanlines and a darkened rim over everything below it. */
fun Modifier.crtOverlay(): Modifier = drawWithContent {
    drawContent()
    val line = Color.Black.copy(alpha = 0.22f)
    val step = 3.dp.toPx()
    val thickness = 1.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, thickness))
        y += step
    }
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f),
            center = center, radius = hypot(size.width, size.height) / 2,
        ),
    )
}

/** The screen background: flat for the CRT style, soft colour glows in two corners for glass. */
@Composable
fun Modifier.termBackground(): Modifier {
    val t = LocalTerm.current
    val p = t.palette
    return if (t.crt) {
        background(p.bg)
    } else {
        background(p.bg).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(p.action.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(size.width * 0.92f, 0f), radius = size.width * 1.1f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(p.primary.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(0f, size.height), radius = size.width * 1.0f,
                ),
            )
        }
    }
}

// ------------------------------------------------------------------ themed Material buttons

@Composable
fun TButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier, enabled, shape = LocalTerm.current.buttonShape, content = content)

@Composable
fun TOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    OutlinedButton(onClick, modifier, enabled, shape = LocalTerm.current.buttonShape, content = content)

@Composable
fun TTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) = TextButton(onClick, modifier, enabled, shape = LocalTerm.current.buttonShape, contentPadding = contentPadding, content = content)
