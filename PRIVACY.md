# uMessage Privacy Policy

Effective date: 2026-11-06

This policy covers the Android app uMessage (package `com.glooboo.spamless`).

## Who we are

uMessage is an open-source SMS and MMS app (GPL-3.0) made by the developer "Glooboo".
Questions about privacy: Glooboo@proton.me

## The short version

- uMessage does not collect your data. It has no servers and no accounts.
- The app does not have the `INTERNET` permission, so Android does not let it open any network connection. Nothing is sent to any server by the app.
- No ads, no analytics, no crash reporting, no telemetry, and no third-party SDKs.
- We do not sell or share your data with anyone.

## Data the app handles on your device, and why

Everything below is processed only on your phone.

- **Messages (SMS and MMS).** uMessage is a messaging app. It receives, reads, stores and sends your messages and shows them to you. Allowed messages live in Android's own SMS database. Messages the spam filter blocks are kept in uMessage's private storage so you can review or restore them.
- **Contacts.** Used to show names and to decide whether a sender is in your phonebook (for the spam filter). A cache of contact numbers is kept in private app storage. Contacts are never uploaded.
- **Phone state and SIM information.** Used to detect your SIM cards and carriers and which SIM a message arrived on or should be sent from. SIM names and numbers you type yourself are kept in the app's private settings.
- **Location (optional).** Only when you tap Location in the attach menu, the app reads your position once and puts a map link in the message field. You press Send yourself. The position is not stored and not sent anywhere by the app.
- **Photos (optional).** Only if you allow it, the attach menu can show your recent pictures so you can attach one with a tap. A picture is read only when you tap it. By default the app removes GPS location and camera details from the copy it sends; your original picture is not changed. The system photo picker works without this permission.
- **Notifications.** Used to show new messages. By default a locked phone shows only the sender, not the text.
- **App lock (optional).** If you turn it on, a PIN hash is kept in the app's private settings. Fingerprint or face unlock is handled by Android; the app only receives a yes or no.
- **Rules, settings and logs.** Your spam rules, blocked numbers and keywords, saved texts, settings, and a short decision log (sender number, decision, matching rule; no message text; kept for up to 7 days, at most 200 entries) are kept in private app storage.

## Permissions and what they are for

| Permission | Purpose |
|---|---|
| READ_SMS, WRITE_SMS, RECEIVE_SMS, RECEIVE_MMS | Receive, read and store messages. Required to be the default SMS app. |
| SEND_SMS | Send the messages and replies you write. |
| READ_CONTACTS | Show contact names; tell known senders from unknown ones. |
| READ_PHONE_STATE | Detect SIM cards and which SIM to use. |
| ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION | Share your location, only when you tap Location in the attach menu. |
| READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED, READ_EXTERNAL_STORAGE (Android 12 and older) | Show recent photos in the attach menu, only if you allow it. |
| POST_NOTIFICATIONS | Show notifications. |
| CALL_PHONE | Start a call when you tap a number. The app never places calls on its own. |
| USE_BIOMETRIC | Unlock the optional app lock with fingerprint or face. |
| WAKE_LOCK, RECEIVE_BOOT_COMPLETED, SCHEDULE_EXACT_ALARM | Finish handling incoming messages with the screen off, and send scheduled messages on time, also after a reboot. |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC | Keep a message backup or restore that you started running, with a progress notification. |

The app manifest is the authoritative list of permissions.

## Sending messages and your carrier

Messages you send go through your mobile carrier as normal SMS or MMS. Your carrier handles them under its own terms and privacy policy. The app does not send them anywhere else.

## Automatic forwarding

Automatic forwarding of messages exists only in the F-Droid/GitHub build, not in the Google Play build.

In the build that has it, forwarding is off until you create a rule. When a rule you created matches a message, the app sends an SMS from your own number to a number you chose, through your carrier. It never forwards on its own and never to anyone you did not choose. Forwarding keeps a local log on the phone; nothing goes to any server.

## Storage and backups

- Data is stored only on your phone, in the Android SMS database and uMessage's private app storage, which other apps cannot read.
- The app opts out of Android and Google backups and device-to-device transfer, so its databases and settings are not copied off the phone that way. Your phone's own SMS backup, if you use one, is handled by your phone and not by uMessage.
- Private notes you add to messages are stored only on the phone, in uMessage's private app storage, and are part of the message backups you create.
- Saved texts and SIM names and numbers are stored as plain text in the app's private settings. Do not save passwords or PINs as saved texts.

## Exports and backup files

The app creates an export or backup file only when you ask for it. It is written to the place you choose and stays there. These files are not encrypted and can contain message text and phone numbers, so keep them somewhere private and delete them when you no longer need them. uMessage does not upload them.

## Retention and deletion

We keep no data on any server, so there is nothing for us to delete. On your phone:

- Delete messages or conversations in the app. When the recycle bin is on, deleted messages and conversations stay in it for 30 days, until you empty it or restore them, and are then removed for good. Blocked messages are deleted after 30 days by default; you can change this or keep them until you delete them.
- Clear the decision log in Settings, or delete blocked messages in the app.
- Clear the app's storage in Android settings, or uninstall the app, to remove everything uMessage keeps. Messages in Android's SMS database are removed only by deleting them (in the app or another SMS app).

## Children

uMessage is not directed to children under 13, and we do not knowingly collect any personal information from anyone.

## Security

The app has no network access, keeps its files in private app storage, and offers an optional app lock. Android encrypts that storage while the phone is locked after it starts. No system is perfectly secure, and anyone who can unlock your phone can read your messages.

## Changes to this policy

If the policy changes, the new version is published at this page with a new effective date. The change applies from that date.

## Contact

Glooboo@proton.me
