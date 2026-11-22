#include "autodora/board.hpp"
#include "autodora/evaluator.hpp"
#include "autodora/rules.hpp"
#include "autodora/simulator.hpp"
#include "autodora/solver.hpp"

#include <atomic>
#include <cassert>
#include <cmath>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>

using namespace autodora;

namespace {

int gTestsPassed = 0;
int gTestsFailed = 0;

void require(bool cond, const std::string& msg) {
    if (!cond) {
        throw std::runtime_error("ASSERTION FAILED: " + msg);
    }
}

// Independently replay a SolutionPath from initialBoard to verify zero illegal moves & exact simulation match
void verifyPathReplay(const BoardState& initial, const RuleConfig& rules, const SolutionPath& sol) {
    BoardState b = initial;
    b.rebuildMasks();

    if (sol.usedCharacterOrb) {
        require(rules.useCharacterOrb, "usedCharacterOrb without rule");
        require(sol.start.row == 0 && sol.start.col == rules.characterOrbCol, "character orb must start at (0, col)");
        b.at(0, rules.characterOrbCol) = Cell{rules.characterOrbElement, Variant::Normal, HazardBits::None};
    }

    CellCoord cur = sol.start;
    require(sol.waypoints.size() == sol.moves.size() + 1, "waypoints size mismatch");
    require(sol.waypoints[0] == cur, "start waypoint mismatch");
    require(sol.metrics.stepCount == sol.moves.size(), "stepCount mismatch");

    for (size_t i = 0; i < sol.moves.size(); ++i) {
        const Direction d = sol.moves[i];
        const int nr = static_cast<int>(cur.row) + kDirDr[static_cast<uint8_t>(d)];
        const int nc = static_cast<int>(cur.col) + kDirDc[static_cast<uint8_t>(d)];
        require(b.inBounds(nr, nc), "move out of bounds");
        if (!rules.allowDiagonalMove) {
            require(!isDiagonal(d), "diagonal move used when disabled");
        }
        const uint8_t curIdx = b.index(cur.row, cur.col);
        const uint8_t nextIdx = b.index(static_cast<uint8_t>(nr), static_cast<uint8_t>(nc));
        require((b.stormMask & bitOf(nextIdx)) == 0, "entered storm tile");
        require((b.noPassMask & bitOf(nextIdx)) == 0, "entered no-pass tile");
        if (!sol.usedCharacterOrb) {
            require((b.charOrbPassMask & bitOf(nextIdx)) == 0, "non-char-orb entered charOrbPass terrain");
        }
        require(!b.cells[nextIdx].isImmovable(), "swapped immovable cell");
        require((b.cells[nextIdx].hazards & HazardBits::PurgatoryFire) == 0, "touched purgatory fire orb");
        require((b.cells[nextIdx].hazards & HazardBits::Electrified) == 0, "touched electrified orb");
        if (rules.avoidCorroded) {
            require((b.cells[nextIdx].hazards & HazardBits::Corroded) == 0, "touched corroded orb");
        }
        if (!rules.allowHitWeatheredToClear) {
            require((b.cells[nextIdx].hazards & HazardBits::Weathered) == 0, "touched weathered orb");
        } else {
            b.cells[nextIdx].hazards &= ~HazardBits::Weathered;
        }
        if ((b.cells[nextIdx].hazards & HazardBits::Sleep) != 0) {
            b.cells[nextIdx].hazards &= ~HazardBits::Sleep;
        }
        if (i == 0 && (b.cells[curIdx].hazards & HazardBits::Sleep) != 0) {
            b.cells[curIdx].hazards &= ~HazardBits::Sleep;
        }
        std::swap(b.cells[curIdx], b.cells[nextIdx]);
        cur = CellCoord{static_cast<uint8_t>(nr), static_cast<uint8_t>(nc)};
        require(sol.waypoints[i + 1] == cur, "waypoint mismatch during replay");
    }

    const SimulationResult sim = simulateBoard(b, rules);
    if (sol.evaluation.allHardConstraintsMet && b.endForbiddenMask != 0) {
        const uint8_t endIdx = b.index(cur.row, cur.col);
        require((b.endForbiddenMask & bitOf(endIdx)) == 0, "hard-met path ended on black hole / bloodbath tile");
    }
    require(sim.totalCombo == sol.simulation.totalCombo, "replayed totalCombo mismatch");
    require(sim.firstWaveCombo == sol.simulation.firstWaveCombo, "replayed firstWaveCombo mismatch");
    require(sim.firstWaveOrbsTotal == sol.simulation.firstWaveOrbsTotal, "replayed firstWaveOrbsTotal mismatch");
}

void runTest(const std::string& name, void (*fn)()) {
    try {
        fn();
        gTestsPassed++;
        std::cout << "[PASS] " << name << "\n";
    } catch (const std::exception& ex) {
        gTestsFailed++;
        std::cout << "[FAIL] " << name << " -> " << ex.what() << "\n";
    }
}

// ============================================================================
// 1. Standard 6x5 High-Combo & Latency Benchmark + Top-K Diversity
// ============================================================================
void testStandard6x5HighCombo() {
    // Realistic 6x5 board with theoretical max = 9 combos (6W, 6F, 6G, 6L, 3D, 3H)
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLWFG"
        "LWDFGL"
        "GWFLHW"
        "FLDGWH",
        6, 5
    );
    RuleConfig r;
    r.maxSteps = 28;
    r.beamWidth = 180;
    r.maxTimeMs = 500;
    r.topK = 4;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "no solutions found");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    std::cout << "       6x5 HighCombo: " << int(best.simulation.totalCombo)
              << "C (1st=" << int(best.simulation.firstWaveCombo) << "C), steps="
              << int(best.metrics.stepCount) << ", topK=" << out.solutions.size()
              << ", time=" << out.elapsedMs << "ms, evals=" << out.statesEvaluated << "\n";
    require(best.simulation.totalCombo >= 7, "expected >= 7 combos on 9C-capable 6x5 board");
    require(out.solutions.size() >= 2, "expected diverse Top-K solutions");
}

// ============================================================================
// 2. Expanded 6x8 "违建" (48-cell) Board Test
// ============================================================================
void testExpanded6x8Weijian() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL"
        "HWFGLD"
        "WFLGDH"
        "GHDWFL",
        6, 8
    );
    require(b.cellCount() == 48, "6x8 must have 48 cells");
    RuleConfig r;
    r.maxSteps = 35;
    r.beamWidth = 120;
    r.maxTimeMs = 800;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "6x8 failed to produce solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    std::cout << "       6x8 Weijian: " << int(best.simulation.totalCombo)
              << "C, orbs=" << best.simulation.totalOrbsEliminated
              << ", steps=" << int(best.metrics.stepCount)
              << ", time=" << out.elapsedMs << "ms\n";
    require(best.simulation.totalCombo >= 10, "expected >= 10 combos on 6x8 weijian board");
}

// ============================================================================
// 3. Dynamic Board Sizes (7x6, 5x4, 4x4)
// ============================================================================
void testDynamicBoardSizes() {
    // 7x6 (42 cells)
    BoardState b76 = BoardState::fromString(
        "WFGLDHW"
        "FGLDHWF"
        "GLDHWFG"
        "LDHWFGL"
        "DHWFGLD"
        "HWFGLDH",
        7, 6
    );
    RuleConfig r;
    r.maxSteps = 20;
    r.beamWidth = 100;
    r.maxTimeMs = 400;
    PuzzleSolver solver;
    auto out76 = solver.solve(b76, r);
    require(!out76.solutions.empty() && out76.solutions[0].simulation.totalCombo >= 8, "7x6 failed");
    verifyPathReplay(b76, r, out76.solutions[0]);

    // 5x4 (20 cells)
    BoardState b54 = BoardState::fromString(
        "WFGLW"
        "FGLWF"
        "GLWFG"
        "LWFGL",
        5, 4
    );
    auto out54 = solver.solve(b54, r);
    require(!out54.solutions.empty() && out54.solutions[0].simulation.totalCombo >= 4, "5x4 failed");
    verifyPathReplay(b54, r, out54.solutions[0]);

    // 4x4 (16 cells)
    BoardState b44 = BoardState::fromString(
        "WFWF"
        "FWFW"
        "GLGL"
        "LGLG",
        4, 4
    );
    auto out44 = solver.solve(b44, r);
    require(!out44.solutions.empty() && out44.solutions[0].simulation.totalCombo >= 4, "4x4 failed");
    verifyPathReplay(b44, r, out44.solutions[0]);
}

// ============================================================================
// 4. Two-Match (二消) & Diagonal Match (斜消)
// ============================================================================
void testTwoMatchAndDiagonalMatch() {
    // Two-match Fire & Heart
    BoardState b2 = BoardState::fromString(
        "FFHHWW"
        "GGLDGL"
        "DGLDGL"
        "LDGLDG"
        "WLDGLD",
        6, 5
    );
    RuleConfig r2;
    r2.elementRules[static_cast<uint8_t>(Element::Fire)].minMatchCount = 2;
    r2.elementRules[static_cast<uint8_t>(Element::Heart)].minMatchCount = 2;
    auto sim2 = simulateBoard(b2, r2);
    require(sim2.firstWaveCombo == 2, "2-match should match FF and HH immediately");

    // Diagonal match (斜消): 3 Waters along main diagonal, zero orthogonal 3-in-a-row!
    BoardState bd = BoardState::fromString(
        "WFLGHF"
        "FWHLDG"
        "GLWFHD"
        "DFHGWL"
        "HLDGFW",
        6, 5
    );
    RuleConfig rd;
    rd.allowDiagonalMatch = false;
    require(simulateBoard(bd, rd).firstWaveCombo == 0, "diagonal should not match when allowDiagonalMatch=false");
    rd.allowDiagonalMatch = true;
    auto simd = simulateBoard(bd, rd);
    require(simd.firstWaveCombo >= 1 && (simd.firstWaveElementsMask & elementBit(Element::Water)),
            "diagonal match should match 3 diagonal Waters");
}

