# Emulator testing

The emulator can inject incoming SMS with `adb emu sms send`, which is enough to test the filter. It cannot test real carriers, real dual-SIM behaviour or OEM battery management; use [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md) for those.

## Setup

1. Install the toolchain: [../scripts/SETUP.md](../scripts/SETUP.md).
2. Create an AVD with a Google APIs image, API 36 (or 26 to test the legacy default-app flow), then start it:
   `emulator -avd <name>`
3. Install the debug APK: `adb install -r app/build/outputs/apk/foss/debug/*.apk` (package `com.glooboo.spamless.debug`; release builds are `com.glooboo.spamless`)
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

## Expected results (spam filter on, the default)

| Message | Expected |
|---|---|
| From 5550100 (contact) | Messages, notification shown |
| From 5550199 (unknown) | The whole chat in Spam, stored read, no notification |
| From `VM-HDFCBK` (code text) | Messages, with the code; the sender joins "Senders of a verification code" |
| Body contains "free prize" from 5550177 | Spam (unknown sender). With a blocked keyword rule "free prize" the sender also gets a block rule and the log shows reason BLOCK_RULE |

Note: `scripts/test-scenarios.sh` and `.ps1` still print the expectations of the old modes. Trust this table.

Also verify:

- "Not spam" on the 5550199 chat moves the whole chat to Messages and allows the sender (a second message from it now arrives in Messages).
- Block on a chat in Messages moves the whole chat to Spam at once; a new text from it arrives silently in Spam. Undo puts it back.
- An allow rule you made beats a block rule for the same number.
- Writing a text to an unknown number adds it to "People you wrote to"; its reply arrives in Messages.
- Turning the Spam filter off (Settings > Spam protection) moves unknown senders' chats to Messages; blocked senders stay in Spam.
- A tracking-number text ("Tracking code 99887766 for your delivery") from an unknown number stays in Spam and does not allow the sender.

## Dual SIM

The stock emulator has one SIM. Newer emulator versions can add a second SIM in AVD advanced settings (multi-SIM support varies by image); otherwise verify per-SIM behaviour on a physical phone.

## Useful commands

```
adb logcat -s uMessageReceive uMessageSort uMessageNotify uMessageForward
adb shell dumpsys package com.glooboo.spamless.debug | grep -i permission
adb shell cmd role get-role-holders android.app.role.SMS
```
