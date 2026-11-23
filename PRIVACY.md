# uMessage Privacy Policy

Effective date: 2026-11-22

This policy covers the Android app uMessage (package `com.glooboo.spamless`).

## Who we are

uMessage is an open-source SMS and MMS app (GPL-3.0) made by the developer "Glooboo".
Questions about privacy: Glooboo@proton.me

## The short version

- uMessage does not collect your data. It has no servers and no accounts.
- The app does not have the `INTERNET` permission, so Android does not let it open any network connection. Nothing is sent to any server by the app.
- No ads, no analytics, no crash reporting, no telemetry, and no third-party SDKs that collect data. The Google Play build includes Google's in-app rating card (see below), which runs on the phone, and uMessage sends no data to it.
- We do not sell or share your data with anyone.

## Data the app handles on your device, and why

Everything below is processed only on your phone.

- **Messages (SMS and MMS).** uMessage is a messaging app. It receives, reads, stores and sends your messages and shows them to you. All messages, spam included, live in Android's own SMS database on your phone. Spam texts are stored there marked as read and seen, so they make no sound; uMessage only decides whether a chat is shown in Messages or in Spam. New spam is no longer kept in uMessage's private database. Spam that older versions kept there is moved to Android's SMS database once uMessage is your default SMS app, and the old copies are deleted 30 days later. A text that cannot be moved stays in uMessage's private storage, so you can still read it. A picture message (MMS) in Spam is downloaded like any other, up to 1 MiB and not while roaming, so it can use a little mobile data.
- **Contacts.** Used to show names and to decide whether a sender is in your phonebook (for the spam filter). A cache of contact numbers is kept in private app storage. Contacts are never uploaded.
- **Phone state and SIM information.** Used to detect your SIM cards and carriers and which SIM a message arrived on or should be sent from. SIM names and numbers you type yourself are kept in the app's private settings.
- **Location (optional).** Only when you tap Location in the attach menu, the app reads your position once and puts a map link in the message field. You press Send yourself. The position is not stored and not sent anywhere by the app.
- **Photos (optional; not in the Google Play build, which uses only the system photo picker).** If you allow it, the attach menu can show your recent pictures so you can attach one with a tap. A picture is read only when you tap it. By default the app removes GPS location and camera details from the copy it sends; your original picture is not changed. The system photo picker works without this permission.
- **Phone calls (optional).** When you tap a phone number in a chat, a contact card or conversation details, and you have allowed uMessage to make calls, the app places the call at once. If you did not allow it, the phone's dialer opens with the number filled in and you press call yourself. The app does not read or change your call log.
- **Notifications.** Used to show new messages. By default uMessage asks Android to show only the sender, not the text, while the phone is locked; the text appears once you unlock. You can switch the text on for the lock screen in Settings. Android has its own lock-screen setting ("Notifications on lock screen" or "Sensitive notifications"): if you set it to show all content, Android shows the text on the lock screen whatever uMessage asks. A new install also posts one quiet weekly summary of how many texts went to Spam; it is made on the phone, shows only a count (on the lock screen only its title) and can be turned off in Settings.
- **App lock (optional).** If you turn it on, a PIN hash is kept in the app's private settings. Fingerprint or face unlock is handled by Android; the app only receives a yes or no. The "Forgot it?" button on the lock screen accepts your phone's own screen lock instead of the PIN, also for hidden chats, and skips the wait after wrong tries. Anyone who knows your phone's screen lock can therefore open uMessage.
- **Rules, settings and logs.** Your spam rules, blocked numbers and keywords, the two automatic allow lists (numbers you wrote to, and senders of a verification code), the list of chats shown in Spam, saved texts, settings, and a short decision log (sender number, decision, matching rule; no message text; kept for up to 7 days, at most 200 entries) are kept in private app storage.

## Permissions and what they are for

| Permission | Purpose |
|---|---|
| READ_SMS, WRITE_SMS, RECEIVE_SMS, RECEIVE_MMS | Receive, read and store messages. Required to be the default SMS app. |
| SEND_SMS | Send the messages and replies you write. |
| READ_CONTACTS | Show contact names; tell known senders from unknown ones. |
| READ_PHONE_STATE | Detect SIM cards and which SIM to use. |
| CALL_PHONE | Call a number at once when you tap it in a chat or contact. Asked at the moment you tap; if you say no, the dialer opens with the number filled in. |
| ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION | Share your location, only when you tap Location in the attach menu. |
| READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED, READ_EXTERNAL_STORAGE (Android 12 and older; not in the Google Play build) | Show recent photos in the attach menu, only if you allow it. |
| POST_NOTIFICATIONS | Show notifications. |
| USE_BIOMETRIC, USE_FINGERPRINT (Android 8 and 9) | Unlock the optional app lock with fingerprint or face. |
| WAKE_LOCK, RECEIVE_BOOT_COMPLETED | Finish handling incoming messages with the screen off, light the screen for a new message if you turn that on, and send scheduled messages, also after a reboot. |
| SCHEDULE_EXACT_ALARM | Send scheduled messages and reminders at the exact time you chose. If it is not allowed, approximate times are used. |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC | Keep a message backup or restore that you started running, with a progress notification. |

