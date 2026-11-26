"""
EnemyIconNet — Next-generation upper-battle enemy status icon classifier (Stage 2B).

Reads a cropped upper battle viewport strip `[B, 3, 96, 256]` (enemies & top-left shield icons)
and predicts a 24-class multi-label vector corresponding directly to official shield icons
so Tier-3 Icon arbitration works even without long-pressing enemy cards.
"""

from __future__ import annotations

import torch
import torch.nn as nn

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


class EnemyIconNet(nn.Module):
    def __init__(self, width: int = 64) -> None:
        super().__init__()
        self.features = nn.Sequential(
            nn.Conv2d(3, width // 2, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(width // 2),
            nn.ReLU(inplace=True),
            nn.Conv2d(width // 2, width, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(width),
            nn.ReLU(inplace=True),
            nn.Conv2d(width, width * 2, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(width * 2),
            nn.ReLU(inplace=True),
            nn.Conv2d(width * 2, width * 2, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(width * 2),
            nn.ReLU(inplace=True),
            nn.AdaptiveAvgPool2d(1),
        )
        self.head = nn.Linear(width * 2, NUM_ENEMY_ICON_CLASSES)

    def forward_logits(self, x: torch.Tensor) -> torch.Tensor:
        feat = self.features(x).flatten(1)
        return self.head(feat)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return torch.sigmoid(self.forward_logits(x))
