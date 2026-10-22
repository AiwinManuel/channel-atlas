#!/usr/bin/env bash
# Builds ChannelAtlas.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK (17+) and the Android SDK (build-tools 34+, platform android-34).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/freemytv-tools/android-sdk}}"
[ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Set ANDROID_HOME."; exit 1; }
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/freemytv-tools" ]; then
  JAVA_HOME="$(ls -d "$HOME"/freemytv-tools/jdk-17*/Contents/Home 2>/dev/null | tail -1)"
fi
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
J="${JAVA_HOME:+$JAVA_HOME/bin/}"
[ -n "${JAVA_HOME:-}" ] && export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH" # d8 and apksigner run `java`

# App art (icon and Fire TV banner), drawn once with Java's own graphics.
if [ ! -f app/res/drawable-xhdpi/banner.png ]; then
  "${J}java" -Djava.awt.headless=true tools/MakeArt.java app/res/drawable-xhdpi
fi

rm -rf out && mkdir -p out/gen out/classes
"$BT/aapt2" compile --dir app/res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest app/AndroidManifest.xml --java out/gen out/res.zip \
  --min-sdk-version 22 --target-sdk-version 33 -A app/assets
"${J}javac" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -encoding UTF-8 -d out/classes $(find app/src out/gen -name '*.java')
"$BT/d8" --release --min-api 22 --lib "$AJ" --output out $(find out/classes -name '*.class')
(cd out && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

# Signed with this folder's own key (made once; updates need the same key, so keep channelatlas.keystore).
if [ ! -f channelatlas.keystore ]; then
  "${J}keytool" -genkeypair -keystore channelatlas.keystore -alias channelatlas -keyalg RSA -keysize 2048 \
    -validity 20000 -storepass channelatlas -keypass channelatlas -dname "CN=Channel Atlas" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks channelatlas.keystore --ks-pass pass:channelatlas --key-pass pass:channelatlas \
  --out ChannelAtlas.apk out/aligned.apk
echo "Built ChannelAtlas.apk ($(du -h ChannelAtlas.apk | cut -f1)). Install: adb install -r ChannelAtlas.apk"
