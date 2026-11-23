# uMessage user guide

uMessage is a messaging app for SMS and MMS that keeps spam out of your Messages list. This guide explains how it decides what is spam and how to change that.

## Getting started

The first-run setup has up to three steps:

1. **Welcome.** A language chip sits at the top. Pick your language here, or keep the phone's language; you can change it later with the Language row in Settings. Below it, choose what happens to unknown senders: **Sort unknown senders into Spam** (recommended) or **Keep everything in Messages**. The card above the choice shows how chats are sorted. Two switches follow: **Quiet notification for texts that go to Spam** (off) and a **weekly Spam summary** (on by default). The page also says: Spam sorting is not always right, look at Spam once a week. You can change all of this later in Settings. Tap **Get started**.
2. **Allow.** Make uMessage your default SMS app, and allow Messages (SMS), Contacts, Phone state and Notifications; **Allow all** asks for them in one go. uMessage can only receive and silence messages while it is your default SMS app. Contacts access is how uMessage knows who you know. Without it, uMessage does not move unknown phone numbers to Spam at all, so that it never hides messages from family and friends. This step is left out when uMessage already is the default app and everything is allowed.
3. **Keep uMessage running.** On Xiaomi, Redmi and Poco phones turn on **Autostart** and set **Battery saver** to "No restrictions" for uMessage. On Samsung, remove it from "Sleeping apps". Otherwise the phone may delay incoming messages.

Delivery reports are on by default and show when your messages are delivered. Some carriers may charge for them; switch them off in Settings > Typing and sending > Sending > **Get delivery reports**.

### The first sort

After you install or update uMessage, it sorts your chats once. A progress page shows the steps: your rules, older spam, a check of your old messages, and the sort itself. No chat moves before it is finished, and nothing is deleted. After about 15 seconds you can tap **Continue in background**; a small bar then shows above Messages until it is done.

When it is done, a card on Messages says how many chats are now in Spam, with **Review**. If you updated from an older version, a **What changed** page also shows once.

## Finding your way around

The bottom bar has **Messages** and **Spam**, plus **Archive**, which is always there (it shows an empty state until you archive a chat). A **Forwarded** tab appears in every build as soon as you add a forward rule; you can hide it in Settings > Forwarding ("Show Forwarded tab").

The three-dot menu at the top of Messages lists, in this order: **Recycle bin** (first; it shows how many messages are in it, and is listed only while the recycle bin is switched on in Settings > Storage and cleanup), **Scheduled** (only when something is scheduled), **Starred** and **Settings**.

**Starred** lists the starred messages themselves, one row per message, newest star first: two starred messages from the same sender are two rows. A row shows the chat's name and picture, the start of the message (or "Picture" for a picture message), its time, a star and, when the message has a private note, a note mark. Tap a row to open the chat at that message, even when it is far back in the history. Press and hold a row to select rows and tap the star in the bar to unstar them. The search icon filters the rows by text or name, and the first 500 stars load first, with **Show more** for the rest. The Starred tile on the search page opens the same screen. The star keeps a copy of the text: if the chat was deleted, its rows stay and tapping one shows the saved text.

The app works in portrait and landscape. Turn the phone sideways and every screen follows: content stays clear of a camera cutout and of the navigation bar at the side, message bubbles keep a readable width, and the keyboard leaves the message box usable.

A long text is sent as one multipart SMS, however long, and is never turned into a picture message unless you ask for it. To change that, open Settings > Typing and sending > Sending > **Send long texts as picture message (MMS)** and choose **Never (default)** or a number of messages (2, 3, 4, 5, 6, 8 or 10): a text that needs at least that many SMS is then sent as MMS, which may cost more. Persian, Arabic and other non-Latin text fits fewer characters in each SMS (70, then 67 per part), so it reaches the number sooner. A very long text still shows a warning before it is sent as many SMS.

Under the newest message you sent to a group, the line that says Sent, Delivered or Delivery failed has a small info icon. Tap it to open **Delivery details**, which lists every person with their own state (Sent, Delivered, Not sent or Delivery failed, with the time when it is known). A group picture message has no state for each person: the carrier reports one state, and the sheet says so. A chat with one person has no such icon.

