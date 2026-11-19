#include "autodora/solver.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <thread>
#include <unordered_map>
#include <unordered_set>

namespace autodora {

namespace {

constexpr uint8_t kMaxMoveSteps = 120;
constexpr uint8_t kMaxTrailWindow = 16;

constexpr uint64_t splitmix64(uint64_t x) noexcept {
    x += 0x9e3779b97f4a7c15ULL;
    x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9ULL;
    x = (x ^ (x >> 27)) * 0x94d049bb133111ebULL;
    return x ^ (x >> 31);
}

struct ZobristTable {
    std::array<std::array<uint64_t, 64>, kMaxCells> cellKey{};
    std::array<uint64_t, kMaxCells> cursorKey{};

    ZobristTable() {
        uint64_t seed = 0xA070D04A20260001ULL;
        for (uint8_t i = 0; i < kMaxCells; ++i) {
            cursorKey[i] = splitmix64(seed++);
            for (uint8_t s = 0; s < 64; ++s) {
                cellKey[i][s] = splitmix64(seed++);
            }
        }
    }

    static inline uint8_t cellSig(const Cell& c) noexcept {
        const uint8_t el = static_cast<uint8_t>(c.element) & 0x0Fu;
        const uint8_t var = static_cast<uint8_t>(c.variant) & 0x03u;
        const uint8_t hazBit = (c.hazards != 0) ? 1u : 0u;
        return static_cast<uint8_t>(((el << 2) | var) ^ (hazBit << 5)) & 0x3Fu;
    }

    uint64_t hashBoardOnly(const BoardState& b) const noexcept {
        uint64_t h = 0;
        const uint8_t n = b.cellCount();
        for (uint8_t i = 0; i < n; ++i) {
            h ^= cellKey[i][cellSig(b.cells[i])];
        }
        return h;
    }
};

const ZobristTable& zobrist() {
    static const ZobristTable tbl;
    return tbl;
}

// ZERO heap allocations! Entire search node is flat stack/contiguous POD.
struct SearchNode {
    BoardState board;
    uint64_t boardHash = 0; // Board configuration hash (without cursor)
    uint64_t stateHash = 0; // Board + cursor hash
    uint8_t startIdx = 0;
    uint8_t curIdx = 0;
    Direction lastDir = Direction::None;

    uint8_t stepCount = 0;
    uint8_t diagonalSteps = 0;
    uint8_t burningHits = 0;
    uint8_t trailHits = 0;
    uint8_t weatheredCleared = 0;
    uint8_t checkpointsReached = 0;
    bool usedCharacterOrb = false;
    bool endedOnSticky = false;

    uint64_t visitedTrailMask = 0;
    std::array<uint8_t, kMaxMoveSteps> moves{};
    std::array<uint8_t, kMaxTrailWindow> trailRing{};
    uint8_t trailCount = 0;

    double beamScore = -1e18;
    bool allHardMet = false;

    void pushTrail(uint8_t idx) noexcept {
        trailRing[trailCount % kMaxTrailWindow] = idx;
        trailCount++;
    }

