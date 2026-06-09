import datetime as dt
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import quickmashup


class QuickMashupTests(unittest.TestCase):
    def test_parse_edl_reads_valid_ranges(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "clip.edl"
            path.write_text(
                "# QuickEDL v1\n"
                "# start_ms end_ms start_time end_time\n"
                "1000 2500 00:00:01.000 00:00:02.500\n"
                "3000 4000\n",
                encoding="utf-8",
            )

            ranges = quickmashup.parse_edl(path)

        self.assertEqual([(item.start_ms, item.end_ms) for item in ranges], [(1000, 2500), (3000, 4000)])

    def test_filename_datetime_detection(self):
        parsed = quickmashup.datetime_from_filename("VID_20260604_071146.mp4")

        self.assertEqual(parsed, dt.datetime(2026, 6, 4, 7, 11, 46))

    def test_camera_detection_pixel_filename(self):
        info = quickmashup.detect_camera(Path("VID_20260604_071146.mp4"), {"streams": [{"codec_type": "video", "width": 1920, "height": 1080}]})

        self.assertEqual(info.family, "mobile")
        self.assertEqual(info.mode, "flat")

    def test_camera_detection_insta360_extension(self):
        info = quickmashup.detect_camera(Path("VID_001.insv"), {"streams": [{"codec_type": "video", "width": 5760, "height": 2880}]})

        self.assertEqual(info.family, "insta360")
        self.assertEqual(info.mode, "flat_360")

    def test_iter_input_videos_respects_recursion(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            top = root / "VID_20260609_120000.mp4"
            nested_dir = root / "nested"
            nested_dir.mkdir()
            nested = nested_dir / "VID_20260609_130000.mp4"
            top.write_bytes(b"")
            nested.write_bytes(b"")

            recursive = quickmashup.iter_input_videos(root, recursive=True)
            flat = quickmashup.iter_input_videos(root, recursive=False)

        self.assertEqual([item.name for item in recursive], ["VID_20260609_120000.mp4", "VID_20260609_130000.mp4"])
        self.assertEqual([item.name for item in flat], ["VID_20260609_120000.mp4"])

    def test_iter_input_videos_accepts_single_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "VID_20260609_120000.mp4"
            path.write_bytes(b"")

            self.assertEqual(quickmashup.iter_input_videos(path, recursive=False), [path])


if __name__ == "__main__":
    unittest.main()
