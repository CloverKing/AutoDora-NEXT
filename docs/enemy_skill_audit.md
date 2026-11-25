# 神魔之塔「神魔百科 · 敵人技能」(98 项) 全量结构化审计与解盾对齐报告

本报告基于当前版本游戏内 **其他 → 神魔百科 → 敵人技能 (`1/98` ~ `98/98`)** 逐项采集的官方图标说明、正/误解法示例图与完整滚动文字说明整理而成（原始游戏截图仅本地归档于 `.gitignore` 忽略的 `backups/` 目录，仓库仅保存干净的结构化技术规范）。

## 一、全量覆盖统计

- **总技能数**：`98` 项
- **已完整覆盖 (`FULL`)**：`37` 项
- **本次审计新发现并已补全/修正 (`UPGRADED`)**：`20` 项
- **纯视觉干扰 / 印记类（已支持画笔标注，待后续端侧模型增强，`CV_PARTIAL`）**：`6` 项
- **非盘面转珠机制（血量/CD/EP/减伤/隐身等，`NON_BOARD`）**：`35` 项

## 二、本次审计发现并落地的核心算法、规则与 UI 升级

| 编号 | 官方敌技 | 审计发现（官方正/误示例核实） | 落地改动 |
| :--- | :--- | :--- | :--- |
| #4–#7 | 二/三/四/五屬盾 | 官方第 2 页正确示例明确展示 **天降叠珠消除** 同样达标，不限首消 | `RuleComposer` 与 `ShieldVerdictEvaluator` 的 `require_elements` 默认改按全回合 `requiredTotalElementsMask` 判定（保留 `paramInt bit 1` 供显式首消指定属使用） |
| #9 | 連擊相等盾 | 官方示例为 **水火木三属同等**（2~6 属通用，不限双属） | `RuleConfig` 新增 `equalComboElementsMask`（兼容旧 2 属），支持任意 2~6 属首消组数相等 |
| #15, #16 | T字限盾 / L字限盾 | 官方第 2 页错误示例明确为 **严格 5 粒**（黏连额外同色即失败） | `evaluator.cpp` 补齐 `strictShape` 对 L/T 的精确 5 粒判定 + 新增 L/T 形态拉珠引导势能，`RuleComposer` 默认开启 `strictShape=true` |
| #18 | 首消組數屬性符石盾 | 官方第 2 页错误示例明确 **心符石不计入属性符石** | 新增 `RuleConfig.minFirstWaveElementalCombo` 与 `min_first_elem_combo` 盾牌与 UI 控件 |
| #23 | 符石禁咒 | 指定属性符石**即使 3 连也无法消除**（非普通禁消惩罚） | `simulator.cpp` 新增 `unmatchableElementsMask`，禁咒属性在模拟时视为不可消除惰性珠 |
| #28, #96 | 灼熱地型 | 与 #25 `燃燒`（触碰扣血）不同，灼热是 **在地型内消除符石才扣血**（可安全经过） | 新增 `TileFlagBits::ScorchingTile (1<<19)`、`simulator.cpp` 消除统计、`evaluator.cpp` 避消约束与盘面画笔 |
| #31, #32 | 血浴地型 / 黑洞 | 官方文字明确：**仅在结束转珠停手于该格时触发惩罚**，路径可安全穿过！ | 将 `BlackHole` 从不可通行墙修正为终点禁停，并新增 `TileFlagBits::EndForbidden (1<<20)` 与盘面画笔 |
| #57, #58 | 首消相同種類盾 / 首消不同種類盾 | 首消精确 N 组且满足全同属 / 全异属，且 **心符石通用（每组心珠可充当任意属性）** | 新增 `Wave1KindMode` (`SameKindHeartWild` / `DistinctKindHeartWild`)、`wave1_same_kind` / `wave1_distinct_kind` 盾牌与双端 UI |
| #60 | 步數盾 | 官方示例为 **区间步数**（如 `25-30 步`），而非仅上限或单点 | 新增 `RuleConfig.minSteps`、`step_range` 盾牌与校验器支持 |
| #66, #81 | 只能消除首批符石 / 寒之氣 | 首批消除后 **所有掉落天降均不能消除** | 新增 `RuleConfig.disableCascades` 在 `simulator.cpp` 中彻底关闭第 2 波及以后连锁 + 新增 `no_cascade` 盾牌 |
| #30, #54, #78, #87 | 起結有序 / 角色符石通行与停放 | 官方明确起/停漩涡为唯一单点，而角色符石通行/停放地形允许覆盖多格；同步统一官方百科术语 | `v0.4.1` 强制编排「绿色漩涡 / 红色漩涡」单点互斥、保留「角色通行 / 角色停放」多格涂抹，并在策略与编排双端统一「起结有序」等官方名称 + 独立新增 7 大种族符石支持 |
| #78 | 角色符石通行地型 | 官方文字明确：地型内符石不可消除，且仅指定角色符石可通过 | 强化 `CharOrbPass` 语义：非角色珠路径不可穿行且地型内不可消除（完全兼容原有角色珠必经/停放测试） |

