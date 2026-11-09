import SwiftUI
import TVRemoteCore

/// Parses SVG path data (`d` attributes) into a SwiftUI `Path`, on whatever grid the data uses (24 x 24 here).
/// Supports M L H V C S Q T A Z in absolute and relative form, implicit repeats, and compressed arc flags.
enum SVGPath {
    private static let lock = NSLock()
    private static var cache = [String: Path]()

    static func path(_ d: String) -> Path {
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[d] { return cached }
        let parsed = parse(d)
        cache[d] = parsed
        return parsed
    }

    private struct Scanner {
        let chars: [Character]
        var pos = 0

        init(_ text: String) { chars = Array(text) }

        mutating func skipSeparators() {
            while pos < chars.count, chars[pos] == " " || chars[pos] == "," || chars[pos] == "\n" || chars[pos] == "\t" { pos += 1 }
        }

        var atEnd: Bool { mutating get { skipSeparators(); return pos >= chars.count } }

        mutating func peekCommand() -> Character? {
            skipSeparators()
            guard pos < chars.count, chars[pos].isLetter, chars[pos] != "e", chars[pos] != "E" else { return nil }
            return chars[pos]
        }

        mutating func number() -> CGFloat? {
            skipSeparators()
            var text = ""
            var sawDot = false
            var i = pos
            if i < chars.count, chars[i] == "-" || chars[i] == "+" { text.append(chars[i]); i += 1 }
            var sawDigit = false
            while i < chars.count {
                let c = chars[i]
                if c.isNumber { text.append(c); sawDigit = true; i += 1 }
                else if c == ".", !sawDot { sawDot = true; text.append(c); i += 1 }
                else { break }
            }
            guard sawDigit else { return nil }
            if i < chars.count, chars[i] == "e" || chars[i] == "E" {
                var j = i + 1
                var exponent = "e"
                if j < chars.count, chars[j] == "-" || chars[j] == "+" { exponent.append(chars[j]); j += 1 }
                var digits = false
                while j < chars.count, chars[j].isNumber { exponent.append(chars[j]); j += 1; digits = true }
                if digits { text += exponent; i = j }
            }
            pos = i
            return Double(text).map { CGFloat($0) }
        }

        /// An arc flag: one character, 0 or 1, with no separator needed after it.
        mutating func flag() -> Bool? {
            skipSeparators()
            guard pos < chars.count, chars[pos] == "0" || chars[pos] == "1" else { return nil }
            defer { pos += 1 }
            return chars[pos] == "1"
        }
    }

    private static func parse(_ d: String) -> Path {
        var s = Scanner(d)
        var path = Path()
        var current = CGPoint.zero
        var start = CGPoint.zero
        var lastControl: CGPoint?   // for S/T reflection
        var lastCommand: Character = " "
        var command: Character = "M"

        while !s.atEnd {
            if let c = s.peekCommand() { command = c; s.pos += 1 } else if command == "Z" || command == "z" { break }
            let relative = command.isLowercase
            let base = relative ? current : .zero
            func point() -> CGPoint? {
                guard let x = s.number(), let y = s.number() else { return nil }
                return CGPoint(x: base.x + x, y: base.y + y)
            }

            switch command.uppercased().first! {
            case "M":
                guard let p = point() else { return path }
                path.move(to: p)
                current = p; start = p
                command = relative ? "l" : "L" // further pairs are line-to
                lastControl = nil
            case "L":
                guard let p = point() else { return path }
                path.addLine(to: p); current = p; lastControl = nil
            case "H":
                guard let x = s.number() else { return path }
                current = CGPoint(x: relative ? current.x + x : x, y: current.y)
                path.addLine(to: current); lastControl = nil
            case "V":
                guard let y = s.number() else { return path }
                current = CGPoint(x: current.x, y: relative ? current.y + y : y)
                path.addLine(to: current); lastControl = nil
            case "C":
                guard let c1 = point(), let c2 = point(), let end = point() else { return path }
                path.addCurve(to: end, control1: c1, control2: c2)
                current = end; lastControl = c2
            case "S":
                guard let c2 = point(), let end = point() else { return path }
                let c1 = (lastCommand.uppercased() == "C" || lastCommand.uppercased() == "S") && lastControl != nil
                    ? CGPoint(x: 2 * current.x - lastControl!.x, y: 2 * current.y - lastControl!.y) : current
                path.addCurve(to: end, control1: c1, control2: c2)
                current = end; lastControl = c2
            case "Q":
                guard let c = point(), let end = point() else { return path }
                path.addQuadCurve(to: end, control: c)
                current = end; lastControl = c
            case "T":
                guard let end = point() else { return path }
                let c = (lastCommand.uppercased() == "Q" || lastCommand.uppercased() == "T") && lastControl != nil
                    ? CGPoint(x: 2 * current.x - lastControl!.x, y: 2 * current.y - lastControl!.y) : current
                path.addQuadCurve(to: end, control: c)
                current = end; lastControl = c
            case "A":
                guard let rx = s.number(), let ry = s.number(), let rotation = s.number(),
                      let large = s.flag(), let sweep = s.flag(), let end = point() else { return path }
                addArc(&path, from: current, rx: rx, ry: ry, rotation: rotation, large: large, sweep: sweep, to: end)
                current = end; lastControl = nil
            case "Z":
                path.closeSubpath()
                current = start; lastControl = nil
            default:
                return path
            }
            lastCommand = command
        }
        return path
    }

