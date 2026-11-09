import XCTest
@testable import TVRemoteCore

final class PaletteTests: XCTestCase {
    private func violations(_ p: Palette, _ what: String, _ fg: UInt32, _ bg: UInt32, _ min: Double) -> [String] {
        let c = contrastRatio(fg, bg)
        return c >= min ? [] : ["\(p.name): \(what) \(String(format: "%.2f", c)):1 (needs \(min))"]
    }

    /// Every palette, now and later, has to be readable: contrast per WCAG 2.
    func testEveryPaletteIsReadable() {
        var problems = [String]()
        for p in PaletteData.all {
            for (bgName, bg) in [("background", p.bg), ("surface", p.surface)] {
                problems += violations(p, "text on \(bgName)", p.text, bg, 7.0)
                problems += violations(p, "dim text on \(bgName)", p.dim, bg, 4.5)
                problems += violations(p, "primary on \(bgName)", p.primary, bg, 4.5)
                problems += violations(p, "action on \(bgName)", p.action, bg, 4.5)
                problems += violations(p, "soft on \(bgName)", p.soft, bg, 4.5)
                problems += violations(p, "warn on \(bgName)", p.warn, bg, 4.5)
            }
            problems += violations(p, "border on background", p.border, p.bg, 3.0)
        }
        XCTAssertTrue(problems.isEmpty, problems.joined(separator: "\n"))
    }

    func testIdsAreUniqueAndLookupFallsBack() {
        XCTAssertEqual(PaletteData.all.count, Set(PaletteData.all.map(\.id)).count)
        XCTAssertEqual(Palette.byId("nope"), PaletteData.neon)
        XCTAssertEqual(Palette.byId("nord"), PaletteData.nord)
        XCTAssertEqual(PaletteData.all.count, 10)
        XCTAssertEqual(PaletteData.pride.stripes?.count, 6)
        XCTAssertTrue(PaletteData.all.filter { $0.id != "pride" }.allSatisfy { $0.stripes == nil })
    }
}

final class ModelTests: XCTestCase {
    func testCatalogHasNoDuplicatesAndOnlyUsableLinks() {
        XCTAssertEqual(appCatalog.count, Set(appCatalog.map(\.name)).count)
        XCTAssertEqual(appCatalog.count, Set(appCatalog.map(\.link)).count)
        for app in appCatalog {
            let ok = app.link.hasPrefix("https://") || app.link.range(of: "^market://launch\\?id=[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$", options: .regularExpression) != nil
            XCTAssertTrue(ok, "\(app.name): \(app.link)")
        }
        XCTAssertEqual(appCatalog.count, 25)
    }

    func testDefaultsComeFromTheCatalog() {
        XCTAssertFalse(defaultApps.isEmpty)
        XCTAssertTrue(defaultApps.allSatisfy { appCatalog.contains($0) })
    }

    func testCustomInputIsALinkOrAPackageName() {
        XCTAssertEqual(customLink("  com.example.app "), "market://launch?id=com.example.app")
        XCTAssertEqual(customLink("https://example.org/x"), "https://example.org/x")
        XCTAssertEqual(customLink("spotify://"), "spotify://")
    }

    func testAppListRoundTripsThroughJson() {
        let apps = [AppShortcut(name: "A", link: "https://a.example"), AppShortcut(name: "B \"q\"", link: "market://launch?id=b.c")]
        XCTAssertEqual(parseApps(appsToJson(apps)), apps)
        XCTAssertEqual(parseApps(nil), defaultApps)
        XCTAssertEqual(parseApps("not json"), defaultApps)
    }

    func testKnownTvPackagesGiveTheCatalogEntry() {
        XCTAssertEqual(shortcutForPackage("com.google.android.youtube.tv"), appCatalog.first { $0.name == "YouTube" })
        XCTAssertEqual(shortcutForPackage("tv.twitch.android.app"), appCatalog.first { $0.name == "Twitch" })
    }

    func testUnknownPackagesGetAReadableNameAndAMarketLink() {
        XCTAssertEqual(guessAppName("tv.twitch.android.app"), "Twitch")
        XCTAssertEqual(guessAppName("com.google.android.youtube.tv"), "Youtube")
        let s = shortcutForPackage("com.example.coolplayer.tv")
        XCTAssertEqual(s.name, "Coolplayer")
        XCTAssertEqual(s.link, "market://launch?id=com.example.coolplayer.tv")
        XCTAssertEqual(shortcutForPackage("com.example.coolplayer.tv", label: "From TV").name, "From TV")
    }

