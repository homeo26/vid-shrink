#!/usr/bin/env bash
# Build & sign the VidShrink APK with Android SDK command-line tools only
# (no Gradle, no Android Studio). Output: build/vidshrink.apk
#
# Requires: JDK, Android SDK build-tools (aapt2, d8, zipalign, apksigner),
#           and an android.jar platform.
#
# Override defaults with env vars:
#   SDK=/path/to/sdk  BT_VER=36.0.0  PLATFORM=android-34  ./build.sh

set -euo pipefail
cd "$(dirname "$0")"

SDK="${SDK:-$HOME/Library/Android/sdk}"
BT_VER="${BT_VER:-36.0.0}"
PLATFORM="${PLATFORM:-android-34}"

BT="$SDK/build-tools/$BT_VER"
AJ="$SDK/platforms/$PLATFORM/android.jar"

[ -d "$BT" ] || { echo "Build-tools not found at $BT — set BT_VER or SDK"; exit 1; }
[ -f "$AJ" ] || { echo "android.jar not found at $AJ — set PLATFORM or SDK"; exit 1; }

echo "Using SDK=$SDK build-tools=$BT_VER platform=$PLATFORM"
# Keep the signing key stable across clean builds (so `adb install -r` works).
mkdir -p build
[ -f build/debug.keystore ] && cp build/debug.keystore /tmp/vidshrink-debug.keystore 2>/dev/null || true
rm -rf build
mkdir -p build/{gen,classes,dex}
[ -f /tmp/vidshrink-debug.keystore ] && cp /tmp/vidshrink-debug.keystore build/debug.keystore 2>/dev/null || true

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir res -o build/res-compiled.zip

echo "[2/6] aapt2 link → unsigned APK + R.java"
"$BT/aapt2" link \
  -I "$AJ" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --target-sdk-version 34 --min-sdk-version 29 \
  -o build/unsigned-noclasses.apk \
  build/res-compiled.zip

echo "[3/6] javac"
javac -source 17 -target 17 -Xlint:-options -classpath "$AJ" \
  -d build/classes \
  build/gen/com/homeo/vidshrink/R.java \
  src/com/homeo/vidshrink/*.java

echo "[4/6] d8 → classes.dex"
"$BT/d8" --min-api 29 --output build/dex $(find build/classes -name "*.class")

echo "[5/6] package APK"
cp build/unsigned-noclasses.apk build/unsigned.apk
( cd build/dex && zip -q ../unsigned.apk classes.dex )

if [ ! -f build/debug.keystore ]; then
  echo "  [keystore] generating one-time debug keystore"
  keytool -genkeypair -keystore build/debug.keystore \
    -storepass android -keypass android \
    -alias k -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=vidshrink,O=local,C=US" 2>/dev/null
fi

echo "[6/6] zipalign + apksigner"
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign \
  --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias k --min-sdk-version 29 \
  --out build/vidshrink.apk build/aligned.apk

echo
echo "Built: $(pwd)/build/vidshrink.apk ($(wc -c < build/vidshrink.apk | tr -d ' ') bytes)"
echo "Install with:  adb install -r build/vidshrink.apk"
