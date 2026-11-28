"""
Train Stage 2B CenterNet spatial `EnemyIconNet` on RTX 4050 GPU with direct `[36, 20, 32]` Gaussian
heatmap focal loss + strip-peak BCE loss (~2.0s/epoch!), evaluate on fixed held-out `val_strips.npz`,
calibrate near-zero-FP thresholds per class, and export verified `enemy_icon_v1.onnx` + `enemy_icon_meta.json`.
"""

from __future__ import annotations

import argparse
import json
import random
import time
from collections import defaultdict
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

from ml.enemy_icon_arch import ENEMY_ICON_CLASSES, NUM_ENEMY_ICON_CLASSES, STRIP_H, STRIP_W, EnemyIconNet
from ml.export_onnx import export_enemy_icon_to_onnx
from ml.tools.build_enemy_icon_dataset import composite_strip_sample_fast


class DynamicEnemyIconTrainDataset(Dataset):
    """Fast in-RAM numpy multi-label strip + `[36, 20, 32]` spatial heatmap generator."""

    def __init__(self, assets_npz: Path, samples_per_epoch: int = 12000, seed: int = 42) -> None:
        z = np.load(assets_npz, allow_pickle=False)
        self.bg_strips = z["bg_strips"]
        pos_cls = z["pos_cls"].tolist()
        pos_rgba = z["pos_rgba"]
        pos_wh = z["pos_wh"].tolist()
        neg_rgba = z["neg_rgba"]
        neg_wh = z["neg_wh"].tolist()

        self.samples_per_epoch = samples_per_epoch
        self.base_seed = seed
        self.epoch = 0

        self.by_cls: dict[int, list[tuple[int, np.ndarray, int, int]]] = defaultdict(list)
        for cidx, rgba, (sw, sh) in zip(pos_cls, pos_rgba, pos_wh):
            self.by_cls[int(cidx)].append((int(cidx), rgba, int(sw), int(sh)))
        self.neg_items = [(neg_rgba[i], int(neg_wh[i][0]), int(neg_wh[i][1])) for i in range(len(neg_rgba))]
        self.active_classes = sorted(self.by_cls.keys())

    def set_epoch(self, epoch: int) -> None:
        self.epoch = epoch

    def __len__(self) -> int:
        return self.samples_per_epoch

    def __getitem__(self, idx: int):
        rng = random.Random((self.base_seed + self.epoch * 131071 + idx) & 0x7FFFFFFF)
        bg = self.bg_strips[rng.randrange(len(self.bg_strips))]
        if idx % 5 == 0:
            im_u8, tgt, hmap = composite_strip_sample_fast(rng, bg, [], self.neg_items, force_negative=True)
        else:
            anchor_cls = self.active_classes[(idx - idx // 5) % len(self.active_classes)]
            picks = [rng.choice(self.by_cls[anchor_cls])]
            if rng.random() < 0.65:
                for _ in range(rng.randint(1, 3)):
                    oc = rng.choice(self.active_classes)
                    if oc != anchor_cls or rng.random() < 0.20:
                        picks.append(rng.choice(self.by_cls[oc]))
            im_u8, tgt, hmap = composite_strip_sample_fast(rng, bg, picks, self.neg_items, force_negative=False)

        x = torch.from_numpy(np.transpose(im_u8, (2, 0, 1)).astype(np.float32) * (1.0 / 255.0))
        return x, torch.from_numpy(tgt), torch.from_numpy(hmap)


def centernet_focal_and_peak_loss(
    spatial_logits: torch.Tensor,
    hmap_tgt: torch.Tensor,
    strip_tgt: torch.Tensor,
) -> torch.Tensor:
    """
    Combines CenterNet penalty-reduced Gaussian focal loss on `[B, 36, 20, 32]` with
    strip-peak `amax` BCE loss on `[B, 36]` so every icon center receives strong direct
    supervision AND peak calibration matches `forward(x)` 1:1!
    """
    probs = torch.sigmoid(spatial_logits.float()).clamp(1e-6, 1.0 - 1e-6)
    pos_mask = hmap_tgt.eq(1.0)
    neg_mask = hmap_tgt.lt(1.0)
    neg_weights = torch.pow(1.0 - hmap_tgt, 4.0)

    pos_loss = -torch.log(probs) * torch.pow(1.0 - probs, 2.0) * pos_mask
    neg_loss = -torch.log(1.0 - probs) * torch.pow(probs, 2.0) * neg_weights * neg_mask

    num_pos = pos_mask.float().sum().clamp_min(1.0)
    focal_loss = (pos_loss.sum() + neg_loss.sum()) / num_pos

    peak_logits = spatial_logits.float().amax(dim=(-2, -1))
    peak_bce = F.binary_cross_entropy_with_logits(
        peak_logits,
        strip_tgt.float(),
        pos_weight=torch.full((NUM_ENEMY_ICON_CLASSES,), 4.0, device=spatial_logits.device),
    )
    return focal_loss + 1.2 * peak_bce


def calibrate_near_zero_fp_thresholds(
    probs: np.ndarray,
    targets: np.ndarray,
    class_stats: dict[str, Any],
) -> tuple[dict[str, float], dict[str, dict[str, Any]]]:
    thresholds: dict[str, float] = {}
    metrics: dict[str, dict[str, Any]] = {}
    neg_rows = targets.sum(axis=1) == 0

    for cidx, cname in enumerate(ENEMY_ICON_CLASSES):
        yt = targets[:, cidx] > 0.5
        yp = probs[:, cidx]
        pos_total = int(yt.sum())
        src_icons = int(class_stats.get(cname, {}).get("total_source_icons", 0))

        best_th = 1.01
        best_tp = 0
        best_fp = 0
        found = False
        min_th = 0.50 if src_icons >= 2 else 0.65

        if pos_total > 0 and src_icons >= 1:
            best_f05 = -1.0
            for th in np.linspace(max(min_th, 0.56), 0.96, 41):
                pred = yp >= th
                tp = int(np.sum(pred & yt))
                fp = int(np.sum(pred & ~yt))
                neg_fp = int(np.sum(pred & neg_rows))
                prec = tp / max(1, tp + fp)
                rec = tp / max(1, pos_total)
                # Strict 0-FP on all 450 negative/distractor strips + >=92% overall precision, maximizing F_0.5
                if neg_fp == 0 and tp >= 6 and prec >= 0.92:
                    f05 = (1.25 * prec * rec) / max(1e-6, 0.25 * prec + rec)
                    if f05 > best_f05:
                        best_f05 = f05
                        best_th, best_tp, best_fp, found = float(round(th, 2)), tp, fp, True

        if not found:
            pred = yp >= best_th
            best_tp = int(np.sum(pred & yt))
            best_fp = int(np.sum(pred & ~yt))

        p50 = yp >= 0.50
        tp50, fp50, fn50 = int(np.sum(p50 & yt)), int(np.sum(p50 & ~yt)), int(np.sum(~p50 & yt))
        f1_50 = (2 * tp50) / max(1, 2 * tp50 + fp50 + fn50)

        thresholds[cname] = best_th
        metrics[cname] = {
            "threshold": best_th,
            "fail_safe": not found,
            "source_icons": src_icons,
            "pos_val": pos_total,
            "tp": best_tp,
            "fp": best_fp,
            "precision": round(best_tp / max(1, best_tp + best_fp), 4) if (best_tp + best_fp) > 0 else 1.0,
            "recall": round(best_tp / max(1, pos_total), 4),
            "f1_at_0_50": round(f1_50, 4),
        }
    return thresholds, metrics


def train_and_export(
    data_dir: Path = Path("ml/datasets/enemy_icon_v1"),
    run_dir: Path = Path("ml/runs/enemy_icon_v1"),
    onnx_out: Path = Path("android/app/src/main/assets/models/enemy_icon_v1.onnx"),
    meta_out: Path = Path("android/app/src/main/assets/models/enemy_icon_meta.json"),
    epochs: int = 32,
    batch_size: int = 64,
    lr: float = 2.0e-3,
    samples_per_epoch: int = 12000,
) -> dict[str, Any]:
    run_dir.mkdir(parents=True, exist_ok=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    use_amp = device.type == "cuda"

    split_manifest = json.loads((data_dir / "split_manifest.json").read_text(encoding="utf-8"))
    val_z = np.load(data_dir / "val_strips.npz", allow_pickle=False)
    val_x = np.transpose(val_z["strips"], (0, 3, 1, 2)).astype(np.float32) * (1.0 / 255.0)
    val_y = val_z["targets"].astype(np.float32)

    train_ds = DynamicEnemyIconTrainDataset(data_dir / "train_assets.npz", samples_per_epoch=samples_per_epoch)
    loader = DataLoader(train_ds, batch_size=batch_size, shuffle=False, num_workers=0, drop_last=False)

    model = EnemyIconNet(width=72).to(device)
    param_count = sum(p.numel() for p in model.parameters())

    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(1, epochs))
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)

    best_f1 = -1.0
    best_ckpt = run_dir / "enemy_icon_best.pt"
    history: list[dict[str, float]] = []

    val_tensor_x = torch.from_numpy(val_x)
    for ep in range(1, epochs + 1):
        train_ds.set_epoch(ep)
        model.train()
        tr_loss = 0.0
        steps = 0
        for bx, by_strip, by_hmap in loader:
            bx = bx.to(device, non_blocking=True)
            by_strip = by_strip.to(device, non_blocking=True)
            by_hmap = by_hmap.to(device, non_blocking=True)
            optimizer.zero_grad(set_to_none=True)
            with torch.amp.autocast("cuda", enabled=use_amp):
                sp_logits = model.forward_spatial_logits(bx)
                loss = centernet_focal_and_peak_loss(sp_logits, by_hmap, by_strip)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            tr_loss += float(loss.detach().cpu())
            steps += 1
        scheduler.step()

        model.eval()
        preds_list = []
        with torch.no_grad():
            for s in range(0, len(val_tensor_x), 128):
                xb = val_tensor_x[s:s + 128].to(device)
                preds_list.append(model(xb).cpu().numpy())
        val_probs = np.concatenate(preds_list, axis=0)
        f1s = []
        for c in range(NUM_ENEMY_ICON_CLASSES):
            yt = val_y[:, c] > 0.5
            yp = val_probs[:, c] >= 0.50
            tp = int(np.sum(yp & yt))
            fp = int(np.sum(yp & ~yt))
            fn = int(np.sum(~yp & yt))
            f1s.append((2 * tp) / max(1, 2 * tp + fp + fn))
        macro_f1 = float(np.mean(f1s))
        history.append({"epoch": ep, "train_loss": round(tr_loss / max(1, steps), 4), "val_macro_f1_050": round(macro_f1, 4)})
        print(f"[EnemyIconNet ep {ep:02d}/{epochs}] loss={tr_loss/max(1,steps):.4f} val_macro_f1@0.5={macro_f1:.4f}", flush=True)

        if macro_f1 > best_f1:
            best_f1 = macro_f1
            torch.save({"model_state": model.state_dict(), "epoch": ep, "val_macro_f1": macro_f1}, best_ckpt)

    state = torch.load(best_ckpt, map_location="cpu", weights_only=True)
    model.cpu().load_state_dict(state["model_state"])
    model.eval()
    preds_list = []
    with torch.no_grad():
        for s in range(0, len(val_tensor_x), 128):
            preds_list.append(model(val_tensor_x[s:s + 128]).numpy())
    val_probs = np.concatenate(preds_list, axis=0)

    thresholds, class_metrics = calibrate_near_zero_fp_thresholds(val_probs, val_y, split_manifest["class_stats"])
    max_diff = export_enemy_icon_to_onnx(model, onnx_out, strip_h=STRIP_H, strip_w=STRIP_W)

    sess_opts = ort.SessionOptions()
    sess_opts.intra_op_num_threads = 4
    sess = ort.InferenceSession(str(onnx_out), sess_opts, providers=["CPUExecutionProvider"])
    bench_in = np.random.default_rng(0).random((1, 3, STRIP_H, STRIP_W), dtype=np.float32)
    for _ in range(5):
        sess.run(["icon_probs"], {"upper_strip": bench_in})
    t0 = time.perf_counter()
    for _ in range(40):
        sess.run(["icon_probs"], {"upper_strip": bench_in})
    cpu_ms = (time.perf_counter() - t0) * 1000.0 / 40.0

    neg_mask = val_y.sum(axis=1) == 0
    neg_fp_strips = 0
    for r in np.flatnonzero(neg_mask):
        if any(val_probs[r, i] >= thresholds[ENEMY_ICON_CLASSES[i]] for i in range(NUM_ENEMY_ICON_CLASSES)):
            neg_fp_strips += 1

    active_classes = [c for c, m in class_metrics.items() if not m["fail_safe"]]
    meta = {
        "model_name": "EnemyIconNetV2",
        "schema_version": 2,
        "input_name": "upper_strip",
        "output_name": "icon_probs",
        "input_shape": [1, 3, STRIP_H, STRIP_W],
        "num_classes": NUM_ENEMY_ICON_CLASSES,
        "classes": ENEMY_ICON_CLASSES,
        "param_count": param_count,
        "onnx_bytes": onnx_out.stat().st_size,
        "onnx_kb": round(onnx_out.stat().st_size / 1024.0, 1),
        "onnx_parity_max_abs_diff": float(f"{max_diff:.2e}"),
        "cpu_ms_desktop_4t": round(cpu_ms, 2),
        "best_val_macro_f1_050": round(best_f1, 4),
        "active_calibrated_classes": len(active_classes),
        "neg_strip_fp_count": int(neg_fp_strips),
        "neg_strip_total": int(neg_mask.sum()),
        "class_thresholds": thresholds,
        "class_metrics": class_metrics,
    }
    meta_out.parent.mkdir(parents=True, exist_ok=True)
    meta_out.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    (run_dir / "train_history.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
    (run_dir / "eval_report.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return meta


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Stage 2B EnemyIconNet on GPU and export ONNX")
    parser.add_argument("--epochs", type=int, default=32)
    parser.add_argument("--samples-per-epoch", type=int, default=12000)
    args = parser.parse_args()
    meta = train_and_export(epochs=args.epochs, samples_per_epoch=args.samples_per_epoch)
    print(
        f"Exported {meta['model_name']}: size={meta['onnx_kb']}KB, diff={meta['onnx_parity_max_abs_diff']:.2e}, "
        f"cpu={meta['cpu_ms_desktop_4t']}ms, best_macro_f1={meta['best_val_macro_f1_050']}, "
        f"active_classes={meta['active_calibrated_classes']}/{meta['num_classes']}, "
        f"neg_fp={meta['neg_strip_fp_count']}/{meta['neg_strip_total']}"
    )


if __name__ == "__main__":
    main()
