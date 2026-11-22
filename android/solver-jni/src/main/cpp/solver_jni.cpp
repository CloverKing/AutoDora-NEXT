#include <jni.h>
#include <android/log.h>

#include "autodora/board.hpp"
#include "autodora/rules.hpp"
#include "autodora/solver.hpp"

#include <algorithm>
#include <atomic>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr const char* kLogTag = "AutoDoraJNI";

std::string escapeJson(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        if (c == '"') out += "\\\"";
        else if (c == '\\') out += "\\\\";
        else out.push_back(c);
    }
    return out;
}

// Compact JSON serializer for SolveOutput (zero third-party C++ JSON dependency)
std::string serializeSolveOutputJson(const autodora::SolveOutput& out) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"statesExpanded\":" << out.statesExpanded << ",";
    oss << "\"statesEvaluated\":" << out.statesEvaluated << ",";
    oss << "\"elapsedMs\":" << out.elapsedMs << ",";
    oss << "\"interrupted\":" << (out.interrupted ? "true" : "false") << ",";
    oss << "\"timedOut\":" << (out.timedOut ? "true" : "false") << ",";
    oss << "\"solutions\":[";

    for (size_t i = 0; i < out.solutions.size(); ++i) {
        if (i > 0) oss << ",";
        const auto& s = out.solutions[i];
        oss << "{";
        oss << "\"startRow\":" << static_cast<int>(s.start.row) << ",";
        oss << "\"startCol\":" << static_cast<int>(s.start.col) << ",";
        oss << "\"usedCharacterOrb\":" << (s.usedCharacterOrb ? "true" : "false") << ",";
        oss << "\"characterOrbCol\":" << static_cast<int>(s.characterOrbCol) << ",";
        oss << "\"stepCount\":" << static_cast<int>(s.metrics.stepCount) << ",";
        oss << "\"firstWaveCombo\":" << static_cast<int>(s.simulation.firstWaveCombo) << ",";
        oss << "\"totalCombo\":" << static_cast<int>(s.simulation.totalCombo) << ",";
        oss << "\"firstWaveOrbs\":" << static_cast<int>(s.simulation.firstWaveOrbsTotal) << ",";
        oss << "\"totalOrbs\":" << static_cast<int>(s.simulation.totalOrbsEliminated) << ",";
        oss << "\"allHardConstraintsMet\":" << (s.evaluation.allHardConstraintsMet ? "true" : "false") << ",";
        oss << "\"satisfiedFlags\":" << s.evaluation.satisfiedFlags << ",";
        oss << "\"failedFlags\":" << s.evaluation.failedFlags << ",";
        oss << "\"score\":" << s.evaluation.totalScore << ",";
        oss << "\"tag\":\"" << escapeJson(s.tag) << "\",";

        oss << "\"moves\":[";
        for (size_t m = 0; m < s.moves.size(); ++m) {
            if (m > 0) oss << ",";
            oss << static_cast<int>(s.moves[m]);
        }
        oss << "],";

        oss << "\"groups\":[";
        for (uint8_t g = 0; g < s.simulation.groupCount; ++g) {
            if (g > 0) oss << ",";
            const auto& grp = s.simulation.groups[g];
            oss << "{\"element\":" << static_cast<int>(grp.element)
                << ",\"orbCount\":" << static_cast<int>(grp.orbCount)
                << ",\"wave\":" << static_cast<int>(grp.wave)
                << ",\"hasEnchanted\":" << (grp.enchantedCount > 0 ? "true" : "false") << "}";
        }
        oss << "]";
        oss << "}";
    }
    oss << "]}";
    return oss.str();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_autodora_next_jni_NativeSolver_nativeCreateCancelToken(JNIEnv*, jobject) {
    auto* token = new std::atomic<bool>(false);
    return reinterpret_cast<jlong>(token);
}

JNIEXPORT void JNICALL
Java_com_autodora_next_jni_NativeSolver_nativeCancel(JNIEnv*, jobject, jlong tokenPtr) {
    if (tokenPtr != 0) {
        auto* token = reinterpret_cast<std::atomic<bool>*>(tokenPtr);
        token->store(true, std::memory_order_relaxed);
    }
}

JNIEXPORT void JNICALL
Java_com_autodora_next_jni_NativeSolver_nativeDestroyCancelToken(JNIEnv*, jobject, jlong tokenPtr) {
    if (tokenPtr != 0) {
        delete reinterpret_cast<std::atomic<bool>*>(tokenPtr);
    }
}

