#include "autodora/board.hpp"

#include <cctype>
#include <stdexcept>

namespace autodora {

void BoardState::rebuildMasks() noexcept {
    validMask = 0;
    puzzleMask1 = 0;
    puzzleMask2 = 0;
    startMask = 0;
    endMask = 0;
    stormMask = 0;
    shatteredMask = 0;
    burningTileMask = 0;
    stickyTileMask = 0;
    blackHoleMask = 0;
    noPassMask = 0;
    voidMask = 0;
    barrierMask = 0;
    sealedStoneMask = 0;
    metamorphosisMask = 0;
    trailCorrosionMask = 0;
    miasmaMask = 0;
    charOrbPassMask = 0;
    charOrbEndMask = 0;
    scorchingTileMask = 0;
    endForbiddenMask = 0;

    const uint8_t n = cellCount();
    for (uint8_t i = 0; i < n; ++i) {
        const uint64_t b = bitOf(i);
        const uint32_t f = tileFlags[i];
        if ((f & TileFlagBits::VoidTile) != 0) {
            voidMask |= b;
            continue;
        }
        validMask |= b;
        if (f & TileFlagBits::Puzzle1)              puzzleMask1 |= b;
        if (f & TileFlagBits::Puzzle2)              puzzleMask2 |= b;
        if (f & TileFlagBits::StartAllowed)         startMask |= b;
        if (f & TileFlagBits::EndRequired)          endMask |= b;
        if (f & TileFlagBits::Storm)                stormMask |= b;
        if (f & TileFlagBits::Shattered)            shatteredMask |= b;
        if (f & TileFlagBits::BurningTile)          burningTileMask |= b;
        if (f & TileFlagBits::StickyTile)           stickyTileMask |= b;
        if (f & TileFlagBits::BlackHole)            { blackHoleMask |= b; endForbiddenMask |= b; }
        if (f & TileFlagBits::NoPass)               noPassMask |= b;
        if (f & TileFlagBits::BarrierFirstWaveOnly) barrierMask |= b;
        if (f & TileFlagBits::SealedStone)          sealedStoneMask |= b;
        if (f & TileFlagBits::Metamorphosis)        metamorphosisMask |= b;
        if (f & TileFlagBits::TrailCorrosion)       trailCorrosionMask |= b;
        if (f & TileFlagBits::Miasma)               miasmaMask |= b;
        if (f & TileFlagBits::CharOrbPass)          charOrbPassMask |= b;
        if (f & TileFlagBits::CharOrbEnd)           charOrbEndMask |= b;
        if (f & TileFlagBits::ScorchingTile)        scorchingTileMask |= b;
        if (f & TileFlagBits::EndForbidden)         endForbiddenMask |= b;
    }
}

BoardState BoardState::fromString(std::string_view text, uint8_t cols, uint8_t rows) {
    BoardState b;
    b.cols = cols;
    b.rows = rows;
    const uint8_t total = static_cast<uint8_t>(cols * rows);
    uint8_t idx = 0;

    for (size_t i = 0; i < text.size() && idx < total; ++i) {
        const char ch = text[i];
        if (std::isspace(static_cast<unsigned char>(ch)) || ch == '|' || ch == '-' || ch == ',') {
            continue;
        }
        if (ch == '+') {
            if (idx > 0) {
                b.cells[idx - 1].variant = Variant::Enchanted;
            }
            continue;
        }
        if (ch == '*') {
            if (idx > 0) {
                b.cells[idx - 1].variant = Variant::Race;
            }
            continue;
        }
        Cell cell;
        cell.element = charToElement(ch);
        cell.variant = Variant::Normal;
        cell.hazards = HazardBits::None;
        b.cells[idx++] = cell;
    }

    if (idx != total) {
        throw std::invalid_argument("BoardState::fromString: orb count does not match cols*rows");
    }
    b.rebuildMasks();
    return b;
}

std::string BoardState::toString() const {
    std::string out;
    out.reserve(static_cast<size_t>(rows) * (cols * 2 + 1));
    for (uint8_t r = 0; r < rows; ++r) {
        for (uint8_t c = 0; c < cols; ++c) {
            const Cell& cell = at(r, c);
            out.push_back(elementToChar(cell.element));
            out.push_back(cell.variant == Variant::Enchanted ? '+' : ' ');
        }
        out.push_back('\n');
    }
    return out;
}

} // namespace autodora
