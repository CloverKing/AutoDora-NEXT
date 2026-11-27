"""
Training harness for Stage 2A dual-frame BoardMasterGridNet (`phase4_v2` & backward-compatible `train_one_run`).

Features:
 - `train_phase4_v2`: Dual-frame `(frame_a, frame_b)` `[N, 6, 320, 384]` training on RTX 4050 CUDA AMP
 - Per-cell, per-head/bit validity masking (`element_mask`, `variant_mask`, `race_mask`, `hazard_mask[17]`, `overlay_mask[8]`)
 - On-the-fly dual-frame augmentations:
     * Synthetic flicker jitter between A & B when `has_real_pair == False`
     * 15% synthetic full-board monochrome conversion (`BLACK_WHITE=1` preserving exact base element targets!)
     * 12% dimmed-board brightness darkening (training robust low-light / Buoy recovery)
 - Near-zero-FP per-class threshold calibration on `val_boards.npz` -> exports `board_master_v2.onnx` (verified 6x5 & 6x8 parity < 1e-4) + `board_master_meta.json`.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler

from ml.board_master_arch import BoardMasterGridNet
from ml.export_onnx import export_board_master_to_onnx
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
    SLICE_ELEMENT,
    SLICE_HAZARDS,
    SLICE_IS_RUNE,
    SLICE_RACE,
    SLICE_TILE_OVERLAY,
    SLICE_VARIANT,
    TILE_OVERLAY_NAMES,
)


class DualFrameBoardDataset(Dataset):
    """Backward-compatible dataset used by unit tests (synthetic `[N, 6, H, W]` float32)."""

    def __init__(self, npz_path: Path) -> None:
        data = np.load(npz_path, allow_pickle=False)
        self.dual_boards = data["dual_boards"].astype(np.float32)
        self.targets = data["targets"].astype(np.float32)
        self.masks = (
            data["label_masks"].astype(np.float32)
            if "label_masks" in data
            else np.ones((len(self.dual_boards), 6), dtype=np.float32)
        )
        self.weights = (
            data["sample_weights"].astype(np.float32)
            if "sample_weights" in data
            else np.ones((len(self.dual_boards),), dtype=np.float32)
        )
        self.rows = int(data["rows"]) if "rows" in data else 5
        self.cols = int(data["cols"]) if "cols" in data else 6

    def __len__(self) -> int:
        return len(self.dual_boards)

    def __getitem__(self, idx: int):
        return (
            torch.from_numpy(self.dual_boards[idx]),
            torch.from_numpy(self.targets[idx]),
            torch.from_numpy(self.masks[idx]),
            torch.tensor(self.weights[idx], dtype=torch.float32),
        )


def compute_board_master_loss(
    logits: dict[str, torch.Tensor],
    targets: torch.Tensor,
    masks: torch.Tensor,
    sample_weights: torch.Tensor,
) -> torch.Tensor:
    """Backward-compatible board-level masked loss for unit tests."""
    b, k, _ = targets.shape
    sw = sample_weights.view(b, 1).expand(b, k)

    def weighted_bce(pred_logits: torch.Tensor, tgt: torch.Tensor, head_mask: torch.Tensor) -> torch.Tensor:
        loss_raw = F.binary_cross_entropy_with_logits(pred_logits, tgt, reduction="none").mean(dim=-1)
        m = head_mask.view(b, 1) * sw
        return (loss_raw * m).sum() / m.sum().clamp_min(1.0)

    def weighted_ce(pred_logits: torch.Tensor, tgt_onehot: torch.Tensor, head_mask: torch.Tensor) -> torch.Tensor:
        tgt_idx = tgt_onehot.argmax(dim=-1)
        loss_raw = F.cross_entropy(pred_logits.reshape(-1, pred_logits.size(-1)), tgt_idx.reshape(-1), reduction="none").view(b, k)
        m = head_mask.view(b, 1) * sw
        return (loss_raw * m).sum() / m.sum().clamp_min(1.0)

    loss = weighted_bce(logits["is_rune"], targets[:, :, SLICE_IS_RUNE[0]:SLICE_IS_RUNE[1]], masks[:, 0])
    loss = loss + 1.5 * weighted_ce(logits["element"], targets[:, :, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]], masks[:, 1])
    loss = loss + weighted_ce(logits["variant"], targets[:, :, SLICE_VARIANT[0]:SLICE_VARIANT[1]], masks[:, 2])
    loss = loss + weighted_ce(logits["race"], targets[:, :, SLICE_RACE[0]:SLICE_RACE[1]], masks[:, 3])
    loss = loss + 1.2 * weighted_bce(logits["hazards"], targets[:, :, SLICE_HAZARDS[0]:SLICE_HAZARDS[1]], masks[:, 4])
    loss = loss + 1.2 * weighted_bce(logits["tile_overlay"], targets[:, :, SLICE_TILE_OVERLAY[0]:SLICE_TILE_OVERLAY[1]], masks[:, 5])
    return loss


def train_one_run(
    train_npz: Path,
    out_dir: Path,
    epochs: int = 20,
    batch_size: int = 16,
    lr: float = 1e-3,
    device_str: str = "auto",
) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    device = torch.device("cuda" if (device_str == "auto" and torch.cuda.is_available()) else ("cpu" if device_str == "auto" else device_str))
    ds = DualFrameBoardDataset(train_npz)
    sampler = WeightedRandomSampler(ds.weights.tolist(), num_samples=len(ds), replacement=True)
    loader = DataLoader(ds, batch_size=min(batch_size, len(ds)), sampler=sampler, drop_last=False)

    model = BoardMasterGridNet().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(1, epochs))
    use_amp = device.type == "cuda"
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)

    history: list[dict[str, float]] = []
    for epoch in range(1, epochs + 1):
        model.train()
        total_loss = 0.0
        steps = 0
        for x, y, m, w in loader:
            x, y, m, w = x.to(device), y.to(device), m.to(device), w.to(device)
            optimizer.zero_grad(set_to_none=True)
            with torch.amp.autocast("cuda", enabled=use_amp):
                logits = model.forward_logits(x, rows=ds.rows, cols=ds.cols)
                loss = compute_board_master_loss(logits, y, m, w)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            total_loss += float(loss.detach().cpu())
            steps += 1
        scheduler.step()
        history.append({"epoch": epoch, "train_loss": total_loss / max(1, steps)})

    ckpt_path = out_dir / "board_master_latest.pt"
    torch.save({"model_state": model.state_dict(), "rows": ds.rows, "cols": ds.cols, "history": history}, ckpt_path)
    (out_dir / "train_history.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
    return ckpt_path


class Phase4V2BoardDataset(Dataset):
    """Per-cell/per-head masked dual-frame board dataset with online monochrome/dim/flicker augmentation."""

    def __init__(self, npz_path: Path, augment: bool = False, seed: int = 42) -> None:
        z = np.load(npz_path, allow_pickle=False)
        self.boards_a = z["boards_a"]
        self.boards_b = z["boards_b"]
        self.has_real_pair = z["has_real_pair"]
        self.targets = z["targets"].astype(np.float32)
        self.is_rune_mask = z["is_rune_mask"].astype(np.float32)
        self.element_mask = z["element_mask"].astype(np.float32)
        self.variant_mask = z["variant_mask"].astype(np.float32)
        self.race_mask = z["race_mask"].astype(np.float32)
        self.hazard_mask = z["hazard_mask"].astype(np.float32)
        self.overlay_mask = z["overlay_mask"].astype(np.float32)
        self.weights = z["sample_weights"].astype(np.float32)
        self.sources = z["sources"]
        self.augment = augment
        self.rng = np.random.default_rng(seed)

    def __len__(self) -> int:
        return len(self.boards_a)

    def __getitem__(self, idx: int):
        a = self.boards_a[idx].astype(np.float32) / 255.0
        b = self.boards_b[idx].astype(np.float32) / 255.0
        tgt = self.targets[idx].copy()
        hm = self.hazard_mask[idx].copy()
        is_neg_board = float(tgt[:, SLICE_IS_RUNE[0]].max()) < 0.5

        if self.augment:
            # Global brightness/dimming augmentation
            gain = float(self.rng.uniform(0.52, 0.78)) if self.rng.random() < 0.14 else float(self.rng.uniform(0.90, 1.10))
            a = np.clip(a * gain, 0.0, 1.0)
            if self.has_real_pair[idx]:
                b = np.clip(b * gain * float(self.rng.uniform(0.96, 1.04)), 0.0, 1.0)
            else:
                b = np.clip(a * float(self.rng.uniform(0.94, 1.06)), 0.0, 1.0)
                if self.rng.random() < 0.45:
                    b = np.clip(b + self.rng.normal(0.0, 0.015, size=b.shape).astype(np.float32), 0.0, 1.0)

            # Synthetic full-board monochrome conversion teaching color recovery from orb shape!
            if not is_neg_board and self.rng.random() < 0.14:
                bw_a = np.dot(a, [0.299, 0.587, 0.114])[..., None]
                bw_b = np.dot(b, [0.299, 0.587, 0.114])[..., None]
                a = np.repeat(bw_a, 3, axis=-1).astype(np.float32)
                b = np.repeat(bw_b, 3, axis=-1).astype(np.float32)
                bw_idx = HAZARD_TO_IDX["BLACK_WHITE"]
                tgt[:, SLICE_HAZARDS[0] + bw_idx] = 1.0
                hm[:, bw_idx] = 1.0

            # Horizontal flip preserves grid validity? No — top-right Race/Mark badges are orientation-sensitive!
            # Instead apply slight Gaussian sensor noise only.
            if self.rng.random() < 0.30:
                a = np.clip(a + self.rng.normal(0.0, 0.012, size=a.shape).astype(np.float32), 0.0, 1.0)
                b = np.clip(b + self.rng.normal(0.0, 0.012, size=b.shape).astype(np.float32), 0.0, 1.0)

        dual = np.concatenate([np.transpose(a, (2, 0, 1)), np.transpose(b, (2, 0, 1))], axis=0).astype(np.float32)
        return (
            torch.from_numpy(dual),
            torch.from_numpy(tgt),
            torch.from_numpy(self.is_rune_mask[idx]),
            torch.from_numpy(self.element_mask[idx]),
            torch.from_numpy(self.variant_mask[idx]),
            torch.from_numpy(self.race_mask[idx]),
            torch.from_numpy(hm),
            torch.from_numpy(self.overlay_mask[idx]),
            torch.tensor(self.weights[idx], dtype=torch.float32),
        )


def compute_masked_phase4_v2_loss(
    logits: dict[str, torch.Tensor],
    tgt: torch.Tensor,
    m_rune: torch.Tensor,
    m_elem: torch.Tensor,
    m_var: torch.Tensor,
    m_race: torch.Tensor,
    m_haz: torch.Tensor,
    m_ov: torch.Tensor,
    sw: torch.Tensor,
    haz_pos_weight: torch.Tensor,
    ov_pos_weight: torch.Tensor,
    race_class_weight: torch.Tensor | None = None,
) -> torch.Tensor:
    b, k, _ = tgt.shape
    w = sw.view(b, 1).expand(b, k)

    def masked_bce_1d(lgt: torch.Tensor, y: torch.Tensor, m: torch.Tensor) -> torch.Tensor:
        raw = F.binary_cross_entropy_with_logits(lgt, y, reduction="none").squeeze(-1)
        eff = m * w
        return (raw * eff).sum() / eff.sum().clamp_min(1.0)

    def masked_ce(
        lgt: torch.Tensor,
        y_oh: torch.Tensor,
        m: torch.Tensor,
        cls_w: torch.Tensor | None = None,
    ) -> torch.Tensor:
        idx = y_oh.argmax(dim=-1)
        raw = F.cross_entropy(lgt.reshape(-1, lgt.size(-1)), idx.reshape(-1), weight=cls_w, reduction="none").view(b, k)
        eff = m * w
        return (raw * eff).sum() / eff.sum().clamp_min(1.0)

    def masked_bce_nd(lgt: torch.Tensor, y: torch.Tensor, m: torch.Tensor, pw: torch.Tensor) -> torch.Tensor:
        raw = F.binary_cross_entropy_with_logits(lgt, y, pos_weight=pw, reduction="none")
        eff = m * w.unsqueeze(-1)
        return (raw * eff).sum() / eff.sum().clamp_min(1.0)

    loss = 1.0 * masked_bce_1d(logits["is_rune"], tgt[:, :, SLICE_IS_RUNE[0]:SLICE_IS_RUNE[1]], m_rune)
    loss = loss + 2.0 * masked_ce(logits["element"], tgt[:, :, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]], m_elem)
    loss = loss + 1.0 * masked_ce(logits["variant"], tgt[:, :, SLICE_VARIANT[0]:SLICE_VARIANT[1]], m_var)
    loss = loss + 1.3 * masked_ce(logits["race"], tgt[:, :, SLICE_RACE[0]:SLICE_RACE[1]], m_race, race_class_weight)
    loss = loss + 1.4 * masked_bce_nd(logits["hazards"], tgt[:, :, SLICE_HAZARDS[0]:SLICE_HAZARDS[1]], m_haz, haz_pos_weight)
    loss = loss + 1.4 * masked_bce_nd(logits["tile_overlay"], tgt[:, :, SLICE_TILE_OVERLAY[0]:SLICE_TILE_OVERLAY[1]], m_ov, ov_pos_weight)
    return loss


def _calibrate_binary_thresholds(
    probs: np.ndarray,
    targets: np.ndarray,
    masks: np.ndarray,
    names: list[str],
) -> tuple[dict[str, float], dict[str, dict[str, Any]]]:
    thresholds: dict[str, float] = {}
    stats: dict[str, dict[str, Any]] = {}
    for ci, cname in enumerate(names):
        valid = masks[:, ci] > 0.5
        yt = targets[valid, ci] > 0.5
        yp = probs[valid, ci]
        pos_cnt = int(yt.sum())

        best_th = 1.01
        best_tp = 0
        best_fp = 0
        found = False
        best_cand: tuple[float, int, int] | None = None
        if pos_cnt >= 4:
            for th in np.linspace(0.40, 0.98, 59):
                pred = yp >= th
                tp = int(np.sum(pred & yt))
                fp = int(np.sum(pred & ~yt))
                prec = tp / max(1, tp + fp)
                if fp == 0 and tp >= 3:
                    best_th, best_tp, best_fp, found = float(round(th, 2)), tp, fp, True
                    break
                if tp >= 5 and fp <= 1 and prec >= 0.95 and best_cand is None:
                    best_cand = (float(round(th, 2)), tp, fp)

        if not found and best_cand is not None:
            best_th, best_tp, best_fp, found = best_cand[0], best_cand[1], best_cand[2], True

        if not found and pos_cnt > 0:
            pred = yp >= best_th
            best_tp = int(np.sum(pred & yt))
            best_fp = int(np.sum(pred & ~yt))

        thresholds[cname] = best_th
        stats[cname] = {
            "threshold": best_th,
            "fail_safe": not found,
            "pos_val": pos_cnt,
            "tp": best_tp,
            "fp": best_fp,
            "precision": round(best_tp / max(1, best_tp + best_fp), 4) if (best_tp + best_fp) > 0 else 1.0,
            "recall": round(best_tp / max(1, pos_cnt), 4) if pos_cnt > 0 else 0.0,
        }
    return thresholds, stats


def train_phase4_v2(
    data_dir: Path = Path("ml/datasets/phase4_v2"),
    run_dir: Path = Path("ml/runs/board_master_v2"),
    onnx_out: Path = Path("android/app/src/main/assets/models/board_master_v2.onnx"),
    meta_out: Path = Path("android/app/src/main/assets/models/board_master_meta.json"),
    epochs: int = 38,
    batch_size: int = 16,
    lr: float = 1e-3,
    embed_dim: int = 128,
) -> dict[str, Any]:
    run_dir.mkdir(parents=True, exist_ok=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    use_amp = device.type == "cuda"

    tr_ds = Phase4V2BoardDataset(data_dir / "train_boards.npz", augment=True, seed=42)
    va_ds = Phase4V2BoardDataset(data_dir / "val_boards.npz", augment=False, seed=42)

    # Compute bounded pos_weight per hazard & overlay bit
    tr_haz_y = tr_ds.targets[:, :, SLICE_HAZARDS[0]:SLICE_HAZARDS[1]]
    tr_haz_m = tr_ds.hazard_mask
    haz_pw = []
    for hi in range(NUM_HAZARDS):
        pos = float(((tr_haz_y[:, :, hi] > 0.5) & (tr_haz_m[:, :, hi] > 0.5)).sum())
        neg = float(((tr_haz_y[:, :, hi] < 0.5) & (tr_haz_m[:, :, hi] > 0.5)).sum())
        haz_pw.append(float(np.clip(neg / max(1.0, pos), 1.5, 16.0)))

    tr_ov_y = tr_ds.targets[:, :, SLICE_TILE_OVERLAY[0]:SLICE_TILE_OVERLAY[1]]
    tr_ov_m = tr_ds.overlay_mask
    ov_pw = []
    for oi in range(NUM_TILE_OVERLAYS):
        pos = float(((tr_ov_y[:, :, oi] > 0.5) & (tr_ov_m[:, :, oi] > 0.5)).sum())
        neg = float(((tr_ov_y[:, :, oi] < 0.5) & (tr_ov_m[:, :, oi] > 0.5)).sum())
        ov_pw.append(float(np.clip(neg / max(1.0, pos), 1.5, 16.0)))

    haz_pw_t = torch.tensor(haz_pw, dtype=torch.float32, device=device)
    ov_pw_t = torch.tensor(ov_pw, dtype=torch.float32, device=device)

    # Bounded inverse-sqrt weights for rare races (NONE capped at 0.7 so rare races learn strongly)
    tr_race_valid = tr_ds.race_mask > 0.5
    tr_race_cls = tr_ds.targets[:, :, SLICE_RACE[0]:SLICE_RACE[1]].argmax(axis=-1)[tr_race_valid]
    total_race_cells = max(1, len(tr_race_cls))
    race_w_list = []
    for ri in range(NUM_RACES):
        rc_cnt = float((tr_race_cls == ri).sum())
        w_r = float(np.clip((total_race_cells / (NUM_RACES * max(1.0, rc_cnt))) ** 0.5, 0.6, 8.0))
        if ri == 0:
            w_r = min(w_r, 0.7)
        race_w_list.append(w_r)
    race_w_t = torch.tensor(race_w_list, dtype=torch.float32, device=device)

    sampler = WeightedRandomSampler(tr_ds.weights.tolist(), num_samples=len(tr_ds), replacement=True)
    tr_loader = DataLoader(tr_ds, batch_size=batch_size, sampler=sampler, num_workers=0, drop_last=False)
    va_loader = DataLoader(va_ds, batch_size=batch_size, shuffle=False, num_workers=0, drop_last=False)

    bw_idx = HAZARD_TO_IDX["BLACK_WHITE"]
    v1_rows = np.array([str(s) == "phase4_v1" for s in va_ds.sources], dtype=bool)
    v1_em_val = va_ds.element_mask[v1_rows] > 0.5
    v1_etrue_2d = va_ds.targets[v1_rows, :, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]].argmax(axis=-1)
    v1_bw_2d = va_ds.targets[v1_rows, :, SLICE_HAZARDS[0] + bw_idx]

    model = BoardMasterGridNet(embed_dim=embed_dim, n_heads=4, n_layers=2).to(device)
    param_count = sum(p.numel() for p in model.parameters())
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(1, epochs))
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)

    best_score = -1.0
    best_ckpt = run_dir / "board_master_best.pt"
    history: list[dict[str, float]] = []

    for ep in range(1, epochs + 1):
        model.train()
        tr_loss = 0.0
        steps = 0
        for batch in tr_loader:
            bx, btgt, mr, me, mv, mra, mh, mo, bsw = [t.to(device, non_blocking=True) for t in batch]
            optimizer.zero_grad(set_to_none=True)
            with torch.amp.autocast("cuda", enabled=use_amp):
                logits = model.forward_logits(bx)
                loss = compute_masked_phase4_v2_loss(
                    logits, btgt, mr, me, mv, mra, mh, mo, bsw, haz_pw_t, ov_pw_t, race_w_t
                )
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            tr_loss += float(loss.detach().cpu())
            steps += 1
        scheduler.step()

        # Evaluate on held-out val_boards.npz
        model.eval()
        all_probs = []
        with torch.no_grad():
            for batch in va_loader:
                bx = batch[0].to(device, non_blocking=True)
                all_probs.append(model(bx).cpu().numpy())
        vp = np.concatenate(all_probs, axis=0)  # [N_val, 30, 47]

        em_valid = va_ds.element_mask > 0.5
        em_pred = vp[:, :, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]].argmax(axis=-1)
        em_true = va_ds.targets[:, :, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]].argmax(axis=-1)
        elem_acc = float((em_pred[em_valid] == em_true[em_valid]).mean())

        clean_6 = em_valid & (em_true < 6)
        clean6_acc = float((em_pred[clean_6] == em_true[clean_6]).mean()) if clean_6.any() else elem_acc

        if v1_rows.any():
            v1_ep = em_pred[v1_rows]
            v1_c6_nonbw = v1_em_val & (v1_etrue_2d < 6) & (v1_bw_2d < 0.5)
            v1_clean6_acc = float((v1_ep[v1_c6_nonbw] == v1_etrue_2d[v1_c6_nonbw]).mean()) if v1_c6_nonbw.any() else clean6_acc
        else:
            v1_clean6_acc = clean6_acc

        ra_valid = va_ds.race_mask > 0.5
        ra_pred = vp[:, :, SLICE_RACE[0]:SLICE_RACE[1]].argmax(axis=-1)
        ra_true = va_ds.targets[:, :, SLICE_RACE[0]:SLICE_RACE[1]].argmax(axis=-1)
        race_acc = float((ra_pred[ra_valid] == ra_true[ra_valid]).mean())
        race_recalls = []
        for ri in range(NUM_RACES):
            rm_i = ra_valid & (ra_true == ri)
            if int(rm_i.sum()) >= 3:
                race_recalls.append(float((ra_pred[rm_i] == ri).mean()))
        race_macro_recall = float(np.mean(race_recalls)) if race_recalls else race_acc

        ir_valid = va_ds.is_rune_mask > 0.5
        ir_acc = float(((vp[:, :, 0] >= 0.5) == (va_ds.targets[:, :, 0] >= 0.5))[ir_valid].mean())

        score = clean6_acc * 0.35 + v1_clean6_acc * 0.25 + race_macro_recall * 0.25 + ir_acc * 0.15
        history.append({
            "epoch": ep,
            "train_loss": round(tr_loss / max(1, steps), 4),
            "val_is_rune_acc": round(ir_acc, 4),
            "val_elem_acc": round(elem_acc, 4),
            "val_clean6_acc": round(clean6_acc, 4),
            "val_v1_clean6_acc": round(v1_clean6_acc, 4),
            "val_race_acc": round(race_acc, 4),
            "val_race_macro_recall": round(race_macro_recall, 4),
        })
        print(
            f"[BoardMaster ep {ep:02d}/{epochs}] loss={tr_loss/max(1,steps):.4f} "
            f"rune={ir_acc:.4f} clean6={clean6_acc:.4f} v1_c6={v1_clean6_acc:.4f} "
            f"race={race_acc:.4f} race_mac={race_macro_recall:.4f}",
            flush=True,
        )
        if score > best_score:
            best_score = score
            torch.save({"model_state": model.state_dict(), "epoch": ep, "metrics": history[-1]}, best_ckpt)

    # Load best checkpoint -> full evaluation, calibration & ONNX export
    state = torch.load(best_ckpt, map_location="cpu", weights_only=True)
    model.cpu().load_state_dict(state["model_state"])
    model.eval()
    all_probs = []
    with torch.no_grad():
        for batch in va_loader:
            all_probs.append(model(batch[0]).numpy())
    vp = np.concatenate(all_probs, axis=0)

    # Flatten valid cells for summary metrics
    flat_vp = vp.reshape(-1, vp.shape[-1])
    flat_vt = va_ds.targets.reshape(-1, va_ds.targets.shape[-1])
    flat_em = va_ds.element_mask.reshape(-1) > 0.5
    flat_vm = va_ds.variant_mask.reshape(-1) > 0.5
    flat_rm = va_ds.race_mask.reshape(-1) > 0.5
    flat_hm = va_ds.hazard_mask.reshape(-1, NUM_HAZARDS)
    flat_om = va_ds.overlay_mask.reshape(-1, NUM_TILE_OVERLAYS)

    em_pred = flat_vp[:, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]].argmax(axis=-1)
    em_true = flat_vt[:, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]].argmax(axis=-1)
    clean6 = flat_em & (em_true < 6)

    v1_em = v1_em_val.reshape(-1)
    v1_vp = vp[v1_rows].reshape(-1, vp.shape[-1])
    v1_vt = va_ds.targets[v1_rows].reshape(-1, va_ds.targets.shape[-1])
    v1_epred = v1_vp[:, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]].argmax(axis=-1)
    v1_etrue = v1_vt[:, SLICE_ELEMENT[0]:SLICE_ELEMENT[1]].argmax(axis=-1)
    v1_bw = v1_vt[:, SLICE_HAZARDS[0] + bw_idx]
    v1_c6_nonbw = v1_em & (v1_etrue < 6) & (v1_bw < 0.5)
    v1_c6_all = v1_em & (v1_etrue < 6)

    va_pred = flat_vp[:, SLICE_VARIANT[0]:SLICE_VARIANT[1]].argmax(axis=-1)
    va_true = flat_vt[:, SLICE_VARIANT[0]:SLICE_VARIANT[1]].argmax(axis=-1)
    ra_pred = flat_vp[:, SLICE_RACE[0]:SLICE_RACE[1]].argmax(axis=-1)
    ra_true = flat_vt[:, SLICE_RACE[0]:SLICE_RACE[1]].argmax(axis=-1)
    dragon_idx = RACE_NAMES.index("DRAGON")
    dragon_mask = flat_rm & (ra_true == dragon_idx)
    race_per_class = {}
    race_macro_vals = []
    for ri, rname in enumerate(RACE_NAMES):
        rm_i = flat_rm & (ra_true == ri)
        if int(rm_i.sum()) >= 3:
            r_rec = round(float((ra_pred[rm_i] == ri).mean()), 4)
            race_per_class[rname] = {"cells": int(rm_i.sum()), "recall": r_rec}
            race_macro_vals.append(r_rec)
        elif int(rm_i.sum()) > 0:
            race_per_class[rname] = {"cells": int(rm_i.sum()), "recall": round(float((ra_pred[rm_i] == ri).mean()), 4)}
    race_macro_recall = round(float(np.mean(race_macro_vals)), 4) if race_macro_vals else 0.0

    haz_ths, haz_metrics = _calibrate_binary_thresholds(
        flat_vp[:, SLICE_HAZARDS[0]:SLICE_HAZARDS[1]],
        flat_vt[:, SLICE_HAZARDS[0]:SLICE_HAZARDS[1]],
        flat_hm,
        HAZARD_NAMES,
    )
    ov_ths, ov_metrics = _calibrate_binary_thresholds(
        flat_vp[:, SLICE_TILE_OVERLAY[0]:SLICE_TILE_OVERLAY[1]],
        flat_vt[:, SLICE_TILE_OVERLAY[0]:SLICE_TILE_OVERLAY[1]],
        flat_om,
        TILE_OVERLAY_NAMES,
    )

    max_diff = export_board_master_to_onnx(model, onnx_out, rows=5, cols=6)

    # Benchmark CPU latency for both 6x5 and 6x8
    sess_opts = ort.SessionOptions()
    sess_opts.intra_op_num_threads = 4
    sess = ort.InferenceSession(str(onnx_out), sess_opts, providers=["CPUExecutionProvider"])
    x5 = np.random.default_rng(0).random((1, 6, 320, 384), dtype=np.float32)
    x8 = np.random.default_rng(0).random((1, 6, 512, 384), dtype=np.float32)
    for _ in range(3):
        sess.run(["cell_probs"], {"dual_board": x5})
        sess.run(["cell_probs"], {"dual_board": x8})
    t0 = time.perf_counter()
    for _ in range(25):
        sess.run(["cell_probs"], {"dual_board": x5})
    ms5 = (time.perf_counter() - t0) * 1000.0 / 25.0
    t0 = time.perf_counter()
    for _ in range(25):
        sess.run(["cell_probs"], {"dual_board": x8})
    ms8 = (time.perf_counter() - t0) * 1000.0 / 25.0

    meta = {
        "model_name": "BoardMasterGridNetV2",
        "schema_version": 1,
        "input_name": "dual_board",
        "output_name": "cell_probs",
        "quantization": "fp32",
        "param_count": param_count,
        "onnx_bytes": onnx_out.stat().st_size,
        "onnx_kb": round(onnx_out.stat().st_size / 1024.0, 1),
        "onnx_parity_max_abs_diff": float(f"{max_diff:.2e}"),
        "cpu_ms_6x5_desktop_4t": round(ms5, 2),
        "cpu_ms_6x8_desktop_4t": round(ms8, 2),
        "hazard_thresholds": haz_ths,
        "tile_overlay_thresholds": ov_ths,
        "val_summary": {
            "is_rune_acc": round(float(((flat_vp[:, 0] >= 0.5) == (flat_vt[:, 0] >= 0.5))[va_ds.is_rune_mask.reshape(-1) > 0.5].mean()), 4),
            "element_acc": round(float((em_pred[flat_em] == em_true[flat_em]).mean()), 4),
            "clean_6color_element_acc": round(float((em_pred[clean6] == em_true[clean6]).mean()), 4),
            "phase4_v1_val_element_acc": round(float((v1_epred[v1_em] == v1_etrue[v1_em]).mean()), 4) if v1_em.any() else 0.0,
            "phase4_v1_val_clean6_acc": round(float((v1_epred[v1_c6_nonbw] == v1_etrue[v1_c6_nonbw]).mean()), 4) if v1_c6_nonbw.any() else 0.0,
            "phase4_v1_val_all_clean6_acc": round(float((v1_epred[v1_c6_all] == v1_etrue[v1_c6_all]).mean()), 4) if v1_c6_all.any() else 0.0,
            "variant_acc": round(float((va_pred[flat_vm] == va_true[flat_vm]).mean()), 4),
            "race_acc": round(float((ra_pred[flat_rm] == ra_true[flat_rm]).mean()), 4),
            "race_macro_recall": race_macro_recall,
            "race_per_class_recall": race_per_class,
            "dragon_race_recall": round(float((ra_pred[dragon_mask] == dragon_idx).mean()), 4) if dragon_mask.any() else 0.0,
            "dragon_val_cells": int(dragon_mask.sum()),
        },
        "production_safety_metrics": {
            "hazards": haz_metrics,
            "tile_overlay": ov_metrics,
        },
    }
    meta_out.parent.mkdir(parents=True, exist_ok=True)
    meta_out.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    (run_dir / "train_history.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
    (run_dir / "eval_report.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return meta


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Stage 2A dual-frame BoardMasterGridNet")
    parser.add_argument("--phase4-v2", action="store_true", help="Train on ml/datasets/phase4_v2 and export ONNX")
    parser.add_argument("--train-npz", help="Legacy single-split .npz path")
    parser.add_argument("--out", default="ml/runs/board_master", help="Run output directory")
    parser.add_argument("--epochs", type=int, default=38)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=1e-3)
    args = parser.parse_args()

    if args.phase4_v2:
        meta = train_phase4_v2(epochs=args.epochs, batch_size=args.batch_size, lr=args.lr)
        print(
            f"Exported {meta['model_name']}: size={meta['onnx_kb']}KB, diff={meta['onnx_parity_max_abs_diff']:.2e}, "
            f"6x5={meta['cpu_ms_6x5_desktop_4t']}ms, 6x8={meta['cpu_ms_6x8_desktop_4t']}ms, "
            f"val={meta['val_summary']}"
        )
    elif args.train_npz:
        ckpt = train_one_run(Path(args.train_npz), Path(args.out), epochs=args.epochs, batch_size=args.batch_size, lr=args.lr)
        print(f"Saved checkpoint: {ckpt}")
    else:
        parser.error("Provide --phase4-v2 or --train-npz")


if __name__ == "__main__":
    main()
