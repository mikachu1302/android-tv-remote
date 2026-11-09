import Foundation
import Darwin
import TVRemoteCore

/// Sends the Wake-on-LAN magic packet. iOS only lets apps with Apple's multicast entitlement broadcast, which a
/// free developer account cannot get, so the broadcasts may be dropped; the packet to the TV's own address
/// is the one that can get through.
enum WakeOnLan {
    /// Sends the packet three times to every target on ports 9 and 7. Blocking: call it off the main thread.
    static func send(mac: [UInt8], targets: [String]) {
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { return }
        defer { close(fd) }
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size))
        let packet = Wol.packet(mac)
        for round in 0..<3 {
            for target in targets {
                for port in [9, 7] {
                    var address = sockaddr_in()
                    address.sin_family = sa_family_t(AF_INET)
                    address.sin_port = in_port_t(port).bigEndian
                    guard inet_pton(AF_INET, target, &address.sin_addr) == 1 else { continue }
                    _ = withUnsafePointer(to: &address) { pointer in
                        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            sendto(fd, packet, packet.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                }
            }
            if round < 2 { usleep(500_000) }
        }
    }
}
