import Foundation

// OuterMessage field numbers (polo.proto).
private let fPairingRequest = 10
private let fPairingRequestAck = 11
private let fOptions = 20
private let fConfiguration = 30
private let fConfigurationAck = 31
private let fSecret = 40
private let fSecretAck = 41

private let statusOk: Int64 = 200
private let encodingHexadecimal: Int64 = 3
private let roleInput: Int64 = 1
private let codeLength: Int64 = 6

/// The messages of the pairing dialogue (the TV shows a 6-digit code at the end), built as bytes.
public enum PairingMessages {
    public static let requestAckField = fPairingRequestAck
    public static let optionsField = fOptions
    public static let configurationAckField = fConfigurationAck
    public static let secretAckField = fSecretAck
    public static let statusOK = statusOk

    private static func outer(_ build: (inout PbWriter) -> Void) -> [UInt8] {
        var w = PbWriter()
        w.varint(1, 2) // protocol_version
        w.varint(2, statusOk)
        build(&w)
        return w.toBytes()
    }

    private static func encoding(_ w: inout PbWriter, _ field: Int) {
        w.message(field) { e in
            e.varint(1, encodingHexadecimal)
            e.varint(2, codeLength)
        }
    }

    public static func pairingRequest() -> [UInt8] {
        outer { $0.message(fPairingRequest) { r in r.string(1, "atvremote"); r.string(2, "TV Remote") } }
    }

    public static func options() -> [UInt8] {
        outer { w in w.message(fOptions) { o in encoding(&o, 1); o.varint(3, roleInput) } }
    }

    public static func configuration() -> [UInt8] {
        outer { w in w.message(fConfiguration) { c in encoding(&c, 1); c.varint(2, roleInput) } }
    }

    public static func secret(_ hash: [UInt8]) -> [UInt8] {
        outer { $0.message(fSecret) { $0.bytes(1, hash) } }
    }
}

/// A big-endian unsigned number without leading zero bytes (how both sides hash the public keys).
private func unsigned(_ bytes: [UInt8]) -> [UInt8] {
    let trimmed = Array(bytes.drop(while: { $0 == 0 }))
    return trimmed.isEmpty ? [0] : trimmed
}

/// The pairing secret: SHA-256 over both public keys (unsigned big-endian) and the code without its first
/// byte, which is a checksum of the result.
public func pairingSecret(
    clientModulus: [UInt8], clientExponent: [UInt8],
    serverModulus: [UInt8], serverExponent: [UInt8],
    codeBytes: [UInt8]
) -> [UInt8] {
    SHA256.hash(
        unsigned(clientModulus) + unsigned(clientExponent) + unsigned(serverModulus) + unsigned(serverExponent)
            + Array(codeBytes.dropFirst())
    )
}

/// The 6 hex digits the TV shows as 3 bytes, or nil when the text is not such a code.
public func parsePairingCode(_ text: String) -> [UInt8]? {
    let clean = text.trimmingCharacters(in: .whitespaces)
    guard clean.count == 6, clean.allSatisfy({ $0.isHexDigit }) else { return nil }
    let chars = Array(clean)
    return stride(from: 0, to: 6, by: 2).map { UInt8(String(chars[$0...$0 + 1]), radix: 16)! }
}
