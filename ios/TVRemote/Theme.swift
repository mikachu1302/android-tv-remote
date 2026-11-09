import SwiftUI
import UIKit
import TVRemoteCore

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }
}

/// A rounded rectangle whose corner radius is fixed (square for 0) or "as round as possible" (a circle for a
/// square frame, a capsule for a wide one) with `.infinity`.
struct KeyShape: InsettableShape {
    var radius: CGFloat
    var inset: CGFloat = 0

    static let square = KeyShape(radius: 0)
    static let round = KeyShape(radius: .infinity)

    func path(in rect: CGRect) -> Path {
        let r = rect.insetBy(dx: inset, dy: inset)
        let corner = radius == .infinity ? min(r.width, r.height) / 2 : max(0, radius - inset)
        return RoundedRectangle(cornerRadius: corner, style: .continuous).path(in: r)
    }

    func inset(by amount: CGFloat) -> KeyShape {
        var copy = self
        copy.inset += amount
        return copy
    }
}

/// What every view asks for: the style, the colours, and what follows from them.
struct TermTheme {
    var style: UiStyle = .crt
    var palette: Palette = PaletteData.neon
    var scanlines = true

    var crt: Bool { style == .crt }

    var bg: Color { Color(hex: palette.bg) }
    var surface: Color { Color(hex: palette.surface) }
    var border: Color { Color(hex: palette.border) }
    var text: Color { Color(hex: palette.text) }
    var dim: Color { Color(hex: palette.dim) }
    var primary: Color { Color(hex: palette.primary) }
    var action: Color { Color(hex: palette.action) }
    var soft: Color { Color(hex: palette.soft) }
    var warn: Color { Color(hex: palette.warn) }

    /// Shape of keys, tiles and panes.
    var controlShape: KeyShape { crt ? .square : KeyShape(radius: 16) }
    var buttonShape: KeyShape { crt ? .square : KeyShape(radius: 12) }

    /// The border colour of an ordinary pane or key.
    var paneBorder: Color { crt ? border : primary.opacity(0.30) }
    var borderWidth: CGFloat { crt ? 2 : 1 }

    /// The font of the style. VT323 is drawn small, so its sizes are scaled up to match the other font.
    func font(_ size: CGFloat, bold: Bool = false) -> Font {
        crt ? .custom("VT323-Regular", size: size * 1.3) : Font(firaCode(size, weight: bold ? 700 : 400))
    }
}

/// Fira Code is a variable font: pick the weight along its `wght` axis.
private func firaCode(_ size: CGFloat, weight: CGFloat) -> UIFont {
    let axis = 0x7767_6874 // 'wght'
    let descriptor = UIFontDescriptor(fontAttributes: [.family: "Fira Code"])
        .addingAttributes([UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): [axis: weight]])
    let font = UIFont(descriptor: descriptor, size: size)
    return font.familyName.contains("Fira") ? font : UIFont.monospacedSystemFont(ofSize: size, weight: weight >= 600 ? .bold : .regular)
}

private struct TermKey: EnvironmentKey { static let defaultValue = TermTheme() }
private struct SwipeableKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    var term: TermTheme {
        get { self[TermKey.self] }
        set { self[TermKey.self] = newValue }
    }

    /// True for buttons a swipe can start on: the swipeable pages, and the bottom row next to the home-gesture area.
    var swipeable: Bool {
        get { self[SwipeableKey.self] }
        set { self[SwipeableKey.self] = newValue }
    }
}

// MARK: - drawing helpers

private struct Glow: ViewModifier {
    let color: Color
    let shape: KeyShape
    var width: CGFloat = 2

    func body(content: Content) -> some View {
        content.background(
            ZStack {
                shape.stroke(color.opacity(0.05), lineWidth: width * 9)
                shape.stroke(color.opacity(0.08), lineWidth: width * 6)
                shape.stroke(color.opacity(0.16), lineWidth: width * 3.5)
            }
        )
    }
}

private struct Pane: ViewModifier {
    @Environment(\.term) private var term
    let shape: KeyShape?
    let glowColor: Color?

