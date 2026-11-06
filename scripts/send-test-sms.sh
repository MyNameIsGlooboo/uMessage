#!/usr/bin/env bash
# Send one fake incoming SMS to a running emulator.
# Usage: scripts/send-test-sms.sh <number-or-sender-id> "<text>"
# Requires adb on PATH and one emulator running (set ADB_SERIAL=emulator-5554 if several devices).
set -euo pipefail
number="${1:?usage: send-test-sms.sh <number> <text>}"
text="${2:?usage: send-test-sms.sh <number> <text>}"
adb_args=()
if [ -n "${ADB_SERIAL:-}" ]; then adb_args=(-s "$ADB_SERIAL"); fi
adb "${adb_args[@]}" emu sms send "$number" "$text"
