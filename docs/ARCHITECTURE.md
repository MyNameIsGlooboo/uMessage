# Architecture

uMessage is a fork of Fossify Messages. It keeps the upstream single-module Android app and adds a spam engine and a Jetpack Compose Material 3 UI. Every uMessage screen and dialog is Compose; including the app lock screen (`ui/lock/LockActivity`) and the system block list (`ui/spam/BlockedNumbersActivity`), which replaced the vendored Fossify Commons screens. Everything is under `app/src/main/kotlin/com/glooboo/umessage`.

## Packages

| Package | Role |
|---|---|
| `spam` | **Contracts.** `SpamContracts.kt` is the frozen public interface of the spam engine: `SpamFilter`, `SpamRepository`, `SpamRule`, `RuleType`, `ListKind`, `IncomingMessage`, `FilterDecision`, `BlockedMessage`, `SpamSettings`, `SpamStats`, `UnknownSenderMode`. UI code depends only on these types. `UMessage.kt` is the entry point that wires the engine into the app. |
| `spam/engine`, `spam/data` | Implementations of the contracts: pure decision logic (`engine`) and Room storage, contact cache, repository (`data`). The engine has no Android I/O except the contact lookup injected by the data layer, so it is unit-tested on the JVM. |
| `ui` | Compose + Material 3 UI. `ui/theme` (`UMessageTheme`, which defaults to Material You dynamic color on Android 12+ and otherwise uses the chosen color preset; `ThemePresets`, `Type.kt` with Google Sans Flex and Roboto static weights, `contentDirection()` for RTL text), `ui/components` (app bars, Pixel-style grouped settings rows, `showComposeDialog` host), `ui/conversations` (list, swipe, `SwipeGuard`), `ui/thread` (bubbles, composer, send button with SIM badge, relative date labels), `ui/settings`, `ui/spam`, `ui/onboarding`, `ui/icons` (generated Material Symbols `ImageVector`s). |
| `activities`, `dialogs` | Activity hosts and dialog launchers around the Compose screens. The upstream screens extend `SimpleActivity` / `ComposeSimpleActivity` (built on the commons AppCompat `BaseSimpleActivity`, which also enforces the app lock in `onResume`); the uMessage screens under `ui/` (blocked, rules, spam settings, theme, decision log, onboarding) are Compose hosts as well. Screen changes use the platform default activity transitions and predictive back (the old custom `res/anim/spamless_*` animations were removed). |
| `receivers` | Broadcast receivers. The filter hook points are `SmsReceiver` (SMS_DELIVER), `MmsPushReceiver` (WAP_PUSH_DELIVER, before download) and `MmsReceiver` (content check after an MMS download; not exported, it only receives the app's own download-complete broadcast). |
| `messaging`, `services` | Sending, scheduling, status handling, respond-via-message. |
| `databases`, `models`, `helpers`, `extensions`, `interfaces` | Upstream conversations database, models and utilities. |

## Data ownership

- The Android Telephony provider stays the source of truth for **allowed** messages, so other apps and backups remain compatible.
- The uMessage Room database holds **blocked** messages, rules, the contact-number cache and counters. A blocked message is never written to the provider until the user restores it ("Not spam").

## Receive path

```
SMS_DELIVER / WAP_PUSH_DELIVER
  -> receiver (goAsync)
  -> build IncomingMessage (address, body, subscriptionId)
  -> SpamFilter.evaluate()
       Allow -> write to Telephony provider, update conversation, notify
       Block -> SpamRepository.storeBlocked() only (no provider write, no notification unless enabled)
```

Blocked MMS are not downloaded. See [FILTER_PIPELINE.md](FILTER_PIPELINE.md) for the decision order.

## Build and flavors

Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`). Fossify Commons is vendored in `third_party/commons` as an included build and substituted for `org.fossify:commons`. Flavor dimension `variants`: `foss` (the one uMessage ships and CI builds) and `gplay` (inherited, unused). minSdk 26, compile/target SDK 36. Release signing comes from `keystore.properties` or `SIGNING_*` environment variables; the release workflow refuses to publish an unsigned APK.

Repositories: Google and Maven Central for everything, JitPack only for the groups published nowhere else (`org.fossify` mmslib/IndicatorFastScroll and the legacy commons libraries), via `exclusiveContent` in `settings.gradle.kts`. No `mavenLocal()`.

Room schemas are exported to `app/schemas/` and committed, so a schema change shows up in review next to its migration.

Backups: `allowBackup="false"` and `res/xml/data_extraction_rules.xml` / `backup_rules.xml` keep all private storage (databases, decision log, prefs with the app-lock hash) out of cloud backup and device-to-device transfer. See [PRIVACY.md](../PRIVACY.md).

## Invariants guarded by CI

- Unit tests pass (`testFossDebugUnitTest`) and lint passes with no errors (`lintFossDebug`, and `lintFossRelease` on the release job). `app/lint-baseline.xml` only holds a few older warnings; new findings are not added to it.
- The release variant builds with R8 minify and resource shrinking on every push (`assembleFossRelease`, unsigned).
- Neither the debug nor the release APK requests `android.permission.INTERNET` (`scripts/check-no-internet.sh`).
- Detekt runs and uploads a report but does not gate yet (`app/detekt-baseline.xml` still lists older findings).

## UI conventions

- **Icons:** Material Symbols Outlined, generated into `ui/icons/Symbols.kt` by `tools/symbols/generate_symbols.py` (Apache-2.0). Do not hand-edit the generated file; add names to the script and rerun.
- **Fonts:** static weights only. HyperOS ignores variable font axes.
- **RTL:** bubble text and the composer field follow the first strong character (`startsRightToLeft`, `contentDirection()`), not the app locale.
- **Conversation swipe:** a row swipes only when the finger moves within 30 degrees of horizontal (`SwipeGuard.kt`, tested in `SwipeGuardTest`). Long-press selection is ignored once the finger has moved past touch slop.
- **Default SIM:** `Config.defaultSimId` is a SIM subscription id, `DEFAULT_SIM_PHONE` (follow phone settings) or `DEFAULT_SIM_ASK`. A per-number SIM choice wins over it.
