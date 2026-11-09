import XCTest

/// Drives the real app on the simulator in demo mode (no TV): what a swipe, a tap and a hold do on the remote.
final class RemoteUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private func launch(style: String = "glass", extra: [String] = []) {
        app.launchArguments = ["-demo", "YES", "-demoStyle", style] + extra
        app.launch()
        XCTAssertTrue(app.buttons["key-OK"].waitForExistence(timeout: 10))
    }

    private var pressed: String { app.staticTexts["demo-keys"].label }

    func testTapOnAKeySendsItOnce() {
        launch()
        app.buttons["key-Up"].tap()
        XCTAssertTrue(NSPredicate(format: "label == %@", "19").evaluate(with: app.staticTexts["demo-keys"]), "pressed: \(pressed)")
    }

    func testSwipingFromAKeyChangesThePageAndSendsNothing() {
        launch()
        let ok = app.buttons["key-OK"]
        ok.press(forDuration: 0.02, thenDragTo: app.buttons["key-Left"], withVelocity: .fast, thenHoldForDuration: 0)
        // The dial's own arrows are on the same page; a real page swipe goes across the whole width:
        let start = ok.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.02, thenDragTo: start.withOffset(CGVector(dx: -260, dy: 0)), withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(app.buttons["key-Digit 5"].waitForExistence(timeout: 3), "the number pad page is shown")
        XCTAssertFalse(pressed.contains("23"), "swiping from OK must not press OK; pressed: \(pressed)")
    }

    func testNumberPadAndMoreKeysPages() {
        launch(style: "crt")
        app.buttons["dot-1"].tap()
        app.buttons["key-Digit 5"].tap()
        XCTAssertTrue(NSPredicate(format: "label == %@", "12").evaluate(with: app.staticTexts["demo-keys"]), "pressed: \(pressed)")
        app.buttons["dot-2"].tap()
        app.buttons["key-Guide"].tap()
        XCTAssertTrue(NSPredicate(format: "label == %@", "12,172").evaluate(with: app.staticTexts["demo-keys"]), "pressed: \(pressed)")
    }

    func testAppRowLaunchesAndAppShortcutsScreenOpens() {
        launch()
        app.buttons["app-Netflix"].tap()
        XCTAssertTrue(pressed.hasPrefix("launch:"), "pressed: \(pressed)")
        app.buttons["app-add"].tap()
        XCTAssertTrue(app.staticTexts["App shortcuts"].waitForExistence(timeout: 3))
    }

    func testNothingScrollsAndEverythingIsOnScreen() {
        launch()
        let window = app.windows.firstMatch.frame
        for id in ["key-Settings", "key-Volume up", "key-Volume down", "key-Home", "key-Back", "key-Play or pause", "key-Channel up", "key-Channel down"] {
            let frame = app.buttons[id].frame
            XCTAssertTrue(window.contains(frame), "\(id) \(frame) is inside \(window)")
        }
    }
}
