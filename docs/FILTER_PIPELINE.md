# Filter pipeline

The pipeline runs inside the SMS_DELIVER receiver (and WAP_PUSH_DELIVER for MMS) before anything touches the system inbox. Contract: `SpamFilter.evaluate(IncomingMessage): FilterDecision` in `spam/SpamContracts.kt`. Implementation: `spam/engine/DefaultSpamFilter.kt`.

```
incoming SMS PDUs
  -> assemble multipart, read sender + subscriptionId (SIM)
  -> write-ahead row in pending_sms
  -> normalize sender (libphonenumber; region of the receiving SIM first, then the default region)
  1. Allow rule match (ListKind.ALLOW)?     -> ALLOW  (ALLOW_RULE, always wins, also over block rules)
  2. Block rule match (ListKind.BLOCK)?     -> BLOCK  (BLOCK_RULE; number / prefix / sender id / keyword / regex,
                                                         plus the legacy Fossify blocked numbers and keywords)
  3. Known sender: in contacts?             -> ALLOW  (CONTACT)
     a number you have sent a text to?      -> ALLOW  (KNOWN_CONVERSATION)
  4. Sender blank (could not be read), or a
     phone number whose contacts lookup
     failed or cannot run (no READ_CONTACTS),
     mode not OFF?                          -> ALLOW  (CONTACTS_UNAVAILABLE)
  5. Unknown-sender mode:
       SMART  -> ALLOW (OTP_EXEMPTION) if the body looks like an OTP (a link in the body does not matter),
                 else BLOCK (UNKNOWN_SENDER)
       STRICT -> BLOCK (UNKNOWN_SENDER), verification codes included
       OFF    -> ALLOW (MODE_OFF), or BLOCK (UNKNOWN_SENDER) when the old "Block unknown numbers"
                 switch is on: it turns Off into Strict and has no effect in Strict or Smart
  ALLOW -> write to Telephony provider, notify
  BLOCK -> store only in the uMessage DB (Spam folder); silent unless notifyOnBlock
  filter or store failure (any Throwable) -> deliver to the inbox (fail open)
  -> delete the pending_sms row
```

## Rule types

