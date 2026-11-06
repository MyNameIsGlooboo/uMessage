# Toolchain setup (JDK 17 + Android SDK 36)

## Windows (PowerShell)

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install Google.AndroidStudio          # easiest: includes SDK Manager and emulator
```

Or command-line tools only: download "Command line tools" from https://developer.android.com/studio#command-line-tools-only, unzip to `C:\Android\cmdline-tools\latest`, then:

```powershell
setx ANDROID_HOME "C:\Android"
C:\Android\cmdline-tools\latest\bin\sdkmanager.bat --licenses
C:\Android\cmdline-tools\latest\bin\sdkmanager.bat "platform-tools" "platforms;android-36" "build-tools;36.0.0" "emulator" "system-images;android-36;google_apis;x86_64"
```

Add `%ANDROID_HOME%\platform-tools` and `%ANDROID_HOME%\emulator` to PATH.

## Linux / macOS

Install a JDK 17 (Temurin) and the command-line tools, then run the same `sdkmanager` command with `$ANDROID_HOME`.

## Project config

Gradle finds the SDK through `ANDROID_HOME` or `local.properties` (`sdk.dir=C\:\\Android`; this file is git-ignored). Verify:

```
java -version        # 17.x
./gradlew assembleFossDebug testFossDebugUnitTest lintFossDebug
bash scripts/check-no-internet.sh app/build/outputs/apk/foss/debug/*.apk
```

`check-no-internet.sh` needs `aapt2` from build-tools; it is found automatically under `$ANDROID_HOME/build-tools`.

## Scripts

- `send-test-sms.ps1` / `.sh`: send one fake incoming SMS to the emulator.
- `test-scenarios.ps1` / `.sh`: send four scenario messages (contact, unknown, OTP, spam keyword).
- `check-no-internet.sh`: the same check CI runs.
