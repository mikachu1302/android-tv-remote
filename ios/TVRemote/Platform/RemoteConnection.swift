import Foundation
import TVRemoteCore

/// A live control connection to the TV. A reader task answers the TV's handshake and pings and keeps `state`
/// current; the send functions are safe to call from anywhere and queue in call order.
final class RemoteConnection {
    private let connection: TLSConnection
    private let appVersion: String
    private let lock = NSLock()
    private var closed = false
    private var currentState = TvState()
    private var active = Feature.wanted
    private var lastReceived = Date()
    private var tasks: [Task<Void, Never>] = []
    private let onState: (TvState) -> Void
    private let onClosed: (Error?) -> Void
    private let trace: ((String) -> Void)?

    var state: TvState { lock.lock(); defer { lock.unlock() }; return currentState }

    /// Connects (throws `PinMismatchError` when the TV's certificate is not the pinned one) and starts reading.
    static func open(
        host: String, port: UInt16 = remotePort, pin: String, appVersion: String,
        onState: @escaping (TvState) -> Void, onClosed: @escaping (Error?) -> Void, trace: ((String) -> Void)? = nil
    ) async throws -> RemoteConnection {
        let tls = try TLSConnection(host: host, port: port, pin: pin)
        try await tls.connect(timeout: 5)
        let conn = RemoteConnection(tls, appVersion, onState, onClosed, trace)
        conn.startReading()
        return conn
    }

    private init(_ connection: TLSConnection, _ appVersion: String, _ onState: @escaping (TvState) -> Void,
                 _ onClosed: @escaping (Error?) -> Void, _ trace: ((String) -> Void)?) {
        self.connection = connection
        self.appVersion = appVersion
        self.onState = onState
        self.onClosed = onClosed
        self.trace = trace
    }

    private func startReading() {
        let reader = Task { [weak self] in
            if let self { await self.readLoop() }
        }
        // Pings arrive every ~5 s; silence for much longer means the link is dead.
        let watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard let self, !self.isClosed else { return }
                if Date().timeIntervalSince(self.lock.locked { self.lastReceived }) > 30 { self.finish(ConnectionError(message: "The TV stopped answering")) }
            }
        }
        lock.locked { tasks = [reader, watchdog] }
    }

    private var isClosed: Bool { lock.locked { closed } }

    private func readLoop() async {
        var frames = FrameBuffer()
        do {
            while !isClosed {
                while let payload = try frames.next() {
                    let msg = try parsePb(payload)
                    lock.locked { lastReceived = Date() }
                    trace?("rx " + msg.describe())
                    handle(msg)
                }
                frames.append(try await connection.receive())
            }
        } catch {
            if !isClosed { finish(error) }
        }
    }

    private func handle(_ msg: PbMessage) {
        if msg.has(Field.configure) {
            active = Feature.wanted & (msg.message(Field.configure)?.long(1, default: Feature.wanted) ?? Feature.wanted)
            write(Outgoing.configure(active: active, appVersion: appVersion))
        } else if msg.has(Field.setActive) {
            write(Outgoing.setActive(active))
        } else if msg.has(Field.pingRequest) {
            write(Outgoing.pingResponse(msg.message(Field.pingRequest)?.long(1) ?? 0))
        }
        let next = state.applying(msg)
        if next != state {
            lock.locked { currentState = next }
            onState(next)
        }
    }

    private func write(_ payload: [UInt8]) { connection.send(frame(payload)) }

    private func send(_ payload: [UInt8]) throws {
        if isClosed { throw ConnectionError(message: "not connected") }
        write(payload)
    }

    func send(key code: Int, _ direction: Direction = .short) throws { try send(Outgoing.key(code, direction)) }

    /// Inserts `text` at the cursor of the focused text field.
    func sendText(_ text: String) throws {
        guard !text.isEmpty else { return }
        let s = state
        try send(Outgoing.text(text, imeCounter: s.imeCounter, imeFieldCounter: s.imeFieldCounter))
    }

    func voiceBegin(_ session: Int) throws { try send(Outgoing.voiceBegin(session)) }
    func voiceChunk(_ session: Int, samples: [UInt8]) throws { try send(Outgoing.voiceChunk(session, samples: samples)) }
    func voiceEnd(_ session: Int) throws { try send(Outgoing.voiceEnd(session)) }

    /// Opens a deep link on the TV, e.g. an https URL an app handles.
    func launch(_ link: String) throws { try send(Outgoing.launch(link)) }

    private func finish(_ error: Error?) {
        let wasOpen = lock.locked { () -> Bool in
            if closed { return false }
            closed = true
            return true
        }
        guard wasOpen else { return }
        connection.close()
        lock.locked { tasks }.forEach { $0.cancel() }
        onClosed(error)
    }

    func close() { finish(nil) }
}

extension NSLock {
    /// Runs `body` with the lock held (NSLock.locked exists from iOS 16 on; this keeps the older spelling working too).
    @discardableResult
    func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
