# Session Handoff

Last updated: 2026-06-09

## Stop point

Debug APK build succeeds on this Debian ARM64 host, and the app has been installed and smoke-tested on a physical Pixel 8.

```bash
GRADLE_USER_HOME=/home/jon/quickedl/.gradle ./gradlew assembleDebug --no-daemon
```

Output:

```text
/home/jon/quickedl/app/build/outputs/apk/debug/app-debug.apk
```

Physical-device check on 2026-06-08:

- Re-ran the debug build command; result was still `BUILD SUCCESSFUL`.
- Confirmed ADB sees the connected phone:

```text
38091FDJH00M2E         device usb:1-1.2 product:shiba model:Pixel_8 device:shiba transport_id:1
```

- Installed the debug APK with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Launched the app with:

```bash
adb shell am start -n com.quickedl/.MainActivity
```

- Confirmed the app process was running and `MainActivity` was resumed in the foreground.
- Picked `/sdcard/DCIM/Camera` through the Android folder picker.
- Confirmed the app listed three camera videos and loaded playback.
- Created one EDL range for `VID_20260604_071146.mp4`.
- Confirmed the sidecar was written at `/sdcard/DCIM/Camera/VID_20260604_071146.edl` with:

```text
# QuickEDL v1
# start_ms end_ms start_time end_time
2013 4058 00:00:02.013 00:00:04.058
```

- Basic orientation handling was exercised by rotating the phone while the app was running; no crash was observed.
- Force-stopped and relaunched the app; the persisted Camera folder grant, three-video list, existing `.edl`, and total selected `In` time restored without reopening the picker.
- Fixed the phone portrait layout discovered during testing:
  - Portrait now stacks the video list above the player/controls instead of squeezing both columns side by side.
  - EDL controls now render in two weighted rows so `Next` and `Remove` remain visible on narrow screens.
- Rebuilt, reinstalled, and visually checked the updated layout in portrait and landscape on the Pixel 8.
- Added a file-list visibility toggle so the video area can take more space during review.
- Reworked layout sizing:
  - Landscape uses `file list | player | controls`, with controls capped to the right quarter of the screen.
  - Portrait uses `file list | player | controls`, with controls capped to the bottom quarter of the screen.
- Renamed EDL controls to Resolve-style labels: `Mark In`, `Mark Out`, and `Clear In/Out`.
- Clarified boundary navigation labels as `Prev Edit Point` and `Next Edit Point`.
- Added `Next Unmarked` to jump to the next unlocked clip with no selected EDL time.
- Added per-clip lock/unlock icon buttons in the file list; locked clips block EDL edits but still allow playback/navigation.
- Added an EDL timeline strip above the scrub controls:
  - Gray base track.
  - Light selected ranges.
  - White highlight when the playhead is inside a selected range.
  - Orange playhead and pending In marker.
- Moved the EDL range highlighting onto the primary scrubber directly under video playback and removed the separate secondary highlight strip from the control panel.
- The highlighted primary scrubber supports tap/drag seeking.
- Disabled Media3's overlay playback controller so playback/scrubbing no longer dims the video image.
- Added a custom transport row below the video with time display, jump to start/end, symmetric `-5` / `+5` second jumps, play/pause, and a placeholder settings button.
- Made `Mark In` / `Mark Out` order-independent. Marking Out first and then In now creates the normalized selected range correctly.
- Pending In/Out marks now preview on the primary timeline before the range is saved.
- Changed slow scrub behavior to a relative fine-scrub control:
  - Dragging the slow bar moves within a small window around the current playhead.
  - `-` and `+` buttons step one frame backward/forward using the detected video frame rate when available.
- Enlarged the slow-scrub `-` and `+` frame-step buttons to reduce accidental timeline/slider touches.
- Added `EDL_FORMAT.md` documenting the current QuickEDL sidecar format and a recommended compatibility strategy:
  - Keep QuickEDL sidecars as the canonical simple/script-friendly format.
  - Target Open Video Editor Android first for native Android open-source editor compatibility.
  - Generate OTIO as the primary script/interchange output.
  - Generate CMX 3600 EDL for DaVinci Resolve cuts-only import.
  - Generate MLT XML for Kdenlive/Shotcut/open-source editor workflows.
