# QuickMashup Roadmap

## Purpose

QuickMashup turns QuickEDL sidecar ranges into compiled review videos. The immediate goal is a fast Raspberry Pi 5 command-line renderer. The long-term goal is a mixed-camera compiler that can produce flat review edits and YouTube-uploadable 3D/180/360 videos.

## Current Phase: Flat Review Compiler

The current tool is deliberately simple:

- Read QuickEDL sidecars.
- Accept one or more input directories and/or individual video files.
- Scan directories recursively by default, with `--no-recursive` for top-level-only scans.
- Filter by date or date range.
- Sort selected excerpts into a first-pass timeline.
- Normalize excerpts into one flat H.264/AAC MP4.
- Report detected camera family and source mode.

This phase is about fast proof of workflow, not final spatial output.

## Camera Families

Supported detection targets:

- Mobile phone cameras:
  - Pixel 8.
  - Samsung S20+ 5G.
  - iPhone.
- Vuze+ 360 3D camera.
- VuzeXR:
  - 3D 180.
  - Flat 360.
- Insta360 X2 and related variants.
- Qoocam Ego.

Detection is currently heuristic. Real sample metadata should drive the next pass.

## Timeline And Sync Strategy

The first ordering strategy is simple:

1. Use filename timestamp when available.
2. Fall back to media metadata timestamp.
3. Fall back to filesystem modified time.
4. Sort by detected time, path, and EDL range start.

This is not enough for multi-camera sync. The roadmap should add:

- Per-folder offset.
- Per-camera profile offset.
- Per-clip manual offset.
- Clock drift correction.
- Optional audio correlation.
- Optional visual sync marker support.
- A saved plan/manifest that records all offsets and chosen sync sources.

## Spatial Output Goal

Future QuickMashup should generate outputs suitable for upload to YouTube as spatial video. Before implementation, verify current YouTube requirements for projection metadata, stereo layout metadata, codec/container constraints, and upload validation.

Candidate output modes:

- Flat 2D MP4.
- Monoscopic equirectangular 360.
- Stereoscopic 180.
- Stereoscopic 360.

Mixed-source policy needs to be explicit. For example, flat phone clips could be:

- Rendered as normal full-frame cuts in flat mode.
- Placed as panels inside a 360 canvas.
- Used as inserts over 3D/360 footage.
- Excluded from spatial exports unless explicitly enabled.

## Implementation Milestones

1. Real flat review validation.
2. Structured render plan JSON.
3. Stronger camera detection from real metadata samples.
4. Manual sync offsets and timeline manifest.
5. Spatial source layout database.
6. First YouTube-ready monoscopic 360 export.
7. First stereoscopic 180 export.
8. First stereoscopic 360 export.
9. Android handoff or Android-native compile workflow.

## Validation

Each output mode needs a concrete validation checklist:

- Local ffprobe metadata inspection.
- Local playback check.
- Duration and A/V sync check.
- Correct projection/stereo metadata check.
- Short private upload test where YouTube recognizes the intended format.
- Regression sample for every supported camera family.
