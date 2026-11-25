#!/usr/bin/env python3
"""
AutoDora-NEXT Phase 4 Multi-Head CNN Dataset Builder
====================================================

Combines ALL locally archived real Tower of Saviors screenshots (under gitignored
`backups/`) with exact manifest & visual-QA coordinates into a train/val `.npz`
dataset strictly split by board/video group (zero tile leakage!).

Outputs strictly to gitignored `ml/datasets/phase4_v1/`:
  - `train.npz` & `val.npz`
  - `dataset_summary.json`
  - `qa_label_check.jpg` (contact sheet across all classes for visual QA)
"""

from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set, Tuple

import numpy as np
from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))
sys.path.insert(0, str(REPO_ROOT / "ml" / "tools"))

from ml.schema import (
    ELEMENT_NAMES,
    ELEMENT_TO_IDX,
    HAZARD_NAMES,
    HAZARD_TO_IDX,
    INPUT_SIZE,
    NUM_HAZARDS,
    NUM_TILE_OVERLAYS,
    RACE_NAMES,
    RACE_TO_IDX,
    TILE_OVERLAY_NAMES,
    TILE_OVERLAY_TO_IDX,
    VARIANT_TO_IDX,
)
from extract_tos_dataset import BoardBox, detect_tos_board, rgb_to_hsv_np  # noqa: E402


CELL_SYMBOL_TO_EL = {
    "W": "WATER",
    "F": "FIRE",
    "G": "WOOD",
    "L": "LIGHT",
    "D": "DARK",
    "H": "HEART",
    "?": "HIDDEN",
    "U": "UNKNOWN",
    ".": "EMPTY",
}


def parse_grid(rows: List[str]) -> Dict[Tuple[int, int], str]:
    out: Dict[Tuple[int, int], str] = {}
    for r, line in enumerate(rows):
        tokens = line.replace(" ", "")
        for c, ch in enumerate(tokens):
            if ch in CELL_SYMBOL_TO_EL:
                out[(r, c)] = CELL_SYMBOL_TO_EL[ch]
    return out


# ---------------------------------------------------------------------------
# Lightweight Python mirrors of CvBoardRecognizer helpers (for base-element
# pseudo-labeling unobscured cells + Race/Enchanted/Evil Energy assists)
# ---------------------------------------------------------------------------

def hsv_base_classify_cell(rgb: np.ndarray, cx: float, cy: float, cw: float, ch: float) -> Tuple[str, float, int, bool]:
    """
    Returns (element_name, confidence, star_count, has_race_badge).
    Excludes top-right quadrant for element & star checks just like CvBoardRecognizer.kt.
    """
    h_img, w_img, _ = rgb.shape
    radius = min(cw, ch) * 0.5
    votes = np.zeros(6, dtype=np.float32)
    total_w = 0.0
    low_sat = 0
    total_cnt = 0
    max_star = 0

    for ring_idx, (rr_mul, angles) in enumerate(((0.18, 8), (0.36, 16), (0.62, 16))):
        ring_r = radius * rr_mul
        for a in range(angles):
            theta = 2.0 * np.pi * a / angles
            dx = np.cos(theta) * ring_r
            dy = np.sin(theta) * ring_r
            if dx > radius * 0.08 and dy < -radius * 0.08:
                continue
            px = min(w_img - 1, max(0, int(round(cx + dx))))
            py = min(h_img - 1, max(0, int(round(cy + dy))))
            pix = rgb[py:py + 1, px:px + 1]
            hh, ss, vv = rgb_to_hsv_np(pix)
            hv, sv, vv = float(hh[0, 0]), float(ss[0, 0]), float(vv[0, 0])
            total_cnt += 1
            if ring_idx == 1 and vv >= 0.93 and sv <= 0.18:
                max_star += 1
            if sv < 0.20 or vv < 0.16:
                low_sat += 1
                continue
            w = sv * (0.4 + 0.6 * vv) * (1.25 if ring_idx == 0 else 1.0)
            total_w += w
            if hv < 22.0 or hv >= 346.0:
                votes[1] += w  # FIRE
            elif hv <= 74.0:
                votes[3] += w  # LIGHT
            elif hv <= 162.0:
                votes[2] += w  # WOOD
            elif hv <= 248.0:
                votes[0] += w  # WATER
            elif hv <= 310.0:
                votes[4] += w  # DARK
            else:
                votes[5] += w  # HEART

    # Center star gleam
    px0 = min(w_img - 1, max(0, int(round(cx))))
    py0 = min(h_img - 1, max(0, int(round(cy))))
    _, cs, cv = rgb_to_hsv_np(rgb[py0:py0 + 1, px0:px0 + 1])
    if float(cv[0, 0]) >= 0.94 and float(cs[0, 0]) <= 0.18:
        max_star += 2

    # Top-right race badge check
    min_off, max_off = radius * 0.28, radius * 0.78
    white_hits = outer_white = dark_hits = med_dark = vivid_hits = 0
    for iy in range(7):
        dy = -(min_off + (max_off - min_off) * iy / 6.0)
        for ix in range(7):
            dx = min_off + (max_off - min_off) * ix / 6.0
            px = min(w_img - 1, max(0, int(round(cx + dx))))
            py = min(h_img - 1, max(0, int(round(cy + dy))))
            _, rs, rv = rgb_to_hsv_np(rgb[py:py + 1, px:px + 1])
            s_v, v_v = float(rs[0, 0]), float(rv[0, 0])
            if v_v >= 0.72 and s_v <= 0.28:
                white_hits += 1
                if ix >= 4 or iy >= 4:
                    outer_white += 1
            if v_v <= 0.28:
                dark_hits += 1
                if 1 <= ix <= 4 and 1 <= iy <= 4:
                    med_dark += 1
            if s_v >= 0.50 and v_v >= 0.45:
                vivid_hits += 1

    has_race = (
        white_hits / 49.0 >= 0.11
        and outer_white >= 2
        and dark_hits / 49.0 >= 0.14
        and med_dark >= 3
        and vivid_hits / 49.0 <= 0.26
    )

    if total_w < 0.5 or low_sat / max(1, total_cnt) > 0.82:
        return "UNKNOWN", 0.15, max_star, has_race

    best_idx = int(np.argmax(votes))
    best_v = float(votes[best_idx])
    sorted_v = np.sort(votes)
    second_v = float(sorted_v[-2])
    dom = best_v / max(1e-4, total_w)
    margin = (best_v - second_v) / max(1e-4, best_v)
    conf = float(np.clip(dom * 0.65 + margin * 0.35, 0.0, 1.0))
    el_map = ["WATER", "FIRE", "WOOD", "LIGHT", "DARK", "HEART"]
    return el_map[best_idx], conf, max_star, has_race


def detect_custom_yongjue_b1(rgb: np.ndarray) -> BoardBox:
    """Custom HP-anchored BoardBox for 960x1080 wide-cropped yongjue_b1 frames."""
    h, w, _ = rgb.shape
    y0, y1 = int(h * 0.38), int(h * 0.55)
    hh, ss, vv = rgb_to_hsv_np(rgb[y0:y1])
    pink = ((hh >= 328.0) | (hh <= 20.0)) & (ss >= 0.28) & (vv >= 0.45)
    row_cov = pink.mean(axis=1)
    peak_i = int(np.argmax(row_cov))
    bot_i = peak_i
    while bot_i + 1 < row_cov.size and row_cov[bot_i + 1] >= 0.18:
        bot_i += 1
    hp_y = y0 + bot_i
    top = hp_y + 18
    bottom = h - 4
    board_h = bottom - top
    board_w = int(round(board_h * 6.0 / 5.0))
    xs = np.flatnonzero(pink[peak_i])
    bar_l, bar_r = int(xs.min()), int(xs.max())
    # Align horizontally inside [125..940] using board_w
    left = max(0, int(round((bar_l + bar_r) * 0.5 - board_w * 0.51)))
    right = min(w, left + board_w)
    return BoardBox(left, top, right, bottom, hp_y, 0.9, 0.9, 0.9, 0.9)


