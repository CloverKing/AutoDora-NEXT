#include "autodora/solver.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <unordered_set>

namespace autodora {

namespace {

constexpr uint16_t kMaxMoveSteps = 300;
constexpr uint8_t kMaxTrailWindow = 16;

constexpr uint64_t splitmix64(uint64_t x) noexcept {
    x += 0x9e3779b97f4a7c15ULL;
    x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9ULL;
    x = (x ^ (x >> 27)) * 0x94d049bb133111ebULL;
    return x ^ (x >> 31);
}

struct ZobristTable {
    std::array<std::array<uint64_t, 1024>, kMaxCells> cellKey{};
    std::array<uint64_t, kMaxCells> cursorKey{};

    ZobristTable() {
        uint64_t seed = 0xA070D04A20260001ULL;
        for (uint8_t i = 0; i < kMaxCells; ++i) {
            cursorKey[i] = splitmix64(seed++);
            for (uint16_t s = 0; s < 1024; ++s) {
                cellKey[i][s] = splitmix64(seed++);
            }
        }
    }

    static inline uint16_t cellSig(const Cell& c) noexcept {
        const uint16_t el = static_cast<uint16_t>(c.element) & 0x0Fu;
        const uint16_t var = static_cast<uint16_t>(c.variant) & 0x03u;
        const uint16_t prio = ((c.hazards & HazardBits::Priority) != 0) ? 1u : 0u;
        const uint16_t resv = ((c.hazards & HazardBits::Reserve) != 0) ? 1u : 0u;
        const uint16_t slp  = ((c.hazards & HazardBits::Sleep) != 0) ? 1u : 0u;
        const uint16_t oth  = ((c.hazards & ~(HazardBits::Priority | HazardBits::Reserve | HazardBits::Sleep)) != 0) ? 1u : 0u;
        return static_cast<uint16_t>(
            el | (var << 4) | (prio << 6) | (resv << 7) | (slp << 8) | (oth << 9)
        ) & 0x3FFu;
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

    uint16_t stepCount = 0;
    uint16_t diagonalSteps = 0;
    uint8_t burningHits = 0;
    uint8_t trailHits = 0;
    uint8_t miasmaHits = 0;
    uint8_t witheredVineHits = 0;
    uint8_t weatheredCleared = 0;
    uint8_t checkpointsReached = 0;
    bool usedCharacterOrb = false;
    bool endedOnSticky = false;

    uint64_t visitedTrailMask = 0;
    uint64_t visitedAllMask = 0;
    uint64_t charOrbVisitedMask = 0;
    std::array<uint8_t, kMaxMoveSteps> moves{};
    std::array<uint8_t, kMaxTrailWindow> trailRing{};
    uint8_t trailCount = 0;

    double beamScore = -1e18;
    double frontierScore = -1e18;
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

inline bool canEnterCell(const BoardState& board, uint8_t idx, const RuleConfig& rules, bool usedCharacterOrb = false) noexcept {
    const uint64_t b = bitOf(idx);
    if ((b & board.validMask) == 0) return false;
    // Note: BlackHole (#32) & EndForbidden (#31) ARE passable during movement and only forbidden as final endpoint!
    if ((b & (board.stormMask | board.noPassMask)) != 0) return false;
    // Skill #78 (角色符石通行地型): only Character Runestone can traverse CharOrbPass terrain!
    if (!usedCharacterOrb && (b & board.charOrbPassMask) != 0) return false;
    const Cell& c = board.cells[idx];
    if (c.isImmovable()) return false;
    if ((c.hazards & HazardBits::PurgatoryFire) != 0) return false; // 炼狱之火: 触碰即死!
    if ((c.hazards & HazardBits::Electrified) != 0) return false;
    if (rules.avoidCorroded && (c.hazards & HazardBits::Corroded) != 0) return false;
    if ((c.hazards & HazardBits::Weathered) != 0 && !rules.allowHitWeatheredToClear) return false;
    return true;
}

inline bool canStartAtCell(const BoardState& board, uint8_t idx, const RuleConfig& rules) noexcept {
    if (rules.fixedStartCell >= 0) {
        if (idx != static_cast<uint8_t>(rules.fixedStartCell)) return false;
        return (bitOf(idx) & board.validMask) != 0 && !board.cells[idx].isImmovable();
    }
    if (!canEnterCell(board, idx, rules)) return false;
    if ((board.stickyTileMask & bitOf(idx)) != 0) return false;
    if ((board.cells[idx].hazards & (HazardBits::StickyOrb | HazardBits::Corroded)) != 0) return false;
    if (board.startMask != 0) {
        return (board.startMask & bitOf(idx)) != 0;
    }
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
    m.miasmaHits = node.miasmaHits;
    m.witheredVineHits = node.witheredVineHits;
    m.weatheredClearedByHit = node.weatheredCleared;
    m.checkpointsReached = node.checkpointsReached;
    m.uniqueCellsVisited = static_cast<uint8_t>(std::popcount(node.visitedAllMask));
    m.charOrbVisitedMask = node.charOrbVisitedMask;
    m.charOrbEndIdx = node.curIdx;
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
    for (uint16_t i = 0; i < node.stepCount; ++i) {
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

// Reusable per-solve worker pool: spawns OS threads at most ONCE per solve() instead of per depth!
struct SolveThreadPool {
    uint32_t maxWorkers = 1;
    std::vector<std::thread> threads;
    std::mutex mtx;
    std::condition_variable cvStart;
    std::condition_variable cvDone;
    std::function<void(uint32_t)> task;
    uint32_t activeWorkers = 0;
    uint32_t remaining = 0;
    uint64_t generation = 0;
    bool stop = false;
    bool started = false;

    explicit SolveThreadPool(uint32_t count) : maxWorkers(count) {}

    void ensureStarted() {
        if (started || maxWorkers <= 1) return;
        started = true;
        threads.reserve(maxWorkers - 1);
        for (uint32_t tid = 1; tid < maxWorkers; ++tid) {
            threads.emplace_back([this, tid]() {
                uint64_t seenGen = 0;
                while (true) {
                    std::function<void(uint32_t)> fn;
                    {
                        std::unique_lock<std::mutex> lock(mtx);
                        cvStart.wait(lock, [&]() { return stop || generation != seenGen; });
                        if (stop) return;
                        seenGen = generation;
                        if (tid >= activeWorkers) continue;
                        fn = task;
                    }
                    fn(tid);
                    {
                        std::lock_guard<std::mutex> lock(mtx);
                        if (--remaining == 0) {
                            cvDone.notify_one();
                        }
                    }
                }
            });
        }
    }

    ~SolveThreadPool() {
        if (threads.empty()) return;
        {
            std::lock_guard<std::mutex> lock(mtx);
            stop = true;
            generation++;
        }
        cvStart.notify_all();
        for (auto& th : threads) {
            if (th.joinable()) th.join();
        }
    }

    template <typename Fn>
    void run(uint32_t workers, Fn&& fn) {
        if (workers <= 1 || maxWorkers <= 1) {
            fn(0);
            return;
        }
        ensureStarted();
        const uint32_t clamped = std::min<uint32_t>(workers, maxWorkers);
        {
            std::lock_guard<std::mutex> lock(mtx);
            task = std::ref(fn);
            activeWorkers = clamped;
            remaining = clamped - 1;
            generation++;
        }
        cvStart.notify_all();
        fn(0);
        {
            std::unique_lock<std::mutex> lock(mtx);
            cvDone.wait(lock, [&]() { return remaining == 0; });
            task = nullptr;
        }
    }
};

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
    const uint16_t rawMax = (rules.exactSteps >= 0)
        ? static_cast<uint16_t>(std::max<int>(rules.maxSteps, rules.exactSteps))
        : std::max<uint16_t>(rules.maxSteps, rules.minSteps);
    const uint16_t maxSteps = std::min<uint16_t>(rawMax, kMaxMoveSteps);
    // For single fixed-start solves (Hold-and-Replan), keep exact beamWidth (no 2x bloat for 30-cell diversity)
    // so the solver reaches deep high-combo solutions within a tight ~100-175ms real-time budget!
    const uint32_t beamWidth = (rules.fixedStartCell >= 0)
        ? std::clamp<uint32_t>(rules.beamWidth, 24, 800)
        : std::clamp<uint32_t>(rules.beamWidth * 2, 32, 2400);
    const auto& zt = zobrist();
    const bool hasTimeBudget = (rules.maxTimeMs > 0);
    const auto deadline = tStart + std::chrono::milliseconds(rules.maxTimeMs);

    auto isCancelled = [&]() -> bool {
        return cancelFlag != nullptr && cancelFlag->load(std::memory_order_relaxed);
    };
    auto isTimedOut = [&]() -> bool {
        if (!hasTimeBudget) return false;
        return std::chrono::steady_clock::now() >= deadline;
    };

    // Build initial frontier (Step 0)
    auto minStepsToMask = [&](uint8_t idx, uint64_t mask, bool diag) noexcept -> int {
        const int r1 = idx / cols;
        const int c1 = idx % cols;
        int best = 100;
        for (uint8_t i = 0; i < totalCells; ++i) {
            if ((mask & bitOf(i)) == 0) continue;
            const int dr = std::abs(r1 - static_cast<int>(i / cols));
            const int dc = std::abs(c1 - static_cast<int>(i % cols));
            const int d = diag ? std::max(dr, dc) : (dr + dc);
            if (d < best) best = d;
        }
        return best;
    };

    std::vector<SearchNode> frontier;
    if (rules.useCharacterOrb && rules.fixedStartCell < 0 && baseBoard.startMask == 0) {
        const uint8_t cCol = std::min<uint8_t>(rules.characterOrbCol, static_cast<uint8_t>(cols - 1));
        const uint8_t idx = baseBoard.index(0, cCol);
        if ((baseBoard.validMask & bitOf(idx)) != 0 &&
            (baseBoard.stormMask & bitOf(idx)) == 0 &&
            (baseBoard.noPassMask & bitOf(idx)) == 0) {
            SearchNode node;
            node.board = baseBoard;
            node.board.cells[idx] = Cell{rules.characterOrbElement, Variant::Normal, RaceType::None, HazardBits::None};
            node.startIdx = idx;
            node.curIdx = idx;
            node.usedCharacterOrb = true;
            node.stepCount = 0;
            node.visitedAllMask = bitOf(idx);
            node.charOrbVisitedMask = bitOf(idx);
            if ((baseBoard.miasmaMask & bitOf(idx)) != 0) {
                node.miasmaHits = 1;
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
            if (node.miasmaHits <= rules.maxMiasmaHits) {
                frontier.push_back(node);
            }
        }
    } else {
        for (uint8_t idx = 0; idx < totalCells; ++idx) {
            if (!canStartAtCell(baseBoard, idx, rules)) continue;
            SearchNode node;
            node.board = baseBoard;
            node.startIdx = idx;
            node.curIdx = idx;
            node.stepCount = 0;
            node.visitedAllMask = bitOf(idx);
            node.charOrbVisitedMask = bitOf(idx);
            // Picking up a sleeping orb wakes it up immediately
            if ((node.board.cells[idx].hazards & HazardBits::Sleep) != 0) {
                node.board.cells[idx].hazards &= ~HazardBits::Sleep;
            }
            if ((baseBoard.burningTileMask & bitOf(idx)) != 0 ||
                (baseBoard.cells[idx].hazards & (HazardBits::BurningOrb | HazardBits::Bomb | HazardBits::Thorn)) != 0) {
                node.burningHits = 1;
                if (rules.fixedStartCell < 0 && node.burningHits > rules.maxBurningTileHurts) continue;
            }
            if ((baseBoard.miasmaMask & bitOf(idx)) != 0) {
                node.miasmaHits = 1;
                if (rules.fixedStartCell < 0 && node.miasmaHits > rules.maxMiasmaHits) continue;
            }
            if ((baseBoard.cells[idx].hazards & HazardBits::WitheredVine) != 0) {
                node.witheredVineHits = 1;
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
    const size_t poolLimit = (rules.topK <= 1) ? 4 : std::max<size_t>(rules.topK * 8, 32);
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

    // In Tower of Saviors, a 0-step move does NOT trigger rune matching or pass a turn!
    // Only record depth-0 as a terminal solution if exactSteps == 0 or pulling a Character Orb itself moved a rune.
    const bool allowZeroStepSolution = (rules.exactSteps == 0) || rules.useCharacterOrb;
    bool canPruneEndUnreachable = false;
    if (baseBoard.endMask != 0) {
        for (const auto& n : frontier) {
            if (minStepsToMask(n.curIdx, baseBoard.endMask, rules.allowDiagonalMove) <= static_cast<int>(maxSteps)) {
                canPruneEndUnreachable = true;
                break;
            }
        }
    }
    bool foundGoalEarly = false;
    for (SearchNode& node : frontier) {
        const SimulationResult sim = simulateBoard(node.board, rules);
        const EvalResult ev = evaluateCandidate(node.board, sim, makeMetrics(node), rules);
        node.beamScore = ev.totalScore;
        node.frontierScore = ev.totalScore;
        node.allHardMet = ev.allHardConstraintsMet;
        out.statesEvaluated++;
        if (allowZeroStepSolution) {
            recordCandidate(node);
            if (rules.earlyStopOnGoal && ev.allHardConstraintsMet &&
                (rules.exactSteps < 0 || rules.exactSteps == 0)) {
                foundGoalEarly = true;
            }
        }
    }

    uint32_t hwThreads = std::thread::hardware_concurrency();
    if (hwThreads == 0) hwThreads = 4;
    const uint32_t threadCount = (rules.numThreads > 0) ? rules.numThreads : std::min<uint32_t>(hwThreads, 6);
    SolveThreadPool workerPool(threadCount);

    std::unordered_set<uint64_t> globalVisited;
    globalVisited.reserve(static_cast<size_t>(beamWidth) * std::min<uint16_t>(maxSteps, 36));
    for (const auto& n : frontier) {
        globalVisited.insert(n.stateHash);
    }

    std::atomic<bool> timedOutFlag{false};

    for (uint16_t depth = 1; depth <= maxSteps && !frontier.empty() && !foundGoalEarly; ++depth) {
        if (isCancelled()) {
            out.interrupted = true;
            break;
        }
        if (timedOutFlag.load(std::memory_order_relaxed) || isTimedOut()) {
            out.timedOut = true;
            break;
        }

        const size_t fSize = frontier.size();
        const uint32_t workers = (fSize >= 24 && threadCount > 1)
            ? std::min<uint32_t>(threadCount, static_cast<uint32_t>(fSize / 12))
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
                if (isCancelled() ||
                    earlyGoalFlag.load(std::memory_order_relaxed) ||
                    timedOutFlag.load(std::memory_order_relaxed)) {
                    break;
                }
                // Check hard deadline inside worker loop every 8 frontier parents (~2-5ms precision!)
                if (hasTimeBudget && ((fi - beginIdx) & 7u) == 7u && isTimedOut()) {
                    timedOutFlag.store(true, std::memory_order_relaxed);
                    break;
                }
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
                    if (!canEnterCell(parent.board, nextIdx, rules, parent.usedCharacterOrb)) continue;

                    // Traffic Light exact reachability pruning: discard branches that cannot possibly reach endMask in remaining steps
                    const int remSteps = static_cast<int>(maxSteps) - static_cast<int>(parent.stepCount + 1);
                    if (canPruneEndUnreachable) {
                        const int minStepsToEnd = minStepsToMask(nextIdx, baseBoard.endMask, rules.allowDiagonalMove);
                        if (minStepsToEnd > remSteps) continue;
                    }

                    // Trail Corrosion self-avoiding path check (轨迹腐蚀: 不可回头重访!)
                    if ((rules.trailCorrosionEnabled || (parent.board.trailCorrosionMask & bitOf(nextIdx)) != 0) &&
                        (parent.visitedAllMask & bitOf(nextIdx)) != 0) {
                        continue;
                    }

                    uint8_t nextBurn = parent.burningHits;
                    if ((parent.board.burningTileMask & bitOf(nextIdx)) != 0 ||
                        (parent.board.cells[nextIdx].hazards & (HazardBits::BurningOrb | HazardBits::Bomb | HazardBits::Thorn)) != 0) {
                        nextBurn++;
                        if (nextBurn > rules.maxBurningTileHurts) continue;
                    }

                    uint8_t nextMiasma = parent.miasmaHits;
                    if ((parent.board.miasmaMask & bitOf(nextIdx)) != 0) {
                        nextMiasma++;
                        if (nextMiasma > rules.maxMiasmaHits) continue;
                    }

                    uint8_t nextVine = parent.witheredVineHits;
                    if ((parent.board.cells[nextIdx].hazards & HazardBits::WitheredVine) != 0) {
                        nextVine++;
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
                    const uint16_t oldCurSig = ZobristTable::cellSig(child.board.cells[parent.curIdx]);
                    const uint16_t oldNextSig = ZobristTable::cellSig(child.board.cells[nextIdx]);

                    if (rules.allowHitWeatheredToClear &&
                        (child.board.cells[nextIdx].hazards & HazardBits::Weathered) != 0) {
                        child.board.cells[nextIdx].hazards &= ~HazardBits::Weathered;
                        child.weatheredCleared++;
                    }
                    // Stepping through a sleeping orb wakes it up so it can match!
                    if ((child.board.cells[nextIdx].hazards & HazardBits::Sleep) != 0) {
                        child.board.cells[nextIdx].hazards &= ~HazardBits::Sleep;
                    }

                    std::swap(child.board.cells[parent.curIdx], child.board.cells[nextIdx]);

                    const uint16_t newCurSig = ZobristTable::cellSig(child.board.cells[parent.curIdx]);
                    const uint16_t newNextSig = ZobristTable::cellSig(child.board.cells[nextIdx]);
                    child.boardHash = parent.boardHash
                        ^ zt.cellKey[parent.curIdx][oldCurSig] ^ zt.cellKey[parent.curIdx][newCurSig]
                        ^ zt.cellKey[nextIdx][oldNextSig] ^ zt.cellKey[nextIdx][newNextSig];
                    child.stateHash = child.boardHash ^ zt.cursorKey[nextIdx];

                    child.curIdx = nextIdx;
                    child.lastDir = dir;
                    child.moves[parent.stepCount] = d;
                    child.stepCount = static_cast<uint16_t>(parent.stepCount + 1);
                    if (isDiagonal(dir)) child.diagonalSteps++;
                    child.burningHits = nextBurn;
                    child.miasmaHits = nextMiasma;
                    child.witheredVineHits = nextVine;
                    child.trailHits = nextTrailHits;
                    child.visitedAllMask |= bitOf(nextIdx);
                    child.charOrbVisitedMask |= bitOf(nextIdx);
                    child.endedOnSticky = ((parent.board.stickyTileMask & bitOf(nextIdx)) != 0) ||
                                          ((parent.board.cells[nextIdx].hazards & (HazardBits::StickyOrb | HazardBits::Corroded)) != 0);

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
                    child.frontierScore = ev.totalScore;
                    if (baseBoard.endMask != 0 && (baseBoard.endMask & bitOf(nextIdx)) == 0) {
                        // Neutralize premature -150,000 TrafficLight cliff during intermediate beam exploration
                        // so paths can freely build high combos/shields across the board, then funnel cleanly into endMask!
                        const int manhToEnd = minStepsToMask(nextIdx, baseBoard.endMask, false);
                        const int stepToEnd = rules.allowDiagonalMove
                            ? minStepsToMask(nextIdx, baseBoard.endMask, true)
                            : manhToEnd;
                        const double pull = (remSteps <= stepToEnd + 6) ? 180.0 : 42.0;
                        child.frontierScore += 150000.0 + static_cast<double>(manhToEnd) * 200.0 - static_cast<double>(stepToEnd) * pull;
                    }
                    if (baseBoard.endForbiddenMask != 0 && (baseBoard.endForbiddenMask & bitOf(nextIdx)) != 0 && remSteps >= 1) {
                        // BlackHole (#32) / Bloodbath (#31) are passable during movement; neutralize terminal cliff mid-path!
                        child.frontierScore += 150600.0 - ((remSteps <= 2) ? 120.0 : 0.0);
                    }
                    if (rules.exactSteps < 0 && rules.minSteps > 0 && child.stepCount < rules.minSteps) {
                        // Step-Range Shield (#60): allow smooth beam exploration until reaching minSteps!
                        child.frontierScore += 100000.0 + static_cast<double>(rules.minSteps - child.stepCount) * 180.0;
                    }
                    if (rules.exactSteps >= 0 && child.stepCount < static_cast<uint16_t>(rules.exactSteps)) {
                        child.frontierScore += 100000.0 + static_cast<double>(rules.exactSteps - child.stepCount) * 200.0;
                    }
                    child.allHardMet = ev.allHardConstraintsMet;
                    threadEvaluated[tid]++;

                    if (rules.earlyStopOnGoal && ev.allHardConstraintsMet) {
                        earlyGoalFlag.store(true, std::memory_order_relaxed);
                    }

                    localOut.push_back(child);
                }
            }
        };

        workerPool.run(workers, workerFn);

        size_t totalCand = 0;
        for (uint32_t t = 0; t < workers; ++t) {
            out.statesExpanded += threadExpanded[t];
            out.statesEvaluated += threadEvaluated[t];
            totalCand += threadCandidates[t].size();
        }
        if (totalCand == 0) {
            if (timedOutFlag.load(std::memory_order_relaxed)) out.timedOut = true;
            break;
        }

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
                refs.push_back(CandRef{t, i, v[i].frontierScore});
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

        // When fixedStartCell >= 0 (Hold-and-Replan), all nodes share the single held start cell;
        // do NOT apply multi-start per-start cap (otherwise depths 1..8 collapse primary diversity to ~12 nodes!).
        const uint32_t maxPerStart = (rules.fixedStartCell < 0 && depth <= 8)
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
        if (timedOutFlag.load(std::memory_order_relaxed)) {
            out.timedOut = true;
            break;
        }
    }

    // Build diverse Top-K solutions (fast 1-candidate path when topK == 1 during Hold-and-Replan!)
    const uint32_t targetK = std::max<uint32_t>(1, rules.topK);
    if (targetK == 1 && !bestPool.empty()) {
        const SearchNode* bestNode = &bestPool[0];
        for (size_t i = 1; i < bestPool.size(); ++i) {
            const auto& cand = bestPool[i];
            if (cand.allHardMet != bestNode->allHardMet) {
                if (cand.allHardMet) bestNode = &cand;
            } else if (cand.beamScore > bestNode->beamScore) {
                bestNode = &cand;
            }
        }
        SolutionPath sp = buildSolutionPath(*bestNode, rules, "");
        sp.tag = sp.evaluation.allHardConstraintsMet ? "最佳解 (全盾达成)" : "当前最优解";
        out.solutions.push_back(std::move(sp));
        out.elapsedMs = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - tStart).count();
        return out;
    }

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
