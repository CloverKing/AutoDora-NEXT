#include "autodora/simulator.hpp"

#include <algorithm>
#include <bit>
#include <queue>

namespace autodora {

namespace {

inline bool areElementsCompatible(Element a, Element b, const RuleConfig& rules) noexcept {
    if (!isMatchableElement(a) || !isMatchableElement(b)) return false;
    const uint8_t ia = static_cast<uint8_t>(a);
    const uint8_t ib = static_cast<uint8_t>(b);
    return ((rules.compatibleLinkMask[ia] & (1u << ib)) != 0) &&
           ((rules.compatibleLinkMask[ib] & (1u << ia)) != 0);
}

inline uint8_t requiredRunForElement(Element e, const RuleConfig& rules) noexcept {
    if (!isMatchableElement(e)) return 255;
    const uint8_t m = rules.elementRules[static_cast<uint8_t>(e)].minMatchCount;
    return (m >= 1 && m <= 6) ? m : 3;
}

inline bool canCellEliminate(const BoardState& board, uint8_t idx, uint8_t wave = 1) noexcept {
    const uint64_t b = bitOf(idx);
    if ((b & board.validMask) == 0) return false;
    if ((b & (board.stormMask | board.shatteredMask | board.sealedStoneMask)) != 0) return false;
    if (wave > 1 && (b & board.barrierMask) != 0) return false;
    const Cell& c = board.cells[idx];
    if (!c.isMatchable()) return false;
    if (c.isUnmatchableHazard()) return false;
    return true;
}

inline uint64_t orthogonalNeighbors(uint64_t mask, uint8_t cols, uint8_t rows) noexcept {
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

void analyzeGroupGeometry(MatchGroup& g, const BoardState& board) noexcept {
    const uint8_t cols = board.cols;
    const uint8_t rows = board.rows;
    const uint64_t mask = g.cellMask;

    uint8_t minR = rows, maxR = 0, minC = cols, maxC = 0;
    std::array<uint8_t, kMaxBoardDim> rowCounts{};
    std::array<uint8_t, kMaxBoardDim> colCounts{};

    for (uint8_t r = 0; r < rows; ++r) {
        for (uint8_t c = 0; c < cols; ++c) {
            const uint8_t idx = cellIndex(r, c, cols);
            if ((mask & bitOf(idx)) != 0) {
                minR = std::min(minR, r);
                maxR = std::max(maxR, r);
                minC = std::min(minC, c);
                maxC = std::max(maxC, c);
                rowCounts[r]++;
                colCounts[c]++;
            }
        }
    }

    // Full rows & columns (counting non-void tiles)
    for (uint8_t r = 0; r < rows; ++r) {
        uint8_t activeInRow = 0;
        for (uint8_t c = 0; c < cols; ++c) {
            if (board.validMask & bitOf(cellIndex(r, c, cols))) activeInRow++;
        }
        if (activeInRow > 0 && rowCounts[r] == activeInRow) {
            g.fullRowMask |= static_cast<uint8_t>(1u << r);
        }
    }

    for (uint8_t c = 0; c < cols; ++c) {
        uint8_t activeInCol = 0;
        uint8_t curRun = 0;
        uint8_t bestRun = 0;
        for (uint8_t r = 0; r < rows; ++r) {
            const uint64_t b = bitOf(cellIndex(r, c, cols));
            if (board.validMask & b) activeInCol++;
            if (mask & b) {
                curRun++;
                if (curRun > bestRun) bestRun = curRun;
            } else {
                curRun = 0;
            }
        }
        g.colMaxRun[c] = bestRun;
        if (activeInCol > 0 && colCounts[c] == activeInCol) {
            g.fullColMask |= static_cast<uint8_t>(1u << c);
        }
    }

    // Cross detection: center (r,c) + 4 orthogonal neighbors all present
    for (uint8_t r = 1; r + 1 < rows; ++r) {
        for (uint8_t c = 1; c + 1 < cols; ++c) {
            const uint64_t crossMask =
                bitOf(cellIndex(r, c, cols)) |
                bitOf(cellIndex(static_cast<uint8_t>(r - 1), c, cols)) |
                bitOf(cellIndex(static_cast<uint8_t>(r + 1), c, cols)) |
                bitOf(cellIndex(r, static_cast<uint8_t>(c - 1), cols)) |
                bitOf(cellIndex(r, static_cast<uint8_t>(c + 1), cols));
            if ((mask & crossMask) == crossMask) {
                g.containsCross = true;
                if (g.orbCount == 5) {
                    g.isExactCross5 = true;
                }
            }
        }
    }

    // 2x2 Square (田字) detection
    for (uint8_t r = 0; r + 1 < rows; ++r) {
        for (uint8_t c = 0; c + 1 < cols; ++c) {
            const uint64_t sqMask =
                bitOf(cellIndex(r, c, cols)) |
                bitOf(cellIndex(r, static_cast<uint8_t>(c + 1), cols)) |
                bitOf(cellIndex(static_cast<uint8_t>(r + 1), c, cols)) |
                bitOf(cellIndex(static_cast<uint8_t>(r + 1), static_cast<uint8_t>(c + 1), cols));
            if ((mask & sqMask) == sqMask) {
                g.isSquare2x2 = true;
            }
        }
    }

    // L-shape & T-shape detection (standard 3x3 bounding box, 5 orbs, or generalized L/T)
    const uint8_t hSpan = static_cast<uint8_t>(maxR - minR + 1);
    const uint8_t wSpan = static_cast<uint8_t>(maxC - minC + 1);
    if (hSpan >= 3 && wSpan >= 3) {
        // Check L-shape: one full boundary row and one full boundary column intersecting at a corner
        bool hasFullTop = (rowCounts[minR] == wSpan);
        bool hasFullBot = (rowCounts[maxR] == wSpan);
        bool hasFullLeft = (colCounts[minC] == hSpan);
        bool hasFullRight = (colCounts[maxC] == hSpan);

        const uint8_t expectedL = static_cast<uint8_t>(hSpan + wSpan - 1);
        if (g.orbCount == expectedL && (hasFullTop || hasFullBot) && (hasFullLeft || hasFullRight)) {
            g.isLShape = true;
        }

        // Check T-shape: full bar on one boundary edge + single perpendicular stem in the interior
        if (!g.isLShape && g.orbCount == expectedL) {
            if (hasFullTop || hasFullBot) {
                for (uint8_t c = static_cast<uint8_t>(minC + 1); c < maxC; ++c) {
                    if (colCounts[c] == hSpan) {
                        g.isTShape = true;
                        break;
                    }
                }
            }
            if (!g.isTShape && (hasFullLeft || hasFullRight)) {
                for (uint8_t r = static_cast<uint8_t>(minR + 1); r < maxR; ++r) {
                    if (rowCounts[r] == wSpan) {
                        g.isTShape = true;
                        break;
                    }
                }
            }
        }
    }
}

uint16_t countSkyfallRiskPairs(const BoardState& board) noexcept {
    uint16_t pairs = 0;
    const uint8_t cols = board.cols;
    const uint8_t rows = board.rows;

    for (uint8_t r = 0; r < rows; ++r) {
        for (uint8_t c = 0; c < cols; ++c) {
            const uint8_t idx = cellIndex(r, c, cols);
            if (!canCellEliminate(board, idx)) continue;
            const Element e = board.cells[idx].element;
            if (c + 1 < cols) {
                const uint8_t rIdx = cellIndex(r, static_cast<uint8_t>(c + 1), cols);
                if (canCellEliminate(board, rIdx) && board.cells[rIdx].element == e) {
                    pairs++;
                }
            }
            if (r + 1 < rows) {
                const uint8_t dIdx = cellIndex(static_cast<uint8_t>(r + 1), c, cols);
                if (canCellEliminate(board, dIdx) && board.cells[dIdx].element == e) {
                    pairs++;
                }
            }
        }
    }
    return pairs;
}

} // namespace

SimulationResult simulateBoard(const BoardState& initialBoard, const RuleConfig& rules) {
    SimulationResult res;
    BoardState cur = initialBoard;
    const uint8_t cols = cur.cols;
    const uint8_t rows = cur.rows;
    const uint8_t totalCells = cur.cellCount();

    uint64_t initialElectrifiedMask = 0;
    uint64_t initialBloodDrainMask = 0;
    uint64_t initialBombMask = 0;
    uint64_t initialFrozenMask = 0;
    uint64_t initialFoamMask = 0;
    uint64_t initialLockedHurtMask = 0;
    uint64_t initialLockedAtkDownMask = 0;
    uint64_t initialLightPrisonMask = 0;
    uint64_t initialPriorityMask = 0;
    uint64_t petrifiedOrIceMask = 0;

    for (uint8_t i = 0; i < totalCells; ++i) {
        const uint64_t b = bitOf(i);
        const Cell& c = cur.cells[i];
        if (canCellEliminate(cur, i, 1)) {
            res.matchableCellsAtStart++;
            res.initialElementCounts[static_cast<uint8_t>(c.element)]++;
        }
        if (c.hazards & HazardBits::Electrified)   initialElectrifiedMask |= b;
        if (c.hazards & HazardBits::BloodDrain)    initialBloodDrainMask |= b;
        if (c.hazards & HazardBits::Bomb)          initialBombMask |= b;
        if (c.hazards & HazardBits::Frozen)        initialFrozenMask |= b;
        if (c.hazards & HazardBits::Foam)          initialFoamMask |= b;
        if (c.hazards & HazardBits::LockedHurt)    initialLockedHurtMask |= b;
        if (c.hazards & HazardBits::LockedAtkDown) initialLockedAtkDownMask |= b;
        if (c.hazards & HazardBits::LightPrison)   initialLightPrisonMask |= b;
        if (c.hazards & HazardBits::Priority)      initialPriorityMask |= b;
        if (c.hazards & (HazardBits::Petrified | HazardBits::IceSealed)) petrifiedOrIceMask |= b;
    }

    uint64_t clearedElectrified = 0;
    uint64_t clearedBloodFirst = 0;
    uint64_t clearedBombFirst = 0;
    uint64_t clearedFrozen = 0;
    uint64_t clearedFoam = 0;
    uint64_t clearedLockedHurtFirst = 0;
    uint64_t clearedLockedAtkDown = 0;
    uint64_t clearedLightPrison = 0;
    uint64_t clearedPriority = 0;
    uint64_t brokenReserve = 0;

    // Track original cell index for hazard clearance across gravity drops
    std::array<uint8_t, kMaxCells> origSlot{};
    for (uint8_t i = 0; i < totalCells; ++i) origSlot[i] = i;

    uint8_t wave = 0;
    while (wave < 20) {
        const uint8_t nextWave = static_cast<uint8_t>(wave + 1);
        uint64_t matchedMask = 0;

        // 1. Check 1-match elements first
        for (uint8_t i = 0; i < totalCells; ++i) {
            if (!canCellEliminate(cur, i, nextWave)) continue;
            if (requiredRunForElement(cur.cells[i].element, rules) == 1) {
                matchedMask |= bitOf(i);
            }
        }

        // Helper lambda to scan a straight line for runs
        auto scanLine = [&](int startR, int startC, int dr, int dc) {
            int r = startR;
            int c = startC;
            while (cur.inBounds(r, c)) {
                const uint8_t idx = cellIndex(static_cast<uint8_t>(r), static_cast<uint8_t>(c), cols);
                if (!canCellEliminate(cur, idx, nextWave)) {
                    r += dr;
                    c += dc;
                    continue;
                }
                const Element baseEl = cur.cells[idx].element;
                const uint8_t req = requiredRunForElement(baseEl, rules);

                // Advance while compatible with baseEl
                int nr = r + dr;
                int nc = c + dc;
                uint8_t runLen = 1;
                uint64_t runMask = bitOf(idx);
                uint8_t maxReqInRun = req;

                while (cur.inBounds(nr, nc)) {
                    const uint8_t nIdx = cellIndex(static_cast<uint8_t>(nr), static_cast<uint8_t>(nc), cols);
                    if (!canCellEliminate(cur, nIdx, nextWave)) break;
                    const Element nextEl = cur.cells[nIdx].element;
                    if (!areElementsCompatible(baseEl, nextEl, rules)) break;
                    runMask |= bitOf(nIdx);
                    runLen++;
                    maxReqInRun = std::min(maxReqInRun, requiredRunForElement(nextEl, rules));
                    nr += dr;
                    nc += dc;
                }

                if (runLen >= maxReqInRun) {
                    matchedMask |= runMask;
                }

                r = nr;
                c = nc;
            }
        };

        // Horizontal runs
        for (uint8_t r = 0; r < rows; ++r) scanLine(r, 0, 0, 1);
        // Vertical runs
        for (uint8_t c = 0; c < cols; ++c) scanLine(0, c, 1, 0);
        // Optional diagonal runs (斜消)
        if (rules.allowDiagonalMatch) {
            for (uint8_t c = 0; c < cols; ++c) {
                scanLine(0, c, 1, 1);
                scanLine(0, c, 1, -1);
            }
            for (uint8_t r = 1; r < rows; ++r) {
                scanLine(r, 0, 1, 1);
                scanLine(r, static_cast<uint8_t>(cols - 1), 1, -1);
            }
        }

        if (matchedMask == 0) {
            break;
        }

        ++wave;
        res.cascadeWaves = wave;

        // 2. Flood-fill connected matched cells into MatchGroups
        uint64_t visited = 0;
        const uint8_t dirLimit = rules.allowDiagonalMatch ? 8 : 4;

        for (uint8_t start = 0; start < totalCells; ++start) {
            const uint64_t sb = bitOf(start);
            if ((matchedMask & sb) == 0 || (visited & sb) != 0) continue;

            MatchGroup g;
            g.wave = wave;
            g.element = cur.cells[start].element;
            uint8_t groupEnchMask = 0;
            uint8_t groupRaceMask = 0;

            std::array<uint8_t, kMaxCells> queueBuf{};
            uint8_t qHead = 0, qTail = 0;
            queueBuf[qTail++] = start;
            visited |= sb;

            while (qHead < qTail) {
                const uint8_t u = queueBuf[qHead++];
                const uint64_t ub = bitOf(u);
                g.cellMask |= ub;
                g.orbCount++;

                const Cell& uc = cur.cells[u];
                const uint8_t uBit = elementBit(uc.element);
                g.groupElementsMask |= uBit;
                if (uc.variant == Variant::Enchanted) {
                    g.enchantedCount++;
                    groupEnchMask |= uBit;
                }
                if (uc.variant == Variant::Race) {
                    g.raceCount++;
                    groupRaceMask |= uBit;
                }

                const int ur = u / cols;
                const int ucCol = u % cols;
                for (uint8_t d = 0; d < dirLimit; ++d) {
                    const int vr = ur + kDirDr[d];
                    const int vc = ucCol + kDirDc[d];
                    if (!cur.inBounds(vr, vc)) continue;
                    const uint8_t v = cellIndex(static_cast<uint8_t>(vr), static_cast<uint8_t>(vc), cols);
                    const uint64_t vb = bitOf(v);
                    if ((matchedMask & vb) == 0 || (visited & vb) != 0) continue;
                    if (!areElementsCompatible(g.element, cur.cells[v].element, rules)) continue;
                    visited |= vb;
                    queueBuf[qTail++] = v;
                }
            }

            analyzeGroupGeometry(g, cur);

            // Track petrified / ice-sealed / foam 4-neighbor orthogonal hits per combo
            if (petrifiedOrIceMask != 0 || initialFoamMask != 0) {
                const uint64_t adj = orthogonalNeighbors(g.cellMask, cols, rows);
                if (petrifiedOrIceMask != 0) {
                    res.petrifiedAdjacentHits += static_cast<uint16_t>(std::popcount(adj & petrifiedOrIceMask));
                }
                if (initialFoamMask != 0) {
                    const uint64_t popped = adj & initialFoamMask;
                    if (popped != 0) {
                        clearedFoam |= popped;
                        res.foamAdjacentHits += static_cast<uint16_t>(std::popcount(popped));
                    }
                }
            }

            const uint8_t elIdx = static_cast<uint8_t>(g.element);
            const uint8_t elMask = g.groupElementsMask;

            res.totalCombo++;
            res.totalOrbsEliminated += g.orbCount;
            res.totalElementsMask |= elMask;
            res.totalElementCombos[elIdx]++;
            res.totalElementOrbs[elIdx] += g.orbCount;
            res.totalEnchantedElementsMask |= groupEnchMask;

            if (wave == 1) {
                res.firstWaveCombo++;
                res.firstWaveOrbsTotal += g.orbCount;
                res.firstWaveElementsMask |= elMask;
                res.firstWaveElementCombos[elIdx]++;
                res.firstWaveElementOrbs[elIdx] += g.orbCount;
                res.firstWaveEnchantedElementsMask |= groupEnchMask;
                res.firstWaveRaceElementsMask |= groupRaceMask;
                res.totalRaceOrbsFirstWave += g.raceCount;
                res.firstWaveClearedMask |= g.cellMask;
            } else if (wave == 2) {
                res.secondWaveCombo++;
                res.secondWaveElementsMask |= elMask;
            }
            res.allWavesClearedMask |= g.cellMask;

            if (res.groupCount < kMaxMatchGroups) {
                res.groups[res.groupCount++] = g;
            }
        }

        // 3. Record cleared hazard orbs and clear matched cells
        for (uint8_t i = 0; i < totalCells; ++i) {
            if ((matchedMask & bitOf(i)) == 0) continue;
            const uint64_t origBit = bitOf(origSlot[i]);
            const uint32_t hz = cur.cells[i].hazards;
            if (hz & HazardBits::Electrified)   clearedElectrified |= origBit;
            if (hz & HazardBits::Frozen)        clearedFrozen |= origBit;
            if (hz & HazardBits::LockedAtkDown) clearedLockedAtkDown |= origBit;
            if (hz & HazardBits::LightPrison)   clearedLightPrison |= origBit;
            if (hz & HazardBits::Luminous)      res.luminousCleared++;
            if (hz & HazardBits::Priority)      clearedPriority |= origBit;
            if (hz & HazardBits::Reserve)       brokenReserve |= origBit;
            if (cur.cells[i].variant == Variant::Weakened) res.weakenedEliminated++;
            if (wave == 1) {
                if (hz & HazardBits::BloodDrain) clearedBloodFirst |= origBit;
                if (hz & HazardBits::Bomb)       clearedBombFirst |= origBit;
                if (hz & HazardBits::LockedHurt) clearedLockedHurtFirst |= origBit;
            }
            cur.cells[i] = Cell{Element::Empty, Variant::Normal, HazardBits::None};
        }

        if (wave == 1) {
            for (uint8_t i = 0; i < totalCells; ++i) {
                if (canCellEliminate(cur, i, 1)) {
                    res.remainingAfterFirstWave[static_cast<uint8_t>(cur.cells[i].element)]++;
                }
            }
        }

        // Apply gravity to both board and origSlot tracking
        for (uint8_t c = 0; c < cols; ++c) {
            std::array<Cell, kMaxBoardDim> movableCells{};
            std::array<uint8_t, kMaxBoardDim> movableOrig{};
            uint8_t mCount = 0;
            for (int r = static_cast<int>(rows) - 1; r >= 0; --r) {
                const uint8_t idx = cellIndex(static_cast<uint8_t>(r), c, cols);
                if ((bitOf(idx) & cur.validMask) == 0) continue;
                if (cur.cells[idx].isImmovable()) continue;
                if (cur.cells[idx].element != Element::Empty) {
                    movableCells[mCount] = cur.cells[idx];
                    movableOrig[mCount] = origSlot[idx];
                    mCount++;
                }
            }
            uint8_t wIdx = 0;
            for (int r = static_cast<int>(rows) - 1; r >= 0; --r) {
                const uint8_t idx = cellIndex(static_cast<uint8_t>(r), c, cols);
                if ((bitOf(idx) & cur.validMask) == 0) continue;
                if (cur.cells[idx].isImmovable()) continue;
                if (wIdx < mCount) {
                    cur.cells[idx] = movableCells[wIdx];
                    origSlot[idx] = movableOrig[wIdx];
                    wIdx++;
                } else {
                    cur.cells[idx] = Cell{Element::Empty, Variant::Normal, HazardBits::None};
                    origSlot[idx] = idx;
                }
            }
        }

        if (wave == 1 && rules.avoidSkyfallRisk) {
            res.skyfallRiskPairs = countSkyfallRiskPairs(cur);
        }
    }

    if (wave == 0) {
        res.remainingAfterFirstWave = res.initialElementCounts;
        if (rules.avoidSkyfallRisk) {
            res.skyfallRiskPairs = countSkyfallRiskPairs(cur);
        }
    }

    res.electrifiedRemaining = static_cast<uint8_t>(std::popcount(initialElectrifiedMask & ~clearedElectrified));
    res.bloodDrainRemainingFirstWave = static_cast<uint8_t>(std::popcount(initialBloodDrainMask & ~clearedBloodFirst));
    res.bombRemainingFirstWave = static_cast<uint8_t>(std::popcount(initialBombMask & ~clearedBombFirst));
    res.frozenRemaining = static_cast<uint8_t>(std::popcount(initialFrozenMask & ~clearedFrozen));
    res.foamRemaining = static_cast<uint8_t>(std::popcount(initialFoamMask & ~clearedFoam));
    res.lockedHurtRemainingFirstWave = static_cast<uint8_t>(std::popcount(initialLockedHurtMask & ~clearedLockedHurtFirst));
    res.lockedAtkDownRemaining = static_cast<uint8_t>(std::popcount(initialLockedAtkDownMask & ~clearedLockedAtkDown));
    res.lightPrisonRemaining = static_cast<uint8_t>(std::popcount(initialLightPrisonMask & ~clearedLightPrison));
    res.priorityOrbsRemaining = static_cast<uint8_t>(std::popcount(initialPriorityMask & ~clearedPriority));
    res.reserveOrbsBroken = static_cast<uint8_t>(std::popcount(brokenReserve));
    res.settledBoard = cur;

    return res;
}

} // namespace autodora
