# uMessage user guide

uMessage is a messaging app for SMS and MMS that keeps spam out of your inbox. This guide explains how it decides what is spam and how to change that.

## Getting started

The first-run setup has up to four steps:

1. **Welcome.** The only control is a language chip at the top. Pick your language here, or keep the phone's language; you can change it later with the Language row in Settings. Tap **Get started**.
2. **Allow.** Make uMessage your default SMS app, and allow Messages (SMS), Contacts, Phone state and Notifications; **Allow all** asks for them in one go. uMessage can only receive and filter messages while it is your default SMS app. Contacts access is how uMessage knows who you know. Without it, uMessage does not block unknown phone numbers at all, so that it never hides messages from family and friends. This step is left out when uMessage already is the default app and everything is allowed.
3. **Unknown senders.** Choose Strict, Smart or Off (see below) and how long to keep spam. The **Get delivery reports** switch is also here. It is on by default and shows when your messages are delivered. Some carriers may charge for delivery reports, so switch it off there if yours does. You can change it later in Settings > Typing and sending > Sending > **Get delivery reports**.
4. **Keep uMessage running.** On Xiaomi, Redmi and Poco phones turn on **Autostart** and set **Battery saver** to "No restrictions" for uMessage. On Samsung, remove it from "Sleeping apps". Otherwise the phone may delay incoming messages.

## Finding your way around

The bottom bar has **Messages** and **Spam**, plus **Archive**, which is always there (it shows an empty state until you archive a chat). A **Forwarded** tab appears only in the GitHub and F-Droid builds, once a forward rule exists and you have switched the tab on.

The three-dot menu at the top of Messages lists, in this order: **Recycle bin** (first; it shows how many messages are in it, and is listed only while the recycle bin is switched on in Settings > Storage and cleanup), **Scheduled** (only when something is scheduled), **Starred** and **Settings**.

The app works in portrait and landscape. Turn the phone sideways and every screen follows: content stays clear of a camera cutout and of the navigation bar at the side, message bubbles keep a readable width, and the keyboard leaves the message box usable.

Settings > About has **Send feedback**, which opens your email app with a message to the developer already addressed, and, in the Google Play build only, **Rate uMessage**, which opens the Play Store.

## Who counts as known

A sender is **known** if:

- they are in your contacts, or
- they are a number you have sent a text to before.

Messages from known senders always go to your inbox (unless you have blocked them with a rule). Everyone else is an **unknown sender**, and the unknown-sender mode decides what happens.

## Unknown-sender modes

Change the mode in Settings > Spam protection (Messages from unknown senders), or from the Spam tab ("Change").

| Mode | Unknown senders | Verification codes from unknown senders |
|---|---|---|
| **Smart** (default) | Spam | Delivered, if the message has no link |
| **Strict** | Spam | Spam too |
| **Off** | Delivered | Delivered |

Smart is the right choice for most people: banks, delivery services and login codes usually come from numbers that are not in your contacts, and Smart still lets their codes through. A "code" message that also contains a link is treated as spam in Smart, because that is a common phishing trick.

Your own allow and block rules apply in every mode, including Off.

## The Spam tab

Spam does not reach your inbox and does not make a sound. It waits in the **Spam** tab at the bottom of the screen; the tab shows how many arrived since you last looked. You can turn on a notification for spam in Settings > Spam protection > Retention and alerts.

For each message in Spam you can:

- **Not spam**: moves the messages to your inbox and allows the sender from now on, in one step. A snackbar offers Undo. From a chat in Spam it opens the normal chat so you can reply at once. For sender names that anyone can fake (for example `ACME-BANK`) no allow rule is added; the messages only move. To stop allowing a sender, delete the rule in Settings > Rules.
- **Block sender**: adds a block rule for the sender and removes their messages from Spam.
- **Delete** the message.

Each message shows why it was blocked (for example "Unknown sender" or the rule that matched). Settings > Spam protection > **Recent activity** lists the last decisions for all incoming messages, including allowed ones.

Spam is deleted automatically after 30 days. You can change the period, or keep them until you delete them, in Settings > Spam protection > Retention and alerts. A picture message that could not be downloaded (no mobile data, say) shows "Picture message not downloaded" in the chat; tap **Tap to download** to try again. Picture messages (MMS) held as spam are not downloaded, so they cost no mobile data; restoring one allows the sender but cannot bring back the picture.

