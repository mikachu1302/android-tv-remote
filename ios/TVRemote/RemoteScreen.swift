import SwiftUI
import TVRemoteCore

/// The pages of the big area under the app row, in swipe order.
enum PadPage: Int, CaseIterable {
    case pad, numbers, more

    var label: String {
        switch self {
        case .pad: return "Pad"
        case .numbers: return "Numbers"
        case .more: return "More keys"
        }
    }
}

struct RemoteScreen: View {
    let tv: PairedTv
    var onSettings: () -> Void
    var onAddApp: () -> Void

    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    @StateObject private var pageDrag = PageDragState()
    @State private var page = Demo.enabled ? min(max(Demo.page, 0), 2) : 0
    /// Keyboard bar: nil = closed, otherwise the id of the session (a new id resets the field).
    @State private var keyboard: Int? = Demo.enabled && Demo.screen == "keyboard" ? 1 : nil
    @State private var keyboardInitial = ""
    @State private var sessions = 0

    private var on: Bool { vm.tvState.on == true }

    var body: some View {
        ZStack(alignment: .bottom) {
            VStack(spacing: 10) {
                if term.crt {
                    CrtHeader(tv: tv, on: on, onSettings: onSettings)
                } else {
                    GlassHeader(tv: tv, on: on, onSettings: onSettings)
                }
                AppRow(onAddApp: onAddApp)
                PadPager(page: $page)
                PagerDots(page: $page)
                BottomControls(keyboardOpen: keyboard != nil, onKeyboard: toggleKeyboard)
                    .environment(\.swipeable, true)
            }
            // Extra room below: the home-gesture area reaches higher than the system bar's own inset.
            .padding(.init(top: 6, leading: 16, bottom: 12, trailing: 16))
            .ignoresSafeArea(.keyboard)

            if let id = keyboard {
                KeyboardBar(initial: keyboardInitial, label: vm.tvState.textField?.label ?? "", onClose: { keyboard = nil })
                    .id(id)
            }
        }
        .environmentObject(pageDrag)
        // The TV asked for the keyboard (a text field got focus): open ours with that field's text.
        .onChange(of: vm.tvState.keyboardRequests) { count in
            if count > 0 { openKeyboard(vm.tvState.textField?.value ?? "") }
        }
    }

    private func openKeyboard(_ initial: String) {
        keyboardInitial = initial
        sessions += 1
        keyboard = sessions
    }

    private func toggleKeyboard() {
        if keyboard == nil { openKeyboard("") } else { keyboard = nil }
    }
}

// MARK: - status

private struct Chip: Identifiable {
    let id = UUID()
    let text: String
    let color: Color
    var dot = true
}

private func statusChips(link: Link, state: TvState, voice: VoiceUi, term: TermTheme) -> [Chip] {
    var chips = [Chip]()
    switch voice {
    case .listening: chips.append(Chip(text: "listening…", color: term.action))
    case .starting: chips.append(Chip(text: "starting voice…", color: term.dim))
    case .unavailable: chips.append(Chip(text: "voice not available right now", color: term.warn))
    case .idle: break
    }
    switch link {
    case .connected:
        chips.append(Chip(text: "connected", color: term.primary))
        if let on = state.on { chips.append(Chip(text: on ? "tv on" : "tv off", color: on ? term.soft : term.dim)) }
    case .connecting: chips.append(Chip(text: "connecting…", color: term.dim))
    case .retrying: chips.append(Chip(text: "can't reach the TV, retrying (tap to retry now)", color: term.warn))
    case .pinChanged: chips.append(Chip(text: "certificate changed, pair again", color: term.warn))
    case .idle: chips.append(Chip(text: "not connected", color: term.dim))
    }
    // The stick keeps reporting the last app after it went off: only an app on a stick that is on is "open".
    if let pkg = state.appPackage, state.on != false {
        chips.append(Chip(text: shortcutForPackage(pkg, label: state.appLabel).name, color: term.action, dot: false))
    }
    return chips
}

/// The status line of the CRT style: chips as upper-case text separated by dots.
private func statusLine(_ chips: [Chip], separator: Color, font: Font) -> Text {
    var text = Text("")
    for (index, chip) in chips.enumerated() {
        if index > 0 { text = text + Text("  ·  ").foregroundColor(separator) }
        text = text + Text(chip.text.uppercased()).foregroundColor(chip.color)
    }
    return text.font(font)
}

