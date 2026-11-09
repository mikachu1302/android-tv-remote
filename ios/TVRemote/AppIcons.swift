import SwiftUI
import UIKit
import TVRemoteCore

/// The name of the alternate app icon for a choice and the current palette, or nil for the primary icon.
/// (`.match` is the icon of the selected colours, which is the plain Neon one in the neon palette.)
func alternateIconName(_ choice: IconChoice, palette: String) -> String? {
    switch choice {
    case .match: return palette == "neon" ? nil : "icon_" + palette
    case .prideBezel: return "icon_pride_bezel"
    case .prideLight: return "icon_pride_light"
    case .prideRainbow: return "icon_pride_rainbow"
    case .prideNight: return "icon_pride_night"
    case .prideBig: return "icon_pride_big"
    }
}

enum LauncherIcon {
    /// Switches the home-screen icon (iOS shows its own confirmation). Does nothing when it already is that icon.
    @MainActor
    static func apply(_ choice: IconChoice, palette: String) {
        guard UIApplication.shared.supportsAlternateIcons else { return }
        let name = alternateIconName(choice, palette: palette)
        guard UIApplication.shared.alternateIconName != name else { return }
        UIApplication.shared.setAlternateIconName(name) { _ in }
    }
}

/// The preview of an icon choice in the settings: the real icon art when the app has it, else a drawn TV and remote.
struct AppIconPreview: View {
    @EnvironmentObject private var vm: RemoteViewModel
    let choice: IconChoice

    var body: some View {
        let name = previewName
        Group {
            if UIImage(named: name) != nil {
                Image(name).resizable().interpolation(.high)
            } else {
                DrawnIcon(palette: choice == .match ? Palette.byId(vm.settings.palette) : PaletteData.pride)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.white.opacity(0.15), lineWidth: 1))
        .accessibilityHidden(true)
    }

    private var previewName: String {
        switch choice {
        case .match: return "preview_" + vm.settings.palette
        case .prideBezel: return "preview_pride_bezel"
        case .prideLight: return "preview_pride_light"
        case .prideRainbow: return "preview_pride_rainbow"
        case .prideNight: return "preview_pride_night"
        case .prideBig: return "preview_pride_big"
        }
    }
}

private struct DrawnIcon: View {
    let palette: Palette

    var body: some View {
        GeometryReader { proxy in
            let s = proxy.size.width
            ZStack {
                Color(hex: palette.bg)
                RoundedRectangle(cornerRadius: s * 0.06).strokeBorder(Color(hex: palette.primary), lineWidth: s * 0.05)
                    .frame(width: s * 0.5, height: s * 0.38).offset(x: -s * 0.06, y: -s * 0.05)
                RoundedRectangle(cornerRadius: s * 0.06).fill(Color(hex: palette.bg))
                    .overlay(RoundedRectangle(cornerRadius: s * 0.06).strokeBorder(Color(hex: palette.action), lineWidth: s * 0.05))
                    .frame(width: s * 0.2, height: s * 0.42).offset(x: s * 0.2, y: s * 0.08)
            }
        }
    }
}
