"""
BoardMasterGridNet — Next-generation full-board dual-frame multi-head architecture (Stage 2A).

Consumes two aligned static frames (`frame_a`, `frame_b`) concatenated along channels:
  Input:  [B, 6, rows * cell_px, cols * cell_px] (RGB pair, [0, 1] float32)
  Output: [B, rows * cols, 47] aligned 1:1 with ml/schema.py & HybridFusionPolicy.kt!

Supports arbitrary dynamic (cols, rows) (6x5 standard AND 6x8 Weijian expanded) by pooling
cell feature maps cleanly into an adaptive `(rows, cols)` grid and applying global 2-layer
Transformer self-attention across all cells (catching board-wide dark/dim themes & multi-cell puzzle borders).
"""

from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F

from ml.schema import (
    NUM_ELEMENTS,
    NUM_HAZARDS,
    NUM_IS_RUNE,
    NUM_RACES,
    NUM_TILE_OVERLAYS,
    NUM_VARIANTS,
    OUTPUT_DIM,
)


class DwConvBlock(nn.Module):
    def __init__(self, channels: int) -> None:
        super().__init__()
        self.dw = nn.Conv2d(channels, channels, kernel_size=3, padding=1, groups=channels, bias=False)
        self.bn1 = nn.BatchNorm2d(channels)
        self.pw1 = nn.Conv2d(channels, channels * 2, kernel_size=1, bias=False)
        self.act = nn.GELU()
        self.pw2 = nn.Conv2d(channels * 2, channels, kernel_size=1, bias=False)
        self.bn2 = nn.BatchNorm2d(channels)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        residual = x
        x = self.bn1(self.dw(x))
        x = self.pw2(self.act(self.pw1(x)))
        x = self.bn2(x)
        return F.gelu(x + residual)


class BoardMasterGridNet(nn.Module):
    def __init__(self, embed_dim: int = 128, n_heads: int = 4, n_layers: int = 2) -> None:
        super().__init__()
        self.embed_dim = embed_dim
        self.stem = nn.Sequential(
            nn.Conv2d(6, 48, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(48),
            nn.GELU(),
            nn.Conv2d(48, 96, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(96),
            nn.GELU(),
            nn.Conv2d(96, embed_dim, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(embed_dim),
            nn.GELU(),
        )
        self.local_blocks = nn.Sequential(
            DwConvBlock(embed_dim),
            DwConvBlock(embed_dim),
        )
        enc_layer = nn.TransformerEncoderLayer(
            d_model=embed_dim,
            nhead=n_heads,
            dim_feedforward=embed_dim * 2,
            dropout=0.1,
            activation="gelu",
            batch_first=True,
            norm_first=True,
        )
        self.grid_ctx = nn.TransformerEncoder(enc_layer, num_layers=n_layers)

        self.head_is_rune = nn.Linear(embed_dim, NUM_IS_RUNE)
        self.head_element = nn.Linear(embed_dim, NUM_ELEMENTS)
        self.head_variant = nn.Linear(embed_dim, NUM_VARIANTS)
        self.head_race = nn.Linear(embed_dim, NUM_RACES)
        self.head_hazards = nn.Linear(embed_dim, NUM_HAZARDS)
        self.head_tile_overlay = nn.Linear(embed_dim, NUM_TILE_OVERLAYS)

    def forward_logits(
        self,
        dual_board: torch.Tensor,
        rows: int = 5,
        cols: int = 6,
    ) -> dict[str, torch.Tensor]:
        feat = self.local_blocks(self.stem(dual_board))  # [B, D, H', W']
        # Pool cleanly to fixed cell tokens [B, D, rows, cols]
        grid_feat = F.adaptive_avg_pool2d(feat, output_size=(rows, cols))
        b, d, r, c = grid_feat.shape
        tokens = grid_feat.flatten(2).transpose(1, 2)  # [B, R*C, D]
        ctx = self.grid_ctx(tokens)
        return {
            "is_rune": self.head_is_rune(ctx),
            "element": self.head_element(ctx),
            "variant": self.head_variant(ctx),
            "race": self.head_race(ctx),
            "hazards": self.head_hazards(ctx),
            "tile_overlay": self.head_tile_overlay(ctx),
        }

    def forward(
        self,
        dual_board: torch.Tensor,
        rows: int = 5,
        cols: int = 6,
    ) -> torch.Tensor:
        logits = self.forward_logits(dual_board, rows=rows, cols=cols)
        probs = torch.cat(
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
        return probs


assert OUTPUT_DIM == (
    NUM_IS_RUNE + NUM_ELEMENTS + NUM_VARIANTS + NUM_RACES + NUM_HAZARDS + NUM_TILE_OVERLAYS
)