private struct StatusChipView: View {
    @Environment(\.term) private var term
    let chip: Chip

    var body: some View {
        HStack(spacing: 5) {
            if chip.dot { Circle().fill(chip.color).frame(width: 6, height: 6) }
            Text(chip.text).font(term.font(12)).foregroundColor(chip.color).lineLimit(1)
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 2)
        .background(Capsule().fill(chip.color.opacity(0.12)))
        .overlay(Capsule().strokeBorder(chip.color.opacity(0.5), lineWidth: 1))
    }
}

// MARK: - headers

private struct CrtHeader: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let tv: PairedTv
    let on: Bool
    let onSettings: () -> Void

    var body: some View {
        let chips = statusChips(link: vm.link, state: vm.tvState, voice: vm.voice, term: term)
        HStack(alignment: .top, spacing: 12) {
            RemoteButton("Settings", width: 52, height: 52, onClick: onSettings) { color in
                Text("SET").font(term.font(13)).foregroundColor(color)
            }
            VStack(alignment: .leading, spacing: 0) {
                TvSwitcher(active: tv)
                statusLine(chips, separator: term.dim, font: term.font(14)).lineSpacing(0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { vm.retry() }
            RemoteButton(powerLabel(vm.tvState), key: Key.power, width: 52, height: 52, active: on) { color in
                Text("PWR").font(term.font(13)).foregroundColor(color).textGlow(color, on: on)
            }
        }
    }
}

private struct GlassHeader: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let tv: PairedTv
    let on: Bool
    let onSettings: () -> Void

    var body: some View {
        let chips = statusChips(link: vm.link, state: vm.tvState, voice: vm.voice, term: term)
        HStack(spacing: 12) {
            RemoteButton("Settings", width: 46, height: 46, shape: .round, onClick: onSettings) { color in
                Glyph(name: "gear", color: color, size: 22)
            }
            VStack(alignment: .leading, spacing: 6) {
                TvSwitcher(active: tv)
                FlowLayout(spacing: 6, lineSpacing: 4) { ForEach(chips) { StatusChipView(chip: $0) } }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { vm.retry() }
            RemoteButton(powerLabel(vm.tvState), key: Key.power, width: 46, height: 46, active: on, shape: .round) { color in
                Glyph(name: "power", color: color, size: 22)
            }
        }
        .padding(12)
        .pane(KeyShape(radius: 20))
    }
}

private func powerLabel(_ state: TvState) -> String {
    "Power, " + (state.on.map { $0 ? "on" : "off" } ?? "state unknown")
}

/// The TV's name; tap it to switch to another paired TV or add one.
private struct TvSwitcher: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let active: PairedTv

    var body: some View {
        let nameColor = term.crt ? term.primary : term.text
        Menu {
            ForEach(vm.tvs, id: \.name) { tv in
                Button { vm.selectTv(tv) } label: {
                    if tv == active { Label(tv.name, systemImage: "checkmark") } else { Text(tv.name) }
                }
            }
            Button("Add a TV…") { vm.beginAddTv() }
        } label: {
            HStack(spacing: 4) {
                Text(term.crt ? "> \(active.name)" : active.name)
                    .font(term.font(15, bold: true))
                    .foregroundColor(nameColor)
                    .textGlow(nameColor, on: term.crt)
                    .lineLimit(1)
                Glyph(name: "down", color: nameColor, size: 16, strokeWidth: 2.6)
            }
        }
        .accessibilityLabel("TV: \(active.name). Switch TV")
    }
}

// MARK: - app row

