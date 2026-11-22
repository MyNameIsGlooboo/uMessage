# Filter pipeline

The phone's own message store (Android's Telephony provider) holds every message, spam included. The filter only
decides where a chat is shown: in **Messages** or in **Spam**. Rules act on the sender, never on one message, so a
chat is always shown whole, in one place. The user can switch to another SMS app or uninstall uMessage and loses no
message.

The filter answers one question for a sender: where does this sender's chat live? The same function sorts a new text
and sorts the chat list, so a text always lands where its chat is. Contract: `SenderJudge` and `SenderVerdict` in
`spam/engine/SenderVerdict.kt`. The chat list is sorted by `ChatSorter` (`spam/ChatSorter.kt`); the chats shown in
Spam are listed in the table `spam_threads` (`conversations.db`).

## Filter order

Checks run in this order. The first one that decides wins.

| # | Check | Chat goes to | Notification |
|---|---|---|---|
| 1 | The sender cannot be read (blank, or the "Unknown" placeholder) | Messages. No automatic rule, no group. | Normal |
| 2 | An allow rule the user made names the sender (number, sender name, prefix) | Messages | Normal |
| 3 | A block rule names the sender (number, sender name, prefix, made by the user or by a blocked word), or Android's own block list or the old Fossify number list has it | Spam, cause BLOCKED | None, ever |
| 4 | The sender is a contact | Messages | Normal |
| 5 | Contacts cannot be read (no permission, a failed lookup) | Messages (fail open) | Normal |
| 6 | The text holds one of the user's allowed words, patterns or websites | Messages. The sender gets an automatic allow rule. | Normal |
| 7 | The text holds one of the user's blocked words, patterns or websites, or a word of the old Fossify list | Spam. The sender gets a block rule. | None |
| 8 | The sender is automatically allowed: a member of a group rule that is on, or allowed earlier by an allowed word | Messages | Normal |
| 9 | The spam filter switch is off | Messages | As the "unknown sender in Messages" setting says |
| 10 | The text is a verification code (`AllowingCode`) and the code group is on | Messages. The sender joins "Senders of a verification code". | Normal, with the code actions |
| 11 | Anything else: unknown sender | Spam, cause UNKNOWN | None by default. With the opt-in option, one quiet notification that opens Spam. |

Checks 4 and 5 are for phone numbers only. A sender name is never a contact, so with contacts unreadable a sender
name is still judged by checks 6 to 11.

**Precedence.** An allow rule the user made wins over a block. A block wins over everything automatic: group
members and allow rules made by an allowed word. "Allow wins over block" holds only for allow rules the user made.

**Blocking moves the whole chat.** Block (left swipe, the selection bar, the chat menu, the notification action, or
"Report spam", which now equals Block) adds a block rule for the sender. It also removes the sender's automatic allow
rule and group membership, moves the chat to Spam at once, cancels its notification and marks it read. Undo puts the
removed rules, memberships and the unread state back. If an allow rule the user made still lets the sender in, the
chat stays in Messages and the snackbar says so.

**Group chats** always stay in Messages. A text from a blocked member arrives there silently; a text from an unknown
member notifies normally.

## How a text is judged

There is one verdict, for the sender. Facts come first:

- Checks 6, 7 and 10 are the only ones that read the text. Each writes a fact about the sender before the message is
  stored: an automatic allow rule, a block rule, or a group membership. The writes are idempotent.
- Then the place of the message is read from the sender alone (checks 1 to 5, 8, 9 and 11). The chat list uses the
  same function, so the text follows the chat.
- A fact that cannot be written is tried once more. If it still fails, the text goes to Messages, unread, with a
  notification (reason `FACT_FAILED`), and the error is logged.
- On a Spam verdict the `spam_threads` row is written before the message.
- **Safety check** after every insert (SMS, MMS, and the replay of the pending queue): a row written read and silent
  must have its chat in `spam_threads`. If not, the row is marked unread and announced.
- A probe (the rule test sheet, the MMS push gate) never writes facts.
- A blocked word can block anyone who is not a contact and has no allow rule made by the user. That includes group
  members; the block rule it writes then wins. With contacts unreadable it blocks nobody (check 5).

## Incoming SMS