Settings > About has **Send feedback**, which opens your email app with a message to the developer already addressed, and, in the Google Play build only, **Rate uMessage**, which opens the Play Store.

## Where a chat goes

uMessage sorts whole chats, not single messages. Every chat is either in **Messages** or in **Spam**, never split. Every message, spam included, is kept in the phone's own message store, so other SMS apps can see it too.

A sender is **known**, and their chat stays in Messages, if:

- you made an allow rule for them, or
- they are in your contacts, or
- they are on one of the two automatic lists (see below): **People you wrote to** or **Senders of a verification code**.

A sender you **block** goes to Spam, whole chat, even from a contact. Only an allow rule you made yourself beats a block.

Everyone else is an **unknown sender**. With the spam filter on, their chat goes to Spam, silently.

Group chats always stay in Messages. A text from a blocked member arrives there without a sound.

## The spam filter

The spam filter is one switch: Settings > Spam protection > **Spam filter**. The old modes (Strict, Smart and Off) are gone.

- **On** (default): unknown senders go to Spam. Contacts, people you wrote to and senders of a verification code stay in Messages.
- **Off**: everything arrives in Messages, except senders you blocked.

Changing the switch asks first and says how many chats will move. Nothing is deleted.

### The two automatic lists

Two lists sit at the top of your allowed rules (Settings > Spam protection > **Rules**):

- **People you wrote to.** A number you send a text to is allowed from then on. This also counts for a reply sent from your car or watch, a "STOP" reply, and a scheduled message once it is sent. It does not unblock a sender you blocked.
- **Senders of a verification code.** A sender that sends you a verification code is allowed from then on, so its later texts arrive in Messages too. A text that only looks a bit like a code (for example a tracking or order number) does not count.

Each list has its own switch and shows how many senders it holds. Tap it to see them; **Remove** takes a sender off, with Undo. Turning a list off asks first and says how many chats will move; the senders stay on the list and come back when you turn it on again. Turning **Senders of a verification code** off means a code from an unknown sender goes to Spam too, like the old Strict mode.

When a sender was allowed this way, its chat shows a line that says why ("Allowed because you wrote to this number", for example), with **Block** and **Keep**. Keep hides the line. A block always wins over these lists.

Be careful with codes: a scam shaped like "your code is 123456, tap this link" from an unknown sender can reach Messages, so do not tap links you did not expect.

## The Spam tab

Spam does not reach Messages and does not make a sound. It waits in the **Spam** tab at the bottom of the screen. The tab shows how many chats from unknown senders have new texts; opening the tab clears that number. Spam texts are kept in the phone's own message store, marked read.

The Spam tab is a list of chats. Each row shows the newest text and why the chat is there: **Blocked by you** or **Unknown sender**. You can open a chat, read it and reply. Replying to an unknown sender allows them and moves the chat to Messages; a suggested reply asks first ("This allows the sender. Send?").

For a chat in Spam you can:

- **Not spam** (unknown sender): allows the sender with a rule and moves the whole chat to Messages. A snackbar offers Undo. For a sender name that anyone can fake (for example `ACME-BANK`), uMessage asks once before it allows the name. To stop allowing a sender, delete the rule in Settings > Spam protection > Rules.
- **Unblock** (blocked sender): removes your block rule. If the sender is still unknown after that, the chat stays in Spam and the snackbar offers **Not spam**.
- **Block** (unknown sender): adds a block rule, so the chat stays in Spam for good.
- **Delete**: asks first, then deletes the chat from the phone itself, for every app. You can undo right after; later you cannot. Delete works only while uMessage is your default SMS app. There is no "Delete all".

A swipe on a row does **Not spam** or **Unblock**, with Undo. When an unblocked sender is still unknown, the snackbar offers **Not spam** instead of Undo. The menu has **Mark all read**. When the newest text in a Spam chat holds a verification code, the row shows **Copy code** for ten minutes. This is how you reach a code from a blocked sender: uMessage never shows a notification for texts from a blocked sender, not even for a code.

### Blocking a chat

Block a sender from Messages with a left swipe, the selection bar, the chat menu or the **Block** button on a notification. **Report spam** does the same. The whole chat moves to Spam at once, its notification goes away and it is marked read. New texts from that sender arrive silently in Spam. A snackbar offers Undo. If one of your own allow rules still lets the sender in, the chat stays in Messages and the snackbar says so.

