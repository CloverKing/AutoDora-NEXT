"""
Build Stage 2A `phase4_v2` full-spectrum dual-frame board dataset (`[N, 320, 384, 3]` A & B +
`[N, 30, 47]` per-cell targets & per-head/bit validity masks) from ALL collected game & YouTube assets:
  1. `ml/datasets/phase4_v1/{train,val}.npz` (6,486 gold tiles reconstructed to 6x5 boards, preserving split)
  2. Unused curated real battle & YouTube verified boards in `backups/user_training_media/`
     (including 6x8 Weijian boards sliced into 6x5 windows, Blood-Drain video frames, Barrier frames,
      Evil Energy 6-color batch, Coagulated & Weakened batches)
  3. Official ToS Encyclopedia (`backups/tos_encyclopedia/`):
     - `enemy_skills/skill_048/slide_1.png` canonical 6-color monochrome Rosetta board
     - Clean 6x5 demonstration slides across 98 official `enemy_skills`
     - 21 official `140x140` runestone status sprites (`runestone_statuses_clean/status_01..20`)
       composited onto clean battle boards for rare-hazard coverage
  4. Real YouTube Monochrome boards (`yt_moon_wolf2` f001..f007 temporal pairs across Normal/Enchanted/Weakened)
     + exports training-only `mono_sprite_bank.npz` for authentic ToS monochrome online augmentation
  5. Quality-gated `backups/phase4_v2_inbox/` YouTube batches:
     - Rejects zoomed 3x3/4x4 crops, mid-combo `Combo!!` explosions, modal popups, shifted black-bar crops
     - Fixes `hazards_p0/black_white` colored boards -> `BLACK_WHITE = 0.0` (eliminating label inversion)
     - Fixes `hazards_p1_multiscene` (`SHATTERED`, `PUZZLE`, `STORM`, `TRAFFIC_LIGHT_MARKER`, `RACE`)
     - Extracts BOTH 63 board-crop pairs AND 63 full-screen pairs from `board_static_pairs` (~126 real flicker pairs)
  6. Hard negatives (`is_rune = 0`) from `enemy_popups_live/` & dark skill modals

Strictly READ-ONLY on `backups/` and `ml/datasets/phase4_v1/`. Writes ONLY to `ml/datasets/phase4_v2/`.
"""

from __future__ import annotations

import json
import random
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))
sys.path.insert(0, str(REPO_ROOT / "ml" / "tools"))

from extract_tos_dataset import BoardBox, detect_tos_board, rgb_to_hsv_np  # noqa: E402
from ml.schema import (  # noqa: E402
    ELEMENT_NAMES,
    ELEMENT_TO_IDX,
    HAZARD_NAMES,
    HAZARD_TO_IDX,
    NUM_ELEMENTS,
    NUM_HAZARDS,
    NUM_RACES,
    NUM_TILE_OVERLAYS,
    NUM_VARIANTS,
    OUTPUT_DIM,
    RACE_NAMES,
    RACE_TO_IDX,
    SLICE_ELEMENT,
    SLICE_HAZARDS,
    SLICE_IS_RUNE,
    SLICE_RACE,
    SLICE_TILE_OVERLAY,
    SLICE_VARIANT,
    TILE_OVERLAY_NAMES,
    TILE_OVERLAY_TO_IDX,
    VARIANT_NAMES,
    VARIANT_TO_IDX,
)
from ml.tools.build_phase4_dataset import (  # noqa: E402
    analyze_bomb_and_evil_flags,
    detect_custom_yongjue_b1,
    hsv_base_classify_cell,
    parse_grid,
)

BOARD_H = 5 * 64  # 320
BOARD_W = 6 * 64  # 384
CELL_PX = 64
NUM_CELLS = 30

RACE_FOLDER_TO_NAME = {
    "dragon": "DRAGON",
    "god": "GOD",
    "elf": "ELF",
    "demon": "DEMON",
    "machina": "MACHINA",
    "beast": "BEAST",
}

_YY64, _XX64 = np.mgrid[0:64, 0:64]
_ORB_CIRC = ((_XX64 - 31.5) ** 2 + (_YY64 - 31.5) ** 2) <= (24.5 ** 2)
_CORNER_MASK = ((_XX64 - 31.5) ** 2 + (_YY64 - 31.5) ** 2) >= (33.5 ** 2)


def detect_race_medallion_cell64(cell64: np.ndarray) -> tuple[bool, bool]:
    """
    Shift-tolerant circular Race Medallion detector for 64x64 cells across all 7 ToS races
    (GOD, DEMON, HUMAN, BEAST, DRAGON, ELF, MACHINA).
    Returns `(has_medallion, confident_no_medallion)`.
    """
    _, ss, vv = rgb_to_hsv_np(cell64)
    best_iv = 0.0
    for cy_m in (10, 13, 16):
        for cx_m in (47, 51, 55):
            r2 = (_XX64 - cx_m) ** 2 + (_YY64 - cy_m) ** 2
            disc = r2 <= (7.4 ** 2)
            core = r2 <= (5.3 ** 2)
            dk = float((vv[disc] <= 0.38).mean())
            iv = float(((vv[core] >= 0.64) & (ss[core] <= 0.42)).mean())
            viv = float(((ss[disc] >= 0.52) & (vv[disc] >= 0.46)).mean())
            if dk >= 0.24 and iv >= 0.10 and viv <= 0.38:
                return True, False
            if iv > best_iv:
                best_iv = iv
    return False, (best_iv < 0.05)


def has_6x5_grid_periodicity(board_rgb: np.ndarray) -> tuple[bool, float]:
    """Verifies authentic 6x5 (384x320) 64px cell periodicity and rejects text slides / zoomed crops."""
    if board_rgb[:, :10].mean() < 8.0 or board_rgb[:, -10:].mean() < 8.0:
        return False, -1.0
    _, ss, vv = rgb_to_hsv_np(board_rgb)
    inter_v = [float(vv[r * 64 - 4:r * 64 + 4, c * 64 - 4:c * 64 + 4].mean()) for r in range(1, 5) for c in range(1, 6)]
    center_v = [float(vv[r * 64 + 22:r * 64 + 42, c * 64 + 22:c * 64 + 42].mean()) for r in range(5) for c in range(6)]
    col_v = vv.mean(axis=0)
    c_peaks = float(np.mean([col_v[c * 64 + 20:c * 64 + 44].mean() for c in range(6)]))
    c_seams = float(np.mean([col_v[c * 64 - 4:c * 64 + 4].mean() for c in range(1, 6)]))
    row_v = vv.mean(axis=1)
    r_peaks = float(np.mean([row_v[r * 64 + 20:r * 64 + 44].mean() for r in range(5)]))
    r_seams = float(np.mean([row_v[r * 64 - 4:r * 64 + 4].mean() for r in range(1, 5)]))
    dark_inters = sum(1 for v in inter_v if v < 0.44)
    score = (c_peaks - c_seams) + (r_peaks - r_seams) + 0.02 * dark_inters
    ok = (
        dark_inters >= 13
        and float(np.median(center_v)) >= float(np.median(inter_v)) * 1.28
        and c_peaks >= c_seams + 0.038
        and r_peaks >= r_seams + 0.038
    )
    return ok, score


def load_refined_6x5_board(board_path: Path) -> np.ndarray | None:
    """
    Re-refines `(width, cx, top)` from sibling `frames/*.png` when available to eliminate 16:9
    YouTube sidebar horizontal shift, falling back to `board_path` when periodicity is already valid.
    """
    frame_path = board_path.parents[1] / "frames" / board_path.name.replace("_board.png", ".png")
    if frame_path.is_file():
        try:
            im = Image.open(frame_path).convert("RGB")
            rgb = np.asarray(im)
            h, w, _ = rgb.shape
            box = detect_tos_board(rgb)
            if box is not None:
                bw0 = box.width
                cx0 = 0.5 * (box.left + box.right)
                best_arr: np.ndarray | None = None
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
                        for dy_frac in (-0.02, 0.0, 0.02):
                            y0 = int(round(box.top + dy_frac * bh))
                            y1 = y0 + bh
                            if y0 < 0 or y1 > h:
                                continue
                            cand = np.asarray(
                                im.crop((x0, y0, x1, y1)).resize((BOARD_W, BOARD_H), Image.Resampling.BILINEAR),
                                dtype=np.uint8,
                            )
                            ok, sc = has_6x5_grid_periodicity(cand)
                            if ok and sc > best_sc:
                                best_sc = sc
                                best_arr = cand
                if best_arr is not None:
                    return best_arr
        except Exception:
            pass
    cand = _load_board_rgb(board_path)
    if cand is not None and has_6x5_grid_periodicity(cand)[0]:
        return cand
    return None

# Visually distinct hazards safe to mark negative (0.0) on verified clean colored boards
_CLEAN_BOARD_NEG_HAZARDS = [
    HAZARD_TO_IDX["ICE_SEALED"],
    HAZARD_TO_IDX["PETRIFIED"],
    HAZARD_TO_IDX["BOMB"],
    HAZARD_TO_IDX["QUESTION"],
    HAZARD_TO_IDX["BLACK_WHITE"],
    HAZARD_TO_IDX["PURGATORY_FIRE"],
    HAZARD_TO_IDX["EVIL_ENERGY"],
    HAZARD_TO_IDX["COAGULATED"],
]

_CLEAN_BOARD_NEG_OVERLAYS = [
    TILE_OVERLAY_TO_IDX["STORM"],
    TILE_OVERLAY_TO_IDX["SHATTERED"],
    TILE_OVERLAY_TO_IDX["BURNING_TILE"],
    TILE_OVERLAY_TO_IDX["SCORCHING_TILE"],
    TILE_OVERLAY_TO_IDX["END_FORBIDDEN_BLOODBATH"],
    TILE_OVERLAY_TO_IDX["BLACK_HOLE"],
    TILE_OVERLAY_TO_IDX["TRAFFIC_LIGHT_MARKER"],
]