## 三、98 项官方敌技完整对照表

| # | 繁體名稱 | 簡體名稱 | 類別 | 盤面相關性 | 覆蓋狀態 | AutoDora 對應能力 / 備註 |
| :-: | :--- | :--- | :--- | :--- | :-: | :--- |
| 1 | 指定形狀盾 | 指定形状盾 | `shape_puzzle` | `direct_board_rule` | **FULL** | `puzzle_shield`, `strict_puzzle`, `loose_puzzle`, `enchanted_puzzle` `TileFlagBits.PUZZLE_1` CvBoardRecognizer detects classic gold & modern metallic frames; strictNoExternalTouch enforces zero external same-color touch (Slide 3 Red-X verified). |
| 2 | 攻前盾 | 攻前盾 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Pre-attack turn-wait shield; use zero_combo if stalling. |
| 3 | 強化突破 | 强化突破 | `element_color` | `direct_board_rule` | **FULL** | `require_elements (ench mask)`, `any_enchanted_orb_shield` `Variant.ENCHANTED` Per-attribute enchanted orb requirement; CvBoardRecognizer detects multi-frame star gleam. |
| 4 | 二屬盾 | 二属盾 | `element_color` | `direct_board_rule` | **UPGRADED** | `require_elements` UPGRADED: Official Slide 2 confirms cascade/skyfall clears count! Default require_elements now uses requiredTotalElementsMask (Wave-1 strict remains optional). |
| 5 | 三屬盾 | 三属盾 | `element_color` | `direct_board_rule` | **UPGRADED** | `three_elements`, `require_elements` UPGRADED: Official Slide 2 confirms cascade/skyfall clears count for 3-element shield. |
| 6 | 四屬盾 | 四属盾 | `element_color` | `direct_board_rule` | **UPGRADED** | `require_elements` UPGRADED: Official Slide 2 confirms cascade/skyfall clears count for 4-element shield. |
| 7 | 五屬盾 | 五属盾 | `element_color` | `direct_board_rule` | **UPGRADED** | `five_elements`, `require_elements` UPGRADED: Official Slide 2 confirms cascade/skyfall clears count for 5-element shield. |
| 8 | 固定連擊盾 | 固定连击盾 | `combo` | `direct_board_rule` | **FULL** | `exact_first_combo` Exact Wave-1 combo count (cascades after Wave-1 allowed). |
| 9 | 連擊相等盾 | 连击相等盾 | `combo` | `direct_board_rule` | **UPGRADED** | `equal_pair_light_dark (generalized N-color equal_combo_elements)` UPGRADED: Official Slide 1-2 shows 3-color Water=Fire=Wood! Generalized RuleConfig.equalComboElementsMask to support 2..6 arbitrary specified elements. |
| 10 | 反首消符石盾 | 反首消符石盾 | `element_color` | `direct_board_rule` | **FULL** | `anti_first_wave` Wave-1 forbidden orb; Wave-2 cascade explicitly allowed (Slide 1 matches MatchTiming.ForbidFirstWaveAllowCascade). |
| 11 | 全版消除盾 | 全版消除盾 | `orb_count` | `direct_board_rule` | **FULL** | `first_wave_full_clear` Wave-1 100% full-board clear (0 remaining matchable orbs). |
| 12 | 全消盾 | 全消盾 | `orb_count` | `direct_board_rule` | **FULL** | `clear_all_element` Wave-1 clear ALL orbs of specified element(s), including 6x8 expanded rows (Slide 3 verified). |
| 13 | 首消粒數盾 | 首消粒数盾 | `orb_count` | `direct_board_rule` | **FULL** | `digital_orb_count` Exact Wave-1 total orb count (exactFirstWaveTotalOrbs == N). |
| 14 | 十字限盾 | 十字限盾 | `shape_puzzle` | `direct_board_rule` | **FULL** | `cross_shield` Strict exact 5-orb '+' shape in Wave-1 (isExactCross5, zero extra orbs). |
| 15 | T字限盾 | T字限盾 | `shape_puzzle` | `direct_board_rule` | **UPGRADED** | `t_shape` UPGRADED: Official Slide 2 proves strict exact 5-orb T-shape! Fixed evaluator.cpp & RuleComposer to enforce strictShape (orbCount==5) + added L/T pull-distance shaping. |
| 16 | L字限盾 | L字限盾 | `shape_puzzle` | `direct_board_rule` | **UPGRADED** | `l_shape` UPGRADED: Official Slide 2 proves strict exact 5-orb L-shape! Fixed evaluator.cpp & RuleComposer to enforce strictShape (orbCount==5) + added L/T pull-distance shaping. |
| 17 | 首殺盾 | 首杀盾 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Enemy target-kill-order mechanic (tap target enemy first). |
| 18 | 首消組數屬性符石盾 | 首消组数属性符石盾 | `combo` | `direct_board_rule` | **UPGRADED** | `min_first_elem_combo` NEW: Official Slide 2 proves Heart orbs do NOT count toward elemental groups! Added RuleConfig.minFirstWaveElementalCombo & min_first_elem_combo chip. |
| 19 | 力的印記 | 力的印记 | `orb_hazard_mark` | `board_hazard_or_mark` | **CV_PARTIAL** | — `HazardBits.PRIORITY` Top-right orb badge mark (力的印記); mark priority orbs in Board Editor to prioritize clearing; CvBoardRecognizer excludes top-right corner from orb hue. |
| 20 | % 防禦盾 | % 防御盾 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Per-turn max % HP damage cap. |
| 21 | 指定連擊法印 | 指定连击法印 | `combo` | `direct_board_rule` | **FULL** | `exact_first_combo` Exact Wave-1 combo seal (exactFirstWaveCombo == N; cascades allowed). |
| 22 | 指定減傷抗性敵技 | 指定减伤抗性敌技 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Dynamic % damage-reduction resistance gauge. |
| 23 | 符石禁咒 | 符石禁咒 | `orb_hazard_mark` | `board_hazard_or_mark` | **UPGRADED** | `unmatchable_elements` NEW: Added RuleConfig.unmatchableElementsMask in simulator.cpp so banned orb attributes truly cannot form matches even when 3+ align. |
| 24 | 開技消耗代價 | 开技消耗代价 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — HP cost on toggling active skills. |
| 25 | 燃燒 | 燃烧 | `path_terrain` | `board_hazard_or_mark` | **FULL** | `zero_trail_burning` `TileFlagBits.BURNING_TILE` Position-bound touch-damage burning tile (burningTileHits <= maxBurningTileHurts). |
| 26 | 黏腐 | 黏腐 | `path_terrain` | `board_hazard_or_mark` | **FULL** | — `TileFlagBits.STICKY_TILE` Position-bound sticky/corrosive tile; touching immediately ends path. |
| 27 | 瘴氣地型 | 瘴气地型 | `path_terrain` | `board_hazard_or_mark` | **FULL** | — `TileFlagBits.MIASMA` Miasma terrain; traversing accumulates % miasma (miasmaHits <= maxMiasmaHits). |
| 28 | 灼熱地型 | 灼热地型 | `path_terrain` | `board_hazard_or_mark` | **UPGRADED** | — `TileFlagBits.SCORCHING_TILE` NEW: Distinguished from Skill #25 Burning! Scorching terrain hurts on CLEARING inside tile, not touching. Added TileFlagBits.SCORCHING_TILE + Board Editor brush + simulator/evaluator tracking. |
| 29 | 火焰地型 | 火焰地型 | `path_terrain` | `board_hazard_or_mark` | **FULL** | `traffic_light` `TileFlagBits.END_REQUIRED` Must finish path on Flame Terrain tile (reuses EndRequired tile flag). |
| 30 | 起結有序 | 起结有序 | `path_terrain` | `board_hazard_or_mark` | **FULL** | `traffic_light` `TileFlagBits.START_ALLOWED + END_REQUIRED` Green 'GO' vortex start + Red vortex end; CvBoardRecognizer V3 auto-detects both + solver startMask/endMask. |
| 31 | 血浴地型 | 血浴地型 | `path_terrain` | `board_hazard_or_mark` | **UPGRADED** | — `TileFlagBits.END_FORBIDDEN` NEW: Bloodbath terrain is passable but FORBIDDEN as path endpoint. Added TileFlagBits.END_FORBIDDEN + Board Editor brush + solver/evaluator endForbiddenMask. |
| 32 | 黑洞 | 黑洞 | `path_terrain` | `board_hazard_or_mark` | **UPGRADED** | — `TileFlagBits.BLACK_HOLE` FIXED: Official spec proves Black Hole is passable and ONLY lethal if path ENDS on it! Moved BlackHole from impassable wall to endForbiddenMask in solver.cpp & evaluator.cpp. |
| 33 | 中毒 | 中毒 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Poison DoT after turn. |
| 34 | 敵人反擊傷害 | 敌人反击伤害 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Enemy counterattack on taking damage. |
| 35 | 碎裂 | 碎裂 | `path_terrain` | `board_hazard_or_mark` | **FULL** | — `TileFlagBits.SHATTERED` Passable tile, orbs inside cannot eliminate (verified in simulator.cpp & Test #13). |
| 36 | 暴風 | 暴风 | `path_terrain` | `board_hazard_or_mark` | **FULL** | — `TileFlagBits.STORM` Impassable AND unmatchable; CvBoardRecognizer auto-detects multi-frame grey tornado veil. |
| 37 | 連擊護盾 | 连击护盾 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Attack hit-count shield (HIT!). |
| 38 | 漆黑照明 | 漆黑照明 | `orb_hazard_mark` | `board_hazard_or_mark` | **FULL** | — Darkness-with-spotlight during drag; pre-drag board capture + auto-swipe completely bypasses visual obstruction. |
| 39 | 混沌值 | 混沌值 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Chaos % death gauge. |
| 40 | 崩壞值 | 崩坏值 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Collapse % death gauge (clears on wave change). |
| 41 | 連環光牢 | 连环光牢 | `path_terrain` | `board_hazard_or_mark` | **FULL** | — `TileFlagBits.STICKY_TILE / NO_PASS` Laser-shot positions stop movement immediately on touch (maps to StickyTile/NoPass). |
| 42 | 漆黑結聚 | 漆黑结聚 | `orb_hazard_mark` | `board_hazard_or_mark` | **FULL** | — Darkness around held orb during drag; pre-drag capture + auto-swipe completely bypasses it. |
| 43 | 腐化 | 腐化 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Corrupted skill meta-tag bypassed by Corrupted Dragonware. |
| 44 | 結界地型 | 结界地型 | `path_terrain` | `direct_board_rule` | **FULL** | — `TileFlagBits.BARRIER_FIRST_WAVE_ONLY` Inside barrier terrain only Wave-1 eliminates (verified in simulator.cpp & Test #23). |
| 45 | 減傷狀態 | 减伤状态 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Enemy % damage reduction buff. |
| 46 | 符石連擊零化 | 符石连击零化 | `element_color` | `direct_board_rule` | **FULL** | `anti_first_wave` Wave-1 forbidden orb nullifies team ATK & counterattacks (MatchTiming.ForbidFirstWaveAllowCascade). |
| 47 | 反饋 | 反馈 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Per-attacker recoil damage. |
| 48 | 幽閉空間 | 幽闭空间 | `orb_hazard_mark` | `board_hazard_or_mark` | **CV_PARTIAL** | — `HazardBits.BLACK_WHITE` All orbs monochrome grayscale; shape/template CV or Board Editor handles it (documented for future TFLite grayscale head). |
| 49 | 移形換影 | 移形换影 | `orb_hazard_mark` | `board_hazard_or_mark` | **CV_PARTIAL** | — Visual orb disguise (e.g. Water looks like Wood); requires player/Board Editor correction. |
| 50 | 能力點代價 | 能力点代价 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Ability-point active skill budget. |
| 51 | 技能重置 | 技能重置 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Resets member skill CD/EP/turns. |
| 52 | 一步一傷 | 一步一伤 | `path_terrain` | `direct_board_rule` | **FULL** | `step_hurt_minimize` Per-step HP deduction; stepPenaltyWeight = 5.0 minimizes path length. |
| 53 | 縮時地型 | 缩时地型 | `path_terrain` | `board_hazard_or_mark` | **FULL** | — `HazardBits.WITHERED_VINE` Touch reduces orb-move time (penalized per touch via witheredVineHits). |
| 54 | 角色符石地型盾 | 角色符石地型盾 | `path_terrain` | `direct_board_rule` | **FULL** | `char_orb_terrain` `TileFlagBits.CHAR_ORB_END` Character Runestone must end inside yellow-framed terrain tile (requireCharOrbEnd + charOrbEndMask). |
| 55 | 亡命爆擊 | 亡命爆击 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Death-rattle self-destruct damage. |
| 56 | 隱身 | 隐身 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Enemy stealth until 1st attack or skill damage. |
| 57 | 首消相同種類盾 | 首消相同种类盾 | `combo` | `direct_board_rule` | **UPGRADED** | `wave1_same_kind` NEW: Wave-1 exact N combos of a single attribute with Heart-orb universal wildcards (心符石通用)! Added RuleConfig.wave1KindMode = SameKindHeartWild + chip + UI + tests. |
| 58 | 首消不同種類盾 | 首消不同种类盾 | `combo` | `direct_board_rule` | **UPGRADED** | `wave1_distinct_kind` NEW: Wave-1 exact N combos of distinct attributes (<=1 per non-Heart element) with Heart-orb universal wildcards (心符石通用)! Added RuleConfig.wave1KindMode = DistinctKindHeartWild + chip + UI + tests. |
| 59 | 煉獄之火 | 炼狱之火 | `path_terrain` | `board_hazard_or_mark` | **FULL** | — `HazardBits.PURGATORY_FIRE` Instant-death blue-flame terrain; strictly impassable hard wall (verified in Test #22). |
| 60 | 步數盾 | 步数盾 | `path_terrain` | `direct_board_rule` | **UPGRADED** | `step_range`, `exact_steps` NEW: Official Slide 1 shows inclusive range [25-30 steps]! Added RuleConfig.minSteps + step_range chip + solver/evaluator range support. |
| 61 | 步數限制 | 步数限制 | `path_terrain` | `direct_board_rule` | **FULL** | `step_limit` Hard max steps per turn (maxSteps). |
| 62 | 回復顛倒 | 回复颠倒 | `element_color` | `direct_board_rule` | **FULL** | `forbid_all_heart` Recovery reversal turns Heart clears into damage -> forbid_all (Heart). |
| 63 | 終結倒數 | 终结倒数 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Lethal turn countdown. |
| 64 | 凝護 | 凝护 | `turn_damage_defense` | `direct_board_rule` | **FULL** | `maximize_element_orbs`, `elem_burst_shield` Condition-only HP drain (e.g. Heart orb clears) -> maximize target orb count. |
| 65 | 囚錮生命力上限 | 囚锢生命力上限 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Max HP lock/seal. |
| 66 | 只能消除首批符石 | 只能消除首批符石 | `combo` | `direct_board_rule` | **UPGRADED** | `no_cascade (first_wave_only)` NEW: Official spec disables ALL cascades after Wave-1! Added RuleConfig.disableCascades in simulator.cpp + no_cascade wave chip. |
| 67 | 累積符石盾 | 累积符石盾 | `orb_count` | `direct_board_rule` | **FULL** | `elem_burst_shield`, `maximize_element_orbs` Single-turn or cumulative N-orb color shield. |
| 68 | 陰之氣 | 阴之气 | `orb_hazard_mark` | `board_hazard_or_mark` | **CV_PARTIAL** | — `HazardBits.PRIORITY` Yin Qi stack aura with top-right golden circular orb marks; prioritize marked orbs via Priority brush. |
| 69 | 陽之氣 | 阳之气 | `orb_hazard_mark` | `board_hazard_or_mark` | **CV_PARTIAL** | — `HazardBits.PRIORITY` Yang Qi stack aura with top-right slashed-sword orb marks; prioritize marked orbs via Priority brush. |
| 70 | 鏽化值 | 锈化值 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Active-skill Rust % gauge. |
| 71 | 石牢 | 石牢 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Blocks Character Runestone activation. |
| 72 | 雷之氣 | 雷之气 | `orb_hazard_mark` | `board_hazard_or_mark` | **CV_PARTIAL** | — `HazardBits.PRIORITY` Thunder Qi stack aura with top-right lightning-removal orb marks (除雷印記); prioritize marked orbs via Priority brush. |
| 73 | 炎之氣 | 炎之气 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Flame Qi aura (ignores shields/willpower; 100% HP drain on kill while active). |
| 74 | EP 代價 | EP 代价 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Multiplies EP skill activation cost. |
| 75 | 無法控制 | 无法控制 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Enemy immune to status effects. |
| 76 | 技能 CD 增加敵技 | 技能 CD 增加敌技 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Increases team active skill CD. |
| 77 | 技能 EP 減少敵技 | 技能 EP 减少敌技 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Decreases team active skill EP. |
| 78 | 角色符石通行地型 | 角色符石通行地型 | `path_terrain` | `board_hazard_or_mark` | **UPGRADED** | `char_orb_terrain` `TileFlagBits.CHAR_ORB_PASS` UPGRADED: Official spec states orbs inside CharOrbPass terrain CANNOT eliminate and ONLY Character Orb can pass! Enforced non-char-orb impassability & unmatchability when charOrbPassOnly mode is active. |
| 79 | 温律臨界 | 温律临界 | `orb_count` | `direct_board_rule` | **FULL** | `elementRules[e].exactFirstWaveOrbs` Wave-1 temperature delta between raising & lowering orb colors (supported via per-element Wave-1 orb counts). |
| 80 | 泥沼地型 | 泥沼地型 | `path_terrain` | `board_hazard_or_mark` | **UPGRADED** | — `TileFlagBits.MIASMA / BURNING_TILE` Mud Terrain: touching reduces move time & inflicts cannot-attack Mud status -> avoid stepping on Mud cells (mapped via Miasma / EndForbidden / Board Editor avoidance). |
| 81 | 寒之氣 | 寒之气 | `team_skill_other` | `direct_board_rule` | **UPGRADED** | `no_cascade` UPGRADED: Cold Qi disables all cascades (只能消除首批符石) -> directly handled by new RuleConfig.disableCascades + no_cascade chip. |
| 82 | 靈罩 | 灵罩 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Spirit Barrier stack counter (0 => 100% HP drain). |
| 83 | 脈流 | 脉流 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Elemental Pulse active-skill attribute lock. |
| 84 | 我方效果欄狀態弱攻 | 我方效果栏状态弱攻 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Damage reduction scaling with player buff count. |
| 85 | 即效永生 | 即效永生 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Enemy full-heals on turn start/end and skill use. |
| 86 | 佑律 | 佑律 | `element_color` | `non_board_mechanic` | **NON_BOARD** | — Replaces removed/exploded orbs with specified element on entry/turn-end/skill. |
| 87 | 起動浮標 | 启动浮标 | `path_terrain` | `direct_board_rule` | **UPGRADED** | `starting_buoy_shield` / `traffic_light` `BoardState.startingBuoyCol` + `TileFlagBits.START_ALLOWED` Full dual-variant support: (A) Member Portrait Starting Buoy (Wiki No.5893/7403 + No.5894 Buoy Character Orb Shield) with dimmed-board linear luminance restoration, automatic glowing portrait beacon + top-left attribute detection, strict vertical portrait pull (`topPx - 0.77*ch`), automatic Wave-1 Character Orb clearance, Board Editor `起动浮标` brush & `starting_buoy_shield` verdict; (B) Board-Cell Starting Buoy (No.6786–6789) via `START_ALLOWED` (Green Vortex). |
| 88 | 次批消除盾 | 次批消除盾 | `combo` | `direct_board_rule` | **FULL** | `second_wave_combo` Wave-2 cascade combo shield (>= N Wave-2 combos, verified in Test #21). |
| 89 | 冰魄暴劫 | 冰魄暴劫 | `element_color` | `direct_board_rule` | **FULL** | `elem_burst_shield + forbid_all` Ice Death-Rattle: maximize Wood, avoid Water. |
| 90 | 熔焰焚爆 | 熔焰焚爆 | `element_color` | `direct_board_rule` | **FULL** | `elem_burst_shield + forbid_all` Flame Death-Rattle: maximize Water, avoid Fire. |
| 91 | 風刃蔓藤 | 风刃蔓藤 | `element_color` | `direct_board_rule` | **FULL** | `elem_burst_shield + forbid_all` Wind Death-Rattle: maximize Fire, avoid Wood. |
| 92 | 聖劍裁決 | 圣剑裁决 | `element_color` | `direct_board_rule` | **FULL** | `elem_burst_shield + forbid_all` Holy Sword Death-Rattle: maximize Dark, avoid Light. |
| 93 | 深淵滅襲 | 深渊灭袭 | `element_color` | `direct_board_rule` | **FULL** | `elem_burst_shield + forbid_all` Abyss Death-Rattle: maximize Light, avoid Dark. |
| 94 | 兩相消耗 | 两相消耗 | `turn_damage_defense` | `non_board_mechanic` | **NON_BOARD** | — Mutual % HP drain when draining enemy HP. |
| 95 | 附加消除 | 附加消除 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Dispels all active status effects. |
| 96 | 灼熱地型 | 灼热地型 | `path_terrain` | `board_hazard_or_mark` | **UPGRADED** | — `TileFlagBits.SCORCHING_TILE` Duplicate/variant encyclopedia entry of Skill #28 Scorching Terrain -> handled by new TileFlagBits.SCORCHING_TILE. |
| 97 | 金穗聖果 | 金穗圣果 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Character-specific skill effect placeholder (金穗聖果). |
| 98 | 蝕骨腐果 | 蚀骨腐果 | `team_skill_other` | `non_board_mechanic` | **NON_BOARD** | — Character-specific skill effect placeholder (蝕骨腐果). |