- Added `latest_build/` as the root-level location for the current debug APK copy.
- Fixed camera-folder loading performance:
  - Folder scan now runs on a background thread.
  - SAF query filters by video extension / `video/*` MIME type and `.edl` sidecars before creating `DocumentFile` wrappers.
  - Photo-heavy camera folders no longer require processing every photo as a `DocumentFile`.
  - Thumbnail generation remains deferred; no thumbnail work is done during initial video list loading.
- Reduced ANR risk during review/editing:
  - Initial folder scan no longer reads every `.edl` sidecar; only the selected clip loads ranges.
  - Selected clip range loading runs on a background thread.
  - Sidecar writes run on a background thread after the UI updates.
  - Playback UI refresh cadence reduced from 250ms to 500ms.
  - Lock/range updates refresh visible rows directly instead of invalidating the whole list.
- Added persistent active-video highlighting in the file list.
- Moved `-1F` / `+1F` frame-step controls into the transport row beside `-5s` / `+5s`.
- Renamed transport jump labels from `-5` / `+5` to `-5s` / `+5s`.
- Added launcher/startup branding assets:
  - Vector adaptive icon with phone, quick-cut lightning, director-chair, and tropical cue.
  - Generated cartoon splash concept at `app/src/main/res/drawable-nodpi/quickedl_splash_concept.png`.
  - Manifest now references `@mipmap/ic_launcher` / `@mipmap/ic_launcher_round`.

Resume check on 2026-06-09 after system freeze during compile:

- Re-ran the debug build command; result was `BUILD SUCCESSFUL in 53s`.
- Refreshed both root-level APK copies from the fresh build:
  - `latest_build/app-debug.apk`
  - `latest_build/quickedl-debug.apk`
