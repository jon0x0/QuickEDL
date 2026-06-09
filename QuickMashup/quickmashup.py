#!/usr/bin/env python3
"""Build quick video mashups from QuickEDL sidecars."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


VIDEO_EXTENSIONS = {
    ".mp4",
    ".mov",
    ".mkv",
    ".avi",
    ".webm",
    ".m4v",
    ".ts",
    ".mts",
    ".m2ts",
    ".3gp",
    ".insv",
}

SPECIAL_MODES = {"flat_360", "3d_180", "3d_360"}


@dataclasses.dataclass(frozen=True)
class EdlRange:
    start_ms: int
    end_ms: int

    @property
    def duration_ms(self) -> int:
        return self.end_ms - self.start_ms


@dataclasses.dataclass(frozen=True)
class CameraInfo:
    family: str
    model: str
    mode: str
    confidence: str
    reason: str


@dataclasses.dataclass(frozen=True)
class Clip:
    path: Path
    edl_path: Path
    ranges: tuple[EdlRange, ...]
    clip_datetime: dt.datetime | None
    date_source: str
    camera: CameraInfo
    metadata: dict[str, Any]


@dataclasses.dataclass(frozen=True)
class Segment:
    clip: Clip
    edl_range: EdlRange
    index: int


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compile QuickEDL sidecar ranges into one review video.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""examples:
  Preview all selected ranges in one folder, recursively:
    python3 QuickMashup/quickmashup.py /media/pixel --dry-run

  Preview only files directly inside one folder:
    python3 QuickMashup/quickmashup.py /media/pixel --no-recursive --dry-run

  Render one explicit source file with its matching .edl sidecar:
    python3 QuickMashup/quickmashup.py /media/pixel/VID_20260609_120000.mp4 -o one-clip.mp4

  Render one day from multiple camera folders:
    python3 QuickMashup/quickmashup.py /media/pixel /media/insta360 --date 2026-06-09 -o mashup.mp4

  Render a date range at 1080p:
    python3 QuickMashup/quickmashup.py /media/pixel --from 2026-06-01 --to 2026-06-09 --width 1920 --height 1080 -o june.mp4

  Skip detected 3D/180/360 sources until special-format rendering is implemented:
    python3 QuickMashup/quickmashup.py /media/trip --skip-special -o flat-only.mp4

notes:
  QuickMashup currently creates a flat H.264/AAC MP4 review edit.
  3D/180/360 clips are detected and reported, but not spatially transformed yet.
  Future work will add YouTube-ready 3D/360 outputs from mixed camera sources.
""",
    )
    parser.add_argument("inputs", nargs="+", type=Path, help="Input media directories and/or video files.")
    parser.add_argument("-o", "--output", type=Path, default=Path("quickmashup.mp4"), help="Output MP4 path. Default: quickmashup.mp4")
    parser.add_argument("--date", help="Only include clips from this single date, YYYY-MM-DD.")
    parser.add_argument("--from", dest="date_from", help="Start date for filtering, YYYY-MM-DD.")
    parser.add_argument("--to", dest="date_to", help="End date for filtering, YYYY-MM-DD.")
    parser.add_argument("--recursive", dest="recursive", action="store_true", default=True, help="Scan input directories recursively. Default.")
    parser.add_argument("--no-recursive", dest="recursive", action="store_false", help="Only scan files directly inside input directories.")
    parser.add_argument("--dry-run", action="store_true", help="Print the plan without rendering.")
    parser.add_argument("--skip-special", action="store_true", help="Skip detected 3D/180/360 clips.")
    parser.add_argument("--keep-work", action="store_true", help="Keep intermediate segment files.")
    parser.add_argument("--ffmpeg", default="ffmpeg", help="ffmpeg executable path.")
    parser.add_argument("--ffprobe", default="ffprobe", help="ffprobe executable path.")
    parser.add_argument("--width", type=int, default=1280, help="Output width in pixels. Default: 1280")
    parser.add_argument("--height", type=int, default=720, help="Output height in pixels. Default: 720")
    parser.add_argument("--fps", type=float, default=30.0, help="Output frame rate. Default: 30")
    parser.add_argument("--crf", type=int, default=23, help="x264 CRF quality value. Lower is higher quality. Default: 23")
    parser.add_argument("--preset", default="veryfast", help="x264 preset. Default: veryfast")
    parser.add_argument("--no-audio", action="store_true", help="Drop audio in the output.")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    validate_args(args)

    clips = scan_clips(args.inputs, args.ffprobe, args.recursive)
    start_date, end_date = date_bounds(args)
    clips = filter_clips(clips, start_date, end_date, args.skip_special)
    segments = build_segments(clips)

    print_plan(clips, segments)
    sys.stdout.flush()
    if args.dry_run:
        return 0
    if not segments:
        print("No selected QuickEDL ranges matched the inputs.", file=sys.stderr)
        return 2

    render_segments(segments, args)
    return 0


