package io.github.mikachu1302.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalSettings = staticCompositionLocalOf { RemoteSettings() }

/** True for buttons a swipe can start on: the swipeable pages, and the bottom row next to the home-gesture area (see [RemoteButton]). */
val LocalSwipeable = staticCompositionLocalOf { false }

/** How long a touch on a swipeable page waits to see whether it becomes a swipe. */
private const val SWIPE_WINDOW_MS = 100L

private class Released(val up: PointerInputChange?)

/** Which colour a key speaks in: plain, "alive" (plus/minus, play) or the action colour (OK, mic). */
enum class Look { Normal, Primary, Action }

/**
 * One remote button, drawn for the current style. Fires on press (not release) so it feels
 * immediate. With [repeat], holding it keeps the key going: "smooth press" holds the key down on
 * the TV like a physical remote, otherwise a key press is repeated. A null [keyCode] makes it a
 * plain button ([onClick] on release, [onDown]/[onUp] for hold actions). [active] marks a
 * toggled-on state in [activeColor]; [strong] keeps the glowing border; [ghost] draws no
 * background or border (arrows inside the glass dial). [content] gets the colour to draw in.
 */
@Composable
fun RemoteButton(
    description: String,
    keyCode: Int?,
    vm: RemoteViewModel,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    look: Look = Look.Normal,
    active: Boolean = false,
    activeColor: Color? = null,
    strong: Boolean = false,
    ghost: Boolean = false,
    shape: Shape? = null,
    onClick: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    content: @Composable (Color) -> Unit,
) {
    val term = LocalTerm.current
    val p = term.palette
    val haptic = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val swipeable = LocalSwipeable.current
    var pressed by remember { mutableStateOf(false) }

    val keyShape = shape ?: term.controlShape
    val accent = when (look) {
        Look.Action -> p.action
        else -> p.primary
    }
    val glowColor = when {
        active -> activeColor ?: accent
        strong -> accent
        else -> null
    }
    val contentColor = when {
        active -> activeColor ?: accent
        look == Look.Primary -> p.primary
        look == Look.Action -> p.action
        term.crt -> p.text
        else -> p.dim
    }
    val fill = when {
        ghost -> Color.Transparent
        pressed -> accent.copy(alpha = 0.30f).compositeOver(p.surface)
        strong && !term.crt -> p.bg
        else -> p.surface
    }
    val base = if (glowColor != null && !ghost) modifier.glow(glowColor, keyShape) else modifier
    Box(
        base
            .background(fill, keyShape)
            .let {
                when {
                    ghost -> it
                    glowColor != null -> it.border(2.dp, glowColor, keyShape)
                    else -> it.border(if (term.crt) 2.dp else 1.dp, if (term.crt) p.border else p.primary.copy(alpha = 0.30f), keyShape)
                }
            }
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(keyCode, repeat, settings.smoothPress, settings.haptics, swipeable) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    // The touch may be a swipe (next page, or the system home gesture): wait briefly. If the
                    // pager or the system takes the touch over, nothing is sent; a quick tap is sent as one short press.
                    var released: PointerInputChange? = null
                    if (swipeable) {
                        val early = withTimeoutOrNull(SWIPE_WINDOW_MS) { Released(waitForUpOrCancellation()) }
                        if (early != null && early.up == null) {
                            pressed = false
                            return@awaitEachGesture
                        }
                        released = early?.up
                    }
                    if (settings.haptics) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onDown?.invoke()
                    val smooth = repeat && keyCode != null && settings.smoothPress && released == null
                    if (keyCode != null) vm.press(keyCode, if (smooth) Direction.StartLong else Direction.Short)
                    val ticker = if (repeat && keyCode != null && !smooth && released == null) scope.launch {
                        delay(400)
                        while (true) {
                            vm.press(keyCode)
                            delay(150)
                        }
                    } else null
                    try {
                        val up = released ?: waitForUpOrCancellation()
                        if (up != null) onClick?.invoke()
                    } finally {
                        // Also runs when the button leaves the screen mid-press: never leave a key held down.
                        ticker?.cancel()
                        if (smooth) vm.press(keyCode!!, Direction.EndLong)
                        pressed = false
                        onUp?.invoke()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { content(contentColor) }
}

/**
 * An app's tile: its logo when we have one (brand colours in the glass style, the palette's
 * "alive" colour with a faint glow in the CRT style), else its initials. [current] marks the app
 * that is open on the TV.
 */
@Composable
fun AppTile(name: String, modifier: Modifier = Modifier, current: Boolean = false) {
    val term = LocalTerm.current
    val p = term.palette
    val shape: Shape = if (term.crt) RoundedCornerShape(0.dp) else RoundedCornerShape(percent = 30)
    val logo = APP_LOGOS[name]
    Box(modifier.pane(shape, glowColor = if (current) p.action else null), contentAlignment = Alignment.Center) {
        if (logo != null) {
            val color = when {
                term.crt -> if (current) p.action else p.primary
                else -> logo.brand ?: p.text
            }
            LogoMark(logo, color, Modifier.fillMaxSize().padding(fraction(0.26f)), halo = term.crt)
        } else {
            val initials = name.filter { it.isLetterOrDigit() }.take(2).replaceFirstChar { it.uppercase() }
            Text(
                initials, color = if (term.crt) (if (current) p.action else p.primary) else p.soft,
                fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

private fun fraction(f: Float) = (56 * f).dp
