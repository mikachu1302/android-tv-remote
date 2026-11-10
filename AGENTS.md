# TV Remote – notes for coding agents

Android app (Kotlin + Jetpack Compose) that remote-controls an Android TV device over the
local network: an ad-free replacement for the "Remote ATV" app with the same features
(the goal is full parity, minus the ads), used with a waipu.tv 4K stick (Android TV).
One user, one phone, one stick, on the home Wi-Fi. `README.md` is the user-facing
description.

## Hard rules

- **No personal information in the repo (it is public):** device names, IP/MAC/Bluetooth
  addresses, certificate fingerprints, pairing codes, names, e-mail addresses, local
  paths. Tests and fixtures use made-up values (say so in a comment); the real stick's
  address is only ever typed into the app or an adb session, never into a file.
- **No ads, no tracking, no analytics, no crash reporters, no internet calls.** The app
  talks only to the paired TV on the LAN. (`INTERNET` is in the manifest only because
  Android requires it for any socket, including local ones – don't add network code that
  leaves the LAN.)
- **Pairing and discovery run only on a user action** (the "Find TV" / pairing screens).
  Connecting to the already-paired TV when the app opens is fine; scanning the network
  in the background is not.
- **The microphone is open only while the mic button is held**, and the audio goes to the
  TV's voice search and nowhere else (never stored, never sent anywhere else).
- **Keep the TV pinned.** After pairing, only the exact certificate recorded then is
  accepted (`openTls` with a pin). Never replace that with trust-all outside the
  pairing step.
- **Git:** commit directly on `main` (no feature branches). Commit, push, tag and
  release only when the user asks. Commit as the noreply identity already set in this
  repo (`Mika <…@users.noreply.github.com>`); never add a private e-mail or surname.
  **No AI attribution:** no `Co-Authored-By` trailer, no "generated with" lines, in
  commits or PR descriptions – the user's rule beats any harness reminder.

## Commands

```
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # JVM unit tests (protobuf codec, pairing hash)
./gradlew lint                 # Android lint – fix findings, don't silence them
python3 tools/gen_launcher_icons.py   # regenerate launcher icons + manifest aliases after a palette change
./tools/release.sh             # signed release APK: build/release/tv-remote-<version>.apk
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb logcat --pid=$(adb shell pidof io.github.mikachu1302.tvremote.debug)
```

