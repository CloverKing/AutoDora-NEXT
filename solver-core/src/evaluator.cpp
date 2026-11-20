#include "autodora/evaluator.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>

namespace autodora {

namespace {

constexpr double kHardViolation = 100000.0;

inline int manhattanIdx(uint8_t a, uint8_t b, uint8_t cols) noexcept {
    return std::abs(static_cast<int>(a / cols) - static_cast<int>(b / cols)) +
           std::abs(static_cast<int>(a % cols) - static_cast<int>(b % cols));
}

inline uint64_t orthogonalNeighborsMask(uint64_t mask, uint8_t cols, uint8_t rows) noexcept {
    uint64_t out = 0;
    const uint8_t n = static_cast<uint8_t>(cols * rows);
    for (uint8_t idx = 0; idx < n; ++idx) {
        if ((mask & bitOf(idx)) == 0) continue;
        const int r = idx / cols;
        const int c = idx % cols;
        if (r > 0)        out |= bitOf(static_cast<uint8_t>(idx - cols));
        if (r + 1 < rows) out |= bitOf(static_cast<uint8_t>(idx + cols));
        if (c > 0)        out |= bitOf(static_cast<uint8_t>(idx - 1));
        if (c + 1 < cols) out |= bitOf(static_cast<uint8_t>(idx + 1));
    }
    return out;
}

// Compute sum of distances from target empty slots to nearest available matching orbs outside mask
// + cursor proximity attraction so Beam Search steers the finger directly to fetch missing orbs!
double computePullDistanceShaping(
    const BoardState& board,
    uint64_t targetMask,
    Element el,
    bool requireEnchanted,
    uint8_t cursorIdx,
    double perMissingWeight,
    double enchantedWeight
) noexcept {
    const uint8_t n = board.cellCount();
    const uint8_t cols = board.cols;

    std::array<uint8_t, kMaxCells> avail{};
    uint8_t availCount = 0;
    bool hasEnchInside = false;
    std::array<uint8_t, kMaxCells> availEnch{};
    uint8_t availEnchCount = 0;

    for (uint8_t i = 0; i < n; ++i) {
        const Cell& c = board.cells[i];
        if (c.element != el || c.isUnmatchableHazard()) continue;
        if (targetMask & bitOf(i)) {
            if (c.variant == Variant::Enchanted) hasEnchInside = true;
        } else if (!c.isImmovable()) {
            avail[availCount++] = i;
            if (c.variant == Variant::Enchanted) availEnch[availEnchCount++] = i;
        }
    }

    double penalty = 0.0;
    uint64_t usedAvail = 0;
    int minCursorToWork = 12;

    for (uint8_t i = 0; i < n; ++i) {
        if ((targetMask & bitOf(i)) == 0) continue;
        if (board.cells[i].element == el) continue;
        int bestD = 12;
        uint8_t bestJ = 255;
        for (uint8_t k = 0; k < availCount; ++k) {
            const uint8_t j = avail[k];
            if (usedAvail & bitOf(j)) continue;
            const int d = manhattanIdx(i, j, cols);
            if (d < bestD) {
                bestD = d;
                bestJ = j;
            }
        }
        if (bestJ != 255) {
            usedAvail |= bitOf(bestJ);
            const int cd = std::min(manhattanIdx(cursorIdx, bestJ, cols), manhattanIdx(cursorIdx, i, cols));
            if (cd < minCursorToWork) minCursorToWork = cd;
        }
        penalty -= static_cast<double>(bestD) * perMissingWeight;
    }

    if (requireEnchanted && !hasEnchInside && availEnchCount > 0) {
        int bestEnchD = 12;
        uint8_t bestEnchIdx = availEnch[0];
        for (uint8_t i = 0; i < n; ++i) {
            if ((targetMask & bitOf(i)) == 0) continue;
            for (uint8_t k = 0; k < availEnchCount; ++k) {
                const int d = manhattanIdx(i, availEnch[k], cols);
                if (d < bestEnchD) {
                    bestEnchD = d;
                    bestEnchIdx = availEnch[k];
                }
            }
        }
        penalty -= static_cast<double>(bestEnchD) * enchantedWeight;
        const int cd = manhattanIdx(cursorIdx, bestEnchIdx, cols);
        if (cd < minCursorToWork) minCursorToWork = cd;
    }

    // Guide finger towards the missing orb or empty puzzle slot!
    penalty -= static_cast<double>(minCursorToWork) * 38.0;
    return penalty;
}

bool evaluatePuzzleMask(
    const BoardState& boardAfterMoves,
    const SimulationResult& sim,
    uint64_t puzzleMask,
    const PuzzleShieldRule& pRule,
    uint8_t cursorIdx,
    double& shapingOut
) noexcept {
    if (!pRule.enabled || puzzleMask == 0) return true;

    const uint8_t targetSize = static_cast<uint8_t>(std::popcount(puzzleMask));
    const uint8_t n = boardAfterMoves.cellCount();

    // Check exact satisfaction against Wave 1 match groups FIRST
    for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
        const MatchGroup& g = sim.groups[gi];
        if (g.wave != 1) continue;
        if (pRule.requiredElement >= 0 && static_cast<int8_t>(g.element) != pRule.requiredElement) {
            continue;
        }
        if (pRule.requireEnchanted && g.enchantedCount == 0) {
            continue;
        }
        if (pRule.strictNoExternalTouch) {
            if (g.cellMask == puzzleMask) {
                const uint64_t extNeighbors =
                    orthogonalNeighborsMask(puzzleMask, boardAfterMoves.cols, boardAfterMoves.rows) & ~puzzleMask;
                bool externalLeak = false;
                for (uint8_t i = 0; i < n; ++i) {
                    if ((extNeighbors & bitOf(i)) != 0 && boardAfterMoves.cells[i].element == g.element) {
                        externalLeak = true;
                        break;
                    }
                }
                if (!externalLeak) {
                    shapingOut += static_cast<double>(targetSize) * 1000.0;
                    return true;
                }
            }
        } else if ((g.cellMask & puzzleMask) == puzzleMask) {
            shapingOut += static_cast<double>(targetSize) * 1000.0;
            return true;
        }
    }

    if (!pRule.strictNoExternalTouch && pRule.requiredElement < 0 && !pRule.requireEnchanted) {
        if ((sim.firstWaveClearedMask & puzzleMask) == puzzleMask) {
            shapingOut += static_cast<double>(targetSize) * 1000.0;
            return true;
        }
    }

    // Not yet solved: compute laser-guided shaping for the best viable color
    std::array<uint8_t, kNumElements> countsInMask{};
    std::array<uint8_t, kNumElements> enchInMask{};
    for (uint8_t i = 0; i < n; ++i) {
        if ((puzzleMask & bitOf(i)) == 0) continue;
        const Cell& c = boardAfterMoves.cells[i];
        if (!c.isMatchable()) continue;
        const uint8_t e = static_cast<uint8_t>(c.element);
        countsInMask[e]++;
        if (c.variant == Variant::Enchanted) enchInMask[e]++;
    }

    double bestCandShaping = -1e9;
    const uint64_t extNeighbors =
        orthogonalNeighborsMask(puzzleMask, boardAfterMoves.cols, boardAfterMoves.rows) & ~puzzleMask;

