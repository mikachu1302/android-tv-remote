import Foundation

/// A tile in the app row: opens `link` on the TV (a web link, a Play Store page, `dial://<app>`, or `key://<code>`).
public struct AppShortcut: Equatable, Codable, Hashable {
    public var name: String
    public var link: String
    public init(name: String, link: String) { self.name = name; self.link = link }
}

/// An app the stick starts through its DIAL service (`POST http://<stick>:8008/apps/<name>`) rather than through an
/// app link. For apps that ignore app links (Netflix).
public func dialLink(_ app: String) -> String { "dial://\(app)" }

/// The DIAL application name in a `dialLink`, or nil for any other link.
public func dialApp(_ link: String) -> String? {
    guard link.hasPrefix("dial://") else { return nil }
    let name = String(link.dropFirst("dial://".count))
    return name.isEmpty ? nil : name
}

/// A key press as a shortcut: `key://<Android key code>`. On a waipu stick the Home key is the waipu.tv app.
public func keyLink(_ code: Int) -> String { "key://\(code)" }

/// The key code in a `keyLink`, or nil for any other link.
public func keyCode(_ link: String) -> Int? {
    guard link.hasPrefix("key://") else { return nil }
    return Int(link.dropFirst("key://".count))
}

/// The Play Store page of a package. The stick shows it; the user presses OK on "Open" (or "Install").
public func playStoreLink(_ pkg: String) -> String { "https://play.google.com/store/apps/details?id=\(pkg)" }


/// What a user may type for a custom shortcut: a link as is, or an Android package name (`com.example.app`), which
/// becomes the package's Play Store page.
public func customLink(_ input: String) -> String {
    let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
    return text.contains("://") ? text : playStoreLink(text)
}

/// The apps first offered in the row.
public let defaultApps: [AppShortcut] = Array(appCatalog.prefix(5))

/// A readable name from a package like `tv.twitch.android.app` -> "Twitch".
public func guessAppName(_ pkg: String) -> String {
    let parts = pkg.split(separator: ".").map(String.init)
    let name = parts.last(where: { !genericPackageParts.contains($0) }) ?? parts.last ?? pkg
    return name.prefix(1).uppercased() + name.dropFirst()
}

/// The shortcut for an app the TV reports as open: the catalog's entry when we know it.
public func shortcutForPackage(_ pkg: String, label: String? = nil) -> AppShortcut {
    if let name = appPackages[pkg], let entry = appCatalog.first(where: { $0.name == name }) { return entry }
    return AppShortcut(name: label ?? guessAppName(pkg), link: playStoreLink(pkg))
}

/// Links an earlier version saved for an app and that did not work: shortcuts that carry one get the working link.
private let legacyLinks = [
    "https://www.netflix.com": dialLink("Netflix"),
]

/// An old package-launch link becomes its app's current catalog link, or the package's Play Store page; anything else stays.
private func currentLink(_ link: String) -> String {
    if let upgraded = legacyLinks[link] { return upgraded }
    let prefix = "market://launch?id="
    guard link.hasPrefix(prefix) else { return link }
    let pkg = String(link.dropFirst(prefix.count))
    if let name = appPackages[pkg], let entry = appCatalog.first(where: { $0.name == name }) { return entry.link }
    return playStoreLink(pkg)
}

public func parseApps(_ json: String?) -> [AppShortcut] {
    guard let json, let data = json.data(using: .utf8), let apps = try? JSONDecoder().decode([AppShortcut].self, from: data) else {
        return defaultApps
    }
    return apps.map { AppShortcut(name: $0.name, link: currentLink($0.link)) }
}

public func appsToJson(_ apps: [AppShortcut]) -> String {
    String(data: (try? JSONEncoder().encode(apps)) ?? Data("[]".utf8), encoding: .utf8) ?? "[]"
}

/// A TV (or stick) we paired with: its name, last known address and the pinned certificate fingerprint.
public struct PairedTv: Equatable, Codable, Hashable {
    public var name: String
    public var host: String
    public var pin: String
    public init(name: String, host: String, pin: String) { self.name = name; self.host = host; self.pin = pin }
}

public func parseTvs(_ json: String?) -> [PairedTv] {
    guard let json, let data = json.data(using: .utf8), let tvs = try? JSONDecoder().decode([PairedTv].self, from: data) else { return [] }
    return tvs
}

public func tvsToJson(_ tvs: [PairedTv]) -> String {
    String(data: (try? JSONEncoder().encode(tvs)) ?? Data("[]".utf8), encoding: .utf8) ?? "[]"
}

public enum UiStyle: String, Codable, CaseIterable {
    case crt, glass
    public var label: String { self == .crt ? "Neon CRT" : "Glass" }
    public var blurb: String { self == .crt ? "Square, glowing, scanlines" : "Round, soft, dial" }
}

/// Which app icon to show: the one in the selected colours, or one of the Progress Pride ones.
public enum IconChoice: String, Codable, CaseIterable {
    case match, prideBezel, prideLight, prideRainbow, prideNight, prideBig

    public var label: String {
        switch self {
        case .match: return "Match colors"
        case .prideBezel: return "Pride bezel"
        case .prideLight: return "Pride light"
        case .prideRainbow: return "Pride rainbow"
        case .prideNight: return "Pride night"
        case .prideBig: return "Pride big flag"
        }
    }
}

