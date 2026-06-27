#!/usr/bin/env bash
# One-time on-device setup for portal-kasa.
#   ./setup.sh   install + launch once
#
# Uses hzdb (Horizon Debug Bridge) in place of raw adb. Enable ADB on the Portal first
# (Settings -> Debug -> ADB Enabled), connect USB-C, tap "Allow".
set -euo pipefail

PKG="com.portal.kasa"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$APK" ]]; then
  echo "APK not found -- building it (./gradlew assembleDebug)..."
  ./gradlew assembleDebug   # needs a JDK 21 toolchain and the Android SDK (sdk.dir / \$ANDROID_HOME)
fi

echo "1/2  Installing $APK..."
npx -y @meta-quest/hzdb app install -r "$APK"

echo "2/2  Launching (plugs on the same Wi-Fi appear automatically)..."
npx -y @meta-quest/hzdb app launch "$PKG" || \
  npx -y @meta-quest/hzdb adb shell "am start -n $PKG/.MainActivity"

echo
echo "Done. Open the app; plugs on the same Wi-Fi are discovered and listed automatically."
echo "For voice: enable \"Kasa Plugs\" in the assistant's Settings -> External tools, then start a new chat."
