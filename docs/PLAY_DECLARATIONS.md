# Google Play declarations (owner to-do)

Owner decisions (final): keep the recent photos strip and declare the photo permission; keep exact alarms and file the
exact alarm declaration; keep tap-to-call (`CALL_PHONE`); keep Location. The Google Play build therefore declares the
same permissions as the foss build. Each sensitive permission below needs a declaration in Play Console, App content.
Fill them in before the release goes to review. The texts are honest and short; edit the wording, not the facts.

Sensitive permissions in the manifest: `READ_SMS`, `WRITE_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS` (SMS and Call
Log form); `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED` (Photo and video form); `SCHEDULE_EXACT_ALARM`
(Exact alarms form); `FOREGROUND_SERVICE_DATA_SYNC` (Foreground service form); `ACCESS_COARSE_LOCATION`,
`ACCESS_FINE_LOCATION` (Location); `CALL_PHONE` (no form, but a core-feature check).

## 1. SMS and Call Log (App content > Sensitive app permissions)

- Form answers: core functionality **Default SMS handler**. Call Log permissions: none requested (no `READ_CALL_LOG`,
  `WRITE_CALL_LOG`, `PROCESS_OUTGOING_CALLS`).
- Code: whole app; role request in onboarding (`RoleManager.ROLE_SMS`), receivers in `receivers/`, sending in
  `messaging/`.
- Google may ask for: a video of first launch, the "Set as default SMS app" dialog, receiving a message, sending a
  reply, a spam message going to the Spam tab.
- Risk: low. A complete default SMS app is the listed core use. Fallback: none needed.

## 2. Photo and video permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`)

- Form answers: be honest that this is a convenience, not the core function: select the closest use ("Other") and
  describe the recent photos strip. Do not claim gallery or file-manager status.
- Code: `ui/thread/RecentPhotosAccess.kt` (which permission, which access level), `ui/thread/RecentPhotos.kt` (the row
  of thumbnails), shown from `ui/thread/AttachSheet.kt`; the permission is requested through the row's Allow button.
  The row appears fully only after the user allows it, a picture is read only when tapped, and the system Photo picker
  (`PickVisualMedia` in `ThreadActivity`) works without any permission.
- Google may ask for: a video of the attach menu, the permission prompt, the strip showing, a tap attaching a picture.
- Risk: **real**. Google restricts these permissions to apps whose core function needs persistent media access and
  tells apps that pick pictures occasionally to use the photo picker. An SMS app's recent photos strip may be refused.
- Fallback: remove `READ_MEDIA_IMAGES` and `READ_MEDIA_VISUAL_USER_SELECTED` from the Play build (`tools:node="remove"`
  in `app/src/gplay/AndroidManifest.xml`), make `RecentPhotosRow` return early in that build, and keep the system
  photo picker. The earlier 0.4.2 work (`git log -p -- app/src/gplay` and the removed `PlayPermissions.kt`) did exactly
  that.

## 3. Exact alarms (`SCHEDULE_EXACT_ALARM`)

- Form answers: a user-facing feature that needs a message at an exact time: scheduled send and message reminders,
  both set by the user for a time they pick. It is not an alarm clock, timer or calendar app, so say plainly that it
  is user-scheduled messaging. `USE_EXACT_ALARM` is not declared.