/// The settings screen's options.
public struct RemoteSettings: Equatable, Codable {
    public var haptics = true
    /// Holding volume, channel or a direction acts like holding a key on a physical remote.
    public var smoothPress = true
    public var keepAwake = true
    /// Dims the screen when idle (for watching TV in the dark).
    public var theater = false
    public var style: UiStyle = .crt
    /// A `Palette` id.
    public var palette = "neon"
    /// Scanlines over the CRT style.
    public var scanlines = true
    public var icon: IconChoice = .match
    /// The volume keys control the TV itself (UPnP), when one is chosen and answers; else the stick.
    public var tvVolume = true
    public init() {}
}

// MARK: - Palettes

/// One colour scheme. `primary` means "alive" (on, connected, focus, plus and minus keys), `action` is the
/// action (OK, the app that is open, the mic), `soft` a quiet third colour. Colours are 0xRRGGBB.
public struct Palette: Equatable {
    public let id: String
    public let name: String
    public let bg: UInt32
    public let surface: UInt32
    /// Borders of controls: at least 3:1 against `bg`.
    public let border: UInt32
    public let text: UInt32
    /// Secondary text: at least 4.5:1 against `bg`.
    public let dim: UInt32
    public let primary: UInt32
    public let action: UInt32
    public let soft: UInt32
    public let warn: UInt32
    /// A palette with a rainbow in it (Pride): shown as dots in its chip and as the ring of the dial.
    public let stripes: [UInt32]?

    public init(id: String, name: String, bg: UInt32, surface: UInt32, border: UInt32, text: UInt32, dim: UInt32,
                primary: UInt32, action: UInt32, soft: UInt32, warn: UInt32, stripes: [UInt32]?) {
        self.id = id; self.name = name; self.bg = bg; self.surface = surface; self.border = border; self.text = text
        self.dim = dim; self.primary = primary; self.action = action; self.soft = soft; self.warn = warn; self.stripes = stripes
    }

    public static func byId(_ id: String?) -> Palette { PaletteData.all.first(where: { $0.id == id }) ?? PaletteData.neon }
}

/// WCAG 2 contrast between two 0xRRGGBB colours.
public func contrastRatio(_ a: UInt32, _ b: UInt32) -> Double {
    func channel(_ v: UInt32) -> Double {
        let c = Double(v) / 255
        return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
    }
    func luminance(_ c: UInt32) -> Double {
        0.2126 * channel((c >> 16) & 0xFF) + 0.7152 * channel((c >> 8) & 0xFF) + 0.0722 * channel(c & 0xFF)
    }
    let la = luminance(a), lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

// MARK: - More keys

/// A key on the "More" page: shown as a solid `glyph`, a colour bar (`color`, 0xRRGGBB), or its `label`.
public struct MoreKey: Equatable {
    public let label: String
    public let description: String
    public let code: Int
    public let glyph: String?
    public let color: UInt32?
    /// Holding the key keeps it going (seeking).
    public let repeats: Bool

    init(_ label: String, _ description: String, _ code: Int, glyph: String? = nil, color: UInt32? = nil, repeats: Bool = false) {
        self.label = label; self.description = description; self.code = code
        self.glyph = glyph; self.color = color; self.repeats = repeats
    }
}

/// The keys that do not fit on the D-pad: playback, TV keys, the four colour buttons and the stick's own
/// sleep/wake. Whether a key does anything depends on the app that is open on the TV.
public let moreKeys: [[MoreKey]] = [
    [
        MoreKey("PREV", "Previous", Key.mediaPrevious, glyph: "prev"),
        MoreKey("REW", "Rewind", Key.mediaRewind, glyph: "rew", repeats: true),
        MoreKey("FF", "Fast forward", Key.mediaFastForward, glyph: "ff", repeats: true),
        MoreKey("NEXT", "Next", Key.mediaNext, glyph: "next"),
    ],
    [
        MoreKey("PLAY", "Play", Key.mediaPlay, glyph: "play"),
        MoreKey("PAUSE", "Pause", Key.mediaPause, glyph: "pause"),
        MoreKey("STOP", "Stop", Key.mediaStop, glyph: "stop"),
        MoreKey("CC", "Subtitles", Key.captions),
    ],
    [
        MoreKey("GUIDE", "TV guide", Key.guide),
        MoreKey("INFO", "Info", Key.info),
        MoreKey("LAST", "Last channel", Key.lastChannel),
        MoreKey("TXT", "Teletext", Key.teletext),
    ],
    [
        MoreKey("RED", "Red button", Key.progRed, color: 0xFF4B4B),
        MoreKey("GREEN", "Green button", Key.progGreen, color: 0x2FD05B),
        MoreKey("YELLOW", "Yellow button", Key.progYellow, color: 0xFFED00),
        MoreKey("BLUE", "Blue button", Key.progBlue, color: 0x3D7BFF),
    ],
    [
        MoreKey("SLEEP", "Put the stick to sleep", Key.sleep),
        MoreKey("WAKE", "Wake the stick", Key.wakeup),
        MoreKey("APPS", "All apps", Key.allApps),
        MoreKey("SET", "Settings", Key.settings),
    ],
]
