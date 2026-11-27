"""
Build Stage 2A `phase4_v2` dual-frame full-board dataset (`[N, 320, 384, 3]` A & B + `[N, 30, 47]`
per-cell targets & per-head/bit validity masks) from:
  1. `ml/datasets/phase4_v1/{train,val}.npz` (exact 6,486 gold tiles reconstructed to 6x5 boards, preserving split!)
  2. Track B `backups/phase4_v2_inbox/`:
       - `race_missing/` (202 boards: DRAGON/GOD/ELF/DEMON/MACHINA/BEAST)
       - `hazards_p0/` (black_white, ice_sealed, purgatory_fire, shield_orb, enemy_marks: 290 boards)
       - `overlays_p0/` (traffic_light, barrier_char: 131 boards)
       - `hazards_p1_multiscene/` (119 boards: petrified, blood_drain, bomb, storm, shattered, magic_orb, etc.)
       - `collab_skins/` (120 boards across 8 skins)
       - `board_static_pairs/` (63 real `(board_a, board_b)` temporal flicker pairs)
  3. Hard negatives from `enemy_popups_live/` (23 real 1080x2400 dark popup screenshots -> `is_rune=0` boards)

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

from extract_tos_dataset import BoardBox, rgb_to_hsv_np  # noqa: E402
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
    VARIANT_TO_IDX,
)
from ml.tools.build_phase4_dataset import hsv_base_classify_cell  # noqa: E402

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


def _pack_phase4_v1_split(store: BoardSampleStore, npz_path: Path, is_val: bool) -> int:
    z = np.load(npz_path, allow_pickle=False)
    images = z["images"]        # [N, 64, 64, 3]
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

    # Separate positive/mixed battle groups vs pure negative is_rune==0 groups
    by_group: dict[str, list[int]] = defaultdict(list)
    for i, g in enumerate(groups.tolist()):
        by_group[str(g)].append(i)

    added = 0
    for grp, idxs in by_group.items():
        # Chunk into 30-cell 6x5 boards
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
                board_img[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64] = images[sidx]
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
                        tgt[ci, SLICE_RACE[0] + int(race[sidx])] = 1.0
                        rm_race[ci] = 1.0
                    tgt[ci, SLICE_HAZARDS[0]:SLICE_HAZARDS[1]] = hazards[sidx]
                    rm_haz[ci] = hmask[sidx]
                    tgt[ci, SLICE_TILE_OVERLAY[0]:SLICE_TILE_OVERLAY[1]] = overlays[sidx]
                    rm_ov[ci] = omask[sidx]

            # Fill empty trailing slots when chunk < 30 with mirrored clean tiles to avoid sharp black edges (masked out!)
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
                weight=3.6 if has_special else 2.8,
                group=f"v1/{grp}",
                source="phase4_v1",
                val=is_val,
            )
            added += 1
    return added


def _detect_scoped_cell_cues(board_rgb: np.ndarray, mode: str) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """
    Calibrated high-precision detectors strictly scoped to known Track B category folders.
    Returns (pos_mask[30], clean_neg_mask[30], conf[30]).
    """
    pos = np.zeros((NUM_CELLS,), dtype=bool)
    neg = np.zeros((NUM_CELLS,), dtype=bool)
    conf = np.zeros((NUM_CELLS,), dtype=np.float32)

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
                    conf[idx] = min(1.0, purp + spec)
                elif purp < 0.10 and float((ss >= 0.45).mean()) >= 0.45:
                    neg[idx] = True
            elif mode == "PURGATORY_FIRE":
                core = patch[16:48, 16:48]
                _, cs, cv = rgb_to_hsv_np(core)
                white_core = float(((cs <= 0.32) & (cv >= 0.74)).mean())
                mag_ring = float((((hh >= 275.0) & (hh <= 345.0)) & (ss >= 0.25) & (vv >= 0.35)).mean())
                if white_core >= 0.14 and mag_ring >= 0.22:
                    pos[idx] = True
                    conf[idx] = min(1.0, white_core + mag_ring)
                elif mag_ring < 0.08 and white_core < 0.10 and float((ss >= 0.42).mean()) >= 0.45:
                    neg[idx] = True
            elif mode == "TRAFFIC_LIGHT_MARKER":
                # Corners/edges show distinct bright emerald GO swirl or crimson RED vortex
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
                dark_void = float((vv <= 0.15).mean())
                sat_orb = float((ss >= 0.45).mean())
                if dark_void >= 0.62 and sat_orb <= 0.10:
                    pos[idx] = True
                elif sat_orb >= 0.45:
                    neg[idx] = True
    return pos, neg, conf


def _label_track_b_board(
    store: BoardSampleStore,
    img_a: np.ndarray,
    img_b: np.ndarray | None,
    group: str,
    source: str,
    is_val: bool,
    weight: float,
    race_label: str | None = None,
    full_bw: bool = False,
    scoped_cue: str | None = None,
    elem_conf_min: float = 0.68,
) -> None:
    tgt = np.zeros((NUM_CELLS, OUTPUT_DIM), dtype=np.float32)
    rm_is_rune = np.ones((NUM_CELLS,), dtype=np.float32)
    rm_elem = np.zeros((NUM_CELLS,), dtype=np.float32)
    rm_var = np.zeros((NUM_CELLS,), dtype=np.float32)
    rm_race = np.zeros((NUM_CELLS,), dtype=np.float32)
    rm_haz = np.zeros((NUM_CELLS, NUM_HAZARDS), dtype=np.float32)
    rm_ov = np.zeros((NUM_CELLS, NUM_TILE_OVERLAYS), dtype=np.float32)

    box = BoardBox(0, 0, BOARD_W, BOARD_H, -1, 1.0, 1.0, 1.0, 1.0)
    bw_idx = HAZARD_TO_IDX["BLACK_WHITE"]
    cue_pos = np.zeros((NUM_CELLS,), dtype=bool)
    cue_neg = np.zeros((NUM_CELLS,), dtype=bool)
    if scoped_cue:
        cue_pos, cue_neg, _ = _detect_scoped_cell_cues(img_a, scoped_cue)

    any_has_race = False
    for r in range(5):
        for c in range(6):
            ci = r * 6 + c
            tgt[ci, SLICE_IS_RUNE[0]] = 1.0

            if full_bw:
                tgt[ci, SLICE_HAZARDS[0] + bw_idx] = 1.0
                rm_haz[ci, bw_idx] = 1.0
                continue

            cx, cy = (c + 0.5) * CELL_PX, (r + 0.5) * CELL_PX
            el_name, el_conf, _, has_race = hsv_base_classify_cell(img_a, cx, cy, CELL_PX, CELL_PX)

            if scoped_cue and cue_pos[ci]:
                if scoped_cue in HAZARD_TO_IDX:
                    h_i = HAZARD_TO_IDX[scoped_cue]
                    tgt[ci, SLICE_HAZARDS[0] + h_i] = 1.0
                    rm_haz[ci, h_i] = 1.0
                elif scoped_cue in TILE_OVERLAY_TO_IDX:
                    o_i = TILE_OVERLAY_TO_IDX[scoped_cue]
                    tgt[ci, SLICE_TILE_OVERLAY[0] + o_i] = 1.0
                    rm_ov[ci, o_i] = 1.0
                continue
            elif scoped_cue and cue_neg[ci]:
                if scoped_cue in HAZARD_TO_IDX:
                    rm_haz[ci, HAZARD_TO_IDX[scoped_cue]] = 1.0
                elif scoped_cue in TILE_OVERLAY_TO_IDX:
                    rm_ov[ci, TILE_OVERLAY_TO_IDX[scoped_cue]] = 1.0

            if el_name in ELEMENT_TO_IDX and el_conf >= elem_conf_min:
                tgt[ci, SLICE_ELEMENT[0] + ELEMENT_TO_IDX[el_name]] = 1.0
                rm_elem[ci] = 1.0

            if race_label:
                if has_race:
                    any_has_race = True
                    tgt[ci, SLICE_RACE[0] + RACE_TO_IDX[race_label]] = 1.0
                    rm_race[ci] = 1.0
                    tgt[ci, SLICE_VARIANT[0] + VARIANT_TO_IDX["RACE"]] = 1.0
                    rm_var[ci] = 1.0
                elif el_conf >= 0.72:
                    tgt[ci, SLICE_RACE[0] + RACE_TO_IDX["NONE"]] = 1.0
                    rm_race[ci] = 1.0

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


def build_phase4_v2_dataset(
    inbox_dir: Path = Path("backups/phase4_v2_inbox"),
    v1_dir: Path = Path("ml/datasets/phase4_v1"),
    out_dir: Path = Path("ml/datasets/phase4_v2"),
    seed: int = 20261127,
) -> dict[str, Any]:
    rng = random.Random(seed)
    out_dir.mkdir(parents=True, exist_ok=True)
    store = BoardSampleStore()

    # 1. Reconstruct phase4_v1 gold boards preserving EXACT train/val split
    v1_tr = _pack_phase4_v1_split(store, v1_dir / "train.npz", is_val=False)
    v1_va = _pack_phase4_v1_split(store, v1_dir / "val.npz", is_val=True)

    # Helper to assign deterministic 80/20 subbatch splits
    def split_subbatches(paths_by_sub: dict[str, list[Path]], min_val_subs: int = 1) -> set[str]:
        subs = sorted(k for k, v in paths_by_sub.items() if v)
        if len(subs) <= 1:
            return set()
        rng.shuffle(subs)
        n_val = max(min_val_subs, round(len(subs) * 0.22))
        n_val = min(n_val, len(subs) - 1)
        return set(subs[:n_val])

    # 2. Track B race_missing (202 boards across 6 races — chronological per-subfolder 78/22 split
    #    so every subfolder with real race badges contributes to BOTH train and val!)
    race_tr = race_va = 0
    for race_sub, race_name in RACE_FOLDER_TO_NAME.items():
        rdir = inbox_dir / "race_missing" / race_sub
        if not rdir.is_dir():
            continue
        by_sub: dict[str, list[Path]] = defaultdict(list)
        for p in sorted(rdir.rglob("boards/*.png")):
            by_sub[p.parents[1].name].append(p)
        for sub_name, paths in sorted(by_sub.items()):
            val_start = max(1, int(round(len(paths) * 0.78)))
            if len(paths) >= 2 and val_start >= len(paths):
                val_start = len(paths) - 1
            for bi, p in enumerate(paths):
                board_val = (len(paths) >= 2) and (bi >= val_start)
                img = _load_board_rgb(p)
                if img is None:
                    continue
                _label_track_b_board(
                    store,
                    img,
                    None,
                    group=f"race/{race_sub}/{sub_name}/{'val' if board_val else 'trn'}",
                    source=f"race_missing/{race_sub}/{sub_name}/{p.name}",
                    is_val=board_val,
                    weight=2.2 if race_name == "DRAGON" else 1.8,
                    race_label=race_name,
                )
                if board_val:
                    race_va += 1
                else:
                    race_tr += 1

    # 3. Track B hazards_p0
    haz_tr = haz_va = 0
    for cat_name, scoped, is_bw in [
        ("black_white", None, True),
        ("ice_sealed", "ICE_SEALED", False),
        ("purgatory_fire", "PURGATORY_FIRE", False),
        ("shield_orb", None, False),
        ("enemy_marks", None, False),
    ]:
        cdir = inbox_dir / "hazards_p0" / cat_name
        if not cdir.is_dir():
            continue
        by_sub = defaultdict(list)
        for p in sorted(cdir.rglob("boards/*.png")):
            by_sub[p.parents[1].name].append(p)
        val_subs = split_subbatches(by_sub)
        for sub_name, paths in by_sub.items():
            is_v = sub_name in val_subs
            for p in paths:
                img = _load_board_rgb(p)
                if img is None:
                    continue
                _label_track_b_board(
                    store,
                    img,
                    None,
                    group=f"haz_p0/{cat_name}/{sub_name}",
                    source=f"hazards_p0/{cat_name}/{sub_name}/{p.name}",
                    is_val=is_v,
                    weight=2.2 if (is_bw or scoped) else 1.6,
                    full_bw=is_bw,
                    scoped_cue=scoped,
                    elem_conf_min=0.72,
                )
                if is_v:
                    haz_va += 1
                else:
                    haz_tr += 1

    # 4. Track B overlays_p0
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
                if img is None:
                    continue
                _label_track_b_board(
                    store,
                    img,
                    None,
                    group=f"ov_p0/{cat_name}/{sub_name}",
                    source=f"overlays_p0/{cat_name}/{sub_name}/{p.name}",
                    is_val=is_v,
                    weight=1.8,
                    scoped_cue=scoped,
                )
                if is_v:
                    ov_va += 1
                else:
                    ov_tr += 1

    # 5. Track B hazards_p1_multiscene (119 boards)
    p1_tr = p1_va = 0
    p1_dir = inbox_dir / "hazards_p1_multiscene"
    if p1_dir.is_dir():
        by_sub = defaultdict(list)
        for p in sorted(p1_dir.rglob("boards/*.png")):
            by_sub[p.parents[1].name].append(p)
        val_subs = split_subbatches(by_sub, min_val_subs=2)
        for sub_name, paths in by_sub.items():
            is_v = sub_name in val_subs
            scoped = None
            if "blood_thorn" in sub_name:
                scoped = "BLOOD_DRAIN_LOCKED"
            elif "storm" in sub_name:
                scoped = "STORM"
            elif "shatter" in sub_name:
                scoped = "SHATTERED"
            for p in paths:
                img = _load_board_rgb(p)
                if img is None:
                    continue
                _label_track_b_board(
                    store,
                    img,
                    None,
                    group=f"haz_p1/{sub_name}",
                    source=f"hazards_p1/{sub_name}/{p.name}",
                    is_val=is_v,
                    weight=1.6,
                    scoped_cue=scoped,
                )
                if is_v:
                    p1_va += 1
                else:
                    p1_tr += 1

    # 6. Track B collab_skins (120 boards across 8 skins)
    col_tr = col_va = 0
    col_dir = inbox_dir / "collab_skins"
    if col_dir.is_dir():
        for skin_dir in sorted(d for d in col_dir.iterdir() if d.is_dir()):
            paths = sorted(skin_dir.rglob("boards/*.png"))
            val_cut = max(1, round(len(paths) * 0.22))
            for bi, p in enumerate(paths):
                is_v = bi < val_cut
                img = _load_board_rgb(p)
                if img is None:
                    continue
                _label_track_b_board(
                    store,
                    img,
                    None,
                    group=f"collab/{skin_dir.name}",
                    source=f"collab/{skin_dir.name}/{p.name}",
                    is_val=is_v,
                    weight=1.5,
                    elem_conf_min=0.72,
                )
                if is_v:
                    col_va += 1
                else:
                    col_tr += 1

    # 7. Track B board_static_pairs (63 real (board_a, board_b) flicker pairs)
    pair_tr = pair_va = 0
    pm = json.loads((inbox_dir / "board_static_pairs" / "manifest.json").read_text(encoding="utf-8"))
    board_pair_items = [it for it in pm["items"] if "_board_a" in it["pair_a"]]
    rng.shuffle(board_pair_items)
    val_cut = max(8, round(len(board_pair_items) * 0.22))
    for pi, it in enumerate(board_pair_items):
        is_v = pi < val_cut
        pa = inbox_dir / "board_static_pairs" / it["pair_a"]
        pb = inbox_dir / "board_static_pairs" / it["pair_b"]
        ia = _load_board_rgb(pa)
        ib = _load_board_rgb(pb)
        if ia is None or ib is None:
            continue
        _label_track_b_board(
            store,
            ia,
            ib,
            group=f"static_pair/{pa.parent.name}_{pi // 3}",
            source=it["pair_a"],
            is_val=is_v,
            weight=2.2,
            elem_conf_min=0.72,
        )
        if is_v:
            pair_va += 1
        else:
            pair_tr += 1

    # 8. Hard Negatives from A2 live enemy popups (non-board dark screens)
    neg_tr = neg_va = 0
    popup_imgs = sorted((inbox_dir / "enemy_popups_live" / "emu_live_calibration_v1").glob("*.png"))
    for pi, p in enumerate(popup_imgs):
        im = Image.open(p).convert("RGB")
        w, h = im.size
        crop = np.asarray(im.crop((0, int(h * 0.40), w, int(h * 0.90))).resize((BOARD_W, BOARD_H)), dtype=np.uint8)
        tgt = np.zeros((NUM_CELLS, OUTPUT_DIM), dtype=np.float32)  # is_rune = 0.0
        rm_is_rune = np.ones((NUM_CELLS,), dtype=np.float32)
        z1 = np.zeros((NUM_CELLS,), dtype=np.float32)
        is_v = (pi % 5 == 0)
        store.add_board(
            crop,
            None,
            tgt,
            rm_is_rune,
            z1,
            z1,
            z1,
            np.zeros((NUM_CELLS, NUM_HAZARDS), dtype=np.float32),
            np.zeros((NUM_CELLS, NUM_TILE_OVERLAYS), dtype=np.float32),
            weight=2.0,
            group=f"neg_a2/{p.stem}",
            source=p.name,
            val=is_v,
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

    # Visual QA sheet
    qa_picks = [i for i in range(len(boards_a)) if i % max(1, len(boards_a) // 16) == 0][:16]
    qa = Image.new("RGB", (BOARD_W * 2 + 24, len(qa_picks) * (BOARD_H // 2 + 8) + 8), (18, 20, 26))
    draw = ImageDraw.Draw(qa)
    for row_i, idx in enumerate(qa_picks):
        y = 4 + row_i * (BOARD_H // 2 + 8)
        qa.paste(Image.fromarray(boards_a[idx]).resize((BOARD_W // 2, BOARD_H // 2)), (4, y))
        qa.paste(Image.fromarray(boards_b[idx]).resize((BOARD_W // 2, BOARD_H // 2)), (BOARD_W // 2 + 12, y))
        tag = "VAL" if val_mask[idx] else "TRN"
        draw.text((BOARD_W + 20, y + 50), f"[{tag}] {store.groups[idx]}\npair={bool(has_real_pair[idx])} w={sw[idx]:.1f}", fill=(160, 240, 180))
    qa.save(out_dir / "qa_board_check.jpg", quality=90)

    summary = {
        "total_boards": int(len(boards_a)),
        "total_cells": int(len(boards_a) * NUM_CELLS),
        "train_boards": int(tr_mask.sum()),
        "val_boards": int(val_mask.sum()),
        "real_dual_frame_pairs": int(has_real_pair.sum()),
        "breakdown": {
            "phase4_v1": {"train": v1_tr, "val": v1_va},
            "race_missing": {"train": race_tr, "val": race_va},
            "hazards_p0": {"train": haz_tr, "val": haz_va},
            "overlays_p0": {"train": ov_tr, "val": ov_va},
            "hazards_p1_multiscene": {"train": p1_tr, "val": p1_va},
            "collab_skins": {"train": col_tr, "val": col_va},
            "board_static_pairs": {"train": pair_tr, "val": pair_va},
            "neg_a2_popups": {"train": neg_tr, "val": neg_va},
        },
        "races_val_cells": {RACE_NAMES[i]: int(((targets[val_mask, :, SLICE_RACE[0] + i] > 0.5) & (rm_race[val_mask] > 0.5)).sum()) for i in range(NUM_RACES)},
        "hazards_total_pos": {HAZARD_NAMES[i]: int(((targets[:, :, SLICE_HAZARDS[0] + i] > 0.5) & (rm_haz[:, :, i] > 0.5)).sum()) for i in range(NUM_HAZARDS)},
        "overlays_total_pos": {TILE_OVERLAY_NAMES[i]: int(((targets[:, :, SLICE_TILE_OVERLAY[0] + i] > 0.5) & (rm_ov[:, :, i] > 0.5)).sum()) for i in range(NUM_TILE_OVERLAYS)},
    }
    (out_dir / "dataset_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main() -> None:
    s = build_phase4_v2_dataset()
    print(
        f"Phase4_v2 ready: total_boards={s['total_boards']} ({s['total_cells']} cells), "
        f"train={s['train_boards']}, val={s['val_boards']}, real_pairs={s['real_dual_frame_pairs']}"
    )


if __name__ == "__main__":
    main()
