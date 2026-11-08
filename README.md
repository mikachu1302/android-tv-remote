# TV Remote

An ad-free remote control for Android TV devices (built for a waipu.tv 4K stick), as an
Android app. It uses the Android TV Remote protocol v2 – the same one Google's own remote
uses – over your local network.

- Finds the TV on the Wi-Fi (mDNS), pairs once with the code shown on the TV
- D-pad, OK, back, home, power (shows on/off), volume, mute, channel up/down, play/pause,
  number pad with TV and Menu keys
- Keyboard: type on the phone, it appears in the TV's text field (opens by itself when a
  text field gets focus)
- Voice search: hold the mic button
- App shortcuts row: pick apps from a list (streaming, German TV libraries, music, Kodi, VLC …),
  add the app that is open on the TV with one tap, or enter a package name
- Holding a button acts like holding it on a physical remote
- Reconnects by itself (also after the screen locked or the TV's address changed)
- Two looks (Neon CRT and Glass) and nine colour palettes, all switchable in Settings;
  the launcher icon follows the colours, or shows the Progress Pride flag
- Settings: haptics, smooth press, keep screen awake, theater mode
- No ads, no tracking, no accounts; talks only to your TV

The app pins the TV's certificate after pairing and keeps its own key in the Android
keystore.

## Build

```
./gradlew assembleDebug        # debug build, installs as "TV Remote (debug)"
./tools/release.sh             # signed release APK in build/release/ (creates a signing key on first run)
adb install -r build/release/tv-remote-0.2.0.apk
```

Requires the Android SDK (platform 37) and a JDK. See `AGENTS.md` for details.

## Credits

Logo shapes: [Simple Icons](https://simpleicons.org) (CC0); the brands are trademarks of their
owners. Fonts: VT323 and Fira Code (SIL Open Font License, see `licenses/`).
