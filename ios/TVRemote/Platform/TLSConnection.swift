import Foundation
import Network
import Security
import TVRemoteCore

struct PinMismatchError: Error, LocalizedError {
    var errorDescription: String? { "The TV's certificate changed: pair again" }
}

struct ConnectionError: Error, LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// A TLS connection with our client certificate. The TV's certificate is self-signed, so there is no CA to
/// check; instead, once paired, we pin its fingerprint: `pin` nil accepts any certificate (pairing, which
/// records it), otherwise only that exact certificate is accepted.
final class TLSConnection {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "tvremote.tls")
    private let lock = NSLock()
    private var certificate: Data?
    private var mismatch = false

    /// DER of the TV's certificate, once the handshake got that far.
    var serverCertificate: Data? { lock.lock(); defer { lock.unlock() }; return certificate }

    init(host: String, port: UInt16, pin: String?) throws {
        let identity = try Identity.identity()
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        if let secIdentity = sec_identity_create(identity) {
            sec_protocol_options_set_local_identity(options, secIdentity)
        }
        sec_protocol_options_set_verify_block(options, { [weak self] _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate], let leaf = chain.first else {
                complete(false)
                return
            }
            let der = SecCertificateCopyData(leaf) as Data
            guard let self else { complete(false); return }
            self.lock.lock()
            self.certificate = der
            let ok = pin == nil || SHA256.hex([UInt8](der)) == pin
            if !ok { self.mismatch = true }
            self.lock.unlock()
            complete(ok)
        }, queue)

        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 10
        let parameters = NWParameters(tls: tls, tcp: tcp)
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { throw ConnectionError(message: "bad port") }
        connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: parameters)
    }

    /// Opens the connection and completes the TLS handshake.
    func connect(timeout: TimeInterval = 5) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let once = Once()
            let finish: (Result<Void, Error>) -> Void = { result in
                guard once.first() else { return }
                continuation.resume(with: result)
            }
            connection.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    finish(.success(()))
                case .failed(let error), .waiting(let error):
                    self.connection.cancel()
                    finish(.failure(self.wasPinMismatch ? PinMismatchError() : error))
                case .cancelled:
                    finish(.failure(self.wasPinMismatch ? PinMismatchError() : ConnectionError(message: "connection closed")))
                default:
                    break
                }
            }
            queue.asyncAfter(deadline: .now() + timeout) { [weak self] in
                guard let self else { return }
                self.connection.cancel()
                finish(.failure(ConnectionError(message: "The TV did not answer")))
            }
            connection.start(queue: queue)
        }
    }

    private var wasPinMismatch: Bool { lock.lock(); defer { lock.unlock() }; return mismatch }

    /// Queues `bytes` for sending; they go out in the order of the calls. A failure closes the connection.
    func send(_ bytes: [UInt8]) {
        connection.send(content: Data(bytes), completion: .contentProcessed { [weak self] error in
            if error != nil { self?.connection.cancel() }
        })
    }

    /// Waits for the next piece of data; throws when the TV closes the connection.
    func receive() async throws -> [UInt8] {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<[UInt8], Error>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, !data.isEmpty {
                    continuation.resume(returning: [UInt8](data))
                } else if isComplete {
                    continuation.resume(throwing: ConnectionError(message: "The TV closed the connection"))
                } else {
                    continuation.resume(returning: [])
                }
            }
        }
    }

    func close() { connection.cancel() }
}

/// Lets exactly one caller through (a continuation must be resumed once).
private final class Once {
    private let lock = NSLock()
    private var done = false
    func first() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}
