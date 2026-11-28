"""
Build Stage 2B full-spectrum EnemyIconNet dataset (strictly read-only on `backups/`):
  1. Maps ALL solver-relevant official icons in `enemy_icon_taxonomy.json` (1,632 PNGs) +
     98 official `tos_encyclopedia/enemy_skills/skill_*/icon.png` into the 36 `ENEMY_ICON_CLASSES`,
     with explicit `ICON_ID_OVERRIDES` rescuing 16 official board icons (`ICON099` silver heart
     monochrome, `ICON202` disguise, `ICON153` traffic light, `ICON432/1339/1609` step limit,
     `ICON533` recovery reversal, `ICON693/722/835..837` bloodbath/scorching, `ICON1511` char terrain,
     `ICON811` buoy, `ICON1549/1378` no-cascade) that were mislabeled in `enemy_icon_taxonomy.json`!
  2. Flood-fills transparent backgrounds on all `tos_encyclopedia/enemy_skills/skill_*/icon.png` cards
     and renders authentic right-side `NUM 17` (`[NUM] 17`) & `>=5 COM` live battle layouts.
  3. Adds icon-scrubbed real battle backgrounds + synthetic AutoDora top HUD capsules + boss lightning
     hard-negative arcs so `EnemyIconNet` achieves zero false positives on live battle effects.
"""

from __future__ import annotations

from collections import defaultdict, deque
import json
import random
import sys
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from ml.enemy_icon_arch import (  # noqa: E402
    ENEMY_ICON_CLASSES,
    GRID_H,
    GRID_W,
    NUM_ENEMY_ICON_CLASSES,
    STRIP_H,
    STRIP_W,
)

# Explicit corrections for official icons mislabeled as NON_BOARD or wrong class in `enemy_icon_taxonomy.json`
ICON_ID_OVERRIDES = {
    "ICON099": "ICON_MONOCHROME_DISGUISE",   # Canonical silver-heart Monochrome (`幽閉空間`)!
    "ICON628": "ICON_MONOCHROME_DISGUISE",
    "ICON202": "ICON_MONOCHROME_DISGUISE",   # Canonical Disguise (`移形換影`)!
    "ICON815": "ICON_MONOCHROME_DISGUISE",
    "ICON153": "ICON_TRAFFIC_LIGHT",         # Canonical Traffic Light (`起結有序`)!
    "ICON432": "ICON_STEP_LIMIT",            # Canonical Step Shield (`步數盾`)!
    "ICON1339": "ICON_STEP_LIMIT",           # Canonical Step Limit (`步數限制`)!
    "ICON1609": "ICON_STEP_LIMIT",
    "ICON533": "ICON_FORBID_ALL",            # Canonical Recovery Reversal (`回復顛倒`)!
    "ICON693": "ICON_TERRAIN_HAZARD_HINT",   # Bloodbath terrain (`血浴地型`)
    "ICON722": "ICON_TERRAIN_HAZARD_HINT",   # Scorching terrain (`灼熱地型`)
    "ICON835": "ICON_TERRAIN_HAZARD_HINT",
    "ICON836": "ICON_TERRAIN_HAZARD_HINT",
    "ICON837": "ICON_TERRAIN_HAZARD_HINT",
    "ICON1511": "ICON_CHAR_ORB_TERRAIN",     # Char Orb Pass terrain (`角色符石通行地型`)
    "ICON811": "ICON_STARTING_BUOY",         # Starting Buoy (`起動浮標`)
    "ICON1549": "ICON_NO_CASCADE",           # Cold Qi (`寒之氣`)
    "ICON1378": "ICON_NO_CASCADE",           # First-wave only (`只能消除首批符石`)
    "ICON1472": "ICON_EXACT_COMBO",          # Exact Combo Seal (`指定連擊法印`)
    "ICON832": "ICON_ANTI_FIRST_WAVE",       # Zero-combo elemental anti-wave1 (`符石連擊零化`)
    "ICON831": "ICON_ANTI_FIRST_WAVE",
}

CHIP_TO_ICON_CLASS = {
    "puzzle_shield": "ICON_PUZZLE",
    "exact_first_combo": "ICON_EXACT_COMBO",
    "exact_combo": "ICON_EXACT_COMBO",
    "min_combo": "ICON_MIN_COMBO",
    "min_first_combo": "ICON_MIN_COMBO",
    "min_first_elem_combo": "ICON_MIN_COMBO",
    "no_cascade": "ICON_NO_CASCADE",
    "first_wave_only": "ICON_NO_CASCADE",
    "anti_first_wave": "ICON_ANTI_FIRST_WAVE",
    "forbid_all": "ICON_FORBID_ALL",
    "five_elements": "ICON_FIVE_ELEMENTS",
    "five_elements_enchanted": "ICON_FIVE_ELEMENTS",
    "three_elements": "ICON_THREE_ELEMENTS",
    "any_enchanted_orb_shield": "ICON_ENCHANTED_BREAKTHROUGH",
    "cross_shield": "ICON_CROSS_SHIELD",
    "l_shape": "ICON_L_SHAPE",
    "t_shape": "ICON_T_SHAPE",
    "square_2x2": "ICON_SQUARE_2X2",
    "digital_orb_count": "ICON_DIGITAL_COUNT",
    "first_wave_full_clear": "ICON_FULL_CLEAR",
    "clear_all_element": "ICON_FULL_CLEAR",
    "race_orb_shield": "ICON_RACE_SHIELD",
    "unmatchable_elements": "ICON_UNMATCHABLE",
    "step_limit": "ICON_STEP_LIMIT",
    "step_range": "ICON_STEP_LIMIT",
    "traffic_light": "ICON_TRAFFIC_LIGHT",
    "char_orb_first_wave": "ICON_CHAR_ORB_TERRAIN",
    "char_orb_terrain": "ICON_CHAR_ORB_TERRAIN",
    "starting_buoy_shield": "ICON_STARTING_BUOY",
    "zero_trail_burning": "ICON_BURNING_TRAIL",
    "trail_corrosion": "ICON_CORROSION_TRAIL",
    "max_combo": "ICON_MAX_COMBO",
    "same_combo_elements": "ICON_EQUAL_COMBO",
    "equal_pair_light_dark": "ICON_EQUAL_COMBO",
    "all_columns_ameliorate": "ICON_COLUMN_CLEAR",
    "luminous_orb_shield": "ICON_LUMINOUS",
    "forbid_weakened_orb": "ICON_WEAKENED_FORBID",
    "min_orb_group_size": "ICON_ORB_GROUP_SIZE",
    "wave1_distinct_kind": "ICON_WAVE1_KIND",
    "wave1_same_kind": "ICON_WAVE1_KIND",
    "elem_burst_shield": "ICON_ELEM_BURST",
}

