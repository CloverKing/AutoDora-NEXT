"""
Shared Phase 4 multi-head CNN tensor schema matching Kotlin BoardModel.kt & HybridFusionPolicy.kt.

Single concatenated output tensor [N, 47] avoids TFLite multi-output tensor reordering issues
and supports dynamic batch sizes N (e.g. 30 for 6x5, 48 for 6x8).
"""

from __future__ import annotations

SCHEMA_VERSION = 1
INPUT_SIZE = 64

ELEMENT_NAMES = [
    "WATER",    # 0
    "FIRE",     # 1
    "WOOD",     # 2
    "LIGHT",    # 3
    "DARK",     # 4
    "HEART",    # 5
    "HIDDEN",   # 6
    "UNKNOWN",  # 7
    "EMPTY",    # 8
]

VARIANT_NAMES = [
    "NORMAL",     # 0
    "ENCHANTED",  # 1
    "RACE",       # 2
    "WEAKENED",   # 3
]

RACE_NAMES = [
    "NONE",     # 0
    "GOD",      # 1
    "DEMON",    # 2
    "HUMAN",    # 3
    "BEAST",    # 4
    "DRAGON",   # 5
    "ELF",      # 6
    "MACHINA",  # 7
]

# 17 hazard bits aligned with HybridFusionPolicy.HAZARD_BITS_MAP
HAZARD_NAMES = [
    "WEATHERED",          # 0
    "ELECTRIFIED",        # 1
    "FROZEN",             # 2
    "ICE_SEALED",         # 3
    "BLOOD_DRAIN_LOCKED", # 4
    "PETRIFIED",          # 5
    "BOMB",               # 6
    "QUESTION",           # 7
    "BLACK_WHITE",        # 8
    "PURGATORY_FIRE",     # 9
    "EVIL_ENERGY",        # 10
    "THORN",              # 11
    "COAGULATED",         # 12
    "FOAM",               # 13
    "LUMINOUS",           # 14
    "MAGIC_ORB",          # 15
    "BLOOD_THORN",        # 16
]

# 8 per-tile overlays aligned with HybridFusionPolicy.TILE_OVERLAY_BITS_MAP
TILE_OVERLAY_NAMES = [
    "PUZZLE",                  # 0
    "STORM",                   # 1
    "SHATTERED",               # 2
    "BURNING_TILE",            # 3
    "SCORCHING_TILE",          # 4
    "END_FORBIDDEN_BLOODBATH", # 5
    "BLACK_HOLE",              # 6
    "TRAFFIC_LIGHT_MARKER",    # 7
]

NUM_IS_RUNE = 1
NUM_ELEMENTS = len(ELEMENT_NAMES)          # 9
NUM_VARIANTS = len(VARIANT_NAMES)          # 4
NUM_RACES = len(RACE_NAMES)                # 8
NUM_HAZARDS = len(HAZARD_NAMES)            # 17
NUM_TILE_OVERLAYS = len(TILE_OVERLAY_NAMES)  # 8

SLICE_IS_RUNE = (0, 1)
SLICE_ELEMENT = (1, 10)
SLICE_VARIANT = (10, 14)
SLICE_RACE = (14, 22)
SLICE_HAZARDS = (22, 39)
SLICE_TILE_OVERLAY = (39, 47)

OUTPUT_DIM = 47

assert OUTPUT_DIM == (
    NUM_IS_RUNE + NUM_ELEMENTS + NUM_VARIANTS + NUM_RACES + NUM_HAZARDS + NUM_TILE_OVERLAYS
)

ELEMENT_TO_IDX = {name: idx for idx, name in enumerate(ELEMENT_NAMES)}
VARIANT_TO_IDX = {name: idx for idx, name in enumerate(VARIANT_NAMES)}
RACE_TO_IDX = {name: idx for idx, name in enumerate(RACE_NAMES)}
HAZARD_TO_IDX = {name: idx for idx, name in enumerate(HAZARD_NAMES)}
TILE_OVERLAY_TO_IDX = {name: idx for idx, name in enumerate(TILE_OVERLAY_NAMES)}
