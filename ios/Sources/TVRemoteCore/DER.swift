import Foundation

/// The little ASN.1/DER needed for the phone's identity: build a self-signed X.509 certificate around an
/// RSA key, and read the modulus and exponent out of an RSA public key.
public enum DER {
    static func length(_ n: Int) -> [UInt8] {
        if n < 0x80 { return [UInt8(n)] }
        var bytes = [UInt8]()
        var v = n
        while v > 0 { bytes.insert(UInt8(v & 0xFF), at: 0); v >>= 8 }
        return [0x80 | UInt8(bytes.count)] + bytes
    }

    static func tlv(_ tag: UInt8, _ content: [UInt8]) -> [UInt8] { [tag] + length(content.count) + content }
    static func sequence(_ parts: [UInt8]...) -> [UInt8] { tlv(0x30, parts.flatMap { $0 }) }
    static func set(_ content: [UInt8]) -> [UInt8] { tlv(0x31, content) }
    static let null: [UInt8] = [0x05, 0x00]

    /// An INTEGER from big-endian bytes (a leading zero is added when the top bit is set).
    static func integer(_ bytes: [UInt8]) -> [UInt8] {
        var b = Array(bytes.drop(while: { $0 == 0 }))
        if b.isEmpty { b = [0] }
        if b[0] & 0x80 != 0 { b.insert(0, at: 0) }
        return tlv(0x02, b)
    }

    static func oid(_ arcs: [Int]) -> [UInt8] {
        var body = [UInt8(arcs[0] * 40 + arcs[1])]
        for arc in arcs.dropFirst(2) {
            var chunk = [UInt8(arc & 0x7F)]
            var v = arc >> 7
            while v > 0 { chunk.insert(UInt8(v & 0x7F) | 0x80, at: 0); v >>= 7 }
            body += chunk
        }
        return tlv(0x06, body)
    }

    static func bitString(_ bytes: [UInt8]) -> [UInt8] { tlv(0x03, [0] + bytes) }
    static func utf8String(_ s: String) -> [UInt8] { tlv(0x0C, Array(s.utf8)) }

    /// UTCTime "YYMMDDHHMMSSZ" (valid for years 1950 to 2049).
    static func utcTime(_ date: Date) -> [UInt8] {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let text = String(format: "%02d%02d%02d%02d%02d%02dZ", c.year! % 100, c.month!, c.day!, c.hour!, c.minute!, c.second!)
        return tlv(0x17, Array(text.utf8))
    }

    private static let sha256WithRSA = oid([1, 2, 840, 113549, 1, 1, 11])
    private static let rsaEncryption = oid([1, 2, 840, 113549, 1, 1, 1])
    private static let commonName = oid([2, 5, 4, 3])

    /// The part of a certificate that gets signed: version 3, one name for issuer and subject, an RSA key.
    public static func tbsCertificate(commonName cn: String, publicKeyPKCS1: [UInt8], serial: [UInt8], notBefore: Date, notAfter: Date) -> [UInt8] {
        let name = sequence(set(sequence(commonName, utf8String(cn))))
        return sequence(
            tlv(0xA0, integer([2])), // [0] version = v3
            integer(serial),
            sequence(sha256WithRSA, null),
            name,
            sequence(utcTime(notBefore), utcTime(notAfter)),
            name,
            sequence(sequence(rsaEncryption, null), bitString(publicKeyPKCS1))
        )
    }

    /// A finished certificate: the signed part, the algorithm, and `signature` over `tbs`.
    public static func certificate(tbs: [UInt8], signature: [UInt8]) -> [UInt8] {
        sequence(tbs, sequence(sha256WithRSA, null), bitString(signature))
    }

    /// Modulus and exponent (big-endian, as stored) of an RSA public key in PKCS#1 form:
    /// SEQUENCE { INTEGER modulus, INTEGER exponent }.
    public static func parseRSAPublicKey(_ bytes: [UInt8]) -> (modulus: [UInt8], exponent: [UInt8])? {
        var pos = 0
        func readHeader(expect tag: UInt8) -> Int? {
            guard pos < bytes.count, bytes[pos] == tag else { return nil }
            pos += 1
            guard pos < bytes.count else { return nil }
            var len = Int(bytes[pos])
            pos += 1
            if len & 0x80 != 0 {
                let count = len & 0x7F
                guard count > 0, count <= 4, pos + count <= bytes.count else { return nil }
                len = 0
                for _ in 0..<count { len = len << 8 | Int(bytes[pos]); pos += 1 }
            }
            return pos + len <= bytes.count ? len : nil
        }
        func readInteger() -> [UInt8]? {
            guard let len = readHeader(expect: 0x02) else { return nil }
            defer { pos += len }
            return Array(bytes[pos..<pos + len])
        }
        guard readHeader(expect: 0x30) != nil, let n = readInteger(), let e = readInteger() else { return nil }
        return (Array(n.drop(while: { $0 == 0 })), Array(e.drop(while: { $0 == 0 })))
    }
}