    func body(content: Content) -> some View {
        let shape = shape ?? term.controlShape
        let border = glowColor ?? term.paneBorder
        content
            .background(shape.fill(term.surface))
            .overlay(shape.strokeBorder(border, lineWidth: term.borderWidth))
            .background(glowColor.map { color in
                ZStack {
                    shape.stroke(color.opacity(0.05), lineWidth: 18)
                    shape.stroke(color.opacity(0.08), lineWidth: 12)
                    shape.stroke(color.opacity(0.16), lineWidth: 7)
                }
            })
    }
}

extension View {
    /// A glow around `shape`: a few wide, faint strokes of `color` drawn behind the element.
    func glow(_ color: Color, _ shape: KeyShape, width: CGFloat = 2) -> some View { modifier(Glow(color: color, shape: shape, width: width)) }

    /// A pane in the current style: surface fill and border, glowing when `glowColor` is given.
    func pane(_ shape: KeyShape? = nil, glowColor: Color? = nil) -> some View { modifier(Pane(shape: shape, glowColor: glowColor)) }

    /// A text glow for the CRT look.
    func textGlow(_ color: Color, on: Bool = true) -> some View { shadow(color: on ? color.opacity(0.75) : .clear, radius: 5) }
}

/// CRT scanlines and a darkened rim over everything below it.
struct CrtOverlay: View {
    var body: some View {
        Canvas { context, size in
            var y: CGFloat = 0
            while y < size.height {
                context.fill(Path(CGRect(x: 0, y: y, width: size.width, height: 1)), with: .color(.black.opacity(0.22)))
                y += 3
            }
            let radius = hypot(size.width, size.height) / 2
            context.fill(
                Path(CGRect(origin: .zero, size: size)),
                with: .radialGradient(
                    Gradient(stops: [.init(color: .clear, location: 0), .init(color: .clear, location: 0.6),
                                     .init(color: .black.opacity(0.5), location: 1)]),
                    center: CGPoint(x: size.width / 2, y: size.height / 2), startRadius: 0, endRadius: radius
                )
            )
        }
        .allowsHitTesting(false)
        .ignoresSafeArea()
    }
}

/// The screen background: flat for the CRT style, soft colour glows in two corners for glass.
struct TermBackground: View {
    @Environment(\.term) private var term

    var body: some View {
        if term.crt {
            term.bg.ignoresSafeArea()
        } else {
            Canvas { context, size in
                let rect = Path(CGRect(origin: .zero, size: size))
                context.fill(rect, with: .color(term.bg))
                context.fill(rect, with: .radialGradient(
                    Gradient(colors: [term.action.opacity(0.30), .clear]),
                    center: CGPoint(x: size.width * 0.92, y: 0), startRadius: 0, endRadius: size.width * 1.1))
                context.fill(rect, with: .radialGradient(
                    Gradient(colors: [term.primary.opacity(0.16), .clear]),
                    center: CGPoint(x: 0, y: size.height), startRadius: 0, endRadius: size.width))
            }
            .ignoresSafeArea()
        }
    }
}

// MARK: - themed buttons (the Material buttons of the Android app)

enum TButtonKind { case filled, outlined, text }

struct TButton<Label: View>: View {
    @Environment(\.term) private var term
    @Environment(\.isEnabled) private var enabled
    var kind: TButtonKind = .filled
    let action: () -> Void
    @ViewBuilder var label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .font(term.font(15, bold: kind == .filled))
                .foregroundColor(foreground)
                .padding(.horizontal, kind == .text ? 10 : 16)
                .frame(minHeight: 40)
                .background(kind == .filled ? AnyView(term.buttonShape.fill(term.primary)) : AnyView(Color.clear))
                .overlay(kind == .outlined ? AnyView(term.buttonShape.strokeBorder(term.border, lineWidth: 1)) : AnyView(EmptyView()))
                .contentShape(term.buttonShape)
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.4)
    }

    private var foreground: Color {
        switch kind {
        case .filled: return term.bg
        case .outlined: return term.text
        case .text: return term.primary
        }
    }
}
