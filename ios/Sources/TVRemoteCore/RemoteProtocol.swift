import Foundation

// RemoteMessage field numbers (remotemessage.proto).
public enum Field {
    public static let configure = 1
    public static let setActive = 2
    public static let pingRequest = 8
    public static let pingResponse = 9
    public static let keyInject = 10
    public static let imeKeyInject = 20
    public static let imeBatchEdit = 21
    public static let imeShowRequest = 22
    public static let voiceBegin = 30
    public static let voicePayload = 31
    public static let voiceEnd = 32
    public static let start = 40
    public static let setVolume = 50
    public static let appLinkLaunch = 90
}

public let remotePort: UInt16 = 6466
public let pairingPort: UInt16 = 6467

/// Feature flags exchanged in the configure message.
public enum Feature {
    public static let ping: Int64 = 1
    public static let key: Int64 = 2
    public static let ime: Int64 = 4
    public static let voice: Int64 = 8
    public static let power: Int64 = 32
    public static let volume: Int64 = 64
    public static let appLink: Int64 = 512

    /// What we ask for; the TV answers with the subset it supports.
    public static let wanted: Int64 = ping | key | ime | voice | power | volume | appLink
}

public enum Direction: Int64 {
    case short = 3
    case startLong = 1
    case endLong = 2
}

/// Android key codes the TV understands.
public enum Key {
    public static let home = 3
    public static let digit0 = 7 // digit0 + n for n in 0...9
    public static let back = 4
    public static let up = 19
    public static let down = 20
    public static let left = 21
    public static let right = 22
    public static let center = 23
    public static let volumeUp = 24
    public static let volumeDown = 25
    public static let power = 26
    public static let enter = 66
    public static let del = 67
    public static let search = 84
    public static let tv = 170
    public static let mediaNext = 87
    public static let mediaPrevious = 88
    public static let mediaRewind = 89
    public static let mediaFastForward = 90
    public static let mediaStop = 86
    public static let mediaPlay = 126
    public static let mediaPause = 127
    public static let info = 165
    public static let guide = 172
    public static let captions = 175
    public static let settings = 176
    public static let progRed = 183
    public static let progGreen = 184
    public static let progYellow = 185
    public static let progBlue = 186
    public static let sleep = 223
    public static let wakeup = 224
    public static let lastChannel = 229
    public static let teletext = 233
    public static let allApps = 284
    public static let menu = 82
    public static let playPause = 85
    public static let volumeMute = 164
    public static let channelUp = 166
    public static let channelDown = 167
}

public struct Volume: Equatable {
    public var level: Int
    public var max: Int
    public var muted: Bool
    public init(level: Int, max: Int, muted: Bool) { self.level = level; self.max = max; self.muted = muted }
}

/// The TV's focused text field, as last reported.
public struct TextFieldInfo: Equatable {
    public var value: String
    public var label: String
    public init(value: String, label: String) { self.value = value; self.label = label }
}

/// Everything the TV tells us about itself. Fields stay nil/zero until the TV reports them: a stick whose
/// volume is handled by the TV over HDMI-CEC may never send `volume`.
public struct TvState: Equatable {
    public var on: Bool?
    public var volume: Volume?
    public var appPackage: String?
    public var appLabel: String?
    public var textField: TextFieldInfo?
    /// Counts how often the TV asked for the keyboard (a text field got focus).
    public var keyboardRequests = 0
    public var features: Int64 = 0
    public var device: String?
    /// The TV opened a voice session after the Search key; `voiceBegins` counts them.
    public var voiceSession = 0
    public var voiceBegins = 0
    // Counters the TV wants echoed back with every text edit.
    public var imeCounter = 0
    public var imeFieldCounter = 0

    public init(
        on: Bool? = nil, volume: Volume? = nil, appPackage: String? = nil, appLabel: String? = nil,
        textField: TextFieldInfo? = nil, keyboardRequests: Int = 0, features: Int64 = 0, device: String? = nil,
        voiceSession: Int = 0, voiceBegins: Int = 0, imeCounter: Int = 0, imeFieldCounter: Int = 0
    ) {
        self.on = on; self.volume = volume; self.appPackage = appPackage; self.appLabel = appLabel
        self.textField = textField; self.keyboardRequests = keyboardRequests; self.features = features
        self.device = device; self.voiceSession = voiceSession; self.voiceBegins = voiceBegins
        self.imeCounter = imeCounter; self.imeFieldCounter = imeFieldCounter
    }

