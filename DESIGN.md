# QuickEDL Design Notes

## Product goal

QuickEDL is an Android review tool for quickly marking useful footage ranges while watching video. It should be fast enough for field review and simple enough that the sidecar files remain editable outside the app.

## Core decisions

- Native Android with Kotlin.
- Programmatic Android views for the first version, avoiding a large UI framework while the workflow is still being shaped.
- AndroidX Media3 / ExoPlayer for broad playback format support.
- Android Storage Access Framework directory picker so the app can work with user-selected folders without broad storage permissions.
- One `.edl` sidecar per video, stored in the same folder and using the same filename prefix as the video.
- EDL timestamps are stored as integer milliseconds, with readable time strings included as extra columns.
- Directory state is remembered by persisting the SAF tree URI in shared preferences.

## EDL behavior

- Pressing `In` records the current playhead as a pending start point.
- Pressing `Out` with a pending start writes a range from start to end.
- If `Out` is before `In`, the range is normalized so the earlier timestamp is first.
- The app sorts and merges overlapping or touching ranges before saving.
- `Prev` and `Next` jump between all EDL boundaries for the current video.
- `Remove` deletes the range containing the current playhead; if no range contains it, it removes the nearest range within one second.

## Browser behavior

- The current folder list includes common video extensions.
- Each row shows whether a matching `.edl` file exists and the total selected time in that file.
- The header shows total selected time across the current directory.

## Scrubbing behavior

- Fast scrub maps across the full media duration.
- Slow scrub anchors at the current playhead when touched, then moves in small increments around that anchor.

## Known constraints

- Build tooling is not installed in this workspace yet, so the first pass cannot be compiled locally here.
- Gradle wrapper files are not generated yet because no local `gradle` command is available.
- Storage providers can vary in behavior; testing should include local device storage and removable media if that is part of the real workflow.
