# Pull request review checklist

Use this for every PR. Tick each box or write why it does not apply. Related docs: [ARCHITECTURE.md](ARCHITECTURE.md), [FILTER_PIPELINE.md](FILTER_PIPELINE.md), [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md), [PLAY_DECLARATIONS.md](PLAY_DECLARATIONS.md), [RELEASE.md](RELEASE.md).

## Privacy and secrets

- [ ] No personal data, real names, email addresses or real phone numbers in code, tests, fixtures, docs, comments or strings. Use neutral samples such as `+15550001234`.
- [ ] No keys, passwords, tokens or keystores, and nothing added to the tree that belongs in `.gitignore`.
- [ ] No message contents, senders or numbers in logs. Log only under the app's own debug tags.
- [ ] Commits use the project's noreply identity and UTC timestamps.

## Permissions and Play

- [ ] No new permission, or the new one is justified and added to [PLAY_DECLARATIONS.md](PLAY_DECLARATIONS.md) and the Data safety notes.
- [ ] No `android.permission.INTERNET` in any flavor (`scripts/check-no-internet.sh` passes).
- [ ] Privacy policy and store text still match what the app stores and sends (forwarding included).

## Persisted names and data

- [ ] `applicationId`, the database name, SharedPreferences names, notification channel ids, intent actions and extras, backup marker and rule-note prefix are unchanged. Renaming any of them loses user data on update.
- [ ] Room schema change: version bumped, migration written, exported schema in `app/schemas/` committed, and a migration test covers the upgrade from the previous version.
- [ ] Backup and restore still work with old backup files. Removed settings are still read on restore.

## Filter pipeline

- [ ] Decision order in [FILTER_PIPELINE.md](FILTER_PIPELINE.md) is respected (allow rules, block rules, contacts, unknown-sender mode). The doc is updated when the order or a rule type changes.
- [ ] The filter fails open: any error delivers the message to the inbox.
- [ ] Regex rules go through the regex guard; no new catastrophic patterns.
- [ ] Blocked or held messages are never forwarded and never downloaded as MMS when blocked.
- [ ] New or changed rules have JVM unit tests, including a contact-exempt case and an exact-number case.

## Verification codes

- [ ] One code detector is used everywhere (notification, list, chat chip, filters, forwarding).
- [ ] "Hide codes on the lock screen" hides the code in notifications, list, search and the Spam tab.
- [ ] The app never clears the clipboard by itself; copied codes stay marked sensitive.

## Strings and layout

- [ ] New text is in default `values/strings.xml` and in every translated locale (or the gap is listed in the PR).
- [ ] Persian text uses everyday spoken words; placeholders and escaping are intact.
- [ ] RTL checked: bubbles, composer, lists, mixed-direction text and numbers (use bidi isolation for counters).
- [ ] Landscape and large font do not clip or hide controls.

## Build and tests

- [ ] `./gradlew :app:testFossDebugUnitTest :app:lintFossDebug detekt :app:assembleFossDebug` passes locally.
- [ ] No new lint baseline entries; no detekt baseline growth.
- [ ] Changelog entry is user-facing and short; version and store changelogs bumped for a release.
- [ ] Anything touching receive, send or the app lock is checked on a real device or the emulator per the device checklist.
