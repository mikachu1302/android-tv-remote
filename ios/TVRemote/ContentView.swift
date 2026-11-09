import SwiftUI
import UIKit
import TVRemoteCore

private enum AppScreen { case remote, settings, apps }

/// The router: the screen to show, on the themed background. Pairing and finding a TV come first when needed.
struct ContentView: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @State private var screen: AppScreen = {
        guard Demo.enabled else { return .remote }
        switch Demo.screen {
        case "settings": return .settings
        case "apps": return .apps
        default: return .remote
        }
    }()
    /// Back from the app shortcuts: Settings, or the remote when "+" in the app row opened it.
    @State private var appsBack: AppScreen = .settings

    private var term: TermTheme {
        TermTheme(style: vm.settings.style, palette: Palette.byId(vm.settings.palette), scanlines: vm.settings.scanlines)
    }

    var body: some View {
        ZStack {
            TermBackground()
            screenView
            if term.crt && term.scanlines { CrtOverlay() }
            if Demo.enabled {
                // Lets UI tests see which keys were pressed.
                Text(vm.demoKeys.joined(separator: ","))
                    .font(.system(size: 1)).opacity(0.01).frame(width: 1, height: 1)
                    .accessibilityIdentifier("demo-keys").accessibilityLabel(vm.demoKeys.joined(separator: ","))
                    .allowsHitTesting(false)
            }
        }
        .environment(\.term, term)
        .foregroundColor(term.text)
        .preferredColorScheme(.dark)
        .onAppear { applyDemoState() }
        .onChange(of: vm.paired) { _ in screen = .remote }
        .modifier(KeepAwakeAndTheater(settings: vm.settings))
    }

    @ViewBuilder private var screenView: some View {
        if vm.pairing != .none {
            PairingScreen(state: vm.pairing)
        } else if let tv = vm.paired, !vm.addingTv {
            switch screen {
            case .remote:
                RemoteScreen(tv: tv, onSettings: { screen = .settings }, onAddApp: { appsBack = .remote; screen = .apps })
            case .settings:
                SettingsScreen(tv: tv, onBack: { screen = .remote }, onApps: { appsBack = .settings; screen = .apps })
            case .apps:
                AppShortcutsScreen(onBack: { screen = appsBack })
            }
        } else {
            FindTvScreen(canCancel: vm.paired != nil)
        }
    }

    private func applyDemoState() {
        guard Demo.enabled else { return }
        switch Demo.screen {
        case "find": vm.beginAddTv()
        case "pairing": vm.pairing = .waitingForCode(FoundTv(name: "Living room stick", host: "192.0.2.10"), wrongCode: false)
        default: break
        }
    }
}

/// "Keep screen awake" and "Theater mode": no screen timeout while the remote is in front, and a dimmed screen when
/// nothing was touched for a while.
private struct KeepAwakeAndTheater: ViewModifier {
    let settings: RemoteSettings
    @Environment(\.scenePhase) private var phase
    @State private var lastTouch = Date()
    @State private var dimmed = false
    @State private var savedBrightness: CGFloat = UIScreen.main.brightness
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    func body(content: Content) -> some View {
        content
            .simultaneousGesture(DragGesture(minimumDistance: 0).onChanged { _ in touched() })
            .onChange(of: settings.keepAwake) { _ in apply() }
            .onChange(of: phase) { _ in
                if phase != .active { setDim(false) }
                apply()
            }
            .onAppear { apply() }
            .onReceive(ticker) { _ in
                setDim(settings.theater && phase == .active && Date().timeIntervalSince(lastTouch) > 10)
            }
    }

    private func apply() { UIApplication.shared.isIdleTimerDisabled = settings.keepAwake && phase == .active }

    private func touched() {
        lastTouch = Date()
        if dimmed { setDim(false) }
    }

    private func setDim(_ on: Bool) {
        guard on != dimmed else { return }
        dimmed = on
        if on {
            savedBrightness = UIScreen.main.brightness
            UIScreen.main.brightness = 0.01
        } else {
            UIScreen.main.brightness = savedBrightness
        }
    }
}
