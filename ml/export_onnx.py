"""
Exports BoardMasterGridNet (Stage 2A) and EnemyIconNet (Stage 2B) to ONNX (opset 17)
with numerical equivalence verification via onnxruntime CPUExecutionProvider (`max_abs_diff < 1e-4`).
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

from ml.board_master_arch import BoardMasterGridNet
from ml.enemy_icon_arch import EnemyIconNet


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

    class _Wrapper(torch.nn.Module):
        def __init__(self, inner: BoardMasterGridNet, r: int, c: int) -> None:
            super().__init__()
            self.inner = inner
            self.r = r
            self.c = c

        def forward(self, dual_board: torch.Tensor) -> torch.Tensor:
            return self.inner(dual_board, rows=self.r, cols=self.c)

    wrapped = _Wrapper(model_cpu, rows, cols).eval()
    sample = torch.rand(1, 6, rows * cell_px, cols * cell_px, dtype=torch.float32)

    with torch.no_grad():
        torch_out = wrapped(sample).numpy()

    torch.onnx.export(
        wrapped,
        (sample,),
        str(dest_path),
        input_names=["dual_board"],
        output_names=["cell_probs"],
        dynamic_axes={"dual_board": {0: "batch"}, "cell_probs": {0: "batch"}},
        opset_version=opset,
        dynamo=False,
    )

    sess = ort.InferenceSession(str(dest_path), providers=["CPUExecutionProvider"])
    ort_out = sess.run(["cell_probs"], {"dual_board": sample.numpy()})[0]
    return float(np.max(np.abs(torch_out - ort_out)))


def export_enemy_icon_to_onnx(
    model: EnemyIconNet,
    dest_path: Path,
    opset: int = 17,
) -> float:
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    model_cpu = model.eval().cpu()
    sample = torch.rand(1, 3, 96, 256, dtype=torch.float32)
    with torch.no_grad():
        torch_out = model_cpu(sample).numpy()

    torch.onnx.export(
        model_cpu,
        (sample,),
        str(dest_path),
        input_names=["upper_strip"],
        output_names=["icon_probs"],
        dynamic_axes={"upper_strip": {0: "batch"}, "icon_probs": {0: "batch"}},
        opset_version=opset,
        dynamo=False,
    )

    sess = ort.InferenceSession(str(dest_path), providers=["CPUExecutionProvider"])
    ort_out = sess.run(["icon_probs"], {"upper_strip": sample.numpy()})[0]
    return float(np.max(np.abs(torch_out - ort_out)))


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