SEM_TO_ICON_CLASS = {
    "black_white": "ICON_MONOCHROME_DISGUISE",
    "disguise": "ICON_MONOCHROME_DISGUISE",
    "burning": "ICON_BURNING_TRAIL",
    "sticky": "ICON_CORROSION_TRAIL",
    "trail_corrosion": "ICON_CORROSION_TRAIL",
    "enchanted": "ICON_ENCHANTED_BREAKTHROUGH",
    "multi_elements": "ICON_THREE_ELEMENTS",
    "enchanted_elements": "ICON_THREE_ELEMENTS",
    "six_elements": "ICON_FIVE_ELEMENTS",
    "puzzle": "ICON_PUZZLE",
    "race_puzzle": "ICON_PUZZLE",
    "traffic_light": "ICON_TRAFFIC_LIGHT",
    "char_orb": "ICON_CHAR_ORB_TERRAIN",
    "starting_buoy": "ICON_STARTING_BUOY",
    "cross": "ICON_CROSS_SHIELD",
    "l_shape": "ICON_L_SHAPE",
    "t_shape": "ICON_T_SHAPE",
    "square_2x2": "ICON_SQUARE_2X2",
    "digital_orb_count": "ICON_DIGITAL_COUNT",
    "step_limit": "ICON_STEP_LIMIT",
    "step_range": "ICON_STEP_LIMIT",
    "no_cascade": "ICON_NO_CASCADE",
    "first_wave_only": "ICON_NO_CASCADE",
    "max_combo_N": "ICON_MAX_COMBO",
    "equal_combo": "ICON_EQUAL_COMBO",
    "column_clear": "ICON_COLUMN_CLEAR",
    "luminous": "ICON_LUMINOUS",
    "weakened": "ICON_WEAKENED_FORBID",
    "orb_group_size": "ICON_ORB_GROUP_SIZE",
    "wave1_distinct_kind": "ICON_WAVE1_KIND",
    "wave1_same_kind": "ICON_WAVE1_KIND",
    "electrified": "ICON_ELECTRIFIED_HINT",
    "frozen": "ICON_FROZEN_PETRIFIED_HINT",
    "petrified": "ICON_FROZEN_PETRIFIED_HINT",
    "ice_sealed": "ICON_FROZEN_PETRIFIED_HINT",
    "coagulated": "ICON_FROZEN_PETRIFIED_HINT",
    "weathered": "ICON_WEATHERED_BOMB_HINT",
    "bomb": "ICON_WEATHERED_BOMB_HINT",
    "locked_orbs": "ICON_WEATHERED_BOMB_HINT",
    "thorn": "ICON_WEATHERED_BOMB_HINT",
    "blood_thorn": "ICON_WEATHERED_BOMB_HINT",
    "barrier": "ICON_TERRAIN_HAZARD_HINT",
    "black_hole": "ICON_TERRAIN_HAZARD_HINT",
    "storm": "ICON_TERRAIN_HAZARD_HINT",
    "shattered": "ICON_TERRAIN_HAZARD_HINT",
    "scorching": "ICON_TERRAIN_HAZARD_HINT",
    "purgatory_fire": "ICON_TERRAIN_HAZARD_HINT",
    "bloodbath": "ICON_TERRAIN_HAZARD_HINT",
    "hidden_orbs": "ICON_TERRAIN_HAZARD_HINT",
    "fog": "ICON_TERRAIN_HAZARD_HINT",
    "question_orbs": "ICON_TERRAIN_HAZARD_HINT",
}

ENC_SKILL_TO_ICON_CLASS = {
    1: "ICON_PUZZLE",
    3: "ICON_ENCHANTED_BREAKTHROUGH",
    4: "ICON_THREE_ELEMENTS",
    5: "ICON_THREE_ELEMENTS",
    6: "ICON_THREE_ELEMENTS",
    7: "ICON_FIVE_ELEMENTS",
    8: "ICON_EXACT_COMBO",
    9: "ICON_EQUAL_COMBO",
    10: "ICON_ANTI_FIRST_WAVE",
    11: "ICON_FULL_CLEAR",
    12: "ICON_FULL_CLEAR",
    13: "ICON_DIGITAL_COUNT",
    14: "ICON_CROSS_SHIELD",
    15: "ICON_T_SHAPE",
    16: "ICON_L_SHAPE",
    18: "ICON_MIN_COMBO",
    21: "ICON_EXACT_COMBO",
    23: "ICON_UNMATCHABLE",
    25: "ICON_BURNING_TRAIL",
    26: "ICON_CORROSION_TRAIL",
    28: "ICON_TERRAIN_HAZARD_HINT",
    29: "ICON_TRAFFIC_LIGHT",
    30: "ICON_TRAFFIC_LIGHT",
    31: "ICON_TERRAIN_HAZARD_HINT",
    32: "ICON_TERRAIN_HAZARD_HINT",
    35: "ICON_TERRAIN_HAZARD_HINT",
    36: "ICON_TERRAIN_HAZARD_HINT",
    44: "ICON_TERRAIN_HAZARD_HINT",
    46: "ICON_ANTI_FIRST_WAVE",
    48: "ICON_MONOCHROME_DISGUISE",
    49: "ICON_MONOCHROME_DISGUISE",
    54: "ICON_CHAR_ORB_TERRAIN",
    57: "ICON_WAVE1_KIND",
    58: "ICON_WAVE1_KIND",
    59: "ICON_TERRAIN_HAZARD_HINT",
    60: "ICON_STEP_LIMIT",
    61: "ICON_STEP_LIMIT",
    62: "ICON_FORBID_ALL",
    66: "ICON_NO_CASCADE",
    67: "ICON_ELEM_BURST",
    78: "ICON_CHAR_ORB_TERRAIN",
    81: "ICON_NO_CASCADE",
    87: "ICON_STARTING_BUOY",
    96: "ICON_TERRAIN_HAZARD_HINT",
}

