#!/usr/bin/env python3
"""
Audit CvBoardRecognizer HSV feature distributions across real curated ToS frames.
Measures recall & false positives for Evil Energy (all 6 colors), Electrified,
Frozen, Bomb, Bloodbath, Blood-Thorn, and Coagulated runestones.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "ml" / "tools"))
from extract_tos_dataset import BoardBox, detect_tos_board, rgb_to_hsv_np  # noqa: E402


def compute_cell_features(rgb: np.ndarray, box: BoardBox, cols: int = 6, rows: int = 5) -> List[dict]:
    """Mirror CvBoardRecognizer.analyzeCellAbnormalMultiFrame 13x13 grid per cell."""
    cw = box.width / cols
    ch = box.height / rows
    img_h, img_w, _ = rgb.shape
    grid_n = 13
    step_norm = 0.465 / 6.0

    results = []
    for r in range(rows):
        for c in range(cols):
            cx = box.left + (c + 0.5) * cw
            cy = box.top + (r + 0.5) * ch

            xs = [min(img_w - 1, max(0, int(round(cx + ix * step_norm * cw)))) for ix in range(-6, 7)]
            ys = [min(img_h - 1, max(0, int(round(cy + iy * step_norm * ch)))) for iy in range(-6, 7)]
            patch = rgb[np.ix_(ys, xs)]  # [13, 13, 3]
            h, s, v = rgb_to_hsv_np(patch)

            iy_idx, ix_idx = np.meshgrid(np.arange(-6, 7), np.arange(-6, 7), indexing="ij")
            dx = ix_idx * step_norm
            dy = iy_idx * step_norm
            rr = np.hypot(dx, dy)
            linf = np.maximum(np.abs(dx), np.abs(dy))

            body = rr <= 0.385
            inner = rr <= 0.285
            core = rr <= 0.145
            mid = (rr >= 0.18) & (rr <= 0.355)
            rim = (rr >= 0.22) & (rr <= 0.385)
            silver_rim = (rr >= 0.36) & (rr <= 0.45)
            border = linf >= 0.385
            corner = (np.abs(dx) >= 0.33) & (np.abs(dy) >= 0.33)

            avg_v = float(v[body].mean())
            dark_body = float((v[body] < 0.28).mean())
            high_v_body = float((v[body] >= 0.60).mean())
            sat_body = float(((s[body] >= 0.35) & (v[body] >= 0.28)).mean())
            crack_silver = float(((s[body] <= 0.22) & (v[body] >= 0.22) & (v[body] <= 0.85)).mean())
            teal_wisp = float(((h[body] >= 162.0) & (h[body] <= 198.0) & (s[body] >= 0.22) & (v[body] >= 0.32)).mean())
            # Element-agnostic glowing wisp / speckle inside dark Evil Energy orb
            any_wisp = float(((s[body] >= 0.24) & (v[body] >= 0.28)).mean())
            bright_any_wisp = float(((s[body] >= 0.22) & (v[body] >= 0.34)).mean())
            q_glyph = float(((v[body] >= 0.62) & (s[body] <= 0.25)).mean())
            s_rim = float(((v[silver_rim] >= 0.48) & (s[silver_rim] <= 0.42)).mean())
            bb_red = float((((h[body] <= 22.0) | (h[body] >= 332.0)) & (s[body] >= 0.28) & (v[body] >= 0.12) & (v[body] <= 0.42)).mean())
            bb_corner = float((((h[corner] <= 22.0) | (h[corner] >= 332.0)) & (s[corner] >= 0.28) & (v[corner] >= 0.12) & (v[corner] <= 0.38)).mean())

            # Crimson-red jagged tooth box border (#20 Blood-Thorn) & ruby crystal (#05 Coagulated)
            crimson_border = float((((h[border] <= 18.0) | (h[border] >= 342.0)) & (s[border] >= 0.62) & (v[border] >= 0.46)).mean())
            crimson_inner = float((((h[inner] <= 18.0) | (h[inner] >= 342.0)) & (s[inner] >= 0.55) & (v[inner] >= 0.32)).mean())
            crimson_body = float((((h[body] <= 18.0) | (h[body] >= 342.0)) & (s[body] >= 0.55) & (v[body] >= 0.32)).mean())

            # Electrified & Frozen checks
            wh_core = float(((v[inner] >= 0.90) & (s[inner] <= 0.26)).mean())
            wh_body = float(((v[body] >= 0.88) & (s[body] <= 0.28)).mean())
            elec_border = float(((v[border] >= 0.52) & ((h[border] >= 182.0) & (h[border] <= 270.0) | (s[border] <= 0.26))).mean())
            elec_cyan_border = float(((v[border] >= 0.50) & (h[border] >= 182.0) & (h[border] <= 262.0) & (s[border] >= 0.24)).mean())
            ice_white_body = float(((v[body] >= 0.74) & (s[body] <= 0.30)).mean())
            sat_color_core = float(((v[inner] >= 0.38) & (s[inner] >= 0.32)).mean())
            bomb_red = float((((h[core] <= 18.0) | (h[core] >= 342.0)) & (s[core] >= 0.58) & (v[core] >= 0.52)).mean())
            bomb_grey = float(((s[mid] <= 0.24) & (v[mid] >= 0.22) & (v[mid] <= 0.68)).mean())

            # Check old vs proposed Evil Energy
            looks_q = (q_glyph >= 0.045) and (s_rim >= 0.25)
            old_evil = (not looks_q) and avg_v <= 0.34 and dark_body >= 0.54 and high_v_body <= 0.08 and teal_wisp >= 0.06
            cand_evil = (
                (not looks_q)
                and crack_silver < 0.12
                and bb_red < 0.45
                and (
                    (avg_v <= 0.26 and dark_body >= 0.62 and high_v_body <= 0.12 and any_wisp >= 0.01)
                    or (avg_v <= 0.16 and dark_body >= 0.85 and high_v_body <= 0.05)
                )
            )
            old_bb = avg_v <= 0.38 and high_v_body <= 0.08 and bb_red >= 0.48 and bb_corner >= 0.16
            old_bomb = bomb_red >= 0.22 and bomb_grey >= 0.45

            results.append({
                "r": r, "c": c, "avgV": avg_v, "darkB": dark_body, "hiV": high_v_body,
                "crackS": crack_silver, "tealW": teal_wisp, "anyW": any_wisp, "brightW": bright_any_wisp,
                "qGlyph": q_glyph, "sRim": s_rim, "looksQ": looks_q,
                "bbRed": bb_red, "bbCor": bb_corner, "old_bb": old_bb,
                "old_evil": old_evil, "cand_evil": cand_evil, "old_bomb": old_bomb,
                "crimBdr": crimson_border, "crimIn": crimson_inner, "crimBody": crimson_body,
                "whCore": wh_core, "whBody": wh_body, "elecCyan": elec_cyan_border,
                "iceWh": ice_white_body, "satCore": sat_color_core,
            })
    return results


def main() -> int:
    media = REPO_ROOT / "backups" / "user_training_media"
    groups: Dict[str, List[Path]] = {
        "A3_evil_all6": sorted((media / "20261125_yt_user_batch3" / "A3_evil_energy_6colors_complete").glob("*.png"))
        + sorted((media / "20261125_yt_verified_batch" / "A3_evil_energy_wood_dark").glob("*.png")),
        "A2_elec_6x8": sorted((media / "20261125_yt_user_batch2" / "A2_electrified_fire_wood_6x8").glob("*.png")),
        "A4_frozen": sorted((media / "20261125_yt_user_batch2" / "A4_frozen_unfrozen_light").glob("*.png")),
        "A5_bomb": sorted((media / "20261125_yt_user_batch2" / "A5_bomb_wood_light_heart").glob("*.png")),
        "A7_bloodbath": sorted((media / "20261125_yt_user_batch2" / "A7_bloodbath_heart").glob("*.png")),
        "C9_blood_thorn": sorted((media / "20261125_yt_user_batch2" / "C9_blood_spine_runestones").glob("*.png")),
        "C3_coagulated": sorted((media / "20261125_yt_user_batch3" / "C3_coagulated_double_and_single").glob("*.png")),
        "CTRL_q30_teal": sorted((media / "20261125_dark_teal_question_30").glob("*.*")),
        "CTRL_clean_and_music": sorted((media / "20261124_blessed_and_music_6colors").glob("*.jpg"))
        + sorted((media / "20261123_banner_emptyhp_race").glob("*.png"))
        + sorted((REPO_ROOT / "backups" / "phase4_training_dataset" / "real_battle_samples").glob("*.*")),
        "CTRL_monochrome": sorted((media / "20261125_monochrome_and_banned_wood").glob("*.jpg")),
    }

    for gname, paths in groups.items():
        print(f"\n=== GROUP: {gname} ({len(paths)} files) ===")
        for p in paths:
            if p.suffix.lower() not in {".png", ".jpg", ".jpeg"}:
                continue
            rgb = np.array(Image.open(p).convert("RGB"))
            box = detect_tos_board(rgb)
            if box is None:
                print(f"  [NO_BOARD] {p.name} ({rgb.shape[1]}x{rgb.shape[0]})")
                continue
            rows = 8 if "6x8" in p.name or "6x8" in gname else 5
            cells = compute_cell_features(rgb, box, cols=6, rows=rows)
            n_old_evil = sum(x["old_evil"] for x in cells)
            n_cand_evil = sum(x["cand_evil"] for x in cells)
            n_old_bb = sum(x["old_bb"] for x in cells)
            n_bomb = sum(x["old_bomb"] for x in cells)
            bright_cnt = sum(x["avgV"] >= 0.42 for x in cells)
            crim_bdr_hi = [(x["r"], x["c"], round(x["crimBdr"], 2), round(x["crimIn"], 2)) for x in cells if x["crimBdr"] >= 0.35 or x["crimIn"] >= 0.55]
            print(
                f"  {p.name} ({rows}x6) bright={bright_cnt} | oldEvil={n_old_evil} candEvil={n_cand_evil} "
                f"| oldBB={n_old_bb} | bomb={n_bomb} | crimHits={len(crim_bdr_hi)}"
            )
            if gname == "A3_evil_all6" and (n_cand_evil == 0 or n_old_evil != n_cand_evil):
                cands = [(x["r"], x["c"], round(x["avgV"], 2), round(x["darkB"], 2), round(x["anyW"], 2), round(x["crackS"], 2), int(x["looksQ"])) for x in cells if x["avgV"] <= 0.38]
                print(f"    darkCells(avgV<=0.38): {cands[:10]}")
            if gname in {"C9_blood_thorn", "C3_coagulated", "CTRL_clean_and_music"} and crim_bdr_hi:
                print(f"    crimHi: {crim_bdr_hi[:8]}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
