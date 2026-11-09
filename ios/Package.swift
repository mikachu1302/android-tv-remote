// swift-tools-version:5.9
import PackageDescription

// The platform-neutral part of the iOS app: the protocol, the state, the data. No UIKit/SwiftUI/Network,
// so it also builds and tests on Linux (`swift test` in this folder).
let package = Package(
    name: "TVRemoteCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "TVRemoteCore", targets: ["TVRemoteCore"])],
    targets: [
        .target(name: "TVRemoteCore"),
        .testTarget(name: "TVRemoteCoreTests", dependencies: ["TVRemoteCore"]),
    ]
)
