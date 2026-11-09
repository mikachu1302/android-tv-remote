import SwiftUI
import TVRemoteCore

// MARK: - building blocks

/// The big round back button of the settings screens.
struct BackButton: View {
    @Environment(\.term) private var term
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Glyph(name: "arrow-left", color: term.primary, size: 26, strokeWidth: 2.6)
                .frame(width: 52, height: 52)
                .overlay(KeyShape.round.strokeBorder(term.border, lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Back")
        .accessibilityIdentifier("back")
    }
}

/// A screen title with the back button in front.
struct ScreenHeader: View {
    @Environment(\.term) private var term
    let title: String
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            BackButton(action: onBack)
            Text(title).font(term.font(24, bold: true)).foregroundColor(term.text)
            Spacer(minLength: 0)
        }
    }
}

/// A section headline with a divider above and an optional "i" that unfolds an explanation.
struct SettingsSection: View {
    @Environment(\.term) private var term
    let title: String
    var info: String?
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Rectangle().fill(term.border.opacity(0.5)).frame(height: 1).padding(.top, 16).padding(.bottom, 4)
            // A fixed-height header with or without the info icon, so the gap to the content is the same in every section.
            HStack(spacing: 0) {
                Text(title).font(term.font(13, bold: true)).foregroundColor(term.primary)
                if info != nil { InfoButton(open: open) { open.toggle() } }
                Spacer(minLength: 0)
            }
            .frame(height: 32)
            if let info, open { InfoText(text: info).padding(.bottom, 4) }
            Spacer().frame(height: 6)
        }
    }
}

/// A small "i" that unfolds an explanation: keeps the lists short, the detail one tap away.
struct InfoButton: View {
    @Environment(\.term) private var term
    let open: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Glyph(name: "info", color: open ? term.primary : term.dim, size: 18)
                .frame(width: 40, height: 40)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .frame(width: 40, height: 32)
        .accessibilityLabel(open ? "Hide details" : "Show details")
    }
}

struct InfoText: View {
    @Environment(\.term) private var term
    let text: String
    var body: some View { Text(text).font(term.font(12)).foregroundColor(term.dim).fixedSize(horizontal: false, vertical: true) }
}

/// A bordered panel for a section's content, so every section starts with a box at the same distance below its headline.
struct SectionCard<Content: View>: View {
    @Environment(\.term) private var term
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0, content: content)
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(term.controlShape.strokeBorder(term.border, lineWidth: 1))
            .clipShape(term.controlShape)
    }
}

/// A row that opens another screen: bordered, with a count and a chevron, so it reads as tappable.
struct NavRow: View {
    @Environment(\.term) private var term
    let title: String
    let subtitle: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(term.font(16, bold: true)).foregroundColor(term.text)
                    Text(subtitle).font(term.font(12)).foregroundColor(term.dim)
                }
                Spacer(minLength: 0)
                Glyph(name: "right", color: term.primary, size: 22, strokeWidth: 2.6)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .overlay(term.controlShape.strokeBorder(term.border, lineWidth: 1))
            .contentShape(term.controlShape)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title), \(subtitle)")
        .accessibilityIdentifier("nav-" + title)
    }
}

struct SwitchRow: View {
    @Environment(\.term) private var term
    let title: String
    let info: String
    let isOn: Bool
    let onChange: (Bool) -> Void
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Text(title).font(term.font(16, bold: true)).foregroundColor(term.text).lineLimit(1)
                InfoButton(open: open) { open.toggle() }
                Spacer(minLength: 8)
                Toggle("", isOn: Binding(get: { isOn }, set: onChange))
                    .labelsHidden()
                    .tint(term.primary)
                    .accessibilityLabel(title)
                    .accessibilityIdentifier("switch-" + title)
            }
            if open { InfoText(text: info).padding(.bottom, 8).padding(.trailing, 64) }
        }
        .padding(.vertical, 2)
    }
}

// MARK: - settings

