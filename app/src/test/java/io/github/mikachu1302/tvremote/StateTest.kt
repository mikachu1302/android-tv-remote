package io.github.mikachu1302.tvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Messages here are built by hand with made-up values, in the shapes the TV sends.
class StateTest {
    private fun msg(build: PbWriter.() -> Unit) = parsePb(PbWriter().apply(build).toByteArray())

    @Test
    fun startMessageSetsPowerState_andFalseIsAnEmptyMessage() {
        val on = TvState().apply(msg { message(40) { varint(1, 1) } })
        assertEquals(true, on.on)
        // proto3 omits a false bool: the message is present but empty.
        val off = on.apply(msg { message(40) {} })
        assertEquals(false, off.on)
    }

    @Test
    fun switchingOffForgetsTheOpenApp() {
        val open = TvState(on = true, appPackage = "com.example.app", appLabel = "Some App")
        val off = open.apply(msg { message(40) {} })
        assertEquals(false, off.on)
        assertNull(off.appPackage)
        assertNull(off.appLabel)
        // switching on keeps what we know
        assertEquals("com.example.app", open.apply(msg { message(40) { varint(1, 1) } }).appPackage)
    }

    @Test
    fun volumeMessageGivesLevelMaxAndMute() {
        val s = TvState().apply(msg { message(50) { string(3, "x"); varint(6, 100); varint(7, 17); varint(8, 1) } })
        assertEquals(Volume(level = 17, max = 100, muted = true), s.volume)
        val unmuted = s.apply(msg { message(50) { varint(6, 100); varint(7, 18) } })
        assertEquals(Volume(18, 100, false), unmuted.volume)
    }

    @Test
    fun volumeMessageWithoutLevelIsIgnored() {
        // What the real stick sends: a player name and flags, no maximum or level.
        val s = TvState().apply(msg { message(50) { varint(1, 3063); varint(2, 9); string(3, "player"); varint(4, 1) } })
        assertNull(s.volume)
    }

    @Test
    fun imeKeyInjectCarriesAppAndTextFieldAndCounters() {
        val s = TvState().apply(msg {
            message(20) {
                message(1) { varint(1, 5); string(10, "Some App"); string(12, "com.example.app") }
                message(2) { varint(1, 9); string(2, "hello"); string(6, "Search") }
            }
        })
        assertEquals("com.example.app", s.appPackage)
        assertEquals("Some App", s.appLabel)
        assertEquals(5, s.imeCounter)
        assertEquals(9, s.imeFieldCounter)
        assertEquals(TextFieldInfo("hello", "Search"), s.textField)
    }

    @Test
    fun showRequestCountsKeyboardRequestsAndKeepsAppInfo() {
        val base = TvState(appPackage = "com.example.app")
        val s = base
            .apply(msg { message(22) { message(2) { varint(1, 3); string(6, "Search") } } })
            .apply(msg { message(22) { message(2) { varint(1, 4) } } })
        assertEquals(2, s.keyboardRequests)
        assertEquals(4, s.imeFieldCounter)
        assertEquals("com.example.app", s.appPackage)
    }

    @Test
    fun batchEditOnlyFillsCountersWeDontHaveYet() {
        val empty = TvState().apply(msg { message(21) { varint(1, 7); varint(2, 8) } })
        assertEquals(7, empty.imeCounter)
        assertEquals(8, empty.imeFieldCounter)
        val known = TvState(imeCounter = 1, imeFieldCounter = 2).apply(msg { message(21) { varint(1, 7); varint(2, 8) } })
        assertEquals(1, known.imeCounter)
        assertEquals(2, known.imeFieldCounter)
    }

    @Test
    fun configureRecordsFeaturesAndDevice() {
        val s = TvState().apply(msg {
            message(1) { varint(1, 622); message(2) { string(1, "Model X"); string(2, "Vendor Y") } }
        })
        assertEquals(622, s.features)
        assertEquals("Vendor Y Model X", s.device)
    }

    @Test
    fun unrelatedMessagesLeaveTheStateAlone() {
        val s = TvState(on = true)
        assertEquals(s, s.apply(msg { message(8) { varint(1, 1) } })) // ping
    }

    @Test
    fun typingAtTheEndInsertsOrDeletes() {
        assertEquals(TextEdit(0, "a"), textEdit("", "a"))
        assertEquals(TextEdit(0, "bc"), textEdit("a", "abc"))
        assertEquals(TextEdit(1, ""), textEdit("abc", "ab"))
        assertEquals(TextEdit(0, ""), textEdit("abc", "abc"))
    }

    @Test
    fun replacingTextDeletesThenInserts() {
        // e.g. the keyboard swaps a suggestion in: "helo" -> "hello"
        // common prefix is "hel": delete "o", insert "lo"
        assertEquals(TextEdit(1, "lo"), textEdit("helo", "hello"))
        assertEquals(TextEdit(3, "xyz"), textEdit("abc", "xyz"))
    }

    @Test
    fun emojiIsNeverSplit() {
        val face = "😀" // one code point, two UTF-16 chars
        assertEquals(TextEdit(1, ""), textEdit("a$face", "a"))
        assertEquals(TextEdit(1, "😁"), textEdit("a$face", "a😁")) // shares the high surrogate
        assertEquals(TextEdit(1, ""), textEdit(face, ""))
    }
}

class AppCatalogTest {
    @Test
    fun catalogHasNoDuplicatesAndOnlyUsableLinks() {
        assertEquals(CATALOG.size, CATALOG.map { it.name }.toSet().size)
        assertEquals(CATALOG.size, CATALOG.map { it.link }.toSet().size)
        CATALOG.forEach { app ->
            assertTrue("${app.name}: ${app.link}", app.link.startsWith("https://") || dialApp(app.link) != null || keyCode(app.link) != null)
        }
    }

