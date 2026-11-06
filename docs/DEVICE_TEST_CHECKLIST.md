# Real-device test checklist

Run on a Poco/Redmi/Xiaomi phone (HyperOS or MIUI) and a Samsung dual-SIM phone (One UI). Use a second phone, or a friend, to send SMS. Record device, OS version, carrier, result and notes. Tick each box; write FAIL plus detail otherwise.

Test numbers: **C** = a number saved in your contacts, **U** = a number NOT in your contacts, **A** = an alphanumeric sender (ask your bank or use an SMS gateway).

## 0. Prep

- [ ] Install the release APK (Settings > Install unknown apps > allow for the browser/file manager).
- [ ] Put 2 SIMs in the phone, both active. Note which carrier is SIM1 and SIM2.
- [ ] Add C to Contacts. Make sure U is not in Contacts.
- [ ] Turn off RCS/chat features in the previous messaging app (Google Messages > Settings > RCS chats) so SMS arrives as plain SMS.

## 1. Default SMS role

- [ ] Onboarding offers "Set as default SMS app"; the system role dialog appears; accept.
- [ ] Settings > Default apps > SMS app shows uMessage.
- [ ] Deny the dialog once: app explains what is lost and offers to retry.
- [ ] Permissions requested with plain-language reasons (SMS, Contacts, Phone, Notifications); all granted.
- [ ] Existing old messages appear in the conversation list.

## 2. Receive per SIM

- [ ] Send SMS from C to the SIM1 number: arrives in inbox, notification shows, SIM1 badge/label correct.
- [ ] Send SMS from C to the SIM2 number: arrives, SIM2 badge/label correct.
- [ ] Send SMS from U to SIM1: goes to Blocked only, no notification, not in inbox.
- [ ] Send SMS from U to SIM2: same.

## 3. Send per SIM

- [ ] Compose to C using the SIM1 chip: delivered, sent status updates, recipient sees SIM1 number.
- [ ] Compose using the SIM2 chip: same, recipient sees SIM2 number.
- [ ] Reply in an existing thread: default SIM is the last one used in that thread.
- [ ] Long message (more than 160 chars) arrives as one message at the receiver.

## 4. Background receive

- [ ] Swipe uMessage away from recents, screen off 2 minutes, send from C: notification still arrives.
- [ ] Same with U: message appears in Blocked after reopening the app (and nothing in the inbox).
- [ ] Force-stop the app from App info, then send from C. Note whether the message arrives (expected: yes as the system wakes the default SMS app on SMS_DELIVER; record result).
- [ ] Turn Battery saver / Power saving on, repeat swipe-away test with C and U.
- [ ] Xiaomi/Poco/Redmi: Autostart enabled, Battery saver set to "No restrictions", lock-screen notifications allowed, "Notification SMS"/channel importance is high. Test with these OFF too and note what breaks, to validate the in-app OEM guide.
- [ ] Samsung: remove from "Sleeping apps" / "Deep sleeping apps"; repeat. Test again with it listed as sleeping and record the result.

## 5. Unknown vs contact sender

- [ ] Strict mode (default): C allowed, U blocked.
- [ ] Add U as a contact: next message from U is allowed immediately (contact cache updated without opening the app).
- [ ] Delete that contact: U is blocked again.
- [ ] Number formats: C saved as `+91...`, send from the local `0...` or plain form (and the reverse): treated as the same contact.
- [ ] Smart mode: OTP-like unknown message goes to the Unknown tab instead of being dropped.
- [ ] Off mode: unknown senders reach the inbox.

## 6. Whitelist and blacklist

- [ ] Whitelist U: its next message goes to the inbox.
- [ ] Blacklist C (number rule): its message is blocked; remove, and it arrives again.
- [ ] Add C to both lists: whitelist wins, message arrives.
- [ ] Prefix rule (e.g. first digits of a spam range), alphanumeric sender rule (A), keyword rule ("free prize"), regex rule: each blocks a matching test message and does not block a non-matching one.
- [ ] Per-SIM rule: rule scoped to SIM1 blocks on SIM1 and does not block on SIM2.

## 7. Restore from Blocked

- [ ] Open Blocked: items show sender, preview, time, SIM, reason.
- [ ] "Not spam" on a message: it appears in the real inbox with the original timestamp and the sender is whitelisted; a new message from that sender arrives normally.
- [ ] "Block sender": adds a BLOCK rule and clears that sender's blocked messages.
- [ ] Delete from Blocked works. Stats counters update.
- [ ] Purge: with purge set to a short period (or after the configured days), old blocked items disappear; with "keep forever" nothing is removed.

## 8. Verification codes per mode

- [ ] Smart (default): an OTP-style message without a link from A or U arrives in the inbox with the code visible; a non-OTP message from U is blocked; an OTP-style message with a link is blocked.
- [ ] Strict: the same OTP-style message from U is blocked, visible in Blocked.
- [ ] Spam settings and onboarding show no separate "Allow verification codes" switch.
- [ ] Notification "Copy code" action (if present) copies the OTP.
- [ ] Real bank/app OTP (log in to a service that texts you) arrives in Smart mode.

## 9. MMS per SIM

- [ ] Receive an MMS (picture) from C on SIM1: downloads and displays. Repeat for SIM2. Mobile data must be on for the SIM that receives MMS; note the APN/carrier behaviour.
- [ ] Send an MMS (picture) from each SIM.
- [ ] Group MMS with 2+ recipients.
- [ ] MMS from U is blocked and NOT downloaded (check that no mobile data is used and it appears in Blocked as a placeholder).
- [ ] If MMS fails on your carrier, export the diagnostics log and attach it to the issue.

## 10. Notification actions

- [ ] Notification from C shows sender and text (check lock-screen privacy setting).
- [ ] Reply inline from the notification: message sent from the correct SIM.
- [ ] Mark as read dismisses the notification and marks the thread read.
- [ ] Notifications from SIM1 and SIM2 use separate channels (long-press the notification to see).
- [ ] Notify-on-block setting: off = silent for blocked, on = a quiet notification.
- [ ] Multiple messages are grouped; tapping opens the right thread.

## 11. Reboot persistence

- [ ] Reboot the phone and do not open uMessage. Send from C: notification arrives. Send from U: goes to Blocked.
- [ ] Settings, rules, whitelist and blacklist are unchanged after reboot.
- [ ] A scheduled message set before the reboot is still sent at its time.
- [ ] Default SMS role is still held after reboot.

## 12. Misc

- [ ] SIM swap or disabling one SIM does not crash the app; SIM chips update.
- [ ] Backup export, uninstall, reinstall and restore works.
- [ ] Android system "Install unknown apps" prompt wording matches the README steps.
- [ ] Airplane mode: app works for the already-stored data (it never needs the network).
- [ ] Battery usage of uMessage over a day stays low (Settings > Battery).
- [ ] Confirm no INTERNET permission: App info > Permissions shows none for network, or `adb shell dumpsys package com.glooboo.spamless | grep INTERNET` prints nothing.

## Reporting

For each FAIL: device model, OS/HyperOS/One UI version, SIM carriers, steps, expected vs actual, and the diagnostics log if available.