struct SettingsScreen: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let tv: PairedTv
    let onBack: () -> Void
    let onApps: () -> Void

    private var version: String { (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "" }

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenHeader(title: "Settings", onBack: onBack)
                AppearanceSection()

                SettingsSection(title: "Remote behavior")
                SectionCard {
                    SwitchRow(title: "Haptic feedback", info: "A light vibration when remote buttons are pressed.", isOn: vm.settings.haptics) { value in
                        vm.updateSettings { $0.haptics = value }
                    }
                    SwitchRow(title: "Smooth press", info: "Holding volume, channel or a direction acts like holding a key on a physical remote.",
                              isOn: vm.settings.smoothPress) { value in vm.updateSettings { $0.smoothPress = value } }
                    SwitchRow(title: "Keep screen awake", info: "The screen stays on while the remote is open.", isOn: vm.settings.keepAwake) { value in
                        vm.updateSettings { $0.keepAwake = value }
                    }
                    SwitchRow(title: "Theater mode", info: "Dims the screen when idle, to save battery and reduce glare while watching.", isOn: vm.settings.theater) { value in
                        vm.updateSettings { $0.theater = value }
                    }
                }

                SettingsSection(title: "Applications")
                NavRow(title: "App shortcuts", subtitle: "\(vm.apps.count) apps", action: onApps)

                SettingsSection(title: "Connected TVs")
                SectionCard {
                    ForEach(vm.tvs, id: \.name) { item in
                        HStack {
                            VStack(alignment: .leading, spacing: 0) {
                                Text(item.name).font(term.font(16, bold: true)).foregroundColor(term.text).lineLimit(1)
                                if item == tv { Text("In use").font(term.font(12)).foregroundColor(term.primary) }
                            }
                            Spacer(minLength: 0)
                            if item != tv { TButton(kind: .text, action: { vm.selectTv(item) }) { Text("Use") } }
                            TButton(kind: .text, action: { vm.forgetTv(item) }) { Text("Forget") }
                        }
                        .padding(.vertical, 4)
                    }
                    TButton(kind: .outlined, action: vm.beginAddTv) { Text("Add a TV…") }
                        .padding(.vertical, 8)
                }

                TvVolumeSection()

                SettingsSection(title: "About")
                SectionCard {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("TV Remote \(version)").font(term.font(16, bold: true)).foregroundColor(term.text)
                        Text("No ads. No tracking. No accounts.").font(term.font(13)).foregroundColor(term.dim)
                    }
                    .padding(.vertical, 10)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
        }
        .onAppear {
            // Screenshots: scroll to a section by name (-demoScroll colors|icons|volume).
            if Demo.enabled, let target = UserDefaults.standard.string(forKey: "demoScroll") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { proxy.scrollTo(target, anchor: .top) }
            }
        }
        }
    }
}

// MARK: - appearance

