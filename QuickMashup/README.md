# QuickMashup

QuickMashup is the first command-line companion for QuickEDL. It scans one or more media directories, reads QuickEDL `.edl` sidecars, extracts the selected ranges, and compiles them into one quick review video.

The first implementation is intentionally local and scriptable:

- Python standard library only.
- Uses system `ffmpeg` / `ffprobe` for media work.
- Handles mixed camera folders by normalizing excerpts to a common flat output format.
- Detects camera family and capture mode heuristically, then reports what it found.

Special formats such as 3D, 180, and 360 are detected but not transformed yet. By default they are included as ordinary flat frames so the quick edit still works. Use `--skip-special` to exclude non-flat detected clips.

## Usage

Show built-in help:

```bash
python3 QuickMashup/quickmashup.py --help
```

```bash
python3 QuickMashup/quickmashup.py /path/to/camera-folder -o mashup.mp4
```

Multiple folders:

```bash
python3 QuickMashup/quickmashup.py /media/pixel /media/insta360 /media/vuze -o trip.mp4
```

One explicit video file:

```bash
python3 QuickMashup/quickmashup.py /media/pixel/VID_20260609_120000.mp4 -o one-clip.mp4
```

Directory recursion is on by default. Turn it off when you only want files directly inside the provided folder:

```bash
python3 QuickMashup/quickmashup.py /media/pixel --no-recursive --dry-run
```

Single date:

```bash
python3 QuickMashup/quickmashup.py /media/pixel --date 2026-06-09 -o 2026-06-09.mp4
```

Date range:

```bash
python3 QuickMashup/quickmashup.py /media/pixel --from 2026-06-01 --to 2026-06-09 -o june-selects.mp4
```

Preview the plan without rendering:

```bash
python3 QuickMashup/quickmashup.py /media/pixel --dry-run
```

Skip detected 3D/180/360 clips until special-format output is implemented:

```bash
python3 QuickMashup/quickmashup.py /media/trip --skip-special -o flat-only.mp4
```

## How Clips Are Chosen

1. Scan input directories for video files. Recursion is on by default; use `--no-recursive` for top-level files only. Individual video files may also be passed directly.
2. Find a matching `.edl` sidecar next to each video.
3. Parse QuickEDL v1 rows: `start_ms end_ms ...`.
4. If a date/date range is provided, filter by the best available clip date.
5. Sort by detected clip date, then path, then EDL range start.
6. Extract each range and concatenate the excerpts.

Date detection currently prefers:

1. Filename timestamps, such as `VID_20260604_071146.mp4`.
2. Media metadata timestamps from `ffprobe`, such as `creation_time`.
3. Filesystem modified time.

Camera clocks may be wrong. Later sync work should add explicit offsets, audio/video sync marks, and per-camera clock correction.

## Camera Detection

The detector is intentionally heuristic in this first version. It reports:

- Mobile phone camera: Pixel, Samsung S-series, iPhone.
- Vuze+ 360 3D.
- VuzeXR 3D 180 or flat 360 when metadata/filename hints are available.
- Insta360 X2 or other Insta360 variants.
- Qoocam Ego.
- Unknown.

Mode detection reports one of:

- `flat`
- `flat_360`
- `3d_180`
- `3d_360`
- `unknown`

The render path currently treats all modes as flat video frames unless `--skip-special` is used.

## Current Limitation: Spatial Video

QuickMashup does not yet produce true 3D, 180, or 360 output. The current renderer makes a flat review MP4 from selected ranges. This is useful for quick selects, but it is not the final long-term target.

The long-term output goal is to generate YouTube-uploadable spatial video from mixed sources:

- Flat phone clips as normal timeline material.
- Vuze+ as 3D 360 source material.
- VuzeXR as either 3D 180 or flat 360 source material.
- Insta360 X2 and related cameras as flat 360 source material.
- Qoocam Ego as 3D 180 source material.

Before implementing upload-ready spatial export, verify the current YouTube metadata/container requirements and the current camera-specific projection/stereo layouts. Then add explicit projection conversion, stereo layout handling, metadata injection, and validation with a short private upload.

## Output Defaults

Default render settings are conservative for Raspberry Pi 5:

- H.264 via `libx264`
- AAC audio
- 1280x720
- 30 fps
- CRF 23
- `veryfast` preset

Override with:

```bash
python3 QuickMashup/quickmashup.py /media/pixel --width 1920 --height 1080 --fps 30 --crf 21 --preset faster
```

## Examples

Create a quick 720p flat review from all selected ranges in a phone camera folder:

```bash
python3 QuickMashup/quickmashup.py ~/Videos/Pixel8/DCIM/Camera -o pixel-selects.mp4
```

Create a flat review from only the top-level files in a folder:

```bash
python3 QuickMashup/quickmashup.py ~/Videos/Pixel8/DCIM/Camera --no-recursive -o pixel-top-level-selects.mp4
```

Create a flat review edit from Pixel and Insta360 folders for one day:

```bash
python3 QuickMashup/quickmashup.py ~/Videos/Pixel8 ~/Videos/Insta360 --date 2026-06-09 -o trip-2026-06-09.mp4
```

Create a 1080p review edit from a date range:

```bash
python3 QuickMashup/quickmashup.py ~/Videos/Trip --from 2026-06-01 --to 2026-06-09 --width 1920 --height 1080 -o june-trip-review.mp4
```

Keep intermediate segment files while debugging a render:

```bash
python3 QuickMashup/quickmashup.py ~/Videos/Trip --keep-work -o debug-mashup.mp4
```

Render without audio:

```bash
python3 QuickMashup/quickmashup.py ~/Videos/Trip --no-audio -o silent-review.mp4
```