# Canonical must-train core icons (never hold out the sole silver-heart or 3-element shield archetype!)
CANONICAL_MUST_TRAIN_IDS = {
    "ICON099", "ENC_skill_048", "ICON202", "ENC_skill_049",
    "ICON092", "ICON093", "ICON187", "ENC_skill_005", "ENC_skill_006",
    "ICON051", "ENC_skill_001", "ICON1427", "ENC_skill_013", "ICON1376",
    "ICON153", "ENC_skill_030", "ICON432", "ICON1339", "ICON1472",
    "ICON103", "ICON104", "ICON105", "ICON106", "ICON163", "ICON164", "ICON165",
}

CLASS_TO_IDX = {name: i for i, name in enumerate(ENEMY_ICON_CLASSES)}
SPRITE_H_BOX = 20
SPRITE_W_BOX = 32  # Supports wide `NUM 17` (`[NUM] 17`) side-by-side badges!

_FONT_CACHE: dict[int, Any] = {}


def _get_font(size: int) -> Any:
    if size not in _FONT_CACHE:
        for candidate in ("C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/calibrib.ttf", "C:/Windows/Fonts/segoeui.ttf"):
            if Path(candidate).is_file():
                try:
                    _FONT_CACHE[size] = ImageFont.truetype(candidate, size=size)
                    break
                except Exception:
                    pass
        if size not in _FONT_CACHE:
            _FONT_CACHE[size] = ImageFont.load_default()
    return _FONT_CACHE[size]


def _draw_outlined_text(
    draw: ImageDraw.ImageDraw,
    xy: tuple[int, int],
    text: str,
    font: Any,
    fill: tuple[int, int, int, int],
    outline: tuple[int, int, int, int] = (8, 8, 10, 255),
    stroke: int = 2,
) -> None:
    x, y = xy
    for dx in range(-stroke, stroke + 1):
        for dy in range(-stroke, stroke + 1):
            if dx * dx + dy * dy <= stroke * stroke + 1:
                draw.text((x + dx, y + dy), text, font=font, fill=outline)
    draw.text((x, y), text, font=font, fill=fill)


def _scrub_full_width_enemy_band_528x384(arr: np.ndarray) -> np.ndarray:
    """
    Scrubs pre-existing enemy `CD` & mini-icons strictly inside the upper icon band `y in [24..246]`
    across the full width `x in [0..528]`, while preserving `y in [250..384]` (lower monster bodies,
    glowing shells/tentacles, enemy HP bars, companion sprite, and team portraits) 100% RAZOR-SHARP!
    """
    blurred = np.asarray(
        Image.fromarray(arr).filter(ImageFilter.GaussianBlur(radius=9.5)),
        dtype=np.float32,
    )
    orig = arr.astype(np.float32)
    alpha_y = np.zeros((384, 1, 1), dtype=np.float32)
    alpha_y[26:236, 0, 0] = 1.0
    alpha_y[12:26, 0, 0] = np.linspace(0.0, 1.0, 14, dtype=np.float32)
    alpha_y[236:254, 0, 0] = np.linspace(1.0, 0.0, 18, dtype=np.float32)
    mixed = np.clip(orig * (1.0 - alpha_y) + blurred * alpha_y, 0.0, 255.0).astype(np.uint8)
    out = Image.fromarray(mixed).resize((STRIP_W, STRIP_H), Image.Resampling.BOX)
    return np.asarray(out, dtype=np.uint8)


def _extract_clean_upper_bg_160x256(img_path: Path) -> np.ndarray | None:
    """
    Uses exact periodicity-verified board `[x0, x1]` on 16:9 YouTube frames (and full width on
    native portrait frames `w <= 0.72*h`) so `[0.04..0.96] x [0.08..0.42]` matches the live phone
    aspect ratio 1:1 and full-width scrubbing removes 100% of real in-battle CD & shield icons!
    """
    from ml.tools.extract_tos_dataset import detect_tos_board  # noqa: E402
    from ml.tools.build_phase4_v2_board_dataset import has_6x5_grid_periodicity  # noqa: E402

    try:
        im = Image.open(img_path).convert("RGB")
    except Exception:
        return None
    fw, fh = im.size
    if fw < 200 or fh < 320:
        return None

    if fw <= int(fh * 0.72):
        vx0, vx1 = 0, fw
    else:
        rgb_full = np.asarray(im, dtype=np.uint8)
        box = detect_tos_board(rgb_full)
        if box is None:
            return None
        bw0 = box.width
        cx0 = 0.5 * (box.left + box.right)
        best_span: tuple[int, int] | None = None
        best_sc = -1e9
        for w_scale in (0.94, 0.98, 1.00, 1.02, 1.06):
            bw = int(round(bw0 * w_scale))
            bh = int(round(bw * 5.0 / 6.0))
            if bw < 160 or bh > fh:
                continue
            for dx_frac in (-0.04, -0.02, 0.0, 0.02, 0.04):
                x0 = int(round(cx0 - bw * 0.5 + dx_frac * bw0))
                x1 = x0 + bw
                if x0 < 0 or x1 > fw or box.top + bh > fh:
                    continue
                cand = np.asarray(
                    im.crop((x0, box.top, x1, box.top + bh)).resize((384, 320), Image.Resampling.BILINEAR),
                    dtype=np.uint8,
                )
                ok, sc = has_6x5_grid_periodicity(cand)
                if ok and sc > best_sc:
                    best_sc = sc
                    best_span = (x0, x1)
        if best_span is None:
            return None
        vx0, vx1 = best_span

    vp = im.crop((vx0, 0, vx1, fh))
    w, h = vp.size
    crop = vp.crop((int(w * 0.04), int(h * 0.08), int(w * 0.96), int(h * 0.42))).resize(
        (528, 384), Image.Resampling.BILINEAR
    )
    arr = np.asarray(crop, dtype=np.uint8).copy()
    if arr.mean() < 10.0 or arr.mean() > 242.0:
        return None
    return _scrub_full_width_enemy_band_528x384(arr)


