import XCTest
@testable import TVRemoteCore

// Messages here are built by hand with made-up values, in the shapes the TV sends.
final class StateTests: XCTestCase {
    private func msg(_ build: (inout PbWriter) -> Void) -> PbMessage {
        var w = PbWriter()
        build(&w)
        return try! parsePb(w.toBytes())
    }

    func testStartMessageSetsPowerStateAndFalseIsAnEmptyMessage() {
        let on = TvState().applying(msg { $0.message(40) { $0.varint(1, 1) } })
        XCTAssertEqual(on.on, true)
        // proto3 omits a false bool: the message is present but empty.
        let off = on.applying(msg { $0.message(40) { _ in } })
        XCTAssertEqual(off.on, false)
    }

    func testSwitchingOffForgetsTheOpenApp() {
        let open = TvState(on: true, appPackage: "com.example.app", appLabel: "Some App")
        let off = open.applying(msg { $0.message(40) { _ in } })
        XCTAssertEqual(off.on, false)
        XCTAssertNil(off.appPackage)
        XCTAssertNil(off.appLabel)
        // switching on keeps what we know
        XCTAssertEqual(open.applying(msg { $0.message(40) { $0.varint(1, 1) } }).appPackage, "com.example.app")
    }

    func testVolumeMessageGivesLevelMaxAndMute() {
        let s = TvState().applying(msg { $0.message(50) { $0.string(3, "x"); $0.varint(6, 100); $0.varint(7, 17); $0.varint(8, 1) } })
        XCTAssertEqual(s.volume, Volume(level: 17, max: 100, muted: true))
        let unmuted = s.applying(msg { $0.message(50) { $0.varint(6, 100); $0.varint(7, 18) } })
        XCTAssertEqual(unmuted.volume, Volume(level: 18, max: 100, muted: false))
    }

    func testVolumeMessageWithoutLevelIsIgnored() {
        // What the real stick sends: a player name and flags, no maximum or level.
        let s = TvState().applying(msg { $0.message(50) { $0.varint(1, 3063); $0.varint(2, 9); $0.string(3, "player"); $0.varint(4, 1) } })
        XCTAssertNil(s.volume)
    }

    func testImeKeyInjectCarriesAppAndTextFieldAndCounters() {
        let s = TvState().applying(msg {
            $0.message(20) { inject in
                inject.message(1) { $0.varint(1, 5); $0.string(10, "Some App"); $0.string(12, "com.example.app") }
                inject.message(2) { $0.varint(1, 9); $0.string(2, "hello"); $0.string(6, "Search") }
            }
        })
        XCTAssertEqual(s.appPackage, "com.example.app")
        XCTAssertEqual(s.appLabel, "Some App")
        XCTAssertEqual(s.imeCounter, 5)
        XCTAssertEqual(s.imeFieldCounter, 9)
        XCTAssertEqual(s.textField, TextFieldInfo(value: "hello", label: "Search"))
    }

    func testShowRequestCountsKeyboardRequestsAndKeepsAppInfo() {
        let base = TvState(appPackage: "com.example.app")
        let s = base
            .applying(msg { $0.message(22) { $0.message(2) { $0.varint(1, 3); $0.string(6, "Search") } } })
            .applying(msg { $0.message(22) { $0.message(2) { $0.varint(1, 4) } } })
        XCTAssertEqual(s.keyboardRequests, 2)
        XCTAssertEqual(s.imeFieldCounter, 4)
        XCTAssertEqual(s.appPackage, "com.example.app")
    }

    func testBatchEditOnlyFillsCountersWeDontHaveYet() {
        let empty = TvState().applying(msg { $0.message(21) { $0.varint(1, 7); $0.varint(2, 8) } })
        XCTAssertEqual(empty.imeCounter, 7)
        XCTAssertEqual(empty.imeFieldCounter, 8)
        let known = TvState(imeCounter: 1, imeFieldCounter: 2).applying(msg { $0.message(21) { $0.varint(1, 7); $0.varint(2, 8) } })
        XCTAssertEqual(known.imeCounter, 1)
        XCTAssertEqual(known.imeFieldCounter, 2)
    }

    func testConfigureRecordsFeaturesAndDevice() {
        let s = TvState().applying(msg {
            $0.message(1) { cfg in cfg.varint(1, 622); cfg.message(2) { $0.string(1, "Model X"); $0.string(2, "Vendor Y") } }
        })
        XCTAssertEqual(s.features, 622)
        XCTAssertEqual(s.device, "Vendor Y Model X")
    }

    func testVoiceBeginCountsSessions() {
        let s = TvState().applying(msg { $0.message(30) { $0.varint(1, 7) } })
        XCTAssertEqual(s.voiceSession, 7)
        XCTAssertEqual(s.voiceBegins, 1)
    }

    func testUnrelatedMessagesLeaveTheStateAlone() {
        let s = TvState(on: true)
        XCTAssertEqual(s, s.applying(msg { $0.message(8) { $0.varint(1, 1) } })) // ping
    }

    func testTypingAtTheEndInsertsOrDeletes() {
        XCTAssertEqual(textEdit(old: "", new: "a"), TextEdit(deletes: 0, insert: "a"))
        XCTAssertEqual(textEdit(old: "a", new: "abc"), TextEdit(deletes: 0, insert: "bc"))
        XCTAssertEqual(textEdit(old: "abc", new: "ab"), TextEdit(deletes: 1, insert: ""))
        XCTAssertEqual(textEdit(old: "abc", new: "abc"), TextEdit(deletes: 0, insert: ""))
    }

    func testReplacingTextDeletesThenInserts() {
        // e.g. the keyboard swaps a suggestion in: "helo" -> "hello"
        XCTAssertEqual(textEdit(old: "helo", new: "hello"), TextEdit(deletes: 1, insert: "lo"))
        XCTAssertEqual(textEdit(old: "abc", new: "xyz"), TextEdit(deletes: 3, insert: "xyz"))
    }

    func testEmojiIsNeverSplit() {
        let face = "😀"
        XCTAssertEqual(textEdit(old: "a\(face)", new: "a"), TextEdit(deletes: 1, insert: ""))
        XCTAssertEqual(textEdit(old: "a\(face)", new: "a😁"), TextEdit(deletes: 1, insert: "😁"))
        XCTAssertEqual(textEdit(old: face, new: ""), TextEdit(deletes: 1, insert: ""))
    }

    func testTextMessageCountsUtf16ForTheCursor() throws {
        let m = try parsePb(Outgoing.text("a😀", imeCounter: 5, imeFieldCounter: 9))
        let edit = m.message(Field.imeBatchEdit)!
        XCTAssertEqual(edit.long(1), 5)
        XCTAssertEqual(edit.long(2), 9)
        let insert = edit.message(3)!.message(2)!
        XCTAssertEqual(insert.long(1), 3) // "a" is 1 UTF-16 unit, the emoji 2
        XCTAssertEqual(insert.string(3), "a😀")
    }
}
