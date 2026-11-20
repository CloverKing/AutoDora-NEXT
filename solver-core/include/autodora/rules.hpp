#pragma once

#include "autodora/types.hpp"

#include <array>
#include <cstdint>

namespace autodora {

enum class ElementPriority : int8_t {
    Reserve = -1, // 保留不消
    Normal = 0,   // 一般
    High = 1      // 优先消除
};

enum class MatchTiming : uint8_t {
    Any = 0,                   // 无限制
    MustFirstWave = 1,         // 必须首消
    ForbidFirstWaveAllowCascade = 2, // 反首消 (允许叠珠/二波消)
    ForbidAll = 3              // 绝对禁消 (首消+天降均不可消)
};

enum class TargetShape : uint8_t {
    Any = 0,
    Split3 = 1,
    Count2 = 2,
    Count4 = 3,
    Count5 = 4,
    Count6 = 5,
    Count7 = 6,
    Count8 = 7,
    Count9 = 8,
    Row = 9,
    Column = 10,
    Cross = 11,
    LShape = 12,
    TShape = 13,
    Square2x2 = 14,
    ClearAll = 15
};

enum class NotEnoughPolicy : uint8_t {
    MatchAnyway = 0, // 珠数不足设定颗数时照常消除
    Keep = 1         // 保留不消以存珠
};

enum class FourPlusPolicy : uint8_t {
    MaxCombo = 0,    // 拆多组追求最高 Combo
    MaxOrbs = 1,     // 消最多珠
    SingleGroup = 2  // 合并为一大组
};

enum class StartPointPreset : uint8_t {
    Any = 0,
    BottomLeft = 1,
    BottomRight = 2
};

enum class ComboParity : uint8_t {
    Any = 0,
    Odd = 1,
    Even = 2
};

struct ElementRule {
    uint8_t minMatchCount = 3; // 1, 2, 3, or 4
    TargetShape targetShape = TargetShape::Any;
    ElementPriority priority = ElementPriority::Normal;
    MatchTiming timing = MatchTiming::Any;
    double customWeight = 1.0;

    bool requireEnchanted = false;      // 该属性需含强化珠消除
    bool requireRace = false;           // 该属性需含种族珠消除
    bool clearAllFirstWave = false;     // 首消清光该属性全部符石
    int8_t exactFirstWaveCombos = -1;   // -1 = 不限制
    int8_t exactFirstWaveOrbs = -1;     // -1 = 不限制
    uint8_t minFirstWaveOrbs = 0;       // 0 = 不限制
};

struct ColumnAmeliorationRule {
    bool enabled = false;
    uint8_t minOrbs = 4;        // 3, 4, 5, or 255 for full column
    bool hardRequired = false;  // false = 加分项, true = 硬性必达
    int8_t requiredElement = -1; // -1 = 任意属性, 0..5 = 指定属性
};

struct RowBonusRule {
    bool enabled = false;
    bool hardRequired = false;
    int8_t requiredElement = -1;
};

struct PuzzleShieldRule {
    bool enabled = false;
    bool strictNoExternalTouch = true; // 严格不黏连外部同色珠
    int8_t requiredElement = -1;       // -1 = 任意同色, 0..5 = 指定颜色
    bool requireEnchanted = false;     // 强化拼图盾
};

struct CrossShieldRule {
    uint8_t requiredCount = 0;         // 0 = 关闭, 1/2/3 = 单/双/三十字
    bool strict5Orbs = true;           // 严格 5 颗十字 (不黏连多余同色)
    int8_t requiredElement = -1;       // -1 = 任意色
    bool requireDistinctColors = false;
    uint8_t requiredElementsMask = 0;  // 非 0 时要求所选各属性均各自消出十字 (如水+火双十字)
};

struct ShapeShieldRule {
    uint8_t requireLCount = 0;
    uint8_t requireTCount = 0;
    uint8_t requireSquare2x2Count = 0;
    bool strictShape = false;
};

struct RuleConfig {
    // --- Movement & Step Controls ---
    bool allowDiagonalMove = true;         // 允许斜转 (8 向移动)
    uint16_t maxSteps = 30;                // 步数上限 (1..300; 违建/排珠/长手转可达 100~300)
    int16_t exactSteps = -1;               // 精确步数盾 (-1 = 关闭)
    double stepPenaltyWeight = 0.08;       // 一步一伤时可调高至 5.0+
    double diagonalPenaltyWeight = 0.05;   // 同分时略偏向直线
    StartPointPreset startPreset = StartPointPreset::Any;
    int8_t fixedStartCell = -1;            // >=0 时强制从此格起手 (免 Root 问号‧转/风化‧转/烟雾重算专用!)
    bool allowHitWeatheredToClear = false; // 默认严避风化; 开启后允许故意撞掉风化解状态

    // --- Character Orb (角色符石起手与通行/停放地形) ---
    bool useCharacterOrb = false;
    uint8_t characterOrbCol = 0;           // 0..cols-1 (从该列头像下拉至 (0, col))
    Element characterOrbElement = Element::Fire;
    bool requireCharOrbPass = false;       // 要求角色符石路径经过所有 CharOrbPass 格
    bool requireCharOrbEnd = false;        // 要求角色符石停放在 CharOrbEnd 格之一

