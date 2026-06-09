# QuickEDL Sidecar Format

QuickEDL stores edit decisions as one sidecar file per source video. The sidecar is meant to be small, human-readable, easy for scripts to parse, and stable enough to generate interchange formats for other editors.

## Filename

Each sidecar lives next to its source media and uses the same base filename with the `.edl` extension:

```text
VID_20260604_071146.mp4
VID_20260604_071146.edl
```

## Version 1 Format

QuickEDL v1 is a plain UTF-8 text file. Comment lines begin with `#`. Data rows are whitespace-separated.

```text
# QuickEDL v1
# start_ms end_ms start_time end_time
2013 4058 00:00:02.013 00:00:04.058
12500 18100 00:00:12.500 00:00:18.100
```

Columns:

- `start_ms`: selected range start, in integer milliseconds from the beginning of the source clip.
- `end_ms`: selected range end, in integer milliseconds from the beginning of the source clip.
- `start_time`: human-readable start time, written for review.
- `end_time`: human-readable end time, written for review.

The app reads only the first two columns. Extra columns and comments should be preserved by future tooling when practical, but scripts should treat `start_ms` and `end_ms` as authoritative.

## Semantics

- Ranges are inclusive for navigation but should be treated as `[start_ms, end_ms)` for timeline generation.
- `end_ms` must be greater than `start_ms`.
- QuickEDL sorts ranges by start time and merges overlapping or touching ranges before saving.
- Times are relative to the source file, not to a final assembled timeline.
- The sidecar does not currently store frame rate, reel name, track routing, transitions, audio/video separation, speed changes, or source timecode.

## Why This Is Not CMX 3600 EDL

Traditional editing EDLs, especially CMX 3600, are timeline interchange files. They describe timeline events with reel names, source in/out, record in/out, tracks, edit types, and SMPTE timecode.

QuickEDL sidecars describe only useful source ranges. That is intentionally simpler and better for field review and script-based workflows, but it means the sidecar is not a complete Resolve/Kdenlive/Shotcut timeline by itself.

## Recommended Compatibility Strategy

Use the QuickEDL sidecar as the canonical internal format, then generate export files as needed.

Recommended export order:

1. Open Video Editor Android integration
   - Best native Android open-source compatibility target.
   - `devhyper/open-video-editor` is a GPL-licensed Android video editor built with Media3 and Jetpack Compose.
   - Its published feature set is currently centered on clip operations such as trim, grayscale, resolution, scale, and rotate.
   - Because no stable public project-file format is documented, the practical path is likely either:
     - a QuickEDL export/share format that Open Video Editor can import, or
     - a small upstream contribution to Open Video Editor to accept QuickEDL sidecars or a generated JSON timeline.

2. `*.otio` / OpenTimelineIO
   - Best general-purpose script interchange target.
   - Can represent source clips, ranges, metadata, and generated timelines cleanly.
   - Can then be converted through OTIO adapters, including CMX 3600 EDL where appropriate.

3. CMX 3600 `.edl`
   - Best target for DaVinci Resolve and legacy EDL workflows.
   - Use when the desired output is a simple cuts-only timeline.
   - Requires choosing a project frame rate and generating reel names/timecode.

4. MLT XML / `.mlt` or `.kdenlive`
   - Useful for Kdenlive, Shotcut, and other MLT-based editors.
   - Better than CMX when preserving file paths and simple generated timelines for open-source tools.

This avoids writing many formats during review. QuickEDL keeps one simple sidecar per clip, and export scripts create editor-specific outputs only when needed.

## Suggested Script Assembly Model

For script-based editing, a folder-level export can assemble all selected ranges into a timeline:

1. Scan video files in folder order or selected sort order.
2. For each video, load matching QuickEDL sidecar.
3. Append each selected range to an output sequence.
4. Preserve source filename, source URI/path, `start_ms`, `end_ms`, and optional metadata.
5. Export to OTIO first, then optionally convert to CMX 3600 or MLT XML.

Example intermediate model:

```json
{
  "source": "VID_20260604_071146.mp4",
  "ranges": [
    {
      "start_ms": 2013,
      "end_ms": 4058
    }
  ]
}
```

## Android Native Target: Open Video Editor

The most relevant Android-native open-source editor target is currently:

```text
Package: io.github.devhyper.openvideoeditor
Project: https://github.com/devhyper/open-video-editor
```

Recommended approach:

1. Keep QuickEDL sidecars canonical.
2. Add a folder-level `quickedl.json` export that lists source files and selected ranges.
3. Add Android share/open support for that JSON package.
4. If Open Video Editor does not already import project JSON, propose an upstream import path:
   - Accept `application/vnd.quickedl+json`.
   - Resolve media through Android Storage Access Framework URIs when available.
   - Build a simple cuts-only composition from selected ranges.

Suggested JSON shape:

```json
{
  "format": "QuickEDL",
  "version": 1,
  "timeline": {
    "name": "Camera selects",
    "frame_rate": 30
  },
  "clips": [
    {
      "name": "VID_20260604_071146.mp4",
      "uri": "content://...",
      "path_hint": "VID_20260604_071146.mp4",
      "ranges": [
        {
          "start_ms": 2013,
          "end_ms": 4058
        }
      ]
    }
  ]
}
```

This would give QuickEDL a native Android interchange format without replacing the per-clip `.edl` sidecars.

## CMX 3600 Export Notes

CMX 3600 export should be generated, not stored as the sidecar. An exporter needs:

- Project frame rate, such as `23.976`, `24`, `25`, `29.97`, `30`, `50`, `59.94`, or `60`.
- Source start timecode. If unknown, use `00:00:00:00`.
- Reel name mapping. CMX reel names are short, so filenames often need deterministic truncation or aliases.
- Record timeline start timecode, commonly `01:00:00:00`.
- Track choice, usually video-only or video+audio cuts.

Limitations:

- CMX 3600 is good for cuts-only timelines.
- It is not ideal for rich metadata, long file paths, arbitrary notes, multiple versions, or complex generated edits.
- Millisecond timestamps must be converted to frame-accurate SMPTE timecode using the chosen frame rate.

## Future QuickEDL Extensions

If the sidecar needs more data later, prefer comment-based metadata before changing the row format:

```text
# QuickEDL v1
# source_fps 29.97
# source_timecode_start 00:00:00:00
# note Interview selects
# start_ms end_ms start_time end_time
2013 4058 00:00:02.013 00:00:04.058
```

This keeps older readers working because they already ignore comment lines.

Potential metadata:

- Source frame rate.
- Source starting timecode.
- Reel alias.
- Clip lock state.
- Clip rating or tags.
- Transcript/script references.
- Reviewer notes.

## Current Recommendation

Do not replace the QuickEDL sidecar with CMX 3600. Keep the sidecar simple and script-friendly, and add exporters:

- `quickedl export android-json` for Open Video Editor or other Android-native editors.
- `quickedl export otio` as the primary timeline export.
- `quickedl export cmx3600` for DaVinci Resolve import.
- `quickedl export mlt` for Kdenlive/Shotcut-style open-source workflows.

The first exporter should probably be OTIO because it can serve both script automation and downstream conversion better than a legacy EDL.
