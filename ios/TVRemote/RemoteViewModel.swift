import Foundation
import SwiftUI
import UIKit
import AVFoundation
import TVRemoteCore

enum Link { case idle, connecting, connected, retrying, pinChanged }

enum VoiceUi { case idle, starting, listening, unavailable }

enum PairingUi: Equatable {
    case none
    case starting(FoundTv)
    case waitingForCode(FoundTv, wrongCode: Bool)
    case failed(String)
}

/// Fires once; `wait()` returns when it has (or at once if it already did).
private final class OneShot {
    private let lock = NSLock()
    private var fired = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            lock.lock()
            if fired { lock.unlock(); c.resume() } else { continuation = c; lock.unlock() }
        }
    }

    func fire() {
        lock.lock()
        fired = true
        let c = continuation
        continuation = nil
        lock.unlock()
        c?.resume()
    }
}

@MainActor
final class RemoteViewModel: ObservableObject {
    private let defaults = UserDefaults.standard
    private let demo = Demo.enabled
    private let appVersion = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0"

    /// Every paired TV; one of them is the active one the remote controls.
    @Published private(set) var tvs: [PairedTv]
    /// The active TV, nil when none is paired.
    @Published private(set) var paired: PairedTv?
    /// The user is pairing one more TV on top of the ones they have.
    @Published var addingTv = false
    @Published private(set) var link: Link = .idle
    @Published private(set) var tvState = TvState()
    @Published var pairing: PairingUi = .none
    @Published private(set) var settings: RemoteSettings
    @Published private(set) var apps: [AppShortcut]
    @Published private(set) var voice: VoiceUi = .idle
    /// The TV whose own volume the volume keys control (UPnP), and what it last reported (nil: not reachable).
    @Published private(set) var audioTv: AudioTv?
    @Published private(set) var audio: TvAudio?
    /// The keys pressed in demo mode (there is no TV): what UI tests look at.
    @Published private(set) var demoKeys: [String] = []

    private var connection: RemoteConnection?
    private var pairingSession: PairingSession?
    private var loop: Task<Void, Never>?
    private var retryRequested = false
    private var voiceHeld = false
    private var voiceStreamer: VoiceStreamer?
    private var audioLoop: Task<Void, Never>?
    private var ramp: Task<Void, Never>?
    private var lastAudioSet = Date.distantPast
    private var audioSends: Task<Void, Never> = Task {}

    init() {
        let saved = defaults.data(forKey: "settings").flatMap { try? JSONDecoder().decode(RemoteSettings.self, from: $0) }
        var settings = saved ?? RemoteSettings()
        if demo {
            if let style = Demo.style { settings.style = style }
            if let palette = Demo.palette { settings.palette = palette }
        }
        self.settings = settings
        apps = demo ? defaultApps : parseApps(defaults.string(forKey: "apps"))
        if demo {
            tvs = [Demo.tv]
            paired = Demo.tv
            tvState = Demo.state
            link = .connected
            audioTv = Demo.audioTv
            audio = TvAudio(level: 28, muted: false)
        } else {
            let list = parseTvs(defaults.string(forKey: "tvs"))
            tvs = list
            let index = defaults.integer(forKey: "activeTv")
            paired = list.indices.contains(index) ? list[index] : list.first
            audioTv = defaults.data(forKey: "audioTv").flatMap { try? JSONDecoder().decode(AudioTv.self, from: $0) }
        }
    }

    private func saveTvs(_ list: [PairedTv], active: PairedTv?) {
        tvs = list
        paired = active
        defaults.set(tvsToJson(list), forKey: "tvs")
        defaults.set(active.flatMap { list.firstIndex(of: $0) } ?? 0, forKey: "activeTv")
    }

    // MARK: - connection

    /// The app is visible: keep a connection to the paired TV, reconnecting when it drops.
    func onForeground() {
        guard !demo else { return }
        startAudio()
        guard loop == nil, paired != nil else { return }
        loop = Task { [self] in await connectionLoop() }
    }

    /// The app left the screen: close the connection (the TV frees the session; no battery use).
    func onBackground() {
        guard !demo else { return }
        stopConnection()
        stopAudio()
    }