private struct AppearanceSection: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term

    var body: some View {
        SettingsSection(title: "Style")
        HStack(alignment: .top, spacing: 10) {
            ForEach(UiStyle.allCases, id: \.self) { style in
                let selected = vm.settings.style == style
                Button { vm.updateSettings { $0.style = style } } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(style.label).font(term.font(16, bold: true)).foregroundColor(selected ? term.primary : term.text)
                        Text(style.blurb).font(term.font(12)).foregroundColor(term.dim)
                    }
                    .padding(.init(top: 12, leading: 12, bottom: 12, trailing: 30))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .overlay(term.controlShape.strokeBorder(selected ? term.primary : term.border, lineWidth: selected ? 2 : 1))
                    .overlay(alignment: .topTrailing) { if selected { SelectedMark() } }
                    .contentShape(term.controlShape)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(style.label + (selected ? ", selected" : ""))
                .accessibilityIdentifier("style-\(style.rawValue)")
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        if vm.settings.style == .crt {
            Spacer().frame(height: 10)
            SectionCard {
                SwitchRow(title: "Scanlines", info: "Fine horizontal lines and a darker rim, like an old monitor.", isOn: vm.settings.scanlines) { value in
                    vm.updateSettings { $0.scanlines = value }
                }
            }
        }

        SettingsSection(title: "Colors").id("colors")
        VStack(spacing: 10) {
            ForEach(Array(stride(from: 0, to: PaletteData.all.count, by: 2)), id: \.self) { start in
                HStack(alignment: .top, spacing: 10) {
                    ForEach(PaletteData.all[start..<min(start + 2, PaletteData.all.count)], id: \.id) { palette in
                        PaletteChip(palette: palette, selected: palette.id == vm.settings.palette) {
                            vm.updateSettings { $0.palette = palette.id }
                        }
                    }
                    if start + 1 >= PaletteData.all.count { Spacer().frame(maxWidth: .infinity) }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }

        SettingsSection(
            title: "App icon",
            info: "iOS asks for confirmation each time the icon changes, and the new icon shows on the home screen right away."
        ).id("icons")
        VStack(spacing: 10) {
            ForEach(Array(stride(from: 0, to: IconChoice.allCases.count, by: 3)), id: \.self) { start in
                HStack(alignment: .top, spacing: 10) {
                    ForEach(IconChoice.allCases[start..<min(start + 3, IconChoice.allCases.count)], id: \.self) { choice in
                        IconOption(choice: choice, selected: vm.settings.icon == choice) {
                            vm.updateSettings { $0.icon = choice }
                        }
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// The check in a selected card's corner (drawn: a font's check mark can look like a root sign).
private struct SelectedMark: View {
    @Environment(\.term) private var term
    var color: Color?

    var body: some View {
        Glyph(name: "check", color: color ?? term.primary, size: 18, strokeWidth: 3).padding(8)
    }
}

/// A palette drawn in its own colours: name, four swatches, a check when selected.
private struct PaletteChip: View {
    let palette: Palette
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Text(palette.name).font(.system(size: 14, weight: .bold, design: .monospaced)).foregroundColor(Color(hex: palette.text)).lineLimit(1)
                HStack(spacing: 6) {
                    if let stripes = palette.stripes {
                        ForEach(Array(stripes.enumerated()), id: \.offset) { _, c in Circle().fill(Color(hex: c)).frame(width: 14, height: 14) }
                    } else {
                        ForEach([palette.primary, palette.action, palette.soft, palette.dim], id: \.self) { c in
                            Circle().fill(Color(hex: c)).frame(width: 18, height: 18)
                        }
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(hex: palette.bg)))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(selected ? Color(hex: palette.primary) : Color(hex: palette.border), lineWidth: selected ? 3 : 1))
            .overlay(alignment: .topTrailing) { if selected { Glyph(name: "check", color: Color(hex: palette.primary), size: 18, strokeWidth: 3).padding(8) } }
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(palette.name + (selected ? ", selected" : ""))
        .accessibilityIdentifier("palette-" + palette.id)
    }
}

private struct IconOption: View {
    @Environment(\.term) private var term
    let choice: IconChoice
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                AppIconPreview(choice: choice).frame(width: 52, height: 52)
                Text(choice.label).font(term.font(11)).foregroundColor(selected ? term.primary : term.text).lineLimit(2).multilineTextAlignment(.center)
            }
            .padding(.vertical, 10)
            .padding(.horizontal, 4)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(term.controlShape.strokeBorder(selected ? term.primary : term.border, lineWidth: selected ? 2 : 1))
            .overlay(alignment: .topTrailing) { if selected { SelectedMark() } }
            .contentShape(term.controlShape)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("App icon: \(choice.label)" + (selected ? ", selected" : ""))
        .accessibilityIdentifier("icon-\(choice.rawValue)")
    }
}

// MARK: - the TV's own volume

private struct TvVolumeSection: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    @State private var choosing = false

    var body: some View {
        SettingsSection(
            title: "TV volume",
            info: "TVs with a UPnP media renderer (Hisense VIDAA has one) let apps read and set their volume over the network, so the volume keys "
                + "control the TV itself and show its level. The first time, the TV asks on screen whether this phone may control it: allow it with "
                + "the TV's remote. To switch the TV on from standby the app sends a Wake-on-LAN packet: turn that on in the TV's settings and "
                + "enter the TV's MAC address here (a network address of six hex pairs; the TV lists it under network information). It works once "
                + "the TV is fully in standby, a few minutes after switching it off (right after, the TV only blanks its screen and ignores it). "
                + "iOS only lets apps with a special Apple permission send broadcasts, so on an iPhone the packet goes to the TV's own address."
        ).id("volume")
        SectionCard {
            let current = vm.audioTv
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text(current?.name ?? "No TV chosen").font(term.font(16, bold: true)).foregroundColor(term.text).lineLimit(1)
                    Text(status(current)).font(term.font(12)).foregroundColor(term.dim)
                }
                Spacer(minLength: 0)
                TButton(kind: .text, action: { choosing = true }) { Text(current == nil ? "Choose" : "Change") }
                if current != nil { TButton(kind: .text, action: { vm.setAudioTv(nil) }) { Text("Remove") } }
            }
            .padding(.vertical, 4)
            if let current {
                SwitchRow(title: "Volume keys control the TV", info: "Off: the volume keys go to the stick again.", isOn: vm.settings.tvVolume) { value in
                    vm.updateSettings { $0.tvVolume = value }
                }
                MacField(initial: current.mac ?? "").id(current.name)
            }
        }
        .sheet(isPresented: $choosing) { ChooseAudioTvSheet() }
    }

    private func status(_ current: AudioTv?) -> String {
        if current == nil { return "Volume: the stick" }
        if let audio = vm.audio { return "Connected, volume \(audio.level)" }
        return "Not answering (TV off?)"
    }
}

private struct MacField: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    @State private var text: String

    init(initial: String) { _text = State(initialValue: initial) }

    private var valid: Bool { text.trimmingCharacters(in: .whitespaces).isEmpty || Wol.parseMac(text) != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("TV MAC address (Wake-on-LAN)").font(term.font(12)).foregroundColor(term.dim)
            TextField("AA:BB:CC:DD:EE:FF", text: $text)
                .font(term.font(16))
                .foregroundColor(term.text)
                .tint(term.primary)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled(true)
                .padding(.horizontal, 12)
                .frame(height: 44)
                .overlay(term.controlShape.strokeBorder(valid ? term.border : term.warn, lineWidth: 1))
                .onChange(of: text) { value in if valid { vm.setWakeAddress(value) } }
            if !valid { Text("Six pairs of hex digits, like AA:BB:CC:DD:EE:FF").font(term.font(12)).foregroundColor(term.warn) }
        }
        .padding(.vertical, 8)
    }
}

