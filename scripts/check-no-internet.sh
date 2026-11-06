#!/usr/bin/env bash
# Fails (exit 1) if the given APK requests android.permission.INTERNET.
# Usage: scripts/check-no-internet.sh path/to/app.apk
# Needs aapt2 from Android build-tools (found via ANDROID_HOME / ANDROID_SDK_ROOT or PATH).
set -euo pipefail
apk="${1:?usage: check-no-internet.sh <apk>}"
aapt2="$(command -v aapt2 || true)"
if [ -z "$aapt2" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  aapt2="$(ls -d "$sdk"/build-tools/*/aapt2* 2>/dev/null | sort -V | tail -n1 || true)"
fi
[ -n "$aapt2" ] || { echo "aapt2 not found"; exit 2; }
perms="$("$aapt2" dump permissions "$apk")"
echo "$perms"
if echo "$perms" | grep -q "android.permission.INTERNET"; then
  echo "FAIL: APK requests android.permission.INTERNET. uMessage must stay offline."
  exit 1
fi
echo "OK: no INTERNET permission."
