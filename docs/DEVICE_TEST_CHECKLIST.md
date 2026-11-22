# Real-device test checklist

Run on a Poco/Redmi/Xiaomi phone (HyperOS or MIUI) and a Samsung dual-SIM phone (One UI). Use a second phone, or a friend, to send SMS. Record device, OS version, carrier, result and notes. Tick each box; write FAIL plus detail otherwise.

Package names: release and Play builds `com.glooboo.spamless`; debug and daily builds `com.glooboo.spamless.debug`. (The code lives under `com.glooboo.umessage`, but that is not the installed package.)

Test numbers: **C** = a number saved in your contacts, **U** = a number NOT in your contacts, **A** = an alphanumeric sender (ask your bank or use an SMS gateway).

The model: the phone's own message store holds every message, spam included. uMessage only decides whether a chat shows in **Messages** or in **Spam**, always whole. There is one switch, **Spam filter** (on by default). There are no Strict, Smart or Off modes any more.

## 0. Prep

- [ ] Install the release APK (Settings > Install unknown apps > allow for the browser/file manager).
- [ ] Put 2 SIMs in the phone, both active. Note which carrier is SIM1 and SIM2.
- [ ] Add C to Contacts. Make sure U is not in Contacts, and that you have never sent a text to U from this phone.
- [ ] Turn off RCS/chat features in the previous messaging app (Google Messages > Settings > RCS chats) so SMS arrives as plain SMS.

## 1. Default SMS role and first sort

- [ ] Welcome shows the language chip and the two-option choice: "Sort unknown senders into Spam" (selected) and "Keep everything in Messages".
- [ ] Onboarding offers "Set as default SMS app"; the system role dialog appears; accept.
- [ ] Settings > Default apps > SMS app shows uMessage.
- [ ] Deny the dialog once: the app explains what is lost and offers to retry; no dead end.
- [ ] Permissions requested with plain-language reasons (SMS, Contacts, Phone, Notifications); all granted.
- [ ] The progress page "Sorting your chats" shows; "Continue in background" appears after about 15 seconds.
- [ ] Existing old messages appear. Chats with unknown senders that you never wrote to and that sent no code are in Spam; the card "N chats are now in Spam" shows on Messages.
- [ ] Updating over an older version: the "What changed" page shows once; older spam moves into the senders' chats; nothing is lost.

## 2. Receive per SIM

- [ ] Send SMS from C to the SIM1 number: arrives in Messages, notification shows, SIM1 badge/label correct.
- [ ] Send SMS from C to the SIM2 number: arrives, SIM2 badge/label correct.
- [ ] Send SMS from U to SIM1: the whole chat is in Spam, no notification, not in Messages. The Spam tab shows a number.
- [ ] Send SMS from U to SIM2: same.
- [ ] Open the Spam tab: its number clears. The text shows as read in another SMS app (switch default app briefly, then switch back).

## 3. Send per SIM

- [ ] Compose to C using the SIM1 chip: delivered, sent status updates, recipient sees SIM1 number.
- [ ] Compose using the SIM2 chip: same, recipient sees SIM2 number.
- [ ] Reply in an existing thread: default SIM is the last one used in that thread.
- [ ] Long message (more than 160 chars) arrives as one message at the receiver.

## 4. Background receive

- [ ] Swipe uMessage away from recents, screen off 2 minutes, send from C: notification still arrives.
- [ ] Same with U: the chat is in Spam after reopening the app (and not in Messages), no notification.
- [ ] Force-stop the app from App info, then send from C. Note whether the message arrives (expected: yes as the system wakes the default SMS app on SMS_DELIVER; record result).
- [ ] Turn Battery saver / Power saving on, repeat swipe-away test with C and U.
- [ ] Xiaomi/Poco/Redmi: Autostart enabled, Battery saver set to "No restrictions", lock-screen notifications allowed, "Notification SMS"/channel importance is high. Test with these OFF too and note what breaks, to validate the in-app OEM guide.
- [ ] Samsung: remove from "Sleeping apps" / "Deep sleeping apps"; repeat. Test again with it listed as sleeping and record the result.