// ============================================================================
// 5. Strict Puzzle Shield (严格不黏连拼图盾 + 强化拼图盾)
// ============================================================================
void testStrictEnchantedPuzzleShield() {
    // Valid 5-cell strict L-puzzle (union of 3-horizontal + 3-vertical):
    // (1,1), (1,2), (1,3), (2,1), (3,1) — 6 Fire orbs on board (including F+), must NOT attach the 6th Fire orb!
    BoardState b = BoardState::fromString(
        "GDLHGD"
        "LFF+GFH"
        "DGFGLD"
        "HFGDLH"
        "GDLHFG",
        6, 5
    );
    b.setTileFlag(1, 1, TileFlagBits::Puzzle1);
    b.setTileFlag(1, 2, TileFlagBits::Puzzle1);
    b.setTileFlag(1, 3, TileFlagBits::Puzzle1);
    b.setTileFlag(2, 1, TileFlagBits::Puzzle1);
    b.setTileFlag(3, 1, TileFlagBits::Puzzle1);
    b.rebuildMasks();

    RuleConfig r;
    r.puzzle1.enabled = true;
    r.puzzle1.strictNoExternalTouch = true;
    r.puzzle1.requiredElement = static_cast<int8_t>(Element::Fire);
    r.puzzle1.requireEnchanted = true;
    r.maxSteps = 22;
    r.beamWidth = 220;
    r.maxTimeMs = 700;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "strict puzzle no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    std::cout << "       Strict Enchanted Puzzle: met=" << best.evaluation.allHardConstraintsMet
              << ", combo=" << int(best.simulation.totalCombo)
              << ", steps=" << int(best.metrics.stepCount)
              << ", time=" << out.elapsedMs << "ms\n";
    require(best.evaluation.allHardConstraintsMet, "strict enchanted puzzle shield must be satisfied");
    require((best.evaluation.satisfiedFlags & ShieldStatusBits::Puzzle1) != 0, "Puzzle1 flag must be set");
}

// ============================================================================
// 6. Single Cross Shield (严格5颗十字盾, 盘面6颗火珠需避免第6颗黏连)
// ============================================================================
void testStrictCrossShield() {
    BoardState b = BoardState::fromString(
        "WDFGHW"
        "DFWGLD"
        "LFWFHL"
        "GDFWGD"
        "HLDGFH",
        6, 5
    );
    RuleConfig r;
    r.crossShield.requiredCount = 1;
    r.crossShield.strict5Orbs = true;
    r.crossShield.requiredElement = static_cast<int8_t>(Element::Fire);
    r.maxSteps = 24;
    r.beamWidth = 240;
    r.maxTimeMs = 700;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "cross shield no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    std::cout << "       Strict Cross Shield: met=" << best.evaluation.allHardConstraintsMet
              << ", combo=" << int(best.simulation.totalCombo)
              << ", steps=" << int(best.metrics.stepCount)
              << ", time=" << out.elapsedMs << "ms\n";
    require(best.evaluation.allHardConstraintsMet, "strict 5-orb fire cross shield must be met");
}

// ============================================================================
// 7. Geometry Classifier Unit Check (L / T / 2x2 / Cross)
// ============================================================================
void testShapeDetections() {
    // 5-orb L-shape of Water + 5-orb T-shape of Fire (cols 3-5 row 0, stem col 4 rows 1-2) + 2x2 Square of Wood
    BoardState b = BoardState::fromString(
        "WLDFFF"
        "WGHDFG"
        "WWWHFD"
        "GGHLDG"
        "GGDLHG",
        6, 5
    );
    RuleConfig r;
    r.elementRules[static_cast<uint8_t>(Element::Wood)].minMatchCount = 2;
    auto sim = simulateBoard(b, r);
    bool foundL = false, foundT = false, foundSq = false;
    for (uint8_t gi = 0; gi < sim.groupCount; ++gi) {
        const auto& g = sim.groups[gi];
        if (g.wave != 1) continue;
        if (g.element == Element::Water && g.isLShape) foundL = true;
        if (g.element == Element::Fire && g.isTShape) foundT = true;
        if (g.element == Element::Wood && g.isSquare2x2) foundSq = true;
    }
    require(foundL, "failed to detect L-shape");
    require(foundT, "failed to detect T-shape");
    require(foundSq, "failed to detect 2x2 Square");
}

// ============================================================================
// 8. Digital Orb Count Shield (数字盾: 首消恰 18 颗)
// ============================================================================
void testDigitalOrbCountShield() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.exactFirstWaveTotalOrbs = 18;
    r.maxSteps = 22;
    r.beamWidth = 180;
    r.maxTimeMs = 600;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "digital shield no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    std::cout << "       Digital Shield (18 orbs): actual=" << best.simulation.firstWaveOrbsTotal
              << ", met=" << best.evaluation.allHardConstraintsMet
              << ", steps=" << int(best.metrics.stepCount) << "\n";
    require(best.evaluation.allHardConstraintsMet, "exact 18 first-wave orbs must be met");
    require(best.simulation.firstWaveOrbsTotal == 18, "first wave orbs must equal 18");
}

// ============================================================================
// 9. Exact Combo Shield (恰好 5C) + Odd Parity Shield
// ============================================================================
void testExactAndParityComboShield() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.exactTotalCombo = 5;
    r.comboParity = ComboParity::Odd;
    r.comboParityFirstWaveOnly = false;
    r.maxSteps = 18;
    r.beamWidth = 140;
    r.earlyStopOnGoal = true;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "exact 5C no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "exact 5C + odd parity must be satisfied");
    require(best.simulation.totalCombo == 5, "totalCombo must equal 5");
}

// ============================================================================
// 10. Anti-First-Wave (反首消火 -> 叠珠) + Absolute Forbid (绝对禁消心)
// ============================================================================
void testAntiFirstWaveAndForbidAll() {
    BoardState b = BoardState::fromString(
        "FFGLDH"
        "WGFDHW"
        "WLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.elementRules[static_cast<uint8_t>(Element::Fire)].timing = MatchTiming::ForbidFirstWaveAllowCascade;
    r.elementRules[static_cast<uint8_t>(Element::Heart)].timing = MatchTiming::ForbidAll;
    r.maxSteps = 20;
    r.beamWidth = 160;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "anti-first-wave no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "anti-first-wave + forbid-all must be met");
    require((best.simulation.firstWaveElementsMask & elementBit(Element::Fire)) == 0, "Fire must NOT be in Wave 1");
    require((best.simulation.totalElementsMask & elementBit(Element::Heart)) == 0, "Heart must NEVER be eliminated");
}

// ============================================================================
// 11. Five-Element Enchanted Shield (五属强化盾)
// ============================================================================
void testFiveElementEnchantedShield() {
    BoardState b = BoardState::fromString(
        "W+F+G+L+D+H"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    const uint8_t fiveMask = elementBit(Element::Water) | elementBit(Element::Fire) |
                             elementBit(Element::Wood) | elementBit(Element::Light) |
                             elementBit(Element::Dark);
    r.requiredTotalElementsMask = fiveMask;
    r.requiredEnchantedElementsMask = fiveMask;
    r.maxSteps = 26;
    r.beamWidth = 220;
    r.maxTimeMs = 700;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "5-element enchanted no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    std::cout << "       5-Element Enchanted: met=" << best.evaluation.allHardConstraintsMet
              << ", combo=" << int(best.simulation.totalCombo)
              << ", time=" << out.elapsedMs << "ms\n";
    require(best.evaluation.allHardConstraintsMet, "5-element enchanted shield must be satisfied");
}

// ============================================================================
// 12. Column Amelioration (直排昇华: 第1列与第6列硬性要求4消)
// ============================================================================
void testColumnAmelioration() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "WGLDFH"
        "FLDWGH"
        "WDFGLH"
        "WGFDLD",
        6, 5
    );
    RuleConfig r;
    r.columnRules[0] = ColumnAmeliorationRule{true, 4, true, -1};
    r.columnRules[5] = ColumnAmeliorationRule{true, 4, true, -1};
    r.maxSteps = 22;
    r.beamWidth = 200;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "column amelioration no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "column 1 & 6 >=4 amelioration must be met");
}

// ============================================================================
// 13. Hazards & Terrain: Shattered (可经过不可消!), Storm, Weathered, Electrified, Petrified
// ============================================================================
void testHazardsAndTerrainSemantics() {
    // Test Shattered: can pass through, cannot eliminate!
    BoardState bs = BoardState::fromString(
        "WWWFLG"
        "FLGDLH"
        "DLHFGD"
        "FGDHLF"
        "HLFDGH",
        6, 5
    );
    bs.setTileFlag(0, 1, TileFlagBits::Shattered); // Breaks the WWW match in row 0!
    bs.rebuildMasks();
    RuleConfig rs;
    auto simS = simulateBoard(bs, rs);
    require(simS.firstWaveCombo == 0, "Shattered tile must prevent elimination of WWW");

    // Test Petrified adjacent clearance counting
    BoardState bp = BoardState::fromString(
        "WWWFLG"
        "FLGDLH"
        "DLHFGD"
        "FGDHLF"
        "HLFDGH",
        6, 5
    );
    bp.at(1, 1).hazards |= HazardBits::Petrified; // Adjacent to (0,1) which is matched in WWW!
    auto simP = simulateBoard(bp, rs);
    require(simP.petrifiedAdjacentHits >= 1, "Eliminating adjacent group must count petrified hit");

    // Test Weathered avoidance + Electrified must-clear (without touching) + Storm avoidance
    BoardState bh = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    bh.at(2, 2).hazards |= HazardBits::Weathered;
    bh.at(0, 0).hazards |= HazardBits::Electrified; // Must clear without touching!
    bh.setTileFlag(4, 5, TileFlagBits::Storm);
    bh.rebuildMasks();

    RuleConfig rh;
    rh.mustClearElectrified = true;
    rh.maxSteps = 22;
    rh.beamWidth = 200;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(bh, rh);
    require(!out.solutions.empty(), "hazards board no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(bh, rh, best);
    require(best.evaluation.allHardConstraintsMet, "must clear electrified without touching weathered/storm/electrified");
    require(best.simulation.electrifiedRemaining == 0, "electrified orb must be eliminated");
}

// ============================================================================
// 14. Trail Burning (轨迹燃烧: 0 自碰) + Traffic Light (红绿灯起终点 + 精确步数)
// ============================================================================
void testTrailBurningAndTrafficLight() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    b.setTileFlag(0, 0, TileFlagBits::StartAllowed); // Green light
    b.setTileFlag(4, 5, TileFlagBits::EndRequired);  // Red light
    b.rebuildMasks();

    RuleConfig r;
    r.allowDiagonalMove = false; // 4-dir only: Manhattan distance from (0,0) to (4,5) = 9
    r.trailBurningEnabled = true;
    r.trailLength = 0; // Unlimited trail: cannot revisit ANY tile!
    r.maxTrailHurts = 0;
    r.exactSteps = 11; // Must arrive at (4,5) in exactly 11 non-revisiting orthogonal steps!
    r.maxSteps = 11;
    r.beamWidth = 200;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "traffic light + trail burning no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "traffic light + 0-hurt trail + exact 11 steps must succeed");
    require(best.start == (CellCoord{0, 0}), "must start at green light (0,0)");
    require(best.waypoints.back() == (CellCoord{4, 5}), "must end at red light (4,5)");
    require(best.metrics.stepCount == 11, "must take exact 11 steps");
    require(best.metrics.trailBurningHits == 0, "must have 0 trail self-collisions");
}

