# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Changed
- Sharing into uMessage from another app now opens a "Share to" picker that starts with your recent chats (groups included) above the contacts; the search field filters both, and tapping a chat opens it with the shared text and files ready to send

## [0.3.0] - 2026-11-06
### Added
- Google Play build (`gplay` flavor) without automatic forwarding; GitHub and F-Droid builds keep it
- Store texts and changelogs rewritten for Google Play

### Changed
- The modes now differ as described: Smart lets verification codes without a link through, Strict blocks them too. The separate "Allow verification codes" switch is gone
- The old "Block unknown numbers" switch on the Blocked numbers screen no longer overrides Smart; it only makes Off behave like Strict, and its description says so
- Numbers you have texted count as known senders, like contacts
- Adding a rule checks it before the sheet closes: unsafe or catch-all patterns are refused with a message, a failed save keeps your text, and allowed keywords, broad rules and blocks of allowed values ask you to confirm
- Exported rules keep their SIM and note; importing shows a preview first and can be undone
- Blocked keywords keep commas when exported, blank and duplicate keywords are dropped, and the keyword dialog survives rotation
- Blocked-numbers import accepts only phone numbers and says how many entries it skipped

## [0.2.0] - 2026-11-01
### Added
- Bottom navigation with Messages, Blocked and Archived tabs; the Blocked tab shows how many messages were blocked since you last opened it
- New lock screen with dark mode, a wrong-PIN countdown and rotation support
- Emoji picker in the message field
- Undo on every action in the blocked messages and decision log screens
- Dark versions of the Sky and Sunset themes
- Blocked screen: a button that opens the blocking settings, and the current unknown-sender mode with "Change"
- Default SIM setting inside the app: same as phone, SIM 1, SIM 2 or ask every time
- SIM badge on the send button; long-press it to pick a SIM for one message
- Relative date separators in threads (Today, Yesterday, weekday)
- Shared-axis screen transitions and predictive back
- New warm amber launcher icon with a white shield-bubble mark, with a matching splash screen and themed icon

### Changed
- "Allow verification codes" is now on by default for new installs, so login codes from unknown senders are not blocked in Strict mode
- All screens and dialogs rebuilt in Jetpack Compose Material 3 with Pixel-style grouped settings and a large collapsing top bar
- Google Sans Flex and Roboto fonts, Material Symbols icons, filled send icon
- Right-to-left messages and the message field now align and flow right-to-left
- Text keeps its full Material 3 line height, centred, so rows, buttons and chips match the spec and Persian and English lines are the same height
- Persian and Arabic text uses the bundled Vazirmatn font (Android 10 and later) instead of the device's fallback font
- Removed the SIM label from the thread top bar and the "SMS" label from the message field
- Smart is now the default unknown-sender mode for new installs
- The conversation list rebuilds in about 1 second instead of 10, newest threads first
- The app opens straight to the conversation list; the splash screen stays until the list is ready (about 0.4 seconds on a phone)
- Material You is the default theme
- Settings open from a button in the search bar instead of the three-dot menu; the Recycle bin opens from Settings
- Font size and date and time format now follow the system settings
- Message field: no separate gallery button (Photo and Video are under "+"), and the icons are evenly spaced
- The Rules screen hides SIM choices on phones with one SIM

### Fixed
- Scrolling the conversation list no longer swipes or selects rows by accident
- Dialogs now close and release their composition
- Threads no longer show as unread after all their messages are read (seen on Xiaomi phones)
- "Mark unread" now sticks on threads that only contain sent messages

### Security
- App data is no longer included in Android/Google backups or device-to-device transfer: blocked messages, conversations database, decision log and settings (including the app-lock PIN hash) stay on the phone
- The MMS download receiver is no longer exported, so other apps cannot make uMessage read a file or insert a fake MMS
- Main, Archived and Recycle bin screens can no longer be opened directly by other apps
- Removed the unused `READ_SYNC_SETTINGS` permission and a bogus `SMS_RECEIVED` permission entry
- `USE_BIOMETRIC` is declared again, so biometric app unlock does not rely on the deprecated fingerprint permission
- "Allow verification codes" no longer lets a code message with a link through, a common phishing shape; such messages follow the normal Strict or Smart decision. Codes in the standard origin-bound format (last line `@example.com #123456`) are still allowed
- Scheduled messages are re-armed when the exact-alarm permission is granted again
- App can no longer be moved to external storage (it must receive boot broadcasts as the SMS app)

