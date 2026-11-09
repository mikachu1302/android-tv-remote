# TV Remote

An ad-free remote control for Android TV devices (built for a waipu.tv 4K stick), as an
Android app and as an iPhone app (`ios/`). It uses the Android TV Remote protocol v2 – the same one Google's own remote
uses – over your local network.

- Finds the TV on the Wi-Fi (mDNS), pairs once with the code shown on the TV
- D-pad, OK, back, home, power (shows on/off), volume, mute, channel up/down, play/pause,
  number pad with TV and Menu keys
- Keyboard: type on the phone, it appears in the TV's text field (opens by itself when a
  text field gets focus)
- Voice search: hold the mic button
- App shortcuts row: the last tile (+) adds apps from a list (streaming, German TV libraries, music, Kodi, VLC …),
  the app that is open on the TV, or any package name; drag to reorder, tap Remove to take one out
- Holding a button acts like holding it on a physical remote
- Reconnects by itself (also after the screen locked or the TV's address changed)
- Two looks (Neon CRT and Glass) and ten colour palettes (one inspired by the Progress Pride flag),
  all switchable in Settings; the launcher icon follows the colours, or is one of five Pride icons
- Settings: haptics, smooth press, keep screen awake, theater mode
- TV volume: on a Hisense VIDAA TV the volume keys control the TV itself (UPnP) and show its level; a Wake-on-LAN key
  switches the TV on from standby
- No ads, no tracking, no accounts; talks only to your TV

The app pins the TV's certificate after pairing and keeps its own key in the Android
keystore.

## Build

```
./gradlew assembleDebug        # debug build, installs as "TV Remote (debug)"
./tools/release.sh             # signed release APK in build/release/ (creates a signing key on first run)
adb install -r build/release/tv-remote-0.3.0.apk
```

Requires the Android SDK (platform 37) and a JDK. See `AGENTS.md` for details.

### iPhone

The iPhone app (SwiftUI, iOS 16 or newer, laid out for small screens like the iPhone 13 mini) is built by GitHub Actions
on a Mac (`.github/workflows/ios.yml`): the run's `ipa-unsigned` artifact is an unsigned IPA. Install it with a tool that
signs it with your Apple ID (for example AltStore or SideStore; a free Apple ID needs a re-sign every 7 days), or sign it
with a paid developer account and distribute it through TestFlight.
Step-by-step instructions in German for AltStore on a Mac: `docs/iPhone-Installation.md`. The protocol and screens are tested in CI against a
fake TV; see `AGENTS.md` for what is and is not verified.

## Credits

Logo shapes: [Simple Icons](https://simpleicons.org) (CC0); the brands are trademarks of their
owners. Fonts: VT323 and Fira Code (SIL Open Font License, see `licenses/`).