// ============================================================================
// 15. Character Orb Pull Start (角色珠下拉起手: 首动免步数/免燃烧/移除障碍)
// ============================================================================
void testCharacterOrbStart() {
    BoardState b = BoardState::fromString(
        "DFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    // Put Burning tile + Petrified hazard on (0, 0) — pulling Character Orb from Col 0 removes the hazard & ignores step-0 burn!
    b.setTileFlag(0, 0, TileFlagBits::BurningTile);
    b.at(0, 0).hazards |= HazardBits::Petrified;
    b.rebuildMasks();

    RuleConfig r;
    r.useCharacterOrb = true;
    r.characterOrbCol = 0;
    r.characterOrbElement = Element::Fire;
    r.maxBurningTileHurts = 0;
    r.maxSteps = 15;
    r.beamWidth = 120;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "character orb start failed");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.usedCharacterOrb, "must flag usedCharacterOrb");
    require(best.start == (CellCoord{0, 0}), "character orb starts at (0,0)");
    require(best.metrics.burningTileHits == 0, "character orb pull must not trigger step-0 burn");
    require(best.evaluation.allHardConstraintsMet, "character orb solution must meet hard constraints");
}

// ============================================================================
// 16. Cooperative Cancellation
// ============================================================================
void testCancellation() {
    BoardState b = BoardState::fromString(
        "WFGLDHFGLDHWGLDHWFLDHWFGDHWFGL",
        6, 5
    );
    RuleConfig r;
    r.maxSteps = 40;
    r.beamWidth = 500;
    std::atomic<bool> cancel{true};
    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r, &cancel);
    require(out.interrupted, "pre-set cancelFlag must mark interrupted=true");
}

// ============================================================================
// 17. Fixed Start Cell (Hold-and-Replan 问号‧转/风化‧转/烟雾极速重算)
// ============================================================================
void testFixedStartCellHoldAndReplan() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.fixedStartCell = b.index(2, 3); // Finger already held at (2, 3)!
    r.maxSteps = 22;
    r.beamWidth = 140;
    r.maxTimeMs = 200;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "fixedStartCell no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.start == (CellCoord{2, 3}), "must strictly start at fixedStartCell (2,3)");
    require(best.simulation.totalCombo >= 6, "fixedStartCell should still achieve high combo");
    std::cout << "       Hold-and-Replan (2,3): " << int(best.simulation.totalCombo)
              << "C, steps=" << int(best.metrics.stepCount) << ", time=" << out.elapsedMs << "ms\n";
}

// ============================================================================
// 18. Cross-Element Compatible Merge Mask (跨属兼珠: 水兼心互连消除)
// ============================================================================
void testCrossElementMergeMask() {
    // Only 2 Water + 1 Heart in row 0 -> unmatchable normally, matches when Water <-> Heart compatible!
    BoardState b = BoardState::fromString(
        "WWHFLG"
        "FLGDLF"
        "DLGFGD"
        "FGDLFG"
        "LFGDFL",
        6, 5
    );
    RuleConfig r;
    const uint8_t whMask = elementBit(Element::Water) | elementBit(Element::Heart);
    r.compatibleLinkMask[static_cast<uint8_t>(Element::Water)] = whMask;
    r.compatibleLinkMask[static_cast<uint8_t>(Element::Heart)] = whMask;

    auto sim = simulateBoard(b, r);
    require(sim.firstWaveCombo >= 1, "W-W-H must eliminate as 1 merged combo");
    require((sim.firstWaveElementsMask & whMask) == whMask, "merged cluster must credit BOTH Water and Heart elements");
}

// ============================================================================
// 19. Dual-Element Equal Combos (双属首消 Combo 同等盾: 首消光暗同等)
// ============================================================================
void testDualElementEqualCombos() {
    BoardState b = BoardState::fromString(
        "LLDDWF"
        "WFLDFG"
        "GLWDHF"
        "FDLGWH"
        "HWGFDL",
        6, 5
    );
    RuleConfig r;
    r.requireEqualPairCombos = true;
    r.equalPairElemA = static_cast<uint8_t>(Element::Light);
    r.equalPairElemB = static_cast<uint8_t>(Element::Dark);
    r.equalPairMustBeNonZero = true;
    r.maxSteps = 20;
    r.beamWidth = 180;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "equal pair combos no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "Light == Dark non-zero first-wave combo shield must pass");
    const uint8_t cL = best.simulation.firstWaveElementCombos[static_cast<uint8_t>(Element::Light)];
    const uint8_t cD = best.simulation.firstWaveElementCombos[static_cast<uint8_t>(Element::Dark)];
    require(cL > 0 && cL == cD, "Light and Dark first-wave combos must be equal and > 0");
}

// ============================================================================
// 20. Same-Element Multi-Combo Shield (同属连击盾: 首消 >= 3 组暗)
// ============================================================================
void testSameElementMultiCombo() {
    BoardState b = BoardState::fromString(
        "DWDWDF"
        "FDGDFG"
        "DLDWDH"
        "WFDGDW"
        "GFDLDH",
        6, 5
    );
    RuleConfig r;
    r.requiredSameElementCombos = 3;
    r.sameElementComboTarget = static_cast<int8_t>(Element::Dark);
    r.maxSteps = 24;
    r.beamWidth = 220;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "same-element multi-combo no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "must achieve >= 3 Dark first-wave combos");
    require(best.simulation.firstWaveElementCombos[static_cast<uint8_t>(Element::Dark)] >= 3, "Dark first-wave combos >= 3");
}

// ============================================================================
// 21. Second-Wave Cascade Shield (次批消除盾: 第2波天降至少 1C)
// ============================================================================
void testSecondWaveCascadeShield() {
    BoardState b = BoardState::fromString(
        "FFGLDH"
        "WWWDGF"
        "FGLDHW"
        "GLDHWF"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.minSecondWaveCombo = 1;
    r.maxSteps = 16;
    r.beamWidth = 180;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "second wave cascade no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "second wave cascade shield must be satisfied");
    require(best.simulation.secondWaveCombo >= 1, "secondWaveCombo must be >= 1");
}

// ============================================================================
// 22. Purgatory Fire (炼狱之火禁碰) + Foam (泡沫消四邻破裂) + Sleep (沉睡触碰唤醒)
// ============================================================================
void testNewHazardsPurgatoryFoamSleep() {
    // 1) Sleep orb unmatchable until woken up
    BoardState bs = BoardState::fromString(
        "WWWFLG"
        "FLGDLH"
        "DLHFGD"
        "FGDHLF"
        "HLFDGH",
        6, 5
    );
    bs.at(0, 1).hazards |= HazardBits::Sleep;
    RuleConfig rs;
    require(simulateBoard(bs, rs).firstWaveCombo == 0, "Unwoken Sleep orb must block match");

    // 2) Purgatory Fire hard block + Foam 4-neighbor pop!
    BoardState bf = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    bf.at(1, 2).hazards |= HazardBits::PurgatoryFire; // Instant death - must NEVER enter
    bf.at(2, 3).hazards |= HazardBits::Foam;          // Must eliminate 4-neighbor to burst
    bf.rebuildMasks();

    RuleConfig rf;
    rf.mustClearFoam = true;
    rf.maxSteps = 20;
    rf.beamWidth = 180;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(bf, rf);
    require(!out.solutions.empty(), "purgatory+foam no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(bf, rf, best);
    require(best.evaluation.allHardConstraintsMet, "must burst Foam without touching PurgatoryFire");
    require(best.simulation.foamRemaining == 0, "Foam orb must be popped via 4-neighbor match");
}

// ============================================================================
// 23. BarrierFirstWaveOnly (结界地型) + SealedStone (封界石阵)
// ============================================================================
void testBarrierAndSealedStoneTerrains() {
    // SealedStone prevents elimination inside cell (0,1)
    BoardState b1 = BoardState::fromString(
        "WWWFLG"
        "FLGDLH"
        "DLHFGD"
        "FGDHLF"
        "HLFDGH",
        6, 5
    );
    b1.setTileFlag(0, 1, TileFlagBits::SealedStone);
    b1.rebuildMasks();
    RuleConfig r;
    require(simulateBoard(b1, r).firstWaveCombo == 0, "SealedStone must prevent elimination inside tile");

    // BarrierFirstWaveOnly allows Wave 1 elimination, but blocks Wave 2 cascade inside the barrier tile!
    BoardState b2 = BoardState::fromString(
        "FFGLDH"
        "WWWDGF"
        "FGLDHW"
        "DLHGWF"
        "GHFDLW",
        6, 5
    );
    // Normally col 0 has F(0,0), W(1,0), F(2,0) -> after W(1,0..2) clears in Wave 1, F(0,0) drops to (1,0),
    // wait: put F(0,0), F(1,0), W(2,0), F(3,0) so after Wave 1 WWW on row 2 clears, F(0,0),F(1,0),F(3,0) form Wave 2 FFF at col 0!
    BoardState b3 = BoardState::fromString(
        "FGLDHW"
        "FHDWLG"
        "WWWLDF"
        "FDLGWH"
        "GHWFDL",
        6, 5
    );
    // Without barrier, Wave 2 matches F at (1,0),(2,0),(3,0)
    auto simNoBar = simulateBoard(b3, r);
    require(simNoBar.secondWaveCombo >= 1, "Baseline board must have Wave 2 FFF cascade");
    // Put BarrierFirstWaveOnly at (2,0) -> blocks Wave 2 match!
    b3.setTileFlag(2, 0, TileFlagBits::BarrierFirstWaveOnly);
    b3.rebuildMasks();
    auto simWithBar = simulateBoard(b3, r);
    require(simWithBar.secondWaveCombo == 0, "BarrierFirstWaveOnly must block Wave 2 elimination");
}

// ============================================================================
// 24. Character Orb Pass + End Terrain (角色符石通行与停放地型)
// ============================================================================
void testCharOrbPassAndEndTerrain() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    b.setTileFlag(2, 1, TileFlagBits::CharOrbPass);
    b.setTileFlag(4, 3, TileFlagBits::CharOrbEnd);
    b.rebuildMasks();

    RuleConfig r;
    r.useCharacterOrb = true;
    r.characterOrbCol = 0;
    r.characterOrbElement = Element::Water;
    r.requireCharOrbPass = true;
    r.requireCharOrbEnd = true;
    r.maxSteps = 18;
    r.beamWidth = 180;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "charOrb pass+end no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "CharOrb must pass (2,1) and end at (4,3)");
    require(best.waypoints.back() == (CellCoord{4, 3}), "CharOrb must end at (4,3)");
    require((best.metrics.charOrbVisitedMask & bitOf(b.index(2, 1))) != 0, "CharOrb must pass through (2,1)");
}

// ============================================================================
// 25. Trail Corrosion Self-Avoiding Walk + Unique Cells Goal (轨迹腐蚀走满 K 格)
// ============================================================================
void testTrailCorrosionUniqueCells() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.trailCorrosionEnabled = true;
    r.requiredUniqueCellsVisited = 14;
    r.maxSteps = 16;
    r.beamWidth = 180;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "trail corrosion no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "must visit >= 14 unique cells without revisiting");
    require(best.metrics.uniqueCellsVisited >= 14, "uniqueCellsVisited >= 14");
    // Verify strictly self-avoiding waypoints
    uint64_t seen = 0;
    for (const auto& wp : best.waypoints) {
        const uint64_t bit = bitOf(b.index(wp.row, wp.col));
        require((seen & bit) == 0, "trailCorrosion path revisited a cell!");
        seen |= bit;
    }
}

