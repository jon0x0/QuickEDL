# QuickEDL

QuickEDL is a small native Android app for reviewing footage and writing sidecar EDL decisions quickly.

## What it does

- Opens a folder with Android's system folder picker and remembers the last folder.
- Lists common video files in that folder.
- Shows which videos already have a matching `.edl` sidecar file.
- Shows total selected `In` time for the current folder.
- Plays videos with AndroidX Media3 / ExoPlayer.
- Provides normal playback, fast full-duration scrubbing, and a slow scrub control around the current frame.
- Adds `In` / `Out` ranges, jumps to previous/next EDL boundary, and removes the EDL range containing the current playhead.

## EDL format

Each sidecar file has the same base filename as the video and the `.edl` extension:

```text
# QuickEDL v1
# start_ms end_ms start_time end_time
1250 8420 00:00:01.250 00:00:08.420
22000 30333 00:00:22.000 00:00:30.333
```

The app reads the first two columns as millisecond timestamps. The formatted time columns are written for humans.

See [EDL_FORMAT.md](EDL_FORMAT.md) for the full sidecar specification and compatibility/export recommendations.

## Build

Open this directory in Android Studio, let Gradle sync, then run the `app` configuration on a device or emulator.

The project uses:

- Android Gradle Plugin `8.5.2`
- Kotlin `2.0.20`
- AndroidX Media3 `1.4.1`
- Compile / target SDK `34` in this ARM64 workspace build path