class BoardSampleStore:
    def __init__(self) -> None:
        self.boards_a: list[np.ndarray] = []
        self.boards_b: list[np.ndarray] = []
        self.has_real_pair: list[bool] = []
        self.targets: list[np.ndarray] = []
        self.is_rune_mask: list[np.ndarray] = []
        self.element_mask: list[np.ndarray] = []
        self.variant_mask: list[np.ndarray] = []
        self.race_mask: list[np.ndarray] = []
        self.hazard_mask: list[np.ndarray] = []
        self.overlay_mask: list[np.ndarray] = []
        self.sample_weights: list[float] = []
        self.groups: list[str] = []
        self.sources: list[str] = []
        self.is_val: list[bool] = []

    def add_board(
        self,
        board_a: np.ndarray,
        board_b: np.ndarray | None,
        targets: np.ndarray,
        is_rune_mask: np.ndarray,
        element_mask: np.ndarray,
        variant_mask: np.ndarray,
        race_mask: np.ndarray,
        hazard_mask: np.ndarray,
        overlay_mask: np.ndarray,
        weight: float,
        group: str,
        source: str,
        val: bool,
    ) -> None:
        assert board_a.shape == (BOARD_H, BOARD_W, 3)
        if board_b is None:
            self.boards_b.append(board_a)
            self.has_real_pair.append(False)
        else:
            assert board_b.shape == (BOARD_H, BOARD_W, 3)
            self.boards_b.append(board_b)
            self.has_real_pair.append(True)
        self.boards_a.append(board_a)
        self.targets.append(targets.astype(np.float32))
        self.is_rune_mask.append(is_rune_mask.astype(np.float32))
        self.element_mask.append(element_mask.astype(np.float32))
        self.variant_mask.append(variant_mask.astype(np.float32))
        self.race_mask.append(race_mask.astype(np.float32))
        self.hazard_mask.append(hazard_mask.astype(np.float32))
        self.overlay_mask.append(overlay_mask.astype(np.float32))
        self.sample_weights.append(float(weight))
        self.groups.append(group)
        self.sources.append(source)
        self.is_val.append(bool(val))


def _load_board_rgb(path: Path) -> np.ndarray | None:
    try:
        im = Image.open(path).convert("RGB").resize((BOARD_W, BOARD_H), Image.Resampling.BILINEAR)
        return np.asarray(im, dtype=np.uint8)
    except Exception:
        return None


def is_clean_static_6x5_board(
    board_rgb: np.ndarray,
    *,
    allow_dark_holes: bool = False,
    allow_monochrome: bool = False,
) -> bool:
    """
    Rejects corrupted YouTube scraped boards:
      - Shifted crops with black sidebars/bottom bars
      - Zoomed 3x3 / 4x4 sub-board crops or anime face cutscenes / wiki tables
      - Mid-combo `Combo!!` explosions & half-empty skyfall boards
      - Central UI confirmation dialogs or AutoDora neon path lines
    """
    # 1. Reject black sidebars from mis-cropped videos (e.g. left/right 14px purely black)
    if board_rgb[:, :12].mean() < 8.0 or board_rgb[:, -12:].mean() < 8.0:
        return False

    hh, ss, vv = rgb_to_hsv_np(board_rgb)

    # 2. Reject AutoDora path overlays (thin bright neon magenta/cyan lines) or bottom white toast
    toast_patch_s = ss[275:312, 70:314]
    toast_patch_v = vv[275:312, 70:314]
    if float(((toast_patch_s < 0.08) & (toast_patch_v > 0.90)).mean()) > 0.48:
        return False

    # 3. Reject dark UI confirmation dialogs covering center 4x3 cells
    center_box_s = ss[96:240, 80:304]
    center_box_v = vv[96:240, 80:304]
    # Confirmation dialog has muted dark blue-grey box with two teal buttons
    if float(((center_box_s < 0.32) & (center_box_v < 0.42)).mean()) > 0.68 and not allow_monochrome and not allow_dark_holes:
        return False

    valid_orbs = 0
    empty_cells = 0
    explosion_cells = 0
    corner_dark_ok = 0

    for r in range(5):
        for c in range(6):
            patch = board_rgb[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64]
            ps = ss[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64]
            pv = vv[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64]
            orb_s = float(ps[_ORB_CIRC].mean())
            orb_v = float(pv[_ORB_CIRC].mean())
            cor_v = float(pv[_CORNER_MASK].mean())
            cor_s = float(ps[_CORNER_MASK].mean())

            lum = patch.astype(np.float32).mean(axis=-1)
            grad = float(np.abs(np.diff(lum, axis=0)).mean() + np.abs(np.diff(lum, axis=1)).mean())

            # Normal 6x5 cell corners show brown checkerboard (darker & less bright than orb body)
            if cor_v < 0.52 and (orb_v > cor_v * 1.08 or grad >= 10.0):
                corner_dark_ok += 1

            # Mid-combo explosion / white-gold flash washout
            if float(((pv > 0.90) & (ps < 0.28)).mean()) > 0.38:
                explosion_cells += 1
                continue

            # Empty checkerboard hole (orb cleared mid-combo)
            if orb_s < 0.32 and orb_v < 0.34 and grad < 7.5:
                empty_cells += 1
                continue

            if orb_s >= 0.24 and orb_v >= 0.18 and grad >= 7.5:
                valid_orbs += 1
            elif allow_monochrome and orb_s < 0.14 and grad >= 9.0 and float(pv[_ORB_CIRC].max()) >= 0.45:
                valid_orbs += 1
            elif allow_dark_holes and grad >= 8.0:
                valid_orbs += 1

    # Also check bottom-right `Combo!!` golden text overlay
    br_h = hh[210:316, 210:380]
    br_s = ss[210:316, 210:380]
    br_v = vv[210:316, 210:380]
    combo_gold = float((((br_h >= 22.0) & (br_h <= 52.0)) & (br_s >= 0.65) & (br_v >= 0.82)).mean())
    if combo_gold > 0.22 and (empty_cells >= 2 or explosion_cells >= 1):
        return False

    if explosion_cells >= 3:
        return False
    if not allow_dark_holes and empty_cells >= 3:
        return False
    if corner_dark_ok < 18 and not allow_dark_holes:
        return False
    min_valid = 16 if allow_dark_holes else 24
    return valid_orbs >= min_valid


def is_board_truly_monochrome(board_rgb: np.ndarray) -> bool:
    _, ss, vv = rgb_to_hsv_np(board_rgb)
    cell_sats = []
    grad_ok = 0
    for r in range(5):
        for c in range(6):
            ps = ss[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64][_ORB_CIRC]
            pv = vv[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64][_ORB_CIRC]
            cell_sats.append(float(ps.mean()))
            lum = board_rgb[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64].astype(np.float32).mean(axis=-1)
            if (np.abs(np.diff(lum, axis=0)).mean() + np.abs(np.diff(lum, axis=1)).mean()) >= 9.0 and pv.max() >= 0.42:
                grad_ok += 1
    return float(np.percentile(cell_sats, 80)) < 0.11 and grad_ok >= 25


def detect_encyclopedia_board(rgb: np.ndarray) -> BoardBox | None:
    """Detects the 6x5 demonstration board inside official 920x1280 / 910x1175 encyclopedia slides."""
    h, w, _ = rgb.shape
    y0, y1 = int(h * 0.10), int(h * 0.30)
    hh, ss, vv = rgb_to_hsv_np(rgb[y0:y1])
    pink = ((hh >= 330.0) | (hh <= 22.0)) & (ss >= 0.25) & (vv >= 0.55)
    row_cov = pink.mean(axis=1)
    if float(row_cov.max()) < 0.35:
        return None
    peak_i = int(np.argmax(row_cov))
    bot_i = peak_i
    while bot_i + 1 < len(row_cov) and row_cov[bot_i + 1] >= 0.20:
        bot_i += 1
    hp_bot = y0 + bot_i
    left = int(round(w * 0.0305))
    right = int(round(w * 0.9695))
    bw = right - left
    bh = int(round(bw * 5.0 / 6.0))
    top = hp_bot + int(round(h * 0.003))
    bottom = top + bh
    if bottom > h - 30:
        return None
    return BoardBox(left, top, right, bottom, hp_bot, 1.0, 1.0, 1.0, 1.0)


def _crop_board_from_box(im: Image.Image, box: BoardBox) -> np.ndarray:
    crop = im.crop((box.left, box.top, box.right, box.bottom)).resize((BOARD_W, BOARD_H), Image.Resampling.BILINEAR)
    return np.asarray(crop, dtype=np.uint8)


