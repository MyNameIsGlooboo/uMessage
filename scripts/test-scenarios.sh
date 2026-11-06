#!/usr/bin/env bash
# Sends four incoming SMS covering the main filter paths.
# Prereq: uMessage is the default SMS app on the emulator, Strict mode on, and
# CONTACT_NUMBER exists in the emulator's Contacts (see docs/EMULATOR_TESTING.md).
# Usage: CONTACT_NUMBER=5550100 scripts/test-scenarios.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
contact="${CONTACT_NUMBER:-5550100}"
unknown="${UNKNOWN_NUMBER:-5550199}"

echo "1/4 contact number -> expect: inbox + notification"
"$here/send-test-sms.sh" "$contact" "Hi, are we still on for lunch?"; sleep 2
echo "2/4 unknown number -> expect: Blocked folder only"
"$here/send-test-sms.sh" "$unknown" "Hello, I am a stranger writing to you."; sleep 2
echo "3/4 OTP from alphanumeric id -> expect: Blocked (OTP exemption off) or inbox (on)"
"$here/send-test-sms.sh" "VM-HDFCBK" "123456 is your OTP for login. Do not share it with anyone."; sleep 2
echo "4/4 spam keyword -> expect: Blocked (add KEYWORD blacklist rule 'free prize' to test the rule path)"
"$here/send-test-sms.sh" "5550177" "Congratulations! Claim your FREE PRIZE now: bit.ly/xyz"
echo "Done."