    bool inRecentTrail(uint8_t idx, uint8_t windowLen) const noexcept {
        const uint8_t check = std::min<uint8_t>(trailCount, std::min<uint8_t>(windowLen, kMaxTrailWindow));
        for (uint8_t k = 0; k < check; ++k) {
            const uint8_t pos = static_cast<uint8_t>((trailCount - 1 - k) % kMaxTrailWindow);
            if (trailRing[pos] == idx) return true;
        }
        return false;
    }
};

inline bool canEnterCell(const BoardState& board, uint8_t idx, const RuleConfig& rules) noexcept {
    const uint64_t b = bitOf(idx);
    if ((b & board.validMask) == 0) return false;
    if ((b & (board.stormMask | board.blackHoleMask | board.noPassMask)) != 0) return false;
    const Cell& c = board.cells[idx];
    if (c.isImmovable()) return false;
    if ((c.hazards & HazardBits::Electrified) != 0) return false;
    if ((c.hazards & HazardBits::Weathered) != 0 && !rules.allowHitWeatheredToClear) return false;
    return true;
}

inline bool canStartAtCell(const BoardState& board, uint8_t idx, const RuleConfig& rules) noexcept {
    if (!canEnterCell(board, idx, rules)) return false;
    if ((board.stickyTileMask & bitOf(idx)) != 0) return false;
    if ((board.cells[idx].hazards & HazardBits::StickyOrb) != 0) return false;
    if (board.startMask != 0 && (board.startMask & bitOf(idx)) == 0) return false;
    if (rules.startPreset == StartPointPreset::BottomLeft) {
        return idx == board.index(static_cast<uint8_t>(board.rows - 1), 0);
    }
    if (rules.startPreset == StartPointPreset::BottomRight) {
        return idx == board.index(static_cast<uint8_t>(board.rows - 1), static_cast<uint8_t>(board.cols - 1));
    }
    return true;
}

inline PathMetrics makeMetrics(const SearchNode& node) noexcept {
    PathMetrics m;
    m.stepCount = node.stepCount;
    m.diagonalSteps = node.diagonalSteps;
    m.startIdx = node.startIdx;
    m.endIdx = node.curIdx;
    m.burningTileHits = node.burningHits;
    m.trailBurningHits = node.trailHits;
    m.weatheredClearedByHit = node.weatheredCleared;
    m.checkpointsReached = node.checkpointsReached;
    m.usedCharacterOrb = node.usedCharacterOrb;
    m.endedOnSticky = node.endedOnSticky;
    return m;
}

SolutionPath buildSolutionPath(const SearchNode& node, const RuleConfig& rules, const std::string& tag) {
    SolutionPath sp;
    const uint8_t cols = node.board.cols;
    sp.start = CellCoord{static_cast<uint8_t>(node.startIdx / cols), static_cast<uint8_t>(node.startIdx % cols)};
    sp.usedCharacterOrb = node.usedCharacterOrb;
    sp.characterOrbCol = rules.characterOrbCol;
    sp.moves.reserve(node.stepCount);
    for (uint8_t i = 0; i < node.stepCount; ++i) {
        sp.moves.push_back(static_cast<Direction>(node.moves[i]));
    }
    sp.metrics = makeMetrics(node);
    sp.simulation = simulateBoard(node.board, rules);
    sp.evaluation = evaluateCandidate(node.board, sp.simulation, sp.metrics, rules);
    sp.tag = tag;

    sp.waypoints.reserve(node.stepCount + 1);
    CellCoord cur = sp.start;
    sp.waypoints.push_back(cur);
    for (Direction d : sp.moves) {
        cur.row = static_cast<uint8_t>(static_cast<int>(cur.row) + kDirDr[static_cast<uint8_t>(d)]);
        cur.col = static_cast<uint8_t>(static_cast<int>(cur.col) + kDirDc[static_cast<uint8_t>(d)]);
        sp.waypoints.push_back(cur);
    }
    return sp;
}

bool isDistinctSolution(const SolutionPath& a, const SolutionPath& b) {
    if (a.start.row != b.start.row || a.start.col != b.start.col) return true;
    if (a.moves.size() != b.moves.size()) return true;
    if (a.simulation.totalCombo != b.simulation.totalCombo) return true;
    if (a.simulation.firstWaveOrbsTotal != b.simulation.firstWaveOrbsTotal) return true;
    size_t diffs = 0;
    for (size_t i = 0; i < a.moves.size(); ++i) {
        if (a.moves[i] != b.moves[i]) diffs++;
    }
    return diffs >= 3;
}

} // namespace

SolveOutput PuzzleSolver::solve(
    const BoardState& initialBoardInput,
    const RuleConfig& rules,
    const std::atomic<bool>* cancelFlag
) const {
    const auto tStart = std::chrono::steady_clock::now();
    SolveOutput out;

    BoardState baseBoard = initialBoardInput;
    if (!baseBoard.isValidGeometry()) {
        return out;
    }
    baseBoard.rebuildMasks();

    const uint8_t cols = baseBoard.cols;
    const uint8_t totalCells = baseBoard.cellCount();
    const uint8_t dirCount = rules.allowDiagonalMove ? 8 : 4;
    const uint8_t maxSteps = std::min<uint8_t>(rules.maxSteps, kMaxMoveSteps);
    // Scale effective beam width safely while staying well inside maxTimeMs budget
    const uint32_t beamWidth = std::clamp<uint32_t>(rules.beamWidth * 2, 32, 2400);
    const auto& zt = zobrist();

    auto isCancelled = [&]() -> bool {
        return cancelFlag != nullptr && cancelFlag->load(std::memory_order_relaxed);
    };
    auto isTimedOut = [&]() -> bool {
        if (rules.maxTimeMs == 0) return false;
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - tStart).count();
        return elapsed >= static_cast<long long>(rules.maxTimeMs);
    };