def analyze_bomb_and_evil_flags(rgb: np.ndarray, box: BoardBox) -> Tuple[Set[Tuple[int, int]], Set[Tuple[int, int]]]:
    """
    Detect real A5 metallic hex Bombs (including blinking-red phases) and A3 6-color darkened Evil Energy orbs
    Strictly scoped to A5/A3 annotated frames.
    """
    cw, ch = box.width / 6.0, box.height / 5.0
    img_h, img_w, _ = rgb.shape
    step = 0.465 / 6.0
    iy_idx, ix_idx = np.meshgrid(np.arange(-6, 7), np.arange(-6, 7), indexing="ij")
    dx, dy = ix_idx * step, iy_idx * step
    rr = np.hypot(dx, dy)
    body = rr <= 0.385
    bomb_zone = rr <= 0.32

    bombs: Set[Tuple[int, int]] = set()
    evils: Set[Tuple[int, int]] = set()
    for r in range(5):
        for c in range(6):
            cx = box.left + (c + 0.5) * cw
            cy = box.top + (r + 0.5) * ch
            xs = [min(img_w - 1, max(0, int(round(cx + ix * step * cw)))) for ix in range(-6, 7)]
            ys = [min(img_h - 1, max(0, int(round(cy + iy * step * ch)))) for iy in range(-6, 7)]
            h, s, v = rgb_to_hsv_np(rgb[np.ix_(ys, xs)])
            grey_ratio = float(((s[bomb_zone] <= 0.26) & (v[bomb_zone] >= 0.22) & (v[bomb_zone] <= 0.78)).mean())
            if grey_ratio >= 0.40:
                bombs.add((r, c))

            avg_v = float(v[body].mean())
            dark_b = float((v[body] < 0.28).mean())
            hi_v = float((v[body] >= 0.60).mean())
            crack_s = float(((s[body] <= 0.22) & (v[body] >= 0.22) & (v[body] <= 0.85)).mean())
            any_wisp = float(((s[body] >= 0.24) & (v[body] >= 0.28)).mean())
            if avg_v <= 0.23 and dark_b >= 0.70 and hi_v <= 0.09 and crack_s < 0.12 and any_wisp >= 0.015:
                evils.add((r, c))
    return bombs, evils


# ---------------------------------------------------------------------------
# Sample Record Structure
# ---------------------------------------------------------------------------

class SampleStore:
    def __init__(self) -> None:
        self.images: List[np.ndarray] = []
        self.is_rune: List[float] = []
        self.element: List[int] = []
        self.variant: List[int] = []
        self.race: List[int] = []
        self.race_known: List[bool] = []
        self.hazards: List[np.ndarray] = []
        self.hazard_mask: List[np.ndarray] = []
        self.tile_overlay: List[np.ndarray] = []
        self.overlay_mask: List[np.ndarray] = []
        self.groups: List[str] = []
        self.sources: List[str] = []

    def add_tile(
        self,
        tile_rgb_u8: np.ndarray,
        group: str,
        source: str,
        is_rune: float = 1.0,
        element_idx: int = -1,
        variant_idx: int = 0,
        race_idx: int = 0,
        race_known: bool = True,
        hazards_vec: Optional[np.ndarray] = None,
        hazard_mask_vec: Optional[np.ndarray] = None,
        overlay_vec: Optional[np.ndarray] = None,
        overlay_mask_vec: Optional[np.ndarray] = None,
    ) -> None:
        self.images.append(tile_rgb_u8)
        self.is_rune.append(float(is_rune))
        self.element.append(int(element_idx))
        self.variant.append(int(variant_idx))
        self.race.append(int(race_idx))
        self.race_known.append(bool(race_known))
        self.hazards.append(
            hazards_vec if hazards_vec is not None else np.zeros(NUM_HAZARDS, dtype=np.float32)
        )
        self.hazard_mask.append(
            hazard_mask_vec if hazard_mask_vec is not None else np.ones(NUM_HAZARDS, dtype=np.float32)
        )
        self.tile_overlay.append(
            overlay_vec if overlay_vec is not None else np.zeros(NUM_TILE_OVERLAYS, dtype=np.float32)
        )
        self.overlay_mask.append(
            overlay_mask_vec if overlay_mask_vec is not None else np.ones(NUM_TILE_OVERLAYS, dtype=np.float32)
        )
        self.groups.append(group)
        self.sources.append(source)


def crop_cell_tile(im: Image.Image, box: BoardBox, r: int, c: int, rows: int = 5, cols: int = 6) -> np.ndarray:
    cw = box.width / float(cols)
    ch = box.height / float(rows)
    x0 = int(round(box.left + c * cw))
    y0 = int(round(box.top + r * ch))
    x1 = int(round(box.left + (c + 1) * cw))
    y1 = int(round(box.top + (r + 1) * ch))
    tile = im.crop((x0, y0, x1, y1)).resize((INPUT_SIZE, INPUT_SIZE), Image.Resampling.BILINEAR)
    return np.asarray(tile, dtype=np.uint8)