```
SMS arrives -> write-ahead row in pending_sms
  history check still running? -> check this sender now
  text checks 6, 7, 10 -> write the sender fact first
  SenderVerdict(sender)
    MESSAGES -> phone's store (unread), message cache, notification, forwarding
    SPAM     -> spam_threads row first (unread + 1)
                phone's store with read = 1, seen = 1; message cache
                "blocked" event and rule hit
                no normal notification, no unarchive, no forwarding
                cause UNKNOWN and the opt-in option on -> one quiet notification
  safety check on the stored row
  any error -> Messages, unread, with a notification (fail open)
  -> delete the pending_sms row
```

Nothing is written to uMessage's own Spam table any more. The ten-second duplicate drop applies to Spam only.

## Incoming MMS (SE-29)

```
MMS push arrives -> sender part of the verdict (the push has no text)
  MESSAGES -> save the notice in the phone's store and download it (as before)
  SPAM     -> save the notice in the phone's store (read, seen)
              within the limits? -> start the same download
              else -> it stays a "Tap to download" row
Download result -> mmslib -> MmsDownloadHandler
  isContentBlocked: always false (mmslib must store the MMS)
  more than one recipient? -> a group: Messages
      unknown member: normal notification; blocked member: silent
  else -> text checks, facts first, SenderVerdict, safety check, as for an SMS
```

**SE-29: a Spam MMS is downloaded, also from a blocked sender.** A group MMS cannot be told apart from a one-to-one
MMS before the download. Not downloading would lose a group message from a blocked or unknown member. The limits
(`BlockedMmsPolicy.spamNoticeDownloads` and the roaming check in `MmsPushReceiver`):

- the announced size is known and at most 1 MiB (an unknown size, 0 or missing, is not downloaded);
- it has not expired;
- the receiving SIM is not roaming.

A Spam MMS outside the limits stays a "Tap to download" row in its chat. A failed download keeps the notice with "Tap
to try again". In a Spam chat a failed download shows no notification and no toast.

## Outgoing: writing to someone allows them

When a text goes out to a number that the filter calls unknown, the number joins "People you wrote to" and the chat
is sorted again. This is caught in two places: uMessage's own send, and the observer of sent messages (for replies
sent by a car kit, a watch or an assistant). Rows written by the forwarder are skipped.

It also counts: a first text to a new number, a "STOP" reply, a quick reply, a scheduled message at its send time,
each unknown recipient of a group text sent as separate SMS, and any send while the filter is off. It does not count
for a number with a block rule: that chat stays in Spam. A failed membership write never fails the send.

## The switch and the two group rules

The old modes (Strict, Smart, Off) are gone. There is one switch, **Spam filter**, on by default.

- **On:** checks 1 to 11 as above. Unknown senders go to Spam.
- **Off:** check 9 ends the list. Everything goes to Messages except chats of blocked senders.

Two fixed group allow rules sit at the top of the allow list: **Senders of a verification code** and **People you
wrote to**. Each has its own switch (both on by default), a count, and a member list with "Remove". A group that is
off is ignored by checks 8 and 10; its members stay stored. Membership is stored, not derived: deleting the code text
or the sent text later does not move the chat. A block rule for a member wins. Turning the code group off gives the
old Strict behaviour: verification codes from unknown senders go to Spam too.

An allowed word allows the sender too, for numbers and for sender names, for good. That automatic allow rule shows in
the chat with one-tap Block, and a block wins over it. A blocked word blocks the sender.

## First sort and history check

After an update or a fresh install, a first run sorts every chat. Nothing later starts before the earlier step ends:

1. Settings: the switch value is derived from the old mode in the same read.
2. Rule migration (`spamless.db` 8 to 9).
3. Older spam migration: messages kept in uMessage's own database by older versions are written to the phone's store,
   if uMessage is the default SMS app. If not, it waits.
4. History check: for each one-to-one chat with an unknown sender, a text the user sent there makes the sender a
   member of "People you wrote to"; else a received verification code makes it a member of "Senders of a verification
   code". Members found this way put no line in old chats.
5. First sort. No chat moves before this point.
6. Only then auto-delete and other clean-ups.

A full-screen progress page shows while this runs, with "Continue in background" after about 15 seconds. The history
check also runs when the switch goes on, after a reinstall or restore, when uMessage becomes the default app again,
and when contacts become readable. A text that arrives while it runs gets the check for its own sender first.

## Settings migration

| Stored before | Becomes |
|---|---|
| Mode Smart or Strict, or no value | Filter on. Both groups on. |
| Mode Off | Filter off. |
| Mode Off with the old Fossify "block unknown numbers" switch on | Filter on. |
| `notify_on_block` | Same key, same value. It now covers unknown senders only. |
| `purge_after_days`, any value | 0 (keep forever), once, on every updated install. |
| `block_outside_region` | No longer read. The "hold numbers from other countries" option is gone. |

