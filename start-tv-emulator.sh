#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

AVD_NAME="Television_720p"

sdk_dir=""
if [[ -f "local.properties" ]]; then
  sdk_dir="$(grep -E '^sdk\.dir=' local.properties | head -n1 | cut -d'=' -f2- || true)"
fi

if [[ -z "$sdk_dir" ]]; then
  sdk_dir="$HOME/Android/Sdk"
fi

EMULATOR_BIN="$sdk_dir/emulator/emulator"

if [[ ! -x "$EMULATOR_BIN" ]]; then
  echo "ERROR: emulator binary not found/executable at: $EMULATOR_BIN" >&2
  echo "Fix: ensure Android SDK Emulator is installed and sdk.dir is correct in local.properties" >&2
  exit 1
fi

# Start AVD with reasonable defaults for stability.
"$EMULATOR_BIN" -avd "$AVD_NAME" -netdelay none -netspeed full
