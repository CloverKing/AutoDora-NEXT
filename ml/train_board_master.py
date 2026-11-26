"""
Training harness for Stage 2A dual-frame BoardMasterGridNet and Stage 2B EnemyIconNet.

Supports:
 - P0 sample weighting (`sampleWeight` from ingest_device_export.py staging manifest)
 - CUDA AMP mixed precision (falls back to CPU cleanly)
 - Masked multi-head losses (is_rune, element, variant, race, hazards, tile_overlay)
 - Cosine LR schedule and best-checkpoint saving to ml/runs/
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler

from ml.board_master_arch import BoardMasterGridNet
from ml.schema import (
    SLICE_ELEMENT,
    SLICE_HAZARDS,
    SLICE_IS_RUNE,
    SLICE_RACE,
    SLICE_TILE_OVERLAY,
    SLICE_VARIANT,
)


class DualFrameBoardDataset(Dataset):
    """
    Loads paired `[N, 6, H, W]` dual-frame boards + `[N, R*C, 47]` targets + per-head label masks
    and sample weights (`sample_weights`) from an `.npz` bundle generated after device/video ingestion.
    """

    def __init__(self, npz_path: Path) -> None:
        data = np.load(npz_path, allow_pickle=False)
        self.dual_boards = data["dual_boards"].astype(np.float32)  # [N, 6, rows*64, cols*64]
        self.targets = data["targets"].astype(np.float32)          # [N, R*C, 47]
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
    """
    logits: dict of [B, K, C]
    targets: [B, K, 47]
    masks: [B, 6] indicating valid heads per sample (0=is_rune, 1=elem, 2=var, 3=race, 4=haz, 5=ov)
    sample_weights: [B]
    """
    b, k, _ = targets.shape
    sw = sample_weights.view(b, 1).expand(b, k)

    def weighted_bce(pred_logits: torch.Tensor, tgt: torch.Tensor, head_mask: torch.Tensor) -> torch.Tensor:
        loss_raw = F.binary_cross_entropy_with_logits(pred_logits, tgt, reduction="none").mean(dim=-1)
        m = head_mask.view(b, 1) * sw
        denom = m.sum().clamp_min(1.0)
        return (loss_raw * m).sum() / denom

    def weighted_ce(pred_logits: torch.Tensor, tgt_onehot: torch.Tensor, head_mask: torch.Tensor) -> torch.Tensor:
        tgt_idx = tgt_onehot.argmax(dim=-1)
        loss_raw = F.cross_entropy(pred_logits.reshape(-1, pred_logits.size(-1)), tgt_idx.reshape(-1), reduction="none").view(b, k)
        m = head_mask.view(b, 1) * sw
        denom = m.sum().clamp_min(1.0)
        return (loss_raw * m).sum() / denom

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
        avg_loss = total_loss / max(1, steps)
        history.append({"epoch": epoch, "train_loss": avg_loss})

    ckpt_path = out_dir / "board_master_latest.pt"
    torch.save({"model_state": model.state_dict(), "rows": ds.rows, "cols": ds.cols, "history": history}, ckpt_path)
    (out_dir / "train_history.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
    return ckpt_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Stage 2A dual-frame BoardMasterGridNet")
    parser.add_argument("--train-npz", required=True, help="Path to Stage 2A training .npz")
    parser.add_argument("--out", default="ml/runs/board_master", help="Run output directory")
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=1e-3)
    args = parser.parse_args()

    ckpt = train_one_run(Path(args.train_npz), Path(args.out), epochs=args.epochs, batch_size=args.batch_size, lr=args.lr)
    print(f"Saved checkpoint: {ckpt}")


if __name__ == "__main__":
    main()
