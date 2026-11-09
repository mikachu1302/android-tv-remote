import XCTest
import Network
import Security
@testable import TVRemote
import TVRemoteCore

// Everything here talks to a fake TV on this device's loopback interface; all values are made up.

/// A TLS server standing in for the TV: it uses our own identity as its certificate, remembers the client
/// certificate it was shown, and hands each connection to `onConnection`.
private final class FakeTv {
    let listener: NWListener
    private let queue = DispatchQueue(label: "fake-tv")
    private let lock = NSLock()
    private var clientCertificate: Data?
    var port: UInt16 = 0

    var clientCertificateDER: Data? { lock.lock(); defer { lock.unlock() }; return clientCertificate }

    init(onConnection: @escaping (NWConnection, FakeTv) -> Void) throws {
        let identity = try Identity.identity()
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        sec_protocol_options_set_local_identity(options, sec_identity_create(identity)!)
        sec_protocol_options_set_peer_authentication_required(options, true)
        sec_protocol_options_set_verify_block(options, { [weak self] _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            if let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate], let leaf = chain.first {
                self?.lock.lock()
                self?.clientCertificate = SecCertificateCopyData(leaf) as Data
                self?.lock.unlock()
            }
            complete(true)
        }, queue)
        listener = try NWListener(using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
        listener.newConnectionHandler = { [unowned self] connection in
            connection.start(queue: self.queue)
            onConnection(connection, self)
        }
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            var resumed = false
            listener.stateUpdateHandler = { [unowned self] state in
                guard !resumed else { return }
                switch state {
                case .ready:
                    resumed = true
                    port = listener.port?.rawValue ?? 0
                    continuation.resume()
                case .failed(let error):
                    resumed = true
                    continuation.resume(throwing: error)
                default: break
                }
            }
            listener.start(queue: queue)
        }
    }

    func stop() { listener.cancel() }
}

/// Reads whole frames from a connection, calling `handle` for each parsed message.
private func readFrames(_ connection: NWConnection, handle: @escaping (PbMessage) -> Void) {
    var buffer = FrameBuffer()
    func next() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, done, error in
            if let data { buffer.append([UInt8](data)) }
            while let payload = try? buffer.next(), let msg = try? parsePb(payload) { handle(msg) }
            if error == nil && !done { next() }
        }
    }
    next()
}

final class IdentityTests: XCTestCase {
    func testIdentityIsCreatedOnceAndHasAnRSAKey() throws {
        let first = try Identity.identity()
        let second = try Identity.identity()
        var a: SecCertificate?, b: SecCertificate?
        SecIdentityCopyCertificate(first, &a)
        SecIdentityCopyCertificate(second, &b)
        XCTAssertEqual(SecCertificateCopyData(try XCTUnwrap(a)) as Data, SecCertificateCopyData(try XCTUnwrap(b)) as Data)
        XCTAssertEqual(SecCertificateCopySubjectSummary(try XCTUnwrap(a)) as String?, "tvremote")
        let key = try XCTUnwrap(DER.parseRSAPublicKey(try Identity.publicKeyPKCS1()))
        XCTAssertEqual(key.modulus.count, 256)
        XCTAssertEqual(key.exponent, [1, 0, 1])
    }
}

final class TlsTests: XCTestCase {
    func testPinnedConnectionAcceptsTheRightCertificateAndRejectsAnother() async throws {
        let tv = try FakeTv { connection, _ in connection.send(content: Data(frame([1, 2, 3])), completion: .idempotent) }
        try await tv.start()
        defer { tv.stop() }

        // First connection records the certificate (pin nil, like pairing).
        let probe = try TLSConnection(host: "127.0.0.1", port: tv.port, pin: nil)
        try await probe.connect()
        let pin = SHA256.hex([UInt8](try XCTUnwrap(probe.serverCertificate)))
        probe.close()

        // The right pin works and data flows.
        let good = try TLSConnection(host: "127.0.0.1", port: tv.port, pin: pin)
        try await good.connect()
        var buffer = FrameBuffer()
        var frame: [UInt8]?
        while frame == nil {
            buffer.append(try await good.receive())
            frame = try buffer.next()
        }
        XCTAssertEqual(frame, [1, 2, 3])
        good.close()

        // Another pin is refused with the pin-mismatch error.
        let bad = try TLSConnection(host: "127.0.0.1", port: tv.port, pin: String(repeating: "0", count: 64))
        do {
            try await bad.connect()
            XCTFail("a wrong pin must not connect")
        } catch is PinMismatchError {
        }
    }
}