def validate_args(args: argparse.Namespace) -> None:
    if args.date and (args.date_from or args.date_to):
        raise SystemExit("Use either --date or --from/--to, not both.")
    for input_path in args.inputs:
        if not input_path.exists():
            raise SystemExit(f"Input does not exist: {input_path}")
        if input_path.is_file() and input_path.suffix.lower() not in VIDEO_EXTENSIONS:
            raise SystemExit(f"Input file is not a supported video type: {input_path}")
        if not input_path.is_file() and not input_path.is_dir():
            raise SystemExit(f"Input is not a file or directory: {input_path}")
    if args.width <= 0 or args.height <= 0:
        raise SystemExit("--width and --height must be positive.")
    if args.fps <= 0:
        raise SystemExit("--fps must be positive.")
    if shutil.which(args.ffmpeg) is None:
        raise SystemExit(f"ffmpeg not found: {args.ffmpeg}")
    if shutil.which(args.ffprobe) is None:
        raise SystemExit(f"ffprobe not found: {args.ffprobe}")


def date_bounds(args: argparse.Namespace) -> tuple[dt.date | None, dt.date | None]:
    if args.date:
        day = parse_date_arg(args.date, "--date")
        return day, day
    start = parse_date_arg(args.date_from, "--from") if args.date_from else None
    end = parse_date_arg(args.date_to, "--to") if args.date_to else None
    if start and end and start > end:
        raise SystemExit("--from must be on or before --to.")
    return start, end


def parse_date_arg(value: str, label: str) -> dt.date:
    try:
        return dt.date.fromisoformat(value)
    except ValueError as exc:
        raise SystemExit(f"{label} must use YYYY-MM-DD: {value}") from exc


def scan_clips(inputs: list[Path], ffprobe: str, recursive: bool = True) -> list[Clip]:
    clips: list[Clip] = []
    seen: set[Path] = set()
    for input_path in inputs:
        for path in iter_input_videos(input_path, recursive):
            if not path.is_file() or path.suffix.lower() not in VIDEO_EXTENSIONS:
                continue
            resolved = path.resolve()
            if resolved in seen:
                continue
            seen.add(resolved)
            edl_path = path.with_suffix(".edl")
            if not edl_path.exists():
                continue
            ranges = tuple(parse_edl(edl_path))
            if not ranges:
                continue
            metadata = probe_metadata(path, ffprobe)
            clip_datetime, date_source = detect_clip_datetime(path, metadata)
            camera = detect_camera(path, metadata)
            clips.append(
                Clip(
                    path=path,
                    edl_path=edl_path,
                    ranges=ranges,
                    clip_datetime=clip_datetime,
                    date_source=date_source,
                    camera=camera,
                    metadata=metadata,
                )
            )
    return sorted(clips, key=clip_sort_key)


def iter_input_videos(input_path: Path, recursive: bool) -> list[Path]:
    if input_path.is_file():
        return [input_path]
    iterator = input_path.rglob("*") if recursive else input_path.iterdir()
    return sorted(path for path in iterator if path.is_file() and path.suffix.lower() in VIDEO_EXTENSIONS)


def parse_edl(path: Path) -> list[EdlRange]:
    ranges: list[EdlRange] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split()
        if len(parts) < 2:
            print(f"Skipping malformed EDL row {path}:{line_number}", file=sys.stderr)
            continue
        try:
            start_ms = int(parts[0])
            end_ms = int(parts[1])
        except ValueError:
            print(f"Skipping non-integer EDL row {path}:{line_number}", file=sys.stderr)
            continue
        if end_ms <= start_ms:
            print(f"Skipping empty EDL range {path}:{line_number}", file=sys.stderr)
            continue
        ranges.append(EdlRange(start_ms, end_ms))
    return ranges


