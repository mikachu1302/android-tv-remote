import Foundation
import TVRemoteCore

/// A demo mode for screenshots and UI tests: no network, a made-up TV, and the screen/style/palette taken from
/// launch arguments (`-demo YES -demoScreen settings -demoStyle glass -demoPalette pride -demoPage 2`).
enum Demo {
    private static var defaults: UserDefaults { .standard }

    static var enabled: Bool { defaults.bool(forKey: "demo") }
    static var screen: String { defaults.string(forKey: "demoScreen") ?? "remote" }
    static var page: Int { defaults.integer(forKey: "demoPage") }
    static var stickOn: Bool { defaults.object(forKey: "demoStickOn") == nil ? true : defaults.bool(forKey: "demoStickOn") }

    static var style: UiStyle? { defaults.string(forKey: "demoStyle").flatMap { $0 == "crt" ? .crt : $0 == "glass" ? .glass : nil } }
    static var palette: String? { defaults.string(forKey: "demoPalette") }

    // Made-up values only.
    static let tv = PairedTv(name: "Living room stick", host: "192.0.2.10", pin: "demo")
    static let audioTv = AudioTv(name: "Living Room", host: "192.0.2.20", port: 38400, control: "/ctl/rc", mac: "02:00:00:00:00:01")

    static var state: TvState {
        stickOn ? TvState(on: true, appPackage: "com.google.android.youtube.tv", appLabel: "YouTube") : TvState(on: false)
    }
}