private struct AppRow: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let onAddApp: () -> Void

    private var current: AppShortcut? {
        // The stick keeps reporting the last app after it went off: only an app on a stick that is on is "open".
        guard vm.tvState.on != false, let pkg = vm.tvState.appPackage else { return nil }
        return shortcutForPackage(pkg, label: vm.tvState.appLabel)
    }

    var body: some View {
        let size: CGFloat = term.crt ? 58 : 56
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: term.crt ? 8 : 10) {
                ForEach(vm.apps, id: \.self) { app in
                    Button {
                        if vm.settings.haptics { Haptics.tap() }
                        vm.launch(app.link)
                    } label: {
                        VStack(spacing: 4) {
                            AppTile(name: app.name, size: size, current: current?.link == app.link)
                            label(term.crt ? app.name.uppercased() : app.name)
                        }
                        .frame(width: 66)
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Open \(app.name) on the TV")
                    .accessibilityIdentifier("app-" + app.name)
                }
                Button(action: onAddApp) {
                    VStack(spacing: 4) {
                        Glyph(name: "plus", color: term.primary, size: 26, strokeWidth: 2.4)
                            .frame(width: size, height: size)
                            .pane(term.crt ? .square : KeyShape(radius: size * 0.3))
                        label(term.crt ? "ADD" : "Add")
                    }
                    .frame(width: 66)
                    .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add an app shortcut")
                .accessibilityIdentifier("app-add")
            }
        }
    }

    private func label(_ text: String) -> some View {
        Text(text).font(term.font(11)).foregroundColor(term.dim).lineLimit(1).frame(width: 66)
    }
}

// MARK: - the pager

/// The big area: the D-pad, the number pad and the more keys, swiped sideways. A touch that starts on a key and
/// turns into a swipe sends nothing (see `RemoteButton`).
private struct PadPager: View {
    @EnvironmentObject private var drag: PageDragState
    @Environment(\.term) private var term
    @Binding var page: Int
    @State private var dragX: CGFloat = 0
    @State private var swiping = false

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            HStack(spacing: 0) {
                ForEach(PadPage.allCases, id: \.rawValue) { entry in
                    PageContent(entry: entry)
                        .frame(width: width, height: proxy.size.height)
                        .accessibilityHidden(entry.rawValue != page)
                }
            }
            .environment(\.swipeable, true)
            .offset(x: -CGFloat(page) * width + dragX)
            .frame(width: width, height: proxy.size.height, alignment: .leading)
            .clipped()
            .contentShape(Rectangle())
            .simultaneousGesture(
                DragGesture(minimumDistance: 8)
                    .onChanged { value in
                        if !swiping {
                            guard abs(value.translation.width) > abs(value.translation.height) else { return }
                            swiping = true
                            drag.dragging = true
                        }
                        var x = value.translation.width
                        // Pull harder at the ends.
                        if (page == 0 && x > 0) || (page == PadPage.allCases.count - 1 && x < 0) { x /= 3 }
                        dragX = x
                    }
                    .onEnded { value in
                        defer {
                            swiping = false
                            drag.dragging = false
                        }
                        guard swiping else { return }
                        let projected = value.predictedEndTranslation.width
                        var target = page
                        if value.translation.width < -width * 0.2 || projected < -width * 0.5 { target += 1 }
                        if value.translation.width > width * 0.2 || projected > width * 0.5 { target -= 1 }
                        withAnimation(.easeOut(duration: 0.22)) {
                            page = min(max(target, 0), PadPage.allCases.count - 1)
                            dragX = 0
                        }
                    }
            )
        }
    }
}

private struct PageContent: View {
    @Environment(\.term) private var term
    let entry: PadPage

    var body: some View {
        switch entry {
        case .pad: if term.crt { CrtPad() } else { GlassDial() }
        case .numbers: Keypad()
        case .more: MoreKeysPage()
        }
    }
}

/// The little pager: one dot per page, the current one a longer bar. Tapping a dot goes to that page.
private struct PagerDots: View {
    @Environment(\.term) private var term
    @Binding var page: Int