### Alerts and auto-delete

There is no notification for each spam text by default. By default you get one quiet summary a week that says how many texts arrived in Spam; turn it off in Settings > Notifications. Settings > Spam protection > **Auto-delete and alerts** has **Notify me for texts from unknown senders**: it posts one quiet notification that opens Spam and hides the sender and text on the lock screen. It is never posted for a sender you blocked.

Spam is kept until you delete it. **Auto-delete old spam** (same page) is off until you choose a number of days. It then deletes texts that arrived in Spam more than that many days ago, from the phone itself, for every app, and they cannot be brought back. Texts you sent, starred or added a note to stay.

Settings > Spam protection > **Recent activity** lists the last decisions for all incoming messages, including allowed ones.

### Picture messages in Spam

A picture message (MMS) in Spam is downloaded like any other, also from a blocked sender, if it is at most 1 MiB and your phone is not roaming. This is because a group picture message cannot be told apart before the download, and a group chat must not be lost. A larger one stays as **Tap to download** in its chat. A picture message that could not be downloaded (no mobile data, say) shows "Picture message not downloaded"; tap it to try again.

### Older spam

Older versions kept spam only inside uMessage. The first sort moves those texts to the phone itself, into the sender's chat. This needs uMessage to be your default SMS app; until then they are listed read-only under **Older spam kept only in uMessage** in the Spam tab.

## Rules

Open Settings > Spam protection > **Rules**. A rule is either **allowed** or **blocked**, and matches one of:

| Type | Matches | Example |
|---|---|---|
| Number | One exact phone number, in any format | `+1 555 000 1234` |
| Prefix | Every number that starts with it | `+4470` |
| Sender name | A sender shown as a name instead of a number | `VM-HDFCBK`, or `HDFCBK` for any prefix |
| Keyword | Any message that contains the word or phrase | `lottery` |
| Pattern | A regular expression, for advanced users | `win.*prize` |
| Website | A link to that site or one of its subdomains | `example.com` |

Every rule acts on the sender, so it moves the sender's whole chat. How rules combine:

1. **Your allowed rules win.** A sender named by an allow rule you made (number, prefix or sender name) stays in Messages, even if a block rule also matches.
2. Then blocked rules: the sender's chat goes to Spam, even from a contact.
3. Then contacts, then the two automatic lists, then the spam filter switch.

A keyword, pattern or website rule reads the text but acts on the sender: when a blocked keyword is in a text, its sender is blocked; when an allowed keyword is in a text, its sender is allowed, for good. Contacts and senders with an allow rule you made are not blocked by a keyword. A sender allowed by a keyword shows a line in its chat with **Block**, and a block wins over it.

Be careful with allowed keywords: anyone who sends a text with the keyword is allowed from then on. uMessage asks you to confirm allowed keywords and patterns, and refuses very short ones. Keywords also match inside longer words (`win` matches "window").

Every allow and block rule covers both SIMs. (Forward rules still have their own SIM choice.)

You can export your rules to a file and import them on another phone. Import shows what it will add before it changes anything, and you can undo it afterwards.

## App lock

Settings > **App lock** asks for a PIN, a pattern or your fingerprint/face before uMessage opens. After too many wrong tries you have to wait before trying again. If you forget the PIN, tap **Forgot it? Use your phone's screen lock**: it accepts your phone's own screen lock instead, opens the app and your hidden chats, and skips the wait. So anyone who knows your phone's screen lock can open uMessage too.

**What to lock** (on the same page) is Whole app, or Only chosen parts: uMessage then opens without the PIN, and only the Spam tab and your hidden chats ask for it. A chat that is in Spam then also asks for the lock when it is opened from a shortcut, a notification or recent apps.

**Hide this chat** (thread menu) needs an app lock. A hidden chat leaves the chat list, search, Recent codes, stars, pictures and links, and launcher shortcuts, and its notifications say only "New message". Tap **Hidden chats** under the last conversation and enter your lock to see them; they hide again when you leave uMessage (after the lock time chosen in Settings).