    for (uint8_t e = 0; e < kNumElements; ++e) {
        if (pRule.requiredElement >= 0 && static_cast<int8_t>(e) != pRule.requiredElement) continue;
        if (sim.initialElementCounts[e] < targetSize) continue;

        double s = static_cast<double>(countsInMask[e]) * 800.0;
        if (pRule.requireEnchanted && enchInMask[e] > 0) s += 550.0;
        s += computePullDistanceShaping(boardAfterMoves, puzzleMask, static_cast<Element>(e),
                                        pRule.requireEnchanted, cursorIdx, 140.0, 260.0);

        if (pRule.strictNoExternalTouch) {
            uint8_t leakCount = 0;
            for (uint8_t i = 0; i < n; ++i) {
                if ((extNeighbors & bitOf(i)) != 0 && boardAfterMoves.cells[i].element == static_cast<Element>(e)) {
                    leakCount++;
                }
            }
            s -= static_cast<double>(leakCount) * 320.0;
        }
        if (s > bestCandShaping) bestCandShaping = s;
    }

    if (bestCandShaping > -1e8) {
        shapingOut += bestCandShaping;
    }
    return false;
}

double computeCrossShaping(
    const BoardState& board,
    const SimulationResult& sim,
    const CrossShieldRule& cRule,
    uint8_t cursorIdx
) noexcept {
    const uint8_t cols = board.cols;
    const uint8_t rows = board.rows;
    const uint8_t n = board.cellCount();

    double best1 = -1e9, best2 = -1e9;
    for (uint8_t r = 1; r + 1 < rows; ++r) {
        for (uint8_t c = 1; c + 1 < cols; ++c) {
            const uint64_t crossMask =
                bitOf(cellIndex(r, c, cols)) |
                bitOf(cellIndex(static_cast<uint8_t>(r - 1), c, cols)) |
                bitOf(cellIndex(static_cast<uint8_t>(r + 1), c, cols)) |
                bitOf(cellIndex(r, static_cast<uint8_t>(c - 1), cols)) |
                bitOf(cellIndex(r, static_cast<uint8_t>(c + 1), cols));
            if ((crossMask & board.validMask) != crossMask) continue;
            if ((crossMask & (board.stormMask | board.shatteredMask)) != 0) continue;

            const uint64_t extMask = orthogonalNeighborsMask(crossMask, cols, rows) & ~crossMask;

            const uint8_t eStart = (cRule.requiredElement >= 0) ? static_cast<uint8_t>(cRule.requiredElement) : 0;
            const uint8_t eEnd   = (cRule.requiredElement >= 0) ? static_cast<uint8_t>(cRule.requiredElement + 1) : kNumElements;

            for (uint8_t e = eStart; e < eEnd; ++e) {
                if (sim.initialElementCounts[e] < 5) continue;
                const Element el = static_cast<Element>(e);
                uint8_t filled = 0;
                for (uint8_t i = 0; i < n; ++i) {
                    if ((crossMask & bitOf(i)) != 0 && board.cells[i].element == el) filled++;
                }
                if (filled == 0) continue;

                double val = static_cast<double>(filled) * 700.0;
                val += computePullDistanceShaping(board, crossMask, el, false, cursorIdx, 130.0, 0.0);

                if (cRule.strict5Orbs) {
                    uint8_t leaks = 0;
                    for (uint8_t i = 0; i < n; ++i) {
                        if ((extMask & bitOf(i)) != 0 && board.cells[i].element == el) leaks++;
                    }
                    val -= static_cast<double>(leaks) * 280.0;
                }

                if (val > best1) {
                    best2 = best1;
                    best1 = val;
                } else if (val > best2) {
                    best2 = val;
                }
            }
        }
    }

    double total = 0.0;
    if (best1 > -1e8) total += best1;
    if (cRule.requiredCount >= 2 && best2 > -1e8) total += best2 * 0.7;
    return total;
}

double computeAnchorOrbShaping(const BoardState& board, uint8_t anchorIdx, uint8_t cursorIdx, double scale) noexcept {
    const Cell& anchor = board.cells[anchorIdx];
    if (!anchor.isMatchable()) return 0.0;
    const Element el = anchor.element;
    const uint8_t cols = board.cols;
    const uint8_t n = board.cellCount();
    const int ar = anchorIdx / cols;
    const int ac = anchorIdx % cols;

    int bestLineDist = 20;
    int bestCursorDist = 12;

    auto checkPairSlots = [&](int r1, int c1, int r2, int c2) {
        if (!board.inBounds(r1, c1) || !board.inBounds(r2, c2)) return;
        const uint8_t s1 = cellIndex(static_cast<uint8_t>(r1), static_cast<uint8_t>(c1), cols);
        const uint8_t s2 = cellIndex(static_cast<uint8_t>(r2), static_cast<uint8_t>(c2), cols);
        if ((board.validMask & bitOf(s1)) == 0 || (board.validMask & bitOf(s2)) == 0) return;

        int d1 = 10, d2 = 10;
        uint8_t used1 = 255, used2 = 255;
        for (uint8_t i = 0; i < n; ++i) {
            if (i == anchorIdx || board.cells[i].element != el || board.cells[i].isUnmatchableHazard()) continue;
            const int d = manhattanIdx(s1, i, cols);
            if (d < d1) { d1 = d; used1 = i; }
        }
        for (uint8_t i = 0; i < n; ++i) {
            if (i == anchorIdx || i == used1 || board.cells[i].element != el || board.cells[i].isUnmatchableHazard()) continue;
            const int d = manhattanIdx(s2, i, cols);
            if (d < d2) { d2 = d; used2 = i; }
        }
        if (d1 + d2 < bestLineDist) {
            bestLineDist = d1 + d2;
            int cd = 12;
            if (d1 > 0 && used1 != 255) cd = std::min(cd, manhattanIdx(cursorIdx, used1, cols));
            if (d2 > 0 && used2 != 255) cd = std::min(cd, manhattanIdx(cursorIdx, used2, cols));
            bestCursorDist = cd;
        }
    };

    checkPairSlots(ar, ac + 1, ar, ac + 2);
    checkPairSlots(ar, ac - 1, ar, ac + 1);
    checkPairSlots(ar, ac - 2, ar, ac - 1);
    checkPairSlots(ar + 1, ac, ar + 2, ac);
    checkPairSlots(ar - 1, ac, ar + 1, ac);
    checkPairSlots(ar - 2, ac, ar - 1, ac);

    return (14.0 - static_cast<double>(std::min(bestLineDist, 14))) * scale -
           static_cast<double>(bestCursorDist) * (scale * 0.25);
}

// Universal high-combo shaping: adjacent pairs + compactness of remaining 3+-orb color clusters!
double computeClusterShaping(const BoardState& settled, const RuleConfig& rules) noexcept {
    const uint8_t cols = settled.cols;
    const uint8_t rows = settled.rows;
    double score = 0.0;

    std::array<std::array<uint8_t, kMaxCells>, kNumElements> positions{};
    std::array<uint8_t, kNumElements> counts{};

    for (uint8_t r = 0; r < rows; ++r) {
        for (uint8_t c = 0; c < cols; ++c) {
            const uint8_t idx = cellIndex(r, c, cols);
            const Cell& cell = settled.cells[idx];
            if (!cell.isMatchable() || cell.isUnmatchableHazard()) continue;
            const uint8_t e = static_cast<uint8_t>(cell.element);
            const auto& er = rules.elementRules[e];
            if (er.timing == MatchTiming::ForbidAll || er.priority == ElementPriority::Reserve) continue;

            positions[e][counts[e]++] = idx;

            const double w = (er.priority == ElementPriority::High) ? 18.0 : 12.0;
            if (c + 1 < cols && settled.at(r, static_cast<uint8_t>(c + 1)).element == cell.element) {
                score += w;
            }
            if (r + 1 < rows && settled.at(static_cast<uint8_t>(r + 1), c).element == cell.element) {
                score += w;
            }
        }
    }

    // Reward compact remaining triplets (pulls distant 3rd orb toward an existing pair!)
    for (uint8_t e = 0; e < kNumElements; ++e) {
        const uint8_t req = rules.elementRules[e].minMatchCount;
        if (counts[e] < req || req < 2) continue;
        // Find minimum sum of distances from any orb of color e to its nearest (req-1) same-color neighbors
        int bestSpan = 100;
        for (uint8_t i = 0; i < counts[e]; ++i) {
            int d1 = 20, d2 = 20;
            for (uint8_t j = 0; j < counts[e]; ++j) {
                if (i == j) continue;
                const int d = manhattanIdx(positions[e][i], positions[e][j], cols);
                if (d < d1) { d2 = d1; d1 = d; }
                else if (d < d2) { d2 = d; }
            }
            const int span = (req == 2) ? d1 : (d1 + d2);
            if (span < bestSpan) bestSpan = span;
        }
        score += (12.0 - static_cast<double>(std::min(bestSpan, 12))) * 4.5;
    }

    return score;
}

} // namespace

