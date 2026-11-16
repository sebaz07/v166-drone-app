#!/usr/bin/env bash
# Compila el APK sin Gradle: aapt2 + javac + dx + zipalign + apksigner.
# (En Android Studio tambien se puede abrir el proyecto con build.gradle.)
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR="${ANDROID_JAR:-/home/claude/sdk/android-platforms/android-34/android.jar}"
SRC=app/src/main
OUT=build
rm -rf "$OUT/apk" && mkdir -p "$OUT/apk/res" "$OUT/apk/classes" "$OUT/apk/dex"
aapt2 compile --dir "$SRC/res" -o "$OUT/apk/res.zip"
aapt2 link -o "$OUT/apk/base.apk" -I "$ANDROID_JAR" --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 --version-code 2 --version-name 1.1 \
  -A "$SRC/assets" --java "$OUT/apk/gen" "$OUT/apk/res.zip"
javac --release 8 -nowarn -Xlint:-options -cp "$ANDROID_JAR" -d "$OUT/apk/classes" \
  $(find "$SRC/java" "$OUT/apk/gen" -name '*.java')
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/apk/dex/classes.dex" "$OUT/apk/classes"
cp "$OUT/apk/base.apk" "$OUT/apk/unaligned.apk"
(cd "$OUT/apk/dex" && zip -q -j ../unaligned.apk classes.dex)
zipalign -f 4 "$OUT/apk/unaligned.apk" "$OUT/apk/aligned.apk"
if [ ! -f "$OUT/v166.keystore" ]; then
  keytool -genkeypair -keystore "$OUT/v166.keystore" -storepass v166pass -keypass v166pass -alias v166 \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Dron V166, O=Sebas, C=CO" >/dev/null 2>&1
fi
apksigner sign --ks "$OUT/v166.keystore" --ks-pass pass:v166pass --key-pass pass:v166pass \
  --min-sdk-version 26 --out "$OUT/DronV166.apk" "$OUT/apk/aligned.apk"
apksigner verify --verbose --min-sdk-version 26 "$OUT/DronV166.apk" | head -5
ls -la "$OUT/DronV166.apk"