def _pack_phase4_v1_split(store: BoardSampleStore, npz_path: Path, is_val: bool) -> int:
    z = np.load(npz_path, allow_pickle=False)
    images = z["images"]
    is_rune = z["is_rune"]
    element = z["element"]
    variant = z["variant"]
    race = z["race"]
    race_known = z["race_known"]
    hazards = z["hazards"]
    hmask = z["hazard_mask"]
    overlays = z["tile_overlay"]
    omask = z["overlay_mask"]
    groups = z["groups"]

    by_group: dict[str, list[int]] = defaultdict(list)
    neg_tiles: list[np.ndarray] = []
    # Exclude 2 corrupted phase4_v1 race groups: `p4_demon_race_v2` (cropped boss floor/portraits) & `vb_menglie` (black sidebars + mislabeled race)
    skip_v1_groups = {"p4_demon_race_v2", "vb_menglie"}
    for i, g in enumerate(groups.tolist()):
        gs = str(g)
        if gs in skip_v1_groups:
            continue
        if float(is_rune[i]) < 0.5:
            neg_tiles.append(images[i])
        else:
            by_group[gs].append(i)

    added = 0
    # Promote selected multi-board v1 groups to val so THORN, PUZZLE, SHATTERED all have real val support
    # (`q30_dark_teal` is already in val for QUESTION; keep `u2_lizhi_wq30` in train so Weathered `?` teaches BLACK_WHITE=0!)
    promote_v1_val = {"thorn_img1", "p4_puzzle_L", "vb_shattered"}
    machina_idx = RACE_TO_IDX["MACHINA"]
    for grp, idxs in by_group.items():
        grp_val = is_val or (grp in promote_v1_val)
        is_unannotated_yt = grp.startswith("ytdiv_")
        for start in range(0, len(idxs), NUM_CELLS):
            chunk = idxs[start:start + NUM_CELLS]
            board_img = np.zeros((BOARD_H, BOARD_W, 3), dtype=np.uint8)
            tgt = np.zeros((NUM_CELLS, OUTPUT_DIM), dtype=np.float32)
            rm_is_rune = np.zeros((NUM_CELLS,), dtype=np.float32)
            rm_elem = np.zeros((NUM_CELLS,), dtype=np.float32)
            rm_var = np.zeros((NUM_CELLS,), dtype=np.float32)
            rm_race = np.zeros((NUM_CELLS,), dtype=np.float32)
            rm_haz = np.zeros((NUM_CELLS, NUM_HAZARDS), dtype=np.float32)
            rm_ov = np.zeros((NUM_CELLS, NUM_TILE_OVERLAYS), dtype=np.float32)

            for ci, sidx in enumerate(chunk):
                r, c = divmod(ci, 6)
                tile_im = images[sidx]
                board_img[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64] = tile_im
                ir = float(is_rune[sidx])
                tgt[ci, SLICE_IS_RUNE[0]] = ir
                rm_is_rune[ci] = 1.0
                if ir > 0.5:
                    el = int(element[sidx])
                    if 0 <= el < NUM_ELEMENTS:
                        tgt[ci, SLICE_ELEMENT[0] + el] = 1.0
                        rm_elem[ci] = 1.0
                    va = int(variant[sidx])
                    if 0 <= va < NUM_VARIANTS:
                        tgt[ci, SLICE_VARIANT[0] + va] = 1.0
                        rm_var[ci] = 1.0
                    if bool(race_known[sidx]) and 0 <= int(race[sidx]) < NUM_RACES:
                        r_val = machina_idx if grp == "vb_race_demon" else int(race[sidx])
                        # Prevent unannotated `ytdiv_*` race badges from poisoning `RACE = NONE`!
                        if is_unannotated_yt and r_val == 0:
                            _, conf_no_badge = detect_race_medallion_cell64(tile_im)
                            if conf_no_badge:
                                tgt[ci, SLICE_RACE[0]] = 1.0
                                rm_race[ci] = 1.0
                        else:
                            tgt[ci, SLICE_RACE[0] + r_val] = 1.0
                            rm_race[ci] = 1.0
                    tgt[ci, SLICE_HAZARDS[0]:SLICE_HAZARDS[1]] = hazards[sidx]
                    rm_haz[ci] = hmask[sidx]
                    tgt[ci, SLICE_TILE_OVERLAY[0]:SLICE_TILE_OVERLAY[1]] = overlays[sidx]
                    rm_ov[ci] = omask[sidx]

            for ci in range(len(chunk), NUM_CELLS):
                r, c = divmod(ci, 6)
                src_i = chunk[ci % len(chunk)]
                board_img[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64] = images[src_i]

            has_special = bool((hazards[chunk].sum() > 0) or (overlays[chunk].sum() > 0) or (race[chunk] > 0).any())
            store.add_board(
                board_img,
                None,
                tgt,
                rm_is_rune,
                rm_elem,
                rm_var,
                rm_race,
                rm_haz,
                rm_ov,
                weight=3.8 if has_special else 2.8,
                group=f"v1/{grp}",
                source="phase4_v1",
                val=grp_val,
            )
            added += 1

    # Pack UI negative tiles cleanly into dedicated 30-cell `is_rune = 0` boards
    for nstart in range(0, len(neg_tiles), NUM_CELLS):
        nchunk = neg_tiles[nstart:nstart + NUM_CELLS]
        neg_board = np.zeros((BOARD_H, BOARD_W, 3), dtype=np.uint8)
        for ci in range(NUM_CELLS):
            r, c = divmod(ci, 6)
            neg_board[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64] = nchunk[ci % len(nchunk)]
        z1 = np.zeros((NUM_CELLS,), dtype=np.float32)
        store.add_board(
            neg_board,
            None,
            np.zeros((NUM_CELLS, OUTPUT_DIM), dtype=np.float32),
            np.ones((NUM_CELLS,), dtype=np.float32),
            z1,
            z1,
            z1,
            np.zeros((NUM_CELLS, NUM_HAZARDS), dtype=np.float32),
            np.zeros((NUM_CELLS, NUM_TILE_OVERLAYS), dtype=np.float32),
            weight=2.0,
            group=f"v1/neg_pack_{nstart // NUM_CELLS}",
            source="phase4_v1_neg",
            val=is_val,
        )
        added += 1
    return added


def _add_explicit_monochrome_board(
    store: BoardSampleStore,
    board_a: np.ndarray,
    board_b: np.ndarray | None,
    grid_lines: list[str],
    group: str,
    source: str,
    is_val: bool,
    weight: float = 5.0,
    enchanted_cells: set[tuple[int, int]] = frozenset(),
    weakened_cells: set[tuple[int, int]] = frozenset(),
    sprite_collector: list[tuple[int, int, np.ndarray]] | None = None,
) -> None:
    gt = parse_grid(grid_lines)
    tgt = np.zeros((NUM_CELLS, OUTPUT_DIM), dtype=np.float32)
    rm_is_rune = np.ones((NUM_CELLS,), dtype=np.float32)
    rm_elem = np.ones((NUM_CELLS,), dtype=np.float32)
    rm_var = np.ones((NUM_CELLS,), dtype=np.float32)
    rm_race = np.ones((NUM_CELLS,), dtype=np.float32)
    rm_haz = np.ones((NUM_CELLS, NUM_HAZARDS), dtype=np.float32)
    rm_ov = np.ones((NUM_CELLS, NUM_TILE_OVERLAYS), dtype=np.float32)
    rm_ov[:, TILE_OVERLAY_TO_IDX["PUZZLE"]] = 0.0

    bw_idx = HAZARD_TO_IDX["BLACK_WHITE"]
    for r in range(5):
        for c in range(6):
            ci = r * 6 + c
            el_name = gt[(r, c)]
            el_idx = ELEMENT_TO_IDX[el_name]
            tgt[ci, SLICE_IS_RUNE[0]] = 1.0
            tgt[ci, SLICE_ELEMENT[0] + el_idx] = 1.0
            if (r, c) in enchanted_cells:
                va_idx = VARIANT_TO_IDX["ENCHANTED"]
            elif (r, c) in weakened_cells:
                va_idx = VARIANT_TO_IDX["WEAKENED"]
            else:
                va_idx = VARIANT_TO_IDX["NORMAL"]
            tgt[ci, SLICE_VARIANT[0] + va_idx] = 1.0
            tgt[ci, SLICE_RACE[0] + RACE_TO_IDX["NONE"]] = 1.0
            tgt[ci, SLICE_HAZARDS[0] + bw_idx] = 1.0
            if sprite_collector is not None and not is_val:
                patch = board_a[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64].copy()
                sprite_collector.append((el_idx, va_idx, patch))

    store.add_board(
        board_a,
        board_b,
        tgt,
        rm_is_rune,
        rm_elem,
        rm_var,
        rm_race,
        rm_haz,
        rm_ov,
        weight=weight,
        group=group,
        source=source,
        val=is_val,
    )


def _detect_scoped_cell_cues(board_rgb: np.ndarray, mode: str) -> tuple[np.ndarray, np.ndarray]:
    """Calibrated high-precision detectors strictly scoped to verified category folders."""
    pos = np.zeros((NUM_CELLS,), dtype=bool)
    neg = np.zeros((NUM_CELLS,), dtype=bool)

    for r in range(5):
        for c in range(6):
            idx = r * 6 + c
            patch = board_rgb[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64]
            inner = patch[6:58, 6:58]
            hh, ss, vv = rgb_to_hsv_np(inner)
            if mode == "ICE_SEALED":
                purp = float((((hh >= 245.0) & (hh <= 325.0)) & (ss >= 0.18) & (vv >= 0.15) & (vv <= 0.78)).mean())
                spec = float(((ss <= 0.34) & (vv >= 0.44)).mean())
                lum = inner.mean(axis=-1)
                edge = float(np.abs(np.diff(lum, axis=0)).mean() + np.abs(np.diff(lum, axis=1)).mean())
                if purp >= 0.34 and (spec >= 0.10 or edge >= 12.0):
                    pos[idx] = True
                elif purp < 0.10 and float((ss >= 0.45).mean()) >= 0.45:
                    neg[idx] = True
            elif mode == "PURGATORY_FIRE":
                core = patch[16:48, 16:48]
                _, cs, cv = rgb_to_hsv_np(core)
                white_core = float(((cs <= 0.32) & (cv >= 0.74)).mean())
                mag_ring = float((((hh >= 275.0) & (hh <= 345.0)) & (ss >= 0.25) & (vv >= 0.35)).mean())
                if white_core >= 0.14 and mag_ring >= 0.22:
                    pos[idx] = True
                elif mag_ring < 0.08 and white_core < 0.10 and float((ss >= 0.42).mean()) >= 0.45:
                    neg[idx] = True
            elif mode == "TRAFFIC_LIGHT_MARKER":
                c_green = float((((hh >= 95.0) & (hh <= 165.0)) & (ss >= 0.60) & (vv >= 0.70)).mean())
                c_red = float((((hh <= 12.0) | (hh >= 346.0)) & (ss >= 0.65) & (vv >= 0.72)).mean())
                if c_green >= 0.35 or c_red >= 0.40:
                    pos[idx] = True
                else:
                    neg[idx] = True
            elif mode == "BOMB":
                sub = patch[14:50, 14:50]
                _, bs, bv = rgb_to_hsv_np(sub)
                grey = float(((bs <= 0.25) & (bv >= 0.22) & (bv <= 0.76)).mean())
                if grey >= 0.42:
                    pos[idx] = True
                elif grey < 0.12 and float((ss >= 0.45).mean()) >= 0.45:
                    neg[idx] = True
            elif mode == "BLOOD_DRAIN_LOCKED":
                grey_lock = float(((ss <= 0.28) & (vv >= 0.20) & (vv <= 0.60)).mean())
                if grey_lock >= 0.45:
                    pos[idx] = True
                elif grey_lock < 0.14 and float((ss >= 0.45).mean()) >= 0.45:
                    neg[idx] = True
            elif mode == "STORM":
                grey_mist = float(((ss <= 0.26) & (vv >= 0.35) & (vv <= 0.78)).mean())
                sat_orb = float((ss >= 0.48).mean())
                if grey_mist >= 0.68 and sat_orb <= 0.08:
                    pos[idx] = True
                elif sat_orb >= 0.45:
                    neg[idx] = True
            elif mode == "SHATTERED":
                dark_void = float((vv <= 0.20).mean())
                sat_orb = float((ss >= 0.42).mean())
                lum = inner.astype(np.float32).mean(axis=-1)
                cracks = float(((ss <= 0.22) & (vv >= 0.28) & (vv <= 0.75)).mean())
                if dark_void >= 0.48 and sat_orb <= 0.12 and cracks >= 0.04:
                    pos[idx] = True
                elif sat_orb >= 0.45:
                    neg[idx] = True
            elif mode == "PUZZLE":
                # Golden metallic border around 64x64 cell edges [0..6, 58..64]
                b_mask = np.zeros((64, 64), dtype=bool)
                b_mask[:6, :] = True
                b_mask[58:, :] = True
                b_mask[:, :6] = True
                b_mask[:, 58:] = True
                bh, bs, bv = rgb_to_hsv_np(patch)
                gold = float((((bh[b_mask] >= 32.0) & (bh[b_mask] <= 66.0)) & (bs[b_mask] >= 0.38) & (bv[b_mask] >= 0.48)).mean())
                if gold >= 0.26:
                    pos[idx] = True
                elif gold <= 0.05:
                    neg[idx] = True
    return pos, neg