## 5. Unknown vs contact sender

- [ ] Spam filter on (default): C in Messages, U in Spam.
- [ ] Add U as a contact: its chat moves to Messages within a few seconds, and the next message from U is in Messages (contact cache updated without opening the app).
- [ ] Delete that contact: U's chat goes back to Spam (unless you wrote to U meanwhile).
- [ ] Number formats: C saved in international form (`+...`), send from the national form (and the reverse): treated as the same contact.
- [ ] Turn the Spam filter off: the confirm says how many chats move; U's chat moves to Messages; chats of blocked senders stay in Spam. Turn it on again: U goes back to Spam.

## 6. People you wrote to

- [ ] Write a text to a new unknown number U2: U2 joins "People you wrote to" (Settings > Spam protection > Rules). A reply from U2 arrives in Messages with a notification.
- [ ] The chat shows "Allowed because you wrote to this number." with Block and Keep. Keep hides the line for good.
- [ ] Remove U2 from the list (with Undo): its chat goes to Spam.
- [ ] Turn "People you wrote to" off: the confirm says how many chats move; the members stay listed.

## 7. Block and allow rules

- [ ] Block U from Messages (left swipe): the whole chat moves to Spam at once, its notification is cancelled, it is marked read. Undo brings it back as it was.
- [ ] Block C (a contact): the chat moves to Spam; new texts from C arrive silently in Spam, no notification, not even for a code.
- [ ] "Report spam" does the same as Block (it does not archive).
- [ ] Add an allow rule for U and a block rule for U: the allow rule you made wins, the chat stays in Messages; the block snackbar says an allow rule still lets the sender in.
- [ ] Blocked keyword ("free prize") from U: the sender gets a block rule, the chat shows "Blocked automatically because a text had one of your blocked words."
- [ ] Allowed keyword from U: the sender is allowed for good; the chat shows "Allowed automatically ..." with Block; Block wins over it.
- [ ] Prefix rule, alphanumeric sender rule (A), regex rule, website rule: each moves a matching sender and not a non-matching one.
- [ ] Rules apply to both SIMs: a block made while on SIM1 also holds for texts on SIM2. The rule form has no SIM choice.

## 8. The Spam tab

- [ ] Rows are chats, with "Blocked by you" or "Unknown sender".
- [ ] "Not spam" on U: the whole chat moves to Messages, the sender gets an allow rule, Undo works. On a sender name (A) it asks once first.
- [ ] "Unblock" on a blocked contact: the chat goes back to Messages. On a blocked unknown number: it stays in Spam and the snackbar offers "Not spam".
- [ ] A mixed selection (blocked and unknown) offers only Delete and Mark as read.
- [ ] "Mark all as read" in the menu clears every unread count.
- [ ] Delete: the confirm says it deletes from the phone for every app; Undo works right after; there is no "Delete all". With another app as default SMS app, Delete is off and says why.
- [ ] Reply from a Spam chat: the sender joins "People you wrote to" and the chat moves to Messages. A suggested reply asks "This allows the sender. Send?".
- [ ] Auto-delete is "Keep forever" by default. Set a number of days: a warning says texts are deleted from the phone itself. Sent, starred and noted texts stay.

## 9. Verification codes

- [ ] A clear code ("Your verification code is 482913") from U or A: arrives in Messages with the code visible; the sender joins "Senders of a verification code"; its next advert also arrives in Messages.
- [ ] The chat shows "Allowed because this sender sent a verification code." with Block and Keep.
- [ ] A tracking or order number from U ("Tracking code 99887766 for your delivery"): stays in Spam, silent, sender not allowed.
- [ ] Turn "Senders of a verification code" off: a code from a new unknown sender goes to Spam (old Strict behaviour).
- [ ] A code from a blocked sender: no notification; the Spam row shows "Copy code" for ten minutes and it copies the code.
- [ ] Notification "Copy code" action copies the code.
- [ ] Real bank/app code (log in to a service that texts you) arrives in Messages.

## 10. MMS per SIM