/// Searches the network for TVs with a UPnP renderer; each is chosen with its own button.
private struct ChooseAudioTvSheet: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    @Environment(\.dismiss) private var dismiss
    @State private var round = 0
    @State private var found: [AudioTv]?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Choose the TV").font(term.font(22, bold: true)).foregroundColor(term.text)
            if let found {
                if found.isEmpty {
                    Text("Nothing found. The TV must be on and on the same Wi-Fi.").font(term.font(14)).foregroundColor(term.dim)
                }
                ForEach(found, id: \.host) { item in
                    HStack {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(item.name).font(term.font(16, bold: true)).foregroundColor(term.text).lineLimit(1)
                            Text(item.host).font(term.font(12)).foregroundColor(term.dim)
                        }
                        Spacer(minLength: 0)
                        TButton(kind: .outlined, action: {
                            // Keep the wake address when the same TV is chosen again.
                            var chosen = item
                            if vm.audioTv?.name == item.name { chosen.mac = vm.audioTv?.mac }
                            vm.setAudioTv(chosen)
                            dismiss()
                        }) { Text("Use") }
                    }
                }
            } else {
                Text("Searching…").font(term.font(14)).foregroundColor(term.dim)
            }
            Spacer(minLength: 0)
            HStack {
                TButton(kind: .text, action: { dismiss() }) { Text("Close") }
                Spacer()
                TButton(kind: .text, action: { round += 1 }) { Text("Search again") }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(term.bg.ignoresSafeArea())
        .environment(\.term, term)
        .task(id: round) {
            found = nil
            found = Demo.enabled ? [Demo.audioTv] : await discoverAudioTvs()
        }
        .presentationDetents([.medium, .large])
    }
}