def _label_track_b_board(
    store: BoardSampleStore,
    img_a: np.ndarray,
    img_b: np.ndarray | None,
    group: str,
    source: str,
    is_val: bool,
    weight: float,
    race_label: str | None = None,
    scoped_cue: str | None = None,
    elem_conf_min: float = 0.68,
    mark_clean_negatives: bool = False,
) -> None:
    tgt = np.zeros((NUM_CELLS, OUTPUT_DIM), dtype=np.float32)
    rm_is_rune = np.ones((NUM_CELLS,), dtype=np.float32)
    rm_elem = np.zeros((NUM_CELLS,), dtype=np.float32)
    rm_var = np.zeros((NUM_CELLS,), dtype=np.float32)
    rm_race = np.zeros((NUM_CELLS,), dtype=np.float32)
    rm_haz = np.zeros((NUM_CELLS, NUM_HAZARDS), dtype=np.float32)
    rm_ov = np.zeros((NUM_CELLS, NUM_TILE_OVERLAYS), dtype=np.float32)

    bw_idx = HAZARD_TO_IDX["BLACK_WHITE"]
    # Every verified colored board is explicitly NOT monochrome (`BLACK_WHITE = 0`)
    rm_haz[:, bw_idx] = 1.0

    cue_pos = np.zeros((NUM_CELLS,), dtype=bool)
    cue_neg = np.zeros((NUM_CELLS,), dtype=bool)
    if scoped_cue:
        cue_pos, cue_neg = _detect_scoped_cell_cues(img_a, scoped_cue)

    any_has_race = False
    for r in range(5):
        for c in range(6):
            ci = r * 6 + c
            tgt[ci, SLICE_IS_RUNE[0]] = 1.0
            cx, cy = (c + 0.5) * CELL_PX, (r + 0.5) * CELL_PX
            cell64 = img_a[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64]
            el_name, el_conf, star_cnt, _ = hsv_base_classify_cell(img_a, cx, cy, CELL_PX, CELL_PX)
            has_medallion, conf_no_medallion = detect_race_medallion_cell64(cell64)

            if scoped_cue and cue_pos[ci]:
                if scoped_cue in HAZARD_TO_IDX:
                    h_i = HAZARD_TO_IDX[scoped_cue]
                    tgt[ci, SLICE_HAZARDS[0] + h_i] = 1.0
                    rm_haz[ci, h_i] = 1.0
                elif scoped_cue in TILE_OVERLAY_TO_IDX:
                    o_i = TILE_OVERLAY_TO_IDX[scoped_cue]
                    tgt[ci, SLICE_TILE_OVERLAY[0] + o_i] = 1.0
                    rm_ov[ci, o_i] = 1.0
                    if scoped_cue == "SHATTERED":
                        tgt[ci, SLICE_ELEMENT[0] + ELEMENT_TO_IDX["EMPTY"]] = 1.0
                        rm_elem[ci] = 1.0
                        tgt[ci, SLICE_RACE[0] + RACE_TO_IDX["NONE"]] = 1.0
                        rm_race[ci] = 1.0
                        continue
                if scoped_cue not in ("PUZZLE", "TRAFFIC_LIGHT_MARKER"):
                    continue
            elif scoped_cue and cue_neg[ci]:
                if scoped_cue in HAZARD_TO_IDX:
                    rm_haz[ci, HAZARD_TO_IDX[scoped_cue]] = 1.0
                elif scoped_cue in TILE_OVERLAY_TO_IDX:
                    rm_ov[ci, TILE_OVERLAY_TO_IDX[scoped_cue]] = 1.0

            if el_name in ELEMENT_TO_IDX and el_conf >= elem_conf_min:
                tgt[ci, SLICE_ELEMENT[0] + ELEMENT_TO_IDX[el_name]] = 1.0
                rm_elem[ci] = 1.0
                if mark_clean_negatives and el_conf >= 0.76:
                    for hi in _CLEAN_BOARD_NEG_HAZARDS:
                        rm_haz[ci, hi] = 1.0
                    for oi in _CLEAN_BOARD_NEG_OVERLAYS:
                        rm_ov[ci, oi] = 1.0

            if race_label:
                if has_medallion:
                    any_has_race = True
                    tgt[ci, SLICE_RACE[0] + RACE_TO_IDX[race_label]] = 1.0
                    rm_race[ci] = 1.0
                    tgt[ci, SLICE_VARIANT[0] + VARIANT_TO_IDX["RACE"]] = 1.0
                    rm_var[ci] = 1.0
                elif conf_no_medallion and el_conf >= 0.74:
                    tgt[ci, SLICE_RACE[0] + RACE_TO_IDX["NONE"]] = 1.0
                    rm_race[ci] = 1.0
            elif mark_clean_negatives and conf_no_medallion and el_conf >= 0.76:
                tgt[ci, SLICE_RACE[0] + RACE_TO_IDX["NONE"]] = 1.0
                rm_race[ci] = 1.0
                va_name = "ENCHANTED" if star_cnt >= 7 else "NORMAL"
                tgt[ci, SLICE_VARIANT[0] + VARIANT_TO_IDX[va_name]] = 1.0
                rm_var[ci] = 1.0

    effective_weight = max(weight, 3.4) if (race_label and any_has_race) else weight
    store.add_board(
        img_a,
        img_b,
        tgt,
        rm_is_rune,
        rm_elem,
        rm_var,
        rm_race,
        rm_haz,
        rm_ov,
        weight=effective_weight,
        group=group,
        source=source,
        val=is_val,
    )


def _add_encyclopedia_status_composites(
    store: BoardSampleStore,
    enc_dir: Path,
    clean_train_boards: list[np.ndarray],
    clean_val_boards: list[np.ndarray],
    rng: random.Random,
) -> tuple[int, int]:
    """
    Synthesizes realistic special-hazard boards using the official `runestone_statuses_clean`
    140x140 orb sprites pasted with feathered circular alpha onto clean battle boards so rare
    hazards (PETRIFIED, COAGULATED, FOAM, LUMINOUS, MAGIC_ORB, BLOOD_THORN, WEATHERED, ICE_SEALED,
    BOMB, EVIL_ENERGY, BLOOD_DRAIN_LOCKED) have clean positive & negative cells in Train & Val.
    Note: FROZEN is deliberately omitted from sprite synthesis to preserve zero Water-orb false positives.
    """
    status_map: list[tuple[str, str, str | None]] = [
        ("status_01/orb_0.png", "ELECTRIFIED", None),
        ("status_02/orb_0.png", "WEATHERED", None),
        ("status_03/orb_1.png", "ICE_SEALED", None),
        ("status_04/orb_0.png", "PETRIFIED", "UNKNOWN"),
        ("status_05/orb_0.png", "COAGULATED", None),
        ("status_08/orb_0.png", "BLOOD_DRAIN_LOCKED", None),
        ("status_09/orb_0.png", "BOMB", None),
        ("status_11/orb_0.png", "LUMINOUS", None),
        ("status_12/orb_0.png", "EVIL_ENERGY", None),
        ("status_13/orb_0.png", "FOAM", None),
        ("status_17/orb_0.png", "MAGIC_ORB", None),
        ("status_20/orb_0.png", "BLOOD_THORN", None),
    ]
    # Feathered circular alpha mask (64x64) blending orb interior smoothly into real checkerboard corners
    dist = np.sqrt((_XX64 - 31.5) ** 2 + (_YY64 - 31.5) ** 2)
    alpha = np.clip((29.5 - dist) / 3.5, 0.0, 1.0)[..., None].astype(np.float32)

    loaded_sprites: list[tuple[str, np.ndarray, str | None]] = []
    rs_root = enc_dir / "runestone_statuses_clean"
    for rel_p, haz_name, force_el in status_map:
        sp = rs_root / rel_p
        if sp.is_file():
            im = Image.open(sp).convert("RGB").resize((64, 64), Image.Resampling.BILINEAR)
            loaded_sprites.append((haz_name, np.asarray(im, dtype=np.float32), force_el))

    if not loaded_sprites or not clean_train_boards or not clean_val_boards:
        return 0, 0

    tr_added = va_added = 0
    for is_val, pool, n_boards in ((False, clean_train_boards, 72), (True, clean_val_boards, 24)):
        for bi in range(n_boards):
            base = pool[rng.randrange(len(pool))].copy()
            tgt = np.zeros((NUM_CELLS, OUTPUT_DIM), dtype=np.float32)
            rm_is_rune = np.ones((NUM_CELLS,), dtype=np.float32)
            rm_elem = np.zeros((NUM_CELLS,), dtype=np.float32)
            rm_var = np.zeros((NUM_CELLS,), dtype=np.float32)
            rm_race = np.zeros((NUM_CELLS,), dtype=np.float32)
            rm_haz = np.ones((NUM_CELLS, NUM_HAZARDS), dtype=np.float32)
            rm_ov = np.ones((NUM_CELLS, NUM_TILE_OVERLAYS), dtype=np.float32)
            rm_haz[:, HAZARD_TO_IDX["FROZEN"]] = 0.0
            rm_ov[:, TILE_OVERLAY_TO_IDX["PUZZLE"]] = 0.0

            # Classify base board cells first
            for r in range(5):
                for c in range(6):
                    ci = r * 6 + c
                    tgt[ci, SLICE_IS_RUNE[0]] = 1.0
                    el_name, el_conf, _, has_r = hsv_base_classify_cell(
                        base, (c + 0.5) * 64, (r + 0.5) * 64, 64, 64
                    )
                    if el_name in ELEMENT_TO_IDX and el_conf >= 0.72:
                        tgt[ci, SLICE_ELEMENT[0] + ELEMENT_TO_IDX[el_name]] = 1.0
                        rm_elem[ci] = 1.0
                    if not has_r:
                        tgt[ci, SLICE_RACE[0] + RACE_TO_IDX["NONE"]] = 1.0
                        rm_race[ci] = 1.0

            # Paste 3..7 official status orbs onto random distinct cells
            anchor_haz, anchor_sp, anchor_el = loaded_sprites[bi % len(loaded_sprites)]
            k_cells = rng.randint(3, 7)
            chosen_cells = rng.sample(range(NUM_CELLS), k=k_cells)
            for j, ci in enumerate(chosen_cells):
                if j == 0 or rng.random() < 0.65:
                    hname, sp_rgb, f_el = anchor_haz, anchor_sp, anchor_el
                else:
                    hname, sp_rgb, f_el = rng.choice(loaded_sprites)
                r, c = divmod(ci, 6)
                gain = rng.uniform(0.86, 1.12)
                sp_aug = np.clip(sp_rgb * gain, 0.0, 255.0)
                dst_patch = base[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64].astype(np.float32)
                blended = dst_patch * (1.0 - alpha) + sp_aug * alpha
                base[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64] = np.clip(blended, 0, 255).astype(np.uint8)

                tgt[ci, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]] = 0.0
                if f_el is not None:
                    tgt[ci, SLICE_ELEMENT[0] + ELEMENT_TO_IDX[f_el]] = 1.0
                    rm_elem[ci] = 1.0
                else:
                    rm_elem[ci] = 0.0
                rm_var[ci] = 0.0
                rm_race[ci] = 0.0
                tgt[ci, SLICE_HAZARDS[0] + HAZARD_TO_IDX[hname]] = 1.0

            store.add_board(
                base,
                None,
                tgt,
                rm_is_rune,
                rm_elem,
                rm_var,
                rm_race,
                rm_haz,
                rm_ov,
                weight=2.6,
                group=f"enc_status_comp/{'val' if is_val else 'trn'}_{bi}",
                source=f"enc_status_comp_{bi}",
                val=is_val,
            )
            if is_val:
                va_added += 1
            else:
                tr_added += 1
    return tr_added, va_added


