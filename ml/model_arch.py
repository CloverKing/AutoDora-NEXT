"""
Lightweight multi-head CNN architecture (`AutoDoraOrbNetV1`) for 64x64 ToS runestone cells.

Designed for 1:1 structural parity with the BN-folded Keras -> LiteRT (.tflite) exporter:
  - Uses ONLY Conv2d (standard & depthwise), BatchNorm2d, ReLU6, AvgPool2d, and Linear layers.
  - Preserves spatial geometry (top-right badges, 4 corners, center core, outer rim) via a
    2x2 spatial branch concatenated with global average pooling (`256`-dim fused embedding).
"""

from __future__ import annotations

import torch
import torch.nn as nn

from ml.schema import (
    NUM_ELEMENTS,
    NUM_HAZARDS,
    NUM_IS_RUNE,
    NUM_RACES,
    NUM_TILE_OVERLAYS,
    NUM_VARIANTS,
)


class DepthwiseSeparableBlock(nn.Module):
    def __init__(self, in_ch: int, out_ch: int, stride: int = 1) -> None:
        super().__init__()
        self.dw_conv = nn.Conv2d(
            in_ch, in_ch, kernel_size=3, stride=stride, padding=1, groups=in_ch, bias=False
        )
        self.dw_bn = nn.BatchNorm2d(in_ch, eps=1e-3)
        self.dw_act = nn.ReLU6(inplace=True)

        self.pw_conv = nn.Conv2d(in_ch, out_ch, kernel_size=1, stride=1, padding=0, bias=False)
        self.pw_bn = nn.BatchNorm2d(out_ch, eps=1e-3)
        self.pw_act = nn.ReLU6(inplace=True)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.dw_act(self.dw_bn(self.dw_conv(x)))
        x = self.pw_act(self.pw_bn(self.pw_conv(x)))
        return x


class AutoDoraOrbNetV1(nn.Module):
    def __init__(self, dropout: float = 0.15) -> None:
        super().__init__()
        # 64x64 -> 32x32
        self.stem_conv = nn.Conv2d(3, 32, kernel_size=3, stride=2, padding=1, bias=False)
        self.stem_bn = nn.BatchNorm2d(32, eps=1e-3)
        self.stem_act = nn.ReLU6(inplace=True)

        # 32x32 -> 16x16
        self.block1 = DepthwiseSeparableBlock(32, 48, stride=1)
        self.block2 = DepthwiseSeparableBlock(48, 64, stride=2)
        # 16x16 -> 8x8
        self.block3 = DepthwiseSeparableBlock(64, 64, stride=1)
        self.block4 = DepthwiseSeparableBlock(64, 112, stride=2)
        # 8x8 -> 4x4
        self.block5 = DepthwiseSeparableBlock(112, 112, stride=1)
        self.block6 = DepthwiseSeparableBlock(112, 192, stride=2)

        # Spatial-preserving 2x2 branch (4x4 -> Conv1x1(48) -> AvgPool2d(2) -> 2x2x48 = 192)
        self.spatial_conv = nn.Conv2d(192, 48, kernel_size=1, stride=1, padding=0, bias=False)
        self.spatial_bn = nn.BatchNorm2d(48, eps=1e-3)
        self.spatial_act = nn.ReLU6(inplace=True)
        self.spatial_pool = nn.AvgPool2d(kernel_size=2, stride=2)

        self.drop = nn.Dropout(p=dropout)

        feat_dim = 192 + 192  # 384
        self.head_is_rune = nn.Linear(feat_dim, NUM_IS_RUNE)
        self.head_element = nn.Linear(feat_dim, NUM_ELEMENTS)
        self.head_variant = nn.Linear(feat_dim, NUM_VARIANTS)
        self.head_race = nn.Linear(feat_dim, NUM_RACES)
        self.head_hazards = nn.Linear(feat_dim, NUM_HAZARDS)
        self.head_tile_overlay = nn.Linear(feat_dim, NUM_TILE_OVERLAYS)

    def extract_features(self, x: torch.Tensor) -> torch.Tensor:
        x = self.stem_act(self.stem_bn(self.stem_conv(x)))
        x = self.block2(self.block1(x))
        x = self.block4(self.block3(x))
        x = self.block6(self.block5(x))  # [N, 192, 4, 4]

        gap = x.mean(dim=(2, 3))  # [N, 192]
        sp = self.spatial_pool(self.spatial_act(self.spatial_bn(self.spatial_conv(x))))  # [N, 48, 2, 2]
        # Permute to NHWC before flattening so 1:1 matches Keras/TFLite Flatten()
        sp_flat = sp.permute(0, 2, 3, 1).reshape(x.shape[0], 192)
        return torch.cat([gap, sp_flat], dim=1)

    def forward_logits(self, x: torch.Tensor) -> dict[str, torch.Tensor]:
        feat = self.drop(self.extract_features(x))
        return {
            "is_rune": self.head_is_rune(feat),
            "element": self.head_element(feat),
            "variant": self.head_variant(feat),
            "race": self.head_race(feat),
            "hazards": self.head_hazards(feat),
            "tile_overlay": self.head_tile_overlay(feat),
        }

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """Return single concatenated [N, 47] probability tensor matching schema.py & LiteRT."""
        logits = self.forward_logits(x)
        return torch.cat(
            [
                torch.sigmoid(logits["is_rune"]),
                torch.softmax(logits["element"], dim=-1),
                torch.softmax(logits["variant"], dim=-1),
                torch.softmax(logits["race"], dim=-1),
                torch.sigmoid(logits["hazards"]),
                torch.sigmoid(logits["tile_overlay"]),
            ],
            dim=-1,
        )
