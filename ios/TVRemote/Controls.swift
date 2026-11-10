import SwiftUI
import UIKit
import TVRemoteCore

/// Which colour a key speaks in: plain, "alive" (plus/minus, play) or the action colour (OK, mic).
enum Look { case normal, primary, action }

/// Set while a page of the remote is being swiped: a touch that started on a key must then not press it.
@MainActor
final class PageDragState: ObservableObject {
    @Published var dragging = false
}

enum Haptics {
    private static let generator = UIImpactFeedbackGenerator(style: .light)
    static func tap() { generator.impactOccurred() }
}

/// How long a touch on a swipeable page waits to see whether it becomes a swipe.
private let swipeWindowNanos: UInt64 = 100_000_000

/// What one touch on a button is doing: its timers and whether the key went down yet.
private final class PressSession {
    var downFired = false
    var smooth = false
    var finished = false
    var window: Task<Void, Never>?
    var ticker: Task<Void, Never>?
}

/// One remote button, drawn for the current style. Fires on press (not release) so it feels immediate. With
/// `repeats`, holding it keeps the key going: "smooth press" holds the key down on the TV like a physical remote,
/// otherwise a key press is repeated. A nil `keyCode` makes it a plain button (`onClick` on release, `onDown`/`onUp`
/// for hold actions). `active` marks a toggled-on state in `activeColor`; `strong` keeps the glowing border;
/// `ghost` draws no background or border (arrows inside the glass dial). `content` gets the colour to draw in.
struct RemoteButton<Content: View>: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @EnvironmentObject private var pageDrag: PageDragState
    @Environment(\.term) private var term
    @Environment(\.swipeable) private var swipeable

    let description: String
    var keyCode: Int?
    var width: CGFloat?
    var height: CGFloat?
    var repeats: Bool
    var look: Look
    var active: Bool
    var activeColor: Color?
    var strong: Bool
    var ghost: Bool
    var shape: KeyShape?
    var onClick: (() -> Void)?
    var onDown: (() -> Void)?
    var onUp: (() -> Void)?
    @ViewBuilder var content: (Color) -> Content

    @State private var pressed = false
    @State private var session: PressSession?
    /// The current touch moved like a swipe: ignore the rest of it. Cleared when the finger lifts and, as a second guard, at
    /// the first event of the next touch (which always starts with zero movement), so a missed lift cannot swallow a tap.
    @State private var ignoreTouch = false
    @GestureState private var isDown = false

    init(
        _ description: String, key keyCode: Int? = nil, width: CGFloat? = nil, height: CGFloat? = nil,
        repeats: Bool = false, look: Look = .normal, active: Bool = false, activeColor: Color? = nil,
        strong: Bool = false, ghost: Bool = false, shape: KeyShape? = nil,
        onClick: (() -> Void)? = nil, onDown: (() -> Void)? = nil, onUp: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Color) -> Content
    ) {
        self.description = description; self.keyCode = keyCode; self.width = width; self.height = height
        self.repeats = repeats; self.look = look; self.active = active; self.activeColor = activeColor
        self.strong = strong; self.ghost = ghost; self.shape = shape
        self.onClick = onClick; self.onDown = onDown; self.onUp = onUp; self.content = content
    }

    private var keyShape: KeyShape { shape ?? term.controlShape }
    private var accent: Color { look == .action ? term.action : term.primary }
    private var glowColor: Color? { active ? (activeColor ?? accent) : (strong ? accent : nil) }

    private var contentColor: Color {
        if active { return activeColor ?? accent }
        switch look {
        case .primary: return term.primary
        case .action: return term.action
        case .normal: return term.crt ? term.text : term.dim
        }
    }

    private var fill: Color {
        if ghost { return .clear }
        if pressed { return accent.opacity(0.30).overBackground(term.surface) }
        if strong && !term.crt { return term.bg }
        return term.surface
    }

    var body: some View {
        ZStack { content(contentColor) }
            .frame(minWidth: width, maxWidth: width ?? .infinity, minHeight: height, maxHeight: height ?? .infinity)
            .background(keyShape.fill(fill))
            .overlay(borderView)
            .background(glowBackground)
            .contentShape(keyShape)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .updating($isDown) { _, state, _ in state = true }
                    .onChanged { value in
                        if value.translation == .zero { ignoreTouch = false }
                        if ignoreTouch { return }
                        if session == nil { begin() }
                        // A touch that travels this far before the key went down is a swipe, not a press.
                        if let s = session, !s.downFired, swipeable, hypot(value.translation.width, value.translation.height) > 10 {
                            ignoreTouch = true
                            end(completed: false)
                        }
                    }
                    .onEnded { _ in
                        if !ignoreTouch { end(completed: true) }
                    }
            )
            .onChange(of: isDown) { down in
                if down { return }
                ignoreTouch = false
                // Without an `onEnded` the touch was taken over (a swipe, the system): release without clicking.
                DispatchQueue.main.async { if session != nil { end(completed: false) } }
            }
            .onChange(of: pageDrag.dragging) { dragging in
                if dragging, let s = session, !s.downFired {
                    ignoreTouch = true
                    end(completed: false)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(description)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("key-" + description)
            .accessibilityAction(.default) { tapForAccessibility() }
    }

    @ViewBuilder private var borderView: some View {
        if ghost {
            EmptyView()
        } else if let glow = glowColor {
            keyShape.strokeBorder(glow, lineWidth: 2)
        } else {
            keyShape.strokeBorder(term.paneBorder, lineWidth: term.borderWidth)
        }
    }

    @ViewBuilder private var glowBackground: some View {
        if let glow = glowColor, !ghost {
            ZStack {
                keyShape.stroke(glow.opacity(0.05), lineWidth: 18)
                keyShape.stroke(glow.opacity(0.08), lineWidth: 12)
                keyShape.stroke(glow.opacity(0.16), lineWidth: 7)
            }
        }
    }

    // MARK: the touch

    private func begin() {
        let s = PressSession()
        session = s
        pressed = true
        if swipeable {
            // The touch may be a swipe (next page, or the system home gesture): wait briefly. If the pager or the
            // system takes the touch over, nothing is sent; a quick tap is sent as one short press.
            s.window = Task { @MainActor in
                try? await Task.sleep(nanoseconds: swipeWindowNanos)
                if Task.isCancelled || s.finished { return }
                fireDown(s)
            }
        } else {
            fireDown(s)
        }
    }

    private func fireDown(_ s: PressSession) {
        if vm.settings.haptics { Haptics.tap() }
        onDown?()
        s.downFired = true
        guard let code = keyCode else { return }
        s.smooth = repeats && vm.settings.smoothPress
        vm.press(code, s.smooth ? .startLong : .short)
        if repeats && !s.smooth {
            s.ticker = Task { @MainActor in
                try? await Task.sleep(nanoseconds: 400_000_000)
                while !Task.isCancelled {
                    vm.press(code)
                    try? await Task.sleep(nanoseconds: 150_000_000)
                }
            }
        }
    }

    private func end(completed: Bool) {
        guard let s = session, !s.finished else { return }
        s.finished = true
        s.window?.cancel()
        s.ticker?.cancel()
        if s.downFired {
            // Also runs when the button leaves the screen mid-press: never leave a key held down.
            if s.smooth, let code = keyCode { vm.press(code, .endLong) }
            onUp?()
            if completed { onClick?() }
        } else if completed {
            // A tap inside the swipe window: one short press.
            if vm.settings.haptics { Haptics.tap() }
            onDown?()
            if let code = keyCode { vm.press(code) }
            onClick?()
            onUp?()
        }
        session = nil
        pressed = false
    }

    private func tapForAccessibility() {
        onDown?()
        if let code = keyCode { vm.press(code) }
        onClick?()
        onUp?()
    }
}

