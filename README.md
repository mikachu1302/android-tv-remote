# TV Remote

An ad-free remote control for Android TV devices (built for a waipu.tv 4K stick), as an
Android app. It uses the Android TV Remote protocol v2 – the same one Google's own remote
uses – over your local network.

- Finds the TV on the Wi-Fi (mDNS), pairs once with the code shown on the TV
- D-pad, OK, back, home, power, volume, mute, channel up/down, play/pause
- Holding volume or a direction repeats it
- No ads, no tracking, no accounts; talks only to your TV

The app pins the TV's certificate after pairing and keeps its own key in the Android
keystore.

## Build

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires the Android SDK (platform 37) and a JDK. See `AGENTS.md` for details.
