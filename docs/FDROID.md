# F-Droid

Notes for getting uMessage into the main F-Droid repository. Nothing here is submitted yet. F-Droid builds the app from source on its own servers, so the source must build without any non-free part.

## Why uMessage should qualify

- GPL-3.0, full source public, upstream (Fossify Messages) is already on F-Droid with the same build setup.
- No `INTERNET` permission, no tracking, analytics, ads or Google Play Services.
- `dependenciesInfo { includeInApk = false }` is set, so the APK has no Google-encrypted dependency block (F-Droid's scanner flags it otherwise).
- The only binaries in the repository are the two Gradle wrapper jars, which F-Droid accepts and verifies.
- Fossify Commons is built from source (`third_party/commons`, an included build), not downloaded.

Things the reviewer will look at:

- **JitPack.** `settings.gradle.kts` restricts JitPack to `org.fossify` (mmslib) and `com.github.tibbi` (Reprint). Fossify Messages is built by F-Droid with the same two, so this is accepted today, but F-Droid prefers Maven Central; if they object, vendor mmslib like Commons.
- **Flavor.** Build the `foss` flavor only. `gplay` exists for Google Play and differs only in two resource flags (`app/src/*/res/values/bools.xml`).
- **Permissions.** Location (asked only when the user taps Location in the attach menu) and photo access (asked only from "Show recent photos") are runtime permissions with no network use; neither is an anti-feature.
- **Anti-features.** None expected. "NonFreeNet" and "Tracking" do not apply (no network). "NonFreeAssets" does not apply: fonts are OFL/Apache, icons Apache (see `NOTICE.md`).

## Draft metadata

To go in `metadata/com.glooboo.spamless.yml` in the [fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository, via a merge request. Check the commit hash and version against the tag being submitted.

```yaml
Categories:
  - Phone & SMS
  - Security
License: GPL-3.0-only
AuthorName: Glooboo
SourceCode: https://github.com/MyNameIsGlooboo/uMessage
IssueTracker: https://github.com/MyNameIsGlooboo/uMessage/issues
Changelog: https://github.com/MyNameIsGlooboo/uMessage/blob/HEAD/CHANGELOG.md

AutoName: uMessage

RepoType: git
Repo: https://github.com/MyNameIsGlooboo/uMessage.git

Builds:
  - versionName: 0.2.0
    versionCode: 27
    commit: v0.2.0          # replace with the full commit hash of the tag
    subdir: app
    gradle:
      - foss
    # Optional, once reproducible builds are confirmed (see below):
    # binary: https://github.com/MyNameIsGlooboo/uMessage/releases/download/v%v/spamless-%c-foss-release.apk

AllowedAPKSigningKeys: <sha256 of the release certificate, lowercase, no colons>   # only with `binary:`

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 0.2.0
CurrentVersionCode: 27
```

Notes on the fields:

- `License: GPL-3.0-only` matches upstream Fossify Messages. If the owner wants "or later", every source header and NOTICE must say so first; do not change it only here.
- `UpdateCheckMode: Tags` reads `VERSION_NAME` and `VERSION_CODE` from `gradle.properties` at each new tag. F-Droid's checker understands `gradle.properties`, but if it fails, add `UpdateCheckData: gradle.properties|VERSION_CODE=(\d+)|.|VERSION_NAME=(.*)`.
- The listing (title, descriptions, changelogs per version code, images) is read from `fastlane/metadata/android/<locale>/` in the tagged commit; nothing needs copying into fdroiddata.
- Locales: only `en-US` and `fa-IR` are kept. A locale without its own folder shows the en-US text, so no other folder is needed. The old Fossify translations were removed because they described Fossify Messages.

## Reproducible builds

With reproducible builds, F-Droid builds the APK, compares it to our signed APK from GitHub Releases, and if they match apart from the signature, ships ours. Users can then update between F-Droid and GitHub builds, because both carry our signature. Without it, F-Droid signs with its own key, and users cannot switch between the F-Droid build and our APK without uninstalling (which loses their data).

To get there:

1. Build the release in CI with the exact JDK (Temurin 17), Android build-tools (36.0.0) and Gradle wrapper that F-Droid will use; keep `gradle-wrapper.properties` pinned with its SHA-256 (already done).
2. Keep the build free of timestamps and machine paths. Known risks to check:
   - `gitBuildInfo` in `app/build.gradle.kts` adds the commit count and hash to debug and daily `versionNameSuffix` only; release must not use it (it does not today).
   - R8 output is deterministic for the same inputs; baseline profiles (`assets/dexopt/baseline.prof`) can differ between builds and are a common cause of mismatches. If they do, disable them for release or ask F-Droid to strip them.
   - Do not let the build read `local.properties`, environment variables or the clock for anything that ends up in the APK.
3. Compare locally: build twice in clean checkouts (different paths), strip signatures and `diffoscope` the two APKs. Then test with F-Droid's `fdroid build --verbose com.glooboo.spamless` from an fdroidserver checkout.
4. Publish the APK name exactly as the `binary:` URL expects, and put the certificate SHA-256 in `AllowedAPKSigningKeys`.

Until step 3 passes, submit without `binary:` (F-Droid signs it) and add reproducible builds later. The first F-Droid version then has F-Droid's signature, and switching to ours later requires users to reinstall, so it is worth finishing reproducibility before the first submission if it is close.

## Checklist before submitting

- [ ] Tag `vX.Y.Z` is pushed and its commit has the store listing and `changelogs/<versionCode>.txt` for en-US and fa-IR.
- [x] `fastlane/metadata/android/en-US/images/` has `icon.png` (512x512), `featureGraphic.png` and 8 phone screenshots of uMessage.
- [ ] `./gradlew assembleFossRelease` succeeds from a clean clone with no `keystore.properties`.
- [ ] `fdroid lint` and `fdroid build` pass locally with the draft metadata.
- [ ] Decide on reproducible builds (above).
- [ ] Mention the fork in the merge request: uMessage is a fork of Fossify Messages with a different application id, and does not replace it.
