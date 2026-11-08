#!/usr/bin/env bash
# Builds the release APK and signs it: build/release/tv-remote-<version>.apk
#
# The signing key lives OUTSIDE the repository, in ~/.config/tv-remote (or $TVREMOTE_KEYDIR); it is
# created on the first run. Back that folder up: whoever has it can sign updates of the app, and
# without it an installed release can only be replaced after uninstalling it (which loses the
# pairing). Never commit it.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYDIR="${TVREMOTE_KEYDIR:-$HOME/.config/tv-remote}"
KS="$KEYDIR/release.jks"
PASS="$KEYDIR/keystore.pass"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"

if [ ! -f "$KS" ]; then
  echo "No signing key yet: creating one in $KEYDIR"
  mkdir -p "$KEYDIR"
  chmod 700 "$KEYDIR"
  (umask 077; echo "$(head -c 24 /dev/urandom | base64 | tr -d '/+=')" > "$PASS")
  keytool -genkeypair -alias tvremote -keyalg RSA -keysize 4096 -validity 10950 \
    -dname "CN=TV Remote" -keystore "$KS" -storepass:file "$PASS" -keypass:file "$PASS" >/dev/null
  chmod 600 "$KS"
fi

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
./gradlew assembleRelease --console=plain -q

OUT="build/release"
mkdir -p "$OUT"
rm -f "$OUT/tv-remote-$VERSION.apk" "$OUT/aligned.apk"
"$BT/zipalign" -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PASS" \
  --out "$OUT/tv-remote-$VERSION.apk" "$OUT/aligned.apk"
rm -f "$OUT/aligned.apk" "$OUT/tv-remote-$VERSION.apk.idsig"
"$BT/apksigner" verify --print-certs "$OUT/tv-remote-$VERSION.apk" | grep -E "Signer.*certificate (DN|SHA-256)" || true
echo "Built $OUT/tv-remote-$VERSION.apk"
