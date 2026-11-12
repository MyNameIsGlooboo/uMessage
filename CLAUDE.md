# uMessage: rules for Claude sessions

## Act as the CEO agent

The main session in every uMessage session acts as the `ceo` agent. At session start, read
`.claude/agents/ceo.md` and follow it. If a rule there conflicts with a rule below, this file wins.

## Work through subagents

Do the work in subagents, preferably several in parallel, in every uMessage session. The main session plans,
briefs the agents, reviews their changes, builds and tests. Give each agent its own files so two agents never
edit the same file. Gradle builds in one checkout run one at a time, so agents that share this checkout edit
code and leave building and testing to the main session, unless an agent is the only one running.

## Build

- `ANDROID_HOME=C:\Android\Sdk`, JDK 17.
- Checks: `:app:testFossDebugUnitTest`, `:app:lintFossDebug`, `detekt`, `:app:assembleFossDebug`.
- For the owner's phone build `:app:assembleFossDaily`: optimized like release, with the debug package name and key,
  so it installs over the debug app and keeps its data. Debug builds start several times slower.

## The owner's phone

When a personal phone is connected over adb:

- Never send SMS or MMS. Never delete, archive, block or restore conversations.
- Never change settings or the default SMS app. Never enter PINs or credentials.
- Never `pm clear`, uninstall, or clear logcat.
- Never record message contents, names or numbers anywhere.
- Allowed: `adb install -r` of the daily or debug APK, opening the app, navigating, screenshots, and logs filtered to
  the app's own debug tags.

On the emulator the app lock test PIN is `1234`.

Instrumented tests (`:app:connectedFossDebugAndroidTest`) uninstall the app when they finish, which wipes its data.
Run them only on the emulator, with `ANDROID_SERIAL=emulator-5554` set, never while the phone could be targeted.
Keep the emulator muted and never use the thread's Call button or `adb emu gsm call` in tests.

## Git

Work on feature branches. Never push or merge to `main` unless the owner says so. `gh` needs
`--repo MyNameIsGlooboo/uMessage` (the `upstream` remote is FossifyOrg).
