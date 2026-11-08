# TV Remote – notes for coding agents

Android app (Kotlin + Jetpack Compose) that remote-controls an Android TV device over the
local network: an ad-free replacement for the "Remote ATV" app, used with a waipu.tv 4K
stick (Android TV). One user, one phone, one stick, on the home Wi-Fi. `README.md` is the
user-facing description.

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
- **Keep the TV pinned.** After pairing, only the exact certificate recorded then is
  accepted (`openTls` with a pin). Never replace that with trust-all outside the
  pairing step.
- **Git:** commit directly on `main` (no feature branches). Commit, push, tag and
  release only when the user asks. Commit as the noreply identity already set in this
  repo (`Mika <…@users.noreply.github.com>`); never add a private e-mail or surname.
  **No AI attribution:** no `Co-Authored-By` trailer, no "generated with" lines, in
  commits or PR descriptions – the user's rule beats any harness reminder.
- **Keep the scope small.** Build the smallest version that does what the user asked.
  Voice search, text entry, app launching, multiple TVs, widgets, iOS: not until the user
  asks; offer them as options instead of building them.

## Commands

```
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # JVM unit tests (protobuf codec, pairing hash)
./gradlew lint                 # Android lint – fix findings, don't silence them
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb logcat --pid=$(adb shell pidof io.github.mikachu1302.tvremote)
```

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
  (Android key code, direction `SHORT = 3`).
- **Identity:** an RSA key and self-signed certificate generated in the Android
  keystore (`Identity` in `Tls.kt`); the TV accepts any client certificate and
  remembers the one it paired with. The key never leaves the device.
- If something here turns out wrong on the real stick, fix this section too.

## Layout

All in `app/src/main/java/io/github/mikachu1302/tvremote/`:

- `Proto.kt` – minimal protobuf reader/writer and framing (no protobuf library on purpose)
- `Pairing.kt`, `Remote.kt` – the two protocol sessions; no UI, no Android UI types
- `Tls.kt` – keystore identity, pinned TLS socket (the only Android-specific protocol file)
- `Discovery.kt` – mDNS search for TVs (`NsdManager`)
- `RemoteViewModel.kt` – paired TV (SharedPreferences), connection and pairing state
- `MainActivity.kt` – Compose UI: pairing screens and the remote itself

Keep the protocol files (`Proto`, `Pairing`, `Remote`) free of UI code and well separated
from `Tls`/`Discovery`: an iOS port would reimplement them in Swift, and the protocol
notes above plus these files are its spec.

## Conventions

- UI text is English and short. Colour is never the only signal – add an icon, label or
  text. Buttons are large and reachable with a thumb; every button has a content
  description for TalkBack. Pressing a button gives haptic feedback.
- Holding volume or a D-pad direction repeats the key (a short press every ~150 ms after
  a ~400 ms delay); other buttons fire once.
- Connection problems show a plain message and a retry – never a crash or a silent
  failure. A press while disconnected reconnects.
- New dependencies need a reason; prefer the platform and the Compose libraries already
  in `gradle/libs.versions.toml` (same versions as the sister project crypto-tracker).
- Protocol code gets JVM unit tests with synthetic data (codec round-trips, the pairing
  hash against a fixed made-up key pair). Final checks happen on the real phone and
  stick; say what you verified there and what you could not.