**Mute** (thread menu, or Conversation details) silences a chat for 1 hour, 8 hours, a week or until you unmute it. Its texts stay unread and still appear in the shade, quietly, and so do their verification codes.

Notification privacy is a separate setting: choose whether the lock screen shows the sender, the message text or nothing.

## Forwarding (optional)

Forwarding is optional and off until you create a rule. It runs on your phone, sends by SMS on your own mobile plan and goes through no server. Settings > **Forwarding** opens the forward rules, which send a copy of matching incoming messages by SMS to up to three numbers. Each forward is a paid SMS. Only texts that land in Messages are forwarded: spam is never forwarded. Verification codes are forwarded only when a rule's switch for codes is on.

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

uMessage is **not** included in Android or Google cloud backups, by design: your rules and settings never leave the phone that way. All your messages, spam included, are in Android's own SMS storage, so a backup made by your phone or another SMS app may include spam texts too.

To move messages to a new phone:

1. Settings > **Backup and restore** > **Back up** asks "Back up what?": **Messages**, or **Rules and settings**. Each is saved to its own file, in a place you choose.
2. On the new phone, install uMessage, finish the first-run setup and make it the default SMS app, then open Settings > Backup and restore > **Restore** and choose the same kind (restore is only in Settings, not on the welcome screen).
3. You can also export and import your **rules** alone from the rules screen.

To save a backup to Google Drive, tap **Save to Google Drive** (Settings > **Backup and restore**). You choose what to back up and, for messages, the same options as for a normal backup (including a password). uMessage writes the backup to one file in its private cache and hands it to the Google Drive app, whose "Save to Drive" screen lets you choose the account and the folder; choose a folder there to finish. The file also stays on your phone, hidden from other apps, for about a day until uMessage cleans it up. If a backup is running while you leave the screen, tap its "finished" notification to open Drive. **Restore from Google Drive** opens the file picker in Drive. Without the Drive app installed, both fall back to Android's file picker, where you can pick Google Drive (or any other storage app) yourself. Besides that, uMessage has no Google Drive code of its own and makes no network connections: when you back up or restore, Android's file picker lets you choose Google Drive (or any other storage app on your phone) as the place for the file.

### Moving from another SMS app

Settings > **Backup and restore** > **Move from another app** explains the steps:

1. In your old app, make a backup. In SMS Backup & Restore, tap **Back up now**.
2. Save the backup to Google Drive or to a file on your phone.
3. In uMessage, open that screen and tap **Import messages**, then pick the file. uMessage reads the XML files made by SMS Backup & Restore, as well as its own backups.

A backup of all your chats also holds what uMessage keeps beside the messages: the recycle bin, drafts, muted chats, hidden chats, saved texts, scheduled messages and reminders, private notes, stars, labels and archived chats. Spam chats are backed up like every other chat. After a restore, each chat is sorted again by your rules and contacts, so restore your rules and settings too if you want blocked senders to stay blocked. Older spam that is still kept only in uMessage goes into the backup as its own entries; restoring them puts them back with the older spam, and they then move to the phone itself like the rest. A restore never sends or shows anything. When the file holds scheduled messages, uMessage says how many, and to how many numbers, and asks before it turns them on. Messages scheduled for a time that has already passed are not brought back, except repeating ones, which continue at their next time; scheduled messages with attachments and the filter decision log are not in the backup. A backup of only some chats leaves out the older spam, saved texts, scheduled messages and reminders. Hidden chats stay out of sight only while the app lock is on; the lock's PIN is never in a backup. When you back up all your chats while the app lock is on and your hidden chats are locked, uMessage first asks: **Unlock** (your lock screen opens, and the hidden chats go into the backup) or **Skip hidden chats** (they are left out, and the result says how many). If the unlock fails or is cancelled you can try again or skip. With the lock off, or the hidden chats already unlocked, they are included without asking. A backup of chosen chats never holds hidden ones.

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
- Calls are not blocked, except from numbers on Android's own block list.
- uMessage never connects to the internet, so it has no cloud spam database. Everything is decided on your phone.

## Getting help

Report problems at https://github.com/MyNameIsGlooboo/uMessage/issues. Do not paste real phone numbers or message text; replace them with made-up ones. Security problems go to the private channel described in [SECURITY.md](../SECURITY.md).
