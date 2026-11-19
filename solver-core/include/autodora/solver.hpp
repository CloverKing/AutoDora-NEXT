#pragma once

#include "autodora/board.hpp"
#include "autodora/evaluator.hpp"
#include "autodora/rules.hpp"
#include "autodora/simulator.hpp"

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace autodora {

struct SolutionPath {
    CellCoord start{};
    bool usedCharacterOrb = false;
    uint8_t characterOrbCol = 0;
    std::vector<Direction> moves;
    std::vector<CellCoord> waypoints; // Includes start + every stepped cell

    PathMetrics metrics{};
    SimulationResult simulation{};
    EvalResult evaluation{};
    std::string tag; // e.g. "Best Overall", "Shortest Valid", "High First Wave"
};

struct SolveOutput {
    std::vector<SolutionPath> solutions; // Sorted best-first, diverse Top-K
    uint64_t statesExpanded = 0;
    uint64_t statesEvaluated = 0;
    double elapsedMs = 0.0;
    bool interrupted = false;
    bool timedOut = false;
};

class PuzzleSolver {
public:
    PuzzleSolver() = default;

    // Solve the given board under composable RuleConfig.
    // Thread-safe; optional cancelFlag allows immediate cooperative cancellation.
    SolveOutput solve(
        const BoardState& initialBoard,
        const RuleConfig& rules,
        const std::atomic<bool>* cancelFlag = nullptr
    ) const;
};

} // namespace autodora