    // Build initial frontier (Step 0)
    std::vector<SearchNode> frontier;
    if (rules.useCharacterOrb) {
        const uint8_t cCol = std::min<uint8_t>(rules.characterOrbCol, static_cast<uint8_t>(cols - 1));
        const uint8_t idx = baseBoard.index(0, cCol);
        if ((baseBoard.validMask & bitOf(idx)) != 0 &&
            (baseBoard.stormMask & bitOf(idx)) == 0 &&
            (baseBoard.blackHoleMask & bitOf(idx)) == 0 &&
            (baseBoard.noPassMask & bitOf(idx)) == 0) {
            SearchNode node;
            node.board = baseBoard;
            node.board.cells[idx] = Cell{rules.characterOrbElement, Variant::Normal, HazardBits::None};
            node.startIdx = idx;
            node.curIdx = idx;
            node.usedCharacterOrb = true;
            node.stepCount = 0;
            if (node.board.checkpointCount > 0 && node.board.orderedCheckpoints[0] == idx) {
                node.checkpointsReached = 1;
            }
            if (rules.trailBurningEnabled) {
                node.visitedTrailMask = bitOf(idx);
                if (rules.trailLength > 0) node.pushTrail(idx);
            }
            node.boardHash = zt.hashBoardOnly(node.board);
            node.stateHash = node.boardHash ^ zt.cursorKey[idx];
            frontier.push_back(node);
        }
    } else {
        for (uint8_t idx = 0; idx < totalCells; ++idx) {
            if (!canStartAtCell(baseBoard, idx, rules)) continue;
            SearchNode node;
            node.board = baseBoard;
            node.startIdx = idx;
            node.curIdx = idx;
            node.stepCount = 0;
            if ((baseBoard.burningTileMask & bitOf(idx)) != 0 ||
                (baseBoard.cells[idx].hazards & HazardBits::BurningOrb) != 0) {
                node.burningHits = 1;
                if (node.burningHits > rules.maxBurningTileHurts) continue;
            }
            if (node.board.checkpointCount > 0 && node.board.orderedCheckpoints[0] == idx) {
                node.checkpointsReached = 1;
            }
            if (rules.trailBurningEnabled) {
                node.visitedTrailMask = bitOf(idx);
                if (rules.trailLength > 0) node.pushTrail(idx);
            }
            node.boardHash = zt.hashBoardOnly(node.board);
            node.stateHash = node.boardHash ^ zt.cursorKey[idx];
            frontier.push_back(node);
        }
    }

