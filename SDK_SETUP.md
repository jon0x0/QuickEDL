# Android SDK Setup

This repo has the Android app source and a working Gradle wrapper. Java is installed locally as OpenJDK `21.0.11`, and `local.properties` points Gradle at the project-local Android SDK overlay in `android-sdk`.

This host is Debian 13 on ARM64/aarch64. That affects Android builds because some upstream Google command-line tool distributions assume x86_64 Linux. Prefer Debian-packaged Android tools where possible.

## Recommended path

1. Install Android Studio from `https://developer.android.com/studio`.
2. Open this repo folder in Android Studio.
3. Let Android Studio install:
   - JDK 17 or newer if needed
   - Android SDK Platform 35
   - Android SDK Build-Tools
   - Android SDK Platform-Tools
   - Android Emulator if emulator testing is needed
4. Let Gradle sync download the Android Gradle Plugin, Kotlin plugin, Media3, and DocumentFile dependencies.
5. Run the `app` configuration on a physical Android device first, because video folder access and codec behavior are easiest to validate with real files.

## Command-line path on this Debian ARM64 host

Run these commands in a normal terminal, because Codex cannot enter your sudo password:

```bash
sudo apt update
sudo apt install openjdk-21-jdk unzip wget ca-certificates android-sdk-platform-tools android-sdk-build-tools apksigner zipalign google-android-platform-35-installer
```

After that, report back and Codex can locate the installed SDK files and create `local.properties`.

If you want to do it in two smaller steps, install the JDK first:

```bash
sudo apt install openjdk-21-jdk
javac -version
```

Then install the Android packages:

```bash
sudo apt install android-sdk-platform-tools android-sdk-build-tools apksigner zipalign google-android-platform-35-installer
```

## Gradle wrapper

The repo already has a Gradle wrapper generated with Gradle `8.10.2`.

Because this is ARM64, Gradle needs native services disabled:

```bash
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew --no-daemon tasks
```

Codex may need to run Gradle commands outside the sandbox because Gradle uses local sockets internally.

## Alternative Google SDK path

On x86_64 Linux, the usual command-line tools flow is:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

On this ARM64 host, use that only if Debian packages fail or if testing proves Google’s downloaded tools work here.

## Current local blocker

`./gradlew -v` works. The full JDK is installed and `javac 21.0.11` is available.

`local.properties` exists and currently points to:

```properties
sdk.dir=/home/jon/quickedl/android-sdk
```

AGP's bundled Maven `aapt2` binary is x86_64 and cannot run on this ARM64 host:

```text
AAPT2 aapt2-8.5.2-11315950-linux Daemon #0: Unexpected error output:
.../aapt2: 2: Syntax error: "(" unexpected
```

This host has a Debian-packaged ARM64 `aapt2` installed:

```text
/usr/bin/aapt2 -> /usr/lib/android-sdk/build-tools/debian/aapt2
/usr/lib/android-sdk/build-tools/debian/aapt2: ELF 64-bit LSB pie executable, ARM aarch64
```

The workspace now uses that binary via `gradle.properties`:

```properties
android.aapt2FromMavenOverride=/usr/bin/aapt2
```

Debian's `aapt2` can build the app with Android platform 34, but failed against the installed Android platform 35 `android.jar`. The working local setup downloaded Google's `platform-34-ext7_r03.zip`, verified SHA-1 `1f2e9478d6a7601425ceaa553311dc43191f103d`, and unpacked it into:

```text
android-sdk/platforms/android-34
```

The app currently compiles and targets SDK 34 in this workspace. This command succeeds and produces `app/build/outputs/apk/debug/app-debug.apk`:

```bash
GRADLE_USER_HOME=/home/jon/quickedl/.gradle ./gradlew assembleDebug --no-daemon
```
