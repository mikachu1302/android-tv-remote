import SwiftUI
import TVRemoteCore

/// A screen title in the big bold style of the find and pairing screens.
private struct BigTitle: View {
    @Environment(\.term) private var term
    let text: String
    var body: some View { Text(text).font(term.font(28, bold: true)).foregroundColor(term.text) }
}

private struct BodyText: View {
    @Environment(\.term) private var term
    let text: String
    var color: Color?
    var body: some View { Text(text).font(term.font(16)).foregroundColor(color ?? term.text).fixedSize(horizontal: false, vertical: true) }
}

struct FindTvScreen: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let canCancel: Bool
    @State private var found = [FoundTv]()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            BigTitle(text: canCancel ? "Add a TV" : "Find your TV")
            if found.isEmpty {
                BodyText(text: "Searching on the Wi-Fi… Make sure the TV is on and on the same network.")
                BodyText(text: "The first time, iOS asks whether the app may find devices on your network: allow it.", color: term.dim)
            } else {
                BodyText(text: "Tap your TV to pair:")
                ForEach(found, id: \.self) { tv in
                    let already = vm.tvs.contains { $0.name == tv.name }
                    TButton(action: { vm.startPairing(tv) }) {
                        Text(already ? "\(tv.name) (paired – pair again)" : tv.name)
                            .frame(maxWidth: .infinity, minHeight: 40)
                    }
                    .accessibilityIdentifier("tv-" + tv.name)
                }
            }
            if canCancel { TButton(kind: .text, action: vm.cancelAddTv) { Text("Cancel") } }
            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .task {
            if Demo.enabled {
                found = [FoundTv(name: "Living room stick", host: "192.0.2.10"), FoundTv(name: "Bedroom TV", host: "192.0.2.11")]
                return
            }
            for await list in discoverTvs() { found = list }
        }
    }
}

struct PairingScreen: View {
    @EnvironmentObject private var vm: RemoteViewModel
    @Environment(\.term) private var term
    let state: PairingUi
    @State private var code = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            BigTitle(text: "Pairing")
            switch state {
            case .starting(let tv):
                BodyText(text: "Contacting \(tv.name)…")
            case .waitingForCode(_, let wrong):
                BodyText(text: "Enter the 6-character code shown on the TV.")
                if wrong { BodyText(text: "That code was wrong. The TV shows a new one.", color: term.warn) }
                TextField("Code", text: Binding(
                    get: { code },
                    set: { code = String($0.filter { $0.isLetter || $0.isNumber }.prefix(6)).uppercased() }
                ))
                .font(term.font(22))
                .foregroundColor(term.text)
                .tint(term.primary)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled(true)
                .focused($focused)
                .padding(.horizontal, 14)
                .frame(height: 52)
                .overlay(term.controlShape.strokeBorder(term.border, lineWidth: 1))
                .accessibilityIdentifier("pairing-code")
                TButton(action: { vm.submitCode(code) }) { Text("Pair").frame(maxWidth: .infinity, minHeight: 40) }
                    .disabled(code.count != 6)
            case .failed(let message):
                BodyText(text: "Pairing failed: \(message)", color: term.warn)
            case .none:
                EmptyView()
            }
            TButton(kind: .text, action: vm.cancelPairing) { Text("Cancel") }
            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear { focused = true }
    }
}
