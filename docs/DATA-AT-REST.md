# Data at rest (DL-42)

Review item DL-42 said saved messages "are not locked inside the phone": someone who copies the app's files off a
rooted phone can read them. This note says what is protected today, what was fixed, and why the databases are not
encrypted by the app itself yet.

## What protects the data today

- **Android file-based encryption.** `conversations.db`, `spamless.db` (blocked messages and their kept MMS
  content), `files/spamless_decisions.log` and the shared prefs live in credential-encrypted app storage. On
  phones with file-based encryption (required for phones that ship with Android 10 or later; uMessage runs from
  Android 8) these files are encrypted with a key that only exists after the user unlocks the phone once after
  boot, so a stolen phone that is off, or has not been unlocked since boot, gives nothing away. Older phones use
  full-disk encryption, or none if the owner never set a screen lock.
- **App sandbox.** While the phone runs, only uMessage (uid) can read the files. Other apps, USB file transfer and
  `adb pull` without root can't.
- **No backups.** `allowBackup="false"`, `fullBackupContent` and `dataExtractionRules` exclude every domain from
  cloud backup and device-to-device transfer (checked in this wave).
- **No message text in logs.** Logcat lines describe failures (exception, counts, file names), never message text
  or numbers; the decision log never holds message text and now keeps entries for 7 days (SU-40).
- **Notifications.** The lock screen shows only the sender by default (SB-16), and Reply, Copy code and Delete ask
  for an unlocked phone. Android's own notification history (if the user turned it on) keeps notification text;
  the app can't opt out of that.
- **Exports.** The export dialog now says the file is not encrypted.
- **Saved texts, SIM names and numbers.** The saved texts list (for example an address or card number the user chose
  to keep) and the nicknames and numbers typed for each SIM are plain text in the app's private preferences
  (`spamless_composer`). They are not encrypted by the app and not uploaded; the app lock does not encrypt them. The
  saved texts dialog says so and advises against saving passwords.
- **Shared pictures.** Pictures are copied to the app cache before sending; the copy has its location and camera tags
  removed (Settings > Messages, on by default). The original is untouched.
- **Starred messages.** A star keeps a copy of the message text in `starred_messages` (conversations.db), so a
  starred message stays when its chat is deleted. The Starred list says so, and Unstar removes the copy.

What this does **not** cover: a phone that is rooted or exploited *while unlocked* can read any app's files, and
Android's SMS database (where allowed messages live) is outside the app anyway, so app-level encryption would only
cover the copies in uMessage's own databases.

## Option: encrypt the databases (SQLCipher)

| | |
|---|---|
| Protection gained | Only against reading copied files on a rooted/exploited unlocked phone. The key would sit in Android Keystore, which the same attacker can usually use while the phone is unlocked, so the gain is small. Allowed messages stay readable in the system SMS database. |
| Migration | Each existing `conversations.db` and `spamless.db` must be exported into an encrypted copy (`sqlcipher_export`) on first start, with the backup/recovery code (`DatabaseBackups`, `MessagesDatabaseRecovery`, `SpamDatabaseRecovery`) taught to handle both formats. A failed migration risks blocked messages and scheduled messages, the data the app exists to keep. |
| Key handling | Keystore-wrapped key; a Keystore reset (some OEMs after a lock-screen change) would make the databases unreadable, so a recovery story is needed. |
| Performance | SQLCipher costs roughly 5-15% on queries and a slower first open (key derivation), on the SMS receive path where time is limited. |
| APK size | About +3-7 MB of native libraries per ABI. |
| F-Droid | Builds from source; the SQLCipher AAR ships prebuilt native code, which F-Droid accepts only when it can rebuild it, which complicates reproducible builds. |

## Recommendation

Do not add database encryption now. Android's file-based encryption plus the sandbox already cover the realistic
cases (lost or stolen phone, other apps, USB, backups), and the remaining case (a rooted or exploited unlocked
phone) is only partly helped by SQLCipher because the key is usable on that same phone. The migration risk to
blocked and scheduled messages, the receive-path slowdown, APK size and the F-Droid build cost outweigh that.

Revisit if uMessage starts keeping message copies outside credential-encrypted storage (for example direct-boot
aware storage so it can filter before the first unlock), or if users ask for a separate app-only passphrase. In
that case, encrypt only the blocked-message bodies with a Keystore key (field-level, no SQLCipher), which avoids
the database migration and native libraries.