The debug build has its own app ID (`io.github.mikachu1302.tvremote.debug`, label "TV Remote
(debug)") and installs next to the release build, which keeps `io.github.mikachu1302.tvremote`.
Launch the debug one with `adb shell am start -n io.github.mikachu1302.tvremote.debug/io.github.mikachu1302.tvremote.MainActivity`.
Each build has its own pairing and settings.

**Releasing:** bump `versionName`/`versionCode` in `app/build.gradle.kts`, run `./tools/release.sh`, install
with `adb install -r` (updates keep the pairing and settings). The signing key is created on the first run
in `~/.config/tv-remote/` (outside the repo, never commit it, back it up: with a different key an installed
release can only be replaced after uninstalling it, which loses the pairing). Tag and publish a GitHub
release only when the user asks.

`adb` is not on `PATH`. The phone is reached through wireless debugging
(`adb devices` shows it; if it is missing, ask the user to re-enable wireless debugging).
Run `./gradlew test lint` before every commit.

## How the TV talks (Android TV Remote protocol v2)

The same protocol Google's own remote uses. Reference: `tronikos/androidtvremote2`
(`polo.proto` for pairing, `remotemessage.proto` for control). The stick advertises
`_androidtvremote2._tcp` over mDNS.

- **Pairing, port 6467, TLS** with our client certificate. Messages are length-prefixed
  (varint) protobuf `OuterMessage`, `protocol_version = 2`, `status = 200`:
  pairing request → ack → options → options → configuration → ack, then the TV shows a
  6-digit hex code. Secret = SHA-256 over client modulus, client exponent, server
  modulus, server exponent (unsigned big-endian, no leading zero byte) and the code
  *without its first byte*; the first code byte must equal the first hash byte (typo
  check). Send the hash as `Secret`; `SecretAck` means paired.
- **Control, port 6466, TLS**, same framing, `RemoteMessage`. The TV sends configure → we
  answer with the features we offer; set_active → we answer; ping request every ~5 s → we
  must answer with the same value or the TV drops us. Keys are `remote_key_inject`
  (Android key code; direction `SHORT = 3`, or `START_LONG = 1` / `END_LONG = 2` for a
  held key – "smooth press").
- **What the TV tells us** (`TvState`, applied by the pure function `TvState.apply`):
  `remote_start` (field 40) = power state, `remote_set_volume_level` (50) = volume,
  `remote_ime_key_inject` (20) = current app package and the focused text field,
  `remote_ime_show_request` (22) = a text field got focus (we open the keyboard bar),
  `remote_voice_begin` (30) = voice session id after we send the Search key.
- **What we send besides keys:** text = `remote_ime_batch_edit` (21) with the counters from
  the last ime message (backspace and Enter are plain DEL/ENTER keys); app launch =
  `remote_app_link_launch_request` (90) with a deep link, or `market://launch?id=<package>`
  for any installed app; voice = after Search key and the TV's voice_begin, echo it, stream
  16-bit 8 kHz mono PCM in chunks of at least 8 KB (`voice_payload` 31), end with
  `voice_end` (32).
- **Observed on the real waipu.tv 4K stick:** supports all features (code 639). It reports
  power and the current app, **but not its volume**: the volume message has no maximum or
  level and nothing arrives when volume keys are pressed (volume lives on the TV over
  HDMI-CEC). So the app shows a level or mute state only when a device reports a maximum.
  Remote ATV shows "VOL 0" for the same reason.
- **The TV itself (Hisense VIDAA, found by probing the real one):** the stick's volume keys only reach the TV over
  HDMI-CEC, which is unreliable and reports nothing back. VIDAA TVs run a UPnP/DLNA media renderer (port 38400,
  SSDP, description at `/MediaServer/rendererdevicedesc.xml`): its standard `RenderingControl` service answers
  `GetVolume`/`GetMute` and accepts `SetVolume`/`SetMute` (0-100) over plain HTTP + SOAP, no pairing (`TvAudio.kt`:
  `AudioClient`, `Upnp`; SSDP can miss the TV, so `discoverAudioTvs` also probes the phone's /24 for the port).
  Power on = Wake-on-LAN magic packet to the MAC the user types in (`Wol`; the TV's Wi-Fi/network MAC, from the
  neighbour table or the TV's network info); the TV must have wake-on-LAN/Wi-Fi enabled. **It only wakes the TV from real
  standby** (verified with a laptop: the TV was silent on the network, the packet brought it up in about 6 s). Right after the
  user powers off with the original remote the TV is in quick-start standby (screen dark, UPnP and port 36669 still answer)
  and ignores the packet; a few minutes later it drops into real standby. VIDAA's
  own remote protocol (MQTT over TLS on port 36669) needs a client certificate issued by Hisense's "RemoteCA":
  without it the TLS handshake fails, so keys, power-off and input switching are NOT available that way, and
  Hisense's key must not be copied into this repo. **Tried and dropped: the phone as a Bluetooth HID remote** (LE HID over
  GATT and classic `BluetoothHidDevice`, both worked on the phone and were visible to a laptop scan), but the TV's Bluetooth
  device list never showed the phone (it listed the waipu stick, an audio adapter and its own remote): it filters on something
  an Android app cannot set (device class / GAP appearance). Power-off and input switching therefore need a Wi-Fi IR bridge
  (Broadlink-style) or the Hisense app.
- **Connection life:** the app connects when it is visible and closes the connection in the
  background; the loop retries with backoff (0.5 s … 8 s), re-finds the TV by its mDNS
  name when its address changed, and stops with a message if the pinned certificate no
  longer matches.
- **Identity:** an RSA key and self-signed certificate generated in the Android
  keystore (`Identity` in `Tls.kt`); the TV accepts any client certificate and
  remembers the one it paired with. The key never leaves the device.
- If something here turns out wrong on the real stick, fix this section too.

## Layout

All in `app/src/main/java/io/github/mikachu1302/tvremote/`:

- `Proto.kt` – minimal protobuf reader/writer and framing (no protobuf library on purpose)
- `Pairing.kt`, `Remote.kt`, `Voice.kt` – the protocol sessions and state; `Remote.kt` has the
  pure `TvState.apply` and `textEdit`; no UI types (`Voice.kt` needs `AudioRecord`)
- `Tls.kt` – keystore identity, pinned TLS socket (the only Android-specific protocol file)
- `Discovery.kt` – mDNS search for TVs (`NsdManager`), SSDP + subnet search for UPnP renderers
- `TvAudio.kt` – the TV's own volume (UPnP RenderingControl, plain-socket HTTP) and Wake-on-LAN
- `Prefs.kt` – settings, app-shortcut storage (SharedPreferences, org.json) and the `CATALOG` of apps
  the picker offers
- `Theme.kt` – the two styles (Neon CRT, Glass), the palettes, fonts, glow/pane helpers, themed buttons
- `Controls.kt` – `RemoteButton` (one button, drawn per style) and `AppTile` (logo or initials)
- `Icons.kt` – drawn icons from SVG path data; `AppLogos.kt` – app logo shapes (generated, see below)
- `LauncherIcon.kt` – switches the launcher icon alias to match the palette or Pride
- `RemoteViewModel.kt` – paired TV, connection loop, TV state, voice, settings, pairing
- `MainActivity.kt` – activity (theme, keep-awake, theater mode), screen router, find/pair screens
- `RemoteScreen.kt` – the remote (app row, a swipeable pager of D-pad / number pad / more keys with a dot indicator, buttons, keyboard bar, drawn icons)
- `SettingsScreens.kt` – settings, and the one app-shortcuts screen (your apps with drag-to-reorder and
  Remove, the apps still to add, add by package name)

Keep the protocol files (`Proto`, `Pairing`, `Remote`) free of UI code and well separated
from `Tls`/`Discovery`: an iOS port would reimplement them in Swift, and the protocol
notes above plus these files are its spec.

## Appearance

- **Two styles, one layout.** Neon CRT: square keys, glow, scanlines, text labels, VT323.
  Glass: round keys, soft glow, circular dial, icons, Fira Code. The user picks style and palette
  in Settings; everything reads `LocalTerm` (style + palette), never hard-coded colours. CRT text is
  scaled up through the density's font scale (VT323 is drawn small), so write sizes as plain `sp`.
- **Palettes** live in `Palettes` (`Theme.kt`): `primary` = alive (on, connected, plus/minus),
  `action` = the action (OK, the app that is open, the mic), `soft` = quiet third colour. `PaletteTest`
  enforces contrast (text 7:1, other text 4.5:1, borders 3:1): a new palette must pass it. A palette may
  carry `stripes` (Pride): shown as dots in its chip and as the dial ring in the glass style.
- **Launcher icons** are generated, one per palette plus five Progress Pride ones (`pride_bezel`,
  `pride_light`, `pride_rainbow`, `pride_night`, `pride_big`; `IconChoice` in `Prefs.kt`), behind manifest aliases:
  after changing a palette run `python3 tools/gen_launcher_icons.py` (`LauncherResourcesTest` fails
  when they are out of date). Android cannot recolour an icon at runtime, hence the aliases.
- **App logos:** `AppLogos.kt` holds shapes from the Simple Icons collection (CC0, simpleicons.org);
  the brands are trademarks of their owners and the shapes only say which app a tile opens. Apps
  without a logo get an initials tile. Don't draw imitation logos.
- **Fonts:** VT323 and Fira Code (SIL OFL, licences in `licenses/`).
- Lists never change on a row tap: only explicit buttons (Add, Remove, the drag handle) do. Apps already in the
  list are not offered again under "Add apps". Reordering is by drag handle, plus "Move up/down" accessibility actions.
- Long explanations go behind a small "i" (`InfoButton`), not into the list: settings titles stay
  on one line. Cards side by side have equal height (`IntrinsicSize.Max` + `fillMaxHeight`).

## iOS app (`ios/`)

A SwiftUI port with the same features and both styles, for an iPhone 13 mini (360 x 780 pt) and up. Nothing here
can be built on Linux except the core package, so **GitHub Actions on a macOS runner is the build machine**
(`.github/workflows/ios.yml`, branch `ios`): it runs the core tests, generates the Xcode project, runs the app tests
and UI tests on an iPhone 13 mini simulator, takes screenshots of every screen (demo mode, see below) and builds an
unsigned IPA. Artifacts: `screenshots`, `test-results`, `ipa-unsigned`. Look at the screenshots after every UI change
(`gh run download <id>`).

- `ios/Sources/TVRemoteCore` – platform-neutral Swift package: protobuf, TV state, messages, pairing secret, SHA-256,
  DER/X.509, models, palettes, more keys, UPnP/Wake-on-LAN helpers. Tests in `ios/Tests` run on Linux with a Swift
  toolchain (`swift test` in `ios/`; a toolchain from swift.org unpacked in `~/.local/swift` works without root).
  `GeneratedData.swift` (icons, logos, palettes, app catalog) comes from the Android sources:
  `python3 tools/port_data_to_swift.py` after changing any of them.
- `ios/TVRemote` – the app (SwiftUI, iOS 16+). `Platform/` has the Apple-only networking: `Identity` (RSA key in the
  Keychain, self-signed certificate built with `DER`, since iOS cannot create certificates), `TLSConnection`
  (Network.framework, client identity, certificate pinned in a verify block), `PairingSession`, `RemoteConnection`,
  `Discovery` (Bonjour), `AudioClient` (UPnP), `WakeOnLan`, `VoiceStreamer` (AVAudioEngine to 8 kHz PCM).
  `RemoteViewModel` mirrors the Android view model. `Theme`, `Controls` (the button with the same press / hold / swipe
  rules), `RemoteScreen`, `SettingsScreen`, `AppShortcutsScreen`, `Screens` (find, pair) mirror the Android screens.
- The project file is generated from `ios/project.yml` by XcodeGen (never committed).
- **Demo mode** for screenshots and UI tests: launch arguments `-demo YES -demoScreen remote|settings|apps|find|pairing|keyboard
  -demoStyle crt|glass -demoPalette <id> -demoPage 0..2 -demoStickOn NO -demoScroll colors|icons|volume`. It uses a made-up
  TV, no network, and records pressed keys in an invisible `demo-keys` element that `ios/TVRemoteUITests` reads.
- App icons: `python3 tools/gen_ios_icons.py` draws all 16 (one per palette, five Pride) and their previews into the asset
  catalog from the same geometry as the Android icons; alternate icon names go into `ASSETCATALOG_COMPILER_ALTERNATE_APPICON_NAMES`
  in `project.yml` (listed in `ios/alternate-icons.txt`). iOS shows its own alert on every icon change, so the icon only
  changes when the user picks an icon, not on every palette change.
- iOS limits worth knowing: the first connection triggers the system's local-network prompt (`NSLocalNetworkUsageDescription`,
  `NSBonjourServices`); plain HTTP to the TV's UPnP service needs `NSAllowsLocalNetworking`; SSDP and Wake-on-LAN broadcasts need
  Apple's multicast entitlement, which free accounts cannot get, so the TV is found by probing the /24 and the wake packet also
  goes to the TV's own address.
- **Verified in CI:** protocol and state (shared tests), the Keychain identity, the pinned TLS connection, the control
  connection and the whole pairing dialogue against a fake TV on loopback, the SVG parser, and tap / hold / swipe behaviour
  on the simulator. **Not verified:** anything against a real TV or stick, a real device (Keychain with a real provisioning
  profile, the local-network prompt, the microphone, haptics).
- Installing on a phone needs signing. The IPA from CI is unsigned: sideload it with a tool that signs with a free Apple ID
  (AltStore / SideStore; the app then expires after 7 days and must be re-signed), or sign it with a paid developer account
  and use TestFlight.

## Conventions

- UI text is English and short. Colour is never the only signal – add an icon, label or
  text. Buttons are large and reachable with a thumb; every button has a content
  description for TalkBack. Pressing a button gives haptic feedback.
- **The remote fits the screen without scrolling:** the pad pager takes the height the rest leaves (pads scale to it);
  only a window under 640 dp falls back to scrolling. Buttons on the pager's pages and in the bottom row (`LocalSwipeable`) wait ~100 ms before
  sending, so a swipe that starts on a key (to the next page, or the system home gesture from the bottom edge, which
  the app only learns about after the touch-down) sends nothing; a quick tap goes out as one short press. The bottom
  row also keeps 24 dp free below it: the home-gesture area reaches higher than the system bar's inset.
- Holding volume, channel or a D-pad direction holds the key on the TV (`START_LONG` on
  press, `END_LONG` on release – never leave a key held: the release runs in a `finally`);
  with "smooth press" off it repeats a short press (~150 ms after ~400 ms). Other buttons
  fire once.
- **How shortcuts open apps on the waipu stick (tested on the real stick, from the phone app):** the stick ignores a package
  name in the "launch" forms: `market://launch?id=<package>`, `android-app://<package>/` and a full `intent://#Intent;package=...;
  category=android.intent.category.LEANBACK_LAUNCHER;end` sent as `remote_app_link_launch_request` all did nothing, even for Netflix,
  which is installed. What works: (1) a **web link the TV app registers** (YouTube, Prime Video, Disney+, ZDF `https://www.zdf.de`,
  ARD Mediathek `https://www.ardmediathek.de`); (2) the stick's **DIAL service** for Netflix (`dial://Netflix` = `POST
  http://<stick>:8008/apps/Netflix`, no pairing; `GET` on the same URL shows the state and the run id, `DELETE .../<run id>` stops
  it); (3) a **key press** (`key://3` = Home, which on a waipu stick is the waipu.tv app); (4) for every other app the
  **Play Store page** of its package, `https://play.google.com/store/apps/details?id=<package>`: the stick opens the store page and
  the user presses OK on "Open" (verified with Twitch: the status chip shows "Vending", after OK it shows "Twitch"). That last one
  is what the old Remote ATV app does (its strings hold the `.../store/apps/details?id=` prefix, plus per-app deep links such as
  `disneyplus://www.disneyplus.com`, `https://app.primevideo.com`, `crunchyroll://`, and a package list to recognise the open app).
  A web link that the TV app does not handle (or an app that is not installed) does nothing. `CATALOG_APPS` in `Prefs.kt` has
  name, package and link per app (no link = the Play Store page); the Swift core is generated from it
  (`tools/port_data_to_swift.py`). Saved shortcuts with an old `market://launch?id=` link are upgraded when the list loads
  (`currentLink`): to the catalog link of that app, else to the package's Play Store page.
- **App catalog:** the package of each app was checked against Google Play (`play.google.com/store/apps/details?id=<package>`
  must answer 200 with the right title) and is used to recognise the open app and to build its Play Store link. A direct link
  (web, DIAL, key) goes in only after it opened the app on the real stick; watch the status chip, which shows the open app.
  Don't add an app from memory.
- Art in launcher icons must stay inside the part the launcher shows (the generator scales it to ~0.72;
  `LauncherResourcesTest` checks). Settings previews draw the real adaptive icon through the device's mask.
- Icons are drawn from path data (`Icons.kt`): fonts on phones lack many symbols (⏻ and ✓ showed as
  boxes or odd glyphs).
- Connection problems show a plain message and a retry – never a crash or a silent
  failure. A press while disconnected reconnects.
- New dependencies need a reason; prefer the platform and the Compose libraries already
  in `gradle/libs.versions.toml` (same versions as the sister project crypto-tracker).
- Protocol and state code gets JVM unit tests with synthetic data (codec round-trips, the pairing
  hash against a fixed made-up key pair). Final checks happen on the real phone and
  stick; say what you verified there and what you could not.