JNIEXPORT jstring JNICALL
Java_com_autodora_next_jni_NativeSolver_nativeSolve(
    JNIEnv* env,
    jobject,
    jint cols,
    jint rows,
    jintArray jElements,
    jintArray jVariants,
    jintArray jHazards,
    jintArray jTileFlags,
    jintArray jCheckpoints,
    jintArray jRuleInts,
    jdoubleArray jRuleDoubles,
    jintArray jElementRuleInts,
    jdoubleArray jElementWeights,
    jintArray jColRules,
    jintArray jRowRules,
    jlong cancelTokenPtr
) {
    using namespace autodora;

    BoardState board;
    board.cols = static_cast<uint8_t>(cols);
    board.rows = static_cast<uint8_t>(rows);
    const int n = cols * rows;

    if (!board.isValidGeometry()) {
        return env->NewStringUTF("{\"solutions\":[],\"statesExpanded\":0,\"statesEvaluated\":0,\"elapsedMs\":0,\"interrupted\":false,\"timedOut\":false}");
    }

    auto readIntVec = [&](jintArray arr) -> std::vector<int> {
        if (!arr) return {};
        jsize len = env->GetArrayLength(arr);
        std::vector<int> v(static_cast<size_t>(len));
        env->GetIntArrayRegion(arr, 0, len, v.data());
        return v;
    };
    auto readDoubleVec = [&](jdoubleArray arr) -> std::vector<double> {
        if (!arr) return {};
        jsize len = env->GetArrayLength(arr);
        std::vector<double> v(static_cast<size_t>(len));
        env->GetDoubleArrayRegion(arr, 0, len, v.data());
        return v;
    };

    const auto els = readIntVec(jElements);
    const auto vars = readIntVec(jVariants);
    const auto hazs = readIntVec(jHazards);
    const auto tflags = readIntVec(jTileFlags);
    const auto ckpts = readIntVec(jCheckpoints);

    for (int i = 0; i < n; ++i) {
        if (i < static_cast<int>(els.size()))  board.cells[i].element = static_cast<Element>(els[i]);
        if (i < static_cast<int>(vars.size())) board.cells[i].variant = static_cast<Variant>(vars[i]);
        if (i < static_cast<int>(hazs.size())) board.cells[i].hazards = static_cast<uint32_t>(hazs[i]);
        if (i < static_cast<int>(tflags.size())) board.tileFlags[i] = static_cast<uint32_t>(tflags[i]);
    }
    for (int cp : ckpts) {
        if (board.checkpointCount < kMaxCheckpoints) {
            board.orderedCheckpoints[board.checkpointCount++] = static_cast<uint8_t>(cp);
        }
    }
    board.rebuildMasks();

    // Unpack flat RuleConfig arrays (must match NativeSolver.kt packing indices)
    const auto ri = readIntVec(jRuleInts);
    const auto rd = readDoubleVec(jRuleDoubles);
    const auto eri = readIntVec(jElementRuleInts);
    const auto ew = readDoubleVec(jElementWeights);
    const auto cr = readIntVec(jColRules);
    const auto rr = readIntVec(jRowRules);

    RuleConfig rules;
    auto riAt = [&](size_t idx, int defVal = 0) -> int { return idx < ri.size() ? ri[idx] : defVal; };
    auto rdAt = [&](size_t idx, double defVal = 0.0) -> double { return idx < rd.size() ? rd[idx] : defVal; };

    rules.allowDiagonalMove               = riAt(0, 1) != 0;
    rules.maxSteps                        = static_cast<uint16_t>(std::clamp(riAt(1, 30), 1, 300));
    rules.exactSteps                      = static_cast<int16_t>(riAt(2, -1));
    rules.startPreset                     = static_cast<StartPointPreset>(riAt(3, 0));
    rules.allowHitWeatheredToClear        = riAt(4, 0) != 0;
    rules.useCharacterOrb                 = riAt(5, 0) != 0;
    rules.characterOrbCol                 = static_cast<uint8_t>(riAt(6, 0));
    rules.characterOrbElement             = static_cast<Element>(riAt(7, 1));
    rules.allowDiagonalMatch              = riAt(8, 0) != 0;
    rules.forceSplitThree                 = riAt(9, 0) != 0;
    rules.notEnoughPolicy                 = static_cast<NotEnoughPolicy>(riAt(10, 0));
    rules.fourPlusPolicy                  = static_cast<FourPlusPolicy>(riAt(11, 0));
    rules.preferEnchanted                 = riAt(12, 1) != 0;
    rules.preferRace                      = riAt(13, 0) != 0;
    rules.minTotalCombo                   = static_cast<uint8_t>(riAt(14, 0));
    rules.maxTotalCombo                   = static_cast<int8_t>(riAt(15, -1));
    rules.exactTotalCombo                 = static_cast<int8_t>(riAt(16, -1));
    rules.minFirstWaveCombo               = static_cast<uint8_t>(riAt(17, 0));
    rules.exactFirstWaveCombo             = static_cast<int8_t>(riAt(18, -1));
    rules.firstWaveOnlyScoring            = riAt(19, 0) != 0;
    rules.comboParity                     = static_cast<ComboParity>(riAt(20, 0));
    rules.comboParityFirstWaveOnly        = riAt(21, 1) != 0;
    rules.sameComboAcrossEliminatedElements = riAt(22, 0) != 0;
    rules.avoidSkyfallRisk                = riAt(23, 1) != 0;
    rules.exactFirstWaveTotalOrbs         = static_cast<int16_t>(riAt(24, -1));
    rules.requireFirstWaveFullClear       = riAt(25, 0) != 0;
    rules.requiredFirstWaveElementsMask   = static_cast<uint8_t>(riAt(26, 0));
    rules.requiredTotalElementsMask       = static_cast<uint8_t>(riAt(27, 0));
    rules.requiredEnchantedElementsMask   = static_cast<uint8_t>(riAt(28, 0));
    rules.minDistinctElements             = static_cast<uint8_t>(riAt(29, 0));

    rules.puzzle1.enabled                 = riAt(30, 0) != 0;
    rules.puzzle1.strictNoExternalTouch   = riAt(31, 1) != 0;
    rules.puzzle1.requiredElement         = static_cast<int8_t>(riAt(32, -1));
    rules.puzzle1.requireEnchanted        = riAt(33, 0) != 0;
    rules.puzzle2.enabled                 = riAt(34, 0) != 0;
    rules.puzzle2.strictNoExternalTouch   = riAt(35, 1) != 0;
    rules.puzzle2.requiredElement         = static_cast<int8_t>(riAt(36, -1));
    rules.puzzle2.requireEnchanted        = riAt(37, 0) != 0;

    rules.crossShield.requiredCount       = static_cast<uint8_t>(riAt(38, 0));
    rules.crossShield.strict5Orbs         = riAt(39, 1) != 0;
    rules.crossShield.requiredElement     = static_cast<int8_t>(riAt(40, -1));
    rules.crossShield.requireDistinctColors = riAt(41, 0) != 0;

    rules.shapeShield.requireLCount       = static_cast<uint8_t>(riAt(42, 0));
    rules.shapeShield.requireTCount       = static_cast<uint8_t>(riAt(43, 0));
    rules.shapeShield.requireSquare2x2Count = static_cast<uint8_t>(riAt(44, 0));
    rules.shapeShield.strictShape         = riAt(45, 0) != 0;

    rules.trailBurningEnabled             = riAt(46, 0) != 0;
    rules.trailLength                     = static_cast<uint8_t>(riAt(47, 0));
    rules.maxTrailHurts                   = static_cast<uint8_t>(riAt(48, 0));
    rules.maxBurningTileHurts             = static_cast<uint8_t>(riAt(49, 0));
    rules.mustClearElectrified            = riAt(50, 1) != 0;
    rules.mustClearBloodDrain             = riAt(51, 1) != 0;
    rules.mustClearBomb                   = riAt(52, 1) != 0;
    rules.prioritizeFrozen                = riAt(53, 1) != 0;
    rules.prioritizePetrifiedClear        = riAt(54, 1) != 0;

    rules.beamWidth                       = static_cast<uint32_t>(riAt(55, 180));
    rules.maxTimeMs                       = static_cast<uint32_t>(riAt(56, 500));
    rules.numThreads                      = static_cast<uint32_t>(riAt(57, 0));
    rules.topK                            = static_cast<uint32_t>(riAt(58, 5));
    rules.earlyStopOnGoal                 = riAt(59, 0) != 0;

    for (size_t e = 0; e < kNumElements; ++e) {
        rules.compatibleLinkMask[e] = static_cast<uint8_t>(riAt(60 + e, 1 << e));
    }

    // Phase 2.5 extended 13-year ToS rules (indices 66..88)
    rules.fixedStartCell                  = static_cast<int8_t>(riAt(66, -1));
    rules.requireCharOrbPass              = riAt(67, 0) != 0;
    rules.requireCharOrbEnd               = riAt(68, 0) != 0;
    rules.minSecondWaveCombo              = static_cast<uint8_t>(riAt(69, 0));
    rules.requiredSecondWaveElementsMask  = static_cast<uint8_t>(riAt(70, 0));
    rules.requireEqualPairCombos          = riAt(71, 0) != 0;
    rules.equalPairElemA                  = static_cast<uint8_t>(riAt(72, 3));
    rules.equalPairElemB                  = static_cast<uint8_t>(riAt(73, 4));
    rules.equalPairMustBeNonZero          = riAt(74, 1) != 0;
    rules.requiredSameElementCombos       = static_cast<uint8_t>(riAt(75, 0));
    rules.sameElementComboTarget          = static_cast<int8_t>(riAt(76, -1));
    rules.requiredRaceElementsMask        = static_cast<uint8_t>(riAt(77, 0));
    rules.requireAnyRaceOrb               = riAt(78, 0) != 0;
    rules.maximizeElementOrbIndex         = static_cast<int8_t>(riAt(79, -1));
    rules.trailCorrosionEnabled           = riAt(80, 0) != 0;
    rules.requiredUniqueCellsVisited      = static_cast<uint8_t>(riAt(81, 0));
    rules.maxMiasmaHits                   = static_cast<uint8_t>(riAt(82, 0));
    rules.mustClearFoam                   = riAt(83, 1) != 0;
    rules.mustClearLockedHurt             = riAt(84, 1) != 0;
    rules.prioritizeLockedAtkDown         = riAt(85, 1) != 0;
    rules.avoidCorroded                   = riAt(86, 1) != 0;
    rules.prioritizeLuminous              = riAt(87, 1) != 0;
    rules.crossShield.requiredElementsMask = static_cast<uint8_t>(riAt(88, 0));
    rules.requireAnyEnchantedOrb          = riAt(89, 0) != 0;
    rules.requireCharOrbFirstWave         = riAt(90, 0) != 0;
    rules.forbidWeakenedOrbs              = riAt(91, 0) != 0;
    rules.requireLuminousOrbClear         = riAt(92, 0) != 0;
    rules.minSteps                        = static_cast<uint16_t>(std::clamp(riAt(93, 0), 0, 300));
    rules.minFirstWaveElementalCombo      = static_cast<uint8_t>(riAt(94, 0));
    rules.disableCascades                 = riAt(95, 0) != 0;
    rules.wave1KindMode                   = static_cast<Wave1KindMode>(riAt(96, 0));
    rules.equalComboElementsMask          = static_cast<uint8_t>(riAt(97, 0));
    rules.unmatchableElementsMask         = static_cast<uint8_t>(riAt(98, 0));

    rules.stepPenaltyWeight     = rdAt(0, 0.08);
    rules.diagonalPenaltyWeight = rdAt(1, 0.05);

    // 10 ints per element rule
    for (size_t e = 0; e < kNumElements && (e * 10 + 9) < eri.size(); ++e) {
        const size_t base = e * 10;
        auto& er = rules.elementRules[e];
        er.minMatchCount       = static_cast<uint8_t>(eri[base + 0]);
        er.targetShape         = static_cast<TargetShape>(eri[base + 1]);
        er.priority            = static_cast<ElementPriority>(eri[base + 2]);
        er.timing              = static_cast<MatchTiming>(eri[base + 3]);
        er.requireEnchanted    = eri[base + 4] != 0;
        er.requireRace         = eri[base + 5] != 0;
        er.clearAllFirstWave   = eri[base + 6] != 0;
        er.exactFirstWaveCombos = static_cast<int8_t>(eri[base + 7]);
        er.exactFirstWaveOrbs  = static_cast<int8_t>(eri[base + 8]);
        er.minFirstWaveOrbs    = static_cast<uint8_t>(eri[base + 9]);
        if (e < ew.size()) er.customWeight = ew[e];
    }

    // 4 ints per column rule
    for (size_t c = 0; c < kMaxBoardDim && (c * 4 + 3) < cr.size(); ++c) {
        const size_t base = c * 4;
        rules.columnRules[c].enabled         = cr[base + 0] != 0;
        rules.columnRules[c].minOrbs         = static_cast<uint8_t>(cr[base + 1]);
        rules.columnRules[c].hardRequired    = cr[base + 2] != 0;
        rules.columnRules[c].requiredElement = static_cast<int8_t>(cr[base + 3]);
    }

    // 3 ints per row rule
    for (size_t r = 0; r < kMaxBoardDim && (r * 3 + 2) < rr.size(); ++r) {
        const size_t base = r * 3;
        rules.rowRules[r].enabled         = rr[base + 0] != 0;
        rules.rowRules[r].hardRequired    = rr[base + 1] != 0;
        rules.rowRules[r].requiredElement = static_cast<int8_t>(rr[base + 2]);
    }

    const auto* cancelPtr = reinterpret_cast<const std::atomic<bool>*>(cancelTokenPtr);
    PuzzleSolver solver;
    const SolveOutput out = solver.solve(board, rules, cancelPtr);

    __android_log_print(
        ANDROID_LOG_DEBUG, kLogTag,
        "Solved %dx%d in %.1fms (evals=%llu, solutions=%zu)",
        cols, rows, out.elapsedMs,
        static_cast<unsigned long long>(out.statesEvaluated),
        out.solutions.size()
    );

    const std::string json = serializeSolveOutputJson(out);
    return env->NewStringUTF(json.c_str());
}

} // extern "C"