def _add_unused_user_media_boards(store: BoardSampleStore, media_dir: Path) -> tuple[int, int]:
    """Adds verified curated boards from `backups/user_training_media/` not yet in `phase4_v1`."""
    tr_cnt = va_cnt = 0

    def add_full_frame(
        p: Path,
        grp: str,
        is_v: bool,
        *,
        race_lbl: str | None = None,
        scoped: str | None = None,
        haz_cells: dict[str, list[tuple[int, int]]] | None = None,
        ov_cells: dict[str, list[tuple[int, int]]] | None = None,
        var_cells: dict[str, list[tuple[int, int]]] | None = None,
        custom_box: BoardBox | None = None,
    ) -> None:
        nonlocal tr_cnt, va_cnt
        if not p.is_file():
            return
        im = Image.open(p).convert("RGB")
        rgb = np.asarray(im)
        b = custom_box or detect_tos_board(rgb)
        if b is None:
            return
        board_u8 = _crop_board_from_box(im, b)
        tgt = np.zeros((NUM_CELLS, OUTPUT_DIM), dtype=np.float32)
        rm_is_rune = np.ones((NUM_CELLS,), dtype=np.float32)
        rm_elem = np.zeros((NUM_CELLS,), dtype=np.float32)
        rm_var = np.zeros((NUM_CELLS,), dtype=np.float32)
        rm_race = np.zeros((NUM_CELLS,), dtype=np.float32)
        rm_haz = np.ones((NUM_CELLS, NUM_HAZARDS), dtype=np.float32)
        rm_ov = np.ones((NUM_CELLS, NUM_TILE_OVERLAYS), dtype=np.float32)
        rm_haz[:, HAZARD_TO_IDX["FROZEN"]] = 0.0
        rm_ov[:, TILE_OVERLAY_TO_IDX["PUZZLE"]] = 0.0

        h_map: dict[tuple[int, int], set[str]] = defaultdict(set)
        for hn, coords in (haz_cells or {}).items():
            for rc in coords:
                h_map[rc].add(hn)
        o_map: dict[tuple[int, int], set[str]] = defaultdict(set)
        for on, coords in (ov_cells or {}).items():
            for rc in coords:
                o_map[rc].add(on)
        v_map: dict[tuple[int, int], str] = {}
        for vn, coords in (var_cells or {}).items():
            for rc in coords:
                v_map[rc] = vn

        cue_pos, _ = _detect_scoped_cell_cues(board_u8, scoped) if scoped else (np.zeros(NUM_CELLS, bool), np.zeros(NUM_CELLS, bool))

        for r in range(5):
            for c in range(6):
                ci = r * 6 + c
                rc = (r, c)
                tgt[ci, SLICE_IS_RUNE[0]] = 1.0
                el_name, el_conf, star_cnt, has_race = hsv_base_classify_cell(
                    board_u8, (c + 0.5) * 64, (r + 0.5) * 64, 64, 64
                )
                for hn in h_map.get(rc, set()):
                    tgt[ci, SLICE_HAZARDS[0] + HAZARD_TO_IDX[hn]] = 1.0
                for on in o_map.get(rc, set()):
                    tgt[ci, SLICE_TILE_OVERLAY[0] + TILE_OVERLAY_TO_IDX[on]] = 1.0
                if scoped and cue_pos[ci]:
                    if scoped in HAZARD_TO_IDX:
                        tgt[ci, SLICE_HAZARDS[0] + HAZARD_TO_IDX[scoped]] = 1.0
                    elif scoped in TILE_OVERLAY_TO_IDX:
                        tgt[ci, SLICE_TILE_OVERLAY[0] + TILE_OVERLAY_TO_IDX[scoped]] = 1.0

                if not h_map.get(rc) and not (scoped and cue_pos[ci]) and el_name in ELEMENT_TO_IDX and el_conf >= 0.70:
                    tgt[ci, SLICE_ELEMENT[0] + ELEMENT_TO_IDX[el_name]] = 1.0
                    rm_elem[ci] = 1.0

                if rc in v_map:
                    tgt[ci, SLICE_VARIANT[0] + VARIANT_TO_IDX[v_map[rc]]] = 1.0
                    rm_var[ci] = 1.0
                elif not h_map.get(rc) and el_conf >= 0.75:
                    va_n = "ENCHANTED" if star_cnt >= 7 else ("RACE" if (race_lbl and has_race) else "NORMAL")
                    tgt[ci, SLICE_VARIANT[0] + VARIANT_TO_IDX[va_n]] = 1.0
                    rm_var[ci] = 1.0

                if race_lbl and has_race:
                    tgt[ci, SLICE_RACE[0] + RACE_TO_IDX[race_lbl]] = 1.0
                    rm_race[ci] = 1.0
                elif not has_race and el_conf >= 0.72:
                    tgt[ci, SLICE_RACE[0] + RACE_TO_IDX["NONE"]] = 1.0
                    rm_race[ci] = 1.0

        store.add_board(
            board_u8, None, tgt, rm_is_rune, rm_elem, rm_var, rm_race, rm_haz, rm_ov,
            weight=3.0, group=grp, source=f"utm/{p.name}", val=is_v,
        )
        if is_v:
            va_cnt += 1
        else:
            tr_cnt += 1

    # 1. Blood-Drain gameplay frames (`20261122_blood_drain/frames/*.png`)
    bd_frames = sorted((media_dir / "20261122_blood_drain/frames").glob("*.png"))
    for i, fp in enumerate(bd_frames):
        add_full_frame(fp, f"utm_blood_drain_{i // 4}", is_v=(i >= len(bd_frames) - 2), scoped="BLOOD_DRAIN_LOCKED")

    # 2. Barrier frames (`20261122_barrier_burning_victory/barrier_frames/*.png`)
    bar_frames = sorted((media_dir / "20261122_barrier_burning_victory/barrier_frames").glob("*.png"))
    for i, fp in enumerate(bar_frames):
        add_full_frame(fp, f"utm_barrier_{i // 3}", is_v=(i == len(bar_frames) - 1))

    # 3. Verified Batch & User Batch 2/3 additional clean frames
    vb = media_dir / "20261125_yt_verified_batch"
    add_full_frame(vb / "C08_magic_orb/01_wanwu_magic_water_wood_dim.png", "utm_vb_magic_dim", False,
                   haz_cells={"MAGIC_ORB": [(0, 1), (0, 2), (0, 4)]})
    for i, fn in enumerate(("01_zuzhou_fade_mark_dark_4orbs.png", "02_zuzhou_purify_mark_fire_7orbs.png", "03_zuzhou_void_mark_heart_7orbs.png")):
        add_full_frame(vb / "C10_marks_fade_purify_void" / fn, f"utm_vb_marks_{i}", is_v=(i == 2))

    u2 = media_dir / "20261125_yt_user_batch2"
    add_full_frame(u2 / "C9_blood_spine_runestones/baolie_f020_t0133s_bloodspine_fire_burning_alt.png", "utm_u2_bs20", False,
                   haz_cells={"BLOOD_THORN": [(0, 2), (0, 4), (1, 1), (1, 3), (1, 4), (1, 5), (2, 1), (2, 3), (2, 4), (2, 5), (3, 4)]},
                   ov_cells={"SCORCHING_TILE": [(r, c) for r in range(5) for c in (1, 4)]})
    add_full_frame(u2 / "C10_extinguish_flame_mark/rongyao_f004_t0103s_extinguish_flame_dark_heart.png", "utm_u2_mark_ext", False)
    add_full_frame(u2 / "C10_remove_thunder_mark/wanwu_f052_t0660s_remove_thunder_fire_6orbs.png", "utm_u2_mark_th1", False)
    add_full_frame(u2 / "C10_remove_thunder_mark/wanwu_f054_t0675s_remove_thunder_fire_4orbs.png", "utm_u2_mark_th2", True)

    u3 = media_dir / "20261125_yt_user_batch3"
    p_ring = u3 / "C2_weakened_runestones_all6/yongjue_b1_t0009s_weakened_18orbs_ring.png"
    if p_ring.is_file():
        b_ring = detect_custom_yongjue_b1(np.asarray(Image.open(p_ring).convert("RGB")))
        outer18 = [(r, c) for r in range(5) for c in range(6) if r in (0, 4) or c in (0, 5)]
        add_full_frame(p_ring, "utm_u3_weak_ring", True, race_lbl="HUMAN", var_cells={"WEAKENED": outer18}, custom_box=b_ring)

    add_full_frame(u3 / "C3_coagulated_double_and_single/fangtu_kabo_b3_t0029s_coagulated_spawn_glow.png", "utm_u3_coag_kabo", False,
                   haz_cells={"COAGULATED": [(1, 2), (4, 1), (4, 5)]})
    add_full_frame(u3 / "C3_coagulated_double_and_single/fangtu_kabo_b3_t0031s_coagulated_double_3orbs.png", "utm_u3_coag_kabo", False,
                   haz_cells={"COAGULATED": [(1, 2), (4, 1), (4, 5)]})
    add_full_frame(u3 / "C3_coagulated_double_and_single/xunlian_adv_f021_t0654s_coagulated_double_spark.png", "utm_u3_coag_xl", True,
                   haz_cells={"COAGULATED": [(0, 1), (1, 4), (2, 3)]})

    return tr_cnt, va_cnt


