# uMessage user guide

uMessage is a messaging app for SMS and MMS that keeps spam out of your inbox. This guide explains how it decides what is spam and how to change that.

## Getting started

1. Open uMessage and tap **Set as default SMS app**. uMessage can only receive and filter messages while it is your default SMS app.
2. Allow SMS, Contacts, Phone and Notifications. Contacts access is how uMessage knows who you know. Without it, uMessage does not block unknown phone numbers at all, so that it never hides messages from family and friends.
3. On Xiaomi, Redmi and Poco phones turn on **Autostart** and set **Battery saver** to "No restrictions" for uMessage. On Samsung, remove it from "Sleeping apps". Otherwise the phone may delay incoming messages.

## Who counts as known

A sender is **known** if:

- they are in your contacts, or
- they are a number you have sent a text to before.

Messages from known senders always go to your inbox (unless you have blocked them with a rule). Everyone else is an **unknown sender**, and the unknown-sender mode decides what happens.

## Unknown-sender modes

Change the mode in Settings > Spam protection > Spam settings, or from the Blocked tab ("Change").

| Mode | Unknown senders | Verification codes from unknown senders |
|---|---|---|
| **Smart** (default) | Blocked | Delivered, if the message has no link |
| **Strict** | Blocked | Blocked too |
| **Off** | Delivered | Delivered |

Smart is the right choice for most people: banks, delivery services and login codes usually come from numbers that are not in your contacts, and Smart still lets their codes through. A "code" message that also contains a link is treated as spam in Smart, because that is a common phishing trick.

Your own allow and block rules apply in every mode, including Off.

## The Blocked tab

Blocked messages do not reach your inbox and do not make a sound. They wait in the **Blocked** tab at the bottom of the screen; the tab shows how many arrived since you last looked. You can turn on a notification for blocked messages in the spam settings.

For each blocked message you can:

- **Not spam**: moves the message to your inbox and allows the sender from now on.
- **Block sender**: adds a block rule for the sender and removes their blocked messages.
- **Delete** the message.

Each message shows why it was blocked (for example "Unknown sender" or the rule that matched). Settings > Diagnostics > **Decision log** lists the last decisions for all incoming messages, including allowed ones.

Blocked messages are deleted automatically after 30 days. You can change the period, or keep them until you delete them, in the spam settings. Blocked picture messages (MMS) are not downloaded, so they cost no mobile data; restoring one allows the sender but cannot bring back the picture.

## Rules

Open Settings > Spam protection > **Allowed and blocked rules**. A rule is either **allowed** or **blocked**, and matches one of:

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

Be careful with allowed keywords: a message from anyone that contains the keyword skips every block. uMessage asks you to confirm allowed keywords and patterns, and refuses very short ones. Keywords also match inside longer words (`win` matches "window").

On phones with two SIMs, each rule can apply to SIM 1, SIM 2 or both.

You can export your rules to a file and import them on another phone. Import shows what it will add before it changes anything, and you can undo it afterwards.

## App lock

Settings > Privacy and security > **App lock** asks for a PIN, a pattern or your fingerprint/face before uMessage opens. After too many wrong tries you have to wait before trying again. If you forget the PIN there is no recovery from inside the app.

**What to lock** (on the same page) is Whole app, or Only chosen parts: uMessage then opens without the PIN, and only the Blocked tab and your hidden chats ask for it.

**Hide this chat** (thread menu) needs an app lock. A hidden chat leaves the chat list, search, Recent codes, stars, pictures and links, and launcher shortcuts, and its notifications say only "New message". Tap **Hidden chats** under the last conversation and enter your lock to see them; they hide again when you leave uMessage (after the lock time chosen in Settings).

**Mute** (thread menu, or Conversation details) silences a chat for 1 hour, 8 hours, a week or until you unmute it. Its texts stay unread and still appear in the shade, quietly, and so do their verification codes.

Notification privacy is a separate setting: choose whether the lock screen shows the sender, the message text or nothing.

## Backup and restore

uMessage is **not** included in Android or Google cloud backups, by design: your blocked messages and settings never leave the phone that way. Your normal (allowed) messages are in Android's own SMS storage.

To move messages to a new phone:

1. Settings > Backup and restore > **Export messages** saves them to a file you choose.
2. On the new phone, install uMessage, make it the default SMS app, then **Import messages** from that file.
3. Export and import your **rules** separately from the rules screen.

Exported files are not encrypted. Anyone who can open the file can read your messages, so keep it somewhere private and delete it when you are done.

## Sharing into uMessage

When you share text, a picture or a file from another app and pick uMessage, a "Share to" screen opens. It starts with
your most recent chats (up to eight, groups included) and lists your contacts below. Tap a chat to open it with the
shared content ready to send, or search by name or number to reach a chat or a contact. Typing a phone number offers
"Send to" for a number you have not saved. Archived chats and chats hidden behind the app lock are not listed.

## Persian and other right-to-left languages

- Persian and Arabic messages display right-to-left in the Vazirmatn font (Android 10 and later), and the message field switches direction as you type.
- Verification codes are recognised in Persian (for example «رمز پویا: ۱۲۳۴۵» or «کد تأیید»), with Persian or Arabic digits.
- Keyword rules work with Persian text.

## What uMessage does not do

- RCS ("chat features") is not supported; only Google Messages and Samsung Messages can use it. If messages stop arriving after you switch, turn off chat features in your old messaging app.
- Calls are not blocked.
- uMessage never connects to the internet, so it has no cloud spam database. Everything is decided on your phone.

## Getting help

Report problems at https://github.com/MyNameIsGlooboo/uMessage/issues. Do not paste real phone numbers or message text; replace them with made-up ones. Security problems go to the private channel described in [SECURITY.md](../SECURITY.md).