- [ ] Receive an MMS (picture) from C on SIM1: downloads and displays. Repeat for SIM2. Mobile data must be on for the SIM that receives MMS; note the APN/carrier behaviour.
- [ ] Send an MMS (picture) from each SIM.
- [ ] Group MMS with 2+ recipients: stays in Messages, also when one member is unknown (normal notification) or blocked (silent).
- [ ] MMS from U (under 1 MiB, not roaming): downloaded into the phone's store, shown in U's chat in Spam, no notification.
- [ ] MMS from a blocked sender (SE-29): downloaded the same way, silent, in Spam.
- [ ] MMS from U while roaming, or larger than 1 MiB: stays a "Tap to download" row in Spam; tapping it downloads it.
- [ ] A failed download keeps the notice with "Tap to try again"; in Spam no toast or notification.
- [ ] If MMS fails on your carrier, export the diagnostics log and attach it to the issue.

## 11. Notification actions

- [ ] Notification from C shows sender and text (check lock-screen privacy setting).
- [ ] Reply inline from the notification: message sent from the correct SIM.
- [ ] Mark as read dismisses the notification and marks the thread read.
- [ ] Block from the notification: the chat moves to Spam; Undo puts it back.
- [ ] Notifications from SIM1 and SIM2 use separate channels (long-press the notification to see).
- [ ] "Notify me for texts from unknown senders": off = silent for U; on = one quiet notification that opens Spam, private on the lock screen. Never for a blocked sender.
- [ ] Multiple messages are grouped; tapping opens the right thread.

## 12. App lock

- [ ] Turn on App lock with a PIN. Leave the app and come back after the lock time: it asks for the PIN.
- [ ] Wrong PIN several times: a wait is shown.
- [ ] "Forgot your PIN?" accepts the phone's screen lock, opens the app and skips the wait.
- [ ] "Only chosen parts" with Spam locked: the Spam tab asks; a Spam chat opened from a launcher shortcut, recent apps or another app also asks.
- [ ] Hidden chat: gone from the list, search and shortcuts; its notification says only "New message".

## 13. Forwarding

- [ ] Settings > Forwarding: no rule means nothing is forwarded.
- [ ] Add a rule for C to a test number on SIM1: a text from C is forwarded as SMS from SIM1; the Forwarded tab and log show it.
- [ ] A text from U (in Spam) or from a blocked sender is never forwarded.
- [ ] A code from C is forwarded only when the rule's code switch is on.
- [ ] Daily forward limit set to 5: after 5 forwards nothing more goes out that day and a notification says so.

## 14. Reboot persistence

- [ ] Reboot the phone and do not open uMessage. Send from C: notification arrives. Send from U: goes to Spam, silent.
- [ ] Settings, rules and both automatic lists are unchanged after reboot.
- [ ] A scheduled message set before the reboot is still sent at its time.
- [ ] Default SMS role is still held after reboot.

## 15. Not the default app, uninstall

- [ ] Make another app the default SMS app. Texts from U arrive there (unread). uMessage still shows U's chat in Spam; Delete in Spam is off with a reason.
- [ ] Make uMessage the default again: the pending texts are sorted; nothing is lost.
- [ ] Uninstall uMessage (test phone only): every message, spam included, is still in the other SMS app, spam as read texts.

## 16. Misc

- [ ] SIM swap or disabling one SIM does not crash the app; SIM chips update.
- [ ] Backup export, uninstall, reinstall and restore works. A restore with scheduled messages asks before turning them on.
- [ ] Android system "Install unknown apps" prompt wording matches the README steps.
- [ ] Airplane mode: app works for the already-stored data (it never needs the network).
- [ ] Battery usage of uMessage over a day stays low (Settings > Battery).
- [ ] Confirm no INTERNET permission: App info > Permissions shows none for network, or `adb shell dumpsys package com.glooboo.spamless | grep INTERNET` prints nothing (use `com.glooboo.spamless.debug` for a debug or daily build).

## Reporting

For each FAIL: device model, OS/HyperOS/One UI version, SIM carriers, steps, expected vs actual, and the diagnostics log if available.
