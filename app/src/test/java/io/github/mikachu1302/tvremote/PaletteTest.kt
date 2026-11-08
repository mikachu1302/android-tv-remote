package io.github.mikachu1302.tvremote

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Every palette, now and later, has to be readable: contrast per WCAG 2. */
class PaletteTest {
    private fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> if (la > lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun violations(p: Palette, what: String, fg: Color, bg: Color, min: Double): List<String> =
        if (contrast(fg, bg) >= min) emptyList() else listOf("${p.name}: $what ${"%.2f".format(contrast(fg, bg))}:1 (needs $min)")

    @Test
    fun everyPaletteIsReadable() {
        val problems = Palettes.all.flatMap { p ->
            listOf("background" to p.bg, "surface" to p.surface).flatMap { (bgName, bg) ->
                violations(p, "text on $bgName", p.text, bg, 7.0) +
                    violations(p, "dim text on $bgName", p.dim, bg, 4.5) +
                    violations(p, "primary on $bgName", p.primary, bg, 4.5) +
                    violations(p, "action on $bgName", p.action, bg, 4.5) +
                    violations(p, "soft on $bgName", p.soft, bg, 4.5) +
                    violations(p, "warn on $bgName", p.warn, bg, 4.5)
            } + violations(p, "border on background", p.border, p.bg, 3.0)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun idsAreUniqueAndLookupFallsBack() {
        assertEquals(Palettes.all.size, Palettes.all.map { it.id }.toSet().size)
        assertEquals(Palettes.Neon, Palettes.byId("nope"))
        assertEquals(Palettes.Nord, Palettes.byId("nord"))
    }
}
