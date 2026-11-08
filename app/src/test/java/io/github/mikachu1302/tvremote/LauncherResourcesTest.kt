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
        assertTrue("Pride alias", manifest.contains(".${LauncherIcon.aliasName("pride")}\""))
    }

    @Test
    fun prideIconHasEveryProgressFlagColour() {
        val fg = file("res/drawable/ic_launcher_fg_pride.xml").readText().uppercase()
        // Six stripes, plus the chevron: white, pink, light blue, brown, black.
        listOf("E40303", "FF8C00", "FFED00", "008026", "004DFF", "750787", "FFFFFF", "FFAFC8", "74D7EE", "613915", "000000")
            .forEach { assertTrue("missing #$it", fg.contains("#$it")) }
    }

    @Test
    fun aliasFollowsPaletteAndIconChoice() {
        assertEquals("LauncherNeon", LauncherIcon.aliasFor(RemoteSettings()))
        assertEquals("LauncherNord", LauncherIcon.aliasFor(RemoteSettings(palette = "nord")))
        assertEquals("LauncherPride", LauncherIcon.aliasFor(RemoteSettings(palette = "nord", icon = IconChoice.Pride)))
        assertEquals("LauncherNeon", LauncherIcon.aliasFor(RemoteSettings(palette = "unknown")))
    }
}
