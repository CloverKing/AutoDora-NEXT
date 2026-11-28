"""
EnemyIconNet — Anchor-free CenterNet spatial detector for upper-battle enemy status icons (Stage 2B).

Reads an isotropic upper battle viewport strip `[B, 3, 160, 256]` (`x in [0.04..0.96], y in [0.08..0.42]`)
and predicts a 36-class multi-label vector covering ALL solver-relevant shield & hazard-hint icons
without requiring the user to long-press enemy cards.

Key architectural design:
 - 3 stride-2 downsampling stages -> `/8` spatial feature grid (`[B, C, 20, 32]` on `160x256`),
   where each spatial cell has an `8px` stride and `~28px` local receptive field matching a single
   `10..16px` ToS enemy status icon.
 - Supervised directly on `[B, 36, 20, 32]` CenterNet spatial Gaussian heatmaps during training
   (`forward_spatial_logits`), eliminating weakly-supervised MIL gradient starvation!
 - At inference / ONNX export, `forward(x)` simply returns `sigmoid(spatial_logits.amax(dim=(-2, -1)))`
   (`[B, 36]`) — 100% drop-in compatible ONNX contract with zero boss-artwork interference!
"""

from __future__ import annotations

import math
import torch
import torch.nn as nn
import torch.nn.functional as F

STRIP_H = 160
STRIP_W = 256
GRID_H = STRIP_H // 8  # 20
GRID_W = STRIP_W // 8  # 32

ENEMY_ICON_CLASSES = [
    "ICON_PUZZLE",
    "ICON_EXACT_COMBO",
    "ICON_MIN_COMBO",
    "ICON_NO_CASCADE",
    "ICON_ANTI_FIRST_WAVE",
    "ICON_FORBID_ALL",
    "ICON_FIVE_ELEMENTS",
    "ICON_THREE_ELEMENTS",
    "ICON_ENCHANTED_BREAKTHROUGH",
    "ICON_CROSS_SHIELD",
    "ICON_L_SHAPE",
    "ICON_T_SHAPE",
    "ICON_SQUARE_2X2",
    "ICON_DIGITAL_COUNT",
    "ICON_FULL_CLEAR",
    "ICON_RACE_SHIELD",
    "ICON_UNMATCHABLE",
    "ICON_STEP_LIMIT",
    "ICON_TRAFFIC_LIGHT",
    "ICON_CHAR_ORB_TERRAIN",
    "ICON_STARTING_BUOY",
    "ICON_BURNING_TRAIL",
    "ICON_CORROSION_TRAIL",
    "ICON_MONOCHROME_DISGUISE",
    "ICON_MAX_COMBO",
    "ICON_EQUAL_COMBO",
    "ICON_COLUMN_CLEAR",
    "ICON_LUMINOUS",
    "ICON_WEAKENED_FORBID",
    "ICON_ORB_GROUP_SIZE",
    "ICON_WAVE1_KIND",
    "ICON_ELEM_BURST",
    "ICON_ELECTRIFIED_HINT",
    "ICON_FROZEN_PETRIFIED_HINT",
    "ICON_WEATHERED_BOMB_HINT",
    "ICON_TERRAIN_HAZARD_HINT",
]

NUM_ENEMY_ICON_CLASSES = len(ENEMY_ICON_CLASSES)


class _IconDwBlock(nn.Module):
    def __init__(self, channels: int) -> None:
        super().__init__()
        self.dw = nn.Conv2d(channels, channels, kernel_size=3, padding=1, groups=channels, bias=False)
        self.bn1 = nn.BatchNorm2d(channels)
        self.pw1 = nn.Conv2d(channels, channels * 2, kernel_size=1, bias=False)
        self.pw2 = nn.Conv2d(channels * 2, channels, kernel_size=1, bias=False)
        self.bn2 = nn.BatchNorm2d(channels)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return F.gelu(x + self.bn2(self.pw2(F.gelu(self.pw1(self.bn1(self.dw(x)))))))


class EnemyIconNet(nn.Module):
    def __init__(self, width: int = 72) -> None:
        super().__init__()
        c1 = width // 2
        c2 = width
        c3 = width * 2
        self.features = nn.Sequential(
            nn.Conv2d(3, c1, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(c1),
            nn.GELU(),
            _IconDwBlock(c1),
            nn.Conv2d(c1, c2, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(c2),
            nn.GELU(),
            _IconDwBlock(c2),
            _IconDwBlock(c2),
            nn.Conv2d(c2, c3, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(c3),
            nn.GELU(),
            _IconDwBlock(c3),
            _IconDwBlock(c3),
            _IconDwBlock(c3),
        )
        out_conv = nn.Conv2d(c3, NUM_ENEMY_ICON_CLASSES, kernel_size=1)
        nn.init.constant_(out_conv.bias, -math.log((1.0 - 0.02) / 0.02))  # CenterNet focal prior bias = -3.89
        self.spatial_head = nn.Sequential(
            nn.Conv2d(c3, c3, kernel_size=3, padding=1, groups=c3, bias=False),
            nn.BatchNorm2d(c3),
            nn.GELU(),
            nn.Conv2d(c3, c3, kernel_size=1, bias=False),
            nn.BatchNorm2d(c3),
            nn.GELU(),
            out_conv,
        )

    def forward_spatial_logits(self, x: torch.Tensor) -> torch.Tensor:
        return self.spatial_head(self.features(x))  # [B, 36, H/8, W/8]

    def forward_logits(self, x: torch.Tensor) -> torch.Tensor:
        return self.forward_spatial_logits(x).amax(dim=(-2, -1))  # [B, 36]

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return torch.sigmoid(self.forward_logits(x))
