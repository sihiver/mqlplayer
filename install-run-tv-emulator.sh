#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

sdk_dir=""
if [[ -f "local.properties" ]]; then
  sdk_dir="$(grep -E '^sdk\.dir=' local.properties | head -n1 | cut -d'=' -f2- || true)"
fi

if [[ -z "$sdk_dir" ]]; then
  sdk_dir="$HOME/Android/Sdk"
fi

ADB_BIN="$sdk_dir/platform-tools/adb"

if [[ ! -x "$ADB_BIN" ]]; then
  echo "ERROR: adb binary not found/executable at: $ADB_BIN" >&2
  echo "Fix: ensure Android SDK Platform-Tools is installed and sdk.dir is correct in local.properties" >&2
  exit 1
fi

./gradlew assembleDebug

if [[ ! -f "$APK_PATH" ]]; then
  echo "ERROR: Debug APK not found at: $APK_PATH" >&2
  exit 1
fi

# Target the first running emulator.
if ! "$ADB_BIN" -e get-state >/dev/null 2>&1; then
  echo "ERROR: No Android emulator detected (adb -e). Start the TV AVD first." >&2
  echo "Tip: run ./start-tv-emulator.sh (or start it via Android Studio Device Manager)." >&2
  exit 1
fi

"$ADB_BIN" -e wait-for-device
"$ADB_BIN" -e install -r -t "$APK_PATH"

# Launch the app (LoginActivity is the launcher)
"$ADB_BIN" -e shell am start -n com.sihiver.mqltv/.LoginActivity
