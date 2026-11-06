# Security policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue.

- Use GitHub's private vulnerability reporting: https://github.com/MyNameIsGlooboo/uMessage/security/advisories/new
- Describe the problem, the uMessage version (Settings > About > Version), the phone model and Android version, and steps to reproduce.
- Do not include real phone numbers, message text or your decision log. Use made-up examples.

You should get a first answer within 7 days. Once a fix is released, the advisory is published with credit to the reporter unless you ask not to be named. Please give us a reasonable time to fix the problem (normally up to 90 days) before you disclose it.

Examples of what to report: a way to open the app past the app lock; another app reading or changing uMessage's messages, rules or blocked messages; a crafted SMS or MMS that crashes the receiver, hides messages from contacts, or makes the app lose a message; a way to make the app request or use network access.

Not security issues: spam that gets through (open a normal issue with a masked example), and problems that need a rooted or already-unlocked phone in an attacker's hands.

## Supported versions

Only the latest release gets security fixes. Please update before reporting.

| Version | Supported |
|---|---|
| Latest release on GitHub Releases | Yes |
| Older releases, debug and daily builds | No |

## Security model

- **No network access.** The app does not declare the `INTERNET` permission, so Android does not let it open network connections. CI and the release workflow fail any APK that requests it (`scripts/check-no-internet.sh`).
- **Data stays on the device.** Allowed messages live in Android's SMS database. Blocked messages, rules, settings, a contact-number cache and the decision log live in uMessage's private app storage. There are no accounts, analytics, ads or crash reporting.
- **Encryption at rest** comes from Android's file-based encryption of private app storage; uMessage does not add its own database encryption. Details and the reasoning: [docs/DATA-AT-REST.md](docs/DATA-AT-REST.md).
- **Backups disabled.** `allowBackup="false"` plus data-extraction rules keep all app data out of Android/Google cloud backup and device-to-device transfer, including the app-lock PIN hash. Manual message exports are plain, unencrypted files; store them carefully.
- **App lock (optional).** A PIN, pattern, or fingerprint/face through Android's biometric prompt. Too many wrong attempts lock it for a while. The lock protects the app's screens; it does not encrypt data, and notifications follow the lock-screen visibility setting.
- **Lock-screen privacy.** By default notifications on a locked phone show only the sender. Reply, Copy code and Delete from a notification ask for the phone to be unlocked first (Android 12 and later).
- **Fail open.** If the spam filter or storing a blocked message fails, the message goes to the inbox. A bug may let spam through but should never hide a message.
- **Exported components.** The SMS and MMS receivers and the respond-via-message service that the default SMS app role needs are protected by the system permissions Android defines for them (`BROADCAST_SMS`, `BROADCAST_WAP_PUSH`, `SEND_RESPOND_VIA_MESSAGE`). Apart from the launcher and the compose/share entry points, internal receivers and screens are not exported. `app/src/main/AndroidManifest.xml` is the reference.
- **Untrusted input.** Incoming SMS/MMS content is treated as untrusted. User regex rules are checked against catastrophic backtracking and limited in length before they run (`spam/RegexGuard.kt`).

Permissions and what each is used for are listed in [PRIVACY.md](PRIVACY.md).