Persisted names stay: the old `mode` key remains on disk and is still written (Smart or Off) for older app versions.
A settings backup writes the switch, the two group switches and the group members; `purgeAfterDays` is never taken
from a file. Old `DecisionReason` names stay readable in old log lines.

## Rule types

| Type | Matches |
|---|---|
| NUMBER | Exact number after normalization (stored as E.164 when possible) |
| PREFIX | Normalized number starts with the pattern |
| SENDER_ID | Alphanumeric sender, case-insensitive exact (e.g. `VM-HDFCBK`); without an operator prefix it matches any (`HDFCBK` matches `AD-HDFCBK`) |
| KEYWORD | Case-insensitive substring of the body; it also matches inside longer words (`win` matches "window") |
| REGEX | Java regex (case-insensitive, multiline) against the first 2,000 characters of the body |
| DOMAIN | A link in the body whose host is that domain or one of its subdomains |

Every block and allow rule covers both SIMs. The SIM choice is gone from the rule form; forward rules keep theirs.
The rule migration (`spamless.db` 8 to 9) set the SIM of every spam rule to "both" and merged rules that then became
the same (oldest id kept, hit counts added).

KEYWORD, REGEX and DOMAIN rules read the text, but act on the sender (checks 6 and 7). An allowed keyword or pattern
therefore allows every sender that sends it. The add-rule form refuses allowed keywords shorter than 4 characters and
asks for confirmation before saving any allowed keyword or pattern, with a stronger warning for common words (`bank`,
`code`, `کد`, ...). It also asks before saving a block that an existing allow rule would always override (and then
removes that allow rule), and before saving very broad rules.

REGEX safety (`spam/RegexGuard.kt`): `RegexGuard.validate(pattern)` rejects patterns that are empty, longer than 300
characters, do not compile, match the empty string (they would block everything) or repeat a group that already
repeats (`(a+)+`, `(\w+\s?)*`: catastrophic backtracking). `SpamRepository.addRule` refuses such patterns with
`IllegalArgumentException`, the add-rule form runs the same check while the user types (plus a check that refuses
patterns such as `.` that match every ordinary message), and the filter skips any stored rule that fails validation
instead of risking a hang in the receiver. Compiled patterns live in a bounded LRU cache (64 entries).

## Verification codes

Two readers, on purpose:

- `OtpDetector.looksLikeOtp` (`spam/engine/OtpDetector.kt`) decides what is **shown** as a code: the notification, the
  copy chips, the Codes filter, forwarding, and the "Copy code" button on a Spam row. It is generous: a copy chip on a
  tracking number costs nothing.
- `AllowingCode.isAllowingCode` (`spam/engine/AllowingCode.kt`) decides whether a code **allows** its sender for good
  (check 10). It is stricter: it needs a strong code phrase next to the code ("verification code", "OTP", "کد تایید",
  "رمز یکبار مصرف", ...), a possessive form ("Your code is ..."), or a "do not share" line. Words of a reference number
  next to the code (tracking, order, referral, zip, error; "کد پیگیری", "کد سفارش", ...) veto it. A text with an
  advertising word never allows. Every text that allows is also shown as a code, never the other way round.

A weak code (shown as a code, but not allowing) from an unknown sender stays in Spam, silent; the sender is not
allowed. A code from a blocked sender gets no notification; the chat's row in Spam shows a "Copy code" button for ten
minutes.

Shared rules of the detector:

- Persian (`۰-۹`), Arabic-Indic (`٠-٩`) and every other Unicode decimal digit is normalized to ASCII first.
- Keywords need word edges on both sides in any script (`pin` does not match "shipping", `code` does not match
  "barcode", `کد` does not match "کدام"). Persian possessive endings (`کدتان`) are accepted.
- Link detection (`containsUrl`): `http(s)://`, `www.`, or a `domain.tld/` path such as `bit.ly/x`. A bare
  `@example.com` is not a link, and an origin-bound last line is ignored even when its host starts with `www.`.
- Origin-bound format (`isOriginBound`): the last line is exactly `@host #code` with a dotted host name and a 4-12
  character alphanumeric code, optionally followed by ` @embedded-host`. Persian and Arabic-Indic digits in the code
  are accepted.

## Missing contacts permission

