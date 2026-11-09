import XCTest
@testable import TVRemoteCore

// All values here are made up; nothing comes from a real device.
final class ProtocolTests: XCTestCase {
    func testMessageRoundTripsIncludingNestedAndLargeValues() throws {
        var w = PbWriter()
        w.varint(1, 2)
        w.varint(2, 200)
        w.message(10) { $0.string(1, "atvremote"); $0.varint(2, 1 << 40) }
        let msg = try parsePb(w.toBytes())
        XCTAssertEqual(msg.long(1), 2)
        XCTAssertEqual(msg.long(2), 200)
        XCTAssertEqual(msg.message(10)!.long(2), 1 << 40)
        XCTAssertEqual(msg.message(10)!.string(1), "atvremote")
        XCTAssertTrue(msg.has(10))
        XCTAssertFalse(msg.has(11))
        XCTAssertNil(msg.message(11))
    }

    func testKeyInjectEncodesLikeTheTvExpects() {
        // RemoteMessage { remote_key_inject { key_code = 19 (DPAD_UP), direction = 3 (SHORT) } }
        XCTAssertEqual(Outgoing.key(Key.up, .short), [0x52, 0x04, 0x08, 0x13, 0x10, 0x03])
        XCTAssertEqual(Outgoing.key(Key.volumeUp, .startLong), [0x52, 0x04, 0x08, 0x18, 0x10, 0x01])
    }

    func testFramesAreLengthPrefixedAndReadBackInOrderEvenWhenSplit() throws {
        let big = (0..<300).map { UInt8($0 & 0xFF) } // length needs a two-byte varint
        let wire = frame([1, 2, 3]) + frame(big)
        var buffer = FrameBuffer()
        buffer.append(Array(wire[0..<2])) // only part of the first frame
        XCTAssertNil(try buffer.next())
        buffer.append(Array(wire[2..<10]))
        XCTAssertEqual(try buffer.next(), [1, 2, 3])
        XCTAssertNil(try buffer.next()) // the big one is incomplete
        buffer.append(Array(wire[10...]))
        XCTAssertEqual(try buffer.next(), big)
        XCTAssertNil(try buffer.next())
    }

    func testOversizedFramesAreRejected() {
        var buffer = FrameBuffer()
        buffer.append([0xFF, 0xFF, 0xFF, 0x7F]) // length ~268 million
        XCTAssertThrowsError(try buffer.next())
    }

    func testParserRejectsTruncatedInput() {
        XCTAssertThrowsError(try parsePb([0x0A, 0x05, 0x01])) // claims 5 bytes, has 1
    }

    func testPairingSecretMatchesAnIndependentImplementation() {
        // Expected value computed with a separate Python script from the same made-up numbers
        // (the same numbers as the Android test).
        let client = [UInt8](hex: String(repeating: "c0ffee", count: 40))
        let server = [UInt8](hex: String(repeating: "8badf00d", count: 40))
        let exponent: [UInt8] = [1, 0, 1]
        let secret = pairingSecret(clientModulus: client, clientExponent: exponent, serverModulus: server, serverExponent: exponent,
                                   codeBytes: [0x1A, 0x2B, 0x3C])
        XCTAssertEqual(secret.hexString, "267d45d1e9f24b7a2a525f7a7580d9542c39267f192bf976e328375ec747d662")
    }

    func testPairingSecretIgnoresLeadingZeroBytes() {
        let high = [UInt8](repeating: 0xFF, count: 8)
        let a = pairingSecret(clientModulus: [0] + high, clientExponent: [0, 3], serverModulus: high, serverExponent: [3], codeBytes: [0, 9])
        let b = SHA256.hash(high + [3] + high + [3] + [9])
        XCTAssertEqual(a, b)
    }

    func testCodeParsing() {
        XCTAssertEqual(parsePairingCode(" 1A2b3C "), [0x1A, 0x2B, 0x3C])
        XCTAssertNil(parsePairingCode("1A2B3"))
        XCTAssertNil(parsePairingCode("1A2B3G"))
    }

    func testSha256KnownVectors() {
        XCTAssertEqual(SHA256.hex([]), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(SHA256.hex(Array("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        // two blocks, and a length that needs the padding to spill into a second block
        XCTAssertEqual(SHA256.hex(Array("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".utf8)),
                       "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
        XCTAssertEqual(SHA256.hex([UInt8](repeating: 0x61, count: 1_000_000)),
                       "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    }
}

extension Array where Element == UInt8 {
    init(hex: String) {
        let chars = [Character](hex)
        self = stride(from: 0, to: chars.count, by: 2).map { UInt8(String(chars[$0...$0 + 1]), radix: 16)! }
    }

    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