def _scrub_precropped_upper_strip(strip_path: Path) -> np.ndarray | None:
    """Loads a pre-cropped `upper_strips/*_strip.{png,webp}` and scrubs the full enemy icon band."""
    try:
        im = Image.open(strip_path).convert("RGB").resize((528, 384), Image.Resampling.BILINEAR)
    except Exception:
        return None
    arr = np.asarray(im, dtype=np.uint8).copy()
    if arr.mean() < 10.0 or arr.mean() > 242.0:
        return None
    return _scrub_full_width_enemy_band_528x384(arr)


def _clean_enc_icon_rgba(im: Image.Image) -> np.ndarray:
    """
    Crops away the encyclopedia card border `[11%..89%]` -> `48x48` and flood-fills transparent
    any outer olive-grey card background (`RGB ~ [65, 65, 50]`) outside the icon's black outline!
    """
    w, h = im.size
    inner = im.convert("RGBA").crop((int(w * 0.11), int(h * 0.11), int(w * 0.89), int(h * 0.89))).resize(
        (48, 48), Image.Resampling.BILINEAR
    )
    arr = np.asarray(inner, dtype=np.uint8).copy()
    if int(arr[..., 3].min()) < 200:
        return arr

    # Estimate background color from the 4 corners of the 48x48 crop
    corners = np.stack([arr[0, 0, :3], arr[0, 47, :3], arr[47, 0, :3], arr[47, 47, :3]], axis=0).astype(np.float32)
    bg_rgb = corners.mean(axis=0)
    visited = np.zeros((48, 48), dtype=bool)
    q: deque[tuple[int, int]] = deque()
    for x in range(48):
        for y in (0, 47):
            q.append((y, x))
            visited[y, x] = True
    for y in range(48):
        for x in (0, 47):
            if not visited[y, x]:
                q.append((y, x))
                visited[y, x] = True

    while q:
        cy, cx = q.popleft()
        pix = arr[cy, cx, :3].astype(np.float32)
        if float(np.abs(pix - bg_rgb).mean()) <= 26.0:
            arr[cy, cx, 3] = 0
            for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                ny, nx = cy + dy, cx + dx
                if 0 <= ny < 48 and 0 <= nx < 48 and not visited[ny, nx]:
                    visited[ny, nx] = True
                    q.append((ny, nx))
    return arr


def _rasterize_strip_sprite(cls_name: str, base_48: np.ndarray, rng: random.Random) -> tuple[np.ndarray, int, int]:
    """
    Renders an icon (including authentic right-side `[NUM] 17` and `>=5 COM` badges) downsampled
    to exact `[160, 256]` strip dimensions inside a `[20, 32, 4]` buffer.
    """
    sw = rng.randint(9, 18)
    aspect_ratio = rng.uniform(1.04, 1.36)
    sh = max(8, min(17, int(round(sw / aspect_ratio))))
    buf = np.zeros((SPRITE_H_BOX, SPRITE_W_BOX, 4), dtype=np.uint8)

    if cls_name == "ICON_DIGITAL_COUNT" and rng.random() < 0.80:
        # Authentic live ToS layout (`vid2`): `[NUM shield]` on left + bold white `17` on right (`92x48` -> `~25x12`)!
        wide = Image.new("RGBA", (92, 48), (0, 0, 0, 0))
        wide.alpha_composite(Image.fromarray(base_48, mode="RGBA"), (0, 0))
        d = ImageDraw.Draw(wide)
        num_val = rng.choice([6, 7, 8, 9, 10, 12, 14, 15, 16, 17, 18, 19, 20, 22, 24, 25, 28, 30])
        _draw_outlined_text(d, (46, 8), str(num_val), _get_font(28 if num_val >= 10 else 30), fill=(255, 255, 255, 255), stroke=3)
        tot_w = min(SPRITE_W_BOX, int(round(sw * 1.85)))
        sm = wide.resize((tot_w, sh), Image.Resampling.BILINEAR)
        buf[:sh, :tot_w] = np.asarray(sm, dtype=np.uint8)
        return buf, tot_w, sh

    # Official `icons_png_all` already contains 116 real ToS serif icons for >=N / <=N / =N COM across all numbers!
    # Preserving the authentic ToS serif PNGs directly (without overwriting with Arial) ensures 1:1 live font fidelity.
    ic = Image.fromarray(base_48, mode="RGBA")
    ic_sm = ic.resize((sw, sh), Image.Resampling.BILINEAR)
    buf[:sh, :sw] = np.asarray(ic_sm, dtype=np.uint8)
    return buf, sw, sh


def _build_cd_sprites() -> np.ndarray:
    out = []
    for cd_num in (1, 2, 3):
        hi = Image.new("RGBA", (54, 24), (0, 0, 0, 0))
        d = ImageDraw.Draw(hi)
        _draw_outlined_text(d, (2, 4), "CD", _get_font(14), fill=(235, 72, 58, 255), stroke=2)
        _draw_outlined_text(d, (27, 2), str(cd_num), _get_font(17), fill=(255, 240, 220, 255), stroke=2)
        sm = hi.resize((24, 10), Image.Resampling.BILINEAR)
        out.append(np.asarray(sm, dtype=np.uint8))
    return np.stack(out, axis=0)


_CD_SPRITES = _build_cd_sprites()
_GAUSS_3X3 = np.array(
    [[0.20, 0.45, 0.20], [0.45, 1.00, 0.45], [0.20, 0.45, 0.20]],
    dtype=np.float32,
)


