import Foundation

/*
 The TV's own volume, for TVs that run a UPnP/DLNA media renderer (Hisense VIDAA does): the standard
 RenderingControl service reads and sets volume and mute with plain HTTP + SOAP, no pairing and no
 certificate. The stick cannot do this: its volume keys go to the TV over HDMI-CEC and nothing comes back.
 */

/// A TV's renderer: where its RenderingControl listens (`control` is the path of its control URL).
public struct AudioTv: Equatable, Codable {
    public var name: String
    public var host: String
    public var port: Int
    public var control: String
    /// The address for Wake-on-LAN, typed in by the user (a UPnP description does not carry it).
    public var mac: String?
    public init(name: String, host: String, port: Int, control: String, mac: String? = nil) {
        self.name = name; self.host = host; self.port = port; self.control = control; self.mac = mac
    }
}

public struct TvAudio: Equatable {
    public var level: Int
    public var muted: Bool
    public init(level: Int, muted: Bool) { self.level = level; self.muted = muted }
}

public enum Upnp {
    public static let rendering = "urn:schemas-upnp-org:service:RenderingControl:1"

    /// The port and description path Hisense VIDAA TVs use for their renderer.
    public static let vidaaPort = 38400
    public static let vidaaDescription = "/MediaServer/rendererdevicedesc.xml"

    /// A SOAP request body for `action` on instance 0 of the RenderingControl service.
    public static func envelope(_ action: String, _ args: String = "") -> String {
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
            + "<u:\(action) xmlns:u=\"\(rendering)\"><InstanceID>0</InstanceID>\(args)</u:\(action)></s:Body></s:Envelope>"
    }

    /// The text of the first `<name>…</name>` element, or nil.
    public static func tag(_ xml: String, _ name: String) -> String? {
        guard let open = xml.range(of: "<\(name)>") ?? xml.range(of: "<\(name) "), let openEnd = xml[open.lowerBound...].firstIndex(of: ">"),
              let close = xml.range(of: "</\(name)>", range: openEnd..<xml.endIndex) else { return nil }
        return xml[xml.index(after: openEnd)..<close.lowerBound]
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    /// Friendly name and RenderingControl control path from a device description, or nil without that service.
    public static func parseDescription(_ xml: String) -> (name: String, control: String)? {
        var rest = Substring(xml)
        while let start = rest.range(of: "<service>"), let end = rest.range(of: "</service>", range: start.upperBound..<rest.endIndex) {
            let service = String(rest[start.upperBound..<end.lowerBound])
            if tag(service, "serviceType") == rendering, let control = tag(service, "controlURL") {
                return (tag(xml, "friendlyName") ?? "TV", control)
            }
            rest = rest[end.upperBound...]
        }
        return nil
    }

    public static func parseVolume(_ soap: String) -> Int? { tag(soap, "CurrentVolume").flatMap { Int($0) } }

    public static func parseMute(_ soap: String) -> Bool? {
        guard let value = tag(soap, "CurrentMute") else { return nil }
        return value == "1" || value.lowercased() == "true"
    }
}

/// Wake-on-LAN: a "magic packet" the TV's network chip listens for while the TV is in standby.
public enum Wol {
    /// 6 bytes from "AA:BB:CC:DD:EE:FF" (also with dashes or spaces), or nil when it is not a MAC address.
    public static func parseMac(_ text: String) -> [UInt8]? {
        let hex = text.filter { $0 != ":" && $0 != "-" && $0 != " " }
        guard hex.count == 12, hex.allSatisfy({ $0.isHexDigit }) else { return nil }
        let chars = Array(hex)
        return stride(from: 0, to: 12, by: 2).map { UInt8(String(chars[$0...$0 + 1]), radix: 16)! }
    }

    /// 6 x 0xFF, then the address 16 times.
    public static func packet(_ mac: [UInt8]) -> [UInt8] {
        [UInt8](repeating: 0xFF, count: 6) + (0..<96).map { mac[$0 % 6] }
    }

    /// The broadcast address of the network `address` (IPv4, 4 bytes) is on, for a prefix length like 24.
    public static func broadcastOf(_ address: [UInt8], prefix: Int) -> String {
        let ip = address.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        let hostBits: UInt32 = prefix >= 32 ? 0 : (UInt32.max >> UInt32(prefix))
        let broadcast = ip | hostBits
        return (0..<4).map { String((broadcast >> UInt32(24 - 8 * $0)) & 0xFF) }.joined(separator: ".")
    }
}
