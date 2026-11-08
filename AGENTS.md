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
  Power on = Wake-on-LAN magic packet to the MAC the user types in (`Wol`); the TV must have it enabled. VIDAA's
  own remote protocol (MQTT over TLS on port 36669) needs a client certificate issued by Hisense's "RemoteCA":
  without it the TLS handshake fails, so keys, power-off and input switching are NOT available that way, and
  Hisense's key must not be copied into this repo. Bluetooth HID (phone as keyboard/remote) is the untried route
  for power-off and input.
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
- **App catalog:** every package name in `CATALOG` was checked against Google Play
  (`play.google.com/store/apps/details?id=<package>` must answer 200 with the right title)
  before adding it; don't add one from memory. The picker also offers the app that is open
  on the TV (any package works through `market://launch?id=`), so a missing app is never a dead end.
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