def _paste_rgba_np(canvas: np.ndarray, sprite: np.ndarray, x: int, y: int, w: int, h: int, gain: float = 1.0) -> None:
    if x < 0 or y < 0 or x + w > STRIP_W or y + h > STRIP_H:
        return
    sp = sprite[:h, :w]
    alpha = (sp[..., 3:4].astype(np.float32)) * (1.0 / 255.0)
    rgb = np.clip(sp[..., :3].astype(np.float32) * gain, 0.0, 255.0)
    patch = canvas[y:y + h, x:x + w]
    canvas[y:y + h, x:x + w] = patch * (1.0 - alpha) + rgb * alpha


def _draw_boss_lightning_np(canvas: np.ndarray, rng: random.Random) -> None:
    """Draws realistic jagged bright orange/gold/purple boss lightning arcs as hard background clutter."""
    x = rng.randint(40, STRIP_W - 40)
    y = rng.randint(18, STRIP_H - 40)
    col = np.array(rng.choice([(255.0, 215.0, 145.0), (255.0, 155.0, 85.0), (195.0, 165.0, 255.0)]), dtype=np.float32)
    for _ in range(rng.randint(10, 22)):
        x = max(2, min(STRIP_W - 4, x + rng.randint(-5, 6)))
        y = max(2, min(STRIP_H - 4, y + rng.randint(-3, 5)))
        canvas[y - 1:y + 2, x - 1:x + 2] = canvas[y - 1:y + 2, x - 1:x + 2] * 0.25 + col * 0.75


