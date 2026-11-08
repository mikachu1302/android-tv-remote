package io.github.mikachu1302.tvremote

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * The launcher icons are generated from the palettes (`tools/gen_launcher_icons.py`). If a
 * palette changes and the script was not re-run, these fail.
 */
class LauncherResourcesTest {
    private fun file(path: String): File =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())

    @Test
    fun everyPaletteHasAnIconInItsOwnColours() {
        val colors = file("res/values/launcher_colors.xml").readText()
        val manifest = file("AndroidManifest.xml").readText()
        Palettes.all.forEach { p ->
            val fg = file("res/drawable/ic_launcher_fg_${p.id}.xml").readText()
            assertTrue("${p.name}: TV colour", fg.contains(hex(p.primary)))
            assertTrue("${p.name}: remote colour", fg.contains(hex(p.action)))
            assertTrue("${p.name}: background", colors.contains("ic_launcher_bg_${p.id}\">${hex(p.bg)}"))
            assertTrue("${p.name}: adaptive icon", file("res/mipmap-anydpi/ic_launcher_${p.id}.xml").exists())
            assertTrue("${p.name}: alias", manifest.contains(".${LauncherIcon.aliasName(p.id)}\""))
        }
    }

    @Test
    fun everyPrideIconExistsAndShowsTheFlag() {
        val manifest = file("AndroidManifest.xml").readText()
        val stripes = listOf("E40303", "FF8C00", "FFED00", "008026", "004DFF", "750787")
        val chevron = listOf("000000", "613915", "74D7EE", "FFAFC8", "FFFFFF")
        IconChoice.entries.mapNotNull { it.iconId }.forEach { id ->
            val fg = file("res/drawable/ic_launcher_fg_$id.xml").readText().uppercase()
            assertTrue("$id: adaptive icon", file("res/mipmap-anydpi/ic_launcher_$id.xml").exists())
            assertTrue("$id: alias", manifest.contains(".${LauncherIcon.aliasName(id)}\""))
            // The rainbow edge shows the six stripes as a gradient only; the others show the whole flag.
            stripes.forEach { assertTrue("$id: missing #$it", fg.contains("#$it")) }
            if (id != "pride_rainbow") chevron.forEach { assertTrue("$id: missing chevron #$it", fg.contains("#$it")) }
        }
        assertTrue(file("res/drawable/ic_launcher_bg_pride_night.xml").exists())
    }

    @Test
    fun prideIconsAreInsideTheLauncherSafeZone() {
        // Launchers show only the central ~66 of the 108 units: the art is scaled down to fit (see the generator).
        val scale = Regex("""scaleX="([0-9.]+)"""")
        IconChoice.entries.mapNotNull { it.iconId }.plus(Palettes.all.map { it.id }).forEach { id ->
            val fg = file("res/drawable/ic_launcher_fg_$id.xml").readText()
            val outer = scale.find(fg)!!.groupValues[1].toDouble()
            assertTrue("$id: scale $outer is too large", outer <= 0.75)
        }
    }

    @Test
    fun prideHasAPaletteWithSixStripes() {
        val pride = Palettes.byId("pride")
        assertEquals("Pride", pride.name)
        assertEquals(6, pride.stripes!!.size)
        assertEquals(6, pride.stripes!!.toSet().size)
    }

    @Test
    fun aliasFollowsPaletteAndIconChoice() {
        assertEquals("LauncherNeon", LauncherIcon.aliasFor(RemoteSettings()))
        assertEquals("LauncherNord", LauncherIcon.aliasFor(RemoteSettings(palette = "nord")))
        assertEquals("LauncherPride", LauncherIcon.aliasFor(RemoteSettings(palette = "pride")))
        assertEquals("LauncherPrideBezel", LauncherIcon.aliasFor(RemoteSettings(palette = "nord", icon = IconChoice.PrideBezel)))
        assertEquals("LauncherPrideBig", LauncherIcon.aliasFor(RemoteSettings(icon = IconChoice.PrideBig)))
        assertEquals("LauncherNeon", LauncherIcon.aliasFor(RemoteSettings(palette = "unknown")))
    }
}