    var body: some View {
        HStack(spacing: 2) {
            ForEach(PadPage.allCases, id: \.rawValue) { entry in
                let selected = entry.rawValue == page
                Button {
                    withAnimation(.easeOut(duration: 0.22)) { page = entry.rawValue }
                } label: {
                    (term.crt ? AnyShape(Rectangle()) : AnyShape(Capsule()))
                        .fill(selected ? term.primary : term.border)
                        .frame(width: selected ? 22 : 8, height: 8)
                        .frame(width: 36, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(entry.label), page \(entry.rawValue + 1) of \(PadPage.allCases.count)")
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier("dot-\(entry.rawValue)")
            }
        }
        .frame(height: 24)
        .animation(.easeOut(duration: 0.2), value: page)
    }
}

// MARK: - pads

private struct CrtPad: View {
    var body: some View {
        GeometryReader { proxy in
            let cell = min(92, (proxy.size.height - 12) / 3, (proxy.size.width - 12) / 3)
            VStack(spacing: 6) {
                RemoteButton("Up", key: Key.up, width: cell, height: cell, repeats: true) { Solid(name: "tri-up", color: $0, size: cell * 0.33) }
                HStack(spacing: 6) {
                    RemoteButton("Left", key: Key.left, width: cell, height: cell, repeats: true) { Solid(name: "tri-left", color: $0, size: cell * 0.33) }
                    RemoteButton("OK", key: Key.center, width: cell, height: cell, look: .action, strong: true) { color in
                        CrtText("OK", size: 26, color: color, bold: true, glow: true)
                    }
                    RemoteButton("Right", key: Key.right, width: cell, height: cell, repeats: true) { Solid(name: "tri-right", color: $0, size: cell * 0.33) }
                }
                RemoteButton("Down", key: Key.down, width: cell, height: cell, repeats: true) { Solid(name: "tri-down", color: $0, size: cell * 0.33) }
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
    }
}

/// Text in the style's font and a colour, optionally glowing (the CRT look).
struct CrtText: View {
    @Environment(\.term) private var term
    let text: String
    var size: CGFloat
    var color: Color
    var bold = false
    var glow = false

    init(_ text: String, size: CGFloat, color: Color, bold: Bool = false, glow: Bool = false) {
        self.text = text; self.size = size; self.color = color; self.bold = bold; self.glow = glow
    }

    var body: some View {
        Text(text).font(term.font(size, bold: bold)).foregroundColor(color).textGlow(color, on: glow).lineLimit(1)
    }
}

private struct GlassDial: View {
    @Environment(\.term) private var term

    var body: some View {
        GeometryReader { proxy in
            // 272 pt when there is room (the glow around the ring needs a little air), smaller otherwise.
            let dial = max(120, min(272, proxy.size.height - 16, proxy.size.width - 16))
            let s = dial / 272
            let arrow = 64 * s
            let edge = 10 * s
            // The ring fades between the two accents, or runs through the rainbow (Pride); it closes on its first colour.
            let stripes = term.palette.stripes ?? []
            let colours: [UInt32] = stripes.count >= 2 ? stripes + [stripes[0]] : [term.palette.primary, term.palette.action, term.palette.primary]
            let ring = colours.map { Color(hex: $0) }
            ZStack {
                Circle().stroke(term.action.opacity(0.10), lineWidth: 16).padding(-2)
                Circle().stroke(term.primary.opacity(0.10), lineWidth: 8).padding(2)
                Circle().fill(RadialGradient(colors: [term.surface, term.bg], center: .center, startRadius: 0, endRadius: dial / 2)).padding(2)
                Circle().strokeBorder(AngularGradient(colors: ring, center: .center), lineWidth: 4).padding(2)

                RemoteButton("Up", key: Key.up, width: arrow, height: arrow, repeats: true, ghost: true, shape: .round) { Glyph(name: "up", color: $0, size: 32, strokeWidth: 2.4) }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top).padding(.top, edge)
                RemoteButton("Down", key: Key.down, width: arrow, height: arrow, repeats: true, ghost: true, shape: .round) { Glyph(name: "down", color: $0, size: 32, strokeWidth: 2.4) }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom).padding(.bottom, edge)
                RemoteButton("Left", key: Key.left, width: arrow, height: arrow, repeats: true, ghost: true, shape: .round) { Glyph(name: "left", color: $0, size: 32, strokeWidth: 2.4) }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading).padding(.leading, edge)
                RemoteButton("Right", key: Key.right, width: arrow, height: arrow, repeats: true, ghost: true, shape: .round) { Glyph(name: "right", color: $0, size: 32, strokeWidth: 2.4) }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing).padding(.trailing, edge)
                RemoteButton("OK", key: Key.center, width: 100 * s, height: 100 * s, look: .action, strong: true, shape: .round) { color in
                    CrtText("OK", size: 22, color: color, bold: true)
                }
            }
            .frame(width: dial, height: dial)
            .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
        }
    }
}

private struct Keypad: View {
    @Environment(\.term) private var term

