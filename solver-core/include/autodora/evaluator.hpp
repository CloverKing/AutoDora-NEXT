#pragma once

#include "autodora/board.hpp"
#include "autodora/rules.hpp"
#include "autodora/simulator.hpp"

#include <cstdint>

namespace autodora {

// Bit flags summarizing which shields/constraints were satisfied or violated
namespace ShieldStatusBits {
constexpr uint32_t None              = 0;
constexpr uint32_t ComboShield       = 1u << 0;
constexpr uint32_t ComboParity       = 1u << 1;
constexpr uint32_t SameComboElements = 1u << 2;
constexpr uint32_t DigitalOrbCount   = 1u << 3;
constexpr uint32_t FullClear         = 1u << 4;
constexpr uint32_t ElementShield     = 1u << 5;
constexpr uint32_t EnchantedShield   = 1u << 6;
constexpr uint32_t ForbidFirstWave   = 1u << 7;
constexpr uint32_t ForbidAll         = 1u << 8;
constexpr uint32_t ClearAllElement   = 1u << 9;
constexpr uint32_t Puzzle1           = 1u << 10;
constexpr uint32_t Puzzle2           = 1u << 11;
constexpr uint32_t CrossShield       = 1u << 12;
constexpr uint32_t ShapeShield       = 1u << 13;
constexpr uint32_t ColumnAmeliorate  = 1u << 14;
constexpr uint32_t RowBonus          = 1u << 15;
constexpr uint32_t TrafficLight      = 1u << 16;
constexpr uint32_t StepLimit         = 1u << 17;
constexpr uint32_t HazardsClear      = 1u << 18;
constexpr uint32_t BurningLimit      = 1u << 19;
constexpr uint32_t SecondWaveShield  = 1u << 20;
constexpr uint32_t EqualPairCombo    = 1u << 21;
constexpr uint32_t SameElemMultiC    = 1u << 22;
constexpr uint32_t RaceOrbShield     = 1u << 23;
constexpr uint32_t CharOrbTerrain    = 1u << 24;
constexpr uint32_t TrailCellsGoal    = 1u << 25;
constexpr uint32_t MiasmaLimit       = 1u << 26;
constexpr uint32_t Wave1KindShield   = 1u << 27; // #57 首消相同種類盾 / #58 首消不同種類盾
} // namespace ShieldStatusBits

struct PathMetrics {
    uint16_t stepCount = 0;           // Effective game steps (Character orb pull is step 0!)
    uint16_t diagonalSteps = 0;
    uint8_t startIdx = 0;
    uint8_t endIdx = 0;
    uint8_t burningTileHits = 0;
    uint8_t trailBurningHits = 0;
    uint8_t miasmaHits = 0;
    uint8_t witheredVineHits = 0;
    uint8_t weatheredClearedByHit = 0;
    uint8_t checkpointsReached = 0;
    uint8_t uniqueCellsVisited = 0;
    uint64_t charOrbVisitedMask = 0;
    uint8_t charOrbEndIdx = 255;
    bool usedCharacterOrb = false;
    bool endedOnSticky = false;
};

struct EvalResult {
    double totalScore = -1e18;
    double softScore = 0.0;
    double shapingBonus = 0.0;
    double hardPenalty = 0.0;
    bool allHardConstraintsMet = false;
    uint32_t satisfiedFlags = 0;
    uint32_t failedFlags = 0;
};

// Evaluate a candidate terminal board state + path metrics against all active rules.
EvalResult evaluateCandidate(
    const BoardState& boardAfterMoves,
    const SimulationResult& sim,
    const PathMetrics& pathMetrics,
    const RuleConfig& rules
);

} // namespace autodora