extension Color {
    /// This colour (with its alpha) drawn over `background`, as one opaque colour.
    func overBackground(_ background: Color) -> Color {
        let top = UIColor(self), bottom = UIColor(background)
        var (tr, tg, tb, ta): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        var (br, bg, bb, ba): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        top.getRed(&tr, green: &tg, blue: &tb, alpha: &ta)
        bottom.getRed(&br, green: &bg, blue: &bb, alpha: &ba)
        return Color(.sRGB, red: tr * ta + br * (1 - ta), green: tg * ta + bg * (1 - ta), blue: tb * ta + bb * (1 - ta), opacity: 1)
    }
}

/// An app's tile: its logo when we have one (brand colours in the glass style, the palette's "alive" colour with a
/// faint glow in the CRT style), else its initials. `current` marks the app that is open on the TV.
struct AppTile: View {
    @Environment(\.term) private var term
    let name: String
    var size: CGFloat = 58
    var current = false

    var body: some View {
        let shape = term.crt ? KeyShape.square : KeyShape(radius: size * 0.3)
        ZStack {
            if let logo = appLogos[name] {
                let color: Color = term.crt
                    ? (current ? term.action : term.primary)
                    : (logo.brand.map { Color(hex: $0) } ?? term.text)
                LogoMark(logo: logo, color: color, halo: term.crt)
                    .padding(size * 0.26)
            } else {
                let initials = String(name.filter { $0.isLetter || $0.isNumber }.prefix(2)).capitalizedFirst
                Text(initials)
                    .font(term.font(18, bold: true))
                    .foregroundColor(term.crt ? (current ? term.action : term.primary) : term.soft)
            }
        }
        .frame(width: size, height: size)
        .pane(shape, glowColor: current ? term.action : nil)
    }
}

extension String {
    /// The first letter in upper case, the rest as it is.
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}

/// Lays its children out left to right and wraps them onto new lines, like a flow of words.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6
    var lineSpacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(width: proposal.width ?? .infinity, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrange(width: bounds.width, subviews: subviews)
        for (index, origin) in result.origins.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + origin.x, y: bounds.minY + origin.y), proposal: .unspecified)
        }
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> (size: CGSize, origins: [CGPoint]) {
        var origins = [CGPoint]()
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                x = 0
                y += rowHeight + lineSpacing
                rowHeight = 0
            }
            origins.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            maxX = max(maxX, x - spacing)
        }
        return (CGSize(width: maxX, height: y + rowHeight), origins)
    }
}