def composite_strip_sample_fast(
    rng: random.Random,
    bg_u8: np.ndarray,
    pos_items: list[tuple[int, np.ndarray, int, int]],
    neg_items: list[tuple[np.ndarray, int, int]],
    force_negative: bool = False,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    canvas = np.clip(bg_u8.astype(np.float32) * rng.uniform(0.76, 1.14), 0.0, 255.0)
    target = np.zeros((NUM_ENEMY_ICON_CLASSES,), dtype=np.float32)
    heatmap = np.zeros((NUM_ENEMY_ICON_CLASSES, GRID_H, GRID_W), dtype=np.float32)

    if rng.random() < 0.28:
        _draw_boss_lightning_np(canvas, rng)

    # Paste 1..3 razor-sharp zoomed monster-torso / armor / companion patches from the icon-free
    # lower band `bg_u8[106:152, :]` into the upper enemy zone `y in [18..102]` so EnemyIconNetV2
    # never mistakes sharp boss art, glowing emblems, or tentacles for enemy skill icons!
    if rng.random() < 0.72:
        for _ in range(rng.randint(1, 3)):
            pw_src = rng.randint(44, 110)
            ph_src = rng.randint(26, 42)
            sx0 = rng.randint(0, max(0, STRIP_W - pw_src))
            sy0 = rng.randint(106, max(106, 152 - ph_src))
            patch_u8 = bg_u8[sy0:sy0 + ph_src, sx0:sx0 + pw_src]
            if rng.random() < 0.5:
                patch_u8 = patch_u8[:, ::-1]
            dw = min(STRIP_W - 8, int(round(pw_src * rng.uniform(1.15, 1.55))))
            dh = min(84, int(round(ph_src * rng.uniform(1.15, 1.55))))
            zoomed = np.asarray(
                Image.fromarray(patch_u8).resize((dw, dh), Image.Resampling.BILINEAR),
                dtype=np.float32,
            ) * rng.uniform(0.82, 1.15)
            dx0 = rng.randint(4, max(4, STRIP_W - dw - 4))
            dy0 = rng.randint(16, max(16, 106 - dh))
            # Softly feather 4px border so no artificial hard box edge is created
            wy = np.ones((dh, 1, 1), dtype=np.float32)
            wx = np.ones((1, dw, 1), dtype=np.float32)
            f = min(5, dh // 3, dw // 3)
            if f >= 2:
                ramp = np.linspace(0.0, 1.0, f, dtype=np.float32)
                wy[:f, 0, 0] = ramp
                wy[-f:, 0, 0] = ramp[::-1]
                wx[0, :f, 0] = ramp
                wx[0, -f:, 0] = ramp[::-1]
            w2d = wy * wx
            roi = canvas[dy0:dy0 + dh, dx0:dx0 + dw]
            canvas[dy0:dy0 + dh, dx0:dx0 + dw] = np.clip(roi * (1.0 - w2d) + zoomed * w2d, 0.0, 255.0)

    top_y_min = 12
    if rng.random() < 0.42:
        hh = rng.randint(14, 34)
        hy = rng.randint(0, 18) if rng.random() < 0.7 else rng.randint(36, 95)
        hx0 = rng.randint(2, 24)
        hx1 = min(STRIP_W - 2, hx0 + rng.randint(120, STRIP_W - 8))
        bg_bar = np.array(rng.choice([(22.0, 25.0, 32.0), (242.0, 244.0, 248.0), (34.0, 30.0, 48.0)]), dtype=np.float32)
        canvas[hy:min(STRIP_H - 2, hy + hh), hx0:hx1] = (
            canvas[hy:min(STRIP_H - 2, hy + hh), hx0:hx1] * 0.16 + bg_bar * 0.84
        )
        px = hx0 + 6
        pill_y0 = min(STRIP_H - 6, hy + max(2, hh - 9))
        pill_y1 = min(STRIP_H - 2, pill_y0 + 6)
        for _ in range(rng.randint(2, 6)):
            pw = rng.randint(14, 30)
            pcol = rng.choice([
                (235.0, 185.0, 45.0), (225.0, 85.0, 65.0), (45.0, 165.0, 110.0),
                (75.0, 130.0, 230.0), (165.0, 85.0, 215.0), (0.0, 180.0, 205.0),
            ])
            if px + pw < hx1 - 4 and pill_y1 > pill_y0:
                canvas[pill_y0:pill_y1, px:px + pw] = pcol
            px += pw + 3
        if hy < 24:
            top_y_min = max(top_y_min, hy + hh + 6)

    chosen: list[tuple[int | None, np.ndarray, int, int]] = []
    if not force_negative and pos_items:
        for cidx, sp, sw, sh in pos_items:
            chosen.append((cidx, sp, sw, sh))
            target[cidx] = 1.0

    k_neg = rng.choices([0, 1, 2, 3], weights=[0.35, 0.35, 0.20, 0.10])[0]
    if force_negative and rng.random() < 0.72:
        k_neg = rng.randint(1, 3)
    if neg_items and k_neg > 0:
        for sp, sw, sh in rng.sample(neg_items, k=min(k_neg, len(neg_items))):
            chosen.append((None, sp, sw, sh))

    rng.shuffle(chosen)

    if force_negative and not chosen and rng.random() < 0.55:
        cd_sp = _CD_SPRITES[rng.randrange(3)]
        cx = rng.randint(18, STRIP_W - 44)
        cy = rng.randint(max(top_y_min, 28), STRIP_H - 36)
        _paste_rgba_np(canvas, cd_sp, cx, cy, 24, 10, gain=rng.uniform(0.9, 1.1))

    if chosen:
        n_groups = 2 if (len(chosen) >= 3 and rng.random() < 0.38) else 1
        split = len(chosen) if n_groups == 1 else max(1, len(chosen) // 2)
        groups = [chosen[:split], chosen[split:]] if n_groups == 2 else [chosen]
        vertical = rng.random() < 0.80

        for gi, grp in enumerate(groups):
            if not grp:
                continue
            gap = rng.randint(1, 3)
            if n_groups == 2:
                x_lo = 14 if gi == 0 else STRIP_W // 2 + 6
                x_hi = STRIP_W // 2 - 34 if gi == 0 else STRIP_W - 36
            else:
                x_lo, x_hi = (16, 98) if rng.random() < 0.65 else (16, STRIP_W - 60)

            ax = rng.randint(x_lo, max(x_lo, x_hi))
            stack_h = sum(it[3] + gap for it in grp) if vertical else max(it[3] for it in grp)
            y_lo = max(top_y_min + 11, 28)
            y_hi = max(y_lo, min(104, STRIP_H - 46) - stack_h)
            ay = rng.randint(y_lo, y_hi)

            if rng.random() < 0.88 and ay - 11 >= 2 and ax + 24 < STRIP_W:
                _paste_rgba_np(canvas, _CD_SPRITES[rng.randrange(3)], ax, ay - 11, 24, 10, gain=rng.uniform(0.9, 1.1))

            cx, cy = ax, ay
            for cidx, sp, sw, sh in grp:
                if cx + sw > STRIP_W - 3 or cy + sh > STRIP_H - 3:
                    break
                _paste_rgba_np(canvas, sp, cx, cy, sw, sh, gain=rng.uniform(0.88, 1.12))
                if cidx is not None:
                    # For wide `[NUM] 17`, center heatmap over the `NUM` shield + transition!
                    eff_w = min(sw, sh + 4)
                    gx = min(GRID_W - 1, max(0, int((cx + eff_w * 0.5) // 8)))
                    gy = min(GRID_H - 1, max(0, int((cy + sh * 0.5) // 8)))
                    for dy in (-1, 0, 1):
                        ny = gy + dy
                        if 0 <= ny < GRID_H:
                            for dx in (-1, 0, 1):
                                nx = gx + dx
                                if 0 <= nx < GRID_W:
                                    val = _GAUSS_3X3[dy + 1, dx + 1]
                                    if val > heatmap[cidx, ny, nx]:
                                        heatmap[cidx, ny, nx] = val
                if vertical:
                    cy += sh + gap
                else:
                    cx += sw + gap

    if rng.random() < 0.30:
        n_seed = rng.randint(0, 2**31 - 1)
        noise = np.random.default_rng(n_seed).normal(0.0, 2.2, size=canvas.shape).astype(np.float32)
        canvas = np.clip(canvas + noise, 0.0, 255.0)

    return canvas.astype(np.uint8), target, heatmap


def composite_strip_sample(
    rng: random.Random,
    bg_rgb: np.ndarray,
    pos_icons: list[tuple[int, np.ndarray]],
    neg_icons: list[np.ndarray],
    force_negative: bool = False,
) -> tuple[np.ndarray, np.ndarray]:
    p_it = [(c, sp, 14, 12) for c, sp in pos_icons]
    n_it = [(sp, 14, 12) for sp in neg_icons]
    im_u8, tgt, _ = composite_strip_sample_fast(rng, bg_rgb, p_it, n_it, force_negative=force_negative)
    return im_u8, tgt


def build_dataset(
    inbox_dir: Path = Path("backups/phase4_v2_inbox"),
    enc_dir: Path = Path("backups/tos_encyclopedia"),
    media_dir: Path = Path("backups/user_training_media"),
    out_dir: Path = Path("ml/datasets/enemy_icon_v1"),
    val_count: int = 1800,
    seed: int = 20261128,
) -> dict[str, Any]:
    rng = random.Random(seed)
    out_dir.mkdir(parents=True, exist_ok=True)

    a3_path = inbox_dir / "enemy_icons" / "enemy_icon_taxonomy.json"
    a3 = json.loads(a3_path.read_text(encoding="utf-8"))
    icon_dir = Path(a3["source_dir"])

    by_class: dict[str, list[tuple[str, np.ndarray]]] = defaultdict(list)
    distractors_48: list[tuple[str, np.ndarray]] = []
    excluded_board_info_icons = 0

    for ic in a3["icons"]:
        p = icon_dir / ic["file_name"]
        if not p.is_file():
            continue
        iid = ic["icon_id"]
        rgba = np.asarray(Image.open(p).convert("RGBA").resize((48, 48), Image.Resampling.BILINEAR), dtype=np.uint8)

        # 1. Check explicit rescue/correction table first!
        if iid in ICON_ID_OVERRIDES:
            by_class[ICON_ID_OVERRIDES[iid]].append((iid, rgba))
            continue

        if not ic.get("board_solver_relevant"):
            distractors_48.append((iid, rgba))
            continue

        cid = ic.get("solver_chip_id") or ""
        sem = ic.get("semantic_class") or ""
        # Skip single-orb `require_fire` etc. from `ICON_THREE_ELEMENTS` (keep pure multi-element silver shields!)
        if sem.startswith("require_"):
            excluded_board_info_icons += 1
            continue
        cls_name = SEM_TO_ICON_CLASS.get(sem) or CHIP_TO_ICON_CLASS.get(cid)
        if cls_name and cls_name in CLASS_TO_IDX:
            by_class[cls_name].append((iid, rgba))
        else:
            excluded_board_info_icons += 1

    # 2. Load flood-fill cleaned `tos_encyclopedia/enemy_skills/skill_*/icon.png`!
    if (enc_dir / "enemy_skills").is_dir():
        for sdir in sorted((enc_dir / "enemy_skills").iterdir()):
            if not sdir.is_dir():
                continue
            sidx = int(sdir.name.split("_")[1])
            ip = sdir / "icon.png"
            if not ip.is_file():
                continue
            cls_name = ENC_SKILL_TO_ICON_CLASS.get(sidx)
            if cls_name and cls_name in CLASS_TO_IDX:
                rgba = _clean_enc_icon_rgba(Image.open(ip))
                by_class[cls_name].append((f"ENC_{sdir.name}", rgba))

    tr_cls_list: list[int] = []
    tr_sp_list: list[np.ndarray] = []
    tr_wh_list: list[tuple[int, int]] = []

    va_cls_list: list[int] = []
    va_sp_list: list[np.ndarray] = []
    va_wh_list: list[tuple[int, int]] = []

    class_split_stats: dict[str, dict[str, int]] = {}
    for cls_name in ENEMY_ICON_CLASSES:
        cidx = CLASS_TO_IDX[cls_name]
        items = list(by_class.get(cls_name, []))
        must_tr = [it for it in items if it[0] in CANONICAL_MUST_TRAIN_IDS]
        rest = [it for it in items if it[0] not in CANONICAL_MUST_TRAIN_IDS]
        rng.shuffle(rest)

        if len(rest) >= 2:
            n_val = max(1, len(items) // 4)
            v_items = rest[:n_val]
            t_items = must_tr + rest[n_val:]
        else:
            t_items = items
            v_items = items[:max(1, len(items) // 2)] if items else []

        # Also include 1 scale-augmented canonical icon in val for classes whose core archetype is unique
        if must_tr and cls_name in (
            "ICON_MONOCHROME_DISGUISE", "ICON_THREE_ELEMENTS", "ICON_DIGITAL_COUNT",
            "ICON_SQUARE_2X2", "ICON_TRAFFIC_LIGHT", "ICON_EXACT_COMBO",
        ):
            v_items = list(v_items) + must_tr[:1]

        k_tr_vars = max(4, 32 // max(1, len(t_items)))
        for iid, rgba48 in t_items:
            # Boost canonical core archetypes (`ICON099`, `ICON092`, `ICON051`, etc.) 2x within class!
            mult = 2 if iid in CANONICAL_MUST_TRAIN_IDS else 1
            for _ in range(k_tr_vars * mult):
                buf, sw, sh = _rasterize_strip_sprite(cls_name, rgba48, rng)
                tr_cls_list.append(cidx)
                tr_sp_list.append(buf)
                tr_wh_list.append((sw, sh))

        k_va_vars = max(3, 12 // max(1, len(v_items)))
        for _, rgba48 in v_items:
            for _ in range(k_va_vars):
                buf, sw, sh = _rasterize_strip_sprite(cls_name, rgba48, rng)
                va_cls_list.append(cidx)
                va_sp_list.append(buf)
                va_wh_list.append((sw, sh))

        class_split_stats[cls_name] = {
            "total_source_icons": len(items),
            "train_icons": len(t_items),
            "val_icons": len(v_items),
            "unseen_val_split": int(len(rest) >= 2),
        }

    rng.shuffle(distractors_48)
    n_neg_val = max(80, len(distractors_48) // 5)
    va_neg_sp, va_neg_wh = [], []
    for _, rgba48 in distractors_48[:n_neg_val]:
        for _ in range(2):
            buf, sw, sh = _rasterize_strip_sprite("", rgba48, rng)
            va_neg_sp.append(buf)
            va_neg_wh.append((sw, sh))

    tr_neg_sp, tr_neg_wh = [], []
    for _, rgba48 in distractors_48[n_neg_val:]:
        for _ in range(2):
            buf, sw, sh = _rasterize_strip_sprite("", rgba48, rng)
            tr_neg_sp.append(buf)
            tr_neg_wh.append((sw, sh))

    scene_frames: dict[str, list[Path]] = defaultdict(list)
    precropped_strips: dict[str, list[Path]] = defaultdict(list)
    for p in (inbox_dir / "board_static_pairs").rglob("pair_*_a.png"):
        if "_board_" not in p.name:
            scene_frames[f"pair/{p.stem.rsplit('_', 2)[0]}"].append(p)
    for sub in ("race_missing", "hazards_p0", "overlays_p0", "hazards_p1_multiscene", "collab_skins"):
        for p in (inbox_dir / sub).rglob("frames/*.png"):
            scene_frames[str(p.parents[1].relative_to(inbox_dir))].append(p)
    # Include verified `topup_v090_low_recall` frames (cropped strictly to portrait `[vx0, vx1]` via `_extract_clean_upper_bg_160x256`!)
    from ml.tools.build_phase4_v2_board_dataset import load_refined_6x5_board  # noqa: E402
    topup_dir = inbox_dir / "topup_v090_low_recall"
    if topup_dir.is_dir():
        for fdir in sorted(topup_dir.rglob("frames")):
            rel_key = f"topup090/{fdir.parent.relative_to(topup_dir)}"
            for fp in sorted(fdir.glob("*.png")):
                bp = fdir.parent / "boards" / f"{fp.stem}_board.png"
                if bp.is_file() and load_refined_6x5_board(bp) is not None:
                    scene_frames[rel_key].append(fp)
    for yt_sub in ("20261125_yt_verified_batch", "20261125_yt_user_batch2", "20261125_yt_user_batch3"):
        ydir = media_dir / yt_sub
        if ydir.is_dir():
            for p in ydir.rglob("*.png"):
                scene_frames[f"utm/{yt_sub}/{p.parent.name}"].append(p)

    for corpus_sub in ("youtube_mechanic_v1", "youtube_broad_corpus_v1"):
        sdir = inbox_dir / corpus_sub / "upper_strips"
        if sdir.is_dir():
            for sp in sorted(sdir.iterdir()):
                if sp.suffix.lower() in (".png", ".webp", ".jpg"):
                    vid_key = "_".join(sp.stem.split("_")[:2])
                    precropped_strips[f"{corpus_sub}/{vid_key}"].append(sp)

    all_keys = sorted(scene_frames.keys())
    rng.shuffle(all_keys)
    val_scenes = set(all_keys[:max(10, len(all_keys) // 5)])

    strip_keys = sorted(precropped_strips.keys())
    rng.shuffle(strip_keys)
    val_strip_scenes = set(strip_keys[:max(8, len(strip_keys) // 5)])

    bg_train_list, bg_val_list = [], []
    for sk in all_keys:
        target_list = bg_val_list if sk in val_scenes else bg_train_list
        for fp in sorted(scene_frames[sk])[:8]:
            bg = _extract_clean_upper_bg_160x256(fp)
            if bg is not None:
                target_list.append(bg)

    for sk in strip_keys:
        target_list = bg_val_list if sk in val_strip_scenes else bg_train_list
        for sp in sorted(precropped_strips[sk])[:6]:
            bg = _scrub_precropped_upper_strip(sp)
            if bg is not None:
                target_list.append(bg)

    bg_train = np.stack(bg_train_list, axis=0)
    bg_val = np.stack(bg_val_list, axis=0)

    np.savez_compressed(
        out_dir / "train_assets.npz",
        bg_strips=bg_train,
        pos_cls=np.array(tr_cls_list, dtype=np.int64),
        pos_rgba=np.stack(tr_sp_list, axis=0),
        pos_wh=np.array(tr_wh_list, dtype=np.int64),
        neg_rgba=np.stack(tr_neg_sp, axis=0),
        neg_wh=np.array(tr_neg_wh, dtype=np.int64),
    )

    val_pos_by_cls: dict[int, list[tuple[int, np.ndarray, int, int]]] = defaultdict(list)
    all_val_pos: list[tuple[int, np.ndarray, int, int]] = []
    for cidx, sp, (sw, sh) in zip(va_cls_list, va_sp_list, va_wh_list):
        it = (cidx, sp, sw, sh)
        val_pos_by_cls[cidx].append(it)
        all_val_pos.append(it)
    val_neg_items = [(sp, sw, sh) for sp, (sw, sh) in zip(va_neg_sp, va_neg_wh)]

    val_imgs, val_tgts = [], []
    pos_counter = 0
    for i in range(val_count):
        bg = bg_val[rng.randrange(len(bg_val))]
        if i % 4 == 0:
            im_u8, tgt, _ = composite_strip_sample_fast(rng, bg, [], val_neg_items, force_negative=True)
        else:
            anchor_cls = pos_counter % NUM_ENEMY_ICON_CLASSES
            pos_counter += 1
            pool = val_pos_by_cls.get(anchor_cls, [])
            picks = [rng.choice(pool)] if pool else [all_val_pos[0]]
            for p in rng.sample(all_val_pos, k=rng.randint(0, 2)):
                if p[0] != anchor_cls:
                    picks.append(p)
            im_u8, tgt, _ = composite_strip_sample_fast(rng, bg, picks, val_neg_items, force_negative=False)
        val_imgs.append(im_u8)
        val_tgts.append(tgt)

    val_imgs_arr = np.stack(val_imgs, axis=0)
    val_tgts_arr = np.stack(val_tgts, axis=0)
    np.savez_compressed(out_dir / "val_strips.npz", strips=val_imgs_arr, targets=val_tgts_arr)

    qa_rows = 24
    qa = Image.new("RGB", (STRIP_W + 360, qa_rows * (STRIP_H + 6) + 12), (18, 20, 26))
    draw = ImageDraw.Draw(qa)
    for r in range(min(qa_rows, len(val_imgs_arr))):
        y = 6 + r * (STRIP_H + 6)
        qa.paste(Image.fromarray(val_imgs_arr[r]), (6, y))
        active = [ENEMY_ICON_CLASSES[ci].replace("ICON_", "") for ci in np.flatnonzero(val_tgts_arr[r] > 0.5)]
        lbl = ", ".join(active) if active else "NEGATIVE (0 target icons)"
        draw.text((STRIP_W + 16, y + 68), f"#{r:02d} {lbl}", fill=(120, 255, 170) if active else (255, 170, 120))
    qa.save(out_dir / "qa_icon_strips.jpg", quality=92)

    manifest = {
        "strip_shape": [STRIP_H, STRIP_W],
        "num_classes": NUM_ENEMY_ICON_CLASSES,
        "total_a3_icons": len(a3["icons"]),
        "excluded_board_info_icons_from_neg": excluded_board_info_icons,
        "train_pos_sprites": len(tr_sp_list),
        "val_pos_sprites": len(va_sp_list),
        "train_distractor_sprites": len(tr_neg_sp),
        "val_distractor_sprites": len(va_neg_sp),
        "bg_train_strips": int(len(bg_train)),
        "bg_val_strips": int(len(bg_val)),
        "val_strips_total": int(len(val_imgs_arr)),
        "class_stats": class_split_stats,
    }
    (out_dir / "split_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def main() -> None:
    m = build_dataset()
    print(
        f"Stage 2B cleaned dataset ready: classes={m['num_classes']}, "
        f"pos_sprites(train={m['train_pos_sprites']}, val={m['val_pos_sprites']}), "
        f"bg_strips(train={m['bg_train_strips']}, val={m['bg_val_strips']}), val_strips={m['val_strips_total']}"
    )


if __name__ == "__main__":
    main()
