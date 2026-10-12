#!/usr/bin/env bash
# Builds build/CoverLauncher.apk with plain SDK tools (no Gradle, no dependencies).
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
OUT=build
APK=$OUT/CoverLauncher.apk

rm -rf "$OUT/classes" "$OUT/gen" "$OUT/res.zip" "$OUT/dex" "$OUT"/*.apk
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 31 --target-sdk-version 35 --java "$OUT/gen" "$OUT/res.zip"

javac --release 17 -Xlint:all -cp "$JAR" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 31 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
[ -f "$OUT/debug.keystore" ] || keytool -genkeypair -keystore "$OUT/debug.keystore" -storepass android \
    -keypass android -alias debug -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=CoverLauncher"
"$BT/apksigner" sign --ks "$OUT/debug.keystore" --ks-pass pass:android --out "$APK" "$OUT/aligned.apk"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk"
echo "built $APK"