    var body: some View {
        let gap: CGFloat = term.crt ? 6 : 8
        // Rows share the height of the page (at most 4 x 64 pt), so the pad fits whatever room there is.
        VStack(spacing: gap) {
            ForEach(0..<3, id: \.self) { row in
                HStack(spacing: gap) {
                    ForEach(0..<3, id: \.self) { col in
                        let n = row * 3 + col + 1
                        RemoteButton("Digit \(n)", key: Key.digit0 + n) { CrtText("\(n)", size: 24, color: $0) }
                    }
                }
            }
            HStack(spacing: gap) {
                RemoteButton("TV input", key: Key.tv, look: .primary) { CrtText("TV", size: 20, color: $0) }
                RemoteButton("Digit 0", key: Key.digit0) { CrtText("0", size: 24, color: $0) }
                RemoteButton("Menu", key: Key.menu, look: .primary) { CrtText("MENU", size: 18, color: $0) }
            }
        }
        .frame(maxHeight: 280)
    }
}

private struct MoreKeysPage: View {
    @Environment(\.term) private var term

    var body: some View {
        let gap: CGFloat = term.crt ? 6 : 8
        VStack(spacing: gap) {
            ForEach(Array(moreKeys.enumerated()), id: \.offset) { _, row in
                HStack(spacing: gap) {
                    ForEach(row, id: \.label) { key in
                        RemoteButton(key.description, key: key.code, repeats: key.repeats) { color in
                            if let glyph = key.glyph {
                                Solid(name: glyph, color: color, size: 24)
                            } else if let bar = key.color {
                                RoundedRectangle(cornerRadius: 3).fill(Color(hex: bar)).frame(width: 30, height: 12)
                            } else {
                                CrtText(key.label, size: 13, color: color)
                            }
                        }
                    }
                }
            }
        }
        .frame(maxHeight: 290)
    }
}

// MARK: - bottom controls

private struct BottomControls: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let keyboardOpen: Bool
    let onKeyboard: () -> Void

    var body: some View {
        let volume = vm.audio.map { Volume(level: $0.level, max: 100, muted: $0.muted) } ?? vm.tvState.volume
        let muted = volume?.muted == true
        HStack(spacing: 8) {
            Rocker(title: volume == nil ? "VOL" : (muted ? "MUTED" : "VOL \(volume!.level)"),
                   upLabel: "Volume up", upKey: Key.volumeUp, downLabel: "Volume down", downKey: Key.volumeDown)
            VStack(spacing: term.crt ? 6 : 8) {
                HStack(spacing: 6) {
                    ClusterKey(label: "KBD", description: "Keyboard", glyph: "kbd", key: nil, active: keyboardOpen, onClick: onKeyboard)
                    ClusterKey(label: "HOME", description: "Home", glyph: "home", key: Key.home)
                    ClusterKey(label: "MENU", description: "Menu", glyph: "menu", key: Key.menu)
                }
                HStack(spacing: 6) {
                    ClusterKey(label: "MUTE", description: "Mute, " + (muted ? "on" : "off"), glyph: muted ? "speaker-off" : "speaker",
                               key: Key.volumeMute, active: muted, activeColor: term.action)
                    ClusterKey(label: "MIC", description: "Voice search, hold to talk", glyph: "mic", key: nil,
                               active: vm.voice == .listening, activeColor: term.action,
                               onDown: { vm.startVoice() }, onUp: { vm.stopVoice() })
                    ClusterKey(label: "BACK", description: "Back", glyph: "back", key: Key.back)
                }
                RemoteButton("Play or pause", key: Key.playPause, height: 56, look: .primary, shape: term.crt ? nil : .round) { color in
                    if term.crt { CrtText("PLAY / PAUSE", size: 17, color: color, glow: true) } else { Glyph(name: "play", color: color, size: 26) }
                }
            }
            Rocker(title: "CH", upLabel: "Channel up", upKey: Key.channelUp, downLabel: "Channel down", downKey: Key.channelDown)
        }
        .frame(height: term.crt ? 184 : 188)
    }
}

/// A key of the 3 x 3 cluster: a text label in the CRT style, an icon in the glass style.
private struct ClusterKey: View {
    @Environment(\.term) private var term
    let label: String
    let description: String
    let glyph: String
    let key: Int?
    var active = false
    var activeColor: Color?
    var onClick: (() -> Void)?
    var onDown: (() -> Void)?
    var onUp: (() -> Void)?

