import Foundation
import Network
import Darwin
import TVRemoteCore

struct FoundTv: Hashable {
    let name: String
    let host: String
}

/// Finds TVs and sticks on the local network: they advertise the remote service over Bonjour. Emits the
/// growing list of resolved devices; ending the iteration stops the search.
func discoverTvs() -> AsyncStream<[FoundTv]> {
    AsyncStream { continuation in
        let queue = DispatchQueue(label: "tvremote.discovery")
        let browser = NWBrowser(for: .bonjour(type: "_androidtvremote2._tcp", domain: nil), using: .tcp)
        let state = DiscoveryState()

        func publish() { continuation.yield(state.list()) }

        browser.browseResultsChangedHandler = { results, _ in
            var present = Set<String>()
            for result in results {
                guard case let .service(name, _, _, _) = result.endpoint else { continue }
                present.insert(name)
                if state.known(name) { continue }
                state.markResolving(name)
                resolve(result.endpoint, queue: queue) { host in
                    if let host { state.set(name, host) } else { state.forget(name) }
                    publish()
                }
            }
            state.keepOnly(present)
            publish()
        }
        browser.stateUpdateHandler = { newState in
            if case .failed = newState { continuation.finish() }
        }
        browser.start(queue: queue)
        continuation.onTermination = { _ in
            browser.cancel()
            state.cancelAll()
        }
    }
}

private final class DiscoveryState {
    private let lock = NSLock()
    private var hosts = [String: String]()
    private var resolving = Set<String>()

    func list() -> [FoundTv] { lock.locked { hosts.map { FoundTv(name: $0.key, host: $0.value) }.sorted { $0.name < $1.name } } }
    func known(_ name: String) -> Bool { lock.locked { hosts[name] != nil || resolving.contains(name) } }
    func markResolving(_ name: String) { lock.locked { _ = resolving.insert(name) } }
    func set(_ name: String, _ host: String) { lock.locked { hosts[name] = host; resolving.remove(name) } }
    func forget(_ name: String) { lock.locked { resolving.remove(name) } }
    func keepOnly(_ names: Set<String>) { lock.locked { hosts = hosts.filter { names.contains($0.key) } } }
    func cancelAll() { lock.locked { hosts = [:]; resolving = [] } }
}

/// Learns the IPv4 address behind a Bonjour service by opening a TCP connection to it and reading where it
/// went; then closes it again.
private func resolve(_ endpoint: NWEndpoint, queue: DispatchQueue, completion: @escaping (String?) -> Void) {
    let parameters = NWParameters.tcp
    if let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
    let connection = NWConnection(to: endpoint, using: parameters)
    let done = LockedFlag()
    func finish(_ host: String?) {
        guard done.setOnce() else { return }
        connection.cancel()
        completion(host)
    }
    connection.stateUpdateHandler = { state in
        switch state {
        case .ready:
            if case let .hostPort(host, _)? = connection.currentPath?.remoteEndpoint, case let .ipv4(address) = host {
                finish(address.debugDescription.split(separator: "%").first.map(String.init))
            } else {
                finish(nil)
            }
        case .failed, .cancelled:
            finish(nil)
        default:
            break
        }
    }
    queue.asyncAfter(deadline: .now() + 6) { finish(nil) }
    connection.start(queue: queue)
}

private final class LockedFlag {
    private let lock = NSLock()
    private var set = false
    func setOnce() -> Bool { lock.locked { if set { return false }; set = true; return true } }
}

// MARK: - The phone's own network

struct LocalNetwork {
    let address: [UInt8]
    let prefix: Int

    /// The phone's IPv4 address on Wi-Fi (or the first usable interface).
    static func current() -> LocalNetwork? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var best: LocalNetwork?
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = pointer.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET), let mask = ifa.ifa_netmask else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name.hasPrefix("en") else { continue } // Wi-Fi (en0) or a dock's ethernet; not cellular or tunnels
            let ip = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let m = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let bytes = (0..<4).map { UInt8((ip >> UInt32(24 - 8 * $0)) & 0xFF) }
            let candidate = LocalNetwork(address: bytes, prefix: m.nonzeroBitCount)
            if name == "en0" { return candidate }
            best = best ?? candidate
        }
        return best
    }

    var hostString: String { address.map(String.init).joined(separator: ".") }

    /// Every other address of the phone's /24 (a home network); a bigger network is cut down to its /24.
    func neighbours() -> [String] {
        let base = address.prefix(3).map(String.init).joined(separator: ".")
        return (1...254).map { "\(base).\($0)" }.filter { $0 != hostString }
    }

    var broadcast: String { Wol.broadcastOf(address, prefix: prefix) }
}