    private func connectionLoop() async {
        var wait: UInt64 = 500
        var failures = 0
        while !Task.isCancelled {
            guard let tv = paired else { return }
            link = failures == 0 ? .connecting : .retrying
            let closed = OneShot()
            do {
                let conn = try await RemoteConnection.open(
                    host: tv.host, pin: tv.pin, appVersion: appVersion,
                    onState: { [weak self] state in Task { @MainActor in self?.tvState = state } },
                    onClosed: { _ in closed.fire() }
                )
                if Task.isCancelled { conn.close(); return }
                connection = conn
                link = .connected
                failures = 0
                wait = 500
                await closed.wait()
            } catch is CancellationError {
                return
            } catch let error as PinMismatchError {
                _ = error
                // Someone else answers on the TV's address, or the TV was reset: don't retry.
                link = .pinChanged
                loop = nil
                return
            } catch {
                failures += 1
                if failures % 2 == 0 { await rediscover(tv) } // the TV's address may have changed
            }
            if Task.isCancelled { return }
            connection = nil
            tvState = TvState()
            link = .retrying
            let deadline = Date().addingTimeInterval(Double(wait) / 1000)
            while Date() < deadline && !retryRequested && !Task.isCancelled { try? await Task.sleep(nanoseconds: 100_000_000) }
            retryRequested = false
            wait = min(wait * 2, 8000)
        }
    }

