# Emulator testing

The emulator can inject incoming SMS with `adb emu sms send`, which is enough to test the filter. It cannot test real carriers, real dual-SIM behaviour or OEM battery management; use [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md) for those.

## Setup

1. Install the toolchain: [../scripts/SETUP.md](../scripts/SETUP.md).
2. Create an AVD with a Google APIs image, API 36 (or 26 to test the legacy default-app flow), then start it:
   `emulator -avd <name>`
3. Install the debug APK: `adb install -r app/build/outputs/apk/foss/debug/*.apk`
4. Open uMessage, finish onboarding, and set it as the default SMS app.
5. Add a contact (Contacts app) with number `5550100`.

## Inject messages

```
scripts/send-test-sms.sh 5550100 "hello"          # bash
.\scripts\send-test-sms.ps1 -Number 5550100 -Text "hello"   # PowerShell
```

Run all four standard scenarios:

```
CONTACT_NUMBER=5550100 scripts/test-scenarios.sh
.\scripts\test-scenarios.ps1 -ContactNumber 5550100
```

Note: `adb emu` requires the emulator console auth token (`~/.emulator_console_auth_token`); adb handles it automatically. Spaces are fine in the text argument; avoid newlines.

## Expected results (Strict mode)

| Message | Expected |
|---|---|
| From 5550100 (contact) | Appears in inbox, notification shown |
| From 5550199 (unknown) | Only in Spam folder, no notification |
| From `VM-HDFCBK` (OTP text) | Blocked; allowed in Smart mode or when the sender is allowed |
| Body contains "free prize" | Blocked (with a KEYWORD blacklist rule it shows reason BLOCK_RULE) |

Also verify: "Not spam" on a blocked item moves it to the inbox and whitelists the sender (a second message from it now arrives); whitelist beats blacklist; switching mode to Off lets unknown senders through.

## Dual SIM

The stock emulator has one SIM. Newer emulator versions can add a second SIM in AVD advanced settings (multi-SIM support varies by image); otherwise verify per-SIM behaviour on a physical phone.

## Useful commands

```
adb logcat | grep -i spamless
adb shell dumpsys package com.glooboo.spamless | grep -i permission
adb shell cmd role get-role-holders android.app.role.SMS
```
