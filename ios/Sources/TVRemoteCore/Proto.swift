import Foundation

/// Just enough protobuf for the Android TV Remote v2 protocol: varints and length-delimited fields.
/// Messages on the wire are prefixed with their length as a varint.
public struct PbWriter {
    private var out = [UInt8]()

    public init() {}

    private mutating func rawVarint(_ value: UInt64) {
        var v = value
        while v & ~0x7F != 0 {
            out.append(UInt8((v & 0x7F) | 0x80))
            v >>= 7
        }
        out.append(UInt8(v))
    }

    public mutating func varint(_ field: Int, _ value: Int64) {
        rawVarint(UInt64(field) << 3)
        rawVarint(UInt64(bitPattern: value))
    }

    public mutating func bytes(_ field: Int, _ value: [UInt8]) {
        rawVarint((UInt64(field) << 3) | 2)
        rawVarint(UInt64(value.count))
        out.append(contentsOf: value)
    }

    public mutating func string(_ field: Int, _ value: String) { bytes(field, Array(value.utf8)) }

    public mutating func message(_ field: Int, _ build: (inout PbWriter) -> Void) {
        var inner = PbWriter()
        build(&inner)
        bytes(field, inner.toBytes())
    }

    public func toBytes() -> [UInt8] { out }
}

public enum PbError: Error, Equatable {
    case truncated, badLength, unsupportedWireType, frameTooLarge
}

/// A parsed message: every field number maps to the values seen for it.
public struct PbMessage {
    public enum Value: Equatable {
        case varint(UInt64)
        case bytes([UInt8])
    }

    public let fields: [Int: [Value]]

    public func has(_ field: Int) -> Bool { fields[field] != nil }

    public func long(_ field: Int, default fallback: Int64 = 0) -> Int64 {
        if case .varint(let v)? = fields[field]?.first { return Int64(bitPattern: v) }
        return fallback
    }

    public func string(_ field: Int, default fallback: String = "") -> String {
        if case .bytes(let b)? = fields[field]?.first { return String(decoding: b, as: UTF8.self) }
        return fallback
    }

    public func message(_ field: Int) -> PbMessage? {
        if case .bytes(let b)? = fields[field]?.first { return try? parsePb(b) }
        return nil
    }

    /// One-line dump for debug logs: field numbers with values, nested messages in braces.
    public func describe(depth: Int = 0) -> String {
        let parts = fields.keys.sorted().map { field -> String in
            fields[field]!.map { v -> String in
                switch v {
                case .varint(let n): return "\(field)=\(n)"
                case .bytes(let b): return "\(field)=" + Self.describeBytes(b, depth: depth)
                }
            }.joined(separator: ",")
        }
        return "{" + parts.joined(separator: " ") + "}"
    }

    private static func describeBytes(_ bytes: [UInt8], depth: Int) -> String {
        if bytes.isEmpty { return "{}" }
        let text = String(decoding: bytes, as: UTF8.self)
        if text.unicodeScalars.allSatisfy({ $0.value >= 32 && $0 != "\u{FFFD}" }) { return "\"" + String(text.prefix(60)) + "\"" }
        if depth < 4, let inner = try? parsePb(bytes) { return inner.describe(depth: depth + 1) }
        return "bytes[\(bytes.count)]"
    }
}

public func parsePb(_ data: [UInt8]) throws -> PbMessage {
    var fields = [Int: [PbMessage.Value]]()
    var pos = 0

    func varint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while true {
            guard pos < data.count else { throw PbError.truncated }
            let b = data[pos]
            pos += 1
            result |= UInt64(b & 0x7F) << shift
            if b & 0x80 == 0 { return result }
            shift += 7
            guard shift < 64 else { throw PbError.badLength }
        }
    }

    while pos < data.count {
        let tag = try varint()
        let field = Int(tag >> 3)
        switch tag & 7 {
        case 0:
            fields[field, default: []].append(.varint(try varint()))
        case 1:
            guard pos + 8 <= data.count else { throw PbError.truncated }
            pos += 8
        case 2:
            let len = try varint()
            guard len <= UInt64(data.count - pos) else { throw PbError.badLength }
            fields[field, default: []].append(.bytes(Array(data[pos..<pos + Int(len)])))
            pos += Int(len)
        case 5:
            guard pos + 4 <= data.count else { throw PbError.truncated }
            pos += 4
        default:
            throw PbError.unsupportedWireType
        }
    }
    return PbMessage(fields: fields)
}

/// One length-prefixed message: the length is a bare varint (no field tag).
public func frame(_ payload: [UInt8]) -> [UInt8] {
    var v = payload.count
    var out = [UInt8]()
    while v & ~0x7F != 0 {
        out.append(UInt8((v & 0x7F) | 0x80))
        v >>= 7
    }
    out.append(UInt8(v))
    return out + payload
}

/// Collects bytes as they arrive from the socket and hands out whole frames.
public struct FrameBuffer {
    private var buffer = [UInt8]()

    public init() {}

    public mutating func append(_ bytes: [UInt8]) { buffer.append(contentsOf: bytes) }

    /// The next complete frame, or nil when more bytes are needed. Throws on a nonsense length.
    public mutating func next() throws -> [UInt8]? {
        var len = 0
        var shift = 0
        var i = 0
        while true {
            guard i < buffer.count else { return nil } // length itself incomplete
            let b = Int(buffer[i])
            i += 1
            len |= (b & 0x7F) << shift
            if b & 0x80 == 0 { break }
            shift += 7
            if shift > 28 { throw PbError.badLength }
        }
        if len < 0 || len > 1 << 20 { throw PbError.frameTooLarge }
        guard buffer.count - i >= len else { return nil }
        let payload = Array(buffer[i..<i + len])
        buffer.removeFirst(i + len)
        return payload
    }
}