// ============================================================================
// 26. Race Orb Shield (种族符石首消盾)
// ============================================================================
void testRaceOrbShield() {
    BoardState b = BoardState::fromString(
        "W*FGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.requiredRaceElementsMask = elementBit(Element::Water);
    r.maxSteps = 18;
    r.beamWidth = 180;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "race orb shield no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "Water Race Orb shield must be met");
    require((best.simulation.firstWaveRaceElementsMask & elementBit(Element::Water)) != 0, "Water Race orb must clear in wave 1");
}

// ============================================================================
// 27. Exact First-Wave 4C on Real Screenshot Board (MUST NEVER return 0 steps!)
// ============================================================================
void testExactFirst4CRealBoardNeverZeroSteps() {
    // Exact 6x5 board from real user battle screenshot (Screenshot 4)
    BoardState b = BoardState::fromString(
        "GDDLFW"
        "LGLDWG"
        "DHFWDW"
        "FDHFGL"
        "DGWHFF",
        6, 5
    );
    PuzzleSolver solver;

    // Case A: Standard orthogonal matching
    RuleConfig r1;
    r1.exactFirstWaveCombo = 4;
    r1.maxSteps = 30;
    r1.beamWidth = 180;
    SolveOutput out1 = solver.solve(b, r1);
    require(!out1.solutions.empty(), "real board exact first 4C no solution");
    const auto& best1 = out1.solutions[0];
    verifyPathReplay(b, r1, best1);
    require(best1.metrics.stepCount >= 1, "exact first 4C must move >= 1 step");
    require(best1.evaluation.allHardConstraintsMet, "exact first 4C hard constraint must be met");
    require(best1.simulation.firstWaveCombo == 4, "must achieve exact 4 first-wave combos");

    // Case B: Even with allowDiagonalMatch=true (where depth-0 already has 4 diagonal combos!), MUST move >= 1 step!
    RuleConfig r2 = r1;
    r2.allowDiagonalMatch = true;
    SolveOutput out2 = solver.solve(b, r2);
    require(!out2.solutions.empty(), "diag-match exact first 4C no solution");
    const auto& best2 = out2.solutions[0];
    verifyPathReplay(b, r2, best2);
    require(best2.metrics.stepCount >= 1, "diag-match exact first 4C must NEVER return 0 steps!");
    require(best2.evaluation.allHardConstraintsMet, "diag-match exact first 4C must be met");
    require(best2.simulation.firstWaveCombo == 4, "diag-match must achieve exact 4 first-wave combos");
}

// ============================================================================
// 28. Zero Combo Empty Turn (0C 空转: MUST move >= 1 step with 0 combos)
// ============================================================================
void testZeroComboRequiresRealMove() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.exactTotalCombo = 0;
    r.maxSteps = 6;
    r.earlyStopOnGoal = true;
    r.beamWidth = 120;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "0C empty turn no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.metrics.stepCount >= 1, "0C empty turn must still swipe >= 1 step to pass turn");
    require(best.simulation.totalCombo == 0, "0C empty turn must have 0 combos");
    require(best.evaluation.allHardConstraintsMet, "0C empty turn must meet all hard constraints");
}

