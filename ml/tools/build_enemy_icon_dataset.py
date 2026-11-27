"""
Build Stage 2B EnemyIconNet dataset (read-only on `backups/`):
  - Maps A3 `enemy_icon_taxonomy.json` (1,632 RGBA icons) into the 24 ENEMY_ICON_CLASSES + NON_BOARD distractors
  - Extracts real upper-battle strips `[96, 256, 3]` from archived battle screenshots across Track B
  - Enforces strict icon-level (unseen icon variants for >=2-icon classes) AND scene-level 80/20 train/val splits
  - Saves compact binary caches & fixed held-out `val_strips.npz` + `qa_icon_strips.jpg` to gitignored `ml/datasets/enemy_icon_v1/`
"""

from __future__ import annotations

import json
import random
from collections import defaultdict
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from ml.enemy_icon_arch import ENEMY_ICON_CLASSES, NUM_ENEMY_ICON_CLASSES

CHIP_TO_ICON_CLASS = {
    "puzzle_shield": "ICON_PUZZLE",
    "exact_first_combo": "ICON_EXACT_COMBO",
    "exact_combo": "ICON_EXACT_COMBO",
    "min_combo": "ICON_MIN_COMBO",
    "min_first_combo": "ICON_MIN_COMBO",
    "no_cascade": "ICON_NO_CASCADE",
    "first_wave_only": "ICON_NO_CASCADE",
    "anti_first_wave": "ICON_ANTI_FIRST_WAVE",
    "forbid_all": "ICON_FORBID_ALL",
    "five_elements": "ICON_FIVE_ELEMENTS",
    "five_elements_enchanted": "ICON_FIVE_ELEMENTS",
    "three_elements": "ICON_THREE_ELEMENTS",
    "require_elements": "ICON_THREE_ELEMENTS",
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
}

SEM_TO_ICON_CLASS = {
    "black_white": "ICON_MONOCHROME_DISGUISE",
    "disguise": "ICON_MONOCHROME_DISGUISE",
    "hidden_orbs": "ICON_MONOCHROME_DISGUISE",
    "fog": "ICON_MONOCHROME_DISGUISE",
    "burning": "ICON_BURNING_TRAIL",
    "scorching": "ICON_BURNING_TRAIL",
    "sticky": "ICON_CORROSION_TRAIL",
    "trail_corrosion": "ICON_CORROSION_TRAIL",
    "enchanted_elements": "ICON_ENCHANTED_BREAKTHROUGH",
    "puzzle": "ICON_PUZZLE",
    "race_puzzle": "ICON_PUZZLE",
    "traffic_light": "ICON_TRAFFIC_LIGHT",
    "char_orb": "ICON_CHAR_ORB_TERRAIN",
    "starting_buoy": "ICON_STARTING_BUOY",
    "cross": "ICON_CROSS_SHIELD",
    "l_shape": "ICON_L_SHAPE",
    "t_shape": "ICON_T_SHAPE",
    "square_2x2": "ICON_SQUARE_2X2",
    "step_limit": "ICON_STEP_LIMIT",
    "step_range": "ICON_STEP_LIMIT",
    "no_cascade": "ICON_NO_CASCADE",
    "first_wave_only": "ICON_NO_CASCADE",
}

CLASS_TO_IDX = {name: i for i, name in enumerate(ENEMY_ICON_CLASSES)}

STRIP_H = 96
STRIP_W = 256


def _extract_upper_strip(img_path: Path) -> np.ndarray | None:
    try:
        im = Image.open(img_path).convert("RGB")
    except Exception:
        return None
    w, h = im.size
    if w < 120 or h < 200:
        return None
    crop = im.crop((int(w * 0.04), int(h * 0.08), int(w * 0.96), int(h * 0.42)))
    crop = crop.resize((STRIP_W, STRIP_H), Image.Resampling.BILINEAR)
    return np.asarray(crop, dtype=np.uint8)