If READ_CONTACTS is denied or revoked, the filter cannot tell a contact from a stranger. Rather than moving everyone
to Spam (family included), phone numbers go to Messages (check 5) and the decision is logged as
`CONTACTS_UNAVAILABLE`. Allow and block rules that name the sender still apply. Sender names (never contacts) are
still judged by checks 6 to 11.

## Rules file

Export writes a header line `# uMessage rules v2` and one rule per line with tab-separated fields `kind, type, sim,
note, pattern`. The `sim` field is written empty; import ignores it, so every imported spam rule covers both SIMs.
Older files with one `kind,type,pattern` per line still import (no note). Import never applies right away: it shows
how many new allowed and blocked rules the file has, how many lines are skipped (duplicates or invalid), and lists
allowed keywords and patterns and broad rules first. After importing, the snackbar can undo the whole import.

The blocked-keywords list (legacy Fossify) exports one keyword per line so keywords with commas stay whole; old
comma-separated files still import. Blank, one-letter and same-but-for-case keywords are dropped. Blocked-numbers
import accepts only phone numbers and `*` wildcards with digits.

## Delivery guarantees

- **Write-ahead:** the receiver stores the SMS in `pending_sms` before filtering and deletes the row only after it was
  inserted in the phone's store. `insertNewSMS` reports failures as id 0; `deliverIncomingSms` turns that into
  `SmsInsertException`, so the row stays and is replayed.
- **No double insert:** only the store insert can fail delivery. Every later step (message cache, conversation,
  archive state, notification) is best effort, so the fallback insert only runs when nothing was stored (and checks
  the store first).
- **Replay** (`PendingSmsQueue.replay`, on the next SMS and on app start) re-processes rows older than 30 s. A row the
  current process is still working on holds a 5-minute lease, so a slow receiver and a replay never process the same
  message at once. Rows older than 7 days skip the filter and are delivered straight to Messages (unless already in
  the store); they are never dropped undelivered. The replay redoes the facts when it finds the row already stored.
- **Fail open:** any Throwable from the filter or from storing a Spam message (including Errors such as
  `ExceptionInInitializerError`) delivers the message to Messages, unread, with a notification, and is logged.

## Edge cases

- **Bank, code and delivery texts** come from non-contacts (`VM-HDFCBK`, short codes). A verification code puts the
  sender in the code group, so its later texts arrive in Messages. Other senders reach Messages with "Not spam".
- **Reversible:** "Not spam" on an unknown sender adds an allow rule the user made and moves the chat to Messages,
  with Undo. On a sender name, which anyone can fake, it asks once first. "Unblock" on a blocked sender removes the
  block rule; if the sender is then still unknown, the chat stays in Spam and the snackbar offers "Not spam". Nothing
  is copied or moved in the store: only the chat's place changes.
- **Older spam:** messages that older versions kept only in uMessage's database (`blocked_messages`) are written to
  the phone's store by the migration once uMessage is the default app. Until then they are listed read-only under
  "Older spam kept only in uMessage". The old copies are deleted 30 days after the last row moved, except rows that
  are the only copy.
- **Number formats:** `+1...`, national forms, spaces, dashes and local vs international all normalize to one key.
  National numbers are read with one fixed list of regions: every SIM's country, then the default region.
- **Contacts change:** a ContentObserver keeps a cached contact set so filtering is instant and works in the
  background. A contacts change re-sorts the chat list after a few seconds.
- **Auto-delete:** off by default ("Keep forever"). With N days, only received texts that arrived while the chat was
  in Spam and are older than N days are deleted, from the phone's store, for every app. Sent, starred and noted texts
  stay. It needs the default SMS role. Other clean-ups skip chats in Spam.
- **Not the default app:** another app then stores everything. uMessage still sorts on screen, but new texts are not
  silenced. Delete in Spam is disabled with a reason.
- **Stats:** the "last 7 days" count moves its window forward every 15 minutes while observed.
- **Notification for unknown senders** (opt-in, "Notify me for texts from unknown senders"): opens Spam, hides sender
  and text on the lock screen, and is never posted for a blocked sender.

## Test coverage target

Every branch above has a unit test: each of the 11 checks and their order, user allow over block, block over
automatic allow and groups, facts first and fail open on a failed fact, the safety check, group chats, both group
switches, the filter switch, the settings and rule migrations, allowing and weak codes (Persian first), missing
contacts permission, number formats and regions, regex guard, the Spam MMS download limits, outgoing joins,
pending-row lease and expiry policy, and auto-delete.