// ============================================================================
// 29. Per-Color Independent Enchanted Shield (水普通 + 火强化)
// ============================================================================
void testPerColorIndependentEnchanted() {
    BoardState b = BoardState::fromString(
        "WF+GLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    // Require Water (normal OK) + Fire (MUST be Enchanted!)
    r.requiredFirstWaveElementsMask = elementBit(Element::Water) | elementBit(Element::Fire);
    r.requiredTotalElementsMask = elementBit(Element::Water) | elementBit(Element::Fire);
    r.requiredEnchantedElementsMask = elementBit(Element::Fire);
    r.maxSteps = 20;
    r.beamWidth = 180;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "per-color enchanted no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "Water normal + Fire enchanted must be satisfied");
    require((best.simulation.firstWaveElementsMask & elementBit(Element::Water)) != 0, "Water must match");
    require((best.simulation.totalEnchantedElementsMask & elementBit(Element::Fire)) != 0, "Fire enchanted must match");
}

// ============================================================================
// 30. Special Orb Shields (Any Enchanted + Avoid Weakened + Char Orb 1st Wave)
// ============================================================================
void testSpecialOrbShields() {
    BoardState b = BoardState::fromString(
        "W+FGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    // Mark a Wood orb as Weakened (must NOT be eliminated!)
    b.at(2, 0).variant = Variant::Weakened;
    b.rebuildMasks();

    RuleConfig r;
    r.requireAnyEnchantedOrb = true;
    r.forbidWeakenedOrbs = true;
    r.requireCharOrbFirstWave = true;
    r.maxSteps = 20;
    r.beamWidth = 200;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "special orb shields no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "AnyEnchanted + ForbidWeakened + CharOrb1st must all be met");
    require(best.simulation.firstWaveEnchantedElementsMask != 0, "must match an enchanted orb in wave 1");
    require(best.simulation.weakenedEliminated == 0, "must NOT eliminate weakened orb");
    require((best.simulation.firstWaveClearedMask & bitOf(best.metrics.charOrbEndIdx)) != 0, "held/char orb must clear in wave 1");
}

// ============================================================================
// 31. Dual-Cross Specific Colors Shield (水+火双十字)
// ============================================================================
void testDualCrossSpecificColors() {
    BoardState b = BoardState::fromString(
        "WWWFGG"
        "WFWWFG"
        "LWFGLF"
        "DFWFFD"
        "HHFFLL",
        6, 5
    );
    RuleConfig r;
    r.crossShield.requiredCount = 2;
    r.crossShield.requireDistinctColors = true;
    r.crossShield.requiredElementsMask = elementBit(Element::Water) | elementBit(Element::Fire);
    r.maxSteps = 32;
    r.beamWidth = 300;
    r.maxTimeMs = 1200;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "dual cross specific colors no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "Water+Fire dual cross must succeed");
}

// ============================================================================
// 32. Composite Multi-Shield Stack (首消>=4C + >=3属 + 反首消心 + 任意强化)
// ============================================================================
void testCompositeMultiShieldStack() {
    BoardState b = BoardState::fromString(
        "W+FGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.minFirstWaveCombo = 4;
    r.minDistinctElements = 3;
    r.elementRules[static_cast<size_t>(Element::Heart)].timing = MatchTiming::ForbidFirstWaveAllowCascade;
    r.requireAnyEnchantedOrb = true;
    r.maxSteps = 26;
    r.beamWidth = 260;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "composite multi-shield no solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    require(best.evaluation.allHardConstraintsMet, "composite multi-shield stack must all be met");
    require(best.simulation.firstWaveCombo >= 4, "firstWaveCombo >= 4");
    require((best.simulation.firstWaveElementsMask & elementBit(Element::Heart)) == 0, "Heart must not match wave 1");
    require(best.simulation.firstWaveEnchantedElementsMask != 0, "must match enchanted orb");
}

// ============================================================================
// 33. Hold-and-Replan Ultra-Fast Fixed-Start Budget & In-Worker Hard Timeout
// ============================================================================
void testHoldAndReplanFastBudgetAndHardTimeout() {
    // Realistic 8C-capable 6x5 board (same distribution as Test #1)
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLWFG"
        "LWDFGL"
        "GWFLHW"
        "FLDGWH",
        6, 5
    );
    // Case A: Standard Hold-and-Replan real-time config (fixedStartCell, topK=1, beam=180, maxTimeMs=135)
    RuleConfig rFast;
    rFast.fixedStartCell = 14; // Row 2, Col 2
    rFast.maxSteps = 28;
    rFast.beamWidth = 180;
    rFast.maxTimeMs = 135;
    rFast.topK = 1;
    rFast.allowDiagonalMove = true;

    PuzzleSolver solver;
    SolveOutput outFast = solver.solve(b, rFast);
    require(outFast.solutions.size() == 1, "topK=1 fast mode must return exactly 1 best solution");
    const auto& sol = outFast.solutions[0];
    verifyPathReplay(b, rFast, sol);
    std::cout << "       Hold-Replan Fast (2,2): " << int(sol.simulation.totalCombo)
              << "C, steps=" << sol.metrics.stepCount
              << ", time=" << outFast.elapsedMs << "ms, evals=" << outFast.statesEvaluated << "\n";
    require(sol.start.row == 2 && sol.start.col == 2, "must start at exact held cell (2,2)");
    require(sol.simulation.totalCombo >= 7, "fast fixed-start Hold-and-Replan should still find >= 7C");
    require(outFast.elapsedMs <= 160.0, "fast Hold-and-Replan must finish promptly well inside deadline");

    // Case B: Hard in-worker timeout check even under massive beamWidth=600 & maxSteps=60 with maxTimeMs=35
    RuleConfig rTight;
    rTight.fixedStartCell = 14;
    rTight.maxSteps = 60;
    rTight.beamWidth = 600;
    rTight.maxTimeMs = 35;
    rTight.topK = 1;
    rTight.allowDiagonalMove = true;

    SolveOutput outTight = solver.solve(b, rTight);
    std::cout << "       Hold-Replan HardTimeout(35ms): " << int(outTight.solutions[0].simulation.totalCombo)
              << "C, time=" << outTight.elapsedMs << "ms, timedOut=" << outTight.timedOut << "\n";
    require(!outTight.solutions.empty(), "hard-timed-out solve must still return best solution found so far");
    require(outTight.elapsedMs <= 90.0, "in-worker deadline check must stop promptly near 35ms budget");
}

// -----------------------------------------------------------------------------
// 34. Ordinary Puzzle Shield Semantics & Real 5-Cell Cross Board (4-Way Orthogonal Only)
// -----------------------------------------------------------------------------
void testOrdinaryPuzzleShieldSingleColorAndRealCross() {
    // Part A: Semantic check — Ordinary Puzzle Shield (strictNoExternalTouch = false)
    // MUST REJECT clearing puzzleMask with two different colors, and MUST ACCEPT one
    // single same-color connected Wave-1 group covering puzzleMask even with external orbs!
    BoardState multiColor = BoardState::fromString(
        "WWWFFF"
        "GGLGGL"
        "DDLDDL"
        "HHLHHL"
        "WWLWWL",
        6, 5
    );
    // Mark a 6-cell horizontal puzzle across row 0 (3 Water + 3 Fire)
    for (uint8_t c = 0; c < 6; ++c) multiColor.setTileFlag(0, c, TileFlagBits::Puzzle1);
    multiColor.rebuildMasks();
    RuleConfig rOrd;
    rOrd.puzzle1.enabled = true;
    rOrd.puzzle1.strictNoExternalTouch = false;
    SimulationResult simMulti = simulateBoard(multiColor, rOrd);
    PathMetrics dummyM{};
    dummyM.stepCount = 1;
    const auto evalMulti = evaluateCandidate(multiColor, simMulti, dummyM, rOrd);
    require(!evalMulti.allHardConstraintsMet, "ordinary puzzle shield must REJECT multi-color clear across puzzleMask");
    require((evalMulti.satisfiedFlags & ShieldStatusBits::Puzzle1) == 0, "Puzzle1 flag must NOT be set for multi-color clear");

    // Same-color connected group covering a 5-cell Cross PLUS extra external same-color orbs -> MUST ACCEPT!
    BoardState singleColorPlusExt = BoardState::fromString(
        "FDGHFF"
        "WLLGGG"
        "DDGGGG"
        "DLHHGF"
        "GDDGGD",
        6, 5
    );
    singleColorPlusExt.setTileFlag(1, 4, TileFlagBits::Puzzle1);
    singleColorPlusExt.setTileFlag(2, 3, TileFlagBits::Puzzle1);
    singleColorPlusExt.setTileFlag(2, 4, TileFlagBits::Puzzle1);
    singleColorPlusExt.setTileFlag(2, 5, TileFlagBits::Puzzle1);
    singleColorPlusExt.setTileFlag(3, 4, TileFlagBits::Puzzle1);
    singleColorPlusExt.rebuildMasks();
    SimulationResult simSingle = simulateBoard(singleColorPlusExt, rOrd);
    const auto evalSingle = evaluateCandidate(singleColorPlusExt, simSingle, dummyM, rOrd);
    require(evalSingle.allHardConstraintsMet, "ordinary puzzle shield must ACCEPT single same-color group with extra external orbs");
    require((evalSingle.satisfiedFlags & ShieldStatusBits::Puzzle1) != 0, "Puzzle1 flag must be set");

    // Part B: Exact user real-game board from screen recording with 5-cell Cross at {(1,4),(2,3),(2,4),(2,5),(3,4)}
    // solved with allowDiagonalMove = false (strictly 4-way orthogonal moves!)
    BoardState realBoard = BoardState::fromString(
        "FDGHFF"
        "WLLGWG"
        "DDGHFG"
        "DLHHDF"
        "GDDGGD",
        6, 5
    );
    realBoard.setTileFlag(1, 4, TileFlagBits::Puzzle1);
    realBoard.setTileFlag(2, 3, TileFlagBits::Puzzle1);
    realBoard.setTileFlag(2, 4, TileFlagBits::Puzzle1);
    realBoard.setTileFlag(2, 5, TileFlagBits::Puzzle1);
    realBoard.setTileFlag(3, 4, TileFlagBits::Puzzle1);

    RuleConfig rReal;
    rReal.allowDiagonalMove = false;
    rReal.allowDiagonalMatch = false;
    rReal.maxSteps = 55;
    rReal.beamWidth = 260;
    rReal.maxTimeMs = 800;
    rReal.puzzle1.enabled = true;
    rReal.puzzle1.strictNoExternalTouch = false;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(realBoard, rReal);
    require(!out.solutions.empty(), "real cross puzzle board must produce a solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(realBoard, rReal, best);
    std::cout << "       Real 5-Cell Cross (4-Way Ortho): met=" << best.evaluation.allHardConstraintsMet
              << ", 1st=" << int(best.simulation.firstWaveCombo)
              << "C, total=" << int(best.simulation.totalCombo)
              << "C, steps=" << best.metrics.stepCount
              << ", time=" << out.elapsedMs << "ms\n";
    require(best.evaluation.allHardConstraintsMet, "real 5-cell cross puzzle must be satisfied in 4-way orthogonal mode");
    require((best.evaluation.satisfiedFlags & ShieldStatusBits::Puzzle1) != 0, "Puzzle1 must be satisfied");
    // Double-verify that Wave 1 really has ONE single same-color group covering all 5 puzzle cells!
    const uint64_t crossMask =
        bitOf(cellIndex(1, 4, 6)) | bitOf(cellIndex(2, 3, 6)) | bitOf(cellIndex(2, 4, 6)) |
        bitOf(cellIndex(2, 5, 6)) | bitOf(cellIndex(3, 4, 6));
    bool foundSingleGroup = false;
    for (uint8_t gi = 0; gi < best.simulation.groupCount; ++gi) {
        const auto& g = best.simulation.groups[gi];
        if (g.wave == 1 && (g.cellMask & crossMask) == crossMask) {
            foundSingleGroup = true;
            break;
        }
    }
    require(foundSingleGroup, "solution must contain a single same-color Wave-1 group covering the entire 5-cell Cross");
}

// -----------------------------------------------------------------------------
// 35. Real Strict 5-Cell Inverted-L Puzzle Board (4-Way Orthogonal, Zero External Touch)
// -----------------------------------------------------------------------------
void testRealStrictInvertedLPuzzle4WayOrtho() {
    // Exact real-game board from user's Strict Puzzle Shield video (t_1400ms.png)
    // Strict 5-cell inverted-L at {(1,0),(1,1),(1,2),(2,0),(3,0)}
    BoardState b = BoardState::fromString(
        "HWLLFW"
        "FGWDFH"
        "HGWDDF"
        "FLHWWL"
        "DDLLFL",
        6, 5
    );
    b.setTileFlag(1, 0, TileFlagBits::Puzzle1);
    b.setTileFlag(1, 1, TileFlagBits::Puzzle1);
    b.setTileFlag(1, 2, TileFlagBits::Puzzle1);
    b.setTileFlag(2, 0, TileFlagBits::Puzzle1);
    b.setTileFlag(3, 0, TileFlagBits::Puzzle1);

    RuleConfig r;
    r.allowDiagonalMove = false;
    r.allowDiagonalMatch = false;
    r.maxSteps = 55;
    r.beamWidth = 260;
    r.maxTimeMs = 800;
    r.puzzle1.enabled = true;
    r.puzzle1.strictNoExternalTouch = true;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "strict L puzzle must find a solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    std::cout << "       Real Strict 5-Cell L (4-Way Ortho): met=" << best.evaluation.allHardConstraintsMet
              << ", 1st=" << int(best.simulation.firstWaveCombo)
              << "C, total=" << int(best.simulation.totalCombo)
              << "C, steps=" << best.metrics.stepCount
              << ", time=" << out.elapsedMs << "ms\n";
    require(best.evaluation.allHardConstraintsMet, "real strict 5-cell L puzzle must satisfy strictNoExternalTouch=true");
    require((best.evaluation.satisfiedFlags & ShieldStatusBits::Puzzle1) != 0, "Puzzle1 flag must be set");
}

// -----------------------------------------------------------------------------
// 36. Real Traffic Light Stage (GO Start (1,5) -> Red Burst End (3,2) + Anti-First-Wave Dark + >=6C, 4-Way Ortho)
// -----------------------------------------------------------------------------
void testRealTrafficLightStageWithCompositeShields() {
    // Exact 6x5 board from user's real ToS Traffic Light screenshot (media_1795321079536_b0156749.jpg)
    BoardState b = BoardState::fromString(
        "DHWLWG"
        "GFDGGL"
        "LDGWDD"
        "FWLHLW"
        "GWGGLD",
        6, 5
    );
    b.setTileFlag(1, 5, TileFlagBits::StartAllowed);
    b.setTileFlag(3, 2, TileFlagBits::EndRequired);

    RuleConfig r;
    r.allowDiagonalMove = false;
    r.allowDiagonalMatch = false;
    // Deliberately set conflicting BottomLeft preset to verify stage GO startMask cleanly overrides it!
    r.startPreset = StartPointPreset::BottomLeft;
    r.maxSteps = 100;
    r.beamWidth = 380;
    r.maxTimeMs = 800;
    r.minTotalCombo = 6;
    r.elementRules[static_cast<uint8_t>(Element::Dark)].timing = MatchTiming::ForbidFirstWaveAllowCascade;

    PuzzleSolver solver;
    SolveOutput out = solver.solve(b, r);
    require(!out.solutions.empty(), "real traffic light stage must find a solution");
    const auto& best = out.solutions[0];
    verifyPathReplay(b, r, best);
    std::cout << "       Real Traffic Light (1,5)->(3,2) + Anti1stDark + >=6C: met=" << best.evaluation.allHardConstraintsMet
              << ", start=(" << int(best.start.row) << "," << int(best.start.col)
              << ")->end=(" << int(best.waypoints.back().row) << "," << int(best.waypoints.back().col)
              << "), 1st=" << int(best.simulation.firstWaveCombo)
              << "C, total=" << int(best.simulation.totalCombo)
              << "C, steps=" << best.metrics.stepCount
              << ", time=" << out.elapsedMs << "ms\n";
    require(best.start == (CellCoord{1, 5}), "path must start at GO cell (1,5)");
    require(best.waypoints.back() == (CellCoord{3, 2}), "path must end at Red-Burst cell (3,2)");
    require(best.simulation.totalCombo >= 6, "path must reach >= 6 Combo");
    require((best.simulation.firstWaveElementsMask & elementBit(Element::Dark)) == 0, "must NOT match Dark in wave 1");
    require(best.evaluation.allHardConstraintsMet, "all composite Traffic Light + Anti1stDark + >=6C hard constraints must be met");
    require((best.evaluation.satisfiedFlags & ShieldStatusBits::TrafficLight) != 0, "TrafficLight flag must be satisfied");
}

// -----------------------------------------------------------------------------
// 37. Official Encyclopedia #57/#58: Wave-1 Same-Kind & Distinct-Kind (Heart Wildcard)
// -----------------------------------------------------------------------------
void testWave1SameAndDistinctKindHeartWildcard() {
    // Part A: Official #57 Slide 2 semantics (Water + multiple Heart groups = PASS; adding Fire = FAIL)
    BoardState bSameOk = BoardState::fromString(
        "WWWGFD"
        "HHHLGF"
        "WWWDFG"
        "HHHGLD"
        "GLDFGL",
        6, 5
    );
    RuleConfig rSame;
    rSame.exactFirstWaveCombo = 4;
    rSame.wave1KindMode = Wave1KindMode::SameKindHeartWild;
    PathMetrics dm{}; dm.stepCount = 1;
    auto evSameOk = evaluateCandidate(bSameOk, simulateBoard(bSameOk, rSame), dm, rSame);
    require(evSameOk.allHardConstraintsMet, "#57 SameKind must ACCEPT 2 Water + 2 Heart wildcard groups");

    BoardState bSameBad = BoardState::fromString(
        "WWWGFD"
        "FFFLGF"
        "WWWDFG"
        "HHHGLD"
        "GLDFGL",
        6, 5
    );
    auto evSameBad = evaluateCandidate(bSameBad, simulateBoard(bSameBad, rSame), dm, rSame);
    require(!evSameBad.allHardConstraintsMet, "#57 SameKind must REJECT mixing Water + Fire");

    // Part B: Official #58 Slide 2 semantics (Water + Wood + Dark + TWO Heart groups = PASS; 2 Light groups = FAIL)
    BoardState bDistOk = BoardState::fromString(
        "WWWFDL"
        "HHHLFG"
        "GGGFLD"
        "HHHDGL"
        "DDDFGF",
        6, 5
    );
    RuleConfig rDist;
    rDist.exactFirstWaveCombo = 5;
    rDist.wave1KindMode = Wave1KindMode::DistinctKindHeartWild;
    auto evDistOk = evaluateCandidate(bDistOk, simulateBoard(bDistOk, rDist), dm, rDist);
    require(evDistOk.allHardConstraintsMet, "#58 DistinctKind must ACCEPT 3 distinct elements + 2 Heart wildcard groups");

    BoardState bDistBad = BoardState::fromString(
        "WWWFDL"
        "LLLGFG"
        "GGGFLD"
        "LLLDGL"
        "DDDFGF",
        6, 5
    );
    auto evDistBad = evaluateCandidate(bDistBad, simulateBoard(bDistBad, rDist), dm, rDist);
    require(!evDistBad.allHardConstraintsMet, "#58 DistinctKind must REJECT duplicate non-Heart Light groups");

    // Part C: Live solver solves #58 DistinctKind (exact 4 Wave-1 distinct groups) from scrambled board!
    BoardState bSolve = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig rSolve;
    rSolve.exactFirstWaveCombo = 4;
    rSolve.wave1KindMode = Wave1KindMode::DistinctKindHeartWild;
    rSolve.maxSteps = 22;
    rSolve.beamWidth = 220;
    PuzzleSolver solver;
    auto out = solver.solve(bSolve, rSolve);
    require(!out.solutions.empty() && out.solutions[0].evaluation.allHardConstraintsMet,
            "solver must satisfy #58 Wave-1 Distinct-Kind exact 4C");
    verifyPathReplay(bSolve, rSolve, out.solutions[0]);
    require(out.solutions[0].simulation.firstWaveCombo == 4, "firstWaveCombo must be exact 4");
}

// -----------------------------------------------------------------------------
// 38. Official Encyclopedia #18: First-Wave Elemental Groups >= N (Excludes Heart!)
// -----------------------------------------------------------------------------
void testFirstWaveElementalComboExcludesHeart() {
    // 4 elemental matches + 1 Heart match = 5 total Wave-1 combos, but ONLY 4 elemental combos -> MUST FAIL >=5!
    BoardState b = BoardState::fromString(
        "WWWFDL"
        "FFFLDG"
        "GGGDFL"
        "LLLFGD"
        "HHHDGL",
        6, 5
    );
    RuleConfig r;
    r.minFirstWaveElementalCombo = 5;
    PathMetrics dm{}; dm.stepCount = 1;
    auto sim = simulateBoard(b, r);
    require(sim.firstWaveCombo == 5, "board has 5 total Wave-1 combos (4 elem + 1 heart)");
    auto ev = evaluateCandidate(b, sim, dm, r);
    require(!ev.allHardConstraintsMet, "#18 must REJECT 4 elemental + 1 Heart when >=5 elemental groups required");

    // Solver must find >=5 non-Heart Wave-1 groups!
    BoardState b2 = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    r.maxSteps = 24;
    r.beamWidth = 220;
    PuzzleSolver solver;
    auto out = solver.solve(b2, r);
    require(!out.solutions.empty() && out.solutions[0].evaluation.allHardConstraintsMet, "solver must reach >=5 non-Heart Wave-1 combos");
    verifyPathReplay(b2, r, out.solutions[0]);
    uint8_t elemCnt = 0;
    for (uint8_t e = 0; e < 5; ++e) elemCnt += out.solutions[0].simulation.firstWaveElementCombos[e];
    require(elemCnt >= 5, "non-Heart Wave-1 combos must be >= 5");
}

// -----------------------------------------------------------------------------
// 39. Official Encyclopedia #9: 3-Element Equal Wave-1 Combos (水火木連擊相等盾)
// -----------------------------------------------------------------------------
void testThreeElementEqualWave1Combos() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.equalComboElementsMask = elementBit(Element::Water) | elementBit(Element::Fire) | elementBit(Element::Wood);
    r.equalPairMustBeNonZero = true;
    r.maxSteps = 24;
    r.beamWidth = 220;
    PuzzleSolver solver;
    auto out = solver.solve(b, r);
    require(!out.solutions.empty() && out.solutions[0].evaluation.allHardConstraintsMet, "3-element equal Wave-1 combos must pass");
    verifyPathReplay(b, r, out.solutions[0]);
    const auto& c = out.solutions[0].simulation.firstWaveElementCombos;
    require(c[0] > 0 && c[0] == c[1] && c[1] == c[2], "Water == Fire == Wood > 0 in Wave 1");
}

// -----------------------------------------------------------------------------
// 40. Official Encyclopedia #15/#16: Strict Exact 5-Orb L-Shape & T-Shape Shields
// -----------------------------------------------------------------------------
void testStrictExact5OrbLTShapes() {
    // Oversized 7-orb L-shape (4x4 leg) MUST FAIL strictShape=true per Official #16 Slide 2 Red-X!
    BoardState bOversizedL = BoardState::fromString(
        "WFLGDH"
        "WGFDLH"
        "WLDHFG"
        "WWWWFD"
        "GLDHFL",
        6, 5
    );
    RuleConfig rL;
    rL.shapeShield.requireLCount = 1;
    rL.shapeShield.strictShape = true;
    PathMetrics dm{}; dm.stepCount = 1;
    auto evBad = evaluateCandidate(bOversizedL, simulateBoard(bOversizedL, rL), dm, rL);
    require(!evBad.allHardConstraintsMet, "#16 strict L-shape must REJECT oversized 7-orb L");

    // Live solver builds exact 5-orb strict T-shape (#15)!
    BoardState bSolveT = BoardState::fromString(
        "WFWGLD"
        "FWWLDH"
        "GFWLDF"
        "LDWHFG"
        "DHWFDL",
        6, 5
    );
    RuleConfig rT;
    rT.shapeShield.requireTCount = 1;
    rT.shapeShield.strictShape = true;
    rT.maxSteps = 22;
    rT.beamWidth = 240;
    PuzzleSolver solver;
    auto outT = solver.solve(bSolveT, rT);
    require(!outT.solutions.empty() && outT.solutions[0].evaluation.allHardConstraintsMet,
            "#15 strict exact 5-orb T-shape must be solved");
    verifyPathReplay(bSolveT, rT, outT.solutions[0]);
    bool hasStrictT5 = false;
    for (uint8_t gi = 0; gi < outT.solutions[0].simulation.groupCount; ++gi) {
        const auto& g = outT.solutions[0].simulation.groups[gi];
        if (g.wave == 1 && g.isTShape && g.orbCount == 5) hasStrictT5 = true;
    }
    require(hasStrictT5, "solution must contain exact 5-orb Wave-1 T-shape");
}

// -----------------------------------------------------------------------------
// 41. Official Encyclopedia #60: Step-Count Range Shield (步數盾 [12..16])
// -----------------------------------------------------------------------------
void testStepCountRangeShield() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig r;
    r.minSteps = 12;
    r.maxSteps = 16;
    r.beamWidth = 180;
    PuzzleSolver solver;
    auto out = solver.solve(b, r);
    require(!out.solutions.empty() && out.solutions[0].evaluation.allHardConstraintsMet, "step range [12..16] must succeed");
    verifyPathReplay(b, r, out.solutions[0]);
    require(out.solutions[0].metrics.stepCount >= 12 && out.solutions[0].metrics.stepCount <= 16,
            "step count must lie inside [12..16]");
}

// -----------------------------------------------------------------------------
// 42. Official Encyclopedia #31/#32: BlackHole & Bloodbath Passable, Forbidden End!
// -----------------------------------------------------------------------------
void testBlackHoleAndBloodbathPassableEndForbidden() {
    // 1x6 bottleneck: row 1 is all Storm EXCEPT (1,2) which is BlackHole — path MUST pass THROUGH BlackHole
    // from start (0,2) to end (2,2) and MUST NOT end on BlackHole or EndForbidden!
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    for (uint8_t c = 0; c < 6; ++c) {
        if (c != 2) b.setTileFlag(1, c, TileFlagBits::Storm);
    }
    b.setTileFlag(1, 2, TileFlagBits::BlackHole);    // #32: ONLY bridge from top half to bottom half!
    b.setTileFlag(2, 3, TileFlagBits::EndForbidden); // #31: Bloodbath forbidden end!
    b.setTileFlag(0, 2, TileFlagBits::StartAllowed);
    b.setTileFlag(2, 2, TileFlagBits::EndRequired);
    b.rebuildMasks();

    RuleConfig r;
    r.allowDiagonalMove = false;
    r.maxSteps = 12;
    r.beamWidth = 160;
    PuzzleSolver solver;
    auto out = solver.solve(b, r);
    require(!out.solutions.empty() && out.solutions[0].evaluation.allHardConstraintsMet,
            "path must legally traverse through BlackHole (1,2) to reach (2,2)");
    verifyPathReplay(b, r, out.solutions[0]);
    require(out.solutions[0].waypoints.back() == (CellCoord{2, 2}), "must end safely at (2,2)");
}

// -----------------------------------------------------------------------------
// 43. Official Encyclopedia #28/#96 ScorchingTile + #23 Unmatchable Elements (符石禁咒)
// -----------------------------------------------------------------------------
void testScorchingTileAndUnmatchableElement() {
    // #23 符石禁咒: Water cannot match even with 3-in-a-row!
    BoardState bBan = BoardState::fromString(
        "WWWFDL"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    RuleConfig rBan;
    rBan.unmatchableElementsMask = elementBit(Element::Water);
    auto simBan = simulateBoard(bBan, rBan);
    require((simBan.totalElementsMask & elementBit(Element::Water)) == 0,
            "#23 unmatchableElementsMask must prevent WWW from matching");

    // #28/#96 灼熱地型: clearing inside ScorchingTile triggers scorchingOrbsCleared violation,
    // while moving through it without matching inside succeeds!
    BoardState bScorch = BoardState::fromString(
        "WWWFDL"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    bScorch.setTileFlag(0, 1, TileFlagBits::ScorchingTile);
    bScorch.rebuildMasks();
    RuleConfig rS;
    rS.maxBurningTileHurts = 0;
    auto simS = simulateBoard(bScorch, rS);
    require(simS.scorchingOrbsCleared == 1, "clearing orb on ScorchingTile must increment scorchingOrbsCleared");
    PathMetrics dm{}; dm.stepCount = 1;
    require(!evaluateCandidate(bScorch, simS, dm, rS).allHardConstraintsMet,
            "clearing on ScorchingTile with maxBurningTileHurts=0 must fail BurningLimit");
}

// -----------------------------------------------------------------------------
// 44. Official Encyclopedia #66/#81 disableCascades + #78 CharOrbPass Terrain
// -----------------------------------------------------------------------------
void testDisableCascadesAndCharOrbPassRule() {
    // Baseline board has a Wave-2 Fire cascade after Wave-1 Water clears
    BoardState b = BoardState::fromString(
        "FGLDHW"
        "FHDWLG"
        "WWWLDF"
        "FDLGWH"
        "GHWFDL",
        6, 5
    );
    RuleConfig rOn;
    require(simulateBoard(b, rOn).secondWaveCombo >= 1, "baseline must cascade in Wave 2");
    RuleConfig rOff;
    rOff.disableCascades = true;
    auto simNoCas = simulateBoard(b, rOff);
    require(simNoCas.firstWaveCombo == 1 && simNoCas.secondWaveCombo == 0 && simNoCas.totalCombo == 1,
            "#66/#81 disableCascades must physically stop all Wave >= 2 cascades");

    // #78 CharOrbPass terrain: orbs inside cannot match!
    BoardState bCp = BoardState::fromString(
        "WWWFDL"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    bCp.setTileFlag(0, 1, TileFlagBits::CharOrbPass);
    bCp.rebuildMasks();
    require(simulateBoard(bCp, rOn).firstWaveCombo == 0, "#78 CharOrbPass terrain must prevent matching inside tile");
}

// -----------------------------------------------------------------------------
// 45. Official Runestone Status #3/#4/#5: IceSealed No-Peel + Per-Wave 1-Layer Cap on Petrified & Coagulated
// -----------------------------------------------------------------------------
void testIceSealedAndPerWavePetrifiedCoagulatedCap() {
    // (1,1) is surrounded on TOP by WWW (row 0) AND on BOTTOM by FFF (row 2) — 2 separate Wave-1 combos!
    BoardState b = BoardState::fromString(
        "WWWGDH"
        "GLDGHW"
        "FFFHGD"
        "DLHGWF"
        "GHFDLW",
        6, 5
    );
    RuleConfig r;

    // 1) IceSealed (#3): thaws after 3 turns, NOT by 4-neighbor matches -> 0 adjacent peel hits!
    BoardState bIce = b;
    bIce.at(1, 1).hazards |= HazardBits::IceSealed;
    auto simIce = simulateBoard(bIce, r);
    require(simIce.petrifiedAdjacentHits == 0 && simIce.coagulatedAdjacentHits == 0,
            "#3 IceSealed must NOT receive 4-neighbor peel hits");

    // 2) Petrified (#4) & Coagulated (#5): 2 Wave-1 combos touching the SAME orb peel STRICTLY 1 layer!
    BoardState bPet = b;
    bPet.at(1, 1).hazards |= HazardBits::Petrified;
    auto simPet = simulateBoard(bPet, r);
    require(simPet.petrifiedAdjacentHits == 1,
            "#4 Petrified must cap Wave-1 orthogonal peel to strictly 1 layer even with 2 adjacent Wave-1 combos");

    BoardState bCoag = b;
    bCoag.at(1, 1).hazards |= HazardBits::Coagulated;
    require(bCoag.at(1, 1).isImmovable() && bCoag.at(1, 1).isUnmatchableHazard(),
            "#5 Coagulated must be both immovable and unmatchable");
    auto simCoag = simulateBoard(bCoag, r);
    require(simCoag.coagulatedAdjacentHits == 1,
            "#5 Coagulated must cap Wave-1 orthogonal peel to strictly 1 layer");

    // 3) Wave-1 + Wave-2 cascade next to the same Coagulated orb peels 2 layers!
    BoardState b2Wave = BoardState::fromString(
        "FGLDHW"
        "FHDWLG"
        "WWWLDF"
        "FDLGWH"
        "GHWFDL",
        6, 5
    );
    b2Wave.at(2, 1).hazards = HazardBits::None; // ensure row 2 WWW clears in Wave 1
    // Put Coagulated at (3, 1): adjacent to Wave-1 WWW at (2,1) AND Wave-2 FFF at (3,0)!
    b2Wave.at(3, 1).hazards |= HazardBits::Coagulated;
    auto sim2W = simulateBoard(b2Wave, r);
    require(sim2W.secondWaveCombo >= 1 && sim2W.coagulatedAdjacentHits == 2,
            "#5 Coagulated touched in Wave-1 and Wave-2 cascade must peel 2 layers");
}

// -----------------------------------------------------------------------------
// 46. Official Runestone Status #8 BloodDrain All-Wave Clear + #13 Foam Movable & Live-Position Burst
// -----------------------------------------------------------------------------
void testBloodDrainAllWaveAndMovableFoam() {
    // 1) #8 BloodDrain cleared in Wave-2 cascade must count as cleared!
    BoardState bBlood = BoardState::fromString(
        "FGLDHW"
        "FHDWLG"
        "WWWLDF"
        "FDLGWH"
        "GHWFDL",
        6, 5
    );
    bBlood.at(0, 0).hazards |= HazardBits::BloodDrain; // Fire orb drops & clears in Wave-2 FFF!
    RuleConfig r;
    auto simB = simulateBoard(bBlood, r);
    require(simB.secondWaveCombo >= 1 && simB.bloodDrainRemainingFirstWave == 0,
            "#8 BloodDrain cleared in Wave-2 cascade must count as 0 remaining");

    // 2) #13 Foam is MOVABLE during dragging, unmatchable directly, and pops on 4-neighbor match!
    BoardState bFoam = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    bFoam.at(0, 0).hazards |= HazardBits::Foam;
    require(!bFoam.at(0, 0).isImmovable(), "#13 Foam must be movable during orb dragging");
    require(bFoam.at(0, 0).isUnmatchableHazard(), "#13 Foam must NOT be directly matchable");
    RuleConfig rf;
    rf.mustClearFoam = true;
    rf.maxSteps = 18;
    rf.beamWidth = 160;
    PuzzleSolver solver;
    auto outF = solver.solve(bFoam, rf);
    require(!outF.solutions.empty() && outF.solutions[0].evaluation.allHardConstraintsMet,
            "#13 movable Foam must solve cleanly");
    verifyPathReplay(bFoam, rf, outF.solutions[0]);
    require(outF.solutions[0].simulation.foamRemaining == 0, "#13 Foam must burst");
}

// -----------------------------------------------------------------------------
// 47. Official Runestone Status #9 Bomb Touch-Damage Avoidance + #17 MagicOrb (+3 Combo/orb)
// -----------------------------------------------------------------------------
void testBombTouchAvoidanceAndMagicOrbBonus() {
    // 1) #17 MagicOrb: 1 physical combo containing 2 MagicOrbs => totalCombo = 1 + 2*3 = 7!
    BoardState bMagic = BoardState::fromString(
        "WWWFDL"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    bMagic.at(0, 0).hazards |= HazardBits::MagicOrb;
    bMagic.at(0, 1).hazards |= HazardBits::MagicOrb;
    RuleConfig rm;
    auto simM = simulateBoard(bMagic, rm);
    require(simM.firstWaveCombo == 1 && simM.magicOrbsCleared == 2 && simM.totalCombo == 7,
            "#17 MagicOrb must add +3 Combo per cleared MagicOrb (1 + 2*3 = 7C)");

    // 2) #9 Bomb: solver must clear Bomb WITHOUT touching it when maxBurningTileHurts == 0!
    BoardState bBomb = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    bBomb.at(0, 0).hazards |= HazardBits::Bomb;
    RuleConfig rb;
    rb.mustClearBomb = true;
    rb.maxBurningTileHurts = 0;
    rb.maxSteps = 20;
    rb.beamWidth = 180;
    PuzzleSolver solver;
    auto outB = solver.solve(bBomb, rb);
    require(!outB.solutions.empty() && outB.solutions[0].evaluation.allHardConstraintsMet,
            "#9 Bomb must be cleared without touching when maxBurningTileHurts == 0");
    verifyPathReplay(bBomb, rb, outB.solutions[0]);
    require(outB.solutions[0].metrics.burningTileHits == 0, "#9 Bomb path must have 0 touch damage");
    require(outB.solutions[0].simulation.bombRemainingFirstWave == 0, "#9 Bomb must be cleared");
}

// -----------------------------------------------------------------------------
// 48. Official Runestone Status #12 EvilEnergy & #20 BloodThorn Elimination Avoidance
// -----------------------------------------------------------------------------
void testEvilEnergyAndBloodThornAvoidance() {
    BoardState b = BoardState::fromString(
        "WFGLDH"
        "FGLDHW"
        "GLDHWF"
        "LDHWFG"
        "DHWFGL",
        6, 5
    );
    b.at(0, 0).hazards |= HazardBits::EvilEnergy;
    b.at(4, 5).hazards |= HazardBits::BloodThorn;
    RuleConfig r;
    r.forbidWeakenedOrbs = true;
    r.minTotalCombo = 4;
    r.maxSteps = 20;
    r.beamWidth = 180;
    PuzzleSolver solver;
    auto out = solver.solve(b, r);
    require(!out.solutions.empty() && out.solutions[0].evaluation.allHardConstraintsMet,
            "#12/#20 solver must achieve >=4C while avoiding EvilEnergy & BloodThorn");
    verifyPathReplay(b, r, out.solutions[0]);
    require(out.solutions[0].simulation.evilEnergyEliminated == 0, "must NOT eliminate EvilEnergy");
    require(out.solutions[0].simulation.bloodThornEliminated == 0, "must NOT eliminate BloodThorn");
}

} // namespace

int main() {
    std::cout << "=== AutoDora-NEXT C++20 Solver Core Test Suite ===\n";

    runTest("1. Standard 6x5 High-Combo + Top-K Diversity", testStandard6x5HighCombo);
    runTest("2. Expanded 6x8 Weijian (48 cells)", testExpanded6x8Weijian);
    runTest("3. Dynamic Board Sizes (7x6, 5x4, 4x4)", testDynamicBoardSizes);
    runTest("4. Two-Match (2消) & Diagonal Match (斜消)", testTwoMatchAndDiagonalMatch);
    runTest("5. Strict Enchanted Puzzle Shield (严格强化拼图盾)", testStrictEnchantedPuzzleShield);
    runTest("6. Strict 5-Orb Cross Shield (严格十字盾)", testStrictCrossShield);
    runTest("7. Geometry Classifier (L / T / 2x2 Square)", testShapeDetections);
    runTest("8. Digital Orb Count Shield (数字盾: 首消18颗)", testDigitalOrbCountShield);
    runTest("9. Exact Combo (5C) + Odd Parity Shield", testExactAndParityComboShield);
    runTest("10. Anti-First-Wave (反首消叠珠) + Forbid All (禁消)", testAntiFirstWaveAndForbidAll);
    runTest("11. Five-Element Enchanted Shield (五属强化盾)", testFiveElementEnchantedShield);
    runTest("12. Column Amelioration (直排昇华 1&6列>=4消)", testColumnAmelioration);
    runTest("13. Hazards & Terrain (碎裂/石化/风化/电击/暴风)", testHazardsAndTerrainSemantics);
    runTest("14. Trail Burning (轨迹燃烧) + Traffic Light + Exact Steps", testTrailBurningAndTrafficLight);
    runTest("15. Character Orb Pull Start (角色珠下拉起手)", testCharacterOrbStart);
    runTest("16. Cooperative Cancellation", testCancellation);
    runTest("17. Fixed Start Cell (Hold-and-Replan 问号‧转/风化‧转)", testFixedStartCellHoldAndReplan);
    runTest("18. Cross-Element Compatible Merge Mask (跨属兼珠)", testCrossElementMergeMask);
    runTest("19. Dual-Element Equal Combos (首消光暗同等盾)", testDualElementEqualCombos);
    runTest("20. Same-Element Multi-Combo Shield (同属连击盾)", testSameElementMultiCombo);
    runTest("21. Second-Wave Cascade Shield (次批消除盾)", testSecondWaveCascadeShield);
    runTest("22. New Hazards (炼狱之火/泡沫四邻/沉睡唤醒)", testNewHazardsPurgatoryFoamSleep);
    runTest("23. Terrains (结界地型/封界石阵)", testBarrierAndSealedStoneTerrains);
    runTest("24. Character Orb Pass + End Terrain (角色珠通行/停放)", testCharOrbPassAndEndTerrain);
    runTest("25. Trail Corrosion Self-Avoiding Walk (轨迹腐蚀)", testTrailCorrosionUniqueCells);
    runTest("26. Race Orb Shield (种族符石盾)", testRaceOrbShield);
    runTest("27. Exact First-Wave 4C Real Board (Never 0 Steps)", testExactFirst4CRealBoardNeverZeroSteps);
    runTest("28. Zero Combo Empty Turn (Must Move >= 1 Step)", testZeroComboRequiresRealMove);
    runTest("29. Per-Color Independent Enchanted Shield", testPerColorIndependentEnchanted);
    runTest("30. Special Orb Shields (Enchanted/Weakened/Char1st)", testSpecialOrbShields);
    runTest("31. Dual-Cross Specific Colors Shield (Water+Fire)", testDualCrossSpecificColors);
    runTest("32. Composite Multi-Shield Stack", testCompositeMultiShieldStack);
    runTest("33. Hold-and-Replan Fast Fixed-Start & In-Worker Hard Timeout", testHoldAndReplanFastBudgetAndHardTimeout);
    runTest("34. Ordinary Puzzle Shield Semantics & Real 5-Cell Cross (4-Way Ortho)", testOrdinaryPuzzleShieldSingleColorAndRealCross);
    runTest("35. Real Strict 5-Cell Inverted-L Puzzle (4-Way Ortho, Strict No External Touch)", testRealStrictInvertedLPuzzle4WayOrtho);
    runTest("36. Real Traffic Light Stage (1,5)->(3,2) + Anti-1st-Wave Dark + >=6C (4-Way Ortho)", testRealTrafficLightStageWithCompositeShields);
    runTest("37. Official #57/#58 Wave-1 Same-Kind & Distinct-Kind (Heart Wildcard)", testWave1SameAndDistinctKindHeartWildcard);
    runTest("38. Official #18 First-Wave Elemental Combo >= N (Excludes Heart)", testFirstWaveElementalComboExcludesHeart);
    runTest("39. Official #9 3-Element Equal Wave-1 Combos (Water=Fire=Wood)", testThreeElementEqualWave1Combos);
    runTest("40. Official #15/#16 Strict Exact 5-Orb L-Shape & T-Shape Shields", testStrictExact5OrbLTShapes);
    runTest("41. Official #60 Step-Count Range Shield ([12..16] Steps)", testStepCountRangeShield);
    runTest("42. Official #31/#32 BlackHole & Bloodbath Passable but Forbidden End", testBlackHoleAndBloodbathPassableEndForbidden);
    runTest("43. Official #28/#96 ScorchingTile + #23 Unmatchable Elements", testScorchingTileAndUnmatchableElement);
    runTest("44. Official #66/#81 disableCascades + #78 CharOrbPass Terrain", testDisableCascadesAndCharOrbPassRule);
    runTest("45. Runestone #3/#4/#5 IceSealed No-Peel + Per-Wave 1-Layer Cap (Petrified/Coagulated)", testIceSealedAndPerWavePetrifiedCoagulatedCap);
    runTest("46. Runestone #8 BloodDrain All-Wave + #13 Movable Foam Burst", testBloodDrainAllWaveAndMovableFoam);
    runTest("47. Runestone #9 Bomb Touch Avoidance + #17 MagicOrb (+3C/orb)", testBombTouchAvoidanceAndMagicOrbBonus);
    runTest("48. Runestone #12 EvilEnergy & #20 BloodThorn Elimination Avoidance", testEvilEnergyAndBloodThornAvoidance);

    std::cout << "\nSummary: " << gTestsPassed << " passed, " << gTestsFailed << " failed.\n";
    return (gTestsFailed == 0) ? 0 : 1;
}