    /// An SVG elliptical arc as cubic Béziers (SVG 1.1, implementation notes F.6).
    private static func addArc(_ path: inout Path, from p0: CGPoint, rx rxIn: CGFloat, ry ryIn: CGFloat, rotation: CGFloat,
                               large: Bool, sweep: Bool, to p1: CGPoint) {
        if p0 == p1 { return }
        var rx = abs(rxIn), ry = abs(ryIn)
        if rx == 0 || ry == 0 { path.addLine(to: p1); return }
        let phi = rotation * .pi / 180
        let cosPhi = cos(phi), sinPhi = sin(phi)
        let dx2 = (p0.x - p1.x) / 2, dy2 = (p0.y - p1.y) / 2
        let x1p = cosPhi * dx2 + sinPhi * dy2
        let y1p = -sinPhi * dx2 + cosPhi * dy2
        let lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if lambda > 1 { rx *= lambda.squareRoot(); ry *= lambda.squareRoot() }
        let numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        let denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        let sign: CGFloat = large != sweep ? 1 : -1
        let coefficient = sign * max(0, numerator / denominator).squareRoot()
        let cxp = coefficient * (rx * y1p / ry)
        let cyp = coefficient * -(ry * x1p / rx)
        let cx = cosPhi * cxp - sinPhi * cyp + (p0.x + p1.x) / 2
        let cy = sinPhi * cxp + cosPhi * cyp + (p0.y + p1.y) / 2

        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let dot = ux * vx + uy * vy
            let length = (ux * ux + uy * uy).squareRoot() * (vx * vx + vy * vy).squareRoot()
            var a = acos(min(max(dot / length, -1), 1))
            if ux * vy - uy * vx < 0 { a = -a }
            return a
        }
        let theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if !sweep && delta > 0 { delta -= 2 * .pi }
        if sweep && delta < 0 { delta += 2 * .pi }

        let segments = max(1, Int((abs(delta) / (.pi / 2)).rounded(.up)))
        let step = delta / CGFloat(segments)
        let t = 4.0 / 3.0 * tan(step / 4)
        func point(_ theta: CGFloat) -> CGPoint {
            CGPoint(x: cx + rx * cos(theta) * cosPhi - ry * sin(theta) * sinPhi,
                    y: cy + rx * cos(theta) * sinPhi + ry * sin(theta) * cosPhi)
        }
        func derivative(_ theta: CGFloat) -> CGPoint {
            CGPoint(x: -rx * sin(theta) * cosPhi - ry * cos(theta) * sinPhi,
                    y: -rx * sin(theta) * sinPhi + ry * cos(theta) * cosPhi)
        }
        var a = theta1
        for _ in 0..<segments {
            let b = a + step
            let pa = point(a), pb = point(b), da = derivative(a), db = derivative(b)
            path.addCurve(to: pb,
                          control1: CGPoint(x: pa.x + t * da.x, y: pa.y + t * da.y),
                          control2: CGPoint(x: pb.x - t * db.x, y: pb.y - t * db.y))
            a = b
        }
    }
}

/// A path from SVG data drawn on a 24 x 24 grid, scaled to whatever frame it is given.
struct SVGShape: Shape {
    let d: String
    var grid: CGFloat = 24

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / grid
        return SVGPath.path(d).applying(CGAffineTransform(scaleX: scale, y: scale))
    }
}

/// A stroked icon by name (round caps and joins; the zero-length "dots" in some paths become round dots).
struct Glyph: View {
    let name: String
    var color: Color
    var size: CGFloat = 22
    var strokeWidth: CGFloat = 2

    var body: some View {
        SVGShape(d: GlyphData.stroked[name] ?? "")
            .stroke(color, style: StrokeStyle(lineWidth: strokeWidth * size / 24, lineCap: .round, lineJoin: .round))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// A solid shape by name: the CRT D-pad's triangles and the media keys.
struct Solid: View {
    let name: String
    var color: Color
    var size: CGFloat = 24

    var body: some View {
        SVGShape(d: GlyphData.filled[name] ?? "")
            .fill(color)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// An app's logo as a filled shape, with a faint halo when `halo` is set.
struct LogoMark: View {
    let logo: AppLogo
    var color: Color
    var halo = false

    var body: some View {
        GeometryReader { proxy in
            let size = min(proxy.size.width, proxy.size.height)
            ZStack {
                if halo {
                    SVGShape(d: logo.path)
                        .stroke(color.opacity(0.28), style: StrokeStyle(lineWidth: 1.8 * size / 24, lineJoin: .round))
                }
                SVGShape(d: logo.path).fill(color)
            }
            .frame(width: size, height: size)
            .position(x: proxy.size.width / 2, y: proxy.size.height / 2)
        }
        .accessibilityHidden(true)
    }
}
