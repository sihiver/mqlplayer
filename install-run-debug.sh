#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

./gradlew installDebug

# Launch the app (LoginActivity is the launcher)
adb shell am start -n com.sihiver.mqltv/.LoginActivity
