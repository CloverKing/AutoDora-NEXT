#pragma once

#include "autodora/board.hpp"
#include "autodora/rules.hpp"

#include <array>
#include <cstdint>

namespace autodora {

constexpr uint8_t kMaxMatchGroups = 32;

struct MatchGroup {
    uint8_t wave = 1;            // 1 = 首消 (First Wave), 2+ = 叠珠/天降
    Element element = Element::Empty;
    uint8_t groupElementsMask = 0; // 跨属兼珠消除时包含的全部属性位掩码
    uint64_t cellMask = 0;       // 参与消除的格子掩码
    uint8_t orbCount = 0;
    uint8_t enchantedCount = 0;
    uint8_t raceCount = 0;

    // Geometry detections
    bool isExactCross5 = false;  // 严格 5 颗十字
    bool containsCross = false;  // 包含 5 颗十字核心
    bool isLShape = false;
    bool isTShape = false;
    bool isSquare2x2 = false;
    uint8_t fullRowMask = 0;     // 哪些横行被该组完整覆盖
    uint8_t fullColMask = 0;     // 哪些直行被该组完整覆盖
    std::array<uint8_t, kMaxBoardDim> colMaxRun{}; // 该组在各直行中的最大连续纵向颗数
};

// Stack-allocated SimulationResult: ZERO heap allocations in beam search hot path!
struct SimulationResult {
    std::array<MatchGroup, kMaxMatchGroups> groups{};
    uint8_t groupCount = 0;

    uint8_t firstWaveCombo = 0;
    uint8_t secondWaveCombo = 0;
    uint8_t totalCombo = 0;
    uint8_t cascadeWaves = 0;

    uint16_t firstWaveOrbsTotal = 0;
    uint16_t totalOrbsEliminated = 0;
    uint16_t matchableCellsAtStart = 0;

    uint8_t firstWaveElementsMask = 0;
    uint8_t secondWaveElementsMask = 0;
    uint8_t totalElementsMask = 0;
    uint8_t firstWaveEnchantedElementsMask = 0;
    uint8_t totalEnchantedElementsMask = 0;
    uint8_t firstWaveRaceElementsMask = 0;
    uint8_t totalRaceOrbsFirstWave = 0;

    std::array<uint8_t, kNumElements> firstWaveElementCombos{};
    std::array<uint8_t, kNumElements> totalElementCombos{};
    std::array<uint8_t, kNumElements> firstWaveElementOrbs{};
    std::array<uint8_t, kNumElements> totalElementOrbs{};
    std::array<uint8_t, kNumElements> initialElementCounts{};
    std::array<uint8_t, kNumElements> remainingAfterFirstWave{};

    // Hazard clearance tracking
    uint64_t firstWaveClearedMask = 0;
    uint64_t allWavesClearedMask = 0;
    uint8_t electrifiedRemaining = 0;
    uint8_t bloodDrainRemainingFirstWave = 0;
    uint8_t bombRemainingFirstWave = 0;
    uint8_t frozenRemaining = 0;
    uint8_t foamRemaining = 0;
    uint16_t foamAdjacentHits = 0;
    uint8_t lockedHurtRemainingFirstWave = 0;
    uint8_t lockedAtkDownRemaining = 0;
    uint8_t lightPrisonRemaining = 0;
    uint8_t luminousCleared = 0;
    uint8_t weakenedEliminated = 0;
    uint8_t scorchingOrbsCleared = 0;  // #28/#96 灼热地型内被消除的符石数
    uint8_t priorityOrbsRemaining = 0;
    uint8_t reserveOrbsBroken = 0;
    uint16_t petrifiedAdjacentHits = 0;

    uint16_t skyfallRiskPairs = 0;
    BoardState settledBoard;
};

SimulationResult simulateBoard(const BoardState& board, const RuleConfig& rules);

} // namespace autodora