    var body: some View {
        if term.crt {
            RemoteButton(description, key: key, height: 56, active: active, activeColor: activeColor, onClick: onClick, onDown: onDown, onUp: onUp) { color in
                CrtText(label, size: 13, color: color, glow: active)
            }
        } else {
            RemoteButton(description, key: key, active: active, activeColor: activeColor, shape: .round, onClick: onClick, onDown: onDown, onUp: onUp) { color in
                Glyph(name: glyph, color: color, size: 24)
            }
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: 56, maxHeight: 56)
            .frame(maxWidth: .infinity)
        }
    }
}

private struct Rocker: View {
    @Environment(\.term) private var term
    let title: String
    let upLabel: String
    let upKey: Int
    let downLabel: String
    let downKey: Int

    var body: some View {
        if term.crt {
            VStack(spacing: 0) {
                RemoteButton(upLabel, key: upKey, height: 66, repeats: true, look: .primary) { CrtText("+", size: 30, color: $0, bold: true, glow: true) }
                Spacer(minLength: 0)
                Text(title).font(term.font(12)).foregroundColor(term.dim).lineLimit(1)
                Spacer(minLength: 0)
                RemoteButton(downLabel, key: downKey, height: 66, repeats: true, look: .primary) { CrtText("−", size: 30, color: $0, bold: true, glow: true) }
            }
            .frame(width: 72)
        } else {
            VStack(spacing: 0) {
                RemoteButton(upLabel, key: upKey, width: 58, height: 58, repeats: true, look: .primary, shape: .round) { CrtText("+", size: 24, color: $0, bold: true) }
                Spacer(minLength: 0)
                Text(title).font(term.font(11)).foregroundColor(term.dim).lineLimit(1)
                Spacer(minLength: 0)
                RemoteButton(downLabel, key: downKey, width: 58, height: 58, repeats: true, look: .primary, shape: .round) { CrtText("−", size: 24, color: $0, bold: true) }
            }
            .padding(6)
            .frame(width: 72)
            .pane(KeyShape(radius: 36))
        }
    }
}

// MARK: - keyboard

/// An invisible character that is always in the field, so backspace has something to delete.
private let sentinel = "\u{200B}"

/// Typing here is mirrored on the TV's focused text field, character by character. Docked above the phone's keyboard
/// so the remote stays usable.
private struct KeyboardBar: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let initial: String
    let label: String
    let onClose: () -> Void

    /// What we believe the TV's field holds. The field shows `sentinel` + mirror.
    @State private var mirror = ""
    @State private var text = sentinel
    @FocusState private var focused: Bool

    var body: some View {
        let shape = term.crt ? KeyShape.square : KeyShape(radius: 20)
        HStack(spacing: 8) {
            TextField(label.isEmpty ? "Type on the TV" : label, text: $text)
                .font(term.font(16))
                .foregroundColor(term.text)
                .tint(term.primary)
                .focused($focused)
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
                .submitLabel(.send)
                .onSubmit {
                    vm.press(Key.enter)
                    onClose()
                }
                .padding(.horizontal, 12)
                .frame(height: 44)
                .background(term.controlShape.fill(term.bg))
                .overlay(term.controlShape.strokeBorder(term.border, lineWidth: 1))
                .accessibilityIdentifier("keyboard-field")
            TButton(kind: .text, action: onClose) { Text("Close") }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(shape.fill(term.surface))
        .overlay(shape.strokeBorder(term.crt ? term.primary : term.primary.opacity(0.4), lineWidth: term.borderWidth))
        .onAppear {
            mirror = initial
            text = sentinel + initial
            focused = true
        }
        .onChange(of: text) { raw in
            let content = raw.hasPrefix(sentinel) ? String(raw.dropFirst()) : raw
            if raw.isEmpty && mirror.isEmpty {
                // Backspace over the sentinel with nothing typed: delete on the TV.
                vm.press(Key.del)
                text = sentinel
            } else if content != mirror {
                vm.typed(old: mirror, new: content)
                mirror = content
                if !raw.hasPrefix(sentinel) { text = sentinel + content }
            }
        }
    }
}