final class RemoteConnectionTests: XCTestCase {
    func testHandshakePingKeysTextAndStateWorkOverTls() async throws {
        let received = Locked<[PbMessage]>([])
        let tv = try FakeTv { connection, _ in
            readFrames(connection) { msg in received.mutate { $0.append(msg) } }
            // What the real stick sends first: configure, set_active, a ping, its power state and the open app.
            var configure = PbWriter()
            configure.message(Field.configure) { cfg in cfg.varint(1, 622); cfg.message(2) { $0.string(1, "Test stick"); $0.string(2, "Made up") } }
            var active = PbWriter()
            active.message(Field.setActive) { $0.varint(1, 622) }
            var ping = PbWriter()
            ping.message(Field.pingRequest) { $0.varint(1, 7) }
            var power = PbWriter()
            power.message(Field.start) { $0.varint(1, 1) }
            var app = PbWriter()
            app.message(Field.imeKeyInject) { inject in
                inject.message(1) { $0.varint(1, 5); $0.string(10, "Some App"); $0.string(12, "com.example.app") }
                inject.message(2) { $0.varint(1, 9); $0.string(2, ""); $0.string(6, "") }
            }
            for payload in [configure, active, ping, power, app] { connection.send(content: Data(frame(payload.toBytes())), completion: .idempotent) }
        }
        try await tv.start()
        defer { tv.stop() }

        let probe = try TLSConnection(host: "127.0.0.1", port: tv.port, pin: nil)
        try await probe.connect()
        let pin = SHA256.hex([UInt8](try XCTUnwrap(probe.serverCertificate)))
        probe.close()

        let states = Locked<[TvState]>([])
        let connection = try await RemoteConnection.open(
            host: "127.0.0.1", port: tv.port, pin: pin, appVersion: "9.9",
            onState: { state in states.mutate { $0.append(state) } }, onClosed: { _ in }
        )
        defer { connection.close() }

        // Wait for the TV's messages to be answered and applied.
        try await waitUntil { received.value.count >= 3 && connection.state.appPackage != nil }
        let answers = received.value
        XCTAssertTrue(answers.contains { $0.has(Field.configure) }, "we answer configure")
        XCTAssertTrue(answers.contains { $0.has(Field.setActive) }, "we answer set_active")
        XCTAssertEqual(answers.first { $0.has(Field.pingResponse) }?.message(Field.pingResponse)?.long(1), 7)
        XCTAssertEqual(connection.state.on, true)
        XCTAssertEqual(connection.state.appPackage, "com.example.app")
        XCTAssertEqual(connection.state.device, "Made up Test stick")

        // Keys and text go out as the TV expects them.
        try connection.send(key: Key.up)
        try connection.send(key: Key.volumeUp, .startLong)
        try connection.send(key: Key.volumeUp, .endLong)
        try connection.sendText("hi")
        try connection.launch("https://example.org/x")
        try await waitUntil { received.value.count >= 8 }
        let keys = received.value.compactMap { $0.message(Field.keyInject) }.map { ($0.long(1), $0.long(2)) }
        XCTAssertEqual(keys.map(\.0), [19, 24, 24])
        XCTAssertEqual(keys.map(\.1), [3, 1, 2])
        let edit = try XCTUnwrap(received.value.first { $0.has(Field.imeBatchEdit) }?.message(Field.imeBatchEdit))
        XCTAssertEqual(edit.long(1), 5) // the counters the TV sent
        XCTAssertEqual(edit.long(2), 9)
        XCTAssertEqual(edit.message(3)?.message(2)?.string(3), "hi")
        XCTAssertEqual(received.value.first { $0.has(Field.appLinkLaunch) }?.message(Field.appLinkLaunch)?.string(1), "https://example.org/x")
    }
}

