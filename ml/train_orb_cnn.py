#!/usr/bin/env python3
"""
AutoDora-NEXT Phase 4 Multi-Head CNN Trainer (`AutoDoraOrbNetV1`)
=================================================================

Trains on GPU (`ml/.venv/Scripts/python.exe -m ml.train_orb_cnn`) using
`ml/datasets/phase4_v1/{train,val}.npz`, evaluates held-out validation splits,
calibrates conservative precision >= 0.95 per-head thresholds, and saves:
  - `ml/checkpoints/orb_multihead_v1.pt`
  - `ml/runs/phase4_v1/eval_report.json`
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path
from typing import Dict, Tuple

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))

from ml.model_arch import AutoDoraOrbNetV1
from ml.schema import (
    ELEMENT_NAMES,
    HAZARD_NAMES,
    HAZARD_TO_IDX,
    NUM_ELEMENTS,
    NUM_HAZARDS,
    NUM_RACES,
    NUM_TILE_OVERLAYS,
    NUM_VARIANTS,
    RACE_NAMES,
    TILE_OVERLAY_NAMES,
)


class OrbDataset(Dataset):
    def __init__(self, npz_path: Path, augment: bool = False, seed: int = 123) -> None:
        data = np.load(npz_path, allow_pickle=False)
        self.images = data["images"]  # uint8 [N, 64, 64, 3]
        self.is_rune = data["is_rune"].astype(np.float32)
        self.element = data["element"].astype(np.int64)
        self.variant = data["variant"].astype(np.int64)
        self.race = data["race"].astype(np.int64)
        self.race_known = data["race_known"].astype(bool)
        self.hazards = data["hazards"].astype(np.float32)
        self.hazard_mask = data["hazard_mask"].astype(np.float32)
        self.tile_overlay = data["tile_overlay"].astype(np.float32)
        self.overlay_mask = data["overlay_mask"].astype(np.float32)
        self.augment = augment
        self.rng = np.random.default_rng(seed)

    def __len__(self) -> int:
        return int(self.images.shape[0])

    def _augment_tile(
        self,
        img: np.ndarray,
        is_rune: float,
        element: int,
        haz: np.ndarray,
        hmask: np.ndarray,
    ) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
        x = img.astype(np.float32) / 255.0

        # 1) Small translation jitter (+/- 3px clamp-padded) simulating calibration drift
        if self.rng.random() < 0.70:
            sx = int(self.rng.integers(-3, 4))
            sy = int(self.rng.integers(-3, 4))
            if sx != 0 or sy != 0:
                padded = np.pad(x, ((3, 3), (3, 3), (0, 0)), mode="edge")
                x = padded[3 + sy : 3 + sy + 64, 3 + sx : 3 + sx + 64]

        # 2) Synthetic monochrome augmentation (~12% of clean matchable tiles) -> boosts BLACK_WHITE
        bw_idx = HAZARD_TO_IDX["BLACK_WHITE"]
        if (
            is_rune > 0.5
            and 0 <= element <= 5
            and haz.sum() == 0.0
            and self.rng.random() < 0.12
        ):
            gray = 0.299 * x[..., 0] + 0.587 * x[..., 1] + 0.114 * x[..., 2]
            # Slight contrast boost similar to in-game monochrome shader
            gray = np.clip((gray - 0.5) * float(self.rng.uniform(0.95, 1.22)) + 0.5, 0.0, 1.0)
            x = np.stack([gray, gray, gray], axis=-1)
            haz = haz.copy()
            hmask = hmask.copy()
            haz[bw_idx] = 1.0
            hmask[bw_idx] = 1.0

        # 3) Brightness / contrast / dimmed scrim simulation
        r_dim = self.rng.random()
        if r_dim < 0.12 and is_rune > 0.5:
            gain = float(self.rng.uniform(0.48, 0.68))  # dimmed Starting Buoy scrim
            x = np.clip(x * gain, 0.0, 1.0)
        elif r_dim < 0.75:
            alpha = float(self.rng.uniform(0.88, 1.14))
            beta = float(self.rng.uniform(-0.04, 0.04))
            x = np.clip(x * alpha + beta, 0.0, 1.0)

        # 4) Mild camera/compression Gaussian noise
        if self.rng.random() < 0.45:
            noise = self.rng.normal(0.0, 0.014, size=x.shape).astype(np.float32)
            x = np.clip(x + noise, 0.0, 1.0)

        return x, haz, hmask

    def __getitem__(self, idx: int):
        img = self.images[idx]
        is_rune = float(self.is_rune[idx])
        el = int(self.element[idx])
        var = int(self.variant[idx])
        race = int(self.race[idx])
        r_known = bool(self.race_known[idx])
        haz = self.hazards[idx]
        hmask = self.hazard_mask[idx]
        ov = self.tile_overlay[idx]
        omask = self.overlay_mask[idx]

        if self.augment:
            x, haz, hmask = self._augment_tile(img, is_rune, el, haz, hmask)
        else:
            x = img.astype(np.float32) / 255.0

        # PyTorch NCHW float32 [3, 64, 64]
        x_chw = np.transpose(x, (2, 0, 1)).astype(np.float32)
        return (
            torch.from_numpy(x_chw),
            torch.tensor(is_rune, dtype=torch.float32),
            torch.tensor(el, dtype=torch.long),
            torch.tensor(var, dtype=torch.long),
            torch.tensor(race if r_known else -1, dtype=torch.long),
            torch.from_numpy(haz.copy()),
            torch.from_numpy(hmask.copy()),
            torch.from_numpy(ov.copy()),
            torch.from_numpy(omask.copy()),
        )


def compute_pos_weights(targets: np.ndarray, masks: np.ndarray, cap: float) -> torch.Tensor:
    valid = masks > 0.5
    pos = np.where(valid, targets > 0.5, False).sum(axis=0).astype(np.float32)
    tot = valid.sum(axis=0).astype(np.float32)
    neg = np.maximum(1.0, tot - pos)
    weights = np.where(pos >= 1.0, np.sqrt(neg / np.maximum(1.0, pos)), 1.0)
    weights = np.clip(weights, 1.0, cap).astype(np.float32)
    return torch.from_numpy(weights)


def calibrate_binary_thresholds(
    probs: np.ndarray,
    targets: np.ndarray,
    masks: np.ndarray,
    names: list[str],
    default_thresh: float = 0.72,
    min_precision: float = 0.95,
) -> Dict[str, dict]:
    out: Dict[str, dict] = {}
    cand_th = [round(0.45 + 0.05 * i, 2) for i in range(10)]  # 0.45..0.90
    for j, name in enumerate(names):
        m = masks[:, j] > 0.5
        if m.sum() == 0:
            out[name] = {"threshold": default_thresh, "val_pos": 0, "precision": None, "recall": None, "f1": None}
            continue
        y_true = targets[m, j] > 0.5
        y_prob = probs[m, j]
        n_pos = int(y_true.sum())
        if n_pos == 0:
            # Verify no false positives above default_thresh
            fp = int((y_prob >= default_thresh).sum())
            out[name] = {"threshold": default_thresh, "val_pos": 0, "val_fp_at_default": fp, "precision": None, "recall": None, "f1": None}
            continue

        best_safe = None
        best_any = None
        for th in cand_th:
            pred = y_prob >= th
            tp = int((pred & y_true).sum())
            fp = int((pred & (~y_true)).sum())
            fn = int(((~pred) & y_true).sum())
            prec = tp / max(1, tp + fp) if (tp + fp) > 0 else 1.0
            rec = tp / max(1, tp + fn)
            f1 = (2 * prec * rec / max(1e-6, prec + rec)) if (prec + rec) > 0 else 0.0
            stat = {"threshold": th, "val_pos": n_pos, "tp": tp, "fp": fp, "fn": fn, "precision": round(prec, 4), "recall": round(rec, 4), "f1": round(f1, 4)}
            if prec >= min_precision and (best_safe is None or (f1, prec) > (best_safe["f1"], best_safe["precision"])):
                best_safe = stat
            if best_any is None or (f1, prec) > (best_any["f1"], best_any["precision"]):
                best_any = stat
        out[name] = best_safe if best_safe is not None else best_any
    return out


@torch.no_grad()
def evaluate(model: AutoDoraOrbNetV1, loader: DataLoader, device: torch.device) -> dict:
    model.eval()
    probs_all = []
    rune_y, el_y, var_y, race_y = [], [], [], []
    haz_y, haz_m, ov_y, ov_m = [], [], [], []

    for x, is_r, el, var, race, haz, hm, ov, om in loader:
        x = x.to(device, non_blocking=True)
        p = model(x).cpu().numpy()
        probs_all.append(p)
        rune_y.append(is_r.numpy())
        el_y.append(el.numpy())
        var_y.append(var.numpy())
        race_y.append(race.numpy())
        haz_y.append(haz.numpy())
        haz_m.append(hm.numpy())
        ov_y.append(ov.numpy())
        ov_m.append(om.numpy())

    probs = np.concatenate(probs_all, axis=0)
    r_y = np.concatenate(rune_y)
    e_y = np.concatenate(el_y)
    v_y = np.concatenate(var_y)
    rc_y = np.concatenate(race_y)
    h_y = np.concatenate(haz_y)
    h_m = np.concatenate(haz_m) * r_y[:, None]
    o_y = np.concatenate(ov_y)
    o_m = np.concatenate(ov_m) * r_y[:, None]

    p_rune = probs[:, 0]
    p_el = probs[:, 1:10]
    p_var = probs[:, 10:14]
    p_race = probs[:, 14:22]
    p_haz = probs[:, 22:39]
    p_ov = probs[:, 39:47]

    rune_acc = float(((p_rune >= 0.5) == (r_y > 0.5)).mean())
    el_valid = e_y >= 0
    el_acc = float((p_el[el_valid].argmax(axis=1) == e_y[el_valid]).mean()) if el_valid.any() else 0.0

    # Also compute clean unobscured element accuracy (zero hazards & zero overlays)
    clean_mask = el_valid & (h_y.sum(axis=1) == 0) & (o_y.sum(axis=1) == 0) & (e_y <= 5)
    clean_el_acc = float((p_el[clean_mask].argmax(axis=1) == e_y[clean_mask]).mean()) if clean_mask.any() else 0.0

    var_valid = v_y >= 0
    var_acc = float((p_var[var_valid].argmax(axis=1) == v_y[var_valid]).mean()) if var_valid.any() else 0.0

    rc_valid = rc_y >= 0
    race_acc = float((p_race[rc_valid].argmax(axis=1) == rc_y[rc_valid]).mean()) if rc_valid.any() else 0.0

    # Per-race recall
    race_per_class = {}
    if rc_valid.any():
        rc_pred = p_race.argmax(axis=1)
        for ri, rname in enumerate(RACE_NAMES):
            rm = rc_y == ri
            if rm.any():
                race_per_class[rname] = {"count": int(rm.sum()), "recall": round(float((rc_pred[rm] == ri).mean()), 4)}

    haz_metrics = calibrate_binary_thresholds(p_haz, h_y, h_m, HAZARD_NAMES, default_thresh=0.70, min_precision=0.95)
    ov_metrics = calibrate_binary_thresholds(p_ov, o_y, o_m, TILE_OVERLAY_NAMES, default_thresh=0.72, min_precision=0.95)

    haz_f1s = [m["f1"] for m in haz_metrics.values() if m.get("f1") is not None]
    ov_f1s = [m["f1"] for m in ov_metrics.values() if m.get("f1") is not None]

    return {
        "is_rune_acc": round(rune_acc, 4),
        "element_acc": round(el_acc, 4),
        "clean_6color_element_acc": round(clean_el_acc, 4),
        "variant_acc": round(var_acc, 4),
        "race_acc": round(race_acc, 4),
        "race_per_class": race_per_class,
        "macro_hazard_f1": round(float(np.mean(haz_f1s)) if haz_f1s else 0.0, 4),
        "macro_overlay_f1": round(float(np.mean(ov_f1s)) if ov_f1s else 0.0, 4),
        "hazards": haz_metrics,
        "tile_overlay": ov_metrics,
    }


def main() -> int:
    torch.manual_seed(42)
    np.random.seed(42)

    data_dir = REPO_ROOT / "ml" / "datasets" / "phase4_v1"
    ckpt_dir = REPO_ROOT / "ml" / "checkpoints"
    runs_dir = REPO_ROOT / "ml" / "runs" / "phase4_v1"
    ckpt_dir.mkdir(parents=True, exist_ok=True)
    runs_dir.mkdir(parents=True, exist_ok=True)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[TRAIN] Device: {device}" + (f" ({torch.cuda.get_device_name(0)})" if device.type == "cuda" else ""))

    train_ds = OrbDataset(data_dir / "train.npz", augment=True, seed=42)
    val_ds = OrbDataset(data_dir / "val.npz", augment=False, seed=99)
    print(f"[DATA] train={len(train_ds)} val={len(val_ds)}")

    # Inverse-frequency-balanced sampler so ultra-rare C-class mechanics (Coagulated, Blood-Thorn,
    # Luminous, Frozen, Magic Orb, Black Hole, Races) get balanced exposure against 4,000+ normal tiles!
    haz_pos_cnts = np.maximum(1.0, (train_ds.hazards * train_ds.hazard_mask * train_ds.is_rune[:, None] > 0.5).sum(axis=0))
    ov_pos_cnts = np.maximum(1.0, (train_ds.tile_overlay * train_ds.overlay_mask * train_ds.is_rune[:, None] > 0.5).sum(axis=0))
    rc_cnts = np.array([max(1.0, ((train_ds.race == i) & train_ds.race_known).sum()) for i in range(NUM_RACES)])

    sample_weights = np.ones(len(train_ds), dtype=np.float64)
    for i in range(len(train_ds)):
        if train_ds.is_rune[i] < 0.5:
            sample_weights[i] = 3.0
            continue
        w = 1.0
        for hi in np.flatnonzero(train_ds.hazards[i] > 0.5):
            w = max(w, float(min(30.0, 220.0 / haz_pos_cnts[hi])))
        for oi in np.flatnonzero(train_ds.tile_overlay[i] > 0.5):
            w = max(w, float(min(28.0, 200.0 / ov_pos_cnts[oi])))
        if train_ds.variant[i] == 3:  # WEAKENED
            w = max(w, 6.0)
        if train_ds.race_known[i] and train_ds.race[i] > 0:
            w = max(w, float(min(12.0, 260.0 / rc_cnts[train_ds.race[i]])))
        sample_weights[i] = w

    sampler = WeightedRandomSampler(
        weights=torch.from_numpy(sample_weights),
        num_samples=9600,
        replacement=True,
    )
    train_loader = DataLoader(train_ds, batch_size=128, sampler=sampler, num_workers=0, pin_memory=(device.type == "cuda"))
    val_loader = DataLoader(val_ds, batch_size=256, shuffle=False, num_workers=0, pin_memory=(device.type == "cuda"))

    haz_pos_w = compute_pos_weights(train_ds.hazards, train_ds.hazard_mask * train_ds.is_rune[:, None], cap=10.0).to(device)
    ov_pos_w = compute_pos_weights(train_ds.tile_overlay, train_ds.overlay_mask * train_ds.is_rune[:, None], cap=10.0).to(device)

    # Gentle race class balance weights (keeps NONE high precision while boosting rarer God/Demon/Beast/Elf/Machina)
    race_counts = np.array(
        [max(1, ((train_ds.race == i) & train_ds.race_known).sum()) for i in range(NUM_RACES)],
        dtype=np.float32,
    )
    race_w = np.sqrt(race_counts.max() / race_counts)
    race_w = np.clip(race_w, 1.0, 4.0).astype(np.float32)
    race_w[0] = 1.0
    race_w_t = torch.from_numpy(race_w).to(device)

    model = AutoDoraOrbNetV1(dropout=0.15).to(device)
    param_cnt = sum(p.numel() for p in model.parameters())
    print(f"[MODEL] AutoDoraOrbNetV1 params={param_cnt:,}")

    epochs = 60
    optimizer = torch.optim.AdamW(model.parameters(), lr=1.8e-3, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs, eta_min=3e-5)

    ce_el = nn.CrossEntropyLoss(ignore_index=-1, label_smoothing=0.03)
    ce_var = nn.CrossEntropyLoss(ignore_index=-1, label_smoothing=0.02)
    ce_race = nn.CrossEntropyLoss(weight=race_w_t, ignore_index=-1, label_smoothing=0.02)

    best_score = -1.0
    best_path = ckpt_dir / "orb_multihead_v1.pt"
    t0 = time.perf_counter()

    for ep in range(1, epochs + 1):
        model.train()
        tot_loss = 0.0
        steps = 0
        for x, is_r, el, var, race, haz, hm, ov, om in train_loader:
            x = x.to(device, non_blocking=True)
            is_r = is_r.to(device, non_blocking=True)
            el = el.to(device, non_blocking=True)
            var = var.to(device, non_blocking=True)
            race = race.to(device, non_blocking=True)
            haz = haz.to(device, non_blocking=True)
            hm = hm.to(device, non_blocking=True)
            ov = ov.to(device, non_blocking=True)
            om = om.to(device, non_blocking=True)

            optimizer.zero_grad(set_to_none=True)
            out = model.forward_logits(x)

            l_rune = F.binary_cross_entropy_with_logits(out["is_rune"].squeeze(1), is_r)
            l_el = ce_el(out["element"], el) if (el >= 0).any() else torch.tensor(0.0, device=device)
            l_var = ce_var(out["variant"], var) if (var >= 0).any() else torch.tensor(0.0, device=device)
            l_race = ce_race(out["race"], race) if (race >= 0).any() else torch.tensor(0.0, device=device)

            h_active = hm * is_r[:, None]
            l_haz_raw = F.binary_cross_entropy_with_logits(out["hazards"], haz, pos_weight=haz_pos_w, reduction="none")
            l_haz = (l_haz_raw * h_active).sum() / h_active.sum().clamp_min(1.0)

            o_active = om * is_r[:, None]
            l_ov_raw = F.binary_cross_entropy_with_logits(out["tile_overlay"], ov, pos_weight=ov_pos_w, reduction="none")
            l_ov = (l_ov_raw * o_active).sum() / o_active.sum().clamp_min(1.0)

            loss = 0.6 * l_rune + 1.5 * l_el + 0.8 * l_var + 0.9 * l_race + 1.8 * l_haz + 1.3 * l_ov
            loss.backward()
            optimizer.step()

            tot_loss += float(loss.item())
            steps += 1

        scheduler.step()

        if ep == 1 or ep % 5 == 0 or ep >= epochs - 5:
            metrics = evaluate(model, val_loader, device)
            score = (
                metrics["element_acc"] * 0.35
                + metrics["race_acc"] * 0.20
                + metrics["variant_acc"] * 0.10
                + metrics["macro_hazard_f1"] * 0.20
                + metrics["macro_overlay_f1"] * 0.15
            )
            print(
                f"Epoch {ep:02d}/{epochs} loss={tot_loss/max(1,steps):.4f} "
                f"el={metrics['element_acc']:.4f}(clean={metrics['clean_6color_element_acc']:.4f}) "
                f"var={metrics['variant_acc']:.4f} race={metrics['race_acc']:.4f} "
                f"hazF1={metrics['macro_hazard_f1']:.4f} ovF1={metrics['macro_overlay_f1']:.4f} "
                f"score={score:.4f}"
            )
            if score > best_score:
                best_score = score
                torch.save(
                    {
                        "state_dict": model.state_dict(),
                        "epoch": ep,
                        "best_score": best_score,
                        "param_count": param_cnt,
                    },
                    best_path,
                )

    elapsed = time.perf_counter() - t0
    print(f"[DONE] Training finished in {elapsed:.1f}s. Best score={best_score:.4f}")

    ckpt = torch.load(best_path, map_location=device, weights_only=True)
    model.load_state_dict(ckpt["state_dict"])
    final_eval = evaluate(model, val_loader, device)
    final_eval["train_time_sec"] = round(elapsed, 2)
    final_eval["best_epoch"] = int(ckpt["epoch"])
    final_eval["param_count"] = int(param_cnt)
    (runs_dir / "eval_report.json").write_text(json.dumps(final_eval, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n=== FINAL VALIDATION REPORT ===")
    print(json.dumps(final_eval, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