    // --- Matching & Link Mechanics ---
    bool allowDiagonalMatch = false;       // 斜消 (8 向相连消除, 独立于斜转!)
    std::array<uint8_t, kNumElements> compatibleLinkMask = {
        1u << 0, 1u << 1, 1u << 2, 1u << 3, 1u << 4, 1u << 5
    };
    bool forceSplitThree = false;          // 强制 3 珠切珠防黏连
    NotEnoughPolicy notEnoughPolicy = NotEnoughPolicy::MatchAnyway;
    FourPlusPolicy fourPlusPolicy = FourPlusPolicy::MaxCombo;
    bool preferEnchanted = true;
    bool preferRace = false;

    // --- Per-Element Triplets ---
    std::array<ElementRule, kNumElements> elementRules{};

    // --- Amelioration (直排昇华 & 横排) ---
    std::array<ColumnAmeliorationRule, kMaxBoardDim> columnRules{};
    std::array<RowBonusRule, kMaxBoardDim> rowRules{};

    // --- Combo Shields ---
    uint8_t minTotalCombo = 0;
    int8_t maxTotalCombo = -1;             // -1 = 不限制
    int8_t exactTotalCombo = -1;           // -1 = 不限制 (含 0C 空转)
    uint8_t minFirstWaveCombo = 0;
    int8_t exactFirstWaveCombo = -1;       // -1 = 不限制
    bool firstWaveOnlyScoring = false;     // 只计首批 Combo (不依赖天降叠珠)
    ComboParity comboParity = ComboParity::Any;
    bool comboParityFirstWaveOnly = true;
    bool sameComboAcrossEliminatedElements = false; // 同属同 C
    bool avoidSkyfallRisk = true;          // 固定 C / 奇偶盾时惩罚易意外天降残留结构
    uint8_t minSecondWaveCombo = 0;        // 次批消除盾: 第2波(天降叠珠)最少 Combo
    uint8_t requiredSecondWaveElementsMask = 0; // 次批消除盾: 第2波需含属性掩码
    bool requireEqualPairCombos = false;   // 双属首消 Combo 同等盾 (如首消光暗同等盾)
    uint8_t equalPairElemA = 3;            // 默认光 (Light)
    uint8_t equalPairElemB = 4;            // 默认暗 (Dark)
    bool equalPairMustBeNonZero = true;    // 要求双属各自均 > 0 且首消 Combo 数相等
    uint8_t requiredSameElementCombos = 0; // 同属连击盾 (如首消 3 组暗)
    int8_t sameElementComboTarget = -1;    // -1 = 任意单属达到 N 组, 0..5 = 指定属性

    // --- Count & Element Shields ---
    int16_t exactFirstWaveTotalOrbs = -1;  // 数字盾 (-1 = 关闭, 如 18 / 20 / 28)
    bool requireFirstWaveFullClear = false; // 首消全版
    uint8_t requiredFirstWaveElementsMask = 0; // 位掩码 (如五属首消 = 0b011111)
    uint8_t requiredTotalElementsMask = 0;     // 全回合需消属性掩码
    uint8_t requiredEnchantedElementsMask = 0; // 五属强化盾等
    uint8_t requiredRaceElementsMask = 0;      // 指定属性种族符石首消盾
    bool requireAnyRaceOrb = false;            // 任意种族符石首消盾
    uint8_t minDistinctElements = 0;           // 3/4/5 色模式
    int8_t maximizeElementOrbIndex = -1;       // 累积消除盾: 0..5 = 最大化该属性消珠数

    // --- Shape & Puzzle Shields ---
    PuzzleShieldRule puzzle1{};
    PuzzleShieldRule puzzle2{};
    CrossShieldRule crossShield{};
    ShapeShieldRule shapeShield{};

    // --- Terrain, Trail Burning & 13-Year Hazards ---
    bool trailBurningEnabled = false;
    uint8_t trailLength = 0;               // 0 = 无限尾迹, 或 6
    uint8_t maxTrailHurts = 0;             // 允许轨迹碰撞次数 (0..10)
    uint8_t maxBurningTileHurts = 0;       // 允许踩燃烧格次数 (0..10)
    bool trailCorrosionEnabled = false;    // 全局轨迹腐蚀: 严禁重访已走过格子 (自避路径)
    uint8_t requiredUniqueCellsVisited = 0; // 要求路径踏过至少 K 个不重复格子 (如轨迹踏满)
    uint8_t maxMiasmaHits = 0;             // 允许经过瘴气/毒雾格次数
    bool mustClearElectrified = true;      // 电击珠必消
    bool mustClearBloodDrain = true;       // 化血珠必首消
    bool mustClearBomb = true;             // 炸弹珠必首消
    bool mustClearFoam = true;             // 泡沫珠需消四邻破裂
    bool mustClearLockedHurt = true;       // 受伤锁定必首消
    bool prioritizeLockedAtkDown = true;   // 减攻锁定高优清除
    bool avoidCorroded = true;             // 腐蚀符石默认硬避碰
    bool prioritizeLuminous = true;        // 光晕符石高优消除
    bool prioritizeFrozen = true;          // 冻结珠高优清除
    bool prioritizePetrifiedClear = true;  // 石化/冰封珠四邻消除解层加权

    // --- Search Budget & Output ---
    uint32_t beamWidth = 180;              // 算力档位 (16 ~ 1000)
    uint32_t maxTimeMs = 500;              // 最大搜索时长 (ms)
    uint32_t numThreads = 0;               // 0 = 自动检测 CPU 线程数
    uint32_t topK = 5;                     // 返回 Top-K 多样化候选方案
    bool earlyStopOnGoal = false;          // true = 最快解 (满足硬盾即早停), false = 最佳解
};

} // namespace autodora