| Type | Matches |
|---|---|
| NUMBER | Exact number after normalization (stored as E.164 when possible; a SIM-scoped rule is normalized with that SIM's country) |
| PREFIX | Normalized number starts with the pattern |
| SENDER_ID | Alphanumeric sender, case-insensitive exact (e.g. `VM-HDFCBK`); without an operator prefix it matches any (`HDFCBK` matches `AD-HDFCBK`) |
| KEYWORD | Case-insensitive substring of the body; it also matches inside longer words (`win` matches "window") |
| REGEX | Java regex (case-insensitive, multiline) against the first 2,000 characters of the body |

Each rule may be scoped to one SIM (`subscriptionId`) or both (`null`).

An ALLOW keyword or regex lets any message that contains it skip every block rule, from any sender. The add-rule form therefore refuses allowed keywords shorter than 4 characters and asks for confirmation before saving any allowed keyword or pattern, with a stronger warning for common words (`bank`, `code`, `کد`, ...). It also asks before saving a block that an existing allow rule would always override (and then removes that allow rule), and before saving very broad rules.

REGEX safety (`spam/RegexGuard.kt`): `RegexGuard.validate(pattern)` rejects patterns that are empty, longer than 300 characters, do not compile, match the empty string (they would block everything) or repeat a group that already repeats (`(a+)+`, `(\w+\s?)*`: catastrophic backtracking). `SpamRepository.addRule` refuses such patterns with `IllegalArgumentException`, the add-rule form runs the same check while the user types (plus a check that refuses patterns such as `.` that match every ordinary message), and the filter skips any stored rule that fails validation instead of risking a hang in the receiver. Compiled patterns live in a bounded LRU cache (64 entries).

## Modes

| Mode | Behaviour |
|---|---|
| Smart (default for new installs) | Unknown senders are blocked, except that a message that looks like an OTP (see below) is allowed, with or without a link. An origin-bound last line (`@host #code`, as used by WebOTP and Android's SMS User Consent and Retriever APIs) is still not counted as a link by the link test, which the Spam tab uses. Nothing is held for review: everything else goes to the Blocked folder. |
| Strict | Every unknown sender is blocked, verification codes included. There is no code exception. |
| Off | No unknown-sender filtering. Allow and block rules still apply. |

Settings (`SpamSettings`): mode = SMART, purgeAfterDays = 30 (0 = keep forever), notifyOnBlock = false. The stored `otpExemption` field is kept for old installs but no longer has a switch and does not change any mode: Smart always lets codes through, Strict never does. Since 0.4.2 (owner decision) a code with a link ("verification code ... confirm at bit.ly/x") is let through in Smart too; Strict still holds it. Risk: a scam shaped like "your code is 123456, tap this link" from an unknown sender reaches the inbox. A bare link with a prize word, a phone number with a link and a number without a code word are still not codes.

The old Fossify "Block unknown numbers" switch (on the Blocked numbers screen) used to run before the mode and block codes even in Smart. It now only matters in Off mode, where it makes Off behave like Strict; Strict and Smart ignore it, and the screen says so.

### OTP heuristic (`spam/engine/OtpDetector.kt`)

- Persian (`۰-۹`), Arabic-Indic (`٠-٩`) and every other Unicode decimal digit is normalized to ASCII first.
- A 4-8 digit code must sit next to an OTP keyword: in either order, with at most four plain words (letters only) in between. "Your OTP is 4821", "123456 is your login code" and "رمز پویا: ۱۲۳۴۵" match; "Use code SAVE20, call 12345678" does not, because `SAVE20` breaks the chain.
- Keywords: English (`otp`, `code`, `passcode`, `password`, `pin`, `verification`, `verify`, `one-time`, `authentication`, `security code`, `2fa`), Persian (`رمز`, `رمز پویا`, `رمز عبور`, `رمز یکبار مصرف`, `کد`, `کد تایید`, `تایید`, `یکبار مصرف`, ...) and Arabic (`رمز التحقق`, `كلمة المرور`, `كود`, ...). Persian possessive endings (`کدتان`) are accepted.
- Keywords need word edges on both sides in any script (`pin` does not match "shipping", `code` does not match "barcode", `کد` does not match "کدام").
- Link detection (`containsUrl`): `http(s)://`, `www.`, or a `domain.tld/` path such as `bit.ly/x`. A bare `@example.com` is not a link, and an origin-bound last line is ignored even when its host starts with `www.`.
- Origin-bound format (`isOriginBound`): the last line is exactly `@host #code` with a dotted host name and a 4-12 character alphanumeric code, optionally followed by ` @embedded-host`. Persian and Arabic-Indic digits in the code are accepted.

### Missing contacts permission

If READ_CONTACTS is denied or revoked, the filter cannot tell a contact from a stranger. Rather than silently blocking everyone (family included), unknown-sender blocking (Strict and Smart) is skipped for phone numbers and the decision is logged as `CONTACTS_UNAVAILABLE`. Allow/block rules still apply, and alphanumeric sender ids (never contacts) are still filtered by the mode.

## Rules file

Export writes a header line `# uMessage rules v2` and one rule per line with tab-separated fields `kind, type, sim, note, pattern` (sim empty = both SIMs). Older files with one `kind,type,pattern` per line still import (SIM = both, no note). Import never applies right away: it shows how many new allowed and blocked rules the file has, how many lines are skipped (duplicates or invalid), and lists allowed keywords and patterns and broad rules first. After importing, the snackbar can undo the whole import.

The blocked-keywords list (legacy Fossify) exports one keyword per line so keywords with commas stay whole; old comma-separated files still import. Blank, one-letter and same-but-for-case keywords are dropped. Blocked-numbers import accepts only phone numbers and `*` wildcards with digits.

## Delivery guarantees

- **Write-ahead:** the receiver stores the SMS in `pending_sms` before filtering and deletes the row only after it was stored as blocked or inserted in the Telephony provider. `insertNewSMS` reports failures as id 0; `deliverIncomingSms` turns that into `SmsInsertException`, so the row stays and is replayed.
- **No double insert:** only the provider insert can fail `deliverIncomingSms`. Every later step (message cache, conversation, archive state, notification) is best effort, so the fallback insert in `IncomingSmsProcessor.deliver` only runs when nothing was stored (and checks the inbox first).
- **Replay** (`PendingSmsQueue.replay`, on the next SMS and on app start) re-processes rows older than 30 s. A row the current process is still working on holds a 5-minute lease, so a slow receiver and a replay never process the same message at once. Rows older than 7 days skip the filter and are delivered straight to the inbox (unless already in the inbox or the Blocked folder); they are never dropped undelivered.
- **Fail open:** any Throwable from the filter or from storing a blocked message (including Errors such as `ExceptionInInitializerError`) delivers the message to the inbox and is logged.

## Edge cases

- **Bank/OTP/delivery messages** come from non-contacts (`VM-HDFCBK`, short codes). Mitigations: onboarding explanation, one-tap "Not spam" in the Blocked folder (restores and allows the sender), Smart mode (the default) for their codes.
- **Reversible:** blocked messages are kept until purged. `markNotSpam` inserts the message into the real inbox and adds an allow rule for the sender (phone number rule, or sender-id rule) in the same step, with one Undo. For alphanumeric sender names that anyone can fake no rule is added, the messages only move. To stop allowing a sender, delete the rule in Settings > Rules. It is idempotent: the blocked row is claimed and copied to `pending_sms` in one transaction, so a double tap or a crash mid-restore can neither duplicate nor lose the message. `blockSender` adds a BLOCK rule and deletes that sender's blocked messages.
- **Blocked MMS:** a push-blocked MMS is never downloaded (no data spent), so only a placeholder is stored and `BlockedMessage.canRestoreContent` is false: "Not spam" then only allows the sender (nothing is restored). An MMS blocked by its content keeps its text, and "Not spam" restores that text as an SMS (the media is not kept).
- **Number formats:** `+91...`, `0...`, spaces, dashes and local vs international all normalize to one key. National numbers are read in the country of the SIM that received them, then in the default region, so dual-SIM phones with SIMs from two countries match either way.
- **Contacts change:** a ContentObserver keeps a cached contact set so filtering is instant and works in the background.
- **Purge:** expired blocked messages are deleted at most every 6 hours from the receivers and app start (`PurgePolicy`); opening the Blocked screen purges immediately.
- **Stats:** the "last 7 days" count moves its window forward every 15 minutes while observed.
- **Notification on block** (opt-in): opens the Spam folder, uses its own notification tag (no clash with message notifications) and hides sender and text on the lock screen.

## Test coverage target

Every branch above has a unit test: contact, whitelist-wins-over-blacklist, blacklist kinds, OTP handling per mode (code alone, code with a link in Strict, Smart and Off, where Smart now delivers it, origin-bound, Persian), OTP heuristic (Persian/Arabic digits and keywords, word edges, adjacency, links, origin-bound line), missing contacts permission, number formats, per-SIM scope and region, regex guard, each mode, pending-row lease and expiry policy, purge throttle.
