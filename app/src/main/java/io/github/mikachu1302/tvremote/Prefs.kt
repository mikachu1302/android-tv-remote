package io.github.mikachu1302.tvremote

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Which launcher icon to show: the one in the selected colours, or the Progress Pride one. */
enum class IconChoice { Match, Pride }

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
)

/** A tile in the app row: opens [link] on the TV (a deep link or `market://launch?id=<package>`). */
data class AppShortcut(val name: String, val link: String)

/** Opens any installed app by its Android package name (or its Play Store page if it is missing). */
fun marketLink(pkg: String) = "market://launch?id=$pkg"

/**
 * What a user may type for a custom shortcut: a web/deep link as is, or an
 * Android package name (`com.example.app`), which becomes a market link.
 */
fun customLink(input: String): String {
    val text = input.trim()
    return if ("://" in text) text else marketLink(text)
}

/**
 * Apps to pick from. Package names were checked against Google Play. The
 * first four use web links the Android TV apps register; the rest launch by
 * package name. A package that is not installed opens its store page.
 */
val CATALOG = listOf(
    AppShortcut("YouTube", "https://www.youtube.com"),
    AppShortcut("Netflix", "https://www.netflix.com"),
    AppShortcut("Prime Video", "https://app.primevideo.com"),
    AppShortcut("Disney+", "https://www.disneyplus.com"),
    AppShortcut("waipu.tv", marketLink("de.exaring.waipu")),
    AppShortcut("Apple TV", marketLink("com.apple.atve.androidtv.appletv")),
    AppShortcut("Paramount+", marketLink("com.cbs.ott")),
    AppShortcut("HBO Max", marketLink("com.wbd.stream")),
    AppShortcut("Twitch", marketLink("tv.twitch.android.app")),
    AppShortcut("Crunchyroll", marketLink("com.crunchyroll.crunchyroid")),
    AppShortcut("DAZN", marketLink("com.dazn")),
    AppShortcut("Joyn", marketLink("de.prosiebensat1digital.seventv")),
    AppShortcut("RTL+", marketLink("de.rtli.tvnow")),
    AppShortcut("WOW", marketLink("de.sky.online")),
    AppShortcut("ARD Mediathek", marketLink("de.swr.avp.ard.tv")),
    AppShortcut("ZDF", marketLink("com.zdf.android.mediathek")),
    AppShortcut("ARTE", marketLink("tv.arte.plus7")),
    AppShortcut("Spotify", marketLink("com.spotify.tv.android")),
    AppShortcut("YouTube Music", marketLink("com.google.android.youtube.tvmusic")),
    AppShortcut("Amazon Music", marketLink("com.amazon.music.tv")),
    AppShortcut("Pluto TV", marketLink("tv.pluto.android")),
    AppShortcut("Tubi", marketLink("com.tubitv")),
    AppShortcut("Plex", marketLink("com.plexapp.android")),
    AppShortcut("Kodi", marketLink("org.xbmc.kodi")),
    AppShortcut("VLC", marketLink("org.videolan.vlc")),
)

val DEFAULT_APPS = CATALOG.take(5)

/** The Android TV packages behind the catalog entries that launch by web link. */
private val LINK_APP_PACKAGES = mapOf(
    "com.google.android.youtube.tv" to "YouTube",
    "com.netflix.ninja" to "Netflix",
    "com.amazon.amazonvideo.livingroom" to "Prime Video",
    "com.disney.disneyplus" to "Disney+",
)

private val GENERIC_PACKAGE_PARTS = setOf(
    "com", "org", "net", "io", "de", "tv", "app", "android", "androidtv", "google", "ott", "mobile", "tvapp",
)

/** A readable name from a package like `tv.twitch.android.app` -> "Twitch". */
fun guessAppName(pkg: String): String {
    val parts = pkg.split('.')
    val name = parts.lastOrNull { it !in GENERIC_PACKAGE_PARTS } ?: parts.last()
    return name.replaceFirstChar { it.uppercase() }
}

/** The shortcut for an app the TV reports as open: the catalog's entry when we know it. */
fun shortcutForPackage(pkg: String, label: String? = null): AppShortcut =
    LINK_APP_PACKAGES[pkg]?.let { name -> CATALOG.first { it.name == name } }
        ?: CATALOG.firstOrNull { it.link == marketLink(pkg) }
        ?: AppShortcut(label ?: guessAppName(pkg), marketLink(pkg))

fun SharedPreferences.loadSettings() = RemoteSettings(
    haptics = getBoolean("haptics", true),
    smoothPress = getBoolean("smoothPress", true),
    keepAwake = getBoolean("keepAwake", true),
    theater = getBoolean("theater", false),
    style = UiStyle.entries.firstOrNull { it.name == getString("style", null) } ?: UiStyle.Crt,
    palette = getString("palette", null) ?: "neon",
    scanlines = getBoolean("scanlines", true),
    icon = IconChoice.entries.firstOrNull { it.name == getString("icon", null) } ?: IconChoice.Match,
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

fun parseApps(json: String?): List<AppShortcut> {
    if (json == null) return DEFAULT_APPS
    return runCatching {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            AppShortcut(o.getString("name"), o.getString("link"))
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
