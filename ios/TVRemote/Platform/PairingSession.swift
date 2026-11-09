import Foundation
import Security
import TVRemoteCore

/// One pairing session. `start` makes the TV show a 6-digit code; `finish` takes that code and completes the
/// pairing. On success `serverPin` holds the fingerprint of the TV's certificate, to be pinned for later
/// connections.
final class PairingSession {
    private let host: String
    private let port: UInt16
    private var connection: TLSConnection?
    private var frames = FrameBuffer()
    private(set) var serverPin: String?

    init(host: String, port: UInt16 = pairingPort) {
        self.host = host
        self.port = port
    }

    private func next(_ expect: Int) async throws -> PbMessage {
        guard let connection else { throw ConnectionError(message: "pairing not started") }
        while true {
            if let payload = try frames.next() {
                let msg = try parsePb(payload)
                if msg.long(2) != PairingMessages.statusOK { throw ConnectionError(message: "TV refused pairing (status \(msg.long(2)))") }
                guard msg.has(expect) else { throw ConnectionError(message: "unexpected message from TV") }
                return msg
            }
            frames.append(try await connection.receive())
        }
    }

    private func send(_ payload: [UInt8]) { connection?.send(frame(payload)) }

    func start() async throws {
        let conn = try TLSConnection(host: host, port: port, pin: nil)
        connection = conn
        do {
            try await conn.connect(timeout: 10)
            send(PairingMessages.pairingRequest())
            _ = try await next(PairingMessages.requestAckField)
            send(PairingMessages.options())
            _ = try await next(PairingMessages.optionsField)
            send(PairingMessages.configuration())
            _ = try await next(PairingMessages.configurationAckField)
            // The TV now shows the code. The connection stays open until finish().
        } catch {
            close()
            throw error
        }
    }

    /// Returns false when the code is wrong; throws on connection problems.
    func finish(code: String) async throws -> Bool {
        guard connection != nil else { throw ConnectionError(message: "pairing not started") }
        defer { close() }
        guard let codeBytes = parsePairingCode(code) else { return false }
        guard let serverDER = connection?.serverCertificate, let serverCert = SecCertificateCreateWithData(nil, Data(serverDER) as CFData) else {
            throw ConnectionError(message: "no certificate from the TV")
        }
        guard let client = DER.parseRSAPublicKey(try Identity.publicKeyPKCS1()),
              let server = DER.parseRSAPublicKey(try Identity.publicKeyPKCS1(of: serverCert)) else {
            throw ConnectionError(message: "could not read the public keys")
        }
        let hash = pairingSecret(clientModulus: client.modulus, clientExponent: client.exponent,
                                 serverModulus: server.modulus, serverExponent: server.exponent, codeBytes: codeBytes)
        // The first byte of the code is a checksum: catches typos before the TV does.
        if hash[0] != codeBytes[0] { return false }
        send(PairingMessages.secret(hash))
        do {
            _ = try await next(PairingMessages.secretAckField)
            serverPin = SHA256.hex([UInt8](serverDER))
            return true
        } catch {
            return false
        }
    }

    func close() {
        connection?.close()
        connection = nil
    }
}