def probe_metadata(path: Path, ffprobe: str) -> dict[str, Any]:
    command = [
        ffprobe,
        "-v",
        "error",
        "-print_format",
        "json",
        "-show_format",
        "-show_streams",
        str(path),
    ]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        return {"probe_error": result.stderr.strip()}
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return {"probe_error": "ffprobe returned invalid JSON"}


def detect_clip_datetime(path: Path, metadata: dict[str, Any]) -> tuple[dt.datetime | None, str]:
    filename_time = datetime_from_filename(path.name)
    if filename_time:
        return filename_time, "filename"

    metadata_time = datetime_from_metadata(metadata)
    if metadata_time:
        return metadata_time, "metadata"

    try:
        return dt.datetime.fromtimestamp(path.stat().st_mtime), "mtime"
    except OSError:
        return None, "unknown"


def datetime_from_filename(name: str) -> dt.datetime | None:
    patterns = [
        r"(?P<date>20\d{6})[_-]?(?P<time>\d{6})",
        r"(?P<date>20\d{2}[-_]\d{2}[-_]\d{2})[_ -]?(?P<time>\d{2}[-_]\d{2}[-_]\d{2})",
    ]
    for pattern in patterns:
        match = re.search(pattern, name)
        if not match:
            continue
        date_part = re.sub(r"\D", "", match.group("date"))
        time_part = re.sub(r"\D", "", match.group("time"))
        try:
            return dt.datetime.strptime(date_part + time_part, "%Y%m%d%H%M%S")
        except ValueError:
            continue
    return None


def datetime_from_metadata(metadata: dict[str, Any]) -> dt.datetime | None:
    values: list[str] = []
    tags = metadata.get("format", {}).get("tags", {})
    if isinstance(tags, dict):
        values.extend(str(value) for key, value in tags.items() if "time" in key.lower() or "date" in key.lower())
    for stream in metadata.get("streams", []):
        stream_tags = stream.get("tags", {})
        if isinstance(stream_tags, dict):
            values.extend(str(value) for key, value in stream_tags.items() if "time" in key.lower() or "date" in key.lower())

    for value in values:
        parsed = parse_metadata_datetime(value)
        if parsed:
            return parsed
    return None


def parse_metadata_datetime(value: str) -> dt.datetime | None:
    normalized = value.strip().replace("Z", "+00:00")
    for candidate in (normalized, normalized.replace(" ", "T")):
        try:
            parsed = dt.datetime.fromisoformat(candidate)
            return parsed.replace(tzinfo=None)
        except ValueError:
            pass
    return None


def detect_camera(path: Path, metadata: dict[str, Any]) -> CameraInfo:
    haystack = camera_haystack(path, metadata)
    width, height = first_video_dimensions(metadata)
    ratio = width / height if width and height else 0.0
    suffix = path.suffix.lower()

    if "qoocam" in haystack or "qoo cam" in haystack or "ego" in haystack:
        return CameraInfo("qoocam", "Qoocam Ego", infer_stereo_mode(width, height, "3d_180"), "medium", "Qoocam/Ego hint")
    if "vuze xr" in haystack or "vuzexr" in haystack:
        mode = infer_vuzexr_mode(haystack, width, height)
        return CameraInfo("vuze", "VuzeXR", mode, "medium", "VuzeXR hint")
    if "vuze" in haystack:
        return CameraInfo("vuze", "Vuze+", infer_stereo_mode(width, height, "3d_360"), "medium", "Vuze hint")
    if "insta360" in haystack or suffix == ".insv" or "insv" in haystack:
        return CameraInfo("insta360", "Insta360", infer_360_mode(width, height), "medium", "Insta360/INSV hint")
    if "pixel 8" in haystack or "pixel8" in haystack:
        return CameraInfo("mobile", "Pixel 8", "flat", "medium", "Pixel 8 metadata/name hint")
    if "s20" in haystack or "sm-g" in haystack or "samsung" in haystack:
        return CameraInfo("mobile", "Samsung S20+ 5G", "flat", "medium", "Samsung metadata/name hint")
    if "iphone" in haystack or "apple" in haystack:
        return CameraInfo("mobile", "iPhone", "flat", "medium", "Apple metadata/name hint")
    if path.name.upper().startswith("VID_") and ratio < 2.2:
        return CameraInfo("mobile", "Android phone", "flat", "low", "VID_ filename pattern")
    return CameraInfo("unknown", "Unknown", infer_unknown_mode(width, height), "low", "no camera-specific hint")


