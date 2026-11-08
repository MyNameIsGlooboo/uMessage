# Google Play readiness

A checklist for publishing uMessage on Google Play. Nothing has been submitted. Items marked **Owner** need the owner's Play Console account.

See the forwarding section at the end: the Play build includes optional forwarding (Google confirmed it in writing on 8 Nov 2026).

## Build

- [ ] Upload the `gplay` flavor as an App Bundle: `./gradlew bundleGplayRelease`. It differs from `foss` in `app/src/gplay/res/values/bools.xml` (no donate link, `is_google_play_build = true`, which also shows the Rate row). Forwarding is the same in both flavors.
- [ ] Target SDK: currently 36 (`gradle/libs.versions.toml`). Play requires new apps and updates to target an API level no more than about one year behind the latest Android release; recheck every August, when the yearly deadline usually moves.
- [ ] Min SDK 26 (Android 8.0) is fine for Play.
- [ ] `versionCode` from `gradle.properties` must be higher than any build already uploaded, on any track (see [RELEASE.md](RELEASE.md)).

## Signing (Play App Signing)

Play App Signing is mandatory for new apps. Google holds the app signing key; we sign uploads with an upload key.

- [ ] **Owner:** when creating the app, choose to **use the existing release key** as the app signing key (upload it through Play's encrypted export tool, `pepk`), instead of letting Google generate one. Then GitHub, F-Droid (reproducible) and Play builds all carry the same signature, and users can update between them without losing data. If Google generates a new key, Play builds can never update a GitHub-installed uMessage or the other way round.
- [ ] Create a separate **upload key**, keep it apart from the release key, and register it in the console.
- [ ] Record the app signing certificate SHA-256 shown in the console next to the one in the README.

## Store listing

- [x] Title, short and full description: `fastlane/metadata/android/en-US/` and `fa-IR/` (the Fossify text is gone). Short description 80 characters max, full 4,000, changelog 500.
- [x] Graphics in `fastlane/metadata/android/en-US/images`: `icon.png` 512x512, `featureGraphic.png` 1024x500, `phoneScreenshots` (1080x1920; 8 files; Play needs at least 2 and allows 8). **Owner:** upload them in the console.
- [ ] Category: Communication. Contact email (required). Website: the GitHub repository.
- [x] **Privacy policy URL** (required for apps that use SMS or contacts permissions): https://github.com/MyNameIsGlooboo/uMessage/blob/main/PRIVACY.md (the file exists; enter the URL in the console). It must be reachable without signing in and must name the app.

## SMS permissions declaration

uMessage asks for `READ_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `SEND_SMS` and `WRITE_SMS`. Under Play's SMS and Call Log policy these are allowed only for an app that is the user's **default SMS handler** (or another listed exception). uMessage is a full default SMS app, so it qualifies under that core use.

- [ ] **Owner:** fill in the Permissions Declaration Form in Play Console > App content > Sensitive app permissions:
  - Core functionality: **Default SMS handler**. uMessage is a complete SMS/MMS messaging app (send, receive, threads, MMS, group messages) with spam filtering on top; it does nothing without the default SMS role.
  - Explain that it asks to become the default SMS app during onboarding (`RoleManager.ROLE_SMS`) and works only while it holds that role.
  - Provide a short video showing: first launch, the "Set as default SMS app" system dialog, receiving a message, sending a reply, and a spam message going to the Spam tab.
- [ ] Call Log permissions: **not requested** (no `READ_CALL_LOG`, `WRITE_CALL_LOG` or `PROCESS_OUTGOING_CALLS`). Say "No" to Call Log in the form. uMessage has no call blocking of its own. The only call blocking is Android's system block list (`BlockedNumberContract`), which uMessage can show and edit in Settings > Spam protection > Android block list: numbers on it cannot call the phone, because Android applies that list to calls as well as texts. This needs no Call Log permission. `CALL_PHONE` is declared: tapping a number in a chat or contact calls it at once (`ACTION_CALL`) once the user allows it at that moment; if not allowed, the dialer opens with the number filled in. It is not a Call Log permission and needs no form, but calling must stay a real feature (see PLAY_DECLARATIONS.md).
- [ ] Do not add `READ_CALL_LOG` later for spam features: it would need its own declaration and uMessage is not a default dialer.

Other declarations:

- [ ] `SCHEDULE_EXACT_ALARM`: **declared in all builds, including Google Play.** **Owner:** file the Exact alarm declaration in App content (use case and text in [PLAY_DECLARATIONS.md](PLAY_DECLARATIONS.md)). The app already falls back to inexact alarms (`setAndAllowWhileIdle`) when the user has not allowed exact alarms or Play refuses the declaration.
- [ ] `FOREGROUND_SERVICE_DATA_SYNC`: **Owner:** declare the foreground service type in App content > Foreground service permissions, with what it does (`BackupService`: message export and import keep running with a progress notification when the user leaves the app) and a short video.
- [ ] `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` (Share location): requested at runtime only when the user taps Location in the attach menu, one fix, approximate accepted, used only to insert a map link into the message field. Nothing is stored or sent by the app. No background location. Answer the Location questions in the form as in [PLAY_DECLARATIONS.md](PLAY_DECLARATIONS.md); mention it in the Data safety form as "read on tap, not collected".
- [ ] `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`: **declared in all builds, including Google Play** (the recent photos strip of the attach menu, `ui/thread/RecentPhotos.kt`). **Owner:** file the Photo and video permissions declaration (text in [PLAY_DECLARATIONS.md](PLAY_DECLARATIONS.md)); Google may refuse it, and then the permission is removed and the system Photo picker stays. `READ_EXTERNAL_STORAGE` (maxSdkVersion 32) is in the main manifest; check whether the form asks about it.
- [ ] `READ_CONTACTS`, `READ_PHONE_STATE`: no special form, but they must be covered by the privacy policy (they are, in PRIVACY.md).

## Data safety form

The prepared answers and reviewer notes are in [DATA_SAFETY.md](DATA_SAFETY.md).

Play counts data as **collected** only when it is sent off the device. uMessage has no `INTERNET` permission and sends nothing anywhere, so:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable (no data collected) |
| Do you provide a way for users to request that their data is deleted? | Not applicable; all data is on the device and is deleted by uninstalling, clearing app data, or deleting messages in the app |

Notes for the reviewer field, if asked: location is read once only when the user taps Location, to write a map link into their own message; it is not collected, stored or shared by the app. Messages, contacts and phone numbers are processed only on the device to deliver and filter messages; nothing is transmitted; app data is excluded from Android backups. Backups: the "Save to Google Drive" button only hands a backup file to the Google Drive app through a share intent; the user picks the account and folder in Drive, Drive does the upload, and uMessage has no INTERNET permission, Google sign-in or Drive API. SMS sent by the user go through the carrier network, which Play does not count as collection by the app.

If a future version adds anything that sends data (crash reports, an online spam list, forwarding, ...), this form must be updated before that release.

## Content rating

- [ ] **Owner:** complete the IARC questionnaire. Category: **Communication / messaging**. No violence, sexual content, gambling or drugs in the app itself. Answer **Yes** to "users can interact or exchange content" (it is a messaging app) and **No** to sharing location and to digital purchases. Expected result: Everyone / PEGI 3 with a "Users Interact" note.
- [ ] Target audience: 13+ or 18+ (choosing an audience under 13 adds the Families policy, which is not worth it for an SMS app).
- [ ] Ads: **No**. Government app: No. Financial features: No. Health: No.

## Pre-launch

- [ ] Upload to **internal testing** first; check the pre-launch report (it cannot become the default SMS app, so expect limited coverage).
- [ ] Play's new personal developer accounts must run a **closed test with at least 12 testers for 14 days** before production. **Owner:** plan for this.
- [ ] Check that Play Protect does not flag the bundle (see the warning below).

## In-app rating card

The Google Play build may show Google's own rating card (Play In-App Review, `gplayImplementation` only; the foss build has no such code). The Play Store app shows and handles the card; uMessage sends no data to do this. It is requested at most twice, at least 120 days apart, after at least 7 days since install and 5 days of use, only on the message list. The app stores only a day count and the time of the last request. Data safety answers do not change. The Play build's Settings > About also has a **Rate uMessage** row that opens the Play Store listing (the foss build does not show it); both builds have a **Send feedback** row that opens the user's email app with the public contact address and a subject that carries only the version. **Owner:** after the build, check the merged gplay manifest for any permission the library adds (none is expected, and `INTERNET` must stay absent).

## Optional message forwarding: decision

Until 0.4.2 the Play build shipped without forwarding, as a precaution. On 8 Nov 2026 a human Google Play Developer Support reviewer confirmed in writing that optional, user-configured, on-device SMS forwarding to numbers the user enters is allowed for the default SMS handler, needs no extra declaration and no Data safety collection entry, provided the in-app privacy policy and the store description accurately and prominently disclose it (optional, runs locally, sends by SMS on the user's own carrier plan). The owner approved enabling it. From 0.4.3 both flavors have the same forwarding code path.

Disclosure: PRIVACY.md section "Forwarding messages (optional)" and a paragraph in every full_description.txt. The safeguards stay: off by default, per rule, blocked/Spam messages never forwarded, verification codes only when the rule's own switch is on, daily limit, hourly cap per rule, app lock confirmation for changes, a local log. Details: [PLAY_DECLARATIONS.md](PLAY_DECLARATIONS.md) section 9 and [DATA_SAFETY.md](DATA_SAFETY.md).