    func testTvListRoundTripsThroughJson() {
        // Made-up names, addresses and fingerprints.
        let tvs = [PairedTv(name: "Living room", host: "192.0.2.10", pin: "ab12"), PairedTv(name: "Bedroom \"B\"", host: "192.0.2.11", pin: "cd34")]
        XCTAssertEqual(parseTvs(tvsToJson(tvs)), tvs)
        XCTAssertEqual(parseTvs(nil), [])
        XCTAssertEqual(parseTvs("garbage"), [])
    }

    func testMoreKeysAreFiveRowsOfFourWithNoDuplicates() {
        XCTAssertEqual(moreKeys.count, 5)
        moreKeys.forEach { XCTAssertEqual($0.count, 4) }
        let keys = moreKeys.flatMap { $0 }
        XCTAssertEqual(keys.count, Set(keys.map(\.code)).count)
        XCTAssertEqual(keys.count, Set(keys.map(\.label)).count)
        XCTAssertEqual(keys.count, Set(keys.map(\.description)).count)
        keys.forEach { XCTAssertTrue($0.glyph == nil || $0.color == nil, "\($0.label): glyph and colour together") }
    }

    func testMoreKeyCodesMatchTheProtocol() {
        // Android key codes as listed in the protocol's RemoteKeyCode enum (same table as the Android test).
        let expected: [String: Int] = [
            "PREV": 88, "REW": 89, "FF": 90, "NEXT": 87, "PLAY": 126, "PAUSE": 127, "STOP": 86,
            "CC": 175, "GUIDE": 172, "INFO": 165, "LAST": 229, "TXT": 233, "RED": 183, "GREEN": 184,
            "YELLOW": 185, "BLUE": 186, "SLEEP": 223, "WAKE": 224, "APPS": 284, "SET": 176,
        ]
        XCTAssertEqual(Dictionary(uniqueKeysWithValues: moreKeys.flatMap { $0 }.map { ($0.label, $0.code) }), expected)
    }

    func testGlyphsCoverEveryNameTheScreensUse() {
        for name in ["gear", "power", "kbd", "home", "pad", "speaker", "speaker-off", "mic", "back", "play", "up", "down", "left", "right",
                     "arrow-left", "menu", "grip", "plus", "check", "info", "dpad"] {
            XCTAssertNotNil(GlyphData.stroked[name], name)
        }
        for name in ["tri-up", "tri-down", "tri-left", "tri-right", "play", "pause", "stop", "rew", "ff", "prev", "next"] {
            XCTAssertNotNil(GlyphData.filled[name], name)
        }
        moreKeys.flatMap { $0 }.compactMap(\.glyph).forEach { XCTAssertNotNil(GlyphData.filled[$0], $0) }
        XCTAssertEqual(appLogos.count, 15)
    }
}

final class TvAudioTests: XCTestCase {
    private let deviceXML = """
    <?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
    <friendlyName>Test &amp; Lounge</friendlyName>
    <serviceList>
      <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/ctl/cm</controlURL></service>
      <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/ctl/rc</controlURL></service>
    </serviceList></device></root>
    """

    func testDescriptionGivesNameAndRenderingControlPath() {
        let parsed = Upnp.parseDescription(deviceXML)
        XCTAssertEqual(parsed?.name, "Test & Lounge")
        XCTAssertEqual(parsed?.control, "/ctl/rc")
        XCTAssertNil(Upnp.parseDescription("<root><device><friendlyName>Lamp</friendlyName></device></root>"))
    }

    func testEnvelopeNamesActionAndInstance() {
        let body = Upnp.envelope("SetVolume", "<Channel>Master</Channel><DesiredVolume>30</DesiredVolume>")
        XCTAssertTrue(body.contains("<u:SetVolume xmlns:u=\"\(Upnp.rendering)\">"))
        XCTAssertTrue(body.contains("<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>30</DesiredVolume>"))
        XCTAssertEqual(Upnp.tag(body, "DesiredVolume"), "30")
    }

    func testAnswersGiveVolumeAndMute() {
        XCTAssertEqual(Upnp.parseVolume("<u:GetVolumeResponse xmlns:u=\"x\">\n <CurrentVolume>28</CurrentVolume></u:GetVolumeResponse>"), 28)
        XCTAssertEqual(Upnp.parseMute("<CurrentMute>1</CurrentMute>"), true)
        XCTAssertEqual(Upnp.parseMute("<CurrentMute>0</CurrentMute>"), false)
        XCTAssertNil(Upnp.parseVolume("<nothing/>"))
    }