def composite_strip_sample(
    rng: random.Random,
    bg_rgb: np.ndarray,
    pos_icons: list[tuple[int, np.ndarray]],
    neg_icons: list[np.ndarray],
    force_negative: bool = False,
) -> tuple[np.ndarray, np.ndarray]:
    """
    Composites 1..4 target icons + 0..3 distractor icons in realistic horizontal ToS enemy rows
    onto a [96, 256, 3] upper-battle strip. Returns (strip_uint8, multi_hot_float32[24]).
    """
    canvas = Image.fromarray(bg_rgb).copy()
    # Mild brightness/blur jitter on background so icon foregrounds pop realistically
    if rng.random() < 0.35:
        canvas = canvas.filter(ImageFilter.GaussianBlur(radius=rng.uniform(0.3, 1.0)))
    arr = np.asarray(canvas, dtype=np.float32) * rng.uniform(0.75, 1.10)
    canvas = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))

    chosen_items: list[tuple[int | None, np.ndarray]] = []
    target = np.zeros((NUM_ENEMY_ICON_CLASSES,), dtype=np.float32)

    if not force_negative and pos_icons:
        k_pos = rng.choices([1, 2, 3, 4], weights=[0.36, 0.34, 0.20, 0.10])[0]
        picked_pos = rng.sample(pos_icons, k=min(k_pos, len(pos_icons)))
        for cls_idx, rgba in picked_pos:
            chosen_items.append((cls_idx, rgba))
            target[cls_idx] = 1.0

    k_neg = rng.choices([0, 1, 2, 3], weights=[0.35, 0.35, 0.20, 0.10])[0]
    if force_negative and rng.random() < 0.70:
        k_neg = rng.randint(1, 3)
    if neg_icons and k_neg > 0:
        for rgba in rng.sample(neg_icons, k=min(k_neg, len(neg_icons))):
            chosen_items.append((None, rgba))

    rng.shuffle(chosen_items)
    if chosen_items:
        # Place into 1 or 2 horizontal clusters mimicking 1..2 enemy status icon bars
        n_groups = 2 if (len(chosen_items) >= 3 and rng.random() < 0.40) else 1
        split = len(chosen_items) if n_groups == 1 else len(chosen_items) // 2
        groups = [chosen_items[:split], chosen_items[split:]] if n_groups == 2 else [chosen_items]

        icon_sz = rng.randint(18, 28)
        base_y = rng.randint(24, max(25, STRIP_H - icon_sz - 6))
        for gi, grp in enumerate(groups):
            gap = rng.randint(1, 4)
            total_w = len(grp) * (icon_sz + gap)
            if n_groups == 2:
                x_min = 6 if gi == 0 else STRIP_W // 2 + 4
                x_max = (STRIP_W // 2 - total_w - 4) if gi == 0 else (STRIP_W - total_w - 6)
            else:
                x_min = 8
                x_max = STRIP_W - total_w - 8
            cur_x = rng.randint(x_min, max(x_min, x_max))
            row_y = max(4, min(STRIP_H - icon_sz - 4, base_y + rng.randint(-6, 6)))
            for _, rgba in grp:
                sz = max(16, min(32, icon_sz + rng.randint(-1, 1)))
                ic = Image.fromarray(rgba, mode="RGBA").resize((sz, sz), Image.Resampling.BILINEAR)
                if rng.random() < 0.20:
                    ic = ic.filter(ImageFilter.GaussianBlur(radius=0.4))
                # Subtle dark badge backplate (matches ToS dark icon frame)
                back = Image.new("RGBA", (sz + 2, sz + 2), (14, 16, 22, 160))
                canvas.paste(back, (cur_x - 1, row_y - 1), back)
                canvas.paste(ic, (cur_x, row_y), ic)
                cur_x += sz + gap

    out = np.asarray(canvas, dtype=np.uint8)
    # Slight Gaussian sensor noise
    if rng.random() < 0.30:
        noise = np.random.default_rng(rng.randint(0, 2**31 - 1)).normal(0.0, 2.5, size=out.shape)
        out = np.clip(out.astype(np.float32) + noise, 0, 255).astype(np.uint8)
    return out, target


def build_dataset(
    inbox_dir: Path = Path("backups/phase4_v2_inbox"),
    out_dir: Path = Path("ml/datasets/enemy_icon_v1"),
    val_count: int = 1200,
    seed: int = 20261127,
) -> dict[str, Any]:
    rng = random.Random(seed)
    out_dir.mkdir(parents=True, exist_ok=True)

    a3_path = inbox_dir / "enemy_icons" / "enemy_icon_taxonomy.json"
    a3 = json.loads(a3_path.read_text(encoding="utf-8"))
    icon_dir = Path(a3["source_dir"])

    by_class: dict[str, list[tuple[str, np.ndarray]]] = defaultdict(list)
    distractors: list[tuple[str, np.ndarray]] = []

    for ic in a3["icons"]:
        p = icon_dir / ic["file_name"]
        if not p.is_file():
            continue
        rgba = np.asarray(Image.open(p).convert("RGBA").resize((48, 48), Image.Resampling.BILINEAR), dtype=np.uint8)
        if not ic.get("board_solver_relevant"):
            distractors.append((ic["icon_id"], rgba))
            continue
        cid = ic.get("solver_chip_id") or ""
        sem = ic.get("semantic_class") or ""
        cls_name = SEM_TO_ICON_CLASS.get(sem) or CHIP_TO_ICON_CLASS.get(cid)
        if cls_name and cls_name in CLASS_TO_IDX:
            by_class[cls_name].append((ic["icon_id"], rgba))
        else:
            # Board-relevant icons outside the 24 classes still act as realistic UI icon distractors
            distractors.append((ic["icon_id"], rgba))

    # Strict icon-level train/val split
    train_icon_ids: list[str] = []
    train_icon_cls: list[int] = []
    train_icon_rgba: list[np.ndarray] = []

    val_icon_ids: list[str] = []
    val_icon_cls: list[int] = []
    val_icon_rgba: list[np.ndarray] = []

    class_split_stats: dict[str, dict[str, int]] = {}
    for cls_name in ENEMY_ICON_CLASSES:
        cidx = CLASS_TO_IDX[cls_name]
        items = list(by_class.get(cls_name, []))
        rng.shuffle(items)
        if len(items) >= 2:
            n_val = max(1, len(items) // 4)
            v_items = items[:n_val]
            t_items = items[n_val:]
        else:
            # 1-icon class cannot hold out unseen icon; goes to train and val reuses it (flagged single_icon)
            v_items = items
            t_items = items
        for iid, rgba in t_items:
            train_icon_ids.append(iid)
            train_icon_cls.append(cidx)
            train_icon_rgba.append(rgba)
        for iid, rgba in v_items:
            val_icon_ids.append(iid)
            val_icon_cls.append(cidx)
            val_icon_rgba.append(rgba)
        class_split_stats[cls_name] = {
            "total_source_icons": len(items),
            "train_icons": len(t_items),
            "val_icons": len(v_items),
            "unseen_val_split": int(len(items) >= 2),
        }

    rng.shuffle(distractors)
    n_neg_val = max(50, len(distractors) // 5)
    val_neg_rgba = np.stack([x[1] for x in distractors[:n_neg_val]], axis=0)
    train_neg_rgba = np.stack([x[1] for x in distractors[n_neg_val:]], axis=0)

    # Collect real battle upper strips partitioned strictly by subbatch folder
    scene_frames: dict[str, list[Path]] = defaultdict(list)
    for p in (inbox_dir / "board_static_pairs").rglob("pair_*_a.png"):
        if "_board_" not in p.name:
            scene_key = f"pair/{p.stem.rsplit('_', 2)[0]}"
            scene_frames[scene_key].append(p)
    for sub in ("race_missing", "hazards_p0", "overlays_p0", "hazards_p1_multiscene", "collab_skins"):
        for p in (inbox_dir / sub).rglob("frames/*.png"):
            scene_key = str(p.parents[1].relative_to(inbox_dir))
            scene_frames[scene_key].append(p)

    scene_keys = sorted(scene_frames.keys())
    rng.shuffle(scene_keys)
    n_val_scenes = max(4, len(scene_keys) // 5)
    val_scenes = set(scene_keys[:n_val_scenes])

    bg_train_list: list[np.ndarray] = []
    bg_val_list: list[np.ndarray] = []
    for sk in scene_keys:
        paths = sorted(scene_frames[sk])[:12]  # cap per scene for diversity balance
        target_list = bg_val_list if sk in val_scenes else bg_train_list
        for fp in paths:
            strip = _extract_upper_strip(fp)
            if strip is not None:
                target_list.append(strip)

    bg_train = np.stack(bg_train_list, axis=0)
    bg_val = np.stack(bg_val_list, axis=0)

    np.savez_compressed(
        out_dir / "train_assets.npz",
        bg_strips=bg_train,
        pos_cls=np.array(train_icon_cls, dtype=np.int64),
        pos_rgba=np.stack(train_icon_rgba, axis=0),
        neg_rgba=train_neg_rgba,
    )

    # Build deterministic held-out val_strips.npz covering all 24 classes + 25% pure negatives
    val_pos_by_cls: dict[int, list[tuple[int, np.ndarray]]] = defaultdict(list)
    for cidx, rgba in zip(val_icon_cls, val_icon_rgba):
        val_pos_by_cls[cidx].append((cidx, rgba))
    all_val_pos = [(cidx, rgba) for cidx, rgba in zip(val_icon_cls, val_icon_rgba)]
    val_neg_list = [val_neg_rgba[i] for i in range(len(val_neg_rgba))]

    val_imgs: list[np.ndarray] = []
    val_tgts: list[np.ndarray] = []
    for i in range(val_count):
        bg = bg_val[rng.randrange(len(bg_val))]
        force_neg = (i % 4 == 0)
        if force_neg:
            im_u8, tgt = composite_strip_sample(rng, bg, all_val_pos, val_neg_list, force_negative=True)
        else:
            # Ensure even representation across all 24 classes
            anchor_cls = i % NUM_ENEMY_ICON_CLASSES
            pool = val_pos_by_cls.get(anchor_cls, [])
            if pool:
                extra = rng.sample(all_val_pos, k=rng.randint(0, 2))
                picks = [rng.choice(pool)] + [p for p in extra if p[0] != anchor_cls]
            else:
                picks = all_val_pos
            im_u8, tgt = composite_strip_sample(rng, bg, picks, val_neg_list, force_negative=False)
        val_imgs.append(im_u8)
        val_tgts.append(tgt)

    val_imgs_arr = np.stack(val_imgs, axis=0)
    val_tgts_arr = np.stack(val_tgts, axis=0)
    np.savez_compressed(out_dir / "val_strips.npz", strips=val_imgs_arr, targets=val_tgts_arr)

    # Save QA contact sheet
    qa_rows = 24
    qa_w = STRIP_W * 2 + 240
    qa_h = qa_rows * (STRIP_H + 6) + 12
    qa = Image.new("RGB", (qa_w, qa_h), (18, 20, 26))
    draw = ImageDraw.Draw(qa)
    for r in range(min(qa_rows, len(val_imgs_arr))):
        y = 6 + r * (STRIP_H + 6)
        qa.paste(Image.fromarray(val_imgs_arr[r]), (6, y))
        active = [ENEMY_ICON_CLASSES[ci].replace("ICON_", "") for ci in np.flatnonzero(val_tgts_arr[r] > 0.5)]
        lbl = ", ".join(active) if active else "NEGATIVE (0 icons)"
        draw.text((STRIP_W + 16, y + 36), f"#{r:02d} {lbl}", fill=(120, 255, 170) if active else (255, 170, 120))
    qa.save(out_dir / "qa_icon_strips.jpg", quality=92)

    manifest = {
        "total_a3_icons": len(a3["icons"]),
        "train_pos_icons": len(train_icon_ids),
        "val_pos_icons": len(val_icon_ids),
        "train_distractor_icons": int(len(train_neg_rgba)),
        "val_distractor_icons": int(len(val_neg_rgba)),
        "bg_train_strips": int(len(bg_train)),
        "bg_val_strips": int(len(bg_val)),
        "val_strips_total": int(len(val_imgs_arr)),
        "class_stats": class_split_stats,
    }
    (out_dir / "split_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def main() -> None:
    manifest = build_dataset()
    print(
        f"Stage 2B dataset ready: pos_icons(train={manifest['train_pos_icons']}, val={manifest['val_pos_icons']}), "
        f"bg_strips(train={manifest['bg_train_strips']}, val={manifest['bg_val_strips']}), "
        f"val_strips={manifest['val_strips_total']}"
    )


if __name__ == "__main__":
    main()
