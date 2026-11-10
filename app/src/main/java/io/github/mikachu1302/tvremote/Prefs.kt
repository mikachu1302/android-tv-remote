package io.github.mikachu1302.tvremote

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which launcher icon to show: the one in the selected colours, or one of the Progress Pride ones
 * ([iconId] is the generated icon's name, see tools/gen_launcher_icons.py).
 */
enum class IconChoice(val label: String, val iconId: String?) {
    Match("Match colors", null),
    PrideBezel("Pride bezel", "pride_bezel"),
    PrideLight("Pride light", "pride_light"),
    PrideRainbow("Pride rainbow", "pride_rainbow"),
    PrideNight("Pride night", "pride_night"),
    PrideBig("Pride big flag", "pride_big"),
}

/** The "remote behavior" options. */
data class RemoteSettings(
    val haptics: Boolean = true,
    /** Holding volume, channel or a direction acts like holding a key on a physical remote. */
    val smoothPress: Boolean = true,
    val keepAwake: Boolean = true,
    /** Dims the screen when idle (for watching TV in the dark). */
    val theater: Boolean = false,
    val style: UiStyle = UiStyle.Crt,
    /** A [Palette] id. */
    val palette: String = "neon",
    /** Scanlines over the CRT style. */
    val scanlines: Boolean = true,
    val icon: IconChoice = IconChoice.Match,
    /** The volume keys control the TV itself (UPnP), when one is chosen and answers; else the stick. */
    val tvVolume: Boolean = true,
)

/** A tile in the app row: opens [link] on the TV (a web link, a Play Store page, `dial://<app>`, or `key://<code>`). */
data class AppShortcut(val name: String, val link: String)

/**
 * An app the stick starts through its DIAL service (`POST http://<stick>:8008/apps/<name>`, the protocol Cast and
 * phone apps use) rather than through an app link. For apps that ignore app links.
 */
fun dialLink(app: String) = "dial://$app"

/** The DIAL application name in a [dialLink], or null for any other link. */
fun dialApp(link: String): String? = if (link.startsWith("dial://")) link.removePrefix("dial://").ifBlank { null } else null

/** The Play Store page of a package. The stick shows it; the user presses OK on "Open" (or "Install"). */
fun playStoreLink(pkg: String) = "https://play.google.com/store/apps/details?id=$pkg"

/** The old "launch" link of a package, which the waipu stick ignores. Only recognised in saved lists, to upgrade them. */
private const val OLD_LAUNCH_PREFIX = "market://launch?id="

/**
 * What a user may type for a custom shortcut: a link as is, or an Android package name (`com.example.app`), which
 * becomes the package's Play Store page.
 */
fun customLink(input: String): String {
    val text = input.trim()
    return if ("://" in text) text else playStoreLink(text)
}

/** A key press as a shortcut: `key://<Android key code>`. On a waipu stick the Home key is the waipu.tv app. */
fun keyLink(code: Int) = "key://$code"

/** The key code in a [keyLink], or null for any other link. */
fun keyCode(link: String): Int? = if (link.startsWith("key://")) link.removePrefix("key://").toIntOrNull() else null

private class CatalogApp(val name: String, val pkg: String, val link: String = playStoreLink(pkg))

/**
 * Apps to pick from: name, Android TV package (to recognise the open app) and the link that opens it.
 *
 * The stick does NOT open an app from a package name: `market://launch?id=`, `android-app://` and `intent:` links are
 * all ignored (tried on a real waipu stick). What works: a web link the TV app registers (YouTube, Prime Video, Disney+,
 * ZDF, ARD Mediathek), the stick's DIAL service (Netflix), a key (Home is waipu.tv), and, for any other app, its Play
 * Store page ([playStoreLink]): it opens on the stick and the user presses OK on "Open" (what the old Remote ATV app does).
 */
private val CATALOG_APPS = listOf(
    CatalogApp("YouTube", "com.google.android.youtube.tv", "https://www.youtube.com"),
    CatalogApp("Netflix", "com.netflix.ninja", dialLink("Netflix")),
    CatalogApp("Prime Video", "com.amazon.amazonvideo.livingroom", "https://app.primevideo.com"),
    CatalogApp("Disney+", "com.disney.disneyplus", "https://www.disneyplus.com"),
    CatalogApp("waipu.tv", "de.exaring.waipu", keyLink(3)),
    CatalogApp("Apple TV", "com.apple.atve.androidtv.appletv"),
    CatalogApp("Paramount+", "com.cbs.ott"),
    CatalogApp("HBO Max", "com.wbd.stream"),
    CatalogApp("Twitch", "tv.twitch.android.app"),
    CatalogApp("Crunchyroll", "com.crunchyroll.crunchyroid"),
    CatalogApp("DAZN", "com.dazn"),
    CatalogApp("Joyn", "de.prosiebensat1digital.seventv"),
    CatalogApp("RTL+", "de.rtli.tvnow"),
    CatalogApp("WOW", "de.sky.online"),
    CatalogApp("ARD Mediathek", "de.swr.avp.ard.tv", "https://www.ardmediathek.de"),
    CatalogApp("ZDF", "com.zdf.android.mediathek", "https://www.zdf.de"),
    CatalogApp("ARTE", "tv.arte.plus7"),
    CatalogApp("Spotify", "com.spotify.tv.android"),
    CatalogApp("YouTube Music", "com.google.android.youtube.tvmusic"),
    CatalogApp("Amazon Music", "com.amazon.music.tv"),
    CatalogApp("Pluto TV", "tv.pluto.android"),
    CatalogApp("Tubi", "com.tubitv"),
    CatalogApp("Plex", "com.plexapp.android"),
    CatalogApp("Kodi", "org.xbmc.kodi"),
    CatalogApp("VLC", "org.videolan.vlc"),
)

val CATALOG: List<AppShortcut> = CATALOG_APPS.map { AppShortcut(it.name, it.link) }

val DEFAULT_APPS = CATALOG.take(5)

private val PACKAGE_NAMES = CATALOG_APPS.associate { it.pkg to it.name }

private val GENERIC_PACKAGE_PARTS = setOf(
    "com", "org", "net", "io", "de", "tv", "app", "android", "androidtv", "google", "ott", "mobile", "tvapp",
)

/** A readable name from a package like `tv.twitch.android.app` -> "Twitch". */
fun guessAppName(pkg: String): String {
    val parts = pkg.split('.')
    val name = parts.lastOrNull { it !in GENERIC_PACKAGE_PARTS } ?: parts.last()
    return name.replaceFirstChar { it.uppercase() }
}

/** The shortcut for an app the TV reports as open: the catalog's entry when we know it (else a name only). */
fun shortcutForPackage(pkg: String, label: String? = null): AppShortcut =
    PACKAGE_NAMES[pkg]?.let { name -> CATALOG.first { it.name == name } }
        ?: AppShortcut(label ?: guessAppName(pkg), playStoreLink(pkg))

fun SharedPreferences.loadSettings() = RemoteSettings(
    haptics = getBoolean("haptics", true),
    smoothPress = getBoolean("smoothPress", true),
    keepAwake = getBoolean("keepAwake", true),
    theater = getBoolean("theater", false),
    style = UiStyle.entries.firstOrNull { it.name == getString("style", null) } ?: UiStyle.Crt,
    palette = getString("palette", null) ?: "neon",
    scanlines = getBoolean("scanlines", true),
    // "Pride" is what 0.2.x saved before there were five Pride icons.
    icon = getString("icon", null).let { saved ->
        IconChoice.entries.firstOrNull { it.name == saved } ?: if (saved == "Pride") IconChoice.PrideBezel else IconChoice.Match
    },
    tvVolume = getBoolean("tvVolume", true),
)

fun SharedPreferences.Editor.putSettings(s: RemoteSettings): SharedPreferences.Editor = this
    .putBoolean("haptics", s.haptics)
    .putBoolean("smoothPress", s.smoothPress)
    .putBoolean("keepAwake", s.keepAwake)
    .putBoolean("theater", s.theater)
    .putString("style", s.style.name)
    .putString("palette", s.palette)
    .putBoolean("scanlines", s.scanlines)
    .putString("icon", s.icon.name)
    .putBoolean("tvVolume", s.tvVolume)

/** Links an earlier version saved for an app and that did not work: shortcuts that carry one get the working link. */
private val LEGACY_LINKS = mapOf(
    "https://www.netflix.com" to dialLink("Netflix"),
)

/** An old package-launch link becomes its app's current catalog link, or the package's Play Store page; anything else stays. */
private fun currentLink(link: String): String {
    LEGACY_LINKS[link]?.let { return it }
    if (!link.startsWith(OLD_LAUNCH_PREFIX)) return link
    val pkg = link.removePrefix(OLD_LAUNCH_PREFIX)
    return PACKAGE_NAMES[pkg]?.let { name -> CATALOG.first { it.name == name }.link } ?: playStoreLink(pkg)
}

fun parseApps(json: String?): List<AppShortcut> {
    if (json == null) return DEFAULT_APPS
    return runCatching {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            AppShortcut(o.getString("name"), currentLink(o.getString("link")))
        }
    }.getOrDefault(DEFAULT_APPS)
}

fun appsToJson(apps: List<AppShortcut>): String =
    JSONArray(apps.map { JSONObject().put("name", it.name).put("link", it.link) }).toString()

fun parseTvs(json: String?): List<PairedTv> {
    if (json == null) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            PairedTv(o.getString("name"), o.getString("host"), o.getString("pin"))
        }
    }.getOrDefault(emptyList())
}

fun tvsToJson(tvs: List<PairedTv>): String =
    JSONArray(tvs.map { JSONObject().put("name", it.name).put("host", it.host).put("pin", it.pin) }).toString()

fun parseAudioTv(json: String?): AudioTv? {
    if (json == null) return null
    return runCatching {
        val o = JSONObject(json)
        AudioTv(o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("control"), o.optString("mac").ifEmpty { null })
    }.getOrNull()
}

fun audioTvToJson(tv: AudioTv): String =
    JSONObject().put("name", tv.name).put("host", tv.host).put("port", tv.port).put("control", tv.control)
        .apply { tv.mac?.let { put("mac", it) } }.toString()