EvalResult evaluateCandidate(
    const BoardState& boardAfterMoves,
    const SimulationResult& sim,
    const PathMetrics& path,
    const RuleConfig& rules
) {
    EvalResult ev;
    uint32_t sat = 0;
    uint32_t fail = 0;
    double penalty = 0.0;
    double shaping = 0.0;
    double soft = 0.0;

    auto markFail = [&](uint32_t bit, double mult = 1.0) {
        fail |= bit;
        penalty += kHardViolation * mult;
    };
    auto markSat = [&](uint32_t bit) {
        sat |= bit;
    };

    const uint8_t totalCells = boardAfterMoves.cellCount();

    // =========================================================================
    // 1. TRAFFIC LIGHT, CHECKPOINTS, STEPS & BURNING LIMITS
    // =========================================================================
    if (boardAfterMoves.endMask != 0) {
        if ((boardAfterMoves.endMask & bitOf(path.endIdx)) != 0) {
            markSat(ShieldStatusBits::TrafficLight);
        } else {
            markFail(ShieldStatusBits::TrafficLight, 1.5);
            int minDist = 100;
            for (uint8_t i = 0; i < totalCells; ++i) {
                if (boardAfterMoves.endMask & bitOf(i)) {
                    const int d = manhattanIdx(path.endIdx, i, boardAfterMoves.cols);
                    if (d < minDist) minDist = d;
                }
            }
            shaping -= static_cast<double>(minDist) * 200.0;
        }
    }

    if (boardAfterMoves.checkpointCount > 0) {
        if (path.checkpointsReached >= boardAfterMoves.checkpointCount) {
            markSat(ShieldStatusBits::TrafficLight);
        } else {
            const uint8_t missing = static_cast<uint8_t>(boardAfterMoves.checkpointCount - path.checkpointsReached);
            markFail(ShieldStatusBits::TrafficLight, static_cast<double>(missing));
            shaping += static_cast<double>(path.checkpointsReached) * 400.0;
            const uint8_t nextCp = boardAfterMoves.orderedCheckpoints[path.checkpointsReached];
            shaping -= static_cast<double>(manhattanIdx(path.endIdx, nextCp, boardAfterMoves.cols)) * 120.0;
        }
    }

    if (rules.exactSteps >= 0) {
        if (path.stepCount == static_cast<uint8_t>(rules.exactSteps)) {
            markSat(ShieldStatusBits::StepLimit);
        } else {
            const int diff = std::abs(static_cast<int>(path.stepCount) - static_cast<int>(rules.exactSteps));
            markFail(ShieldStatusBits::StepLimit, 1.0);
            shaping -= static_cast<double>(diff) * 200.0;
        }
    } else if (path.stepCount > rules.maxSteps) {
        markFail(ShieldStatusBits::StepLimit, 2.0);
    }

    if (path.burningTileHits > rules.maxBurningTileHurts) {
        markFail(ShieldStatusBits::BurningLimit, static_cast<double>(path.burningTileHits - rules.maxBurningTileHurts));
    } else if (boardAfterMoves.burningTileMask != 0) {
        markSat(ShieldStatusBits::BurningLimit);
    }
    if (rules.trailBurningEnabled) {
        if (path.trailBurningHits > rules.maxTrailHurts) {
            markFail(ShieldStatusBits::BurningLimit, static_cast<double>(path.trailBurningHits - rules.maxTrailHurts));
        } else {
            markSat(ShieldStatusBits::BurningLimit);
        }
    }

    if (boardAfterMoves.miasmaMask != 0) {
        if (path.miasmaHits > rules.maxMiasmaHits) {
            markFail(ShieldStatusBits::MiasmaLimit, static_cast<double>(path.miasmaHits - rules.maxMiasmaHits));
        } else {
            markSat(ShieldStatusBits::MiasmaLimit);
        }
    }

    if (rules.requiredUniqueCellsVisited > 0) {
        if (path.uniqueCellsVisited >= rules.requiredUniqueCellsVisited) {
            markSat(ShieldStatusBits::TrailCellsGoal);
            shaping += 1200.0;
        } else {
            markFail(ShieldStatusBits::TrailCellsGoal, 1.0);
            shaping += static_cast<double>(path.uniqueCellsVisited) * 240.0;
        }
    }

    // Character Orb Pass & End Terrain Constraints (角色符石通行/停放地型)
    if ((rules.requireCharOrbPass || boardAfterMoves.charOrbPassMask != 0) && boardAfterMoves.charOrbPassMask != 0) {
        const uint64_t missingPass = boardAfterMoves.charOrbPassMask & ~path.charOrbVisitedMask;
        if (missingPass == 0) {
            markSat(ShieldStatusBits::CharOrbTerrain);
            shaping += 1400.0;
        } else {
            const uint8_t missCnt = static_cast<uint8_t>(std::popcount(missingPass));
            const uint8_t hitCnt = static_cast<uint8_t>(std::popcount(boardAfterMoves.charOrbPassMask & path.charOrbVisitedMask));
            markFail(ShieldStatusBits::CharOrbTerrain, static_cast<double>(missCnt));
            shaping += static_cast<double>(hitCnt) * 420.0;
            int minDist = 100;
            for (uint8_t i = 0; i < totalCells; ++i) {
                if (missingPass & bitOf(i)) {
                    const int d = manhattanIdx(path.endIdx, i, boardAfterMoves.cols);
                    if (d < minDist) minDist = d;
                }
            }
            shaping -= static_cast<double>(minDist) * 140.0;
        }
    }
    if ((rules.requireCharOrbEnd || boardAfterMoves.charOrbEndMask != 0) && boardAfterMoves.charOrbEndMask != 0) {
        if (path.charOrbEndIdx < totalCells && (boardAfterMoves.charOrbEndMask & bitOf(path.charOrbEndIdx)) != 0) {
            markSat(ShieldStatusBits::CharOrbTerrain);
            shaping += 1200.0;
        } else {
            markFail(ShieldStatusBits::CharOrbTerrain, 1.5);
            int minDist = 100;
            for (uint8_t i = 0; i < totalCells; ++i) {
                if (boardAfterMoves.charOrbEndMask & bitOf(i)) {
                    const int d = manhattanIdx(path.endIdx, i, boardAfterMoves.cols);
                    if (d < minDist) minDist = d;
                }
            }
            shaping -= static_cast<double>(minDist) * 220.0;
        }
    }

    // =========================================================================
    // 2. HAZARD CLEARANCE
    // =========================================================================
    bool hazardFail = false;
    if (rules.mustClearElectrified && sim.electrifiedRemaining > 0) {
        hazardFail = true;
        markFail(ShieldStatusBits::HazardsClear, static_cast<double>(sim.electrifiedRemaining) * 1.5);
        for (uint8_t i = 0; i < totalCells; ++i) {
            if ((boardAfterMoves.cells[i].hazards & HazardBits::Electrified) != 0 &&
                (sim.allWavesClearedMask & bitOf(i)) == 0) {
                shaping += computeAnchorOrbShaping(boardAfterMoves, i, path.endIdx, 140.0);
            }
        }
    }
    if (rules.mustClearBloodDrain && sim.bloodDrainRemainingFirstWave > 0) {
        hazardFail = true;
        markFail(ShieldStatusBits::HazardsClear, static_cast<double>(sim.bloodDrainRemainingFirstWave));
        for (uint8_t i = 0; i < totalCells; ++i) {
            if ((boardAfterMoves.cells[i].hazards & HazardBits::BloodDrain) != 0 &&
                (sim.firstWaveClearedMask & bitOf(i)) == 0) {
                shaping += computeAnchorOrbShaping(boardAfterMoves, i, path.endIdx, 120.0);
            }
        }
    }
    if (rules.mustClearBomb && sim.bombRemainingFirstWave > 0) {
        hazardFail = true;
        markFail(ShieldStatusBits::HazardsClear, static_cast<double>(sim.bombRemainingFirstWave) * 1.5);
        for (uint8_t i = 0; i < totalCells; ++i) {
            if ((boardAfterMoves.cells[i].hazards & HazardBits::Bomb) != 0 &&
                (sim.firstWaveClearedMask & bitOf(i)) == 0) {
                shaping += computeAnchorOrbShaping(boardAfterMoves, i, path.endIdx, 140.0);
            }
        }
    }
    if (rules.mustClearLockedHurt && sim.lockedHurtRemainingFirstWave > 0) {
        hazardFail = true;
        markFail(ShieldStatusBits::HazardsClear, static_cast<double>(sim.lockedHurtRemainingFirstWave) * 1.2);
        for (uint8_t i = 0; i < totalCells; ++i) {
            if ((boardAfterMoves.cells[i].hazards & HazardBits::LockedHurt) != 0 &&
                (sim.firstWaveClearedMask & bitOf(i)) == 0) {
                shaping += computeAnchorOrbShaping(boardAfterMoves, i, path.endIdx, 125.0);
            }
        }
    }
    if (rules.mustClearFoam && sim.foamRemaining > 0) {
        hazardFail = true;
        markFail(ShieldStatusBits::HazardsClear, static_cast<double>(sim.foamRemaining) * 1.2);
        shaping += static_cast<double>(sim.foamAdjacentHits) * 320.0;
        // Guide matches adjacent to unpopped foam cells
        for (uint8_t i = 0; i < totalCells; ++i) {
            if ((boardAfterMoves.cells[i].hazards & HazardBits::Foam) == 0) continue;
            const uint64_t adj = orthogonalNeighborsMask(bitOf(i), boardAfterMoves.cols, boardAfterMoves.rows);
            if ((adj & sim.allWavesClearedMask) == 0) {
                for (uint8_t j = 0; j < totalCells; ++j) {
                    if ((adj & bitOf(j)) != 0 && boardAfterMoves.cells[j].isMatchable()) {
                        shaping += computeAnchorOrbShaping(boardAfterMoves, j, path.endIdx, 40.0);
                    }
                }
            }
        }
    }
    if (!hazardFail) {
        markSat(ShieldStatusBits::HazardsClear);
    }
    if (rules.prioritizeFrozen && sim.frozenRemaining > 0) {
        soft -= static_cast<double>(sim.frozenRemaining) * 35.0;
    }
    if (rules.prioritizeLockedAtkDown && sim.lockedAtkDownRemaining > 0) {
        soft -= static_cast<double>(sim.lockedAtkDownRemaining) * 38.0;
    }
    if (sim.lightPrisonRemaining > 0) {
        soft -= static_cast<double>(sim.lightPrisonRemaining) * 45.0;
    }
    if (rules.prioritizeLuminous && sim.luminousCleared > 0) {
        soft += static_cast<double>(sim.luminousCleared) * 28.0;
    }
    if (sim.weakenedEliminated > 0) {
        if (rules.forbidWeakenedOrbs) {
            hazardFail = true;
            markFail(ShieldStatusBits::HazardsClear, static_cast<double>(sim.weakenedEliminated) * 1.5);
            shaping -= static_cast<double>(sim.weakenedEliminated) * 420.0;
        } else {
            soft -= static_cast<double>(sim.weakenedEliminated) * 8.0;
        }
    }
    if (rules.requireLuminousOrbClear) {
        bool boardHasLuminous = false;
        for (uint8_t i = 0; i < totalCells; ++i) {
            if ((boardAfterMoves.cells[i].hazards & HazardBits::Luminous) != 0) {
                boardHasLuminous = true;
                break;
            }
        }
        if (boardHasLuminous) {
            if (sim.luminousCleared > 0) {
                markSat(ShieldStatusBits::HazardsClear);
                shaping += 900.0;
            } else {
                hazardFail = true;
                markFail(ShieldStatusBits::HazardsClear, 1.0);
                for (uint8_t i = 0; i < totalCells; ++i) {
                    if ((boardAfterMoves.cells[i].hazards & HazardBits::Luminous) != 0) {
                        shaping += computeAnchorOrbShaping(boardAfterMoves, i, path.endIdx, 120.0);
                    }
                }
            }
        }
    }
    if (rules.prioritizePetrifiedClear && sim.petrifiedAdjacentHits > 0) {
        soft += static_cast<double>(sim.petrifiedAdjacentHits) * 40.0;
    }
    if (sim.priorityOrbsRemaining > 0) {
        soft -= static_cast<double>(sim.priorityOrbsRemaining) * 60.0;
    }
    if (sim.reserveOrbsBroken > 0) {
        soft -= static_cast<double>(sim.reserveOrbsBroken) * 55.0;
    }
    if (rules.allowHitWeatheredToClear && path.weatheredClearedByHit > 0) {
        soft += static_cast<double>(path.weatheredClearedByHit) * 30.0;
    }

    // =========================================================================
    // 3. COMBO SHIELDS
    // =========================================================================
    bool comboOk = true;
    const uint8_t evalCombo = rules.firstWaveOnlyScoring ? sim.firstWaveCombo : sim.totalCombo;

    if (rules.exactTotalCombo >= 0) {
        const int diff = std::abs(static_cast<int>(sim.totalCombo) - static_cast<int>(rules.exactTotalCombo));
        if (diff != 0) {
            comboOk = false;
            markFail(ShieldStatusBits::ComboShield, 1.0);
            shaping -= static_cast<double>(diff) * 600.0;
        }
    }
    if (rules.exactFirstWaveCombo >= 0) {
        const int diff = std::abs(static_cast<int>(sim.firstWaveCombo) - static_cast<int>(rules.exactFirstWaveCombo));
        if (diff != 0) {
            comboOk = false;
            markFail(ShieldStatusBits::ComboShield, 1.0);
            shaping -= static_cast<double>(diff) * 600.0;
        }
    }
    if (rules.minTotalCombo > 0 && sim.totalCombo < rules.minTotalCombo) {
        comboOk = false;
        markFail(ShieldStatusBits::ComboShield, 1.0);
        shaping -= static_cast<double>(rules.minTotalCombo - sim.totalCombo) * 500.0;
    }
    if (rules.maxTotalCombo >= 0 && sim.totalCombo > static_cast<uint8_t>(rules.maxTotalCombo)) {
        comboOk = false;
        markFail(ShieldStatusBits::ComboShield, 1.0);
        shaping -= static_cast<double>(sim.totalCombo - static_cast<uint8_t>(rules.maxTotalCombo)) * 500.0;
    }
    if (rules.minFirstWaveCombo > 0 && sim.firstWaveCombo < rules.minFirstWaveCombo) {
        comboOk = false;
        markFail(ShieldStatusBits::ComboShield, 1.0);
        shaping -= static_cast<double>(rules.minFirstWaveCombo - sim.firstWaveCombo) * 500.0;
    }
    if (comboOk && (rules.exactTotalCombo >= 0 || rules.exactFirstWaveCombo >= 0 ||
                    rules.minTotalCombo > 0 || rules.minFirstWaveCombo > 0 || rules.maxTotalCombo >= 0)) {
        markSat(ShieldStatusBits::ComboShield);
    }

    if (rules.comboParity != ComboParity::Any) {
        const uint8_t cCheck = rules.comboParityFirstWaveOnly ? sim.firstWaveCombo : sim.totalCombo;
        const bool isOdd = (cCheck & 1u) != 0;
        const bool parityOk = (cCheck > 0) &&
                              ((rules.comboParity == ComboParity::Odd && isOdd) ||
                               (rules.comboParity == ComboParity::Even && !isOdd));
        if (parityOk) {
            markSat(ShieldStatusBits::ComboParity);
        } else {
            markFail(ShieldStatusBits::ComboParity, 1.0);
        }
    }

    if (rules.sameComboAcrossEliminatedElements) {
        uint8_t refCount = 0;
        bool sameOk = true;
        uint8_t activeEls = 0;
        for (uint8_t e = 0; e < kNumElements; ++e) {
            const uint8_t cnt = sim.firstWaveElementCombos[e];
            if (cnt == 0) continue;
            activeEls++;
            if (refCount == 0) refCount = cnt;
            else if (cnt != refCount) {
                sameOk = false;
                shaping -= static_cast<double>(std::abs(static_cast<int>(cnt) - static_cast<int>(refCount))) * 250.0;
            }
        }
        if (activeEls > 0 && sameOk) {
            markSat(ShieldStatusBits::SameComboElements);
        } else {
            markFail(ShieldStatusBits::SameComboElements, 1.0);
        }
    }

    // Second-Wave (次批消除) Shield
    if (rules.minSecondWaveCombo > 0 || rules.requiredSecondWaveElementsMask != 0) {
        const uint8_t missingW2 = static_cast<uint8_t>(rules.requiredSecondWaveElementsMask & ~sim.secondWaveElementsMask);
        const bool w2ComboOk = (sim.secondWaveCombo >= rules.minSecondWaveCombo);
        if (w2ComboOk && missingW2 == 0) {
            markSat(ShieldStatusBits::SecondWaveShield);
            shaping += 1800.0;
        } else {
            markFail(ShieldStatusBits::SecondWaveShield, 1.2);
            shaping += static_cast<double>(sim.secondWaveCombo) * 650.0;
            shaping += static_cast<double>(std::popcount(static_cast<uint8_t>(rules.requiredSecondWaveElementsMask & sim.secondWaveElementsMask))) * 450.0;
            if (sim.firstWaveCombo > 0) shaping += 180.0;
        }
    }

    // Dual-Element Equal Combos (双属首消 Combo 同等盾，如首消光暗同等盾)
    if (rules.requireEqualPairCombos && rules.equalPairElemA < kNumElements && rules.equalPairElemB < kNumElements) {
        const uint8_t cA = sim.firstWaveElementCombos[rules.equalPairElemA];
        const uint8_t cB = sim.firstWaveElementCombos[rules.equalPairElemB];
        const bool pairOk = (cA == cB) && (!rules.equalPairMustBeNonZero || cA > 0);
        if (pairOk) {
            markSat(ShieldStatusBits::EqualPairCombo);
            shaping += 1600.0;
        } else {
            markFail(ShieldStatusBits::EqualPairCombo, 1.0);
            const int diff = std::abs(static_cast<int>(cA) - static_cast<int>(cB));
            shaping -= static_cast<double>(diff) * 450.0;
            shaping += static_cast<double>(std::min(cA, cB)) * 520.0;
            if (rules.equalPairMustBeNonZero && (cA == 0 || cB == 0)) {
                shaping -= 350.0;
            }
        }
    }

    // Same-Element Multi-Combo Shield (同属连击盾，如首消 3 组暗)
    if (rules.requiredSameElementCombos > 0) {
        uint8_t bestSameC = 0;
        if (rules.sameElementComboTarget >= 0 && rules.sameElementComboTarget < kNumElements) {
            bestSameC = sim.firstWaveElementCombos[static_cast<uint8_t>(rules.sameElementComboTarget)];
        } else {
            for (uint8_t e = 0; e < kNumElements; ++e) {
                if (sim.firstWaveElementCombos[e] > bestSameC) bestSameC = sim.firstWaveElementCombos[e];
            }
        }
        if (bestSameC >= rules.requiredSameElementCombos) {
            markSat(ShieldStatusBits::SameElemMultiC);
            shaping += 1700.0;
        } else {
            markFail(ShieldStatusBits::SameElemMultiC, static_cast<double>(rules.requiredSameElementCombos - bestSameC));
            shaping += static_cast<double>(bestSameC) * 650.0;
        }
    }

    // =========================================================================
    // 4. DIGITAL ORB COUNT & FULL CLEAR & ELEMENT / ENCHANTED SHIELDS
    // =========================================================================
    bool unmetFocusShield = false;

    if (rules.exactFirstWaveTotalOrbs >= 0) {
        const int diff = std::abs(static_cast<int>(sim.firstWaveOrbsTotal) - static_cast<int>(rules.exactFirstWaveTotalOrbs));
        if (diff == 0) {
            markSat(ShieldStatusBits::DigitalOrbCount);
            shaping += 2000.0;
        } else {
            unmetFocusShield = true;
            markFail(ShieldStatusBits::DigitalOrbCount, 1.0);
            shaping -= static_cast<double>(diff) * 420.0;
        }
    }

    if (rules.requireFirstWaveFullClear) {
        if (sim.matchableCellsAtStart > 0 && sim.firstWaveOrbsTotal == sim.matchableCellsAtStart) {
            markSat(ShieldStatusBits::FullClear);
            shaping += 2000.0;
        } else {
            unmetFocusShield = true;
            markFail(ShieldStatusBits::FullClear, 1.0);
            shaping += static_cast<double>(sim.firstWaveOrbsTotal) * 260.0;
        }
    }

    if (rules.requiredFirstWaveElementsMask != 0) {
        const uint8_t missing = static_cast<uint8_t>(rules.requiredFirstWaveElementsMask & ~sim.firstWaveElementsMask);
        if (missing == 0) {
            markSat(ShieldStatusBits::ElementShield);
        } else {
            markFail(ShieldStatusBits::ElementShield, static_cast<double>(std::popcount(missing)));
            const uint8_t hit = static_cast<uint8_t>(rules.requiredFirstWaveElementsMask & sim.firstWaveElementsMask);
            shaping += static_cast<double>(std::popcount(hit)) * 500.0;
        }
    }
    // Elements that require enchanted elimination are enforced by EnchantedShield below;
    // exclude them from plain ElementShield penalty so matching normal orbs without the enchanted orb
    // cannot earn a fake +100,000 reward that strands the enchanted orb!
    const uint8_t plainReqTotalMask = static_cast<uint8_t>(
        rules.requiredTotalElementsMask & ~rules.requiredEnchantedElementsMask);
    if (plainReqTotalMask != 0) {
        const uint8_t missing = static_cast<uint8_t>(plainReqTotalMask & ~sim.totalElementsMask);
        if (missing == 0) {
            markSat(ShieldStatusBits::ElementShield);
        } else {
            markFail(ShieldStatusBits::ElementShield, static_cast<double>(std::popcount(missing)));
            const uint8_t hit = static_cast<uint8_t>(plainReqTotalMask & sim.totalElementsMask);
            shaping += static_cast<double>(std::popcount(hit)) * 450.0;
        }
    }
    if (rules.minDistinctElements > 0) {
        const uint8_t distinct = static_cast<uint8_t>(std::popcount(static_cast<uint8_t>(sim.totalElementsMask & 0x3Fu)));
        if (distinct >= rules.minDistinctElements) {
            markSat(ShieldStatusBits::ElementShield);
        } else {
            markFail(ShieldStatusBits::ElementShield, static_cast<double>(rules.minDistinctElements - distinct));
            shaping += static_cast<double>(distinct) * 400.0;
        }
    }
    if (rules.requiredEnchantedElementsMask != 0) {
        const uint8_t missing = static_cast<uint8_t>(rules.requiredEnchantedElementsMask & ~sim.totalEnchantedElementsMask);
        if (missing == 0) {
            markSat(ShieldStatusBits::EnchantedShield);
            if (rules.requiredTotalElementsMask != 0) {
                markSat(ShieldStatusBits::ElementShield);
            }
        } else {
            unmetFocusShield = true;
            markFail(ShieldStatusBits::EnchantedShield, static_cast<double>(std::popcount(missing)));
            const uint8_t hit = static_cast<uint8_t>(rules.requiredEnchantedElementsMask & sim.totalEnchantedElementsMask);
            shaping += static_cast<double>(std::popcount(hit)) * 750.0;

            for (uint8_t i = 0; i < totalCells; ++i) {
                const Cell& c = boardAfterMoves.cells[i];
                if (c.variant == Variant::Enchanted && c.isMatchable()) {
                    const uint8_t eb = elementBit(c.element);
                    if ((missing & eb) != 0) {
                        shaping += computeAnchorOrbShaping(boardAfterMoves, i, path.endIdx, 140.0);
                        // Strongly penalize burning normal orbs of this color without clearing its enchanted orb!
                        if ((sim.totalElementsMask & eb) != 0) {
                            shaping -= 3000.0;
                        }
                    }
                }
            }
        }
    }

    if (rules.requireAnyEnchantedOrb) {
        if (sim.firstWaveEnchantedElementsMask != 0) {
            markSat(ShieldStatusBits::EnchantedShield);
            shaping += 1100.0;
        } else {
            unmetFocusShield = true;
            markFail(ShieldStatusBits::EnchantedShield, 1.0);
            for (uint8_t i = 0; i < totalCells; ++i) {
                const Cell& c = boardAfterMoves.cells[i];
                if (c.variant == Variant::Enchanted && c.isMatchable()) {
                    shaping += computeAnchorOrbShaping(boardAfterMoves, i, path.endIdx, 140.0);
                }
            }
        }
    }

    if (rules.requireCharOrbFirstWave) {
        if (path.charOrbEndIdx < totalCells && (sim.firstWaveClearedMask & bitOf(path.charOrbEndIdx)) != 0) {
            markSat(ShieldStatusBits::CharOrbTerrain);
            shaping += 1100.0;
        } else {
            unmetFocusShield = true;
            markFail(ShieldStatusBits::CharOrbTerrain, 1.0);
            if (path.charOrbEndIdx < totalCells) {
                shaping += computeAnchorOrbShaping(boardAfterMoves, path.charOrbEndIdx, path.endIdx, 130.0);
            }
        }
    }

    if (rules.requiredRaceElementsMask != 0 || rules.requireAnyRaceOrb) {
        const uint8_t missingRace = static_cast<uint8_t>(rules.requiredRaceElementsMask & ~sim.firstWaveRaceElementsMask);
        const bool anyRaceOk = !rules.requireAnyRaceOrb || sim.totalRaceOrbsFirstWave > 0;
        if (missingRace == 0 && anyRaceOk) {
            markSat(ShieldStatusBits::RaceOrbShield);
            shaping += 1200.0;
        } else {
            unmetFocusShield = true;
            const int missCnt = std::popcount(missingRace) + (anyRaceOk ? 0 : 1);
            markFail(ShieldStatusBits::RaceOrbShield, static_cast<double>(missCnt));
            for (uint8_t i = 0; i < totalCells; ++i) {
                const Cell& c = boardAfterMoves.cells[i];
                if (c.variant == Variant::Race && c.isMatchable()) {
                    const uint8_t eb = elementBit(c.element);
                    if ((missingRace & eb) != 0 || !anyRaceOk) {
                        shaping += computeAnchorOrbShaping(boardAfterMoves, i, path.endIdx, 130.0);
                    }
                }
            }
        }
    }

    // =========================================================================
    // 5. PER-ELEMENT TRIPLET RULES
    // =========================================================================
    for (uint8_t e = 0; e < kNumElements; ++e) {
        const ElementRule& er = rules.elementRules[e];
        const uint8_t eBit = static_cast<uint8_t>(1u << e);
        const bool hitFirst = (sim.firstWaveElementsMask & eBit) != 0;
        const bool hitAny = (sim.totalElementsMask & eBit) != 0;

        switch (er.timing) {
            case MatchTiming::MustFirstWave:
                if (!hitFirst) markFail(ShieldStatusBits::ElementShield, 1.0);
                break;
            case MatchTiming::ForbidFirstWaveAllowCascade:
                if (hitFirst) {
                    markFail(ShieldStatusBits::ForbidFirstWave, 1.5);
                } else {
                    markSat(ShieldStatusBits::ForbidFirstWave);
                    if (hitAny) soft += 25.0;
                }
                break;
            case MatchTiming::ForbidAll:
                if (hitAny) {
                    markFail(ShieldStatusBits::ForbidAll, 1.5);
                } else {
                    markSat(ShieldStatusBits::ForbidAll);
                }
                break;
            case MatchTiming::Any:
                break;
        }

        if (er.requireEnchanted) {
            if ((sim.totalEnchantedElementsMask & eBit) == 0) {
                markFail(ShieldStatusBits::EnchantedShield, 1.0);
            } else {
                markSat(ShieldStatusBits::EnchantedShield);
            }
        }
        if (er.requireRace && (sim.firstWaveRaceElementsMask & eBit) == 0) {
            markFail(ShieldStatusBits::ElementShield, 0.8);
        }
        if (er.clearAllFirstWave) {
            if (sim.remainingAfterFirstWave[e] > 0) {
                markFail(ShieldStatusBits::ClearAllElement, 1.0);
                shaping -= static_cast<double>(sim.remainingAfterFirstWave[e]) * 260.0;
            } else if (sim.initialElementCounts[e] > 0) {
                markSat(ShieldStatusBits::ClearAllElement);
            }
        }
        if (er.exactFirstWaveCombos >= 0) {
            const int diff = std::abs(static_cast<int>(sim.firstWaveElementCombos[e]) - static_cast<int>(er.exactFirstWaveCombos));
            if (diff != 0) {
                markFail(ShieldStatusBits::ComboShield, 1.0);
                shaping -= static_cast<double>(diff) * 280.0;
            }
        }
        if (er.exactFirstWaveOrbs >= 0) {
            const int diff = std::abs(static_cast<int>(sim.firstWaveElementOrbs[e]) - static_cast<int>(er.exactFirstWaveOrbs));
            if (diff != 0) {
                markFail(ShieldStatusBits::DigitalOrbCount, 1.0);
                shaping -= static_cast<double>(diff) * 220.0;
            }
        }
        if (er.minFirstWaveOrbs > 0 && sim.firstWaveElementOrbs[e] < er.minFirstWaveOrbs) {
            markFail(ShieldStatusBits::DigitalOrbCount, 1.0);
            shaping += static_cast<double>(sim.firstWaveElementOrbs[e]) * 120.0;
        }

        if (er.priority == ElementPriority::High) {
            soft += static_cast<double>(sim.totalElementCombos[e]) * 18.0 * er.customWeight;
            soft += static_cast<double>(sim.totalElementOrbs[e]) * 3.5 * er.customWeight;
        } else if (er.priority == ElementPriority::Reserve) {
            soft -= static_cast<double>(sim.totalElementOrbs[e]) * 16.0;
        } else {
            soft += static_cast<double>(sim.totalElementCombos[e]) * 4.0 * er.customWeight;
        }

        auto requiredCountForTarget = [](TargetShape ts) -> uint8_t {
            switch (ts) {
                case TargetShape::Count2: return 2;
                case TargetShape::Count4: return 4;
                case TargetShape::Count5: return 5;
                case TargetShape::Count6: return 6;
                case TargetShape::Count7: return 7;
                case TargetShape::Count8: return 8;
                case TargetShape::Count9: return 9;
                case TargetShape::Row:    return 6;
                case TargetShape::Cross:  return 5;
                case TargetShape::LShape: return 5;
                case TargetShape::TShape: return 5;
                case TargetShape::Square2x2: return 4;
                default: return 0;
            }
        };
        const uint8_t targetOrbs = requiredCountForTarget(er.targetShape);
        if (targetOrbs > 0 && sim.initialElementCounts[e] < targetOrbs) {
            if (rules.notEnoughPolicy == NotEnoughPolicy::Keep && hitAny) {
                soft -= static_cast<double>(sim.totalElementOrbs[e]) * 14.0;
            }
        } else if (er.targetShape != TargetShape::Any) {
            for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
                const MatchGroup& g = sim.groups[gi];
                if (static_cast<uint8_t>(g.element) != e) continue;
                switch (er.targetShape) {
                    case TargetShape::Split3:
                        soft += (g.orbCount == 3) ? 14.0 : -8.0 * static_cast<double>(g.orbCount - 3);
                        break;
                    case TargetShape::Count4:
                    case TargetShape::Count5:
                    case TargetShape::Count6:
                    case TargetShape::Count7:
                    case TargetShape::Count8:
                    case TargetShape::Count9:
                        if (g.orbCount >= targetOrbs) soft += 36.0;
                        break;
                    case TargetShape::Row:
                        if (g.fullRowMask != 0) soft += 45.0;
                        break;
                    case TargetShape::Column:
                        if (g.fullColMask != 0) soft += 45.0;
                        break;
                    case TargetShape::Cross:
                        if (g.containsCross) soft += 60.0;
                        break;
                    case TargetShape::LShape:
                        if (g.isLShape) soft += 50.0;
                        break;
                    case TargetShape::TShape:
                        if (g.isTShape) soft += 50.0;
                        break;
                    case TargetShape::Square2x2:
                        if (g.isSquare2x2) soft += 45.0;
                        break;
                    case TargetShape::ClearAll:
                        if (sim.remainingAfterFirstWave[e] == 0) soft += 50.0;
                        break;
                    default:
                        break;
                }
            }
        }
    }

    // =========================================================================
    // 6. PUZZLE SHIELDS
    // =========================================================================
    if (rules.puzzle1.enabled && boardAfterMoves.puzzleMask1 != 0) {
        if (evaluatePuzzleMask(boardAfterMoves, sim, boardAfterMoves.puzzleMask1, rules.puzzle1, path.endIdx, shaping)) {
            markSat(ShieldStatusBits::Puzzle1);
        } else {
            unmetFocusShield = true;
            markFail(ShieldStatusBits::Puzzle1, 2.0);
        }
    }
    if (rules.puzzle2.enabled && boardAfterMoves.puzzleMask2 != 0) {
        if (evaluatePuzzleMask(boardAfterMoves, sim, boardAfterMoves.puzzleMask2, rules.puzzle2, path.endIdx, shaping)) {
            markSat(ShieldStatusBits::Puzzle2);
        } else {
            unmetFocusShield = true;
            markFail(ShieldStatusBits::Puzzle2, 2.0);
        }
    }

    // =========================================================================
    // 7. CROSS SHIELD & L / T / 2x2 SHAPE SHIELDS
    // =========================================================================
    if (rules.crossShield.requiredCount > 0) {
        uint8_t crossFound = 0;
        uint8_t crossColorsMask = 0;
        for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
            const MatchGroup& g = sim.groups[gi];
            if (g.wave != 1) continue;
            if (rules.crossShield.requiredElement >= 0 &&
                static_cast<int8_t>(g.element) != rules.crossShield.requiredElement) {
                continue;
            }
            const bool isCross = rules.crossShield.strict5Orbs ? g.isExactCross5 : g.containsCross;
            if (!isCross) continue;
            const uint8_t eb = elementBit(g.element);
            if (rules.crossShield.requireDistinctColors && (crossColorsMask & eb) != 0) {
                continue;
            }
            crossColorsMask |= eb;
            crossFound++;
        }
        const uint8_t missingCrossColors = static_cast<uint8_t>(rules.crossShield.requiredElementsMask & ~crossColorsMask);
        const uint8_t matchedReqCrossColors = static_cast<uint8_t>(rules.crossShield.requiredElementsMask & crossColorsMask);
        if (crossFound >= rules.crossShield.requiredCount && missingCrossColors == 0) {
            markSat(ShieldStatusBits::CrossShield);
            shaping += 2200.0 * static_cast<double>(crossFound);
        } else {
            unmetFocusShield = true;
            const int missCount = std::max(
                static_cast<int>(rules.crossShield.requiredCount) - static_cast<int>(crossFound),
                static_cast<int>(std::popcount(missingCrossColors))
            );
            markFail(ShieldStatusBits::CrossShield, static_cast<double>(std::max(1, missCount)) * 1.5);
            shaping += 1200.0 * static_cast<double>(crossFound);
            shaping += 900.0 * static_cast<double>(std::popcount(matchedReqCrossColors));
            shaping += computeCrossShaping(boardAfterMoves, sim, rules.crossShield, path.endIdx);
        }
    }

    if (rules.shapeShield.requireLCount > 0 || rules.shapeShield.requireTCount > 0 ||
        rules.shapeShield.requireSquare2x2Count > 0) {
        uint8_t lFound = 0, tFound = 0, sqFound = 0;
        for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
            const MatchGroup& g = sim.groups[gi];
            if (g.wave != 1) continue;
            if (g.isLShape) lFound++;
            if (g.isTShape) tFound++;
            if (g.isSquare2x2 && (!rules.shapeShield.strictShape || g.orbCount == 4)) sqFound++;
        }
        bool shapeOk = true;
        if (lFound < rules.shapeShield.requireLCount) {
            shapeOk = false;
            markFail(ShieldStatusBits::ShapeShield, static_cast<double>(rules.shapeShield.requireLCount - lFound));
        }
        if (tFound < rules.shapeShield.requireTCount) {
            shapeOk = false;
            markFail(ShieldStatusBits::ShapeShield, static_cast<double>(rules.shapeShield.requireTCount - tFound));
        }
        if (sqFound < rules.shapeShield.requireSquare2x2Count) {
            shapeOk = false;
            markFail(ShieldStatusBits::ShapeShield, static_cast<double>(rules.shapeShield.requireSquare2x2Count - sqFound));
        }
        if (shapeOk) {
            markSat(ShieldStatusBits::ShapeShield);
        }
    }

    // =========================================================================
    // 8. AMELIORATION (直排昇华 & 横排)
    // =========================================================================
    bool anyColEnabled = false;
    bool colHardOk = true;
    for (uint8_t c = 0; c < boardAfterMoves.cols; ++c) {
        const ColumnAmeliorationRule& cr = rules.columnRules[c];
        if (!cr.enabled) continue;
        anyColEnabled = true;
        const uint8_t targetRun = (cr.minOrbs == 255) ? boardAfterMoves.rows : cr.minOrbs;
        uint8_t bestColRun = 0;
        for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
            const MatchGroup& g = sim.groups[gi];
            if (g.wave != 1) continue;
            if (cr.requiredElement >= 0 && static_cast<int8_t>(g.element) != cr.requiredElement) continue;
            if (g.colMaxRun[c] > bestColRun) bestColRun = g.colMaxRun[c];
        }
        if (bestColRun >= targetRun) {
            soft += 42.0;
        } else if (cr.hardRequired) {
            colHardOk = false;
            markFail(ShieldStatusBits::ColumnAmeliorate, 1.0);
            shaping += static_cast<double>(bestColRun) * 140.0;
        } else {
            soft += static_cast<double>(bestColRun) * 6.0;
        }
    }
    if (anyColEnabled && colHardOk) {
        markSat(ShieldStatusBits::ColumnAmeliorate);
    }

    bool anyRowEnabled = false;
    bool rowHardOk = true;
    for (uint8_t r = 0; r < boardAfterMoves.rows; ++r) {
        const RowBonusRule& rr = rules.rowRules[r];
        if (!rr.enabled) continue;
        anyRowEnabled = true;
        bool hitRow = false;
        for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
            const MatchGroup& g = sim.groups[gi];
            if (g.wave != 1) continue;
            if (rr.requiredElement >= 0 && static_cast<int8_t>(g.element) != rr.requiredElement) continue;
            if ((g.fullRowMask & (1u << r)) != 0) {
                hitRow = true;
                break;
            }
        }
        if (hitRow) {
            soft += 48.0;
        } else if (rr.hardRequired) {
            rowHardOk = false;
            markFail(ShieldStatusBits::RowBonus, 1.0);
        }
    }
    if (anyRowEnabled && rowHardOk) {
        markSat(ShieldStatusBits::RowBonus);
    }

    // =========================================================================
    // 9. CORE COMBO, CLUSTER GRADIENT, SHIELD-FIRST FOCUS & PATH COST
    // =========================================================================
    const bool hasExactComboGoal = (rules.exactTotalCombo >= 0 || rules.exactFirstWaveCombo >= 0 ||
                                    rules.maxTotalCombo >= 0 || rules.comboParity != ComboParity::Any);

    const bool needMoreOrbsForGoal =
        (!hasExactComboGoal) ||
        (rules.exactTotalCombo >= 0 && sim.totalCombo < static_cast<uint8_t>(rules.exactTotalCombo)) ||
        (rules.exactFirstWaveCombo >= 0 && sim.firstWaveCombo < static_cast<uint8_t>(rules.exactFirstWaveCombo)) ||
        (rules.exactFirstWaveTotalOrbs >= 0 && sim.firstWaveOrbsTotal < static_cast<uint16_t>(rules.exactFirstWaveTotalOrbs));

    // When a strict geometry / enchanted / digital shield is still UNMET, dampen irrelevant side-combo
    // weight so side combos never hijack Beam Search away from solving the primary shield!
    const double sideComboScale = unmetFocusShield ? 0.15 : 1.0;

    if (!hasExactComboGoal) {
        soft += static_cast<double>(evalCombo) * 140.0 * sideComboScale;
        if (!rules.firstWaveOnlyScoring) {
            soft += static_cast<double>(sim.firstWaveCombo) * 20.0 * sideComboScale;
        }
    } else {
        if (rules.avoidSkyfallRisk && comboOk) {
            soft -= static_cast<double>(sim.skyfallRiskPairs) * 8.5;
        }
        if (rules.exactFirstWaveCombo >= 0 && sim.cascadeWaves > 1) {
            soft -= static_cast<double>(sim.totalCombo - sim.firstWaveCombo) * 25.0;
        }
    }

    if (needMoreOrbsForGoal && !unmetFocusShield) {
        shaping += computeClusterShaping(sim.settledBoard, rules);
    } else if (rules.exactFirstWaveTotalOrbs >= 0 &&
               sim.firstWaveOrbsTotal < static_cast<uint16_t>(rules.exactFirstWaveTotalOrbs)) {
        shaping += computeClusterShaping(sim.settledBoard, rules) * 1.4;
    }

    if (rules.forceSplitThree) {
        for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
            const MatchGroup& g = sim.groups[gi];
            if (g.orbCount == 3) soft += 8.0 * sideComboScale;
            else if (g.orbCount > 3) soft -= static_cast<double>(g.orbCount - 3) * 10.0 * sideComboScale;
        }
    } else if (rules.fourPlusPolicy == FourPlusPolicy::MaxOrbs) {
        soft += static_cast<double>(sim.totalOrbsEliminated) * 6.0 * sideComboScale;
    } else if (rules.fourPlusPolicy == FourPlusPolicy::SingleGroup) {
        for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
            const MatchGroup& g = sim.groups[gi];
            if (g.orbCount >= 4) soft += static_cast<double>(g.orbCount) * 8.0 * sideComboScale;
        }
    } else {
        soft += static_cast<double>(sim.totalOrbsEliminated) * 3.0 * sideComboScale;
    }

    if (rules.preferEnchanted) {
        for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
            soft += static_cast<double>(sim.groups[gi].enchantedCount) * 6.0 * sideComboScale;
        }
    }
        if (rules.maximizeElementOrbIndex >= 0 && rules.maximizeElementOrbIndex < kNumElements) {
            soft += static_cast<double>(sim.totalElementOrbs[static_cast<uint8_t>(rules.maximizeElementOrbIndex)]) * 95.0;
        }

    if (rules.preferRace) {
        for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
            soft += static_cast<double>(sim.groups[gi].raceCount) * 6.0 * sideComboScale;
        }
    }

    soft -= static_cast<double>(path.stepCount) * rules.stepPenaltyWeight;
    soft -= static_cast<double>(path.diagonalSteps) * rules.diagonalPenaltyWeight;
    soft -= static_cast<double>(path.burningTileHits) * 35.0;
    soft -= static_cast<double>(path.trailBurningHits) * 35.0;
    soft -= static_cast<double>(path.miasmaHits) * 30.0;
    soft -= static_cast<double>(path.witheredVineHits) * 22.0;

    ev.allHardConstraintsMet = (fail == 0);
    ev.satisfiedFlags = sat;
    ev.failedFlags = fail;
    ev.hardPenalty = penalty;
    ev.shapingBonus = shaping;
    ev.softScore = soft;
    ev.totalScore = (ev.allHardConstraintsMet ? 1000000.0 : 0.0) - penalty + shaping + soft;
    return ev;
}

} // namespace autodora