- Confirmed ADB sees the connected Pixel 8.
- Installed the fresh debug APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk`; result was `Success`.
- Launched `com.quickedl/.MainActivity` and confirmed it was `RESUMED`, visible, focused, and running as PID `31019`.
- Confirmed the persisted Camera folder grant restored without reopening the picker.
- Confirmed the Camera folder now lists 5 videos.
- Confirmed sidecar files on the phone are intact:
  - `/sdcard/DCIM/Camera/VID_20260604_071146.edl`
  - `/sdcard/DCIM/Camera/VID_20260609_083129.edl`
- Selected `VID_20260604_071146.mp4` and confirmed lazy sidecar loading:
  - Header total updated to `00:00:02.045`.
  - Current row shows `> VID_20260604_071146.mp4` and `EDL 00:00:02.045`.
  - Status shows `VID_20260604_071146.mp4 | 1 ranges | 00:00:02.045`.
  - Primary timeline range highlighting was visible.
- Verified the file-list toggle changes `Hide list` to `Show list` and expands the review/player area while keeping controls visible.
- Observed a polish issue: the top-left `Folder` and list-toggle buttons are visually cramped in portrait and read like `Folder Hide list` / `Folder Show list`.
- Improved scrub responsiveness after device testing:
  - UI refresh is now adaptive: 100ms while playing or scrubbing, 500ms while idle.
  - Fast scrub, slow scrub, and primary timeline drag now refresh the transport time and timeline immediately after seeking.
  - Rebuilt, refreshed `latest_build/`, reinstalled on the Pixel 8, and confirmed `MainActivity` resumes after launch.
- Started `QuickMashup/` as a separate command-line companion tool:
  - Python standard-library CLI at `QuickMashup/quickmashup.py`.
  - Scans one or more directories recursively for videos with matching QuickEDL `.edl` sidecars.
  - Supports individual video file inputs and `--no-recursive` for top-level-only directory scanning.
  - Supports `--date`, `--from`, and `--to` filtering.
  - Uses `ffprobe` metadata, filename timestamps, then file mtime for initial clip date detection.
  - Detects camera family/mode heuristically for phone, Vuze+, VuzeXR, Insta360, and Qoocam Ego sources.
  - Extracts selected ranges with `ffmpeg`, normalizes to flat H.264/AAC output, and concatenates them.
  - Generates silent audio for source clips without audio unless `--no-audio` is used.
  - Includes `--dry-run`, `--skip-special`, output size/fps/CRF/preset options, README, and unit tests.
  - Verified with unit tests and synthetic ffmpeg smoke renders, including mixed source-audio and no-audio excerpts.

To resume next time:

1. Continue hands-on testing on the connected Pixel 8 or another Android device.
2. Hands-on validate scrub feel after the adaptive 100ms active refresh change.
3. Validate the new controls on device: lock/unlock, `Next Unmarked`, `Clear In/Out`, order-independent `Mark In` / `Mark Out`, timeline color state, and transport-row frame stepping.
4. Validate repeated range editing: multiple ranges, removing the range containing the playhead, and previous/next EDL boundary jumps.
5. Validate edge cases with longer real footage and different codecs.
6. Polish portrait top-bar spacing so `Folder` and the file-list toggle read as distinct controls.
7. Start exporter tooling: Android JSON for Open Video Editor compatibility, then OTIO, CMX 3600, and MLT XML.
8. Test QuickMashup against real copied camera folders, including recursive and `--no-recursive` scans, then add explicit per-camera time offset/sync support.

## State recovered after crash

The previous session appears to have finished most of the Android build environment setup but did not leave a final handoff. The repo now contains:

- Native Android QuickEDL app scaffold and first usable `MainActivity`.
- Gradle wrapper generated for Gradle `8.10.2`.
- `local.properties` pointing Gradle at the project-local SDK overlay: `/home/jon/quickedl/android-sdk`.
- Android platform 35, downloaded project-local Android platform 34, and a project-local `build-tools;34.0.0` overlay.
- Existing Gradle caches under `.gradle`.

The current directory also contains a `.git` directory, but it is an empty/read-only mount in this environment. `git status` fails with `fatal: not a git repository`, so this handoff is based on filesystem inspection and a fresh build run rather than Git history.

## Current build status

Command run:

```bash
GRADLE_USER_HOME=/home/jon/quickedl/.gradle ./gradlew assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL in 1m 26s`.

Gradle also warns:

```text
Warning: License for package Android SDK Platform-Tools not accepted.
WARNING: platform-tools package is not installed.
```

That warning is not blocking `assembleDebug`.

## Toolchain notes

AGP's bundled Maven `aapt2` is x86_64 and cannot run on this ARM64 host. `gradle.properties` now sets:

```properties
android.aapt2FromMavenOverride=/usr/bin/aapt2
```

This host has a Debian-packaged ARM64 `aapt2` available:

```text
/usr/bin/aapt2 -> /usr/lib/android-sdk/build-tools/debian/aapt2
/usr/lib/android-sdk/build-tools/debian/aapt2: ELF 64-bit LSB pie executable, ARM aarch64
```

Debian's `aapt2` is old enough that it fails to read the installed API 35 `android.jar` resource table:

```text
LoadedArsc.cpp:94 RES_TABLE_TYPE_TYPE entry offsets overlap actual entry data.
failed to load include path /home/jon/quickedl/android-sdk/platforms/android-35/android.jar
```

The working local path is to compile and target SDK 34. API 34 was downloaded from Google's Android repository as `platform-34-ext7_r03.zip`, SHA-1 `1f2e9478d6a7601425ceaa553311dc43191f103d`, and unpacked into `android-sdk/platforms/android-34`.

## App work still pending

- Broaden real-device testing beyond the first smoke test.
- Validate repeated SAF writes after additional multi-range edits.
- Validate the new locked-clip and next-unmarked workflows.
- Validate slow-scrub frame stepping with several video frame rates.
- Validate playback and scrubbing behavior with longer real footage and varied codecs.
- Add polish after real usage: keyboard/remote shortcuts, thumbnail previews, sort modes, and batch EDL summaries.
