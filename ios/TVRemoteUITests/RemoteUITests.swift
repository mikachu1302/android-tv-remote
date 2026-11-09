import XCTest

/// Drives the real app on the simulator in demo mode (no TV): what a swipe, a tap and a hold do on the remote.
final class RemoteUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
    }

    private func launch(style: String = "glass", extra: [String] = []) {
        app.launchArguments = ["-demo", "YES", "-demoStyle", style] + extra
        app.launch()
        XCTAssertTrue(app.buttons["key-OK"].waitForExistence(timeout: 15), "the remote did not appear:\n\(app.debugDescription)")
    }

    /// The keys the demo mode recorded, comma separated ("" when none).
    private var pressed: String { app.staticTexts["demo-keys"].label }

    /// Waits until the recorded keys equal `expected`.
    private func expectPressed(_ expected: String, _ message: String = "", timeout: TimeInterval = 3, line: UInt = #line) {
        let end = Date().addingTimeInterval(timeout)
        while pressed != expected && Date() < end { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
        XCTAssertEqual(pressed, expected, message, line: line)
    }

    func testTapOnAKeySendsItOnce() {
        launch()
        app.buttons["key-Up"].tap()
        expectPressed("19")
    }

    func testHoldingAKeySendsItOnce() {
        launch()
        app.buttons["key-OK"].press(forDuration: 0.4)
        expectPressed("23")
    }

    func testSwipingFromAKeyChangesThePageAndSendsNothing() {
        launch()
        let ok = app.buttons["key-OK"]
        let start = ok.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.02, thenDragTo: start.withOffset(CGVector(dx: -260, dy: 0)), withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(app.buttons["key-Digit 5"].waitForExistence(timeout: 5), "the number pad page is shown after the swipe")
        XCTAssertEqual(pressed, "", "swiping from OK must not press OK")
    }

    func testSwipingUpFromTheBottomRowSendsNothing() {
        launch()
        let home = app.buttons["key-Home"]
        let start = home.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.02, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -150)), withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertEqual(pressed, "", "a swipe up from the bottom row must not press a key")
        // ... and a plain tap on the same key still works
        home.tap()
        expectPressed("3")
    }

    func testNumberPadAndMoreKeysPages() {
        launch(style: "crt")
        app.buttons["dot-1"].tap()
        XCTAssertTrue(app.buttons["key-Digit 5"].waitForExistence(timeout: 5), "number pad shown")
        app.buttons["key-Digit 5"].tap()
        expectPressed("12", "digit 5")
        app.buttons["dot-2"].tap()
        XCTAssertTrue(app.buttons["key-TV guide"].waitForExistence(timeout: 5), "more keys shown")
        app.buttons["key-TV guide"].tap()
        expectPressed("12,172", "guide")
    }

    func testAppRowLaunchesAndAppShortcutsScreenOpens() {
        launch()
        app.buttons["app-Netflix"].tap()
        let end = Date().addingTimeInterval(3)
        while !pressed.hasPrefix("launch:") && Date() < end { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
        XCTAssertTrue(pressed.hasPrefix("launch:"), "pressed: \(pressed)")
        app.buttons["app-add"].tap()
        XCTAssertTrue(app.staticTexts["App shortcuts"].waitForExistence(timeout: 5))
    }

    func testNothingScrollsAndEverythingIsOnScreen() {
        launch()
        let window = app.windows.firstMatch.frame
        var problems = [String]()
        for id in ["key-Settings", "key-Volume up", "key-Volume down", "key-Home", "key-Back", "key-Play or pause", "key-Channel up", "key-Channel down", "key-OK"] {
            // The More page has keys with the same names (Settings, Menu...): the first match is the one on this page.
            let element = app.buttons.matching(identifier: id).firstMatch
            if !element.exists { problems.append("\(id) is missing"); continue }
            if !window.contains(element.frame) { problems.append("\(id) \(element.frame) is outside \(window)") }
        }
        XCTAssertTrue(problems.isEmpty, problems.joined(separator: "\n"))
    }
}