## Rules

Open Settings > Spam protection > **Rules**. A rule is either **allowed** or **blocked**, and matches one of:

| Type | Matches | Example |
|---|---|---|
| Number | One exact phone number, in any format | `+1 555 000 1234` |
| Prefix | Every number that starts with it | `+4470` |
| Sender name | A sender shown as a name instead of a number | `VM-HDFCBK`, or `HDFCBK` for any prefix |
| Keyword | Any message that contains the word or phrase | `lottery` |
| Pattern | A regular expression, for advanced users | `win.*prize` |

How rules combine:

1. **Allowed rules always win.** If a message matches an allowed rule, it is delivered, even if it also matches a blocked rule.
2. Then blocked rules: a match is blocked, even from a contact.
3. Then known senders, then the mode.

A keyword or pattern rule applies to the sender in both directions: when a blocked keyword blocks a message, its sender is blocked too, and when an allowed keyword lets a message through, its sender is allowed too (contacts and senders that already have their own rule are left alone).

Be careful with allowed keywords: a message from anyone that contains the keyword skips every block. uMessage asks you to confirm allowed keywords and patterns, and refuses very short ones. Keywords also match inside longer words (`win` matches "window").

On phones with two SIMs, each rule can apply to SIM 1, SIM 2 or both.

You can export your rules to a file and import them on another phone. Import shows what it will add before it changes anything, and you can undo it afterwards.

## App lock

Settings > **App lock** asks for a PIN, a pattern or your fingerprint/face before uMessage opens. After too many wrong tries you have to wait before trying again. If you forget the PIN there is no recovery from inside the app.

**What to lock** (on the same page) is Whole app, or Only chosen parts: uMessage then opens without the PIN, and only the Spam tab and your hidden chats ask for it.

**Hide this chat** (thread menu) needs an app lock. A hidden chat leaves the chat list, search, Recent codes, stars, pictures and links, and launcher shortcuts, and its notifications say only "New message". Tap **Hidden chats** under the last conversation and enter your lock to see them; they hide again when you leave uMessage (after the lock time chosen in Settings).

**Mute** (thread menu, or Conversation details) silences a chat for 1 hour, 8 hours, a week or until you unmute it. Its texts stay unread and still appear in the shade, quietly, and so do their verification codes.

Notification privacy is a separate setting: choose whether the lock screen shows the sender, the message text or nothing.

## Forwarding (GitHub and F-Droid builds only)

The Google Play build has no forwarding. In the other builds, Settings > **Forwarding** opens the forward rules, which send a copy of matching inbox messages by SMS to up to three numbers. Each forward is a paid SMS. Spam is never forwarded, and verification codes only when a rule's switch for codes is on.