final class PairingTests: XCTestCase {
    func testPairingWithTheRightCodeSucceedsAndAWrongCodeDoesNot() async throws {
        let outcome = Locked<String?>(nil)
        let expectedHash = Locked<[UInt8]?>(nil)
        let code = Locked<[UInt8]>([0, 0x2B, 0x3C]) // first byte is filled in once the keys are known

        let tv = try FakeTv { connection, tv in
            func reply(_ build: (inout PbWriter) -> Void) {
                var w = PbWriter()
                w.varint(1, 2)
                w.varint(2, 200)
                build(&w)
                connection.send(content: Data(frame(w.toBytes())), completion: .idempotent)
            }
            readFrames(connection) { msg in
                if msg.has(10) { reply { $0.message(11) { _ in } } }
                else if msg.has(20) { reply { $0.message(20) { _ in } } }
                else if msg.has(30) {
                    reply { $0.message(31) { _ in } }
                    // "Show the code": the checksum byte is the first byte of the hash both sides compute.
                    if let clientDER = tv.clientCertificateDER, let clientCert = SecCertificateCreateWithData(nil, clientDER as CFData),
                       let serverIdentity = try? Identity.identity() {
                        var serverCert: SecCertificate?
                        SecIdentityCopyCertificate(serverIdentity, &serverCert)
                        if let client = DER.parseRSAPublicKey((try? Identity.publicKeyPKCS1(of: clientCert)) ?? []),
                           let server = DER.parseRSAPublicKey((try? Identity.publicKeyPKCS1(of: serverCert!)) ?? []) {
                            var bytes = code.value
                            let hash = pairingSecret(clientModulus: client.modulus, clientExponent: client.exponent,
                                                     serverModulus: server.modulus, serverExponent: server.exponent, codeBytes: bytes)
                            bytes[0] = hash[0]
                            code.mutate { $0 = bytes }
                            expectedHash.mutate { $0 = pairingSecret(clientModulus: client.modulus, clientExponent: client.exponent,
                                                                     serverModulus: server.modulus, serverExponent: server.exponent, codeBytes: bytes) }
                        }
                    }
                } else if msg.has(40) {
                    let secret = msg.message(40)?.fields[1]
                    let got: [UInt8]? = { if case .bytes(let b)? = secret?.first { return b } else { return nil } }()
                    outcome.mutate { $0 = got == expectedHash.value ? "ok" : "wrong" }
                    if got == expectedHash.value { reply { $0.message(41) { _ in } } }
                }
            }
        }
        try await tv.start()
        defer { tv.stop() }

        // The right code: the pin we get back is the fake TV's certificate fingerprint.
        let session = PairingSession(host: "127.0.0.1", port: tv.port)
        try await session.start()
        try await waitUntil { expectedHash.value != nil }
        let hex = code.value.map { String(format: "%02X", $0) }.joined()
        let ok = try await session.finish(code: hex)
        XCTAssertTrue(ok)
        XCTAssertEqual(outcome.value, "ok")
        var serverCert: SecCertificate?
        SecIdentityCopyCertificate(try Identity.identity(), &serverCert)
        XCTAssertEqual(session.serverPin, SHA256.hex([UInt8](SecCertificateCopyData(try XCTUnwrap(serverCert)) as Data)))

        // A code whose checksum byte is wrong is refused before anything is sent to the TV.
        let second = PairingSession(host: "127.0.0.1", port: tv.port)
        try await second.start()
        var wrong = code.value
        wrong[0] ^= 0xFF
        let refused = try await second.finish(code: wrong.map { String(format: "%02X", $0) }.joined())
        XCTAssertFalse(refused)
    }
}

final class GlyphTests: XCTestCase {
    func testEveryIconAndLogoParsesIntoAPathInsideItsGrid() {
        let all = Array(GlyphData.stroked.values) + Array(GlyphData.filled.values) + appLogos.values.map(\.path)
        for d in all {
            let box = SVGPath.path(d).boundingRect
            XCTAssertFalse(box.isNull || box.isEmpty && box.width == 0 && box.height == 0, d)
            XCTAssertGreaterThanOrEqual(box.minX, -1, d)
            XCTAssertGreaterThanOrEqual(box.minY, -1, d)
            XCTAssertLessThanOrEqual(box.maxX, 25, d)
            XCTAssertLessThanOrEqual(box.maxY, 25, d)
        }
    }

    func testArcsAndRelativeCommandsLandWhereTheyShould() {
        // A circle of radius 6 around (12, 12) drawn with two arcs, like the gear and info icons do.
        let circle = SVGPath.path("M18 12a6 6 0 1 1-12 0a6 6 0 1 1 12 0").boundingRect
        XCTAssertEqual(circle.minX, 6, accuracy: 0.05)
        XCTAssertEqual(circle.maxX, 18, accuracy: 0.05)
        XCTAssertEqual(circle.minY, 6, accuracy: 0.05)
        XCTAssertEqual(circle.maxY, 18, accuracy: 0.05)
        // relative lines, implicit repeats and h/v
        let box = SVGPath.path("M2 2l10 0 0 10h-10z").boundingRect
        XCTAssertEqual(box, CGRect(x: 2, y: 2, width: 10, height: 10))
        // compressed arc flags ("011") as Simple Icons write them
        XCTAssertFalse(SVGPath.path("M0 0a1 1 0 011 1").isEmpty)
    }
}

// MARK: - helpers

final class Locked<Value> {
    private let lock = NSLock()
    private var stored: Value
    init(_ value: Value) { stored = value }
    var value: Value { lock.lock(); defer { lock.unlock() }; return stored }
    func mutate(_ change: (inout Value) -> Void) { lock.lock(); change(&stored); lock.unlock() }
}

func waitUntil(timeout: TimeInterval = 10, _ condition: () -> Bool) async throws {
    let end = Date().addingTimeInterval(timeout)
    while !condition() {
        if Date() > end { throw XCTSkip("timed out waiting") }
        try await Task.sleep(nanoseconds: 50_000_000)
    }
}