def camera_haystack(path: Path, metadata: dict[str, Any]) -> str:
    parts = [path.name, str(path.parent)]
    tags = metadata.get("format", {}).get("tags", {})
    if isinstance(tags, dict):
        parts.extend(str(key) for key in tags.keys())
        parts.extend(str(value) for value in tags.values())
    for stream in metadata.get("streams", []):
        stream_tags = stream.get("tags", {})
        if isinstance(stream_tags, dict):
            parts.extend(str(key) for key in stream_tags.keys())
            parts.extend(str(value) for value in stream_tags.values())
    return " ".join(parts).lower()


def first_video_dimensions(metadata: dict[str, Any]) -> tuple[int | None, int | None]:
    for stream in metadata.get("streams", []):
        if stream.get("codec_type") == "video":
            width = stream.get("width")
            height = stream.get("height")
            if isinstance(width, int) and isinstance(height, int):
                return width, height
    return None, None


def infer_vuzexr_mode(haystack: str, width: int | None, height: int | None) -> str:
    if "180" in haystack or "3d" in haystack or "vr180" in haystack:
        return "3d_180"
    if "360" in haystack:
        return "flat_360"
    return infer_unknown_mode(width, height)


def infer_stereo_mode(width: int | None, height: int | None, default: str) -> str:
    if not width or not height:
        return default
    ratio = width / height
    if ratio >= 3.4:
        return "3d_180"
    if ratio >= 1.7:
        return default
    return default


def infer_360_mode(width: int | None, height: int | None) -> str:
    if not width or not height:
        return "flat_360"
    ratio = width / height
    if ratio >= 3.4:
        return "3d_360"
    return "flat_360"


def infer_unknown_mode(width: int | None, height: int | None) -> str:
    if not width or not height:
        return "unknown"
    ratio = width / height
    if ratio >= 3.4:
        return "unknown"
    if 1.85 <= ratio <= 2.15:
        return "flat_360"
    return "flat"


def filter_clips(
    clips: list[Clip],
    start_date: dt.date | None,
    end_date: dt.date | None,
    skip_special: bool,
) -> list[Clip]:
    filtered: list[Clip] = []
    for clip in clips:
        if skip_special and clip.camera.mode in SPECIAL_MODES:
            continue
        if start_date or end_date:
            if clip.clip_datetime is None:
                continue
            clip_date = clip.clip_datetime.date()
            if start_date and clip_date < start_date:
                continue
            if end_date and clip_date > end_date:
                continue
        filtered.append(clip)
    return filtered


def build_segments(clips: list[Clip]) -> list[Segment]:
    segments: list[Segment] = []
    index = 1
    for clip in clips:
        for edl_range in sorted(clip.ranges, key=lambda item: item.start_ms):
            segments.append(Segment(clip=clip, edl_range=edl_range, index=index))
            index += 1
    return segments


def clip_sort_key(clip: Clip) -> tuple[str, str]:
    timestamp = clip.clip_datetime.isoformat() if clip.clip_datetime else "9999"
    return timestamp, str(clip.path)


def print_plan(clips: list[Clip], segments: list[Segment]) -> None:
    total_ms = sum(segment.edl_range.duration_ms for segment in segments)
    print(f"QuickMashup plan: {len(clips)} clips, {len(segments)} excerpts, {format_duration(total_ms)} selected")
    families: dict[str, int] = {}
    modes: dict[str, int] = {}
    for clip in clips:
        families[clip.camera.model] = families.get(clip.camera.model, 0) + 1
        modes[clip.camera.mode] = modes.get(clip.camera.mode, 0) + 1
    if families:
        print("Cameras: " + ", ".join(f"{name}={count}" for name, count in sorted(families.items())))
    if modes:
        print("Modes: " + ", ".join(f"{name}={count}" for name, count in sorted(modes.items())))
    for segment in segments:
        clip_time = segment.clip.clip_datetime.isoformat(sep=" ") if segment.clip.clip_datetime else "unknown time"
        print(
            f"{segment.index:03d}  {segment.clip.path}  "
            f"{format_ms(segment.edl_range.start_ms)}-{format_ms(segment.edl_range.end_ms)}  "
            f"{clip_time} ({segment.clip.date_source})  "
            f"{segment.clip.camera.model}/{segment.clip.camera.mode}"
        )


