"""
EnemyIconNet — Next-generation upper-battle enemy status icon classifier (Stage 2B).

Reads a cropped upper battle viewport strip `[B, 3, 96, 256]` (enemies & top-left shield icons)
and predicts a 24-class multi-label vector corresponding directly to official shield icons
so Tier-3 Icon arbitration works even without long-pressing enemy cards.

Upgraded with depthwise-separable residual conv blocks + dual spatial Max/Avg pooling
so small 18..32px enemy icons produce crisp local peak activations anywhere on the strip.
"""

from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F

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
    def __init__(self, width: int = 64) -> None:
        super().__init__()
        self.features = nn.Sequential(
            nn.Conv2d(3, width // 2, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(width // 2),
            nn.GELU(),
            nn.Conv2d(width // 2, width, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(width),
            nn.GELU(),
            _IconDwBlock(width),
            nn.Conv2d(width, width * 2, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(width * 2),
            nn.GELU(),
            _IconDwBlock(width * 2),
            _IconDwBlock(width * 2),
        )
        self.drop = nn.Dropout(0.15)
        self.head = nn.Linear(width * 4, NUM_ENEMY_ICON_CLASSES)

    def forward_logits(self, x: torch.Tensor) -> torch.Tensor:
        f = self.features(x)
        pooled = torch.cat(
            [
                F.adaptive_max_pool2d(f, 1).flatten(1),
                F.adaptive_avg_pool2d(f, 1).flatten(1),
            ],
            dim=1,
        )
        return self.head(self.drop(pooled))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return torch.sigmoid(self.forward_logits(x))
