#!/usr/bin/env python3
"""
Targeted YouTube Top-Up Downloader for Low-Recall Board Mechanics & In-Battle Enemy Icons (v0.9.0)
==================================================================================================
Searches & downloads public 1080p Tower of Saviors gameplay videos covering low-recall classes
identified in `docs/youtube_low_recall_acquisition_plan.md`, writing ONLY under gitignored
`backups/phase4_v2_inbox/topup_v090_low_recall/` without modifying any existing raw files.

For every kept static battle frame, saves:
  1) `frames/`       : clean vertical game viewport
  2) `boards/`       : 6x5 board crop + `orbs/` 64x64 tiles
  3) `upper_strips/` : [0.04..0.96] x [0.08..0.42] upper-battle enemy CD & skill icon strip
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import sys
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "ml" / "tools"))

from extract_tos_dataset import (  # noqa: E402
    extract_frames_ffmpeg,
    process_images,
    sanitize_tag,
    search_youtube,
)

INBOX_ROOT = REPO_ROOT / "backups" / "phase4_v2_inbox"
OUT_ROOT = INBOX_ROOT / "topup_v090_low_recall"

TARGET_QUERIES: List[Tuple[str, str]] = [
    # P0 Races (0% or low recall in BoardMasterGridNetV2)
    ("race_beast", "神魔之塔 獸族符石 隊長 通關"),
    ("race_beast", "神魔之塔 獸隊 實戰 地獄級"),
    ("race_machina", "神魔之塔 機械族符石 隊長 通關"),
    ("race_machina", "神魔之塔 龐貝 機械隊 地獄級"),
    ("race_god_elf_demon", "神魔之塔 神族符石 隊長 通關"),
    ("race_god_elf_demon", "神魔之塔 妖精符石 隊長 通關"),
    ("race_god_elf_demon", "神魔之塔 魔族符石 隊長 地獄級"),
    # P0 Monochrome & Rare Hazards
    ("board_black_white", "神魔之塔 幽閉空間 地獄級"),
    ("board_black_white", "神魔之塔 黑白符石 災厄級"),
    ("hazard_purgatory_thorn", "神魔之塔 煉獄之火 災厄級"),
    ("hazard_purgatory_thorn", "神魔之塔 荊棘符石 地獄級"),
    ("hazard_magic_bloodthorn", "神魔之塔 魔力符石 實戰"),
    ("hazard_magic_bloodthorn", "神魔之塔 刺血符石 地獄級"),
    # P1 Overlays & Weathered/Question
    ("overlay_storm_shattered", "神魔之塔 暴風 地獄級 通關"),
    ("overlay_storm_shattered", "神魔之塔 碎裂 黑洞 災厄級"),
    ("hazard_weathered_question", "神魔之塔 風化符石 問號符石 地獄級"),
    # P0 In-Battle Enemy Skill Icons & Numeric Badges
    ("icon_combo_digital_step", "神魔之塔 首消粒數盾 固定連擊盾 地獄級"),
    ("icon_combo_digital_step", "神魔之塔 步數限制盾 災厄級"),
    ("icon_elements_enchanted", "神魔之塔 五屬強化盾 符石禁咒 地獄級"),
    ("icon_traffic_burning_kind", "神魔之塔 起結有序 黏腐 燃燒 地獄級"),
    ("icon_traffic_burning_kind", "神魔之塔 首消相同種類盾 首消不同種類盾"),
]


def collect_existing_video_ids() -> Set[str]:
    seen: Set[str] = set()
    if not INBOX_ROOT.exists():
        return seen
    for mf in INBOX_ROOT.rglob("metadata.json"):
        try:
            data = json.loads(mf.read_text(encoding="utf-8"))
            src = data.get("source") or {}
            vid = src.get("video_id") or src.get("id")
            if vid:
                seen.add(str(vid))
        except Exception:
            continue
    return seen


def download_video_fast(url: str, dest_dir: Path) -> Tuple[Path, dict]:
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
                raise FileNotFoundError(f"Missing downloaded file for {url}")
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


def save_upper_battle_strips(batch_dir: Path) -> int:
    frames_dir = batch_dir / "frames"
    strips_dir = batch_dir / "upper_strips"
    if not frames_dir.exists():
        return 0
    strips_dir.mkdir(parents=True, exist_ok=True)
    cnt = 0
    for fp in sorted(frames_dir.glob("*.png")):
        try:
            im = Image.open(fp).convert("RGB")
            w, h = im.size
            crop = im.crop((int(w * 0.04), int(h * 0.08), int(w * 0.96), int(h * 0.42)))
            crop.save(strips_dir / f"{fp.stem}_strip.png")
            cnt += 1
        except Exception:
            continue
    return cnt


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-per-query", type=int, default=2)
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--max-frames", type=int, default=16)
    args = ap.parse_args()

    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    seen_ids = collect_existing_video_ids()
    lock = threading.Lock()
    print(f"[INIT] Existing video IDs in inbox: {len(seen_ids)}", flush=True)

    candidate_jobs: List[Tuple[str, str, str, str]] = []
    for cat, query in TARGET_QUERIES:
        try:
            hits = search_youtube(query, max_results=args.max_per_query * 3)
        except Exception as exc:
            print(f"[WARN] Search failed for '{query}': {exc}", flush=True)
            continue
        picked = 0
        for h in hits:
            vid = str(h.get("id") or "")
            dur = float(h.get("duration") or 0)
            # Keep normal gameplay videos (45s .. 1080s = 18 min), skip multi-hour streams
            if not vid or vid in seen_ids or (dur > 0 and (dur < 40 or dur > 1080)):
                continue
            seen_ids.add(vid)
            tag = sanitize_tag(f"yt_{vid}_{h.get('title', '')[:18]}")
            candidate_jobs.append((cat, tag, vid, query))
            picked += 1
            if picked >= args.max_per_query:
                break

    print(f"[QUEUE] Queued {len(candidate_jobs)} new YouTube gameplay videos", flush=True)

    results: List[Dict[str, Any]] = []

    def _run_one(job: Tuple[str, str, str, str]) -> Dict[str, Any]:
        cat, tag, vid, query = job
        batch_dir = OUT_ROOT / cat / tag
        meta_path = batch_dir / "metadata.json"
        if meta_path.exists():
            try:
                m = json.loads(meta_path.read_text(encoding="utf-8"))
                return {"category": cat, "tag": tag, "video_id": vid, "boards": m.get("kept_battle_frames", 0), "cached": True}
            except Exception:
                pass
        url = f"https://www.youtube.com/watch?v={vid}"
        try:
            with tempfile.TemporaryDirectory(prefix=f"topup_{vid}_") as tmp:
                tmp_p = Path(tmp)
                vpath, vmeta = download_video_fast(url, tmp_p / "vid")
                dur = float(vmeta.get("duration") or 0)
                st = "00:15" if dur > 45 else None
                en = "05:30" if dur > 360 else None
                raw = extract_frames_ffmpeg(vpath, tmp_p / "raw", fps=0.55, start=st, end=en)
                if not raw:
                    raw = extract_frames_ffmpeg(vpath, tmp_p / "raw", fps=0.55, start=None, end=None)
                summary = process_images(
                    raw,
                    batch_dir,
                    dedup_threshold=6.5,
                    max_frames=args.max_frames,
                    orb_size=64,
                    crop_orbs=True,
                    source_meta={"type": "youtube_topup_v090", "video_id": vid, "query": query, **vmeta},
                )
                strips = save_upper_battle_strips(batch_dir)
                kept = int(summary.get("kept_battle_frames", 0))
                print(f"[OK] {cat}/{tag}: boards={kept}, strips={strips} ({vmeta.get('title')})", flush=True)
                return {
                    "category": cat,
                    "tag": tag,
                    "video_id": vid,
                    "query": query,
                    "title": vmeta.get("title"),
                    "boards": kept,
                    "upper_strips": strips,
                }
        except Exception as exc:
            print(f"[WARN] Failed {cat}/{tag} ({vid}): {exc}", flush=True)
            return {"category": cat, "tag": tag, "video_id": vid, "query": query, "boards": 0, "error": str(exc)}

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futs = [pool.submit(_run_one, j) for j in candidate_jobs]
        for f in as_completed(futs):
            res = f.result()
            with lock:
                results.append(res)

    total_boards = sum(int(r.get("boards", 0)) for r in results)
    total_strips = sum(int(r.get("upper_strips", 0)) for r in results)
    manifest = {
        "generated_at": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "total_videos_processed": len(results),
        "total_kept_boards": total_boards,
        "total_upper_strips": total_strips,
        "items": sorted(results, key=lambda x: (x.get("category", ""), x.get("tag", ""))),
    }
    (OUT_ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[DONE] Saved manifest to {OUT_ROOT / 'manifest.json'}: boards={total_boards}, strips={total_strips}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
