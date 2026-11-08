# Google Play Data safety: prepared answers

For the `gplay` build. Play counts data as **collected** only when it leaves the device; uMessage has no `INTERNET` permission and sends nothing to any server. Recheck this file before every release, and whenever a permission or library is added.

## Form answers

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | Nothing is transmitted by the app. |
| Is all user data encrypted in transit? | Not applicable | No data is sent by the app. |
| Can users request that their data is deleted? | Not applicable | All data is on the device: delete messages in the app, clear app data, or uninstall. Messages themselves live in Android's own SMS database. |
| Is the app's data handling reviewed against a security standard? | No independent review | Do not claim one. |
| Does the app follow the Families policy? | No | Target audience 13+ or 18+. |

## Data on the device (for the reviewer notes, not the form)

- **Messages (SMS, MMS), contacts, phone numbers, SIM information.** Processed only on the phone to receive, send, show and filter messages. Never uploaded. SMS and MMS the user sends go through the carrier, which Google does not count as collection by the app.
- **Location.** Read once, only when the user taps Location in the attach menu, to put a map link into the user's own message. Not stored, not collected, not shared by the app.
- **Photos.** Attached by the user; a copy without GPS and camera details is made by default. Copies in the app cache are deleted after about a day.
- **Spam rules, blocked messages, decision log, settings, app-lock PIN hash.** Private app storage only. Excluded from Android cloud backup and device transfer (`allowBackup="false"`, `data_extraction_rules.xml`).
- **Exports and backups.** Written only where the user chooses; message exports can be password protected. The app never uploads them. "Save to Google Drive" writes the backup to one file in the app cache and hands it to the Google Drive app (an Android share intent restricted to that app); the user then chooses account and folder in Drive, and Drive does the upload. uMessage has no INTERNET permission, no Google sign-in and no Drive API, and receives nothing back, so it still collects and shares no data. The cache file is removed by the app's daily clean-up after 24 hours.
- **No advertising, analytics, crash reporting or third-party SDK that collects data.** The only extra library in the `gplay` build is Google's Play In-App Review, which shows Google's own rating card; uMessage sends no data to do so.

## Permission declarations that go with the form

See [PLAY_DECLARATIONS.md](PLAY_DECLARATIONS.md) (texts to paste, per permission) and [PLAY_STORE.md](PLAY_STORE.md): SMS permissions (default SMS handler), foreground service (data sync, backup), the photo-library permissions (READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED), SCHEDULE_EXACT_ALARM, location and CALL_PHONE. The Play build declares the same permissions as the foss build.

## If something changes

Adding anything that sends data off the phone (crash reports, an online spam list, forwarding in the Play build, cloud backup) makes the first answer **Yes**. Update this file and the form before that release, and PRIVACY.md at the same time.