- Code: `messaging/ScheduledMessage.kt` (`scheduleMessage`), `messaging/MessageReminders.kt` (`armReminder`),
  `receivers/ScheduledMessageReceiver.kt` and `receivers/RescheduleAlarmsReceiver.kt` (re-arming after boot),
  `ui/thread/ThreadScheduleUi.kt` (asks once for "Alarms & reminders"), `helpers/NotificationHelper.kt`
  (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`). Without the permission the code already uses `setAndAllowWhileIdle`.
- Google may ask for: a video of scheduling a message for a given time, the exact-alarm setting, the message sending at
  that time.
- Risk: **real**. On Play `SCHEDULE_EXACT_ALARM` is meant for alarm, timer and calendar apps; a messaging app can be
  told its use does not qualify.
- Fallback: remove `SCHEDULE_EXACT_ALARM` from the Play build; scheduled messages and reminders then go out a few
  minutes late (inexact alarms). The code supports this: it checks `canScheduleExactAlarms` everywhere.

## 4. Foreground service (`FOREGROUND_SERVICE_DATA_SYNC`)

- Form answers: type **Data sync**; the user starts it (Export or Restore); it ends when the transfer ends or the user
  presses Stop.
- Code: `services/BackupService.kt` (`startForeground`, Stop action `ACTION_CANCEL`); manifest entry
  `.services.BackupService` with `foregroundServiceType="dataSync"`.
- Google may ask for: a video of Settings > Backup > start an export, leave the app, see the notification, return.
- Risk: low to medium. Data sync is accepted for user-started transfers; the Stop button and visible progress
  notification help. Fallback: run export and restore only while the app is open.

## 5. Location (`ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`)

- Form answers: foreground location only, no background location. Used only to put a map link into the message field
  when the user taps Location in the attach menu: one fix, approximate accepted, nothing stored, nothing sent by the
  app (Data safety: read on tap, not collected).
- Code: `helpers/LocationSharer.kt` (`LocationManagerCompat.getCurrentLocation`), driven by
  `ui/thread/ThreadLocationController.kt`; permission request from `ThreadActivity`.
- Google may ask for: a video of tapping Location, the permission prompt, the link appearing in the message field.
- Risk: low to medium. Foreground, user-triggered location is normally accepted, but it must be a visible feature and
  the privacy policy must name it (it does).
- Fallback: remove both permissions and the Location entry, or hand the user to the system map app to pick a place.

## 6. Tap to call (`CALL_PHONE`)

- Form answers: no special form, and it is not a Call Log permission. It must serve a core feature: tapping a phone
  number in a chat, contact card or conversation details calls it. It is asked at the moment of the tap; if refused,
  the dialer opens with the number filled in.
- Code: `extensions/Activity.kt` (`dialNumber`: checks the number, requests `PERMISSION_CALL_PHONE`, then
  `ACTION_CALL`, else `ACTION_DIAL`), `extensions/DialIntent.kt` (`callIntent`, `isDialableNumber`: only digit-style
  numbers become an intent). Callers: `ThreadConversationActions.kt`, `ConversationDetailsActivity.kt`,
  `MainRoutes.kt`, `VCardViewerActivity.kt`.
- Google may ask for: nothing in the console; a review may ask where the call button is. A short video of tapping a
  number and the call starting covers it.
- Risk: low to medium. Play expects calling only where it is a core feature; for a messaging app that is a stretch
  but common. One stray tap on a scam number can place a call at once, which is the cost of this choice.
- Fallback: delete `CALL_PHONE` and always use `ACTION_DIAL` (the number is filled in and the user presses call).

## 7. Texts to paste

<details>
<summary>Plain texts for the console (click to open, then copy)</summary>

```text
=== SMS: core functionality (Default SMS handler) ===
uMessage is a complete SMS and MMS messaging app. It asks to become the default SMS app during first launch (Android
role dialog) and works only while it holds that role. It receives, stores, shows and sends text and picture messages,
group messages and threads, and filters spam into a Spam tab. SMS permissions are used only for these functions.
Nothing is sent to any server. The app does not request call log permissions.

=== Photo and video permissions (READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED) ===
In a conversation, the attach menu shows a row of the user's most recent pictures so a picture can be attached with
one tap. The app asks for the permission only when the user taps the Allow button in that row. A picture is read only
when the user taps it; by default location and camera details are removed from the copy that is sent. The system
photo picker is always available without this permission. Nothing leaves the phone except the message the user sends.

=== Exact alarms (SCHEDULE_EXACT_ALARM) ===
The user can schedule a text message to be sent at a time they choose, and set a reminder on a message. These are
user-scheduled events: a message sent late (for example a birthday wish at midnight, or a meeting reminder) fails its
purpose. The app asks the user to allow "Alarms & reminders" only when they schedule something. If it is not allowed,
the app uses approximate alarms and still sends. There is no other use of exact alarms.

=== Foreground service (FOREGROUND_SERVICE_DATA_SYNC) ===
uMessage is an SMS app. When the user chooses to export all messages to a file, or restore messages from a backup
file, the work can take minutes on large inboxes. The app runs it in a foreground service (BackupService, type
dataSync) with a visible progress notification and a Stop button, so the transfer keeps going if the user leaves the
app. It starts only after the user taps Export or Restore, and ends when the transfer finishes or is stopped. Nothing
is sent over a network.

=== Location (ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION) ===
Used only when the user taps Location in the attach menu of a conversation: the app reads the position once while it is
open (no background location), approximate is accepted, and puts a map link into the message field. The user presses
Send. The position is not stored and not sent anywhere by the app.

=== CALL_PHONE (if asked) ===
When the user taps a phone number in a conversation, contact card or conversation details, the app calls that number.
The permission is asked at the moment of the tap. If the user refuses, the dialer opens with the number filled in.
The app does not read, write or intercept the call log.
```

</details>

Video notes: one short screen recording per form is enough (portrait, no personal messages visible; use the emulator
or a test SIM with sample numbers). Do not show real names or numbers.

## 8. Foreground service text (saved copy)

Copied from the review notes (F9, item PL-FGS):

> uMessage is an SMS app. When the user chooses to export all messages to a file, or restore messages from a backup
> file, the work can take minutes on large inboxes. The app runs it in a foreground service (BackupService, type
> dataSync) with a visible progress notification and a Stop button, so the transfer keeps going if the user leaves the
> app. It starts only after the user taps Export or Restore, and ends when the transfer finishes or is stopped.
> Nothing is sent over a network.

Video: Settings, Backup, start an export, leave the app, see the notification, open it again.

## 9. Message forwarding (optional feature)

On 8 Nov 2026 a human Google Play Developer Support reviewer confirmed in writing:

> Optional, user-configured, on-device SMS forwarding to numbers the user enters is allowed for the default SMS
> handler. It needs no extra declaration and no Data safety collection entry. The in-app privacy policy and the Play
> Store description must accurately and prominently disclose the feature (optional, runs locally on the device, sends
> by SMS using the user's own carrier plan).

What the app does: off by default; the user creates a rule in Settings > Forwarding; matching incoming messages are
sent as normal SMS to up to three numbers the user typed, from the user's own number, on the user's own plan. Blocked
and Spam messages are never forwarded; verification codes only if that rule's switch is on; a daily limit and an
hourly cap per rule exist. Nothing goes through a server, there is no INTERNET permission, and no SMS permission was
added (SEND_SMS, READ_SMS and RECEIVE_SMS are already declared for the default SMS handler).

Disclosure places: PRIVACY.md (section "Forwarding messages (optional)"), the full description in every language, and
the Forwarding screens in the app (they say each forward is a paid SMS).
