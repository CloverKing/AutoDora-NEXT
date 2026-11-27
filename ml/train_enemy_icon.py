"""
Train Stage 2B `EnemyIconNet` on RTX 4050 GPU with class-balanced on-the-fly upper-battle strip
compositing, evaluate on fixed held-out `val_strips.npz`, calibrate near-zero-FP thresholds per class,
and export verified `enemy_icon_v1.onnx` + `enemy_icon_meta.json` into Android assets.
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

from ml.enemy_icon_arch import ENEMY_ICON_CLASSES, NUM_ENEMY_ICON_CLASSES, EnemyIconNet
from ml.export_onnx import export_enemy_icon_to_onnx
from ml.tools.build_enemy_icon_dataset import composite_strip_sample


class DynamicEnemyIconTrainDataset(Dataset):
    """Class-balanced on-the-fly multi-label upper-battle icon strip generator."""

    def __init__(self, assets_npz: Path, samples_per_epoch: int = 12000, seed: int = 42) -> None:
        z = np.load(assets_npz, allow_pickle=False)
        self.bg_strips = z["bg_strips"]  # [M, 96, 256, 3]
        self.pos_cls = z["pos_cls"]
        self.pos_rgba = z["pos_rgba"]
        self.neg_rgba = z["neg_rgba"]
        self.samples_per_epoch = samples_per_epoch
        self.base_seed = seed
        self.epoch = 0

        self.by_cls: dict[int, list[tuple[int, np.ndarray]]] = defaultdict(list)
        self.all_pos: list[tuple[int, np.ndarray]] = []
        for cidx, rgba in zip(self.pos_cls.tolist(), self.pos_rgba):
            item = (int(cidx), rgba)
            self.by_cls[int(cidx)].append(item)
            self.all_pos.append(item)
        self.neg_list = [self.neg_rgba[i] for i in range(len(self.neg_rgba))]
        self.active_classes = sorted(self.by_cls.keys())

    def set_epoch(self, epoch: int) -> None:
        self.epoch = epoch

    def __len__(self) -> int:
        return self.samples_per_epoch

    def __getitem__(self, idx: int):
        rng = random.Random((self.base_seed + self.epoch * 131071 + idx) & 0x7FFFFFFF)
        bg = self.bg_strips[rng.randrange(len(self.bg_strips))]
        force_neg = (idx % 4 == 0)
        if force_neg:
            im_u8, tgt = composite_strip_sample(rng, bg, self.all_pos, self.neg_list, force_negative=True)
        else:
            anchor_cls = self.active_classes[idx % len(self.active_classes)]
            picks = [rng.choice(self.by_cls[anchor_cls])]
            if rng.random() < 0.65:
                for p in rng.sample(self.all_pos, k=min(len(self.all_pos), rng.randint(1, 3))):
                    if p[0] != anchor_cls or rng.random() < 0.25:
                        picks.append(p)
            im_u8, tgt = composite_strip_sample(rng, bg, picks, self.neg_list, force_negative=False)

        x = torch.from_numpy(np.transpose(im_u8, (2, 0, 1)).astype(np.float32) / 255.0)
        y = torch.from_numpy(tgt)
        return x, y


def calibrate_near_zero_fp_thresholds(
    probs: np.ndarray,
    targets: np.ndarray,
    class_stats: dict[str, Any],
) -> tuple[dict[str, float], dict[str, dict[str, Any]]]:
    """
    Scans [0.50..0.98] per class, preferring `FP == 0` with highest recall, else
    `TP >= 5 && FP <= 1 && precision >= 0.94`. Classes with < 2 distinct source icons
    or no clean threshold fall back to safe `1.01` (mathematically disabled).
    """
    thresholds: dict[str, float] = {}
    metrics: dict[str, dict[str, Any]] = {}
    for cidx, cname in enumerate(ENEMY_ICON_CLASSES):
        yt = targets[:, cidx] > 0.5
        yp = probs[:, cidx]
        pos_total = int(yt.sum())
        src_icons = int(class_stats.get(cname, {}).get("total_source_icons", 0))

        best_th = 1.01
        best_tp = 0
        best_fp = 0
        found = False
        if pos_total > 0 and src_icons >= 2:
            for th in np.linspace(0.50, 0.98, 49):
                pred = yp >= th
                tp = int(np.sum(pred & yt))
                fp = int(np.sum(pred & ~yt))
                prec = tp / max(1, tp + fp)
                if fp == 0 and tp >= 3:
                    best_th, best_tp, best_fp, found = float(round(th, 2)), tp, fp, True
                    break
                if tp >= 5 and fp <= 1 and prec >= 0.94 and not found:
                    best_th, best_tp, best_fp, found = float(round(th, 2)), tp, fp, True

        if not found:
            pred = yp >= best_th
            best_tp = int(np.sum(pred & yt))
            best_fp = int(np.sum(pred & ~yt))

        # Best F1 at nominal 0.50 for diagnostic tracking
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
    epochs: int = 28,
    batch_size: int = 64,
    lr: float = 1.5e-3,
    samples_per_epoch: int = 12000,
) -> dict[str, Any]:
    run_dir.mkdir(parents=True, exist_ok=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    use_amp = device.type == "cuda"

    split_manifest = json.loads((data_dir / "split_manifest.json").read_text(encoding="utf-8"))
    val_z = np.load(data_dir / "val_strips.npz", allow_pickle=False)
    val_x = np.transpose(val_z["strips"], (0, 3, 1, 2)).astype(np.float32) / 255.0
    val_y = val_z["targets"].astype(np.float32)

    train_ds = DynamicEnemyIconTrainDataset(data_dir / "train_assets.npz", samples_per_epoch=samples_per_epoch)
    model = EnemyIconNet(width=64).to(device)
    param_count = sum(p.numel() for p in model.parameters())

    pos_weight = torch.full((NUM_ENEMY_ICON_CLASSES,), 6.0, dtype=torch.float32, device=device)
    criterion = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(1, epochs))
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)

    best_f1 = -1.0
    best_ckpt = run_dir / "enemy_icon_best.pt"
    history: list[dict[str, float]] = []

    val_tensor_x = torch.from_numpy(val_x)
    for ep in range(1, epochs + 1):
        train_ds.set_epoch(ep)
        loader = DataLoader(train_ds, batch_size=batch_size, shuffle=False, num_workers=0, drop_last=False)
        model.train()
        tr_loss = 0.0
        steps = 0
        for bx, by in loader:
            bx, by = bx.to(device, non_blocking=True), by.to(device, non_blocking=True)
            optimizer.zero_grad(set_to_none=True)
            with torch.amp.autocast("cuda", enabled=use_amp):
                logits = model.forward_logits(bx)
                loss = criterion(logits, by)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            tr_loss += float(loss.detach().cpu())
            steps += 1
        scheduler.step()

        # Validation evaluation
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

    # Load best checkpoint, run full calibration & export verified ONNX
    state = torch.load(best_ckpt, map_location="cpu", weights_only=True)
    model.cpu().load_state_dict(state["model_state"])
    model.eval()
    preds_list = []
    with torch.no_grad():
        for s in range(0, len(val_tensor_x), 128):
            preds_list.append(model(val_tensor_x[s:s + 128]).numpy())
    val_probs = np.concatenate(preds_list, axis=0)

    thresholds, class_metrics = calibrate_near_zero_fp_thresholds(val_probs, val_y, split_manifest["class_stats"])
    max_diff = export_enemy_icon_to_onnx(model, onnx_out)

    # Benchmark desktop 4-thread CPU latency
    sess_opts = ort.SessionOptions()
    sess_opts.intra_op_num_threads = 4
    sess = ort.InferenceSession(str(onnx_out), sess_opts, providers=["CPUExecutionProvider"])
    bench_in = np.random.default_rng(0).random((1, 3, 96, 256), dtype=np.float32)
    for _ in range(5):
        sess.run(["icon_probs"], {"upper_strip": bench_in})
    t0 = time.perf_counter()
    for _ in range(40):
        sess.run(["icon_probs"], {"upper_strip": bench_in})
    cpu_ms = (time.perf_counter() - t0) * 1000.0 / 40.0

    # Negative-strip false positive check (how many pure negative val strips trigger ANY active threshold?)
    neg_mask = val_y.sum(axis=1) == 0
    neg_fp_strips = 0
    for r in np.flatnonzero(neg_mask):
        if any(val_probs[r, i] >= thresholds[ENEMY_ICON_CLASSES[i]] for i in range(NUM_ENEMY_ICON_CLASSES)):
            neg_fp_strips += 1

    active_classes = [c for c, m in class_metrics.items() if not m["fail_safe"]]
    meta = {
        "model_name": "EnemyIconNetV1",
        "schema_version": 1,
        "input_name": "upper_strip",
        "output_name": "icon_probs",
        "input_shape": [1, 3, 96, 256],
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


def recalibrate_existing_checkpoint(
    data_dir: Path = Path("ml/datasets/enemy_icon_v1"),
    run_dir: Path = Path("ml/runs/enemy_icon_v1"),
    onnx_out: Path = Path("android/app/src/main/assets/models/enemy_icon_v1.onnx"),
    meta_out: Path = Path("android/app/src/main/assets/models/enemy_icon_meta.json"),
) -> dict[str, Any]:
    split_manifest = json.loads((data_dir / "split_manifest.json").read_text(encoding="utf-8"))
    val_z = np.load(data_dir / "val_strips.npz", allow_pickle=False)
    val_x = np.transpose(val_z["strips"], (0, 3, 1, 2)).astype(np.float32) / 255.0
    val_y = val_z["targets"].astype(np.float32)

    best_ckpt = run_dir / "enemy_icon_best.pt"
    state = torch.load(best_ckpt, map_location="cpu", weights_only=True)
    model = EnemyIconNet(width=64)
    model.load_state_dict(state["model_state"])
    model.eval()
    preds_list = []
    with torch.no_grad():
        for s in range(0, len(val_x), 128):
            preds_list.append(model(torch.from_numpy(val_x[s:s + 128])).numpy())
    val_probs = np.concatenate(preds_list, axis=0)

    thresholds, class_metrics = calibrate_near_zero_fp_thresholds(val_probs, val_y, split_manifest["class_stats"])
    neg_mask = val_y.sum(axis=1) == 0
    neg_fp_strips = 0
    for r in np.flatnonzero(neg_mask):
        if any(val_probs[r, i] >= thresholds[ENEMY_ICON_CLASSES[i]] for i in range(NUM_ENEMY_ICON_CLASSES)):
            neg_fp_strips += 1

    active_classes = [c for c, m in class_metrics.items() if not m["fail_safe"]]
    existing_meta = json.loads(meta_out.read_text(encoding="utf-8")) if meta_out.is_file() else {}
    existing_meta.update({
        "onnx_bytes": onnx_out.stat().st_size if onnx_out.is_file() else existing_meta.get("onnx_bytes", 0),
        "onnx_kb": round(onnx_out.stat().st_size / 1024.0, 1) if onnx_out.is_file() else existing_meta.get("onnx_kb", 0.0),
        "active_calibrated_classes": len(active_classes),
        "neg_strip_fp_count": int(neg_fp_strips),
        "neg_strip_total": int(neg_mask.sum()),
        "class_thresholds": thresholds,
        "class_metrics": class_metrics,
    })
    meta_out.write_text(json.dumps(existing_meta, ensure_ascii=False, indent=2), encoding="utf-8")
    (run_dir / "eval_report.json").write_text(json.dumps(existing_meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return existing_meta


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Stage 2B EnemyIconNet on GPU and export ONNX")
    parser.add_argument("--epochs", type=int, default=28)
    parser.add_argument("--samples-per-epoch", type=int, default=12000)
    parser.add_argument("--recalibrate-only", action="store_true", help="Recalibrate fail_safe thresholds from enemy_icon_best.pt")
    args = parser.parse_args()
    if args.recalibrate_only:
        meta = recalibrate_existing_checkpoint()
        print(
            f"Recalibrated {meta.get('model_name', 'EnemyIconNetV1')}: "
            f"active_classes={meta['active_calibrated_classes']}/{meta.get('num_classes', NUM_ENEMY_ICON_CLASSES)}, "
            f"neg_fp={meta['neg_strip_fp_count']}/{meta['neg_strip_total']}"
        )
        return
    meta = train_and_export(epochs=args.epochs, samples_per_epoch=args.samples_per_epoch)
    print(
        f"Exported {meta['model_name']}: size={meta['onnx_kb']}KB, diff={meta['onnx_parity_max_abs_diff']:.2e}, "
        f"cpu={meta['cpu_ms_desktop_4t']}ms, best_macro_f1={meta['best_val_macro_f1_050']}, "
        f"active_classes={meta['active_calibrated_classes']}/{meta['num_classes']}, "
        f"neg_fp={meta['neg_strip_fp_count']}/{meta['neg_strip_total']}"
    )


if __name__ == "__main__":
    main()
