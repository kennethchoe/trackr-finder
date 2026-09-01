#!/bin/sh
# Build and push to the phone. Requires USB debugging enabled and the phone
# authorised (check with: adb devices).
set -e
cd "$(dirname "$0")"
. ./env.sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
echo "Installed. Launching…"
adb shell monkey -p com.agilesalt.trackrfinder -c android.intent.category.LAUNCHER 1 >/dev/null
