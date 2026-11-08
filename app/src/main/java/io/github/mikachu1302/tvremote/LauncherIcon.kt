package io.github.mikachu1302.tvremote

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Android cannot recolour a launcher icon, so there is one icon per palette (and a Pride one),
 * each behind its own manifest alias (`tools/gen_launcher_icons.py` makes them). Exactly one
 * alias is enabled at a time; switching it makes the launcher show the matching icon.
 */
object LauncherIcon {
    private const val NAMESPACE = "io.github.mikachu1302.tvremote"
    private const val DEFAULT_ALIAS = "LauncherNeon"

    fun aliasName(id: String) = "Launcher" + id.replaceFirstChar { it.uppercase() }

    fun aliasFor(settings: RemoteSettings): String =
        aliasName(if (settings.icon == IconChoice.Pride) "pride" else Palettes.byId(settings.palette).id)

    private val all = Palettes.all.map { aliasName(it.id) } + aliasName("pride")

    /** Makes the alias for [settings] the only enabled one. Does nothing when it already is. */
    fun apply(context: Context, settings: RemoteSettings) {
        val pm = context.packageManager
        val target = aliasFor(settings)

        fun component(name: String) = ComponentName(context.packageName, "$NAMESPACE.$name")
        fun enabled(name: String): Boolean = when (pm.getComponentEnabledSetting(component(name))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> name == DEFAULT_ALIAS // not set: the manifest's default
        }

        if (!enabled(target)) {
            pm.setComponentEnabledSetting(component(target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
        all.filter { it != target && enabled(it) }.forEach {
            pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