    /// Applies one message from the TV to the state. Pure, so it is unit-tested.
    public func applying(_ msg: PbMessage) -> TvState {
        var s = self
        func nonEmpty(_ text: String?) -> String? { (text?.isEmpty ?? true) ? nil : text }
        func nonZero(_ n: Int64?) -> Int? { (n == nil || n == 0) ? nil : Int(n!) }

        if msg.has(Field.configure) {
            let cfg = msg.message(Field.configure)
            let info = cfg?.message(2)
            s.features = cfg?.long(1) ?? 0
            let parts = [nonEmpty(info?.string(2)), nonEmpty(info?.string(1))].compactMap { $0 }
            s.device = parts.isEmpty ? nil : parts.joined(separator: " ")
        } else if msg.has(Field.start) {
            // `started` is a bool: when false the field is absent, the message still present.
            let on = msg.message(Field.start)?.long(1) == 1
            s.on = on
            // A stick that goes off forgets its app: the last one must not stay highlighted.
            if !on {
                s.appPackage = nil
                s.appLabel = nil
            }
        } else if msg.has(Field.setVolume) {
            // Some sticks send this message without a level (volume lives on the TV, over HDMI-CEC):
            // only a message with a maximum counts as a volume report.
            if let v = msg.message(Field.setVolume), v.long(6) > 0 {
                s.volume = Volume(level: Int(v.long(7)), max: Int(v.long(6)), muted: v.long(8) == 1)
            }
        } else if msg.has(Field.voiceBegin) {
            s.voiceSession = Int(msg.message(Field.voiceBegin)?.long(1) ?? 0)
            s.voiceBegins += 1
        } else if msg.has(Field.imeKeyInject) {
            let inject = msg.message(Field.imeKeyInject)
            let app = inject?.message(1)
            let field = inject?.message(2)
            s.appPackage = nonEmpty(app?.string(12)) ?? appPackage
            s.appLabel = nonEmpty(app?.string(10)) ?? appLabel
            s.imeCounter = nonZero(app?.long(1)) ?? imeCounter
            s.imeFieldCounter = nonZero(field?.long(1)) ?? imeFieldCounter
            if let field { s.textField = TextFieldInfo(value: field.string(2), label: field.string(6)) }
        } else if msg.has(Field.imeShowRequest) {
            let field = msg.message(Field.imeShowRequest)?.message(2)
            s.keyboardRequests += 1
            s.imeFieldCounter = nonZero(field?.long(1)) ?? imeFieldCounter
            if let field { s.textField = TextFieldInfo(value: field.string(2), label: field.string(6)) }
        } else if msg.has(Field.imeBatchEdit) {
            let edit = msg.message(Field.imeBatchEdit)
            if imeCounter == 0 { s.imeCounter = Int(edit?.long(1) ?? 0) }
            if imeFieldCounter == 0 { s.imeFieldCounter = Int(edit?.long(2) ?? 0) }
        }
        return s
    }
}

// MARK: - Messages we send

public enum Outgoing {
    /// Answer to the TV's configure message: the features both sides support, and who we are.
    public static func configure(active: Int64, appVersion: String, bundleId: String = "io.github.mikachu1302.tvremote") -> [UInt8] {
        var w = PbWriter()
        w.message(Field.configure) { cfg in
            cfg.varint(1, active)
            cfg.message(2) { info in
                info.string(1, "iOS") // model
                info.string(2, "TV Remote") // vendor
                info.varint(3, 1)
                info.string(4, "1")
                info.string(5, bundleId)
                info.string(6, appVersion)
            }
        }
        return w.toBytes()
    }

    public static func setActive(_ active: Int64) -> [UInt8] {
        var w = PbWriter()
        w.message(Field.setActive) { $0.varint(1, active) }
        return w.toBytes()
    }

    public static func pingResponse(_ value: Int64) -> [UInt8] {
        var w = PbWriter()
        w.message(Field.pingResponse) { $0.varint(1, value) }
        return w.toBytes()
    }

    public static func key(_ code: Int, _ direction: Direction = .short) -> [UInt8] {
        var w = PbWriter()
        w.message(Field.keyInject) { k in
            k.varint(1, Int64(code))
            k.varint(2, direction.rawValue)
        }
        return w.toBytes()
    }

    /// Inserts `text` at the cursor of the focused text field.
    public static func text(_ text: String, imeCounter: Int, imeFieldCounter: Int) -> [UInt8] {
        let length = Int64(text.utf16.count) // the cursor position counts UTF-16 units, like Android's
        var w = PbWriter()
        w.message(Field.imeBatchEdit) { edit in
            edit.varint(1, Int64(imeCounter))
            edit.varint(2, Int64(imeFieldCounter))
            edit.message(3) { one in // one edit: insert
                one.varint(1, 1)
                one.message(2) { ins in
                    ins.varint(1, length) // cursor after the inserted text
                    ins.varint(2, length)
                    ins.string(3, text)
                }
            }
        }
        return w.toBytes()
    }

    public static func voiceBegin(_ session: Int) -> [UInt8] {
        var w = PbWriter()
        w.message(Field.voiceBegin) { $0.varint(1, Int64(session)) }
        return w.toBytes()
    }

    /// 16-bit PCM, 8 kHz, mono; the TV wants chunks of at least 8 KB.
    public static func voiceChunk(_ session: Int, samples: [UInt8]) -> [UInt8] {
        var w = PbWriter()
        w.message(Field.voicePayload) { v in
            v.varint(1, Int64(session))
            v.bytes(2, samples)
        }
        return w.toBytes()
    }

    public static func voiceEnd(_ session: Int) -> [UInt8] {
        var w = PbWriter()
        w.message(Field.voiceEnd) { $0.varint(1, Int64(session)) }
        return w.toBytes()
    }

    /// Opens a deep link on the TV, e.g. an https URL an app handles.
    public static func launch(_ link: String) -> [UInt8] {
        var w = PbWriter()
        w.message(Field.appLinkLaunch) { $0.string(1, link) }
        return w.toBytes()
    }
}

// MARK: - Text edits

/// What typing in a local text field means for the TV's field: delete `deletes` characters before the
/// cursor, then insert `insert`. Only edits at the end of the text are supported (that is what typing is).
public struct TextEdit: Equatable {
    public var deletes: Int
    public var insert: String
    public init(deletes: Int, insert: String) { self.deletes = deletes; self.insert = insert }
}

public func textEdit(old: String, new: String) -> TextEdit {
    // Work on code points (Unicode scalars): never split an emoji, count deletes like Android's code points.
    let a = Array(old.unicodeScalars)
    let b = Array(new.unicodeScalars)
    var prefix = 0
    while prefix < a.count && prefix < b.count && a[prefix] == b[prefix] { prefix += 1 }
    var insert = String.UnicodeScalarView()
    insert.append(contentsOf: b[prefix...])
    return TextEdit(deletes: a.count - prefix, insert: String(insert))
}
