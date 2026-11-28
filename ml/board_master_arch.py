"""
BoardMasterGridNet — Next-generation full-board dual-frame multi-head architecture (Stage 2A).

Consumes two aligned static frames (`frame_a`, `frame_b`) concatenated along channels:
  Input:  [B, 6, rows * 64, cols * 64] (RGB pair, [0, 1] float32)
  Output: [B, rows * cols, 47] aligned 1:1 with ml/schema.py & HybridFusionPolicy.kt!

Uses pure-tensor DynamicGridAttnBlock (no baked sequence length in ONNX Reshape) and depthwise
spatial cell pooling (stride=8 matching stem's /8 downsampling -> 8x8 sub-grid per 64px cell),
so a SINGLE exported `.onnx` model dynamically supports 6x5 (30 cells) AND 6x8 Weijian (48 cells).
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


class DynamicGridAttnBlock(nn.Module):
    """Transformer encoder block with pure runtime shape arithmetic for dynamic ONNX grids."""

    def __init__(self, embed_dim: int, n_heads: int = 4, dropout: float = 0.1) -> None:
        super().__init__()
        self.n_heads = n_heads
        self.head_dim = embed_dim // n_heads
        self.scale = self.head_dim ** -0.5
        self.norm1 = nn.LayerNorm(embed_dim)
        self.qkv = nn.Linear(embed_dim, embed_dim * 3, bias=False)
        self.proj = nn.Linear(embed_dim, embed_dim)
        self.drop = nn.Dropout(dropout)
        self.norm2 = nn.LayerNorm(embed_dim)
        self.ffn = nn.Sequential(
            nn.Linear(embed_dim, embed_dim * 2),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(embed_dim * 2, embed_dim),
            nn.Dropout(dropout),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        b, n, d = x.shape
        h = self.norm1(x)
        qkv = self.qkv(h).reshape(b, n, 3, self.n_heads, self.head_dim)
        q = qkv[:, :, 0].transpose(1, 2)
        k = qkv[:, :, 1].transpose(1, 2)
        v = qkv[:, :, 2].transpose(1, 2)
        attn = torch.softmax(torch.matmul(q, k.transpose(-2, -1)) * self.scale, dim=-1)
        out = torch.matmul(attn, v).transpose(1, 2).reshape(b, n, d)
        x = x + self.drop(self.proj(out))
        x = x + self.ffn(self.norm2(x))
        return x


class BoardMasterGridNet(nn.Module):
    def __init__(self, embed_dim: int = 128, n_heads: int = 4, n_layers: int = 2) -> None:
        super().__init__()
        self.embed_dim = embed_dim
        self.stem = nn.Sequential(
            nn.Conv2d(6, 40, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(40),
            nn.GELU(),
            nn.Conv2d(40, 80, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(80),
            nn.GELU(),
            nn.Conv2d(80, embed_dim, kernel_size=3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(embed_dim),
            nn.GELU(),
        )
        self.local_blocks = nn.Sequential(
            DwConvBlock(embed_dim),
            DwConvBlock(embed_dim),
        )
        # Preserve local quadrant geometry (top-right race/mark badge vs center star vs corners)
        self.cell_spatial = nn.Conv2d(
            embed_dim, embed_dim, kernel_size=8, stride=8, groups=embed_dim, bias=False
        )
        self.cell_bn = nn.BatchNorm2d(embed_dim)
        self.grid_ctx = nn.Sequential(
            *[DynamicGridAttnBlock(embed_dim, n_heads=n_heads, dropout=0.1) for _ in range(n_layers)]
        )

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
        feat = self.local_blocks(self.stem(dual_board))  # [B, D, H/8, W/8]
        # Standard cell_px=64 -> H/8 = rows * 8, W/8 = cols * 8; stride-8 conv handles arbitrary dynamic (rows, cols)!
        grid_feat = F.gelu(self.cell_bn(self.cell_spatial(feat)) + F.avg_pool2d(feat, kernel_size=8, stride=8))
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
