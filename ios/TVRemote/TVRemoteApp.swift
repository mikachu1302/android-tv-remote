import SwiftUI

@main
struct TVRemoteApp: App {
    @StateObject private var vm = RemoteViewModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(vm)
                .onChange(of: phase) { newPhase in
                    // Connected while the app is in front; closed in the background (the TV frees the session).
                    if newPhase == .active { vm.onForeground() } else if newPhase == .background { vm.onBackground() }
                }
                .onAppear { vm.onForeground() }
        }
    }
}