### Build
- CI also builds the minified release APK (unsigned) and lints the release variant on every push; detekt runs as a report
- Release workflow fails instead of publishing an unsigned APK, and passes signing secrets through the environment
- JitPack limited to the groups that need it; `mavenLocal()` removed
- Room schemas are committed under `app/schemas/`
- Gradle build cache, parallel execution and configuration cache enabled; build script cleanups; unused app viewBinding turned off; `androidx.core:core-splashscreen` 1.2.0 added
- Compose BOM 2025.09.01 to 2026.06.01
- Stale lint and detekt baseline entries removed
- Issue templates, CONTRIBUTING and CODEOWNERS now point to this project

## [1.10.2] - 2026-10-21
### Changed
- Updated translations

## [1.10.1] - 2026-10-10
### Changed
- Updated translations

### Fixed
- Fixed startup crash on Android 11 and below

## [1.10.0] - 2026-10-10
### Added
- Added inline audio player for MMS attachments ([#261])

### Changed
- Improved error messages when a message fails to send ([#315])
- Updated translations

### Fixed
- Fixed exposed action receivers for security ([#834])
- Fixed group message creation on Xiaomi devices ([#41])
- Fixed incorrect SMS/MMS deletion from notifications in rare cases
- Fixed MMS images stuck on "Sending" when sent together ([#185])
- Partially fixed MMS image sending failures ([#45])

## [1.9.1] - 2026-07-19
### Changed
- Updated translations

### Fixed
- Fixed missing private contact names in message list and threads ([#829])

## [1.9.0] - 2026-07-12
### Added
- Added a group message format choice on the first group send ([#52])

### Changed
- Updated translations

### Fixed
- Partially fixed issue with sending MMS images ([#45])
- Fixed slow loading of the conversation list ([#234])

## [1.8.1] - 2026-07-09
### Changed
- Updated translations

### Fixed
- Fixed messages being sent to the wrong contact ([#615])
- Fixed incomplete message exports ([#713])
- Fixed crash when viewing older messages
- Other stability improvements

## [1.8.0] - 2026-01-30
### Added
- Added support for custom fonts
- Added "Copy number to clipboard" option inside chat overflow menu ([#651])

### Changed
- Improved multi-message copy formatting with timestamps and sender names
- Updated translations

### Fixed
- Fixed missing notifications in some cases ([#159])
- Fixed incorrect blocking of MMS messages in some rare cases ([#644])
- Fixed issue with importing alphanumeric blocked numbers ([#282])
- Fixed issue where scheduled messages were not sent after a reboot or app updates ([#641])

## [1.7.0] - 2025-12-16
### Added
- Ability to select and copy multiple text messages at once ([#600])

### Changed
- Updated translations

### Deprecated
- Deprecated the recycle bin feature ([#290])

### Fixed
- Fixed new conversation shortcut ([#416])
- Fixed blocking MMS messages from unknown numbers ([#610])

## [1.6.0] - 2025-10-29
### Changed
- Compatibility updates for Android 15 & 16
- Calling now works directly without launching dialpad ([#562])
- Search bar is now pinned to the top when scrolling
- Updated translations

### Fixed
- Fixed freezing when sending messages ([#574])

## [1.5.0] - 2025-10-18
### Added
- Unread badge count for conversations ([#177])

### Changed
- Optimized loading messages in conversations ([#234])
- Updated conversation item design to be more compact ([#376])
- Pin/unpin actions now always show as action buttons in the menu ([#561])
- Updated translations

### Fixed
- Fixed position reset when opening attachments in conversations ([#82])
- Fixed automatic scroll to searched message in conversations ([#350])
- Fixed non-standard text and avatar sizes in list items
- Fixed "Mark as read" not working in some cases ([#264])

## [1.4.0] - 2025-10-12
### Added
- Ability to save multiple attachments ([#75])
- Ability to select numbers that aren't starred when starting a new conversation ([#153])

### Changed
- Reordered menu options throughout the app
- Updated translations

### Fixed
- Fixed keyword blocking for MMS messages ([#99])
- Fixed contact number selection when adding members to a group ([#456])
- Fixed a glitch in pattern lock after incorrect attempts
- Fixed disabled send button when sending images without text ([#165])

## [1.3.0] - 2025-09-09
### Added
- Option to keep conversations archived ([#334])

### Changed
- Updated translations

## [1.2.3] - 2025-08-21
### Changed
- Updated translations

### Fixed
- Fixed stale/missing notification badge on some devices

## [1.2.2] - 2025-08-01
### Changed
- Updated translations

### Fixed
- Fixed inability to view messages when there is no SIM card ([#461])

## [1.2.1] - 2025-06-17
### Changed
- Preference category labels now use sentence case
- Updated translations

## [1.2.0] - 2025-06-04
### Added
- Conversation shortcuts ([#209])

### Changed
- Updated translations

## [1.1.7] - 2025-04-01
### Changed
- Added more translations

### Fixed
- Fixed incorrect cursor position when reopening the app ([#349])
- Fixed scrolling issue on conversation details screen ([#359])

## [1.1.6] - 2025-03-24
### Changed
- Other minor fixes and improvements
- Added more translations

### Removed
- Removed storage permission requirement ([#309])

### Fixed
- Fixed crash when viewing messages
- Fixes incorrect author name in group messages ([#180])

## [1.1.5] - 2025-02-02
### Changed
- Added more translations

### Fixed
- Fixed issue with third party intents ([#294])
- Fixed toast error when receiving MMS messages ([#287])
- Fixed RTL layout issue in threads ([#279])

## [1.1.4] - 2025-01-23
### Changed
- Added more translations

### Fixed
- Fixed issue with forwarding messages ([#288])

## [1.1.3] - 2025-01-05
### Changed
- Added more translations

### Fixed
- Fixed issues with conversation date update ([#225], [#274])

## [1.1.2] - 2025-01-05
### Changed
- Added more translations

### Fixed
- Fixed issues with conversation date update ([#225], [#274])

## [1.1.1] - 2025-01-04
### Changed
- Improved third party SMS/MMS intent parsing ([#217], [#243])
- Modified short code check to exclude emails ([#115])
- Other minor bug fixes and improvements
- Added more translations

### Fixed
- Fixed issue with messages draft deletion ([#13])
- Fixed multiple toast errors for MMS messages ([#70], [#262])
- Fixed some layout issues in message thread ([#135])

## [1.1.0] - 2024-12-27
### Changed
- Replaced checkboxes with switches
- Improved app lock logic and interface
- Other minor bug fixes and improvements
- Added more translations

### Removed
- Removed support for Android 7 and older versions

### Fixed
- Fixed various issues related to importing/exporting messages
- Fixed keyword blocking for MMS messages
- Fixed issue with messages draft deletion

## [1.0.1] - 2024-02-09
### Changed
- Minor bug fixes and improvements
- Added some translations

## [1.0.0] - 2024-01-24
### Added
- Initial release

[#13]: https://github.com/FossifyOrg/Messages/issues/13
[#41]: https://github.com/FossifyOrg/Messages/issues/41
[#45]: https://github.com/FossifyOrg/Messages/issues/45
[#52]: https://github.com/FossifyOrg/Messages/issues/52
[#70]: https://github.com/FossifyOrg/Messages/issues/70
[#75]: https://github.com/FossifyOrg/Messages/issues/75
[#82]: https://github.com/FossifyOrg/Messages/issues/82
[#99]: https://github.com/FossifyOrg/Messages/issues/99
[#115]: https://github.com/FossifyOrg/Messages/issues/115
[#135]: https://github.com/FossifyOrg/Messages/issues/135
[#153]: https://github.com/FossifyOrg/Messages/issues/153
[#159]: https://github.com/FossifyOrg/Messages/issues/159
[#165]: https://github.com/FossifyOrg/Messages/issues/165
[#177]: https://github.com/FossifyOrg/Messages/issues/177
[#180]: https://github.com/FossifyOrg/Messages/issues/180
[#185]: https://github.com/FossifyOrg/Messages/issues/185
[#209]: https://github.com/FossifyOrg/Messages/issues/209
[#217]: https://github.com/FossifyOrg/Messages/issues/217
[#225]: https://github.com/FossifyOrg/Messages/issues/225
[#234]: https://github.com/FossifyOrg/Messages/issues/234
[#243]: https://github.com/FossifyOrg/Messages/issues/243
[#261]: https://github.com/FossifyOrg/Messages/issues/261
[#262]: https://github.com/FossifyOrg/Messages/issues/262
[#264]: https://github.com/FossifyOrg/Messages/issues/264
[#274]: https://github.com/FossifyOrg/Messages/issues/274
[#279]: https://github.com/FossifyOrg/Messages/issues/279
[#282]: https://github.com/FossifyOrg/Messages/issues/282
[#287]: https://github.com/FossifyOrg/Messages/issues/287
[#288]: https://github.com/FossifyOrg/Messages/issues/288
[#290]: https://github.com/FossifyOrg/Messages/issues/290
[#294]: https://github.com/FossifyOrg/Messages/issues/294
[#309]: https://github.com/FossifyOrg/Messages/issues/309
[#315]: https://github.com/FossifyOrg/Messages/issues/315
[#334]: https://github.com/FossifyOrg/Messages/issues/334
[#349]: https://github.com/FossifyOrg/Messages/issues/349
[#350]: https://github.com/FossifyOrg/Messages/issues/350
[#359]: https://github.com/FossifyOrg/Messages/issues/359
[#376]: https://github.com/FossifyOrg/Messages/issues/376
[#416]: https://github.com/FossifyOrg/Messages/issues/416
[#456]: https://github.com/FossifyOrg/Messages/issues/456
[#461]: https://github.com/FossifyOrg/Messages/issues/461
[#561]: https://github.com/FossifyOrg/Messages/issues/561
[#562]: https://github.com/FossifyOrg/Messages/issues/562
[#574]: https://github.com/FossifyOrg/Messages/issues/574
[#600]: https://github.com/FossifyOrg/Messages/issues/600
[#610]: https://github.com/FossifyOrg/Messages/issues/610
[#615]: https://github.com/FossifyOrg/Messages/issues/615
[#641]: https://github.com/FossifyOrg/Messages/issues/641
[#644]: https://github.com/FossifyOrg/Messages/issues/644
[#651]: https://github.com/FossifyOrg/Messages/issues/651
[#713]: https://github.com/FossifyOrg/Messages/issues/713
[#829]: https://github.com/FossifyOrg/Messages/issues/829
[#834]: https://github.com/FossifyOrg/Messages/issues/834

[Unreleased]: https://github.com/MyNameIsGlooboo/uMessage/compare/0.2.0...HEAD
[0.2.0]: https://github.com/MyNameIsGlooboo/uMessage/compare/1.10.2...0.2.0
[1.10.2]: https://github.com/FossifyOrg/Messages/compare/1.10.1...1.10.2
[1.10.1]: https://github.com/FossifyOrg/Messages/compare/1.10.0...1.10.1
[1.10.0]: https://github.com/FossifyOrg/Messages/compare/1.9.1...1.10.0
[1.9.1]: https://github.com/FossifyOrg/Messages/compare/1.9.0...1.9.1
[1.9.0]: https://github.com/FossifyOrg/Messages/compare/1.8.1...1.9.0
[1.8.1]: https://github.com/FossifyOrg/Messages/compare/1.8.0...1.8.1
[1.8.0]: https://github.com/FossifyOrg/Messages/compare/1.7.0...1.8.0
[1.7.0]: https://github.com/FossifyOrg/Messages/compare/1.6.0...1.7.0
[1.6.0]: https://github.com/FossifyOrg/Messages/compare/1.5.0...1.6.0
[1.5.0]: https://github.com/FossifyOrg/Messages/compare/1.4.0...1.5.0
[1.4.0]: https://github.com/FossifyOrg/Messages/compare/1.3.0...1.4.0
[1.3.0]: https://github.com/FossifyOrg/Messages/compare/1.2.3...1.3.0
[1.2.3]: https://github.com/FossifyOrg/Messages/compare/1.2.2...1.2.3
[1.2.2]: https://github.com/FossifyOrg/Messages/compare/1.2.1...1.2.2
[1.2.1]: https://github.com/FossifyOrg/Messages/compare/1.2.0...1.2.1
[1.2.0]: https://github.com/FossifyOrg/Messages/compare/1.1.7...1.2.0
[1.1.7]: https://github.com/FossifyOrg/Messages/compare/1.1.6...1.1.7
[1.1.6]: https://github.com/FossifyOrg/Messages/compare/1.1.5...1.1.6
[1.1.5]: https://github.com/FossifyOrg/Messages/compare/1.1.4...1.1.5
[1.1.4]: https://github.com/FossifyOrg/Messages/compare/1.1.3...1.1.4
[1.1.3]: https://github.com/FossifyOrg/Messages/compare/1.1.2...1.1.3
[1.1.2]: https://github.com/FossifyOrg/Messages/compare/1.1.1...1.1.2
[1.1.1]: https://github.com/FossifyOrg/Messages/compare/1.1.0...1.1.1
[1.1.0]: https://github.com/FossifyOrg/Messages/compare/1.0.1...1.1.0
[1.0.1]: https://github.com/FossifyOrg/Messages/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/FossifyOrg/Messages/releases/tag/1.0.0
