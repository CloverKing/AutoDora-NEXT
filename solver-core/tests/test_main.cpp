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
        require((b.blackHoleMask & bitOf(nextIdx)) == 0, "entered black hole");
        require((b.noPassMask & bitOf(nextIdx)) == 0, "entered no-pass tile");
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

    std::cout << "\nSummary: " << gTestsPassed << " passed, " << gTestsFailed << " failed.\n";
    return (gTestsFailed == 0) ? 0 : 1;
}
