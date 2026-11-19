#pragma once

#include "autodora/types.hpp"

#include <array>
#include <cstdint>
#include <string>
#include <string_view>

namespace autodora {

constexpr uint8_t kMaxCheckpoints = 8;

struct Cell {
    Element element = Element::Empty;
    Variant variant = Variant::Normal;
    uint32_t hazards = HazardBits::None;

    constexpr bool isMatchable() const noexcept {
        return isMatchableElement(element);
    }

    constexpr bool isImmovable() const noexcept {
        return (hazards & (HazardBits::IceSealed | HazardBits::Locked | HazardBits::Petrified | HazardBits::Foam)) != 0;
    }

    constexpr bool isUnmatchableHazard() const noexcept {
        return (hazards & (HazardBits::IceSealed | HazardBits::Petrified | HazardBits::Foam | HazardBits::Sleep)) != 0;
    }
};

// Fixed-size POD-friendly BoardState: ZERO heap allocations on copy during beam search!
struct BoardState {
    uint8_t cols = 6;
    uint8_t rows = 5;
    std::array<Cell, kMaxCells> cells{};
    std::array<uint32_t, kMaxCells> tileFlags{};
    std::array<uint8_t, kMaxCheckpoints> orderedCheckpoints{};
    uint8_t checkpointCount = 0;

    // Precomputed 64-bit tile masks for O(1) solver checks
    uint64_t validMask = 0;
    uint64_t puzzleMask1 = 0;
    uint64_t puzzleMask2 = 0;
    uint64_t startMask = 0;
    uint64_t endMask = 0;
    uint64_t stormMask = 0;
    uint64_t shatteredMask = 0;
    uint64_t burningTileMask = 0;
    uint64_t stickyTileMask = 0;
    uint64_t blackHoleMask = 0;
    uint64_t noPassMask = 0;
    uint64_t voidMask = 0;
    uint64_t barrierMask = 0;
    uint64_t sealedStoneMask = 0;
    uint64_t metamorphosisMask = 0;
    uint64_t trailCorrosionMask = 0;
    uint64_t miasmaMask = 0;
    uint64_t charOrbPassMask = 0;
    uint64_t charOrbEndMask = 0;

    constexpr uint8_t cellCount() const noexcept {
        return static_cast<uint8_t>(cols * rows);
    }

    constexpr bool inBounds(int r, int c) const noexcept {
        return r >= 0 && r < static_cast<int>(rows) && c >= 0 && c < static_cast<int>(cols);
    }

    constexpr uint8_t index(uint8_t r, uint8_t c) const noexcept {
        return cellIndex(r, c, cols);
    }

    Cell& at(uint8_t r, uint8_t c) noexcept {
        return cells[index(r, c)];
    }

    const Cell& at(uint8_t r, uint8_t c) const noexcept {
        return cells[index(r, c)];
    }

    void setTileFlag(uint8_t r, uint8_t c, uint32_t flag) noexcept {
        tileFlags[index(r, c)] |= flag;
    }

    void addCheckpoint(uint8_t r, uint8_t c) noexcept {
        if (checkpointCount < kMaxCheckpoints) {
            orderedCheckpoints[checkpointCount++] = index(r, c);
        }
    }

    void rebuildMasks() noexcept;

    bool isValidGeometry() const noexcept {
        return cols >= kMinBoardDim && cols <= kMaxBoardDim &&
               rows >= kMinBoardDim && rows <= kMaxBoardDim;
    }

    static BoardState fromString(std::string_view text, uint8_t cols = 6, uint8_t rows = 5);
    std::string toString() const;
};

} // namespace autodora
