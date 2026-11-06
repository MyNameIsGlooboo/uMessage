# Releasing uMessage

How to cut a release. Releases are made from `main`; only the owner merges to `main` and pushes tags.

## 1. Pick the version

Versions live in `gradle.properties`:

```properties
VERSION_NAME=0.2.0
VERSION_CODE=27
APP_ID=com.glooboo.spamless
```

- `VERSION_NAME` follows [Semantic Versioning](https://semver.org): bump the minor version for new features, the patch version for fixes only. Before 1.0, a breaking change to the rules file or settings also bumps the minor version.
- `VERSION_CODE` goes up by exactly 1 for every published build, on every channel (GitHub, F-Droid, Play). Never reuse or lower it: Android refuses to install a lower code over a higher one, and F-Droid and Play use it to tell builds apart.
- `APP_ID` never changes. Changing it makes a different app that cannot update the old one.

## 2. Update the changelogs

1. `CHANGELOG.md`: rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` and start a new empty `## [Unreleased]` above it. Entries are written for users ("Blocked messages can be restored"), not for developers.
2. Store changelogs, one file per version code:
   - `fastlane/metadata/android/en-US/changelogs/<VERSION_CODE>.txt`
   - `fastlane/metadata/android/fa-IR/changelogs/<VERSION_CODE>.txt` (Persian; use the glossary in `docs/translation-fa.md` when it exists)

   Keep each one under 500 characters (the Google Play limit), plain text, short bullet lines.
3. If a release changes what the app stores or which permissions it asks for, update `PRIVACY.md` and, for Play, the Data safety form (see [PLAY_STORE.md](PLAY_STORE.md)).

## 3. Check the build

On a release branch (for example `release-0.3.0`):

```sh
./gradlew testFossDebugUnitTest lintFossDebug detekt assembleFossDebug
./gradlew assembleFossRelease checkFdroidFossRelease   # R8-minified; F-Droid checks
bash scripts/check-release-version.sh vX.Y.Z           # tag = VERSION_NAME, VERSION_CODE above the last tag
bash scripts/check-no-internet.sh app/build/outputs/apk/foss/release/*.apk
```

Install the release (or `assembleFossDaily`) build on a real phone and go through [DEVICE_TEST_CHECKLIST.md](DEVICE_TEST_CHECKLIST.md): receive from a contact, from an unknown number, a verification code in Smart and Strict, "Not spam", and an upgrade over the previous version with data kept.

Commit the version bump and changelogs as `release: version X.Y.Z (CODE)`, open a pull request to `main`, and merge it once CI is green.

## 4. Tag

```sh
git switch main && git pull
git tag -a vX.Y.Z -m "uMessage X.Y.Z"
git push origin vX.Y.Z
```

Pushing a `v*` tag runs `.github/workflows/release.yml`. A first job without secrets checks the version (the tag must equal `vVERSION_NAME`, and `VERSION_CODE` must be higher than at the previous `v*` tag) and runs detekt, lint, the unit tests and the F-Droid check. Only then does the signing job build signed foss and gplay APKs and AABs, verify every signature (apksigner for APKs, jarsigner for AABs), run the no-INTERNET check, and publish a GitHub Release with all four files and `SHA256SUMS.txt`. Edit the release notes on GitHub afterwards to match `CHANGELOG.md`.

## 5. Signing

The release key is the identity of the app: every update must be signed with the same key, or phones will refuse to install it. Keep at least two offline backups of the keystore and its passwords. Never commit it (`keystore.jks` and `keystore.properties` are in `.gitignore`).

The build reads signing details from one of two places (`app/build.gradle.kts`):

- **Local:** copy `keystore.properties_sample` to `keystore.properties` and fill in `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
- **CI or environment:** `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. The release workflow builds these from the repository secrets `SIGNING_KEYSTORE_BASE64` (the keystore, base64-encoded), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. If any is missing the workflow stops; it never publishes an unsigned APK.

Without either, `assembleFossRelease` produces an unsigned APK (fine for checking R8, not for publishing).

To create a key once:

```sh
keytool -genkeypair -v -keystore spamless-release.jks -alias spamless -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 spamless-release.jks   # value for SIGNING_KEYSTORE_BASE64
```

Record the certificate's SHA-256 fingerprint (`apksigner verify --print-certs`) in the release notes or README so users can check downloads.

## 6. F-Droid

F-Droid builds the app itself from the tag, using the metadata described in [FDROID.md](FDROID.md). After the first inclusion:

- Tags matching `v*` are picked up automatically if the metadata uses `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`.
- F-Droid reads `fastlane/metadata/android/` for the listing and changelogs, so step 2 must be in the tagged commit.
- If reproducible builds are enabled, F-Droid publishes our signed APK only when its build matches it byte for byte; see FDROID.md.

## 7. Google Play

Play takes an Android App Bundle of the `gplay` flavor:

```sh
./gradlew bundleGplayRelease   # app/build/outputs/bundle/gplayRelease/*.aab
```

Upload it to the internal testing track first, then promote to production. The listing text comes from `fastlane/metadata/android/` (upload by hand, or with `fastlane supply` and a service-account key in `fastlane/fastlane.json`, which is git-ignored). `fastlane/Fastfile` uses plain fastlane actions: `fastlane android deploy` builds the gplay AAB and uploads it to the internal track, `fastlane android metadata` uploads only the listing. Before the first Play release, work through [PLAY_STORE.md](PLAY_STORE.md).

## After the release

- Check the GitHub Release has the signed APK and the notes are right.
- Install it over the previous release on a phone and confirm messages, rules and blocked messages survive.
- Bump nothing yet: the next version is chosen at the next release.
