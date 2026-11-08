package io.github.mikachu1302.tvremote

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** The "remote behavior" options. */
data class RemoteSettings(
    val haptics: Boolean = true,
    /** Holding volume, channel or a direction acts like holding a key on a physical remote. */
    val smoothPress: Boolean = true,
    val keepAwake: Boolean = true,
    /** Dims the screen when idle (for watching TV in the dark). */
    val theater: Boolean = false,
)

/** A tile in the app row: opens [link] on the TV (a deep link or `market://launch?id=<package>`). */
data class AppShortcut(val name: String, val link: String)

val DEFAULT_APPS = listOf(
    AppShortcut("YouTube", "https://www.youtube.com"),
    AppShortcut("Netflix", "https://www.netflix.com"),
    AppShortcut("Prime Video", "https://app.primevideo.com"),
    AppShortcut("Disney+", "https://www.disneyplus.com"),
)

fun SharedPreferences.loadSettings() = RemoteSettings(
    haptics = getBoolean("haptics", true),
    smoothPress = getBoolean("smoothPress", true),
    keepAwake = getBoolean("keepAwake", true),
    theater = getBoolean("theater", false),
)

fun SharedPreferences.Editor.putSettings(s: RemoteSettings): SharedPreferences.Editor = this
    .putBoolean("haptics", s.haptics)
    .putBoolean("smoothPress", s.smoothPress)
    .putBoolean("keepAwake", s.keepAwake)
    .putBoolean("theater", s.theater)

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
