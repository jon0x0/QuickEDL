# QuickMashup Task List

## Done

- Created separate `QuickMashup/` project directory.
- Added `quickmashup.py` Python CLI.
- Added scan for video files with matching QuickEDL `.edl` sidecars.
- Added support for one or more input directories.
- Added support for individual video file inputs.
- Added recursive directory scanning by default.
- Added `--no-recursive` for top-level-only directory scanning.
- Added QuickEDL v1 sidecar parser for `start_ms end_ms` ranges.
- Added optional `--date`, `--from`, and `--to` filtering.
- Added dry-run planning output.
- Added basic clip date detection:
  - Filename timestamps.
  - `ffprobe` metadata timestamps.
  - Filesystem modified time fallback.
- Added heuristic camera and mode detection for:
  - Pixel / Android phone video.
  - Samsung S-series phone video.
  - iPhone video.
  - Vuze+.
  - VuzeXR.
  - Insta360 / `.insv`.
  - Qoocam Ego.
- Added flat review rendering through `ffmpeg`.
- Added output normalization to H.264/AAC MP4 with configurable size, FPS, CRF, and x264 preset.
- Added generated silent audio for source clips without audio unless `--no-audio` is used.
- Added `--skip-special` for detected 3D/180/360 sources.
- Added README usage docs and examples.
- Added expanded `--help` examples.
- Added unit tests for EDL parsing, filename date parsing, and camera detection.
- Verified with synthetic ffmpeg smoke renders, including mixed audio/no-audio sources.

## In Progress

- Validate QuickMashup against real copied camera folders from the current QuickEDL workflow.
- Improve camera/mode detection using real metadata samples instead of filename/layout heuristics only.
- Define the first durable intermediate timeline model for mixed camera sources.

## Next

- Run `--dry-run` on real Pixel 8 camera folders with existing QuickEDL sidecars.
- Validate `--no-recursive` against a copied camera folder with nested test media.
- Validate direct single-video-file input.
- Run a real flat review render from one phone folder.
- Run a real flat review render from two or more input folders.
- Confirm date filtering behavior against real camera filenames and metadata.
- Add a `--report-json` or `--plan-json` output for machine-readable render plans.
- Add per-input or per-camera clock offset options for rough sync correction.
- Add explicit camera profile overrides for cases where auto-detection is wrong.
- Add fixture metadata samples for Pixel 8, S20+ 5G, iPhone, Vuze+, VuzeXR, Insta360 X2, and Qoocam Ego.
- Add tests for Vuze+, VuzeXR, iPhone, Samsung, Insta360, and Qoocam Ego detection.
- Add a manifest format that records source path, camera family, mode, date source, EDL range, and output timeline position.

## Spatial Video Roadmap

- Research and verify current YouTube 3D/360 upload requirements before implementation.
- Decide supported final spatial output modes:
  - Flat 2D review MP4.
  - Monoscopic 360.
  - Stereoscopic 180.
  - Stereoscopic 360.
- Define how flat phone video should be placed inside a 180/360 canvas:
  - Full-frame flat insert.
  - Picture-in-360 panel.
  - Optional black/blurred spatial background.
- Define per-camera source layout rules:
  - Vuze+ 3D 360.
  - VuzeXR 3D 180.
  - VuzeXR flat 360.
  - Insta360 flat 360.
  - Qoocam Ego 3D 180.
- Add projection/layout transforms with ffmpeg filters or a dedicated processing library.
- Add metadata injection for YouTube-recognized spatial video outputs.
- Add validation steps to confirm YouTube recognizes uploaded test files as 3D/360.
- Add a compatibility mode that emits editor-friendly intermediate files before final spatial export.

## Open Questions

- Which final spatial mode should be the first target: monoscopic 360, stereoscopic 180, or stereoscopic 360?
- Should mixed flat and spatial sources be combined into one spatial canvas, or should flat clips remain flat cuts in a standard review edit?
- How should camera clock drift be corrected when multiple cameras run for long periods?
- Should sync be based on manual offsets, audio correlation, visual clap/flash markers, GPS time, or external metadata?
- Should QuickMashup eventually run entirely on Android, or should Android hand off a manifest to a desktop/Pi renderer?
