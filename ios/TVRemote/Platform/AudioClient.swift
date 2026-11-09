import Foundation
import TVRemoteCore

/// Talks to the TV's UPnP RenderingControl (see TvAudio.swift in the core). Plain HTTP + SOAP; the app's
/// Info.plist allows cleartext to local addresses for this.
struct AudioClient {
    let tv: AudioTv

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 3
        configuration.timeoutIntervalForResource = 5
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }()

    private func call(_ action: String, _ args: String) async throws -> String {
        guard let url = URL(string: "http://\(tv.host):\(tv.port)\(tv.control)") else { throw ConnectionError(message: "bad address") }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type")
        request.setValue("\"\(Upnp.rendering)#\(action)\"", forHTTPHeaderField: "SOAPACTION")
        request.httpBody = Data(Upnp.envelope(action, args).utf8)
        let (data, response) = try await Self.session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw ConnectionError(message: "The TV answered with an error")
        }
        return String(decoding: data, as: UTF8.self)
    }

    private let master = "<Channel>Master</Channel>"

    func read() async throws -> TvAudio {
        guard let level = Upnp.parseVolume(try await call("GetVolume", master)) else { throw ConnectionError(message: "no volume in the answer") }
        let muted = Upnp.parseMute(try await call("GetMute", master)) ?? false
        return TvAudio(level: level, muted: muted)
    }

    func setVolume(_ level: Int) async throws {
        _ = try await call("SetVolume", "\(master)<DesiredVolume>\(min(max(level, 0), 100))</DesiredVolume>")
    }

    func setMute(_ on: Bool) async throws {
        _ = try await call("SetMute", "\(master)<DesiredMute>\(on ? 1 : 0)</DesiredMute>")
    }
}

/// Looks for TVs with a UPnP renderer on the phone's /24 network by asking each address for the Hisense VIDAA
/// renderer description (an SSDP search would need Apple's multicast entitlement, which free accounts lack).
/// Runs only when the user asks (the TV volume settings).
func discoverAudioTvs() async -> [AudioTv] {
    guard let network = LocalNetwork.current() else { return [] }
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 1.2
    configuration.timeoutIntervalForResource = 2
    configuration.waitsForConnectivity = false
    let session = URLSession(configuration: configuration)

    func describe(_ host: String) async -> AudioTv? {
        guard let url = URL(string: "http://\(host):\(Upnp.vidaaPort)\(Upnp.vidaaDescription)"),
              let (data, response) = try? await session.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let parsed = Upnp.parseDescription(String(decoding: data, as: UTF8.self)) else { return nil }
        return AudioTv(name: parsed.name, host: host, port: Upnp.vidaaPort, control: parsed.control)
    }

    let hosts = network.neighbours()
    var found = [AudioTv]()
    // 48 at a time: enough to sweep a /24 in a few seconds without flooding the Wi-Fi.
    for start in stride(from: 0, to: hosts.count, by: 48) {
        let batch = hosts[start..<min(start + 48, hosts.count)]
        await withTaskGroup(of: AudioTv?.self) { group in
            for host in batch { group.addTask { await describe(host) } }
            for await tv in group { if let tv { found.append(tv) } }
        }
    }
    return found.sorted { $0.host < $1.host }
}