    @Test
    fun defaultsComeFromTheCatalog() {
        assertTrue(DEFAULT_APPS.isNotEmpty())
        assertTrue(CATALOG.containsAll(DEFAULT_APPS))
    }

    @Test
    fun customInputIsALinkOrAPackageName() {
        assertEquals("https://play.google.com/store/apps/details?id=com.example.app", customLink("  com.example.app "))
        assertEquals("https://example.org/x", customLink("https://example.org/x"))
        assertEquals("spotify://", customLink("spotify://"))
    }

    @Test
    fun savedShortcutsFromEarlierVersionsGetTheLinkThatWorks() {
        val old = """[{"name":"YouTube","link":"https://www.youtube.com"},{"name":"Netflix","link":"https://www.netflix.com"},""" +
            """{"name":"ZDF","link":"market://launch?id=com.zdf.android.mediathek"},{"name":"waipu.tv","link":"market://launch?id=de.exaring.waipu"},""" +
            """{"name":"Mine","link":"market://launch?id=com.example.mine"},{"name":"Web","link":"https://example.org/x"}]"""
        val apps = parseApps(old)
        assertEquals("https://www.youtube.com", apps[0].link)
        assertEquals("dial://Netflix", apps[1].link)
        assertEquals("https://www.zdf.de", apps[2].link)
        assertEquals("key://3", apps[3].link)
        // an old launch link of an app outside the catalog becomes that package's Play Store page
        assertEquals("https://play.google.com/store/apps/details?id=com.example.mine", apps[4].link)
        assertEquals("https://example.org/x", apps[5].link)
    }

    @Test
    fun dialAndKeyLinksNameTheirTarget() {
        assertEquals("Netflix", dialApp(dialLink("Netflix")))
        assertEquals(null, dialApp("https://www.netflix.com"))
        assertEquals(null, dialApp("dial://"))
        assertEquals(3, keyCode(keyLink(3)))
        assertEquals(null, keyCode("https://example.org"))
        assertEquals(null, keyCode("key://home"))
    }

    @Test
    fun appListRoundTripsThroughJson() {
        val apps = listOf(AppShortcut("A", "https://a.example"), AppShortcut("B \"q\"", "https://play.google.com/store/apps/details?id=b.c"))
        assertEquals(apps, parseApps(appsToJson(apps)))
        assertEquals(DEFAULT_APPS, parseApps(null))
        assertEquals(DEFAULT_APPS, parseApps("not json"))
    }
}

class AppNameTest {
    @Test
    fun knownTvPackagesGiveTheCatalogEntry() {
        assertEquals(CATALOG.first { it.name == "YouTube" }, shortcutForPackage("com.google.android.youtube.tv"))
        assertEquals(CATALOG.first { it.name == "Twitch" }, shortcutForPackage("tv.twitch.android.app"))
        assertEquals(CATALOG.first { it.name == "Netflix" }, shortcutForPackage("com.netflix.ninja"))
        assertEquals(CATALOG.first { it.name == "waipu.tv" }, shortcutForPackage("de.exaring.waipu"))
    }

    @Test
    fun unknownPackagesGetAReadableNameAndAMarketLink() {
        assertEquals("Twitch", guessAppName("tv.twitch.android.app"))
        assertEquals("Youtube", guessAppName("com.google.android.youtube.tv"))
        val s = shortcutForPackage("com.example.coolplayer.tv")
        assertEquals("Coolplayer", s.name)
        assertEquals("https://play.google.com/store/apps/details?id=com.example.coolplayer.tv", s.link)
        assertEquals("From TV", shortcutForPackage("com.example.coolplayer.tv", "From TV").name)
    }
}

class PairedTvStorageTest {
    @Test
    fun tvListRoundTripsThroughJson() {
        // Made-up names, addresses and fingerprints.
        val tvs = listOf(PairedTv("Living room", "192.0.2.10", "ab12"), PairedTv("Bedroom \"B\"", "192.0.2.11", "cd34"))
        assertEquals(tvs, parseTvs(tvsToJson(tvs)))
        assertEquals(emptyList<PairedTv>(), parseTvs(null))
        assertEquals(emptyList<PairedTv>(), parseTvs("garbage"))
    }
}

class MoreKeysTest {
    @Test
    fun fiveRowsOfFourWithNoDuplicates() {
        assertEquals(5, MORE_KEYS.size)
        MORE_KEYS.forEach { assertEquals(4, it.size) }
        val keys = MORE_KEYS.flatten()
        assertEquals(keys.size, keys.map { it.code }.toSet().size)
        assertEquals(keys.size, keys.map { it.label }.toSet().size)
        assertEquals(keys.size, keys.map { it.description }.toSet().size)
    }

    @Test
    fun codesMatchTheProtocol() {
        // Android key codes as listed in the protocol's RemoteKeyCode enum.
        val expected = mapOf(
            "PREV" to 88, "REW" to 89, "FF" to 90, "NEXT" to 87, "PLAY" to 126, "PAUSE" to 127, "STOP" to 86,
            "CC" to 175, "GUIDE" to 172, "INFO" to 165, "LAST" to 229, "TXT" to 233, "RED" to 183, "GREEN" to 184,
            "YELLOW" to 185, "BLUE" to 186, "SLEEP" to 223, "WAKE" to 224, "APPS" to 284, "SET" to 176,
        )
        assertEquals(expected, MORE_KEYS.flatten().associate { it.label to it.code })
    }

    @Test
    fun everyKeyHasOneWayOfBeingDrawnOrIsText() {
        MORE_KEYS.flatten().forEach { assertTrue("${it.label}: glyph and colour together", it.glyph == null || it.color == null) }
    }
}