def add_annotated_board(
    store: SampleStore,
    img_path: Path,
    group: str,
    *,
    box: Optional[BoardBox] = None,
    rows: int = 5,
    skip_rows: Set[int] = frozenset(),
    skip_cells: Set[Tuple[int, int]] = frozenset(),
    default_race: Optional[str] = None,  # e.g. "HUMAN", "GOD", "MACHINA", "DEMON", "BEAST", "ELF", or None=auto
    race_overrides: Optional[Dict[Tuple[int, int], str]] = None,
    element_overrides: Optional[Dict[Tuple[int, int], str]] = None,
    variant_overrides: Optional[Dict[Tuple[int, int], str]] = None,
    hazard_coords: Optional[Dict[str, Iterable[Tuple[int, int]]]] = None,
    overlay_coords: Optional[Dict[str, Iterable[Tuple[int, int]]]] = None,
    unknown_hazard_cols: Set[str] = frozenset(),
    unknown_overlay_cols: Set[str] = frozenset(),
    min_hsv_conf: float = 0.55,
    only_annotated_special_or_clean: bool = False,
) -> int:
    if not img_path.exists():
        print(f"[WARN] Missing image: {img_path}")
        return 0
    im = Image.open(img_path).convert("RGB")
    rgb = np.array(im)
    if box is None:
        box = detect_tos_board(rgb)
    if box is None:
        print(f"[WARN] No board detected: {img_path}")
        return 0

    cw = box.width / 6.0
    ch = box.height / float(rows)
    el_ovr = element_overrides or {}
    var_ovr = variant_overrides or {}
    r_ovr = race_overrides or {}

    cell_hazards: Dict[Tuple[int, int], Set[str]] = defaultdict(set)
    for h_name, coords in (hazard_coords or {}).items():
        for rc in coords:
            cell_hazards[rc].add(h_name)

    cell_overlays: Dict[Tuple[int, int], Set[str]] = defaultdict(set)
    for o_name, coords in (overlay_coords or {}).items():
        for rc in coords:
            cell_overlays[rc].add(o_name)

    h_mask_base = np.ones(NUM_HAZARDS, dtype=np.float32)
    for hn in unknown_hazard_cols:
        h_mask_base[HAZARD_TO_IDX[hn]] = 0.0

    o_mask_base = np.ones(NUM_TILE_OVERLAYS, dtype=np.float32)
    for on in unknown_overlay_cols:
        o_mask_base[TILE_OVERLAY_TO_IDX[on]] = 0.0

    added = 0
    for r in range(rows):
        if r in skip_rows:
            continue
        for c in range(6):
            rc = (r, c)
            if rc in skip_cells:
                continue

            cx = box.left + (c + 0.5) * cw
            cy = box.top + (r + 0.5) * ch
            hsv_el, hsv_conf, star_cnt, auto_race = hsv_base_classify_cell(rgb, cx, cy, cw, ch)

            haz_set = cell_hazards.get(rc, set())
            ov_set = cell_overlays.get(rc, set())
            has_special = bool(haz_set or ov_set or rc in el_ovr or rc in var_ovr)

            if only_annotated_special_or_clean and not has_special and hsv_conf < 0.72:
                continue

            # Element label
            if rc in el_ovr:
                el_name = el_ovr[rc]
            elif "SHATTERED" in ov_set:
                el_name = "EMPTY"
            elif "QUESTION" in haz_set:
                el_name = "HIDDEN"
            elif hsv_conf >= min_hsv_conf and hsv_el != "UNKNOWN":
                el_name = hsv_el
            else:
                # Obscured hazard tile with unknown underlying element -> mask element loss (-1)
                el_name = None

            el_idx = ELEMENT_TO_IDX[el_name] if el_name is not None else -1

            # Race & Variant
            if rc in r_ovr:
                race_name = r_ovr[rc]
                race_known = True
            elif default_race is not None:
                race_name = default_race
                race_known = True
            elif "SHATTERED" in ov_set or "QUESTION" in haz_set:
                race_name = "NONE"
                race_known = True
            elif not auto_race:
                race_name = "NONE"
                race_known = True
            else:
                # Top-right race badge visually detected on unannotated board -> don't guess which of 7 races!
                race_name = "NONE"
                race_known = False

            race_idx = RACE_TO_IDX[race_name]

            if rc in var_ovr:
                var_name = var_ovr[rc]
            elif star_cnt >= 7 and not haz_set:
                var_name = "ENCHANTED"
            elif race_idx > 0 or (auto_race and not race_known):
                var_name = "RACE"
            else:
                var_name = "NORMAL"
            var_idx = VARIANT_TO_IDX[var_name]

            h_vec = np.zeros(NUM_HAZARDS, dtype=np.float32)
            for hn in haz_set:
                h_vec[HAZARD_TO_IDX[hn]] = 1.0

            o_vec = np.zeros(NUM_TILE_OVERLAYS, dtype=np.float32)
            for on in ov_set:
                o_vec[TILE_OVERLAY_TO_IDX[on]] = 1.0

            tile_u8 = crop_cell_tile(im, box, r, c, rows=rows, cols=6)
            store.add_tile(
                tile_u8,
                group=group,
                source=f"{img_path.name}:r{r}c{c}",
                is_rune=1.0,
                element_idx=el_idx,
                variant_idx=var_idx,
                race_idx=race_idx,
                race_known=race_known,
                hazards_vec=h_vec,
                hazard_mask_vec=h_mask_base.copy(),
                overlay_vec=o_vec,
                overlay_mask_vec=o_mask_base.copy(),
            )
            added += 1

    # Also harvest 2 non-board UI/background negative patches (`is_rune = 0`) per frame!
    if box.top > 100 and box.width >= 200:
        for idx, (nx0, ny0) in enumerate(((box.left + 12, 20), (box.left + box.width // 2, int(box.top * 0.45)))):
            nw = min(int(round(cw)), box.width - nx0 - 1, im.width - nx0)
            nh = min(int(round(ch)), im.height - ny0)
            if nw >= 48 and nh >= 48:
                patch = im.crop((nx0, ny0, nx0 + nw, ny0 + nh)).resize((INPUT_SIZE, INPUT_SIZE), Image.Resampling.BILINEAR)
                store.add_tile(
                    np.asarray(patch, dtype=np.uint8),
                    group=group,
                    source=f"{img_path.name}:neg_{idx}",
                    is_rune=0.0,
                    element_idx=-1,
                    variant_idx=-1,
                    race_idx=0,
                    race_known=False,
                    hazards_vec=np.zeros(NUM_HAZARDS, dtype=np.float32),
                    hazard_mask_vec=np.zeros(NUM_HAZARDS, dtype=np.float32),
                    overlay_vec=np.zeros(NUM_TILE_OVERLAYS, dtype=np.float32),
                    overlay_mask_vec=np.zeros(NUM_TILE_OVERLAYS, dtype=np.float32),
                )
    return added


def build_all_samples() -> SampleStore:
    store = SampleStore()
    media = REPO_ROOT / "backups" / "user_training_media"
    p4_samples = REPO_ROOT / "backups" / "phase4_training_dataset"

    # 1) Blessed + Music 6 colors + Human Race (180 gold tiles)
    bm_dir = media / "20261124_blessed_and_music_6colors"
    bm_specs = [
        ("01_music_wood_blessed_water.jpg", "WOOD", "WATER"),
        ("02_music_fire_blessed_light.jpg", "FIRE", "LIGHT"),
        ("03_music_dark_blessed_heart.jpg", "DARK", "HEART"),
        ("04_music_water_blessed_wood.jpg", "WATER", "WOOD"),
        ("05_music_light_blessed_fire.jpg", "LIGHT", "FIRE"),
        ("06_music_heart_blessed_dark.jpg", "HEART", "DARK"),
    ]
    for i, (fname, el_odd, el_even) in enumerate(bm_specs):
        ov = {(r, c): (el_odd if c % 2 == 0 else el_even) for r in range(5) for c in range(6)}
        add_annotated_board(store, bm_dir / fname, group=f"bm_human_{i}", default_race="HUMAN", element_overrides=ov)

    # 2) Electrified Heart (2 frames) & Electrified classic
    eh_coords = [(0, 1), (0, 4), (2, 1), (3, 0), (3, 3)]
    for fi, fn in enumerate(("01_electrified_heart_frame1.jpg", "02_electrified_heart_frame2.jpg")):
        add_annotated_board(
            store,
            media / "20261124_electrified_heart" / fn,
            group=f"elec_heart_{fi}",
            default_race="NONE",
            element_overrides={rc: "HEART" for rc in eh_coords},
            hazard_coords={"ELECTRIFIED": eh_coords},
        )
    add_annotated_board(
        store,
        media / "20261123_electrified/electrified_board.jpg",
        group="elec_classic",
        default_race="NONE",
        element_overrides={(1, 1): "WATER", (2, 5): "LIGHT"},
        hazard_coords={"ELECTRIFIED": [(1, 1), (2, 5)]},
    )

    # 3) Thorn 6 colors complete
    thorn_els = {
        (0, 5): "LIGHT", (1, 3): "LIGHT", (3, 1): "LIGHT",
        (3, 3): "FIRE", (4, 4): "FIRE",
        (0, 2): "DARK", (1, 1): "DARK", (1, 2): "DARK",
        (2, 4): "WATER", (4, 5): "WATER",
        (0, 0): "WOOD",
        (0, 1): "HEART",
    }
    add_annotated_board(
        store,
        media / "20261124_thorn_6colors_complete/01_thorn_all_6colors_light_fire_complete.jpg",
        group="thorn_6c",
        default_race="NONE",
        element_overrides=thorn_els,
        variant_overrides={(1, 2): "ENCHANTED", (0, 0): "ENCHANTED"},
        hazard_coords={"THORN": list(thorn_els.keys())},
    )
    add_annotated_board(
        store,
        media / "20261123_petrified_locked_thorn/img1_thorn.jpg",
        group="thorn_img1",
        default_race="NONE",
        element_overrides={(1, 4): "HEART", (2, 3): "LIGHT"},
        hazard_coords={"THORN": [(1, 4), (2, 3)]},
    )

    # 4) Void Mark + Luminous + God Race (3 frames)
    vm_dir = media / "20261124_void_mark_and_luminous"
    add_annotated_board(
        store,
        vm_dir / "01_void_mark_heart_luminous_light_0of6.jpg",
        group="void_luminous_1",
        skip_cells={(0, 4)},  # finger indicator
        default_race="NONE",
        element_overrides={(4, 2): "LIGHT"},
        hazard_coords={"LUMINOUS": [(4, 2)]},
    )
    add_annotated_board(
        store,
        vm_dir / "02_void_mark_light_god_race_ban_dark.jpg",
        group="god_race_ban",
        default_race="GOD",
        race_overrides={(1, 2): "NONE", (2, 2): "NONE", (3, 2): "NONE"},
        element_overrides={(1, 2): "LIGHT", (2, 2): "LIGHT", (3, 2): "LIGHT"},
    )
    add_annotated_board(
        store,
        vm_dir / "03_void_mark_dark_luminous_dark_2of6.jpg",
        group="void_luminous_3",
        default_race="NONE",
        element_overrides={(0, 3): "DARK"},
        hazard_coords={"LUMINOUS": [(0, 3)]},
    )

    # 5) Burning Columns + Blessed Pure + Dark Modal (hard neg!) + Dark-Teal Q30
    bb_dir = media / "20261125_burning_blessed_fog"
    add_annotated_board(
        store,
        bb_dir / "burning_tile/01_burning_columns_c0_c5_water_fire_light_heart_10cells.jpg",
        group="burning_cols_0_5",
        default_race="NONE",
        overlay_coords={"BURNING_TILE": [(r, c) for r in range(5) for c in (0, 5)]},
    )
    add_annotated_board(
        store,
        bb_dir / "blessed_pure/01_blessed_pure_no_race_wood_fire_light_dark_heart_8orbs.jpg",
        group="blessed_pure",
        default_race="NONE",
    )
    add_annotated_board(
        store,
        media / "20261122_barrier_burning_victory/burning_tile_board.jpg",
        group="burning_single_classic",
        default_race="NONE",
        overlay_coords={"BURNING_TILE": [(1, 0)]},
    )
    # 30/30 Dark Teal Question Orbs
    add_annotated_board(
        store,
        media / "20261125_dark_teal_question_30/01_dark_teal_question_30orbs.png",
        group="q30_dark_teal",
        default_race="NONE",
        element_overrides={(r, c): "HIDDEN" for r in range(5) for c in range(6)},
        hazard_coords={"QUESTION": [(r, c) for r in range(5) for c in range(6)]},
    )

    # 6) Machina Race + Monochrome All-6
    mono_dir = media / "20261125_monochrome_and_banned_wood"
    add_annotated_board(
        store,
        mono_dir / "01_banned_wood_and_machina_race.jpg",
        group="machina_race_25",
        default_race="MACHINA",
        race_overrides={rc: "NONE" for rc in [(0, 4), (0, 5), (1, 0), (2, 0), (2, 2)]},
        element_overrides={rc: "WOOD" for rc in [(0, 3), (1, 0), (1, 5), (2, 4), (3, 4), (4, 0)]},
    )
    mono_grid = parse_grid([
        "D F D W L L",
        "D D F G F W",
        "H G H H G D",
        "F H D H G L",
        "F F D L H D",
    ])
    add_annotated_board(
        store,
        mono_dir / "02_monochrome_black_white_6colors.jpg",
        group="monochrome_30",
        default_race="NONE",
        element_overrides=mono_grid,
        hazard_coords={"BLACK_WHITE": list(mono_grid.keys())},
    )

    # 7) Petrified + Full-Board 30/30 Locked
    plt_dir = media / "20261123_petrified_locked_thorn"
    add_annotated_board(
        store,
        plt_dir / "v2_petrified.png",
        group="petrified_v2",
        default_race="NONE",
        element_overrides={(0, 2): "UNKNOWN", (4, 2): "UNKNOWN", (4, 4): "UNKNOWN"},
        hazard_coords={"PETRIFIED": [(0, 2), (4, 2), (4, 4)]},
    )
    locked_grid = parse_grid([
        "H L H G W H",
        "H D D W G G",
        "G F L W W L",
        "G F H H F G",
        "W G L W L W",
    ])
    add_annotated_board(
        store,
        plt_dir / "v3_locked_all30.png",
        group="locked_all30",
        default_race="NONE",
        element_overrides=locked_grid,
        hazard_coords={"BLOOD_DRAIN_LOCKED": list(locked_grid.keys())},
    )

    # 8) Verified Batch (Youtube curated 1)
    vb = media / "20261125_yt_verified_batch"
    # A2 Electrified
    add_annotated_board(
        store, vb / "A2_electrified_dark/01_wanwu_dark_elec_4orbs.png",
        group="vb_wanwu", default_race="NONE",
        element_overrides={(0, 1): "WATER", (0, 2): "WATER", (0, 4): "WOOD", (2, 2): "DARK", (3, 1): "DARK", (4, 1): "DARK", (4, 3): "DARK"},
        hazard_coords={"MAGIC_ORB": [(0, 1), (0, 2), (0, 4)], "ELECTRIFIED": [(2, 2), (3, 1), (4, 1), (4, 3)]},
    )
    add_annotated_board(
        store, vb / "A2_electrified_dark/03_menglie_water_dark_elec.png",
        group="vb_menglie", skip_rows={4}, default_race="MACHINA",
        element_overrides={(0, 4): "WATER", (2, 2): "DARK", (3, 1): "WATER"},
        hazard_coords={"ELECTRIFIED": [(0, 4), (2, 2), (3, 1)]},
    )
    add_annotated_board(
        store, vb / "A2_electrified_dark/04_yongjue_light_elec_3orbs.png",
        group="vb_yongjue_elec", default_race="BEAST",
        element_overrides={(0, 1): "LIGHT", (2, 3): "LIGHT", (2, 5): "LIGHT", (3, 2): "LIGHT"},
        hazard_coords={"ELECTRIFIED": [(0, 1), (2, 3), (2, 5)]},
    )
    # A3 Wood/Dark Evil Energy + Luminous
    add_annotated_board(
        store, vb / "A3_evil_energy_wood_dark/01_esha_wood_evil_3orbs.png",
        group="vb_esha_evil", default_race="NONE",
        element_overrides={(3, 0): "WOOD", (3, 1): "WOOD", (3, 2): "WOOD", (3, 3): "LIGHT"},
        hazard_coords={"EVIL_ENERGY": [(3, 0), (3, 1), (3, 2)], "LUMINOUS": [(3, 3)]},
    )
    add_annotated_board(
        store, vb / "A3_evil_energy_wood_dark/02_esha_wood_dark_evil_6orbs.png",
        group="vb_esha_evil", default_race="NONE",
        element_overrides={(4, 2): "DARK"},
        hazard_coords={"EVIL_ENERGY": [(3, 0), (3, 1), (3, 2), (4, 0), (4, 1), (4, 3)], "LUMINOUS": [(4, 2)]},
    )
    # A4 Frozen (exact 7 Frozen cells on hundun frames; (1,3) is normal Wood!)
    add_annotated_board(
        store, vb / "A4_frozen_dark/01_hundun_frozen_dark_multi.png",
        group="vb_hundun_froz_1", default_race="NONE",
        element_overrides={(0, 1): "WATER", (0, 2): "WATER", (1, 2): "DARK", (2, 4): "HEART", (3, 4): "DARK", (4, 4): "HEART", (4, 5): "WATER"},
        hazard_coords={"FROZEN": [(0, 1), (0, 2), (1, 2), (2, 4), (3, 4), (4, 4), (4, 5)]},
    )
    add_annotated_board(
        store, vb / "A4_frozen_dark/02_hundun_frozen_dark_wood_heart.png",
        group="vb_hundun_froz_2", default_race="NONE",
        element_overrides={(0, 1): "WATER", (0, 2): "WATER", (1, 2): "WOOD", (2, 4): "DARK", (3, 4): "HEART", (4, 4): "WOOD", (4, 5): "WATER"},
        hazard_coords={"FROZEN": [(0, 1), (0, 2), (1, 2), (2, 4), (3, 4), (4, 4), (4, 5)]},
    )
    # A5 Bombs (auto exact bomb detector!)
    for p in sorted((vb / "A5_bomb_fire_dark").glob("*.png")):
        rgb = np.array(Image.open(p).convert("RGB"))
        b = detect_tos_board(rgb)
        if b:
            bombs, _ = analyze_bomb_and_evil_flags(rgb, b)
            ov_coords = {}
            if "blackhole" in p.name:
                bh = [(r, c) for r in range(5) for c in range(6) if (r, c) not in {(1, 1), (2, 0), (2, 1), (3, 2), (4, 5)} and r < 4]
                ov_coords["BLACK_HOLE"] = bh
            add_annotated_board(store, p, group=f"vb_bomb_{p.stem[:10]}", box=b, default_race="NONE", hazard_coords={"BOMB": bombs}, overlay_coords=ov_coords)

    # A6 Burning (Col 3, Col 5)
    add_annotated_board(
        store, vb / "A6_burning_wood_dark_closed/01_moyan_col3_burning_dark.png",
        group="vb_moyan_burn_1", default_race="NONE",
        overlay_coords={"BURNING_TILE": [(r, 3) for r in range(5)]},
    )
    add_annotated_board(
        store, vb / "A6_burning_wood_dark_closed/02_moyan_col5_burning_wood_dark.png",
        group="vb_moyan_burn_2", default_race="NONE",
        overlay_coords={"BURNING_TILE": [(r, 5) for r in range(5)]},
    )
    # A7 Bloodbath
    for bi, fn in enumerate(("01_yongjue_bloodbath_light_3orbs.png", "02_yongjue_bloodbath_light_f2.png")):
        add_annotated_board(
            store, vb / "A7_bloodbath_light" / fn,
            group=f"vb_yongjue_bb_{bi}", default_race="NONE",
            overlay_coords={"END_FORBIDDEN_BLOODBATH": [(3, 2), (3, 3), (3, 4), (4, 2), (4, 3), (4, 4)]},
        )
    # C01 Weathered, C04 Foam, C07 Luminous, C08 Magic, C11 Scorching, C12 BlackHole, Bonus Race/Shattered
    add_annotated_board(
        store, vb / "C01_weathered/01_moyan_weathered_fire_dark.png",
        group="vb_moyan_weath", default_race="NONE",
        element_overrides={(2, 1): "DARK", (2, 4): "FIRE"},
        hazard_coords={"WEATHERED": [(2, 1), (2, 4)]},
    )
    foam_cells = [(1, 2), (1, 5), (2, 1), (2, 2)]
    add_annotated_board(
        store, vb / "C04_foam/01_juewang_foam_4colors_6orbs.png",
        group="vb_juewang_foam", skip_cells={(r, 0) for r in range(5)}, default_race="NONE",
        element_overrides={(1, 2): "HEART", (1, 5): "DARK", (2, 1): "WATER", (2, 2): "WOOD"},
        hazard_coords={"FOAM": foam_cells},
    )
    add_annotated_board(
        store, vb / "C07_luminous_3states/01_zuzhou_luminous_fire_charging.png",
        group="vb_zuzhou_lum1", default_race="NONE",
        element_overrides={(4, 3): "FIRE"},
        hazard_coords={"LUMINOUS": [(4, 3)]},
    )
    add_annotated_board(
        store, vb / "C07_luminous_3states/02_zuzhou_luminous_wood_empty.png",
        group="vb_zuzhou_lum2", default_race="NONE",
        element_overrides={(4, 3): "WOOD"},
        hazard_coords={"LUMINOUS": [(4, 3)]},
    )
    add_annotated_board(
        store, vb / "C07_luminous_3states/03_esha_luminous_water_full_gold.png",
        group="vb_esha_lum", default_race="NONE",
        element_overrides={(4, 0): "WATER"},
        hazard_coords={"LUMINOUS": [(4, 0)]},
    )
    add_annotated_board(
        store, vb / "C08_magic_orb/02_wanwu_magic_water_wood_bright.png",
        group="vb_wanwu", default_race="NONE",
        element_overrides={(0, 1): "WATER", (0, 2): "WATER", (0, 4): "WOOD"},
        hazard_coords={"MAGIC_ORB": [(0, 1), (0, 2), (0, 4)]},
    )
    scorch_specs = [
        ("C11_scorching_terrain/01_juewang_scorching_col1_6.png", [0, 5]),
        ("C11_scorching_terrain/02_zuzhou_scorching_col2_5.png", [1, 4]),
        ("C11_scorching_terrain/03_rongyao_scorching_col1_6.png", [0, 5]),
        ("C11_scorching_terrain/04_wanwu_scorching_col2_6.png", [1, 5]),
    ]
    for fn, cols_sc in scorch_specs:
        add_annotated_board(
            store, vb / fn, group=f"vb_scorch_{Path(fn).stem[:10]}",
            default_race="NONE",
            overlay_coords={"SCORCHING_TILE": [(r, c) for r in range(5) for c in cols_sc]},
        )
    add_annotated_board(
        store, vb / "C12_black_hole_terrain/02_rongguang_blackhole_empty_wood.png",
        group="vb_rong_bh", skip_rows={4}, default_race="NONE",
        element_overrides={rc: "EMPTY" for rc in [(1, 0), (1, 3), (1, 4), (1, 5), (2, 0), (2, 3), (2, 4)]},
        overlay_coords={"BLACK_HOLE": [(1, 0), (1, 3), (1, 4), (1, 5), (2, 0), (2, 3), (2, 4)]},
    )
    add_annotated_board(
        store, vb / "C12_black_hole_terrain/03_zuzhou_blackhole_col1_6_10cells.png",
        group="vb_zuz_bh1", default_race="NONE",
        overlay_coords={"BLACK_HOLE": [(r, c) for r in range(5) for c in (0, 5)]},
    )
    add_annotated_board(
        store, vb / "C12_black_hole_terrain/04_zuzhou_blackhole_scatter_8cells.png",
        group="vb_zuz_bh2", skip_rows={4}, default_race="NONE",
        overlay_coords={"BLACK_HOLE": [(0, 0), (0, 3), (1, 1), (1, 3), (2, 4), (2, 5), (3, 0)]},
    )
    shatt_cells = [(0, 0), (0, 1), (0, 2), (0, 4), (1, 0), (1, 1), (1, 2), (1, 3), (1, 4), (2, 0), (2, 1)]
    add_annotated_board(
        store, vb / "Bonus_race_and_shattered/01_esha_shattered_9cells.png",
        group="vb_shattered", default_race="DEMON",
        race_overrides={rc: "NONE" for rc in shatt_cells},
        overlay_coords={"SHATTERED": shatt_cells},
    )
    add_annotated_board(store, vb / "Bonus_race_and_shattered/02_chenfeng_fairy_race_30orbs.png", group="vb_race_elf", default_race="ELF")
    add_annotated_board(store, vb / "Bonus_race_and_shattered/03_chenfeng_beast_race_30orbs.png", group="vb_race_beast", default_race="BEAST")
    add_annotated_board(store, vb / "Bonus_race_and_shattered/04_weishengcun_demon_race_30orbs.png", group="vb_race_demon", default_race="DEMON")

    # 9) User Batch 2 (5x6 verified curated frames)
    u2 = media / "20261125_yt_user_batch2"
    add_annotated_board(
        store, u2 / "A2_electrified_fire_wood_6x8/beiliya_f018_t0058s_electrified_heart_wood_light.png",
        group="u2_beiliya", default_race="NONE",
        element_overrides={(1, 0): "HEART", (1, 3): "WOOD", (1, 5): "LIGHT"},
        hazard_coords={"ELECTRIFIED": [(1, 0), (1, 3), (1, 5)]},
    )
    add_annotated_board(
        store, u2 / "A2_electrified_fire_wood_6x8/beiliya_f052_t0152s_electrified_cols01.png",
        group="u2_beiliya", default_race="NONE",
        hazard_coords={"ELECTRIFIED": [(r, c) for r in range(5) for c in (0, 1)]},
    )
    ning_elec = [(0, 0), (1, 0), (1, 2), (1, 5), (2, 3), (3, 4), (4, 5)]
    for fn in ("ningjie_f021_t0020s_electrified_6x8_machine.png", "ningjie_f024_t0021s_electrified_6x8_machine_alt.png"):
        add_annotated_board(
            store, u2 / "A2_electrified_fire_wood_6x8" / fn,
            group="u2_ningjie", default_race="MACHINA",
            element_overrides={rc: "LIGHT" for rc in [(0, 0), (1, 0), (1, 2), (1, 5)]} if "alt" in fn else None,
            hazard_coords={"ELECTRIFIED": [(0, 0), (1, 0), (1, 2), (1, 5), (2, 3), (3, 4)] if "alt" in fn else [(1, 0), (1, 2), (2, 3), (3, 4)]},
        )
    # A4 Frozen Light/Wood/Dark/Heart
    add_annotated_board(
        store, u2 / "A4_frozen_unfrozen_light/mingtu_f062_t0286s_frozen_light_wood_dark.png",
        group="u2_mingtu_1", default_race="NONE",
        element_overrides={(1, 0): "LIGHT", (1, 4): "DARK", (2, 3): "WOOD"},
        hazard_coords={"FROZEN": [(1, 0), (1, 4), (2, 3)]},
    )
    add_annotated_board(
        store, u2 / "A4_frozen_unfrozen_light/mingtu_f070_t0306s_frozen_light_heart_dark.png",
        group="u2_mingtu_2", default_race="NONE",
        element_overrides={(1, 2): "LIGHT", (2, 4): "DARK", (3, 5): "HEART"},
        hazard_coords={"FROZEN": [(1, 2), (2, 4), (3, 5)]},
    )
    # A5 Bombs batch 2
    for ji, p in enumerate(sorted((u2 / "A5_bomb_wood_light_heart").glob("*.png"))):
        rgb = np.array(Image.open(p).convert("RGB"))
        b = detect_tos_board(rgb)
        if b:
            bombs, _ = analyze_bomb_and_evil_flags(rgb, b)
            add_annotated_board(store, p, group=f"u2_jie_bomb_{ji}", box=b, default_race="NONE", hazard_coords={"BOMB": bombs})
    # A7 Full-Board 30/30 Bloodbath Heart
    add_annotated_board(
        store, u2 / "A7_bloodbath_heart/chenfeng_f040_t0422s_bloodbath_30_all6_heart.png",
        group="u2_chenfeng_bb30", default_race="NONE",
        overlay_coords={"END_FORBIDDEN_BLOODBATH": [(r, c) for r in range(5) for c in range(6)]},
    )
    # C1 Weathered + Q30 + Weathered
    add_annotated_board(
        store, u2 / "C1_weathered_all6_and_question/lizhi_f087_t0330s_weathered_question_30.png",
        group="u2_lizhi_wq30", default_race="NONE",
        element_overrides={(r, c): "HIDDEN" for r in range(5) for c in range(6)},
        hazard_coords={"QUESTION": [(r, c) for r in range(5) for c in range(6)], "WEATHERED": [(r, c) for r in range(5) for c in range(6)]},
    )
    # C8 Magic Orb
    add_annotated_board(
        store, u2 / "C8_magic_circle_5colors/baolie_f034_t0270s_magic_fire_light_heart_water.png",
        group="u2_baolie_mag", default_race="NONE",
        element_overrides={(0, 1): "HEART", (0, 2): "FIRE", (0, 3): "LIGHT", (0, 4): "WATER"},
        hazard_coords={"MAGIC_ORB": [(0, 1), (0, 2), (0, 3), (0, 4)]},
    )
    add_annotated_board(
        store, u2 / "C8_magic_circle_5colors/baolie_f037_t0287s_magic_water_light_plus_bloodspine.png",
        group="u2_baolie_mag", skip_rows={3, 4}, default_race="NONE",
        element_overrides={(0, 1): "WATER", (0, 2): "LIGHT", (0, 3): "WATER", (0, 5): "FIRE", (1, 4): "FIRE", (2, 0): "FIRE"},
        hazard_coords={"MAGIC_ORB": [(0, 1), (0, 2), (0, 3)], "BLOOD_THORN": [(0, 5), (1, 4), (2, 0)]},
    )
    add_annotated_board(
        store, u2 / "C8_magic_circle_5colors/jie_f020_t0214s_magic_light_bottom_row.png",
        group="u2_jie_mag", default_race="HUMAN",
        element_overrides={(4, 1): "LIGHT", (4, 2): "LIGHT", (4, 3): "LIGHT"},
        hazard_coords={"MAGIC_ORB": [(4, 1), (4, 2), (4, 3)]},
    )
    # C9 Blood-Thorn
    add_annotated_board(
        store, u2 / "C9_blood_spine_runestones/baolie_f010_t0082s_bloodspine_light.png",
        group="u2_baolie_bs1", skip_rows={4}, default_race="NONE",
        element_overrides={(0, 0): "LIGHT", (3, 2): "LIGHT"},
        hazard_coords={"BLOOD_THORN": [(0, 0), (3, 2)]},
    )
    bs19 = [(0, 2), (0, 4), (1, 1), (1, 3), (1, 4), (1, 5), (2, 1), (2, 3), (2, 4), (2, 5), (3, 4)]
    add_annotated_board(
        store, u2 / "C9_blood_spine_runestones/baolie_f019_t0132s_bloodspine_fire_burning.png",
        group="u2_baolie_bs2", default_race="NONE",
        element_overrides={rc: ("WOOD" if rc == (0, 4) else "FIRE") for rc in bs19},
        hazard_coords={"BLOOD_THORN": bs19},
        overlay_coords={"SCORCHING_TILE": [(r, c) for r in range(5) for c in (1, 4)]},
    )
    bs26 = [(0, 1), (1, 2), (2, 3), (2, 4), (4, 4), (4, 5)]
    add_annotated_board(
        store, u2 / "C9_blood_spine_runestones/baolie_f026_t0177s_bloodspine_dark.png",
        group="u2_baolie_bs3", default_race="NONE",
        element_overrides={(0, 1): "DARK", (1, 2): "FIRE", (2, 3): "FIRE", (2, 4): "FIRE", (4, 4): "FIRE", (4, 5): "FIRE"},
        hazard_coords={"BLOOD_THORN": bs26},
    )
    bs38 = [(0, 4), (0, 5), (1, 1), (1, 2), (1, 4), (2, 0), (2, 4), (3, 4), (4, 4)]
    add_annotated_board(
        store, u2 / "C9_blood_spine_runestones/baolie_f038_t0290s_bloodspine_fire_alt.png",
        group="u2_baolie_bs4", default_race="NONE",
        element_overrides={rc: "FIRE" for rc in bs38},
        hazard_coords={"BLOOD_THORN": bs38},
    )
    # Bonus Music / Banned clean Human Race
    for fn in ("wanwu_f010_t0152s_banned_water_and_human_badge.png", "wanwu_f063_t0800s_music_fire_water.png", "wanwu_f071_t0895s_music_water_clean.png"):
        add_annotated_board(store, u2 / "bonus_music_shield_screw_burning_6x8" / fn, group=f"u2_wanwu_{fn[:9]}", default_race="NONE")

    # 10) User Batch 3: A3 Evil Energy all 6 colors, C2 Weakened all 6 colors, C3 Coagulated
    u3 = media / "20261125_yt_user_batch3"
    for p in sorted((u3 / "A3_evil_energy_6colors_complete").glob("*.png")):
        rgb = np.array(Image.open(p).convert("RGB"))
        b = detect_tos_board(rgb)
        if b:
            _, evils = analyze_bomb_and_evil_flags(rgb, b)
            skip_r = {4} if "esha_huamei" in p.name else set()
            grp = f"u3_evil_{p.stem.split('_')[0]}_{p.stem.split('_')[1]}"
            race_def = "DEMON" if "limulu" in p.name else "NONE"
            add_annotated_board(store, p, group=grp, box=b, skip_rows=skip_r, default_race=race_def, hazard_coords={"EVIL_ENERGY": evils})

    # C2 Weakened (Heart + All 6 via yongjue_b1)
    for fn in ("xunlian_fumian1_f004_t0013s_weakened_heart_5orbs.png", "xunlian_fumian1_f011_t0028s_weakened_heart_5orbs_alt.png"):
        weak_h = [(1, 0), (2, 2), (2, 4), (3, 3), (3, 4)]
        add_annotated_board(
            store, u3 / "C2_weakened_runestones_all6" / fn,
            group="u3_xunlian_weak", default_race="NONE",
            element_overrides={rc: "HEART" for rc in weak_h},
            variant_overrides={rc: "WEAKENED" for rc in weak_h},
        )
    outer18 = [(r, c) for r in range(5) for c in range(6) if r in (0, 4) or c in (0, 5)]
    for fn in ("yongjue_b1_t0029s_weakened_18orbs_clean.png", "yongjue_b1_t0030s_weakened_18orbs_all6colors.png", "yongjue_b1_t0031s_weakened_18orbs_all6_alt.png"):
        p = u3 / "C2_weakened_runestones_all6" / fn
        if p.exists():
            rgb = np.array(Image.open(p).convert("RGB"))
            b = detect_custom_yongjue_b1(rgb)
            add_annotated_board(
                store, p, group="u3_yongjue_weak18", box=b, default_race="HUMAN",
                variant_overrides={rc: "WEAKENED" for rc in outer18},
            )

    # C3 Coagulated
    coag_specs = [
        ("fangtu_guangai_f010_t0092s_coagulated_double_3orbs.png", [(0, 1), (2, 3), (2, 4)], set(), set()),
        ("fangtu_guangai_f011_t0096s_coagulated_double_2orbs.png", [(1, 5), (2, 5)], set(), set()),
        ("fangtu_guangai_f012_t0104s_coagulated_single_cracked_3orbs.png", [(1, 1), (3, 1), (4, 1)], {4}, set()),
        ("fangtu_guangai_f014_t0114s_coagulated_double_3orbs_alt.png", [(0, 3), (1, 3), (3, 3)], set(), set()),
        ("xunlian_adv_f022_t0655s_coagulated_double_clean.png", [(0, 1), (1, 4), (2, 3)], set(), set()),
    ]
    for fn, coords, skip_r, skip_c in coag_specs:
        ov_el = {rc: "HEART" for rc in coords} if "f012" in fn else {}
        add_annotated_board(
            store, u3 / "C3_coagulated_double_and_single" / fn,
            group=f"u3_coag_{fn[:12]}", skip_rows=skip_r, skip_cells=skip_c,
            default_race="NONE",
            element_overrides=ov_el,
            hazard_coords={"COAGULATED": coords},
        )

    # 11) Phase 4 Real Battle Samples + Puzzle + Traffic Light frames
    for fn in ("emu_battle_01.png", "user_4c_battle.jpg", "user_fire5_battle.jpg", "user_light_dark5_battle.jpg"):
        add_annotated_board(store, p4_samples / "real_battle_samples" / fn, group=f"p4_clean_{Path(fn).stem}", default_race="NONE")
    add_annotated_board(
        store, p4_samples / "real_battle_samples/emu_mech_check.png",
        group="p4_storm_col0", default_race="NONE",
        overlay_coords={"STORM": [(r, 0) for r in range(5)]},
    )
    add_annotated_board(
        store, media / "20261123_banner_emptyhp_race/v2_00s.png",
        group="p4_demon_race_v2", default_race="DEMON",
        skip_cells={(0, 5)},
    )
    # Puzzle Shield frames (Normal Cross + Strict L + Strict 1x5)
    for fn in ("t_100ms.png", "t_4200ms.png"):
        add_annotated_board(
            store, p4_samples / "extracted_frames/normal_silver_cross_puzzle" / fn,
            group="p4_puzzle_cross", default_race="NONE",
            overlay_coords={"PUZZLE": [(1, 4), (2, 3), (2, 4), (2, 5), (3, 4)]},
        )
    for fn in ("t_1000ms.png", "t_2200ms.png"):
        add_annotated_board(
            store, p4_samples / "extracted_frames/strict_gold_L_puzzle" / fn,
            group="p4_puzzle_L", default_race="NONE",
            overlay_coords={"PUZZLE": [(1, 0), (1, 1), (1, 2), (2, 0), (3, 0)]},
        )
    # Hard UI Negatives (`is_rune = 0` explicit popups & victory screens)
    for fn in ("20261122_barrier_burning_victory/burning_skill_popup.jpg", "20261122_barrier_burning_victory/victory_flash_reveal_bug.jpg", "20261125_burning_blessed_fog/burning_tile/02_burning_skill_modal_uriel_gt.jpg"):
        p = media / fn
        if p.exists():
            im = Image.open(p).convert("RGB")
            w, h = im.size
            for gi, (px, py) in enumerate(((int(w * 0.2), int(h * 0.45)), (int(w * 0.5), int(h * 0.55)), (int(w * 0.4), int(h * 0.68)), (int(w * 0.6), int(h * 0.25)))):
                patch = im.crop((px, py, min(w, px + 84), min(h, py + 84))).resize((INPUT_SIZE, INPUT_SIZE), Image.Resampling.BILINEAR)
                store.add_tile(
                    np.asarray(patch, dtype=np.uint8),
                    group=f"neg_popup_{Path(fn).stem[:10]}",
                    source=f"{p.name}:modal_{gi}",
                    is_rune=0.0,
                    element_idx=-1,
                    variant_idx=-1,
                    race_idx=0,
                    race_known=False,
                    hazards_vec=np.zeros(NUM_HAZARDS, dtype=np.float32),
                    hazard_mask_vec=np.zeros(NUM_HAZARDS, dtype=np.float32),
                    overlay_vec=np.zeros(NUM_TILE_OVERLAYS, dtype=np.float32),
                    overlay_mask_vec=np.zeros(NUM_TILE_OVERLAYS, dtype=np.float32),
                )

    # 12) High-Confidence Diversity Harvest across YouTube extracted folders (rows 0..3 only, <= 3 frames/video)
    for root_name in ("yt_extracted", "yt_extracted_round2", "yt_extracted_round3"):
        yt_root = media / root_name
        if not yt_root.exists():
            continue
        for vid_dir in sorted(yt_root.iterdir()):
            frames_dir = vid_dir / "frames"
            if not frames_dir.exists():
                continue
            cand_frames = sorted(frames_dir.glob("*.png"))
            # Pick up to 3 well-spaced frames per video
            if len(cand_frames) > 3:
                idxs = [0, len(cand_frames) // 2, len(cand_frames) - 1]
                cand_frames = [cand_frames[i] for i in idxs]
            for fp in cand_frames:
                rgb = np.array(Image.open(fp).convert("RGB"))
                box = detect_tos_board(rgb)
                if box is None:
                    continue
                # Only keep very confident clean/enchanted/race tiles (skip rows with possible special hazards by thresholding high)
                add_annotated_board(
                    store,
                    fp,
                    group=f"ytdiv_{vid_dir.name[:16]}",
                    box=box,
                    skip_rows={4},
                    default_race=None,
                    min_hsv_conf=0.80,
                    only_annotated_special_or_clean=True,
                )

    return store


def save_qa_sheet(store: SampleStore, val_mask: np.ndarray, out_path: Path) -> None:
    """Render visual contact sheet of sampled tiles per class so humans can audit label purity."""
    groups_to_show: List[Tuple[str, np.ndarray]] = []
    el_arr = np.array(store.element)
    var_arr = np.array(store.variant)
    race_arr = np.array(store.race)
    haz_arr = np.stack(store.hazards, axis=0)
    ov_arr = np.stack(store.tile_overlay, axis=0)
    rune_arr = np.array(store.is_rune)

    for idx, name in enumerate(ELEMENT_NAMES[:7]):
        groups_to_show.append((f"EL:{name}", np.flatnonzero((el_arr == idx) & (haz_arr.sum(axis=1) == 0))))
    groups_to_show.append(("VAR:WEAKENED", np.flatnonzero(var_arr == VARIANT_TO_IDX["WEAKENED"])))
    for ridx in range(1, len(RACE_NAMES)):
        groups_to_show.append((f"RACE:{RACE_NAMES[ridx]}", np.flatnonzero((race_arr == ridx) & np.array(store.race_known))))
    for hidx, hname in enumerate(HAZARD_NAMES):
        inds = np.flatnonzero(haz_arr[:, hidx] > 0.5)
        if inds.size > 0:
            groups_to_show.append((f"HAZ:{hname}", inds))
    for oidx, oname in enumerate(TILE_OVERLAY_NAMES):
        inds = np.flatnonzero(ov_arr[:, oidx] > 0.5)
        if inds.size > 0:
            groups_to_show.append((f"OV:{oname}", inds))
    groups_to_show.append(("NEG:NON_RUNE", np.flatnonzero(rune_arr < 0.5)))

    cols = 8
    sz = 64
    pad = 4
    row_h = sz + 18
    non_empty = [(lbl, inds[:cols]) for lbl, inds in groups_to_show if inds.size > 0]
    canvas = Image.new("RGB", (180 + cols * (sz + pad), len(non_empty) * row_h + 16), (22, 24, 28))
    draw = ImageDraw.Draw(canvas)
    for row_i, (lbl, inds) in enumerate(non_empty):
        y = 8 + row_i * row_h
        draw.text((8, y + 22), f"{lbl} ({len(inds)})", fill=(230, 240, 255))
        for ci, s_idx in enumerate(inds):
            x = 176 + ci * (sz + pad)
            canvas.paste(Image.fromarray(store.images[s_idx]), (x, y))
            tag = "V" if val_mask[s_idx] else "T"
            draw.text((x + 2, y + 2), tag, fill=(0, 255, 160) if tag == "T" else (255, 220, 60))
    canvas.save(out_path, quality=92)


def main() -> int:
    out_dir = REPO_ROOT / "ml" / "datasets" / "phase4_v1"
    out_dir.mkdir(parents=True, exist_ok=True)

    store = build_all_samples()
    n = len(store.images)
    print(f"[INFO] Built {n} total tiles across {len(set(store.groups))} unique board/video groups")

    # Group-stratified deterministic ~80/20 split with priority to put at least some positive samples
    # in val for every rare hazard/race/overlay where >1 group exists!
    # Curated balanced validation groups ensuring EVERY multi-frame mechanic has both train AND val representation
    rng = np.random.default_rng(42)
    unique_groups = sorted(set(store.groups))
    val_groups: Set[str] = {
        g for g in (
            "bm_human_4", "bm_human_5",
            "vb_menglie", "vb_yongjue_elec",
            "vb_hundun_froz_2", "u2_mingtu_2",
            "vb_bomb_02_juewang", "u2_jie_bomb_2",
            "vb_moyan_burn_2",
            "vb_yongjue_bb_0", "vb_yongjue_bb_1",
            "vb_zuzhou_lum1",
            "u2_baolie_mag",
            "u2_baolie_bs2",
            "vb_moyan_weath",
            "vb_scorch_02_zuzhou_", "vb_scorch_03_rongyao_",
            "vb_zuz_bh2",
            "u3_evil_xueling_lingmi", "u3_evil_jinqian_huamei",
            "monochrome_30",
            "machina_race_25",
            "p4_demon_race_v2",
        )
        if g in unique_groups
    }
    # Add diverse background YouTube & clean control groups to ~22% val
    for g in unique_groups:
        if g.startswith("ytdiv_") or g.startswith("p4_clean_") or g.startswith("neg_popup_"):
            if int(rng.integers(0, 100)) < 22:
                val_groups.add(g)

    # Also ensure Coagulated has split: f011 + f012 in val (group prefix)
    is_val = np.array([
        (g in val_groups) or ("coag_fangtu_guangai_f011" in src) or ("coag_fangtu_guangai_f012" in src)
        for g, src in zip(store.groups, store.sources)
    ], dtype=bool)
    # Wait: simplify group naming for coag specs above? Let's keep clean group matching:
    for idx, src in enumerate(store.sources):
        if "fangtu_guangai_f011" in src or "fangtu_guangai_f012" in src:
            is_val[idx] = True
        elif "fangtu_guangai_f010" in src or "fangtu_guangai_f014" in src or "xunlian_adv_f022" in src:
            is_val[idx] = False
    is_train = ~is_val

    imgs = np.stack(store.images, axis=0)
    is_rune = np.array(store.is_rune, dtype=np.float32)
    element = np.array(store.element, dtype=np.int64)
    variant = np.array(store.variant, dtype=np.int64)
    race = np.array(store.race, dtype=np.int64)
    race_known = np.array(store.race_known, dtype=bool)
    hazards = np.stack(store.hazards, axis=0)
    hazard_mask = np.stack(store.hazard_mask, axis=0)
    tile_overlay = np.stack(store.tile_overlay, axis=0)
    overlay_mask = np.stack(store.overlay_mask, axis=0)
    groups_arr = np.array(store.groups)

    def save_split(mask: np.ndarray, fname: Path) -> None:
        np.savez_compressed(
            fname,
            images=imgs[mask],
            is_rune=is_rune[mask],
            element=element[mask],
            variant=variant[mask],
            race=race[mask],
            race_known=race_known[mask],
            hazards=hazards[mask],
            hazard_mask=hazard_mask[mask],
            tile_overlay=tile_overlay[mask],
            overlay_mask=overlay_mask[mask],
            groups=groups_arr[mask],
        )

    save_split(is_train, out_dir / "train.npz")
    save_split(is_val, out_dir / "val.npz")
    save_qa_sheet(store, is_val, out_dir / "qa_label_check.jpg")

    summary = {
        "total_tiles": int(n),
        "train_tiles": int(is_train.sum()),
        "val_tiles": int(is_val.sum()),
        "unique_groups": len(unique_groups),
        "val_groups": sorted(val_groups),
        "elements_train": {ELEMENT_NAMES[i]: int(((element == i) & is_train).sum()) for i in range(len(ELEMENT_NAMES))},
        "elements_val": {ELEMENT_NAMES[i]: int(((element == i) & is_val).sum()) for i in range(len(ELEMENT_NAMES))},
        "races_known": {RACE_NAMES[i]: int(((race == i) & race_known).sum()) for i in range(len(RACE_NAMES))},
        "hazards_total": {HAZARD_NAMES[i]: int((hazards[:, i] > 0.5).sum()) for i in range(NUM_HAZARDS)},
        "hazards_val": {HAZARD_NAMES[i]: int(((hazards[:, i] > 0.5) & is_val).sum()) for i in range(NUM_HAZARDS)},
        "overlays_total": {TILE_OVERLAY_NAMES[i]: int((tile_overlay[:, i] > 0.5).sum()) for i in range(NUM_TILE_OVERLAYS)},
        "overlays_val": {TILE_OVERLAY_NAMES[i]: int(((tile_overlay[:, i] > 0.5) & is_val).sum()) for i in range(NUM_TILE_OVERLAYS)},
    }
    (out_dir / "dataset_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