def build_phase4_v2_dataset(
    inbox_dir: Path = Path("backups/phase4_v2_inbox"),
    v1_dir: Path = Path("ml/datasets/phase4_v1"),
    enc_dir: Path = Path("backups/tos_encyclopedia"),
    media_dir: Path = Path("backups/user_training_media"),
    out_dir: Path = Path("ml/datasets/phase4_v2"),
    seed: int = 20261128,
) -> dict[str, Any]:
    rng = random.Random(seed)
    out_dir.mkdir(parents=True, exist_ok=True)
    store = BoardSampleStore()
    mono_sprites: list[tuple[int, int, np.ndarray]] = []
    clean_tr_pool: list[np.ndarray] = []
    clean_va_pool: list[np.ndarray] = []

    # 1. Reconstruct phase4_v1 gold boards preserving EXACT train/val split
    v1_tr = _pack_phase4_v1_split(store, v1_dir / "train.npz", is_val=False)
    v1_va = _pack_phase4_v1_split(store, v1_dir / "val.npz", is_val=True)

    # 2. Add unused curated boards from user_training_media
    utm_tr, utm_va = _add_unused_user_media_boards(store, media_dir)

    # 3. Canonical Official Monochrome Rosetta Board (`skill_048/slide_1.png`) + Real YouTube `yt_moon_wolf2` pairs
    mono_tr = mono_va = 0
    s048_path = enc_dir / "enemy_skills/skill_048/slide_1.png"
    if s048_path.is_file():
        s048_im = Image.open(s048_path).convert("RGB")
        s048_box = detect_encyclopedia_board(np.asarray(s048_im))
        if s048_box is not None:
            s048_board = _crop_board_from_box(s048_im, s048_box)
            _add_explicit_monochrome_board(
                store,
                s048_board,
                None,
                ["W F G L D H"] * 5,
                group="mono/enc_skill048",
                source="tos_encyclopedia/skill_048/slide_1.png",
                is_val=False,
                weight=8.5,
                sprite_collector=mono_sprites,
            )
            mono_tr += 1

    mw2_dir = inbox_dir / "hazards_p0/black_white/yt_moon_wolf2/boards"
    if mw2_dir.is_dir():
        f1 = _load_board_rgb(mw2_dir / "f001_t0018s_board.png")
        f2 = _load_board_rgb(mw2_dir / "f002_t0028s_board.png")
        f3 = _load_board_rgb(mw2_dir / "f003_t0032s_board.png")
        f4 = _load_board_rgb(mw2_dir / "f004_t0033s_board.png")
        f5 = _load_board_rgb(mw2_dir / "f005_t0038s_board.png")
        f6 = _load_board_rgb(mw2_dir / "f006_t0042s_board.png")
        f7 = _load_board_rgb(mw2_dir / "f007_t0052s_board.png")

        if f1 is not None and f2 is not None:
            _add_explicit_monochrome_board(
                store, f1, f2,
                ["D H G G G G", "H G G H D W", "L W G H W D", "L L D G F D", "W F H D G L"],
                group="mono/mw2_pair1", source="yt_moon_wolf2/f001_f002", is_val=False, weight=8.0,
                enchanted_cells={(0, 0), (1, 4), (2, 5), (3, 2), (3, 4), (3, 5), (4, 1), (4, 3)},
                weakened_cells={(0, 2), (0, 3), (0, 4), (0, 5), (1, 1), (1, 2), (2, 2), (3, 3), (4, 4)},
                sprite_collector=mono_sprites,
            )
            mono_tr += 1
        if f4 is not None and f5 is not None:
            _add_explicit_monochrome_board(
                store, f4, f5,
                ["H L L G G G", "L G G L H L", "L W W L W W", "L L H G F W", "W F L L H L"],
                group="mono/mw2_pair2", source="yt_moon_wolf2/f004_f005", is_val=False, weight=8.0,
                weakened_cells={(0, 2), (0, 3), (0, 4), (0, 5), (1, 1), (1, 2), (3, 1), (3, 3)},
                sprite_collector=mono_sprites,
            )
            mono_tr += 1
        if f3 is not None:
            _add_explicit_monochrome_board(
                store, f3, None,
                ["H L L G G G", "L G G L H L", "L W W L W W", "L L H G F W", "W F L L H L"],
                group="mono/mw2_spark", source="yt_moon_wolf2/f003", is_val=False, weight=7.0,
                weakened_cells={(0, 2), (0, 3), (0, 4), (0, 5), (1, 1), (1, 2), (3, 1), (3, 3)},
            )
            mono_tr += 1
        if f6 is not None and f7 is not None:
            _add_explicit_monochrome_board(
                store, f6, f7,
                ["L L L G G G", "L G G L L L", "L W W L W W", "L L L G F W", "W F L L L L"],
                group="mono/mw2_val_pair", source="yt_moon_wolf2/f006_f007", is_val=True, weight=5.0,
                weakened_cells={(0, 2), (0, 3), (0, 4), (0, 5), (1, 1), (1, 2), (3, 1), (3, 3)},
            )
            mono_va += 1

    # 4. Clean Official Encyclopedia 6x5 Slides (`backups/tos_encyclopedia/enemy_skills/skill_*/slide_1.png`)
    enc_tr = enc_va = 0
    enc_scoped_by_idx = {
        1: "PUZZLE",
        29: "TRAFFIC_LIGHT_MARKER",
        30: "TRAFFIC_LIGHT_MARKER",
        35: "SHATTERED",
        36: "STORM",
        59: "PURGATORY_FIRE",
    }
    if (enc_dir / "enemy_skills").is_dir():
        for sdir in sorted((enc_dir / "enemy_skills").iterdir()):
            if not sdir.is_dir() or sdir.name == "skill_048":
                continue
            sidx = int(sdir.name.split("_")[1])
            sp = sdir / "slide_1.png"
            if not sp.is_file():
                continue
            im = Image.open(sp).convert("RGB")
            b = detect_encyclopedia_board(np.asarray(im))
            if b is None:
                continue
            b_u8 = _crop_board_from_box(im, b)
            scoped = enc_scoped_by_idx.get(sidx)
            if not is_clean_static_6x5_board(b_u8, allow_dark_holes=(scoped in ("SHATTERED", "STORM"))):
                continue
            is_v = (sidx % 5 == 0)
            _label_track_b_board(
                store, b_u8, None,
                group=f"enc_slide/{sdir.name}",
                source=f"tos_encyclopedia/{sdir.name}/slide_1.png",
                is_val=is_v,
                weight=2.0,
                scoped_cue=scoped,
                elem_conf_min=0.72,
                mark_clean_negatives=(scoped is None),
            )
            if is_v:
                enc_va += 1
            else:
                enc_tr += 1

    # Helper for deterministic subbatch splits
    def split_subbatches(paths_by_sub: dict[str, list[Path]], min_val_subs: int = 1) -> set[str]:
        subs = sorted(k for k, v in paths_by_sub.items() if v)
        if len(subs) <= 1:
            return set()
        rng.shuffle(subs)
        n_val = max(min_val_subs, round(len(subs) * 0.22))
        n_val = min(n_val, len(subs) - 1)
        return set(subs[:n_val])

    # 5. Track B race_missing (filtered for clean static 6x5 boards)
    race_tr = race_va = 0
    kept_track_b = 0
    rejected_track_b = 0
    for race_sub, race_name in RACE_FOLDER_TO_NAME.items():
        rdir = inbox_dir / "race_missing" / race_sub
        if not rdir.is_dir():
            continue
        by_sub: dict[str, list[Path]] = defaultdict(list)
        for p in sorted(rdir.rglob("boards/*.png")):
            by_sub[p.parents[1].name].append(p)
        for sub_name, paths in sorted(by_sub.items()):
            with_badge: list[tuple[Path, np.ndarray]] = []
            without_badge: list[tuple[Path, np.ndarray]] = []
            for p in paths:
                img = load_refined_6x5_board(p)
                if img is not None and is_clean_static_6x5_board(img):
                    kept_track_b += 1
                    has_any_b = sum(
                        1 for rr in range(5) for cc in range(6)
                        if detect_race_medallion_cell64(img[rr * 64:(rr + 1) * 64, cc * 64:(cc + 1) * 64])[0]
                    ) >= 2
                    (with_badge if has_any_b else without_badge).append((p, img))
                else:
                    rejected_track_b += 1
            for bucket in (with_badge, without_badge):
                val_start = max(1, int(round(len(bucket) * 0.76)))
                if len(bucket) >= 2 and val_start >= len(bucket):
                    val_start = len(bucket) - 1
                for bi, (p, img) in enumerate(bucket):
                    board_val = (len(bucket) >= 2) and (bi >= val_start)
                    _label_track_b_board(
                        store, img, None,
                        group=f"race/{race_sub}/{sub_name}/{'val' if board_val else 'trn'}",
                        source=f"race_missing/{race_sub}/{sub_name}/{p.name}",
                        is_val=board_val,
                        weight=2.4 if race_name == "DRAGON" else 1.9,
                        race_label=race_name,
                        mark_clean_negatives=True,
                    )
                    if board_val:
                        race_va += 1
                        clean_va_pool.append(img)
                    else:
                        race_tr += 1
                        clean_tr_pool.append(img)

    # 6. Track B hazards_p0 (fixing black_white colored boards -> BLACK_WHITE=0, stratified per valid board!)
    haz_tr = haz_va = 0
    for cat_name, scoped in [
        ("black_white", None),
        ("ice_sealed", "ICE_SEALED"),
        ("purgatory_fire", "PURGATORY_FIRE"),
        ("shield_orb", None),
        ("enemy_marks", None),
    ]:
        cdir = inbox_dir / "hazards_p0" / cat_name
        if not cdir.is_dir():
            continue
        valid_cat: list[tuple[str, Path, np.ndarray, bool]] = []
        for p in sorted(cdir.rglob("boards/*.png")):
            sub_n = p.parents[1].name
            if sub_n in ("enc_skill048", "yt_moon_wolf2"):
                continue
            img = _load_board_rgb(p)
            if img is None or is_board_truly_monochrome(img) or not is_clean_static_6x5_board(img):
                rejected_track_b += 1
                continue
            kept_track_b += 1
            has_pos = bool(_detect_scoped_cell_cues(img, scoped)[0].any()) if scoped else False
            valid_cat.append((sub_n, p, img, has_pos))
        pos_list = [x for x in valid_cat if x[3]]
        neg_list = [x for x in valid_cat if not x[3]]
        for bucket in (pos_list, neg_list):
            v_cut = max(1, round(len(bucket) * 0.25)) if len(bucket) >= 2 else 0
            for bi, (sub_name, p, img, _) in enumerate(bucket):
                is_v = bi >= (len(bucket) - v_cut) and v_cut > 0
                _label_track_b_board(
                    store, img, None,
                    group=f"haz_p0/{cat_name}/{sub_name}/{'v' if is_v else 't'}",
                    source=f"hazards_p0/{cat_name}/{sub_name}/{p.name}",
                    is_val=is_v,
                    weight=2.4 if scoped else 1.5,
                    scoped_cue=scoped,
                    elem_conf_min=0.72,
                    mark_clean_negatives=(cat_name == "black_white"),
                )
                if is_v:
                    haz_va += 1
                else:
                    haz_tr += 1

    # 7. Track B overlays_p0
    ov_tr = ov_va = 0
    for cat_name, scoped in [("traffic_light", "TRAFFIC_LIGHT_MARKER"), ("barrier_char", None)]:
        odir = inbox_dir / "overlays_p0" / cat_name
        if not odir.is_dir():
            continue
        by_sub = defaultdict(list)
        for p in sorted(odir.rglob("boards/*.png")):
            by_sub[p.parents[1].name].append(p)
        val_subs = split_subbatches(by_sub)
        for sub_name, paths in by_sub.items():
            is_v = sub_name in val_subs
            for p in paths:
                img = _load_board_rgb(p)
                if img is None or not is_clean_static_6x5_board(img):
                    rejected_track_b += 1
                    continue
                kept_track_b += 1
                _label_track_b_board(
                    store, img, None,
                    group=f"ov_p0/{cat_name}/{sub_name}",
                    source=f"overlays_p0/{cat_name}/{sub_name}/{p.name}",
                    is_val=is_v,
                    weight=1.9,
                    scoped_cue=scoped,
                )
                if is_v:
                    ov_va += 1
                else:
                    ov_tr += 1

    # 8. Track B hazards_p1_multiscene (cleaned & stratified per valid subbatch!)
    p1_tr = p1_va = 0
    p1_dir = inbox_dir / "hazards_p1_multiscene"
    if p1_dir.is_dir():
        by_sub = defaultdict(list)
        for p in sorted(p1_dir.rglob("boards/*.png")):
            sub_n = p.parents[1].name
            if sub_n == "enc_p1_all":
                continue
            by_sub[sub_n].append(p)
        for sub_name, paths in sorted(by_sub.items()):
            scoped = None
            race_lbl = None
            allow_holes = False
            if "sticky_shatter" in sub_name:
                scoped = "SHATTERED"
                race_lbl = "DEMON"
                allow_holes = True
            elif "odin_coag_puzzle" in sub_name:
                scoped = "PUZZLE"
                race_lbl = "HUMAN"
            elif "storm" in sub_name:
                scoped = "STORM"
                race_lbl = "MACHINA"
                allow_holes = True
            elif "blood_thorn_1" in sub_name:
                race_lbl = "DEMON"
            elif "rudeus" in sub_name:
                race_lbl = "HUMAN"

            valid_sub: list[tuple[Path, np.ndarray]] = []
            for p in paths:
                img = _load_board_rgb(p)
                if img is None or is_board_truly_monochrome(img):
                    rejected_track_b += 1
                    continue
                if not is_clean_static_6x5_board(img, allow_dark_holes=allow_holes):
                    rejected_track_b += 1
                    continue
                kept_track_b += 1
                valid_sub.append((p, img))

            v_cut = max(1, round(len(valid_sub) * 0.25)) if len(valid_sub) >= 2 else 0
            for bi, (p, img) in enumerate(valid_sub):
                is_v = bi >= (len(valid_sub) - v_cut) and v_cut > 0
                cur_scoped = "TRAFFIC_LIGHT_MARKER" if ("sticky_shatter" in sub_name and "f013" in p.name) else scoped
                _label_track_b_board(
                    store, img, None,
                    group=f"haz_p1/{sub_name}/{'v' if is_v else 't'}",
                    source=f"hazards_p1/{sub_name}/{p.name}",
                    is_val=is_v,
                    weight=2.0,
                    race_label=race_lbl,
                    scoped_cue=cur_scoped,
                )
                if is_v:
                    p1_va += 1
                else:
                    p1_tr += 1

    # 9. Track B collab_skins (filtered for clean static boards across 8 skins)
    col_tr = col_va = 0
    col_dir = inbox_dir / "collab_skins"
    if col_dir.is_dir():
        for skin_dir in sorted(d for d in col_dir.iterdir() if d.is_dir()):
            valid_skin: list[tuple[Path, np.ndarray]] = []
            for p in sorted(skin_dir.rglob("boards/*.png")):
                img = _load_board_rgb(p)
                if img is not None and is_clean_static_6x5_board(img):
                    valid_skin.append((p, img))
                    kept_track_b += 1
                else:
                    rejected_track_b += 1
            val_cut = max(1, round(len(valid_skin) * 0.22)) if len(valid_skin) >= 2 else 0
            for bi, (p, img) in enumerate(valid_skin):
                is_v = bi < val_cut
                _label_track_b_board(
                    store, img, None,
                    group=f"collab/{skin_dir.name}",
                    source=f"collab/{skin_dir.name}/{p.name}",
                    is_val=is_v,
                    weight=1.7,
                    elem_conf_min=0.72,
                    mark_clean_negatives=True,
                )
                if is_v:
                    col_va += 1
                else:
                    col_tr += 1

    # 10. Track B board_static_pairs (ALL 126 items: 63 board-crops + 63 full-screen pairs cropped!)
    pair_tr = pair_va = 0
    pm = json.loads((inbox_dir / "board_static_pairs" / "manifest.json").read_text(encoding="utf-8"))
    all_pair_items = list(pm["items"])
    rng.shuffle(all_pair_items)
    val_cut = max(12, round(len(all_pair_items) * 0.22))
    for pi, it in enumerate(all_pair_items):
        is_v = pi < val_cut
        pa = inbox_dir / "board_static_pairs" / it["pair_a"]
        pb = inbox_dir / "board_static_pairs" / it["pair_b"]
        if not pa.is_file() or not pb.is_file():
            continue
        if "_board_" in pa.name:
            ia = _load_board_rgb(pa)
            ib = _load_board_rgb(pb)
        else:
            ima = Image.open(pa).convert("RGB")
            imb = Image.open(pb).convert("RGB")
            box = detect_tos_board(np.asarray(ima))
            if box is None:
                continue
            ia = _crop_board_from_box(ima, box)
            ib = _crop_board_from_box(imb, box)
        if ia is None or ib is None or not is_clean_static_6x5_board(ia):
            continue
        _label_track_b_board(
            store, ia, ib,
            group=f"static_pair/{pa.parent.name}_{pi // 3}",
            source=it["pair_a"],
            is_val=is_v,
            weight=2.4,
            elem_conf_min=0.72,
            mark_clean_negatives=True,
        )
        if is_v:
            pair_va += 1
            clean_va_pool.append(ia)
        else:
            pair_tr += 1
            clean_tr_pool.append(ia)

    # 10b. Track B `topup_v090_low_recall` (549 raw -> ~270 periodicity-refined static 6x5 boards across 33 videos!)
    topup_tr = topup_va = 0
    topup_dir = inbox_dir / "topup_v090_low_recall"
    topup_video_meta: dict[str, tuple[str | None, str | None]] = {
        # Verified per-video `(leader_race_label, scoped_cue)` audited against video titles & team leaders:
        "yt_9YK8Mur3sj8": ("BEAST", None),
        "yt_J7zNtCsRy8U": ("BEAST", None),
        "yt_wCy68XsXik4": ("BEAST", None),
        "yt_CeAh8f74yMY": ("BEAST", None),
        "yt_rgZlNTruMTI": ("BEAST", None),
        "yt_apM8_xUQKWA": ("MACHINA", None),
        "yt_GEQ23bfKMCI": ("MACHINA", None),
        "yt_J3NhPUD3qfM": ("MACHINA", None),
        "yt_AbTsoyWb7dg": ("DRAGON", "STORM"),
        "yt_bqSntx55VfU": ("DRAGON", "STORM"),
        "yt_OvhiEpvZruY": ("DRAGON", None),
        "yt_gqv_QDUZ7Co": ("GOD", None),
        "yt_QpbaoWcKSWk": ("GOD", None),
        "yt_1nslJYE6nUk": ("GOD", None),
        "yt_svYn5lHowrk": ("ELF", None),
        "yt_2cT1vdr8Fps": ("ELF", None),
        "yt_b8FekYr7WCg": ("ELF", None),
        "yt_DUjopJA9Re0": ("HUMAN", None),
        "yt_xo6NoRjSdZ4": ("HUMAN", None),
        "yt_Mzf3fEnn65w": ("HUMAN", None),
        "yt_CXhhf2g3HyA": ("HUMAN", "PURGATORY_FIRE"),
        "yt_B_BtnA5G3cg": (None, "PURGATORY_FIRE"),
        "yt_JnpnDR0Te8w": ("HUMAN", "TRAFFIC_LIGHT_MARKER"),
        "yt_YXvo8y8mleo": ("HUMAN", "TRAFFIC_LIGHT_MARKER"),
    }
    if topup_dir.is_dir():
        for bdir in sorted(topup_dir.rglob("boards")):
            sub_dir = bdir.parent
            sub_tag = sub_dir.name
            cat_name = sub_dir.parent.name
            vid_prefix = "_".join(sub_tag.split("_")[:2])
            race_lbl: str | None = None
            scoped_cue: str | None = None
            for k_vid, (r_l, s_c) in topup_video_meta.items():
                if sub_tag.startswith(k_vid):
                    race_lbl, scoped_cue = r_l, s_c
                    break
            allow_holes = ("storm" in cat_name) or ("shatter" in cat_name) or (scoped_cue in ("STORM", "SHATTERED"))

            valid_boards: list[tuple[Path, np.ndarray]] = []
            for bp in sorted(bdir.glob("*.png")):
                img = load_refined_6x5_board(bp)
                if img is None or is_board_truly_monochrome(img):
                    rejected_track_b += 1
                    continue
                if not is_clean_static_6x5_board(img, allow_dark_holes=allow_holes):
                    rejected_track_b += 1
                    continue
                kept_track_b += 1
                valid_boards.append((bp, img))

            if not valid_boards:
                continue
            v_cut = max(1, round(len(valid_boards) * 0.22)) if len(valid_boards) >= 3 else 0
            for bi, (bp, img) in enumerate(valid_boards):
                is_v = (v_cut > 0) and (bi >= len(valid_boards) - v_cut)
                _label_track_b_board(
                    store,
                    img,
                    None,
                    group=f"topup090/{cat_name}/{vid_prefix}/{'v' if is_v else 't'}",
                    source=f"topup_v090/{cat_name}/{sub_tag}/{bp.name}",
                    is_val=is_v,
                    weight=2.8 if race_lbl in ("BEAST", "MACHINA", "GOD", "ELF", "DRAGON") else 2.0,
                    race_label=race_lbl,
                    scoped_cue=scoped_cue,
                    elem_conf_min=0.72,
                    mark_clean_negatives=(scoped_cue is None),
                )
                if is_v:
                    topup_va += 1
                    if scoped_cue is None:
                        clean_va_pool.append(img)
                else:
                    topup_tr += 1
                    if scoped_cue is None:
                        clean_tr_pool.append(img)

    # 11. Official `runestone_statuses_clean` composite boards for rare hazards
    comp_tr, comp_va = _add_encyclopedia_status_composites(store, enc_dir, clean_tr_pool, clean_va_pool, rng)

    # 12. Hard Negatives from A2 live enemy popups (non-board dark screens)
    neg_tr = neg_va = 0
    popup_imgs = sorted((inbox_dir / "enemy_popups_live" / "emu_live_calibration_v1").glob("*.png"))
    for pi, p in enumerate(popup_imgs):
        im = Image.open(p).convert("RGB")
        w, h = im.size
        crop = np.asarray(im.crop((0, int(h * 0.40), w, int(h * 0.90))).resize((BOARD_W, BOARD_H)), dtype=np.uint8)
        tgt = np.zeros((NUM_CELLS, OUTPUT_DIM), dtype=np.float32)
        rm_is_rune = np.ones((NUM_CELLS,), dtype=np.float32)
        z1 = np.zeros((NUM_CELLS,), dtype=np.float32)
        is_v = (pi % 5 == 0)
        store.add_board(
            crop, None, tgt, rm_is_rune, z1, z1, z1,
            np.zeros((NUM_CELLS, NUM_HAZARDS), dtype=np.float32),
            np.zeros((NUM_CELLS, NUM_TILE_OVERLAYS), dtype=np.float32),
            weight=2.0, group=f"neg_a2/{p.stem}", source=p.name, val=is_v,
        )
        if is_v:
            neg_va += 1
        else:
            neg_tr += 1

    val_mask = np.array(store.is_val, dtype=bool)
    tr_mask = ~val_mask

    boards_a = np.stack(store.boards_a, axis=0)
    boards_b = np.stack(store.boards_b, axis=0)
    has_real_pair = np.array(store.has_real_pair, dtype=bool)
    targets = np.stack(store.targets, axis=0)
    rm_is_rune = np.stack(store.is_rune_mask, axis=0)
    rm_elem = np.stack(store.element_mask, axis=0)
    rm_var = np.stack(store.variant_mask, axis=0)
    rm_race = np.stack(store.race_mask, axis=0)
    rm_haz = np.stack(store.hazard_mask, axis=0)
    rm_ov = np.stack(store.overlay_mask, axis=0)
    sw = np.array(store.sample_weights, dtype=np.float32)
    grp_arr = np.array(store.groups)
    src_arr = np.array(store.sources)

    def save_split(m: np.ndarray, fname: Path) -> None:
        np.savez_compressed(
            fname,
            boards_a=boards_a[m],
            boards_b=boards_b[m],
            has_real_pair=has_real_pair[m],
            targets=targets[m],
            is_rune_mask=rm_is_rune[m],
            element_mask=rm_elem[m],
            variant_mask=rm_var[m],
            race_mask=rm_race[m],
            hazard_mask=rm_haz[m],
            overlay_mask=rm_ov[m],
            sample_weights=sw[m],
            groups=grp_arr[m],
            sources=src_arr[m],
        )

    save_split(tr_mask, out_dir / "train_boards.npz")
    save_split(val_mask, out_dir / "val_boards.npz")

    # Save training-only monochrome sprite bank (`[K, 64, 64, 3]` + `el_idx`, `va_idx`)
    if mono_sprites:
        np.savez_compressed(
            out_dir / "mono_sprite_bank.npz",
            el_idx=np.array([x[0] for x in mono_sprites], dtype=np.int64),
            va_idx=np.array([x[1] for x in mono_sprites], dtype=np.int64),
            patches=np.stack([x[2] for x in mono_sprites], axis=0),
        )

    # Visual QA sheet
    qa_picks = [i for i in range(len(boards_a)) if i % max(1, len(boards_a) // 18) == 0][:18]
    qa = Image.new("RGB", (BOARD_W * 2 + 280, len(qa_picks) * (BOARD_H // 2 + 8) + 8), (18, 20, 26))
    draw = ImageDraw.Draw(qa)
    for row_i, idx in enumerate(qa_picks):
        y = 4 + row_i * (BOARD_H // 2 + 8)
        qa.paste(Image.fromarray(boards_a[idx]).resize((BOARD_W // 2, BOARD_H // 2)), (4, y))
        qa.paste(Image.fromarray(boards_b[idx]).resize((BOARD_W // 2, BOARD_H // 2)), (BOARD_W // 2 + 12, y))
        tag = "VAL" if val_mask[idx] else "TRN"
        draw.text(
            (BOARD_W + 20, y + 44),
            f"[{tag}] {store.groups[idx]}\npair={bool(has_real_pair[idx])} w={sw[idx]:.1f}",
            fill=(160, 240, 180),
        )
    qa.save(out_dir / "qa_board_check.jpg", quality=90)

    summary = {
        "total_boards": int(len(boards_a)),
        "total_cells": int(len(boards_a) * NUM_CELLS),
        "train_boards": int(tr_mask.sum()),
        "val_boards": int(val_mask.sum()),
        "real_dual_frame_pairs": int(has_real_pair.sum()),
        "mono_sprite_bank_count": len(mono_sprites),
        "track_b_quality_gate": {"kept": kept_track_b, "rejected_corrupted": rejected_track_b},
        "breakdown": {
            "phase4_v1": {"train": v1_tr, "val": v1_va},
            "user_media_extra": {"train": utm_tr, "val": utm_va},
            "real_monochrome": {"train": mono_tr, "val": mono_va},
            "encyclopedia_slides": {"train": enc_tr, "val": enc_va},
            "encyclopedia_status_composites": {"train": comp_tr, "val": comp_va},
            "race_missing": {"train": race_tr, "val": race_va},
            "hazards_p0": {"train": haz_tr, "val": haz_va},
            "overlays_p0": {"train": ov_tr, "val": ov_va},
            "hazards_p1_multiscene": {"train": p1_tr, "val": p1_va},
            "collab_skins": {"train": col_tr, "val": col_va},
            "board_static_pairs": {"train": pair_tr, "val": pair_va},
            "topup_v090_low_recall": {"train": topup_tr, "val": topup_va},
            "neg_a2_popups": {"train": neg_tr, "val": neg_va},
        },
        "races_val_cells": {
            RACE_NAMES[i]: int(((targets[val_mask, :, SLICE_RACE[0] + i] > 0.5) & (rm_race[val_mask] > 0.5)).sum())
            for i in range(NUM_RACES)
        },
        "hazards_total_pos": {
            HAZARD_NAMES[i]: int(((targets[:, :, SLICE_HAZARDS[0] + i] > 0.5) & (rm_haz[:, :, i] > 0.5)).sum())
            for i in range(NUM_HAZARDS)
        },
        "hazards_val_pos": {
            HAZARD_NAMES[i]: int(((targets[val_mask, :, SLICE_HAZARDS[0] + i] > 0.5) & (rm_haz[val_mask, :, i] > 0.5)).sum())
            for i in range(NUM_HAZARDS)
        },
        "overlays_total_pos": {
            TILE_OVERLAY_NAMES[i]: int(((targets[:, :, SLICE_TILE_OVERLAY[0] + i] > 0.5) & (rm_ov[:, :, i] > 0.5)).sum())
            for i in range(NUM_TILE_OVERLAYS)
        },
        "overlays_val_pos": {
            TILE_OVERLAY_NAMES[i]: int(((targets[val_mask, :, SLICE_TILE_OVERLAY[0] + i] > 0.5) & (rm_ov[val_mask, :, i] > 0.5)).sum())
            for i in range(NUM_TILE_OVERLAYS)
        },
    }
    (out_dir / "dataset_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main() -> None:
    s = build_phase4_v2_dataset()
    print(
        f"Phase4_v2 ready: total_boards={s['total_boards']} ({s['total_cells']} cells), "
        f"train={s['train_boards']}, val={s['val_boards']}, real_pairs={s['real_dual_frame_pairs']}, "
        f"mono_sprites={s['mono_sprite_bank_count']}, gate={s['track_b_quality_gate']}"
    )


if __name__ == "__main__":
    main()
