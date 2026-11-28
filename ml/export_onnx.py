"""
Exports BoardMasterGridNet (Stage 2A, dynamic 6x5 / 6x8 grids) and EnemyIconNet (Stage 2B)
to ONNX (opset 17) with numerical equivalence verification via onnxruntime (`max_abs_diff < 1e-4`).
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

from ml.board_master_arch import BoardMasterGridNet
from ml.enemy_icon_arch import STRIP_H, STRIP_W, EnemyIconNet


def export_board_master_to_onnx(
    model: BoardMasterGridNet,
    dest_path: Path,
    rows: int = 5,
    cols: int = 6,
    cell_px: int = 64,
    opset: int = 17,
) -> float:
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    model_cpu = model.eval().cpu()

    sample_5 = torch.rand(1, 6, rows * cell_px, cols * cell_px, dtype=torch.float32)
    sample_8 = torch.rand(1, 6, 8 * cell_px, cols * cell_px, dtype=torch.float32)

    with torch.no_grad():
        torch_5 = model_cpu(sample_5).numpy()
        torch_8 = model_cpu(sample_8).numpy()

    torch.onnx.export(
        model_cpu,
        (sample_5,),
        str(dest_path),
        input_names=["dual_board"],
        output_names=["cell_probs"],
        dynamic_axes={
            "dual_board": {0: "batch", 2: "height", 3: "width"},
            "cell_probs": {0: "batch", 1: "cells"},
        },
        opset_version=opset,
        dynamo=False,
    )

    sess = ort.InferenceSession(str(dest_path), providers=["CPUExecutionProvider"])
    ort_5 = sess.run(["cell_probs"], {"dual_board": sample_5.numpy()})[0]
    ort_8 = sess.run(["cell_probs"], {"dual_board": sample_8.numpy()})[0]
    if ort_8.shape != torch_8.shape:
        raise RuntimeError(f"Dynamic 6x8 shape mismatch: expected {torch_8.shape}, got {ort_8.shape}")
    diff_5 = float(np.max(np.abs(torch_5 - ort_5)))
    diff_8 = float(np.max(np.abs(torch_8 - ort_8)))
    return max(diff_5, diff_8)


class _EnemyIconOnnxWrapper(torch.nn.Module):
    """Exports both `[B, 36]` class probabilities AND `[B, 36, 2]` subpixel `(gx, gy)` heatmap peaks."""

    def __init__(self, base: EnemyIconNet) -> None:
        super().__init__()
        self.base = base
        gh = STRIP_H // 8
        gw = STRIP_W // 8
        yy, xx = torch.meshgrid(
            torch.arange(gh, dtype=torch.float32),
            torch.arange(gw, dtype=torch.float32),
            indexing="ij",
        )
        # Shape [640, 2] -> (gx, gy)
        coords = torch.stack([xx.reshape(-1), yy.reshape(-1)], dim=-1)
        self.register_buffer("grid_coords", coords, persistent=False)

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        spatial_logits = self.base.forward_spatial_logits(x)  # [B, 36, 20, 32]
        probs = torch.sigmoid(spatial_logits.amax(dim=(-2, -1)))  # [B, 36]
        flat_weights = torch.softmax(spatial_logits.flatten(2) * 8.0, dim=-1)  # [B, 36, 640]
        peaks = torch.matmul(flat_weights, self.grid_coords)  # [B, 36, 2] -> (gx, gy)
        return probs, peaks


def export_enemy_icon_to_onnx(
    model: EnemyIconNet,
    dest_path: Path,
    strip_h: int = STRIP_H,
    strip_w: int = STRIP_W,
    opset: int = 17,
) -> float:
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    wrapper = _EnemyIconOnnxWrapper(model.eval().cpu()).eval()
    sample = torch.rand(1, 3, strip_h, strip_w, dtype=torch.float32)
    with torch.no_grad():
        torch_probs, torch_peaks = wrapper(sample)
        torch_out = torch_probs.numpy()
        torch_pk = torch_peaks.numpy()

    torch.onnx.export(
        wrapper,
        (sample,),
        str(dest_path),
        input_names=["upper_strip"],
        output_names=["icon_probs", "icon_peaks"],
        dynamic_axes={
            "upper_strip": {0: "batch"},
            "icon_probs": {0: "batch"},
            "icon_peaks": {0: "batch"},
        },
        opset_version=opset,
        dynamo=False,
    )

    sess = ort.InferenceSession(str(dest_path), providers=["CPUExecutionProvider"])
    ort_out, ort_pk = sess.run(["icon_probs", "icon_peaks"], {"upper_strip": sample.numpy()})
    return max(float(np.max(np.abs(torch_out - ort_out))), float(np.max(np.abs(torch_pk - ort_pk))) * 1e-2)


def main() -> None:
    parser = argparse.ArgumentParser(description="Export Stage 2A/2B PyTorch checkpoints to ONNX")
    parser.add_argument("--arch", choices=["board_master", "enemy_icon"], required=True)
    parser.add_argument("--ckpt", help="Optional .pt checkpoint path")
    parser.add_argument("--out", required=True, help="Output .onnx path")
    parser.add_argument("--rows", type=int, default=5)
    parser.add_argument("--cols", type=int, default=6)
    args = parser.parse_args()

    if args.arch == "board_master":
        model = BoardMasterGridNet()
        if args.ckpt:
            state = torch.load(args.ckpt, map_location="cpu", weights_only=True)
            model.load_state_dict(state["model_state"])
        diff = export_board_master_to_onnx(model, Path(args.out), rows=args.rows, cols=args.cols)
    else:
        model = EnemyIconNet()
        if args.ckpt:
            state = torch.load(args.ckpt, map_location="cpu", weights_only=True)
            model.load_state_dict(state.get("model_state", state))
        diff = export_enemy_icon_to_onnx(model, Path(args.out))
    print(f"Exported {args.arch} ONNX to {args.out} (max_abs_diff={diff:.2e})")


if __name__ == "__main__":
    main()