    /// Looks for the paired TV by its Bonjour name; remembers a new address.
    private func rediscover(_ tv: PairedTv) async {
        let search = Task { () -> FoundTv? in
            for await list in discoverTvs() {
                if let match = list.first(where: { $0.name == tv.name }) { return match }
            }
            return nil
        }
        let timeout = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            search.cancel()
        }
        let found = await search.value
        timeout.cancel()
        guard let found, found.host != tv.host else { return }
        var moved = tv
        moved.host = found.host
        saveTvs(tvs.map { $0.name == tv.name ? moved : $0 }, active: moved)
    }

    /// Called when the user taps the status: retry right now instead of waiting.
    func retry() {
        if demo || link == .pinChanged { return }
        retryRequested = true
        onForeground()
    }

    // MARK: - sending

    private func withConnection(_ action: (RemoteConnection) throws -> Void) {
        guard let conn = connection else {
            retry() // a press while disconnected wakes the reconnect loop
            return
        }
        try? action(conn) // a closed connection is noticed by the loop, which reconnects
    }

    func press(_ keyCode: Int, _ direction: Direction = .short) {
        if demo {
            if direction != .endLong { demoKeys.append("\(keyCode)") }
            return
        }
        if audioKey(keyCode, direction) { return }
        withConnection { try $0.send(key: keyCode, direction) }
    }

    /// The user edited the keyboard field from `old` to `new`; mirror that on the TV.
    func typed(old: String, new: String) {
        let edit = textEdit(old: old, new: new)
        if demo { demoKeys.append("text:\(edit.deletes):\(edit.insert)"); return }
        withConnection { conn in
            for _ in 0..<edit.deletes { try conn.send(key: Key.del) }
            try conn.sendText(edit.insert)
        }
    }

    func launch(_ link: String) {
        if demo { demoKeys.append("launch:\(link)"); return }
        if let code = keyCode(link) {
            press(code)
            return
        }
        if let app = dialApp(link), let tv = paired {
            // Not an app link: the stick starts these through its DIAL service (Netflix ignores app links).
            Task { await startDialApp(host: tv.host, app: app) }
            return
        }
        withConnection { try $0.launch(link) }
    }

    // MARK: - voice

    /// The mic button went down: open a voice session on the TV and stream the microphone.
    func startVoice() {
        if demo { demoKeys.append("voice"); return }
        guard let conn = connection else {
            retry()
            return
        }
        guard !voiceHeld else { return }
        voiceHeld = true
        voice = .starting
        Task { [self] in
            let granted = await requestMicrophone()
            guard granted, voiceHeld else {
                voiceHeld = false
                voice = granted ? .idle : .unavailable
                return
            }
            let before = conn.state.voiceBegins
            try? conn.send(key: Key.search) // the TV answers by opening a voice session
            var session: Int?
            for _ in 0..<125 { // up to 2.5 s
                if conn.state.voiceBegins != before { session = conn.state.voiceSession; break }
                try? await Task.sleep(nanoseconds: 20_000_000)
            }
            guard let session else {
                voiceHeld = false
                voice = .unavailable
                return
            }
            do {
                try conn.voiceBegin(session)
                let streamer = VoiceStreamer(connection: conn, session: session)
                try streamer.start()
                voiceStreamer = streamer
                voice = .listening
                if !voiceHeld { stopVoice() } // released while the session was opening
            } catch {
                voiceHeld = false
                voice = .unavailable
            }
        }
    }

    /// The mic button went up.
    func stopVoice() {
        voiceHeld = false
        voiceStreamer?.stop()
        voiceStreamer = nil
        if voice != .unavailable { voice = .idle }
    }

    private func requestMicrophone() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
    }

    // MARK: - settings and apps

    func updateSettings(_ change: (inout RemoteSettings) -> Void) {
        var next = settings
        change(&next)
        let iconChanged = next.icon != settings.icon
        settings = next
        if demo { return }
        defaults.set(try? JSONEncoder().encode(next), forKey: "settings")
        // iOS confirms every icon change with its own alert, so the icon only follows an explicit choice of icon.
        if iconChanged { LauncherIcon.apply(next.icon, palette: next.palette) }
    }

    private func saveApps(_ list: [AppShortcut]) {
        apps = list
        if !demo { defaults.set(appsToJson(list), forKey: "apps") }
    }

    func addApp(_ app: AppShortcut) { saveApps(apps + [app]) }
    func resetApps() { saveApps(defaultApps) }
    func removeApp(at index: Int) { saveApps(apps.enumerated().filter { $0.offset != index }.map(\.element)) }

    func moveApp(from index: Int, to target: Int) {
        guard apps.indices.contains(index), apps.indices.contains(target) else { return }
        var list = apps
        list.insert(list.remove(at: index), at: target)
        saveApps(list)
    }

    // MARK: - the TV's own volume and power

    /// Polls the TV's volume while the app is visible; nil while it does not answer (off, or another network).
    private func startAudio() {
        guard audioLoop == nil else { return }
        audioLoop = Task { [self] in
            while !Task.isCancelled {
                guard let tv = audioTv, settings.tvVolume else {
                    audio = nil
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    continue
                }
                var wait: UInt64 = 2_500_000_000
                do {
                    let now = try await AudioClient(tv: tv).read()
                    // A reading taken just before our own change would flicker the display back.
                    if Date().timeIntervalSince(lastAudioSet) > 1.5 { audio = now }
                } catch is CancellationError {
                    return
                } catch {
                    audio = nil
                    wait = 1_500_000_000
                }
                try? await Task.sleep(nanoseconds: wait)
            }
        }
    }

    private func stopAudio() {
        ramp?.cancel()
        audioLoop?.cancel()
        audioLoop = nil
        audio = nil
    }

    /// Volume and mute go to the TV when it answers; else they stay keys for the stick (HDMI-CEC).
    /// Returns true when the key was handled here.
    private func audioKey(_ code: Int, _ direction: Direction) -> Bool {
        guard audioTv != nil, settings.tvVolume, audio != nil else { return false }
        switch code {
        case Key.volumeUp, Key.volumeDown:
            let step = code == Key.volumeUp ? 1 : -1
            switch direction {
            case .short:
                nudgeVolume(step)
            case .startLong:
                ramp?.cancel()
                ramp = Task { [self] in
                    nudgeVolume(step)
                    try? await Task.sleep(nanoseconds: 400_000_000)
                    while !Task.isCancelled {
                        nudgeVolume(step)
                        try? await Task.sleep(nanoseconds: 120_000_000)
                    }
                }
            case .endLong:
                ramp?.cancel()
            }
        case Key.volumeMute:
            if direction != .endLong { toggleTvMute() }
        default:
            return false
        }
        return true
    }

    /// Network calls to the TV run one after the other, in the order of the key presses.
    private func enqueueAudio(_ work: @escaping () async -> Void) {
        let previous = audioSends
        audioSends = Task {
            await previous.value
            await work()
        }
    }

    private func nudgeVolume(_ step: Int) {
        guard let tv = audioTv, let current = audio else { return }
        let next = min(max(current.level + step, 0), 100)
        guard next != current.level else { return }
        lastAudioSet = Date()
        audio = TvAudio(level: next, muted: current.muted)
        enqueueAudio { [weak self] in
            do { try await AudioClient(tv: tv).setVolume(next) } catch { await MainActor.run { self?.audio = nil } }
        }
    }

    private func toggleTvMute() {
        guard let tv = audioTv, let current = audio else { return }
        lastAudioSet = Date()
        audio = TvAudio(level: current.level, muted: !current.muted)
        enqueueAudio { [weak self] in
            do { try await AudioClient(tv: tv).setMute(!current.muted) } catch { await MainActor.run { self?.audio = nil } }
        }
    }

    /// Chooses the TV for volume (and the address to wake); nil forgets it.
    func setAudioTv(_ tv: AudioTv?) {
        audioTv = tv
        audio = nil
        if demo { return }
        if let tv, let data = try? JSONEncoder().encode(tv) { defaults.set(data, forKey: "audioTv") } else { defaults.removeObject(forKey: "audioTv") }
    }

    /// Sets the TV's MAC address for Wake-on-LAN; an empty text clears it.
    func setWakeAddress(_ text: String) {
        guard var tv = audioTv else { return }
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        tv.mac = trimmed.isEmpty ? nil : trimmed
        setAudioTv(tv)
    }

    /// Switches the TV on from standby with a Wake-on-LAN packet. False when there is no address to wake.
    func wakeTv() -> Bool {
        guard let tv = audioTv, let mac = tv.mac.flatMap(Wol.parseMac) else { return false }
        if demo { demoKeys.append("wake"); return true }
        var targets = ["255.255.255.255"]
        if let network = LocalNetwork.current() { targets.append(network.broadcast) }
        targets.append(tv.host)
        let unique = Array(NSOrderedSet(array: targets)) as? [String] ?? targets
        DispatchQueue.global(qos: .userInitiated).async { WakeOnLan.send(mac: mac, targets: unique) }
        return true
    }

    // MARK: - pairing

    func startPairing(_ tv: FoundTv, wrongCode: Bool = false) {
        pairingSession?.close()
        pairing = .starting(tv)
        let session = PairingSession(host: tv.host)
        pairingSession = session
        Task { [self] in
            do {
                try await session.start()
                pairing = .waitingForCode(tv, wrongCode: wrongCode)
            } catch {
                session.close()
                pairing = .failed((error as? LocalizedError)?.errorDescription ?? "Could not reach the TV")
            }
        }
    }

    func submitCode(_ code: String) {
        guard case let .waitingForCode(tv, _) = pairing, let session = pairingSession else { return }
        Task { [self] in
            let ok = (try? await session.finish(code: code)) ?? false
            if ok, let pin = session.serverPin {
                let found = PairedTv(name: tv.name, host: tv.host, pin: pin)
                pairingSession = nil
                pairing = .none
                addingTv = false
                // Pairing a TV again replaces its entry; a new one is added and becomes active.
                stopConnection()
                saveTvs(tvs.filter { $0.name != found.name } + [found], active: found)
                onForeground()
            } else {
                // The TV closes the session after a bad code: start over, it shows a fresh one.
                session.close()
                startPairing(tv, wrongCode: true)
            }
        }
    }

    func cancelPairing() {
        pairingSession?.close()
        pairingSession = nil
        pairing = .none
    }

    /// Opens the "find a TV" screen on top of the TVs already paired.
    func beginAddTv() { addingTv = true }
    func cancelAddTv() { addingTv = false }

    /// Makes `tv` the one the remote controls.
    func selectTv(_ tv: PairedTv) {
        guard tv != paired else { return }
        stopConnection()
        saveTvs(tvs, active: tv)
        onForeground()
    }

    /// Forgets a TV. Only the TV's entry goes: settings and app shortcuts stay.
    func forgetTv(_ tv: PairedTv) {
        let rest = tvs.filter { $0.name != tv.name }
        let wasActive = paired?.name == tv.name
        if wasActive { stopConnection() }
        saveTvs(rest, active: wasActive ? rest.first : paired)
        if wasActive && !rest.isEmpty { onForeground() }
    }

    /// Closes the connection and stops reconnecting, without touching what is paired.
    private func stopConnection() {
        stopVoice()
        loop?.cancel()
        loop = nil
        connection?.close()
        connection = nil
        tvState = TvState()
        link = .idle
    }
}