The app manifest is the authoritative list of permissions.

## Sending messages and your carrier

Messages you send go through your mobile carrier as normal SMS or MMS. Your carrier handles them under its own terms and privacy policy. The app does not send them anywhere else.

## Forwarding messages (optional)

uMessage can forward copies of incoming messages to other phone numbers. This is optional and **off by default**: nothing is forwarded until you open Settings > Forwarding and create a rule yourself.

- Only the numbers you enter receive anything, up to three per rule.
- When a rule you created matches an incoming message, your phone sends a copy as a normal SMS from your own number, on your own mobile plan. Each forward is a paid SMS and can cost money, depending on your plan.
- Everything runs on your phone. No copy goes through any server, and the developer never receives, sees or stores your messages or the numbers you enter. The app has no internet permission, so it could not send them anywhere over the network.
- Only messages that arrive in Messages are forwarded. Messages in Spam, including those from blocked senders, are never forwarded.
- Verification codes are forwarded only if you switch that on for that rule.
- You can switch a rule off, delete it, or turn forwarding off completely at any time. A daily limit and an hourly cap per rule are available.
- Forwarding keeps a local log on the phone, so you can see what was sent. The log never leaves the phone.

This is the same in every build of the app, Google Play included.

## Rating card (Google Play build)

The Google Play build may show Google's own rating card. The Play Store app shows and handles that card; uMessage sends no data to do this. The app keeps only a count of the days it was opened and the time of the last request, on the phone. The F-Droid/GitHub build has no rating card.

## Storage and backups

- Data is stored only on your phone, in the Android SMS database and uMessage's private app storage, which other apps cannot read.
- The app opts out of Android and Google backups and device-to-device transfer, so its databases and settings are not copied off the phone that way. Your phone's own SMS backup, if you use one, is handled by your phone and not by uMessage; it can include spam texts, because they are in Android's SMS database.
- Private notes you add to messages are stored only on the phone, in uMessage's private app storage, and are part of the message backups you create. So are your stars, labels, archived, muted and hidden chats, drafts, recycle-bin messages, saved texts, older spam not yet moved to Android's SMS database, and scheduled messages and reminders (never the app-lock PIN). Spam chats are backed up like any other chat.
- Saved texts and SIM names and numbers are stored as plain text in the app's private settings. Do not save passwords or PINs as saved texts.

## Exports and backup files

The app creates an export or backup file only when you ask for it. It is written to the place you choose and stays there. A message export is not encrypted unless you protect it with a password when you create it. Backups of your settings and rules are never encrypted. These files can contain message text and phone numbers, so keep them somewhere private and delete them when you no longer need them. uMessage does not upload them.

When you share a chat or the decision log, or attach a photo, uMessage makes a temporary copy in its private cache. Copies older than a day are deleted automatically. While a backup is being made, it is first written to a temporary file in a folder of its own in the private cache and then copied to the place you chose; that file is deleted right after, and if the app is closed in between, at its next start. A backup saved to Google Drive is first put in a folder of the private cache and handed to the Drive app; that copy is deleted by the next hand-over or by the daily cache clean-up, within about two days. When you restore a backup that holds scheduled messages, they are not sent until you turn them on: until you choose, their text and numbers are kept in the app's private storage on this phone.

## Retention and deletion

We keep no data on any server, so there is nothing for us to delete. On your phone:

- Delete messages or conversations in the app. When the recycle bin is on, deleted messages and conversations stay in it for 30 days, until you empty it or restore them, and are then removed for good. Spam is kept until you delete it. If you turn on auto-delete of old spam in Settings, texts that arrived in Spam more than the chosen number of days ago are deleted from Android's SMS database, for every app; texts you sent, starred or added a note to stay.
- Clear the decision log in Settings, or delete spam in the app.
- Clear the app's storage in Android settings, or uninstall the app, to remove everything uMessage keeps. Messages in Android's SMS database, spam included, stay after that; they are removed only by deleting them (in the app or another SMS app).

## Children

uMessage is not directed to children under 13, and we do not knowingly collect any personal information from anyone.

## Security

The app has no network access, keeps its files in private app storage, and offers an optional app lock. Android encrypts that storage while the phone is locked after it starts. No system is perfectly secure, and anyone who can unlock your phone can read your messages.

## Changes to this policy

If the policy changes, the new version is published at this page with a new effective date. The change applies from that date.

## Contact

Glooboo@proton.me