The **Daily forward limit** (on the Forwarding options page) is off by default. You can set it to 5, 10, 20 or 50 forwards a day; when it is reached, nothing more is forwarded for the rest of the day and uMessage shows a notification. Each rule also has its own hourly cap. The full list of safeguards is in the [README](../README.md#forwarding).

## Notes on messages

Each message can carry one private note, for example to remember what a bank-transaction text was for.

- Long-press a message to select it, then open the menu (the three dots) and choose **Add note**. If the message already has a note the row says **Edit note**.
- The note appears in its own coloured box right under the message. Tap the box to change it.
- In the note window type up to 500 characters. **Save** keeps it, **Cancel** closes the window, **Delete note** (shown only when a note exists) removes it. Saving an empty note also removes it.
- A chat that has notes shows a small note mark in the conversation list.
- The search page has a **Notes** section that lists notes containing your text. Tap one to open its chat.
- Notes are stored only on your phone and are included in the message backups you create. A message that is still scheduled cannot have a note until it is sent. Persian and Arabic notes work right to left.

## Backup and restore

uMessage is **not** included in Android or Google cloud backups, by design: your spam and settings never leave the phone that way. Your normal (allowed) messages are in Android's own SMS storage.

To move messages to a new phone:

1. Settings > **Backup and restore** > **Back up** asks "Back up what?": **Messages**, or **Rules and settings**. Each is saved to its own file, in a place you choose.
2. On the new phone, install uMessage, finish the first-run setup and make it the default SMS app, then open Settings > Backup and restore > **Restore** and choose the same kind (restore is only in Settings, not on the welcome screen).
3. You can also export and import your **rules** alone from the rules screen.

To save a backup to Google Drive, tap **Save to Google Drive** (Settings > **Backup and restore**). You choose what to back up and, for messages, the same options as for a normal backup (including a password). uMessage writes the backup to one file in its private cache and hands it to the Google Drive app, whose "Save to Drive" screen lets you choose the account and the folder; choose a folder there to finish. The file also stays on your phone, hidden from other apps, for about a day until uMessage cleans it up. If a backup is running while you leave the screen, tap its "finished" notification to open Drive. **Restore from Google Drive** opens the file picker in Drive. Without the Drive app installed, both fall back to Android's file picker, where you can pick Google Drive (or any other storage app) yourself. The app itself never connects to the internet: the Google Drive app does the upload and the download. Besides that, uMessage has no Google Drive code of its own and makes no network connections: when you back up or restore, Android's file picker lets you choose Google Drive (or any other storage app on your phone) as the place for the file.

### Moving from another SMS app

Settings > **Backup and restore** > **Move from another app** explains the steps:

1. In your old app, make a backup. In SMS Backup & Restore, tap **Back up now**.
2. Save the backup to Google Drive or to a file on your phone.
3. In uMessage, open that screen and tap **Import messages**, then pick the file. uMessage reads the XML files made by SMS Backup & Restore, as well as its own backups.

A backup of all your chats also holds what uMessage keeps beside the messages: spam (the Spam folder), the recycle bin, drafts, muted chats, hidden chats, saved texts, scheduled messages and reminders, private notes, stars, labels and archived chats. Restoring puts the Spam folder's messages back in the Spam folder only, never in the inbox, and it never sends or shows anything. Messages scheduled for a time that has already passed are not brought back, except repeating ones, which continue at their next time; scheduled messages with attachments and the filter decision log are not in the backup. A backup of only some chats leaves out the Blocked folder, saved texts, scheduled messages and reminders. Hidden chats stay out of sight only while the app lock is on; the lock's PIN is never in a backup. When you back up all your chats while the app lock is on and your hidden chats are locked, uMessage first asks: **Unlock** (your lock screen opens, and the hidden chats go into the backup) or **Skip hidden chats** (they are left out, and the result says how many). If the unlock fails or is cancelled you can try again or skip. With the lock off, or the hidden chats already unlocked, they are included without asking. A backup of chosen chats never holds hidden ones.

Exported files are not encrypted. Anyone who can open the file can read your messages, so keep it somewhere private and delete it when you are done.

## Sharing into uMessage

When you share text, a picture or a file from another app and pick uMessage, a "Share to" screen opens. It starts with
your most recent chats (up to eight, groups included) and lists your contacts below. Tap a chat to open it with the
shared content ready to send, or search by name or number to reach a chat or a contact. Typing a phone number offers
"Send to" for a number you have not saved. Archived chats and chats hidden behind the app lock are not listed.

## Persian and other right-to-left languages

- uMessage is available in 22 languages: English plus Arabic, Bengali, Chinese (Simplified and Traditional), French, German, Hindi, Indonesian, Italian, Japanese, Korean, Persian, Polish, Portuguese (Brazil), Russian, Spanish, Thai, Turkish, Ukrainian, Urdu and Vietnamese. Choose one on the welcome screen or with the Language row in Settings.
- Persian, Arabic and Urdu messages display right-to-left in the Vazirmatn font (Android 10 and later), and the message field switches direction as you type.
- Verification codes are recognised in Persian (for example «رمز پویا: ۱۲۳۴۵» or «کد تأیید»), with Persian or Arabic digits.
- Keyword rules work with Persian text.

## What uMessage does not do

- RCS ("chat features") is not supported; only Google Messages and Samsung Messages can use it. If messages stop arriving after you switch, turn off chat features in your old messaging app.
- Calls are not blocked.
- uMessage never connects to the internet, so it has no cloud spam database. Everything is decided on your phone.

## Getting help

Report problems at https://github.com/MyNameIsGlooboo/uMessage/issues. Do not paste real phone numbers or message text; replace them with made-up ones. Security problems go to the private channel described in [SECURITY.md](../SECURITY.md).