    if (frontier.empty()) {
        out.elapsedMs = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - tStart).count();
        return out;
    }

    std::vector<SearchNode> bestPool;
    const size_t poolLimit = std::max<size_t>(rules.topK * 8, 32);
    auto recordCandidate = [&](const SearchNode& node) {
        if (bestPool.size() < poolLimit) {
            bestPool.push_back(node);
            std::sort(bestPool.begin(), bestPool.end(), [](const SearchNode& a, const SearchNode& b) {
                return a.beamScore > b.beamScore;
            });
        } else if (node.beamScore > bestPool.back().beamScore) {
            bestPool.back() = node;
            std::sort(bestPool.begin(), bestPool.end(), [](const SearchNode& a, const SearchNode& b) {
                return a.beamScore > b.beamScore;
            });
        }
    };

    bool foundGoalEarly = false;
    for (SearchNode& node : frontier) {
        const SimulationResult sim = simulateBoard(node.board, rules);
        const EvalResult ev = evaluateCandidate(node.board, sim, makeMetrics(node), rules);
        node.beamScore = ev.totalScore;
        node.allHardMet = ev.allHardConstraintsMet;
        out.statesEvaluated++;
        recordCandidate(node);
        if (rules.earlyStopOnGoal && ev.allHardConstraintsMet &&
            (rules.exactSteps < 0 || rules.exactSteps == 0)) {
            foundGoalEarly = true;
        }
    }

    uint32_t hwThreads = std::thread::hardware_concurrency();
    if (hwThreads == 0) hwThreads = 4;
    const uint32_t threadCount = (rules.numThreads > 0) ? rules.numThreads : std::min<uint32_t>(hwThreads, 6);

    std::unordered_set<uint64_t> globalVisited;
    globalVisited.reserve(static_cast<size_t>(beamWidth) * std::min<uint8_t>(maxSteps, 36));
    for (const auto& n : frontier) {
        globalVisited.insert(n.stateHash);
    }

    for (uint8_t depth = 1; depth <= maxSteps && !frontier.empty() && !foundGoalEarly; ++depth) {
        if (isCancelled()) {
            out.interrupted = true;
            break;
        }
        if (isTimedOut()) {
            out.timedOut = true;
            break;
        }

        const size_t fSize = frontier.size();
        const uint32_t workers = (fSize >= 16 && threadCount > 1)
            ? std::min<uint32_t>(threadCount, static_cast<uint32_t>(fSize / 8))
            : 1;

        std::vector<std::vector<SearchNode>> threadCandidates(workers);
        std::vector<uint64_t> threadExpanded(workers, 0);
        std::vector<uint64_t> threadEvaluated(workers, 0);
        std::atomic<bool> earlyGoalFlag{false};

        auto workerFn = [&](uint32_t tid) {
            const size_t beginIdx = (fSize * tid) / workers;
            const size_t endIdx = (fSize * (tid + 1)) / workers;
            auto& localOut = threadCandidates[tid];
            localOut.reserve((endIdx - beginIdx) * 5);

            for (size_t fi = beginIdx; fi < endIdx; ++fi) {
                if (isCancelled() || earlyGoalFlag.load(std::memory_order_relaxed)) break;
                const SearchNode& parent = frontier[fi];
                if (parent.endedOnSticky) continue;

                const int pr = parent.curIdx / cols;
                const int pc = parent.curIdx % cols;
                const Direction forbiddenRev = oppositeDirection(parent.lastDir);
                threadExpanded[tid]++;

                for (uint8_t d = 0; d < dirCount; ++d) {
                    const Direction dir = static_cast<Direction>(d);
                    if (dir == forbiddenRev) continue;

                    const int nr = pr + kDirDr[d];
                    const int nc = pc + kDirDc[d];
                    if (!parent.board.inBounds(nr, nc)) continue;

                    const uint8_t nextIdx = cellIndex(static_cast<uint8_t>(nr), static_cast<uint8_t>(nc), cols);
                    if (!canEnterCell(parent.board, nextIdx, rules)) continue;

                    uint8_t nextBurn = parent.burningHits;
                    if ((parent.board.burningTileMask & bitOf(nextIdx)) != 0 ||
                        (parent.board.cells[nextIdx].hazards & HazardBits::BurningOrb) != 0) {
                        nextBurn++;
                        if (nextBurn > rules.maxBurningTileHurts) continue;
                    }

                    uint8_t nextTrailHits = parent.trailHits;
                    if (rules.trailBurningEnabled) {
                        bool hitTrail = false;
                        if (rules.trailLength == 0) {
                            hitTrail = (parent.visitedTrailMask & bitOf(nextIdx)) != 0;
                        } else {
                            hitTrail = parent.inRecentTrail(nextIdx, rules.trailLength);
                        }
                        if (hitTrail) {
                            nextTrailHits++;
                            if (nextTrailHits > rules.maxTrailHurts) continue;
                        }
                    }

                    SearchNode child = parent; // Flat stack copy, ZERO heap allocs!
                    const uint8_t oldCurSig = ZobristTable::cellSig(child.board.cells[parent.curIdx]);
                    const uint8_t oldNextSig = ZobristTable::cellSig(child.board.cells[nextIdx]);

                    if (rules.allowHitWeatheredToClear &&
                        (child.board.cells[nextIdx].hazards & HazardBits::Weathered) != 0) {
                        child.board.cells[nextIdx].hazards &= static_cast<uint16_t>(~HazardBits::Weathered);
                        child.weatheredCleared++;
                    }

                    std::swap(child.board.cells[parent.curIdx], child.board.cells[nextIdx]);

                    const uint8_t newCurSig = ZobristTable::cellSig(child.board.cells[parent.curIdx]);
                    const uint8_t newNextSig = ZobristTable::cellSig(child.board.cells[nextIdx]);
                    child.boardHash = parent.boardHash
                        ^ zt.cellKey[parent.curIdx][oldCurSig] ^ zt.cellKey[parent.curIdx][newCurSig]
                        ^ zt.cellKey[nextIdx][oldNextSig] ^ zt.cellKey[nextIdx][newNextSig];
                    child.stateHash = child.boardHash ^ zt.cursorKey[nextIdx];

                    child.curIdx = nextIdx;
                    child.lastDir = dir;
                    child.moves[parent.stepCount] = d;
                    child.stepCount = static_cast<uint8_t>(parent.stepCount + 1);
                    if (isDiagonal(dir)) child.diagonalSteps++;
                    child.burningHits = nextBurn;
                    child.trailHits = nextTrailHits;
                    child.endedOnSticky = ((parent.board.stickyTileMask & bitOf(nextIdx)) != 0) ||
                                          ((parent.board.cells[nextIdx].hazards & HazardBits::StickyOrb) != 0);

                    if (child.checkpointsReached < child.board.checkpointCount &&
                        child.board.orderedCheckpoints[child.checkpointsReached] == nextIdx) {
                        child.checkpointsReached++;
                    }

                    if (rules.trailBurningEnabled) {
                        child.visitedTrailMask |= bitOf(nextIdx);
                        if (rules.trailLength > 0) child.pushTrail(nextIdx);
                    }

                    const SimulationResult sim = simulateBoard(child.board, rules);
                    const EvalResult ev = evaluateCandidate(child.board, sim, makeMetrics(child), rules);
                    child.beamScore = ev.totalScore;
                    child.allHardMet = ev.allHardConstraintsMet;
                    threadEvaluated[tid]++;

                    if (rules.earlyStopOnGoal && ev.allHardConstraintsMet) {
                        earlyGoalFlag.store(true, std::memory_order_relaxed);
                    }

                    localOut.push_back(child);
                }
            }
        };

        if (workers == 1) {
            workerFn(0);
        } else {
            std::vector<std::thread> pool;
            pool.reserve(workers);
            for (uint32_t t = 0; t < workers; ++t) {
                pool.emplace_back(workerFn, t);
            }
            for (auto& th : pool) th.join();
        }

        size_t totalCand = 0;
        for (uint32_t t = 0; t < workers; ++t) {
            out.statesExpanded += threadExpanded[t];
            out.statesEvaluated += threadEvaluated[t];
            totalCand += threadCandidates[t].size();
        }
        if (totalCand == 0) break;

        // Index sort instead of copying full SearchNode structs during std::sort!
        struct CandRef {
            uint32_t tid;
            uint32_t idx;
            double score;
        };
        std::vector<CandRef> refs;
        refs.reserve(totalCand);
        for (uint32_t t = 0; t < workers; ++t) {
            const auto& v = threadCandidates[t];
            for (uint32_t i = 0; i < v.size(); ++i) {
                refs.push_back(CandRef{t, i, v[i].beamScore});
            }
        }
        std::sort(refs.begin(), refs.end(), [](const CandRef& a, const CandRef& b) {
            return a.score > b.score;
        });

        // Deduplicate by stateHash + cap boardHash copies + start-index diversity in early depths!
        std::vector<SearchNode> nextFrontier;
        std::vector<CandRef> overflowRefs;
        nextFrontier.reserve(beamWidth);
        overflowRefs.reserve(beamWidth);

        std::unordered_map<uint64_t, uint8_t> boardHashCount;
        boardHashCount.reserve(beamWidth * 2);
        std::array<uint32_t, kMaxCells> startCounts{};
        std::array<uint32_t, kMaxCells> curCounts{};

        const uint32_t maxPerStart = (depth <= 8)
            ? std::max<uint32_t>(4, beamWidth / std::max<uint32_t>(8, totalCells / 2))
            : beamWidth;
        const uint32_t maxPerCur = std::max<uint32_t>(6, beamWidth / std::max<uint32_t>(6, totalCells / 3));

        for (const CandRef& cr : refs) {
            const SearchNode& node = threadCandidates[cr.tid][cr.idx];
            if (globalVisited.find(node.stateHash) != globalVisited.end()) {
                continue;
            }
            globalVisited.insert(node.stateHash);

            if (bestPool.size() < poolLimit || node.beamScore > bestPool.back().beamScore) {
                recordCandidate(node);
                if (rules.earlyStopOnGoal && node.allHardMet) {
                    foundGoalEarly = true;
                }
            }

            if (node.endedOnSticky) continue;

            // Avoid flooding beam with identical board configurations under different cursor positions
            uint8_t& bhc = boardHashCount[node.boardHash];
            if (bhc >= 2) continue;

            if (nextFrontier.size() < beamWidth &&
                startCounts[node.startIdx] < maxPerStart &&
                curCounts[node.curIdx] < maxPerCur) {
                bhc++;
                startCounts[node.startIdx]++;
                curCounts[node.curIdx]++;
                nextFrontier.push_back(node);
            } else if (overflowRefs.size() < beamWidth) {
                overflowRefs.push_back(cr);
            }
        }

        for (const CandRef& cr : overflowRefs) {
            if (nextFrontier.size() >= beamWidth) break;
            const SearchNode& node = threadCandidates[cr.tid][cr.idx];
            uint8_t& bhc = boardHashCount[node.boardHash];
            if (bhc >= 2) continue;
            bhc++;
            nextFrontier.push_back(node);
        }

        frontier = std::move(nextFrontier);
    }

    // Build diverse Top-K solutions
    std::vector<SolutionPath> fullCandidates;
    fullCandidates.reserve(bestPool.size());
    for (const auto& n : bestPool) {
        fullCandidates.push_back(buildSolutionPath(n, rules, ""));
    }

    std::sort(fullCandidates.begin(), fullCandidates.end(), [](const SolutionPath& a, const SolutionPath& b) {
        if (a.evaluation.allHardConstraintsMet != b.evaluation.allHardConstraintsMet) {
            return a.evaluation.allHardConstraintsMet > b.evaluation.allHardConstraintsMet;
        }
        return a.evaluation.totalScore > b.evaluation.totalScore;
    });

    const uint32_t targetK = std::max<uint32_t>(1, rules.topK);
    if (!fullCandidates.empty()) {
        fullCandidates[0].tag = fullCandidates[0].evaluation.allHardConstraintsMet ? "最佳解 (全盾达成)" : "当前最优解";
        out.solutions.push_back(fullCandidates[0]);

        if (targetK >= 2) {
            int shortestIdx = -1;
            for (size_t i = 1; i < fullCandidates.size(); ++i) {
                if (fullCandidates[i].evaluation.allHardConstraintsMet &&
                    fullCandidates[i].metrics.stepCount < out.solutions[0].metrics.stepCount) {
                    if (shortestIdx < 0 ||
                        fullCandidates[i].metrics.stepCount < fullCandidates[shortestIdx].metrics.stepCount ||
                        (fullCandidates[i].metrics.stepCount == fullCandidates[shortestIdx].metrics.stepCount &&
                         fullCandidates[i].evaluation.totalScore > fullCandidates[shortestIdx].evaluation.totalScore)) {
                        shortestIdx = static_cast<int>(i);
                    }
                }
            }
            if (shortestIdx >= 0 && isDistinctSolution(out.solutions[0], fullCandidates[shortestIdx])) {
                fullCandidates[shortestIdx].tag = "最短达标路径";
                out.solutions.push_back(fullCandidates[shortestIdx]);
            }
        }

        for (size_t i = 1; i < fullCandidates.size() && out.solutions.size() < targetK; ++i) {
            bool distinct = true;
            for (const auto& chosen : out.solutions) {
                if (!isDistinctSolution(chosen, fullCandidates[i])) {
                    distinct = false;
                    break;
                }
            }
            if (distinct) {
                fullCandidates[i].tag = "备选方案 #" + std::to_string(out.solutions.size() + 1);
                out.solutions.push_back(fullCandidates[i]);
            }
        }
    }

    out.elapsedMs = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - tStart).count();
    return out;
}

} // namespace autodora
