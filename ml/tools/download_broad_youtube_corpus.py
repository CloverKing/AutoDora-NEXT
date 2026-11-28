#!/usr/bin/env python3
"""
Broad-Spectrum YouTube Gameplay Corpus Downloader & Dual-Frame Extractor (v1)
=============================================================================
Orchestrates large-scale, multi-dimensional YouTube Tower of Saviors (`神魔之塔`) gameplay
acquisition for next-generation larger-capacity Stage 2A (`BoardMasterGridNet`) and
Stage 2B (`EnemyIconNet`) model training.

Key capabilities:
  1. 72 curated search queries spanning 10 dimensions (2024-2026 Calamity bosses, Monthly Lv10,
     all 7 modern Race-Runestone leaders, 6x8 expanded boards, Monochrome/Question/Buoy,
     all 22 hazards & 5 marks, spatial terrains/puzzles, 12 collab orb skins, live CD numeric
     badges, and Nightmare/Hell stages).
  2. Global `video_id` deduplication across ALL subfolders of `backups/phase4_v2_inbox/` and
     `backups/user_training_media/` + 100% resumable per-video `metadata.json`.
  3. Multi-window `3.0 fps` sampling across Early / Mid / Boss phases -> extracts genuine
     `(frame_t, frame_{t+0.33s})` static dual-frame pairs (`dHash <= 4`), refined to exact
     `6x5` (`384x320`) or `6x8` (`384x512`) periodic cell alignment!
  4. Saves compact `WEBP (quality=92)`:
       - `boards/{stem}_{a,b}.webp`   (6x5 dual-frame static board pair)
       - `boards_6x8/{stem}_{a,b}.webp` (when 6x8 expanded board is detected)
       - `upper_strips/{stem}_strip.webp` (live upper-screen enemy CD & skill icon strip)
       - `enemy_popups/{stem}_popup.webp` (dark long-press enemy skill modal hard negatives)
  5. Strictly writes ONLY under gitignored `backups/phase4_v2_inbox/youtube_broad_corpus_v1/`
     and deletes temporary raw `.mp4` streams in `%TEMP%` immediately after frame extraction.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from typing import Any, Dict, List, Optional, Set, Tuple

import numpy as np
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))
sys.path.insert(0, str(REPO_ROOT / "ml" / "tools"))

from extract_tos_dataset import (  # noqa: E402
    detect_tos_board,
    find_game_column_span,
    resolve_ffmpeg,
    rgb_to_hsv_np,
    sanitize_tag,
    search_youtube,
)
from extract_youtube_frames import compute_dhash64, hamming_distance  # noqa: E402

INBOX_ROOT = REPO_ROOT / "backups" / "phase4_v2_inbox"
MEDIA_ROOT = REPO_ROOT / "backups" / "user_training_media"
OUT_ROOT = INBOX_ROOT / "youtube_broad_corpus_v1"

BROAD_CORPUS_QUERIES: List[Tuple[str, str, Optional[str]]] = [
    # (dimension, youtube_query, optional_verified_race_hint)
    # 01. 2024-2026 Calamity & Endgame Bosses
    ("01_calamity_bosses", "神魔之塔 流光災厄級 通關", None),
    ("01_calamity_bosses", "神魔之塔 十封王 災厄級 通關", None),
    ("01_calamity_bosses", "神魔之塔 侵蝕封王 災厄級 0石", None),
    ("01_calamity_bosses", "神魔之塔 絕亡刑獄 災厄級 通關", None),
    ("01_calamity_bosses", "神魔之塔 讓眾生恐懼的詛咒 災厄級", None),
    ("01_calamity_bosses", "神魔之塔 卡俄斯 災厄級 通關", None),
    ("01_calamity_bosses", "神魔之塔 未來的火種 災厄級", None),
    # 02. Monthly Lv9/Lv10 Challenges & Corridor of Memories
    ("02_monthly_corridor", "神魔之塔 挑戰任務 Lv10 通關", None),
    ("02_monthly_corridor", "神魔之塔 十一月挑戰 Lv10 通關", None),
    ("02_monthly_corridor", "神魔之塔 十二月挑戰 Lv10 通關", None),
    ("02_monthly_corridor", "神魔之塔 九月挑戰 Lv10 通關", None),
    ("02_monthly_corridor", "神魔之塔 記憶迴廊 100層 通關", None),
    ("02_monthly_corridor", "神魔之塔 魔神戰 階段 通關", None),
    ("02_monthly_corridor", "神魔之塔 戰慄級 S級 通關", None),
    # 03. All 7 Modern Race-Runestone Meta Leaders (2021-2026)
    ("03_race_god", "神魔之塔 帝鴻 神隊 災厄級 通關", "GOD"),
    ("03_race_god", "神魔之塔 刻琿 神族隊長 實戰", "GOD"),
    ("03_race_god", "神魔之塔 阿努比斯 神隊 地獄級", "GOD"),
    ("03_race_demon", "神魔之塔 兩面宿儺 魔族隊長 通關", "DEMON"),
    ("03_race_demon", "神魔之塔 鬼舞辻無慘 魔隊 實戰", "DEMON"),
    ("03_race_demon", "神魔之塔 零覓 魔隊 災厄級", "DEMON"),
    ("03_race_human", "神魔之塔 葬送的芙莉蓮 人類隊 通關", "HUMAN"),
    ("03_race_human", "神魔之塔 魯迪烏斯 實戰 災厄級", "HUMAN"),
    ("03_race_beast", "神魔之塔 凱撒 獸隊 災厄級 通關", "BEAST"),
    ("03_race_beast", "神魔之塔 賈比爾 獸隊 地獄級 通關", "BEAST"),
    ("03_race_beast", "神魔之塔 尼祿 獸隊 實戰 通關", "BEAST"),
    ("03_race_beast", "神魔之塔 鈴子 純獸隊 通關", "BEAST"),
    ("03_race_dragon", "神魔之塔 尼德霍格 龍隊 災厄級", "DRAGON"),
    ("03_race_dragon", "神魔之塔 卑彌呼 龍隊 地獄級 通關", "DRAGON"),
    ("03_race_dragon", "神魔之塔 青眼白龍 龍隊 實戰", "DRAGON"),
    ("03_race_elf", "神魔之塔 琉彩 妖精隊 實戰 通關", "ELF"),
    ("03_race_elf", "神魔之塔 克洛怡 妖精隊長 災厄級", "ELF"),
    ("03_race_elf", "神魔之塔 潘朵拉 妖精隊 地獄級", "ELF"),
    ("03_race_machina", "神魔之塔 初音未來 機械隊 災厄級", "MACHINA"),
    ("03_race_machina", "神魔之塔 阿圖姆 機械隊 通關", "MACHINA"),
    ("03_race_machina", "神魔之塔 歐幾里得 機械族 實戰", "MACHINA"),
    ("03_race_machina", "神魔之塔 魯路修 機械隊 地獄級", "MACHINA"),
    # 04. 6x8 Expanded Board (Weijian)
    ("04_weijian_6x8", "神魔之塔 6x8 大版面 實戰 通關", None),
    ("04_weijian_6x8", "神魔之塔 主角 界限突破 擴版 實戰", None),
    ("04_weijian_6x8", "神魔之塔 違建 6x8 隊長 地獄級", None),
    # 05. Monochrome, Disguise, Question, Starting Buoy
    ("05_mono_question_buoy", "神魔之塔 黑白符石 地獄級 通關", None),
    ("05_mono_question_buoy", "神魔之塔 幽閉空間 夢魘級 通關", None),
    ("05_mono_question_buoy", "神魔之塔 移形換影 地獄級 通關", None),
    ("05_mono_question_buoy", "神魔之塔 全版問號珠 地獄級 通關", None),
    ("05_mono_question_buoy", "神魔之塔 起動浮標 角色符石盾 通關", None),
    # 06. All 22 Runestone Hazards & 5 Elemental Marks
    ("06_hazards_marks", "神魔之塔 雙重凝結 凝結符石 地獄級", None),
    ("06_hazards_marks", "神魔之塔 泡沫符石 爆破炸彈 地獄級", None),
    ("06_hazards_marks", "神魔之塔 邪惡能量 化血符石 夢魘級", None),
    ("06_hazards_marks", "神魔之塔 冰封符石 凍結符石 地獄級", None),
    ("06_hazards_marks", "神魔之塔 石化符石 電擊符石 災厄級", None),
    ("06_hazards_marks", "神魔之塔 弱化鎖定 音樂符石 地獄級", None),
    ("06_hazards_marks", "神魔之塔 荊棘符石 鋼線符石 通關", None),
    ("06_hazards_marks", "神魔之塔 煉獄之火 刺血符石 災厄級", None),
    ("06_hazards_marks", "神魔之塔 淡化印記 淨化印記 虛之印記", None),
    ("06_hazards_marks", "神魔之塔 消炎印記 除雷印記 夢魘級", None),
    # 07. Spatial Overlays & Terrains
    ("07_overlays_terrain", "神魔之塔 嚴格拼圖 指定形狀盾 地獄級", None),
    ("07_overlays_terrain", "神魔之塔 起結有序 紅綠燈 地獄級", None),
    ("07_overlays_terrain", "神魔之塔 灼熱地型 燃燒地型 災厄級", None),
    ("07_overlays_terrain", "神魔之塔 血浴地型 黑洞地型 通關", None),
    ("07_overlays_terrain", "神魔之塔 角色符石通行地型 結界地型", None),
    # 08. Collaboration Custom Orb Skins (Visual Generalization)
    ("08_collab_skins", "神魔之塔 鬼滅之刃 合作 地獄級 通關", None),
    ("08_collab_skins", "神魔之塔 咒術迴戰 合作 夢魘級 通關", None),
    ("08_collab_skins", "神魔之塔 間諜家家酒 合作 地獄級", None),
    ("08_collab_skins", "神魔之塔 刀劍神域 合作 災厄級 通關", None),
    ("08_collab_skins", "神魔之塔 遊戲王 合作 夢魘級 通關", None),
    ("08_collab_skins", "神魔之塔 鏈鋸人 合作 地獄級 通關", None),
    ("08_collab_skins", "神魔之塔 關於我轉生變成史萊姆 合作 地獄級", None),
    ("08_collab_skins", "神魔之塔 我推的孩子 合作 地獄級 通關", None),
    # 09. Live In-Battle Enemy CD Icons & Numeric Badges
    ("09_enemy_icons_badges", "神魔之塔 連擊上限盾 災厄級 通關", None),
    ("09_enemy_icons_badges", "神魔之塔 首消粒數盾 夢魘級 通關", None),
    ("09_enemy_icons_badges", "神魔之塔 十之詛咒 正方盾 地獄級", None),
    ("09_enemy_icons_badges", "神魔之塔 寒之氣 只能消除首批符石 通關", None),
    ("09_enemy_icons_badges", "神魔之塔 累積符石盾 五屬強化盾 災厄級", None),
]


def collect_all_known_video_ids() -> Set[str]:
    seen: Set[str] = set()
    for root in (INBOX_ROOT, MEDIA_ROOT):
        if not root.exists():
            continue
        for mf in root.rglob("metadata.json"):
            try:
                data = json.loads(mf.read_text(encoding="utf-8"))
                src = data.get("source") or {}
                vid = src.get("video_id") or src.get("id") or data.get("video_id")
                if vid:
                    seen.add(str(vid))
            except Exception:
                continue
    return seen


def has_6x5_grid_periodicity(board_rgb: np.ndarray) -> Tuple[bool, float]:
    """Verifies authentic 6x5 (384x320) 64px cell periodicity and rejects non-board slides/zoomed crops."""
    if board_rgb[:, :10].mean() < 8.0 or board_rgb[:, -10:].mean() < 8.0:
        return False, -1.0
    _, ss, vv = rgb_to_hsv_np(board_rgb)
    # Reject bottom white toast or center dark UI modal
    if float(((ss[275:312, 70:314] < 0.08) & (vv[275:312, 70:314] > 0.90)).mean()) > 0.45:
        return False, -1.0

    inter_v = [float(vv[r * 64 - 4:r * 64 + 4, c * 64 - 4:c * 64 + 4].mean()) for r in range(1, 5) for c in range(1, 6)]
    center_v = [float(vv[r * 64 + 22:r * 64 + 42, c * 64 + 22:c * 64 + 42].mean()) for r in range(5) for c in range(6)]
    col_v = vv.mean(axis=0)
    c_peaks = float(np.mean([col_v[c * 64 + 20:c * 64 + 44].mean() for c in range(6)]))
    c_seams = float(np.mean([col_v[c * 64 - 4:c * 64 + 4].mean() for c in range(1, 6)]))
    row_v = vv.mean(axis=1)
    r_peaks = float(np.mean([row_v[r * 64 + 20:r * 64 + 44].mean() for r in range(5)]))
    r_seams = float(np.mean([row_v[r * 64 - 4:r * 64 + 4].mean() for r in range(1, 5)]))

    # Reject mid-combo washout explosions
    expl = sum(
        1 for r in range(5) for c in range(6)
        if float(((vv[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64] > 0.90) & (ss[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64] < 0.26)).mean()) > 0.38
    )
    if expl >= 3:
        return False, -1.0

    dark_inters = sum(1 for v in inter_v if v < 0.44)
    score = (c_peaks - c_seams) + (r_peaks - r_seams) + 0.02 * dark_inters
    ok = (
        dark_inters >= 13
        and float(np.median(center_v)) >= float(np.median(inter_v)) * 1.28
        and c_peaks >= c_seams + 0.038
        and r_peaks >= r_seams + 0.038
    )
    return ok, score


def detect_refined_board_coords(im: Image.Image) -> Optional[Tuple[int, int, int, int, int, int]]:
    """
    Returns `(x0, y0, x1, y1, vx0, vx1)` of a periodicity-verified 6x5 board inside `im`,
    refining `(width, cx, top)` around `detect_tos_board` to eliminate 16:9 sidebar shift.
    """
    rgb = np.asarray(im)
    h, w, _ = rgb.shape
    box = detect_tos_board(rgb)
    if box is None:
        return None
    bw0 = box.width
    cx0 = 0.5 * (box.left + box.right)
    best_coords: Optional[Tuple[int, int, int, int, int, int]] = None
    best_sc = -1e9

    for w_scale in (0.94, 0.98, 1.00, 1.02, 1.06):
        bw = int(round(bw0 * w_scale))
        bh = int(round(bw * 5.0 / 6.0))
        if bw < 160 or bh > h:
            continue
        for dx_frac in (-0.04, -0.02, 0.0, 0.02, 0.04):
            x0 = int(round(cx0 - bw * 0.5 + dx_frac * bw0))
            x1 = x0 + bw
            if x0 < 0 or x1 > w:
                continue
            for dy_frac in (-0.018, 0.0, 0.018):
                y0 = int(round(box.top + dy_frac * bh))
                y1 = y0 + bh
                if y0 < 0 or y1 > h:
                    continue
                cand = np.asarray(im.crop((x0, y0, x1, y1)).resize((384, 320), Image.Resampling.BILINEAR), dtype=np.uint8)
                ok, sc = has_6x5_grid_periodicity(cand)
                if ok and sc > best_sc:
                    best_sc = sc
                    best_coords = (x0, y0, x1, y1, x0, x1)
    return best_coords


def is_dark_enemy_skill_modal(vp_rgb: np.ndarray) -> bool:
    """Detects dark in-battle long-press enemy skill detail modal inside a portrait viewport."""
    h, w, _ = vp_rgb.shape
    if w < 180 or h < 320:
        return False
    mid = vp_rgb[int(h * 0.22):int(h * 0.78), int(w * 0.08):int(w * 0.92)]
    hh, ss, vv = rgb_to_hsv_np(mid)
    dark_bg = float(((ss < 0.35) & (vv > 0.05) & (vv < 0.26)).mean())
    bright_txt = float(((ss < 0.30) & (vv > 0.72)).mean())
    gold_hdr = float((((hh >= 28.0) & (hh <= 58.0)) & (ss >= 0.35) & (vv >= 0.45)).mean())
    return dark_bg >= 0.52 and 0.015 <= bright_txt <= 0.22 and gold_hdr >= 0.008


def download_video_temp(url: str, dest_dir: Path) -> Tuple[Path, Dict[str, Any]]:
    import yt_dlp  # type: ignore

    dest_dir.mkdir(parents=True, exist_ok=True)
    ydl_opts: dict = {
        "format": (
            "bestvideo[ext=mp4][width<=1920][height<=1920][fps<=30]/"
            "bestvideo[width<=1920][height<=1920][fps<=30]/"
            "best[ext=mp4][height<=1920]/bestvideo/best"
        ),
        "outtmpl": str(dest_dir / "%(id)s.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "concurrent_fragment_downloads": 4,
        "retries": 2,
        "socket_timeout": 20,
    }
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        fname = Path(ydl.prepare_filename(info))
        if not fname.exists():
            matches = list(dest_dir.glob(f"{info['id']}.*"))
            if not matches:
                raise FileNotFoundError(f"Missing video file for {url}")
            fname = matches[0]
        return fname, {
            "id": info.get("id"),
            "title": info.get("title"),
            "uploader": info.get("uploader"),
            "webpage_url": info.get("webpage_url") or url,
            "duration": info.get("duration"),
            "width": info.get("width"),
            "height": info.get("height"),
        }


def extract_multiwindow_3fps_frames(video_path: Path, raw_dir: Path, duration: float) -> List[Path]:
    """
    Samples 3.0 fps bursts (`dt = 0.333s` for genuine dual-frame static pairs!) across up to
    3 distinct windows (Early R1-R2, Mid R4-R6, Boss final waves) so even 12-minute runs yield
    diverse waves + boss phases quickly without decoding 30,000 frames.
    """
    ffmpeg = resolve_ffmpeg()
    raw_dir.mkdir(parents=True, exist_ok=True)
    windows: List[Tuple[float, float]] = []
    if duration <= 210:
        windows.append((max(0.0, min(8.0, duration * 0.05)), max(30.0, duration - 3.0)))
    else:
        windows.append((12.0, min(92.0, duration * 0.35)))
        mid_s = max(95.0, duration * 0.44)
        windows.append((mid_s, min(mid_s + 80.0, duration * 0.72)))
        boss_s = max(duration * 0.74, duration - 105.0)
        windows.append((boss_s, max(boss_s + 20.0, duration - 6.0)))

    out_files: List[Path] = []
    for wi, (st, en) in enumerate(windows):
        if en <= st + 3.0:
            continue
        pat = str(raw_dir / f"w{wi}_%05d.jpg")
        subprocess.run(
            [
                ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-ss", f"{st:.1f}", "-to", f"{en:.1f}",
                "-i", str(video_path),
                "-vf", "fps=3.0",
                "-q:v", "2",
                pat,
            ],
            check=False,
        )
        out_files.extend(sorted(raw_dir.glob(f"w{wi}_*.jpg")))
    return out_files


def process_video_dual_pairs(
    raw_paths: List[Path],
    batch_dir: Path,
    max_pairs: int = 22,
) -> Dict[str, int]:
    boards_dir = batch_dir / "boards"
    b68_dir = batch_dir / "boards_6x8"
    strips_dir = batch_dir / "upper_strips"
    popups_dir = batch_dir / "enemy_popups"

    seen_board_hashes: List[int] = []
    seen_popup_hashes: List[int] = []
    cached_coords: Optional[Tuple[int, int, int, int, int, int]] = None

    kept_pairs = 0
    kept_6x8 = 0
    kept_popups = 0

    idx = 0
    n = len(raw_paths)
    while idx + 1 < n and kept_pairs < max_pairs:
        pa, pb = raw_paths[idx], raw_paths[idx + 1]
        # Must belong to the same time window (`w0_`, `w1_`, `w2_`) so delta_t == 0.333s!
        if pa.name[:3] != pb.name[:3]:
            idx += 1
            continue

        try:
            ima = Image.open(pa).convert("RGB")
        except Exception:
            idx += 1
            continue

        # Check for dark enemy skill popup on rare non-board frames
        vx0_p, vx1_p = find_game_column_span(np.asarray(ima))
        vp_a = ima.crop((vx0_p, 0, vx1_p, ima.height))
        if kept_popups < 4 and is_dark_enemy_skill_modal(np.asarray(vp_a)):
            hp = compute_dhash64(vp_a)
            if all(hamming_distance(hp, prev) > 6 for prev in seen_popup_hashes):
                popups_dir.mkdir(parents=True, exist_ok=True)
                vp_a.save(popups_dir / f"popup_{kept_popups:02d}_{hp:016x}.webp", format="WEBP", quality=90)
                seen_popup_hashes.append(hp)
                kept_popups += 1
            idx += 3
            continue

        # Fast reuse of verified crop coords when video layout is static; re-detect if periodicity drops!
        coords = None
        if cached_coords is not None:
            x0, y0, x1, y1, vx0, vx1 = cached_coords
            if x1 <= ima.width and y1 <= ima.height:
                cand_a = np.asarray(ima.crop((x0, y0, x1, y1)).resize((384, 320), Image.Resampling.BILINEAR), dtype=np.uint8)
                ok_c, _ = has_6x5_grid_periodicity(cand_a)
                if ok_c:
                    coords = cached_coords

        if coords is None:
            coords = detect_refined_board_coords(ima)
            if coords is not None:
                cached_coords = coords

        if coords is None:
            idx += 2
            continue

        x0, y0, x1, y1, vx0, vx1 = coords
        ba_pil = ima.crop((x0, y0, x1, y1)).resize((384, 320), Image.Resampling.BILINEAR)
        ha = compute_dhash64(ba_pil)
        if any(hamming_distance(ha, prev) <= 5 for prev in seen_board_hashes):
            idx += 2
            continue

        try:
            imb = Image.open(pb).convert("RGB")
        except Exception:
            idx += 2
            continue

        bb_pil = imb.crop((x0, y0, x1, y1)).resize((384, 320), Image.Resampling.BILINEAR)
        hb = compute_dhash64(bb_pil)
        # Require static board between t and t+0.33s (`hamming <= 4`), rejecting finger drags & skyfalls!
        if hamming_distance(ha, hb) > 4:
            idx += 1
            continue
        ok_b, _ = has_6x5_grid_periodicity(np.asarray(bb_pil, dtype=np.uint8))
        if not ok_b:
            idx += 1
            continue

        boards_dir.mkdir(parents=True, exist_ok=True)
        strips_dir.mkdir(parents=True, exist_ok=True)
        stem = f"p{kept_pairs:03d}_{ha:016x}"
        ba_pil.save(boards_dir / f"{stem}_a.webp", format="WEBP", quality=92)
        bb_pil.save(boards_dir / f"{stem}_b.webp", format="WEBP", quality=92)

        # Save aligned upper enemy CD/icon strip `[0.04..0.96] x [0.08..0.42]` of the portrait game viewport
        vw = vx1 - vx0
        vh = ima.height
        sx0 = max(0, vx0 + int(vw * 0.04))
        sx1 = min(ima.width, vx0 + int(vw * 0.96))
        sy0 = int(vh * 0.08)
        sy1 = max(sy0 + 40, min(y0, int(vh * 0.42)))
        strip_pil = ima.crop((sx0, sy0, sx1, sy1)).resize((512, 320), Image.Resampling.BILINEAR)
        strip_pil.save(strips_dir / f"{stem}_strip.webp", format="WEBP", quality=92)

        # Also check if 3 rows directly above `y0` form a 6x8 expanded Weijian board (no pink HP bar in `[y0-3*ch .. y0]`)!
        ch_px = (y1 - y0) / 5.0
        y0_68 = int(round(y0 - 3.0 * ch_px))
        if y0_68 >= int(vh * 0.18):
            upper3 = np.asarray(ima.crop((x0, y0_68, x1, y0)).resize((384, 192), Image.Resampling.BILINEAR), dtype=np.uint8)
            uh, us, uv = rgb_to_hsv_np(upper3)
            pink_band = float((((uh >= 328.0) | (uh <= 20.0)) & (us >= 0.28) & (uv >= 0.45)).mean(axis=1).max())
            u_inters = [float(uv[r * 64 - 4:r * 64 + 4, c * 64 - 4:c * 64 + 4].mean()) for r in (1, 2) for c in range(1, 6)]
            if pink_band < 0.22 and sum(1 for v in u_inters if v < 0.44) >= 7:
                b68_dir.mkdir(parents=True, exist_ok=True)
                ima.crop((x0, y0_68, x1, y1)).resize((384, 512), Image.Resampling.BILINEAR).save(
                    b68_dir / f"{stem}_6x8_a.webp", format="WEBP", quality=92
                )
                imb.crop((x0, y0_68, x1, y1)).resize((384, 512), Image.Resampling.BILINEAR).save(
                    b68_dir / f"{stem}_6x8_b.webp", format="WEBP", quality=92
                )
                kept_6x8 += 1

        seen_board_hashes.append(ha)
        kept_pairs += 1
        idx += 4  # Advance ~1.33s after capturing a clean static pair

    return {"pairs_6x5": kept_pairs, "pairs_6x8": kept_6x8, "enemy_popups": kept_popups}


def main() -> int:
    ap = argparse.ArgumentParser(description="Broad-spectrum YouTube dual-frame corpus collector")
    ap.add_argument("--max-per-query", type=int, default=2, help="New videos to fetch per query")
    ap.add_argument("--workers", type=int, default=4, help="Parallel download/extract workers")
    ap.add_argument("--max-pairs-per-video", type=int, default=20, help="Max deduplicated static pairs per video")
    ap.add_argument("--limit-videos", type=int, default=0, help="Optional cap on total videos in this run (0 = all)")
    args = ap.parse_args()

    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    seen_ids = collect_all_known_video_ids()
    lock = threading.Lock()
    print(f"[BROAD-INIT] Existing deduplicated video IDs across backups/: {len(seen_ids)}", flush=True)

    jobs: List[Tuple[str, str, str, str, Optional[str]]] = []
    for dim, query, race_hint in BROAD_CORPUS_QUERIES:
        try:
            hits = search_youtube(query, max_results=args.max_per_query * 3 + 2)
        except Exception as exc:
            print(f"[WARN] Search failed for '{query}': {exc}", flush=True)
            continue
        picked = 0
        for h in hits:
            vid = str(h.get("id") or "")
            dur = float(h.get("duration") or 0)
            if not vid or vid in seen_ids or (dur > 0 and (dur < 45 or dur > 1200)):
                continue
            seen_ids.add(vid)
            tag = sanitize_tag(f"yt_{vid}_{h.get('title', '')[:20]}")
            jobs.append((dim, tag, vid, query, race_hint))
            picked += 1
            if picked >= args.max_per_query:
                break
        if args.limit_videos > 0 and len(jobs) >= args.limit_videos:
            jobs = jobs[:args.limit_videos]
            break

    print(f"[BROAD-QUEUE] Queued {len(jobs)} brand-new YouTube gameplay videos across {len(BROAD_CORPUS_QUERIES)} queries", flush=True)
    results: List[Dict[str, Any]] = []

    def _worker(job: Tuple[str, str, str, str, Optional[str]]) -> Dict[str, Any]:
        dim, tag, vid, query, race_hint = job
        batch_dir = OUT_ROOT / dim / tag
        meta_path = batch_dir / "metadata.json"
        if meta_path.is_file():
            try:
                m = json.loads(meta_path.read_text(encoding="utf-8"))
                return {**m, "cached": True}
            except Exception:
                pass

        url = f"https://www.youtube.com/watch?v={vid}"
        try:
            with tempfile.TemporaryDirectory(prefix=f"broad_{vid}_") as tmp:
                tmp_p = Path(tmp)
                vpath, vmeta = download_video_temp(url, tmp_p / "vid")
                dur = float(vmeta.get("duration") or 180.0)
                raw_frames = extract_multiwindow_3fps_frames(vpath, tmp_p / "raw", dur)
                counts = process_video_dual_pairs(raw_frames, batch_dir, max_pairs=args.max_pairs_per_video)
                rec = {
                    "dimension": dim,
                    "tag": tag,
                    "video_id": vid,
                    "query": query,
                    "verified_race_hint": race_hint,
                    "source": {"type": "youtube_broad_v1", "video_id": vid, **vmeta},
                    **counts,
                }
                if counts["pairs_6x5"] > 0 or counts["enemy_popups"] > 0:
                    batch_dir.mkdir(parents=True, exist_ok=True)
                    meta_path.write_text(json.dumps(rec, ensure_ascii=False, indent=2), encoding="utf-8")
                print(
                    f"[OK] {dim}/{tag}: pairs_6x5={counts['pairs_6x5']}, 6x8={counts['pairs_6x8']}, "
                    f"popups={counts['enemy_popups']} ({vmeta.get('title')})",
                    flush=True,
                )
                return rec
        except Exception as exc:
            print(f"[WARN] Failed {dim}/{tag} ({vid}): {exc}", flush=True)
            return {"dimension": dim, "tag": tag, "video_id": vid, "query": query, "pairs_6x5": 0, "error": str(exc)}

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futs = [pool.submit(_worker, j) for j in jobs]
        for fut in as_completed(futs):
            r = fut.result()
            with lock:
                results.append(r)

    # Aggregate ALL completed `metadata.json` across `OUT_ROOT` so resumable runs accumulate cleanly
    all_records: List[Dict[str, Any]] = []
    for mp in sorted(OUT_ROOT.rglob("metadata.json")):
        try:
            all_records.append(json.loads(mp.read_text(encoding="utf-8")))
        except Exception:
            continue

    manifest = {
        "generated_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "videos_with_valid_samples": len(all_records),
        "last_run_attempted": len(results),
        "total_pairs_6x5": sum(int(x.get("pairs_6x5", 0)) for x in all_records),
        "total_pairs_6x8": sum(int(x.get("pairs_6x8", 0)) for x in all_records),
        "total_upper_strips": sum(int(x.get("pairs_6x5", 0)) for x in all_records),
        "total_enemy_popups": sum(int(x.get("enemy_popups", 0)) for x in all_records),
        "items": all_records,
    }
    (OUT_ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        f"[BROAD-DONE] Videos={manifest['videos_with_valid_samples']}, "
        f"pairs_6x5={manifest['total_pairs_6x5']}, pairs_6x8={manifest['total_pairs_6x8']}, "
        f"strips={manifest['total_upper_strips']}, popups={manifest['total_enemy_popups']}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
