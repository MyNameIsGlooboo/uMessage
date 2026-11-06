# Contributing to uMessage

uMessage is a fork of [Fossify Messages](https://github.com/FossifyOrg/Messages). Please report
uMessage problems here, not upstream: the Fossify team does not support this fork.

## Reporting a bug or asking for a feature

- Use the [issue templates](https://github.com/MyNameIsGlooboo/uMessage/issues/new/choose).
  Search [open and closed issues](https://github.com/MyNameIsGlooboo/uMessage/issues?q=is%3Aissue) first.
- One bug or one feature per issue.
- Never paste real phone numbers, message text or your decision log into an issue. Mask them.
- Security problems (for example an app-lock bypass or a way to read blocked messages from
  another app): please do not open a public issue. Use GitHub's private vulnerability reporting
  on this repository instead; see [SECURITY.md](SECURITY.md).

## Contributing code

- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [PRIVACY.md](PRIVACY.md) first.
  uMessage is fully offline: a change that adds the `INTERNET` permission, analytics or any
  network library will not be accepted, and CI fails if the APK requests `INTERNET`.
- A change that adds a permission or stores new data must update PRIVACY.md in the same PR.
- Build and check locally before opening a PR (needs JDK 17 and the Android SDK, platform 36):

  ```sh
  ./gradlew assembleFossDebug testFossDebugUnitTest lintFossDebug detekt
  ./gradlew assembleFossRelease checkFdroidFossRelease   # R8-minified, unsigned; F-Droid checks
  ```

  CI fails on any detekt finding and any lint error that is not in the baselines. Lint warnings
  are reported but never fail the build.
- Do not add entries to `app/lint-baseline.xml` or `app/detekt-baseline.xml` to make a new
  finding go away. Fix it, or suppress it in code with a comment saying why. The baselines hold
  only older findings: mostly long lines and magic numbers left alone so open branches do not
  conflict, broad `catch (e: Exception)` blocks, and the complexity of large classes and
  screens (ThreadActivity, RulesScreen, the Context extensions), which needs a real refactor. Shrink them when you touch a file,
  then regenerate with `./gradlew detektBaseline` or `./gradlew updateLintBaselineFossDebug`.
- Room schema changes: bump the database version, add a migration, and commit the new JSON
  that the build writes to `app/schemas/`.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `chore:`, `docs:` ...). Add a line to the `Unreleased` section of
  [CHANGELOG.md](CHANGELOG.md) for user-visible changes.

## Translations

uMessage currently ships in English only (see README). Translation contributions are welcome
once the UI strings settle.