def render_segments(segments: list[Segment], args: argparse.Namespace) -> None:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="quickmashup-") as temp_name:
        work_dir = Path(temp_name)
        if args.keep_work:
            keep_dir = args.output.with_suffix(args.output.suffix + ".work")
            if keep_dir.exists():
                raise SystemExit(f"Work directory already exists: {keep_dir}")
            work_dir = keep_dir
            work_dir.mkdir(parents=True)
        segment_paths = []
        for segment in segments:
            output_path = work_dir / f"segment-{segment.index:04d}.mp4"
            extract_segment(segment, output_path, args)
            segment_paths.append(output_path)
        concat_file = work_dir / "concat.txt"
        concat_file.write_text(
            "".join(f"file '{escape_concat_path(path)}'\n" for path in segment_paths),
            encoding="utf-8",
        )
        concat_segments(concat_file, args.output, args)
        if args.keep_work:
            print(f"Kept intermediate files in {work_dir}")


def extract_segment(segment: Segment, output_path: Path, args: argparse.Namespace) -> None:
    start_seconds = segment.edl_range.start_ms / 1000.0
    duration_seconds = segment.edl_range.duration_ms / 1000.0
    vf = (
        f"scale={args.width}:{args.height}:force_original_aspect_ratio=decrease,"
        f"pad={args.width}:{args.height}:(ow-iw)/2:(oh-ih)/2,"
        f"fps={args.fps},format=yuv420p,setsar=1"
    )
    command = [
        args.ffmpeg,
        "-hide_banner",
        "-y",
        "-ss",
        f"{start_seconds:.3f}",
        "-t",
        f"{duration_seconds:.3f}",
        "-i",
        str(segment.clip.path),
    ]
    clip_has_audio = has_audio(segment.clip.metadata)
    if not args.no_audio and not clip_has_audio:
        command.extend(
            [
                "-f",
                "lavfi",
                "-t",
                f"{duration_seconds:.3f}",
                "-i",
                "anullsrc=channel_layout=stereo:sample_rate=48000",
            ]
        )
    command.extend(["-map", "0:v:0"])
    if not args.no_audio:
        command.extend(["-map", "0:a:0" if clip_has_audio else "1:a:0"])
    command.extend(
        [
            "-vf",
            vf,
            "-c:v",
            "libx264",
            "-preset",
            args.preset,
            "-crf",
            str(args.crf),
            "-movflags",
            "+faststart",
        ]
    )
    if args.no_audio:
        command.extend(["-an"])
    else:
        command.extend(["-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2", "-af", "aresample=async=1:first_pts=0"])
    command.append(str(output_path))
    run_command(command)


def concat_segments(concat_file: Path, output_path: Path, args: argparse.Namespace) -> None:
    command = [
        args.ffmpeg,
        "-hide_banner",
        "-y",
        "-f",
        "concat",
        "-safe",
        "0",
        "-i",
        str(concat_file),
    ]
    if args.no_audio:
        command.extend(["-c", "copy"])
    else:
        command.extend(["-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2"])
    command.extend(["-movflags", "+faststart", str(output_path)])
    run_command(command)
    print(f"Wrote {output_path}")


def run_command(command: list[str]) -> None:
    printable = " ".join(str(part) for part in command)
    print(printable, flush=True)
    result = subprocess.run(command, check=False)
    if result.returncode != 0:
        raise SystemExit(f"Command failed with exit code {result.returncode}: {printable}")


def has_audio(metadata: dict[str, Any]) -> bool:
    return any(stream.get("codec_type") == "audio" for stream in metadata.get("streams", []))


def escape_concat_path(path: Path) -> str:
    return str(path).replace("'", "'\\''")


def format_duration(ms: int) -> str:
    seconds = ms / 1000.0
    return f"{seconds:.3f}s"


def format_ms(ms: int) -> str:
    total_seconds, millis = divmod(ms, 1000)
    minutes, seconds = divmod(total_seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}.{millis:03d}"


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