    func testMacAddressesParseInCommonSpellings() {
        let expected: [UInt8] = [0x02, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E]
        XCTAssertEqual(Wol.parseMac("02:1a:2b:3c:4d:5e"), expected)
        XCTAssertEqual(Wol.parseMac("02-1A-2B-3C-4D-5E"), expected)
        XCTAssertEqual(Wol.parseMac("021a2b3c4d5e"), expected)
        XCTAssertNil(Wol.parseMac("02:1a:2b:3c:4d"))
        XCTAssertNil(Wol.parseMac("02:1a:2b:3c:4d:5g"))
        XCTAssertNil(Wol.parseMac(""))
    }

    func testBroadcastAddressFollowsThePrefix() {
        XCTAssertEqual(Wol.broadcastOf([192, 0, 2, 7], prefix: 24), "192.0.2.255")
        XCTAssertEqual(Wol.broadcastOf([198, 51, 100, 9], prefix: 16), "198.51.255.255")
        XCTAssertEqual(Wol.broadcastOf([192, 0, 2, 7], prefix: 25), "192.0.2.127")
        XCTAssertEqual(Wol.broadcastOf([192, 0, 2, 7], prefix: 32), "192.0.2.7")
    }

    func testMagicPacketIsSixFFsAndSixteenCopies() {
        let mac: [UInt8] = [1, 2, 3, 4, 5, 6]
        let packet = Wol.packet(mac)
        XCTAssertEqual(packet.count, 102)
        XCTAssertTrue(packet.prefix(6).allSatisfy { $0 == 0xFF })
        for i in 0..<16 { XCTAssertEqual(Array(packet[6 + i * 6..<12 + i * 6]), mac) }
    }
}

final class DERTests: XCTestCase {
    /// A made-up 2048-bit-shaped RSA public key in PKCS#1 form (not a real key).
    private func fakeKey(modulusTopBitSet: Bool) -> [UInt8] {
        let modulus = [UInt8(modulusTopBitSet ? 0xC1 : 0x41)] + [UInt8](repeating: 0x5A, count: 255)
        return DER.sequence(DER.integer(modulus), DER.integer([1, 0, 1]))
    }

    func testPublicKeyRoundTrips() throws {
        let key = fakeKey(modulusTopBitSet: true)
        let parsed = try XCTUnwrap(DER.parseRSAPublicKey(key))
        XCTAssertEqual(parsed.modulus.count, 256) // the sign byte DER adds is not part of the number
        XCTAssertEqual(parsed.modulus.first, 0xC1)
        XCTAssertEqual(parsed.exponent, [1, 0, 1])
        XCTAssertNil(DER.parseRSAPublicKey([0x30, 0x03, 0x02, 0x01]))
        XCTAssertNil(DER.parseRSAPublicKey([]))
    }

    func testIntegersGetASignByteOnlyWhenNeeded() {
        XCTAssertEqual(DER.integer([0x7F]), [0x02, 0x01, 0x7F])
        XCTAssertEqual(DER.integer([0x80]), [0x02, 0x02, 0x00, 0x80])
        XCTAssertEqual(DER.integer([0, 0, 1]), [0x02, 0x01, 0x01])
        XCTAssertEqual(DER.integer([]), [0x02, 0x01, 0x00])
    }

    func testOidsAndLengths() {
        XCTAssertEqual(DER.oid([1, 2, 840, 113549, 1, 1, 11]), [0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B])
        XCTAssertEqual(DER.length(5), [5])
        XCTAssertEqual(DER.length(200), [0x81, 200])
        XCTAssertEqual(DER.length(300), [0x82, 0x01, 0x2C])
    }

    func testCertificateIsAStructurallyValidSequence() {
        let tbs = DER.tbsCertificate(
            commonName: "TV Remote", publicKeyPKCS1: fakeKey(modulusTopBitSet: false), serial: [0x01, 0x02],
            notBefore: Date(timeIntervalSince1970: 1_700_000_000), notAfter: Date(timeIntervalSince1970: 2_300_000_000)
        )
        let cert = DER.certificate(tbs: tbs, signature: [UInt8](repeating: 0xAB, count: 256))
        XCTAssertEqual(cert.first, 0x30)
        // 1_700_000_000 = 2023-11-14 22:13:20 UTC
        XCTAssertTrue(String(decoding: tbs, as: UTF8.self).contains("231114221320Z"))
        try? Data(cert).write(to: URL(fileURLWithPath: ProcessInfo.processInfo.environment["TVREMOTE_DER_OUT"] ?? "/dev/null"))
    }
}
