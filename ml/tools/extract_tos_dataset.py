#!/usr/bin/env python3
"""
AutoDora-NEXT Semi-Automatic ToS Video/Image Frame Extractor & 6x5 Orb Cropper
==============================================================================

Downloads public Tower of Saviors (神魔之塔) gameplay videos via `yt-dlp` (or reads
local video/image files), extracts keyframes via `ffmpeg`, automatically locates the
6x5 battle board (works on BOTH native vertical phone videos AND 16:9 YouTube videos
with sidebars!), filters out non-battle/duplicate frames, crops 64x64 individual orb
tiles for Phase 4 LiteRT CNN training, and builds a visual contact sheet for fast QA.

IMPORTANT COMPLIANCE NOTE:
  All output media MUST be saved under gitignored directories (`backups/` or
  `ml/datasets/`). Never commit copyrighted third-party game imagery to Git.

Usage Examples:
  # 1) Single YouTube URL (default: 0.5 fps, auto-detect 6x5 board, crop 64x64 orbs)
  python ml/tools/extract_tos_dataset.py --url "https://www.youtube.com/watch?v=XXXX" --tag scorching_jail

  # 2) YouTube URL with specific time window (e.g. R3 boss wave only: 01:20 to 02:30)
  python ml/tools/extract_tos_dataset.py --url "https://youtu.be/XXXX" --start 01:20 --end 02:30 --fps 1.0 --tag boss_r3

  # 3) Batch file of YouTube URLs (one per line: `URL` or `URL | tag | start-end`)
  python ml/tools/extract_tos_dataset.py --urls-file backups/video_queue.txt

  # 4) Local MP4 video or folder of screenshots
  python ml/tools/extract_tos_dataset.py --input path/to/recording.mp4 --tag local_clip
  python ml/tools/extract_tos_dataset.py --images-dir backups/raw_screenshots --tag wiki_batch

  # 5) YouTube keyword search (top N public videos, preview metadata first with --dry-run)
  python ml/tools/extract_tos_dataset.py --yt-search "神魔之塔 絕亡刑獄 災厄級" --max-results 2 --tag jail_search
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

import numpy as np
from PIL import Image, ImageDraw, ImageFont


# ---------------------------------------------------------------------------
# Tool discovery (yt-dlp & ffmpeg)
# ---------------------------------------------------------------------------

def resolve_ffmpeg() -> str:
    """Locate ffmpeg binary from system PATH or bundled imageio-ffmpeg."""
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    try:
        import imageio_ffmpeg  # type: ignore
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:
        raise RuntimeError(
            "ffmpeg not found. Install via `pip install imageio-ffmpeg` or `winget install Gyan.FFmpeg`."
        ) from exc


def ensure_yt_dlp() -> None:
    try:
        import yt_dlp  # noqa: F401
    except ImportError as exc:
        raise RuntimeError(
            "yt-dlp not found. Install via `pip install yt-dlp`."
        ) from exc


# ---------------------------------------------------------------------------
# Fast NumPy HSV & ToS 6x5 Battle Board Auto-Detector
# ---------------------------------------------------------------------------

@dataclass
class BoardBox:
    left: int
    top: int
    right: int
    bottom: int
    hp_y: int
    hp_coverage: float
    corner_dark_ratio: float
    orb_contrast_ratio: float
    rim_peak_ratio: float

    @property
    def width(self) -> int:
        return self.right - self.left

    @property
    def height(self) -> int:
        return self.bottom - self.top

    @property
    def cell_w(self) -> float:
        return self.width / 6.0

    @property
    def cell_h(self) -> float:
        return self.height / 5.0


def rgb_to_hsv_np(rgb: np.ndarray) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Vectorized RGB [0..255] -> (H deg [0..360), S [0..1], V [0..1])."""
    arr = rgb.astype(np.float32) / 255.0
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    d = mx - mn

    s = np.where(mx > 1e-5, d / np.maximum(mx, 1e-5), 0.0)
    h = np.zeros_like(mx)
    nz = d > 1e-5

    mask_r = nz & (mx == r)
    mask_g = nz & (mx == g) & (~mask_r)
    mask_b = nz & (~mask_r) & (~mask_g)

    h[mask_r] = 60.0 * (((g[mask_r] - b[mask_r]) / d[mask_r]) % 6.0)
    h[mask_g] = 60.0 * (((b[mask_g] - r[mask_g]) / d[mask_g]) + 2.0)
    h[mask_b] = 60.0 * (((r[mask_b] - g[mask_b]) / d[mask_b]) + 4.0)
    h = np.where(h < 0.0, h + 360.0, h)
    return h, s, mx


def find_game_column_span(rgb: np.ndarray) -> Tuple[int, int]:
    """
    Locate the horizontal [x0, x1] span of the vertical ToS game viewport.
    - If frame is already portrait (W <= H * 0.72), returns (0, W).
    - If frame is landscape 16:9 YouTube capture with sidebars/overlays, locates the
      coral-pink HP bar horizontal extent OR the central ~9:16 portrait strip!
    """
    h, w, _ = rgb.shape
    if w <= int(h * 0.72):
        return 0, w

    # Landscape YouTube frame: search for the horizontal coral-pink HP bar band in y in [38%..66%]
    y0, y1 = int(h * 0.38), int(h * 0.66)
    band = rgb[y0:y1]
    hh, ss, vv = rgb_to_hsv_np(band)
    pink = ((hh >= 328.0) | (hh <= 20.0)) & (ss >= 0.28) & (vv >= 0.45)

    # Row with longest contiguous coral-pink run (>= 18% of height, i.e., ~35% of portrait width)
    min_run = max(40, int(h * 0.16))
    best_len = 0
    best_span: Optional[Tuple[int, int]] = None

    for row_idx in range(pink.shape[0]):
        row = pink[row_idx]
        if int(row.sum()) < min_run:
            continue
        # Bridge tiny 1-3px gaps (e.g. HP digits / slash)
        padded = np.convolve(row.astype(np.int32), np.ones(7, dtype=np.int32), mode="same") > 0
        xs = np.flatnonzero(padded)
        if xs.size < min_run:
            continue
        # Find longest contiguous segment
        splits = np.where(np.diff(xs) > 12)[0] + 1
        for seg in np.split(xs, splits):
            seg_len = int(seg[-1] - seg[0] + 1)
            if seg_len > best_len and seg_len <= int(h * 0.68):
                best_len = seg_len
                best_span = (int(seg[0]), int(seg[-1]))

    if best_span is not None:
        # In ToS, the coral HP bar spans roughly [0.095*gw .. 0.945*gw] (~85% of viewport width)
        bar_l, bar_r = best_span
        bar_w = bar_r - bar_l
        est_gw = int(round(bar_w / 0.85))
        # Clamp reasonable portrait width (9:16..9:21 of screen height)
        est_gw = max(int(h * 0.42), min(int(h * 0.62), est_gw))
        est_left = max(0, int(round(bar_l - est_gw * 0.095)))
        est_right = min(w, est_left + est_gw)

        # Refine left/right edges against bright YouTube sidebars (common white/light-grey script panels
        # beside the vertical phone capture): trim outer columns whose lower-half median luminance > 0.72.
        low_band = rgb[int(h * 0.58):int(h * 0.94), est_left:est_right]
        if low_band.size > 0:
            col_luma = low_band.max(axis=2).mean(axis=0) / 255.0
            trim_l = 0
            while trim_l < min(45, col_luma.size // 6) and col_luma[trim_l] > 0.68:
                trim_l += 1
            trim_r = 0
            while trim_r < min(45, col_luma.size // 6) and col_luma[col_luma.size - 1 - trim_r] > 0.68:
                trim_r += 1
            est_left += trim_l
            est_right -= trim_r
        return est_left, est_right

    # Fallback: centered 9:16 portrait strip inside 16:9 canvas
    est_gw = int(round(h * 9.0 / 16.0))
    left = max(0, (w - est_gw) // 2)
    return left, min(w, left + est_gw)


def detect_tos_board(rgb: np.ndarray) -> Optional[BoardBox]:
    """
    Auto-detects the 6x5 Tower of Saviors battle board in a frame.
    Mirrors `AutoGridDetector.kt` (HP-bar anchored top + checkerboard corner check +
    vivid/rim-peak cell gate supporting normal, dimmed, monochrome, and 30/30 '?' boards).
    """
    img_h, img_w, _ = rgb.shape
    vx0, vx1 = find_game_column_span(rgb)
    vw = vx1 - vx0
    if vw < 180 or img_h < 320:
        return None

    # Expected 6x5 board geometry inside the portrait viewport
    board_w = vw
    board_h = int(round(board_w * 5.0 / 6.0))
    if board_h > img_h:
        return None

    # Search for HP bar in the middle vertical band of the viewport
    search_y0 = int(img_h * 0.36)
    search_y1 = min(img_h - int(board_h * 0.90), int(img_h * 0.66))
    if search_y1 <= search_y0 + 8:
        search_y0 = max(0, img_h - board_h - int(vw * 0.25))
        search_y1 = max(search_y0 + 10, img_h - board_h + int(vw * 0.08))

    bar_x0 = vx0 + int(vw * 0.12)
    bar_x1 = vx0 + int(vw * 0.66)
    band = rgb[search_y0:search_y1, bar_x0:bar_x1]
    hh, ss, vv = rgb_to_hsv_np(band)
    pink = ((hh >= 328.0) | (hh <= 20.0)) & (ss >= 0.28) & (vv >= 0.45)
    row_cov = pink.mean(axis=1)

    best_hp_cov = float(row_cov.max()) if row_cov.size else 0.0
    if best_hp_cov < 0.25:
        # Fallback: also allow empty HP bar (1 HP team) via dark-maroon HP trough + standard 16:9 bottom anchor
        game_h = vw * (16.0 / 9.0)
        letterbox = max(0.0, (img_h - game_h) * 0.5)
        est_bottom = int(min(img_h, round(img_h - letterbox + game_h * 0.004)))
        est_top = max(0, est_bottom - board_h)
        candidate_tops = [est_top]
        hp_y = max(0, est_top - int(vw * 0.04))
    else:
        # Find bottom edge of HP bar (last contiguous row with coverage >= 0.22 around peak)
        peak_i = int(np.argmax(row_cov))
        bot_i = peak_i
        while bot_i + 1 < row_cov.size and row_cov[bot_i + 1] >= 0.18:
            bot_i += 1
        hp_y = search_y0 + bot_i
        # Board top starts ~1.8%..3.2% of viewport width below HP bar bottom
        base_top = hp_y + int(round(vw * 0.022))
        candidate_tops = [
            base_top,
            base_top - int(round(vw * 0.012)),
            base_top + int(round(vw * 0.014)),
        ]

    best_box: Optional[BoardBox] = None
    best_score = -1.0

    for top in candidate_tops:
        bottom = top + board_h
        if top < 0 or bottom > img_h:
            continue

        cw = board_w / 6.0
        ch = board_h / 5.0

        # Sample corners + centers + rim peaks across all 30 cells
        dark_corners = 0
        total_corners = 0
        pos_contrast = 0
        vivid_cells = 0
        rim_peak_cells = 0

        for r in range(5):
            for c in range(6):
                cx = vx0 + (c + 0.5) * cw
                cy = top + (r + 0.5) * ch

                # 4 cell corners (0.44 * cell)
                c_vs = []
                for dx, dy in ((-0.44, -0.44), (0.44, -0.44), (-0.44, 0.44), (0.44, 0.44)):
                    px = min(img_w - 1, max(0, int(round(cx + dx * cw))))
                    py = min(img_h - 1, max(0, int(round(cy + dy * ch))))
                    R, G, B = rgb[py, px]
                    v = max(int(R), int(G), int(B)) / 255.0
                    c_vs.append(v)
                    total_corners += 1
                    if v <= 0.40:
                        dark_corners += 1
                corner_mean = sum(c_vs) / 4.0

                # Inner 5-point probe
                in_vs = []
                in_ss = []
                for dx, dy in ((0.0, 0.0), (-0.18, 0.0), (0.18, 0.0), (0.0, -0.18), (0.0, 0.18)):
                    px = min(img_w - 1, max(0, int(round(cx + dx * cw))))
                    py = min(img_h - 1, max(0, int(round(cy + dy * ch))))
                    R, G, B = int(rgb[py, px, 0]), int(rgb[py, px, 1]), int(rgb[py, px, 2])
                    mx = max(R, G, B)
                    mn = min(R, G, B)
                    in_vs.append(mx / 255.0)
                    in_ss.append(0.0 if mx == 0 else (mx - mn) / mx)
                in_mean_v = sum(in_vs) / 5.0
                in_mean_s = sum(in_ss) / 5.0
                if in_mean_v > corner_mean + 0.14:
                    pos_contrast += 1
                if in_mean_v >= 0.40 and in_mean_s >= 0.30:
                    vivid_cells += 1

                # Outer rim / '?' glyph peak probe (catches 30/30 dark-teal '?' and monochrome boards!)
                peak_v = max(in_vs)
                for dx, dy in ((0.0, -0.40), (0.0, 0.40), (-0.40, 0.0), (0.40, 0.0), (-0.29, -0.29), (0.29, 0.29)):
                    px = min(img_w - 1, max(0, int(round(cx + dx * cw))))
                    py = min(img_h - 1, max(0, int(round(cy + dy * ch))))
                    v = int(rgb[py, px].max()) / 255.0
                    if v > peak_v:
                        peak_v = v
                if peak_v > corner_mean + 0.26 and peak_v >= 0.50:
                    rim_peak_cells += 1

        dark_ratio = dark_corners / max(1, total_corners)
        contrast_ratio = pos_contrast / 30.0
        vivid_ratio = vivid_cells / 30.0
        rim_ratio = rim_peak_cells / 30.0

        # Battle gate: dark checkerboard corners + orb interior/rim contrast + HP bar
        is_valid = (
            dark_ratio >= 0.52
            and (
                (contrast_ratio >= 0.45 and (vivid_ratio >= 0.20 or best_hp_cov >= 0.25))
                or (rim_ratio >= 0.55 and best_hp_cov >= 0.25)
            )
        )
        if is_valid:
            score = dark_ratio * 1.5 + contrast_ratio * 1.2 + rim_ratio * 0.8 + best_hp_cov
            if score > best_score:
                best_score = score
                best_box = BoardBox(
                    left=vx0,
                    top=top,
                    right=vx1,
                    bottom=bottom,
                    hp_y=hp_y,
                    hp_coverage=round(best_hp_cov, 3),
                    corner_dark_ratio=round(dark_ratio, 3),
                    orb_contrast_ratio=round(contrast_ratio, 3),
                    rim_peak_ratio=round(rim_ratio, 3),
                )

    return best_box


def board_fingerprint(board_rgb: np.ndarray) -> np.ndarray:
    """Compute a compact 30x3 float32 color/luminance signature for fast deduplication."""
    small = np.array(Image.fromarray(board_rgb).resize((24, 20), Image.Resampling.BILINEAR), dtype=np.float32)
    return small


def is_duplicate_board(fp: np.ndarray, prev_fps: List[np.ndarray], threshold: float) -> bool:
    for p in prev_fps:
        if float(np.mean(np.abs(fp - p))) < threshold:
            return True
    return False


# ---------------------------------------------------------------------------
# Video download (yt-dlp) & frame extraction (ffmpeg)
# ---------------------------------------------------------------------------

def parse_time_to_sec(t: Optional[str]) -> Optional[float]:
    if not t:
        return None
    parts = [float(p) for p in t.strip().split(":")]
    if len(parts) == 1:
        return parts[0]
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    if len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    raise ValueError(f"Invalid timestamp: {t}")


def download_youtube_video(
    url: str,
    dest_dir: Path,
    max_height: int = 2400,
    start: Optional[str] = None,
    end: Optional[str] = None,
) -> Tuple[Path, dict]:
    """Download public YouTube video stream (full 1080p for both 16:9 landscape and 9:20 portrait)."""
    ensure_yt_dlp()
    import yt_dlp  # type: ignore

    dest_dir.mkdir(parents=True, exist_ok=True)
    out_tmpl = str(dest_dir / "%(id)s.%(ext)s")

    ydl_opts: dict = {
        "format": (
            f"bestvideo[ext=mp4][width<=1920][height<={max_height}]/"
            f"bestvideo[width<=1920][height<={max_height}]/"
            f"best[ext=mp4][height<={max_height}]/bestvideo/best"
        ),
        "outtmpl": out_tmpl,
        "noplaylist": True,
        "quiet": False,
        "no_warnings": True,
        "retries": 2,
    }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        fname = Path(ydl.prepare_filename(info))
        if not fname.exists():
            # Sometimes extension changes after merge
            matches = list(dest_dir.glob(f"{info['id']}.*"))
            if not matches:
                raise FileNotFoundError(f"Downloaded file missing for {url}")
            fname = matches[0]
        meta = {
            "id": info.get("id"),
            "title": info.get("title"),
            "uploader": info.get("uploader"),
            "webpage_url": info.get("webpage_url") or url,
            "duration": info.get("duration"),
            "width": info.get("width"),
            "height": info.get("height"),
        }
        return fname, meta


def search_youtube(query: str, max_results: int = 3) -> List[dict]:
    """Search public YouTube metadata without downloading."""
    ensure_yt_dlp()
    import yt_dlp  # type: ignore

    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
    }
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        res = ydl.extract_info(f"ytsearch{max_results}:{query}", download=False)
        entries = res.get("entries") or []
        return [
            {
                "id": e.get("id"),
                "title": e.get("title"),
                "url": e.get("url") if str(e.get("url", "")).startswith("http") else f"https://www.youtube.com/watch?v={e.get('id')}",
                "duration": e.get("duration"),
                "channel": e.get("channel") or e.get("uploader"),
            }
            for e in entries
            if e and e.get("id")
        ]


def extract_frames_ffmpeg(
    video_path: Path,
    raw_frames_dir: Path,
    fps: float = 0.5,
    start: Optional[str] = None,
    end: Optional[str] = None,
) -> List[Tuple[Path, float]]:
    """Run ffmpeg to sample video frames at `fps` into `raw_frames_dir`."""
    ffmpeg = resolve_ffmpeg()
    raw_frames_dir.mkdir(parents=True, exist_ok=True)
    cmd = [ffmpeg, "-hide_banner", "-loglevel", "error"]
    s_sec = parse_time_to_sec(start)
    e_sec = parse_time_to_sec(end)
    if s_sec is not None:
        cmd += ["-ss", f"{s_sec:.3f}"]
    if e_sec is not None:
        cmd += ["-to", f"{e_sec:.3f}"]
    cmd += [
        "-i", str(video_path),
        "-vf", f"fps={fps}",
        "-q:v", "2",
        str(raw_frames_dir / "raw_%05d.png"),
    ]
    subprocess.run(cmd, check=True)
    files = sorted(raw_frames_dir.glob("raw_*.png"))
    base_t = s_sec or 0.0
    return [(p, base_t + idx / fps) for idx, p in enumerate(files)]


# ---------------------------------------------------------------------------
# Processing pipeline: filter -> crop 6x5 board & 64x64 orbs -> contact sheet
# ---------------------------------------------------------------------------

def build_contact_sheet(
    items: List[Tuple[Path, str]],
    out_path: Path,
    cols: int = 5,
    thumb_w: int = 300,
) -> None:
    if not items:
        return
    thumbs: List[Tuple[Image.Image, str]] = []
    max_h = 0
    for p, label in items:
        im = Image.open(p).convert("RGB")
        ratio = thumb_w / float(im.width)
        th = int(round(im.height * ratio))
        im_r = im.resize((thumb_w, th), Image.Resampling.LANCZOS)
        thumbs.append((im_r, label))
        if th > max_h:
            max_h = th

    pad = 8
    caption_h = 22
    rows = math.ceil(len(thumbs) / cols)
    canvas_w = cols * (thumb_w + pad) + pad
    canvas_h = rows * (max_h + caption_h + pad) + pad
    sheet = Image.new("RGB", (canvas_w, canvas_h), (24, 24, 28))
    draw = ImageDraw.Draw(sheet)

    for idx, (im_r, label) in enumerate(thumbs):
        r = idx // cols
        c = idx % cols
        x = pad + c * (thumb_w + pad)
        y = pad + r * (max_h + caption_h + pad)
        sheet.paste(im_r, (x, y))
        draw.rectangle([x, y + max_h, x + thumb_w, y + max_h + caption_h], fill=(38, 38, 46))
        draw.text((x + 6, y + max_h + 4), label[:42], fill=(235, 240, 255))

    sheet.save(out_path, quality=90)


def process_images(
    image_items: List[Tuple[Path, Optional[float]]],
    out_dir: Path,
    dedup_threshold: float = 7.0,
    max_frames: int = 40,
    orb_size: int = 64,
    crop_orbs: bool = True,
    source_meta: Optional[dict] = None,
) -> dict:
    frames_dir = out_dir / "frames"
    boards_dir = out_dir / "boards"
    orbs_dir = out_dir / "orbs"
    frames_dir.mkdir(parents=True, exist_ok=True)
    boards_dir.mkdir(parents=True, exist_ok=True)
    if crop_orbs:
        orbs_dir.mkdir(parents=True, exist_ok=True)

    kept_fps: List[np.ndarray] = []
    kept_records: List[dict] = []
    sheet_items: List[Tuple[Path, str]] = []
    skipped_non_battle = 0
    skipped_dup = 0

    for img_path, ts_sec in image_items:
        if len(kept_records) >= max_frames:
            break
        im = Image.open(img_path).convert("RGB")
        rgb = np.array(im)
        box = detect_tos_board(rgb)
        if box is None:
            skipped_non_battle += 1
            continue

        board_rgb = rgb[box.top:box.bottom, box.left:box.right]
        fp = board_fingerprint(board_rgb)
        if dedup_threshold > 0 and is_duplicate_board(fp, kept_fps, dedup_threshold):
            skipped_dup += 1
            continue
        kept_fps.append(fp)

        idx = len(kept_records) + 1
        ts_tag = f"_t{int(round(ts_sec)):04d}s" if ts_sec is not None else ""
        stem = f"f{idx:03d}{ts_tag}"

        # Crop vertical viewport around the battle screen (clean, removes 16:9 YouTube sidebars)
        vp_im = im.crop((box.left, 0, box.right, im.height))
        frame_out = frames_dir / f"{stem}.png"
        vp_im.save(frame_out)

        board_im = Image.fromarray(board_rgb)
        board_out = boards_dir / f"{stem}_board.png"
        board_im.save(board_out)

        # Save one grid alignment preview on the very first kept frame
        if idx == 1:
            prev = board_im.copy()
            d = ImageDraw.Draw(prev)
            for c in range(1, 6):
                x = int(round(c * box.cell_w))
                d.line([(x, 0), (x, prev.height)], fill=(0, 255, 160), width=2)
            for r in range(1, 5):
                y = int(round(r * box.cell_h))
                d.line([(0, y), (prev.width, y)], fill=(0, 255, 160), width=2)
            prev.save(out_dir / "preview_grid_check.png")

        if crop_orbs:
            cw, ch = box.cell_w, box.cell_h
            for r in range(5):
                for c in range(6):
                    x0 = int(round(c * cw))
                    y0 = int(round(r * ch))
                    x1 = int(round((c + 1) * cw))
                    y1 = int(round((r + 1) * ch))
                    tile = board_im.crop((x0, y0, x1, y1)).resize((orb_size, orb_size), Image.Resampling.LANCZOS)
                    tile.save(orbs_dir / f"{stem}_r{r}_c{c}.png")

        label = f"{stem} (hp={box.hp_coverage:.2f})"
        sheet_items.append((board_out, label))
        kept_records.append({
            "index": idx,
            "stem": stem,
            "timestamp_sec": round(ts_sec, 2) if ts_sec is not None else None,
            "frame_file": f"frames/{frame_out.name}",
            "board_file": f"boards/{board_out.name}",
            "board_box": asdict(box),
            "viewport_size": [vp_im.width, vp_im.height],
        })

    if sheet_items:
        build_contact_sheet(sheet_items, out_dir / "contact_sheet_boards.jpg", cols=5, thumb_w=300)
        # Also build a full-viewport sheet (shows enemy skills/HP/portraits)
        vp_sheet_items = [(out_dir / rec["frame_file"], rec["stem"]) for rec in kept_records]
        build_contact_sheet(vp_sheet_items, out_dir / "contact_sheet_full.jpg", cols=5, thumb_w=240)

    summary = {
        "source": source_meta or {},
        "total_scanned": len(image_items),
        "kept_battle_frames": len(kept_records),
        "total_cropped_orbs": len(kept_records) * 30 if crop_orbs else 0,
        "skipped_non_battle": skipped_non_battle,
        "skipped_duplicate": skipped_dup,
        "frames": kept_records,
    }
    (out_dir / "metadata.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def sanitize_tag(s: str) -> str:
    clean = re.sub(r"[^0-9A-Za-z_\-\u4e00-\u9fff]+", "_", s).strip("_")
    return clean[:64] or "batch"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="AutoDora-NEXT semi-automatic ToS YouTube/Video frame extractor & 6x5 orb cropper"
    )
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("--url", help="Public YouTube video URL")
    src.add_argument("--urls-file", help="Text file of URLs (`URL` or `URL | tag | start-end`)")
    src.add_argument("--yt-search", help="Public YouTube search query (downloads top --max-results)")
    src.add_argument("--input", help="Local video file (.mp4/.mkv/.webm)")
    src.add_argument("--images-dir", help="Folder of local screenshots (.png/.jpg/.webp)")

    parser.add_argument("--tag", default="tos_extract", help="Output batch subfolder name")
    parser.add_argument(
        "--out-root",
        default="backups/user_training_media/yt_extracted",
        help="Root output directory (MUST remain gitignored, default: backups/user_training_media/yt_extracted)",
    )
    parser.add_argument("--fps", type=float, default=0.5, help="Sampling FPS for videos (default: 0.5 = 1 frame / 2s)")
    parser.add_argument("--start", default=None, help="Optional start timestamp (e.g. 00:45 or 120)")
    parser.add_argument("--end", default=None, help="Optional end timestamp (e.g. 03:10 or 190)")
    parser.add_argument("--max-height", type=int, default=2400, help="Max video height for yt-dlp (default: 2400 to keep 1080p portrait)")
    parser.add_argument("--max-frames", type=int, default=40, help="Max unique battle frames to keep per source")
    parser.add_argument("--max-results", type=int, default=2, help="Max search results for --yt-search")
    parser.add_argument("--dedup", type=float, default=7.0, help="L1 board diff threshold for deduplication (0=off)")
    parser.add_argument("--orb-size", type=int, default=64, help="Cropped orb tile resolution (default: 64)")
    parser.add_argument("--no-orbs", action="store_true", help="Skip saving individual 64x64 orb PNGs")
    parser.add_argument("--keep-video", action="store_true", help="Keep downloaded MP4 file inside batch folder")
    parser.add_argument("--dry-run", action="store_true", help="For --yt-search: list candidate videos without downloading")
    args = parser.parse_args()

    out_root = Path(args.out_root)
    out_root.mkdir(parents=True, exist_ok=True)

    if args.yt_search:
        results = search_youtube(args.yt_search, max_results=args.max_results)
        print(json.dumps(results, ensure_ascii=False, indent=2))
        if args.dry_run:
            return 0
        jobs = [(r["url"], f"{sanitize_tag(args.tag)}_{i+1}_{r['id']}", args.start, args.end) for i, r in enumerate(results)]
    elif args.url:
        jobs = [(args.url, sanitize_tag(args.tag), args.start, args.end)]
    elif args.urls_file:
        jobs = []
        for idx, line in enumerate(Path(args.urls_file).read_text(encoding="utf-8").splitlines(), 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = [p.strip() for p in line.split("|")]
            u = parts[0]
            t = sanitize_tag(parts[1]) if len(parts) >= 2 and parts[1] else f"{sanitize_tag(args.tag)}_{idx}"
            st, en = args.start, args.end
            if len(parts) >= 3 and "-" in parts[2]:
                s0, e0 = parts[2].split("-", 1)
                st = s0.strip() or st
                en = e0.strip() or en
            jobs.append((u, t, st, en))
    elif args.input:
        vid_path = Path(args.input)
        batch_dir = out_root / sanitize_tag(args.tag)
        with tempfile.TemporaryDirectory(prefix="tos_raw_") as tmp:
            raw = extract_frames_ffmpeg(vid_path, Path(tmp), fps=args.fps, start=args.start, end=args.end)
            summary = process_images(
                raw, batch_dir, dedup_threshold=args.dedup, max_frames=args.max_frames,
                orb_size=args.orb_size, crop_orbs=not args.no_orbs,
                source_meta={"type": "local_video", "path": str(vid_path)},
            )
        print(f"[OK] {batch_dir}: kept {summary['kept_battle_frames']} boards ({summary['total_cropped_orbs']} orbs)")
        return 0
    else:
        img_dir = Path(args.images_dir)
        imgs = sorted(
            p for p in img_dir.rglob("*")
            if p.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"}
        )
        batch_dir = out_root / sanitize_tag(args.tag)
        summary = process_images(
            [(p, None) for p in imgs], batch_dir, dedup_threshold=args.dedup,
            max_frames=args.max_frames, orb_size=args.orb_size, crop_orbs=not args.no_orbs,
            source_meta={"type": "images_dir", "path": str(img_dir)},
        )
        print(f"[OK] {batch_dir}: kept {summary['kept_battle_frames']} boards ({summary['total_cropped_orbs']} orbs)")
        return 0

    # Execute YouTube download + extract jobs
    for url, tag, st, en in jobs:
        batch_dir = out_root / tag
        print(f"\n=== Processing [{tag}]: {url} ===")
        try:
            with tempfile.TemporaryDirectory(prefix="tos_yt_") as tmp:
                tmp_path = Path(tmp)
                vid_dir = (batch_dir / "video") if args.keep_video else (tmp_path / "video")
                vid_path, meta = download_youtube_video(
                    url, vid_dir, max_height=args.max_height, start=st, end=en
                )
                print(f"Downloaded: {meta.get('title')} ({meta.get('width')}x{meta.get('height')}, {meta.get('duration')}s)")
                raw = extract_frames_ffmpeg(vid_path, tmp_path / "raw_frames", fps=args.fps, start=st, end=en)
                summary = process_images(
                    raw, batch_dir, dedup_threshold=args.dedup, max_frames=args.max_frames,
                    orb_size=args.orb_size, crop_orbs=not args.no_orbs,
                    source_meta={"type": "youtube", **meta},
                )
                print(
                    f"[OK] {batch_dir}: scanned={summary['total_scanned']} "
                    f"kept={summary['kept_battle_frames']} boards "
                    f"orbs={summary['total_cropped_orbs']} "
                    f"(non_battle={summary['skipped_non_battle']}, dup={summary['skipped_duplicate']})"
                )
        except Exception as exc:
            print(f"[WARN] Skipped {url}: {exc}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
