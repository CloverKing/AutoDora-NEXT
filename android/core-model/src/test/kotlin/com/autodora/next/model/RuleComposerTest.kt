package com.autodora.next.model

import org.junit.Assert.*
import org.junit.Test

class RuleComposerTest {

    @Test
    fun `compose merges base team preset with multiple enemy wave chips and boosts budget`() {
        val base = TeamBasePreset(
            id = "fire_2match",
            name = "Fire/Heart 2-Match",
            baseConfig = RuleConfig(
                allowDiagonalMove = false,
                beamWidth = 120,
                elementRules = List(6) { idx ->
                    if (idx == Element.FIRE.code || idx == Element.HEART.code) {
                        ElementRule(minMatchCount = 2, priority = ElementPriority.HIGH)
                    } else {
                        ElementRule()
                    }
                }
            )
        )

        val chips = listOf(
            WaveShieldChip("five_elements_enchanted", "五属强化盾", ShieldCategory.COUNT_AND_ELEMENT),
            WaveShieldChip("digital_orb_count", "数字盾(18粒)", ShieldCategory.COUNT_AND_ELEMENT, paramInt = 18),
            WaveShieldChip("anti_first_wave", "反首消暗", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = Element.DARK.bitMask),
            WaveShieldChip("cross_shield", "双十字盾", ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 2)
        )

        val composed = RuleComposer.compose(base, chips)

        // Base team 2-match preserved
        assertEquals(2, composed.elementRules[Element.FIRE.code].minMatchCount)
        assertEquals(2, composed.elementRules[Element.HEART.code].minMatchCount)

        // Enemy wave shields applied
        assertEquals(RuleComposer.FIVE_ELEMENTS_MASK, composed.requiredTotalElementsMask)
        assertEquals(RuleComposer.FIVE_ELEMENTS_MASK, composed.requiredEnchantedElementsMask)
        assertEquals(18, composed.exactFirstWaveTotalOrbs)
        assertEquals(MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE, composed.elementRules[Element.DARK.code].timing)
        assertEquals(2, composed.crossShield.requiredCount)

        // Cooperative high-difficulty auto-boost activated WITHOUT silently overriding allowDiagonalMove=false!
        assertFalse("Must strictly preserve user allowDiagonalMove=false even on high-difficulty shields", composed.allowDiagonalMove)
        assertTrue(composed.beamWidth >= 260)
        assertTrue(composed.maxTimeMs >= 800)
    }

    @Test
    fun `compose strictly preserves allowDiagonalMove false for puzzle shields`() {
        val base = TeamBasePreset(
            id = "no_diag",
            name = "Orthogonal Only",
            baseConfig = RuleConfig(allowDiagonalMove = false, allowDiagonalMatch = false)
        )
        val chips = listOf(
            WaveShieldChip("puzzle_shield", "普通拼图", ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 0)
        )
        val composed = RuleComposer.compose(base, chips)
        assertTrue(composed.puzzle1.enabled)
        assertFalse(composed.puzzle1.strictNoExternalTouch)
        assertFalse("Puzzle shield must never silently enable 8-way diagonal moves", composed.allowDiagonalMove)
        assertFalse("Puzzle shield must never silently enable diagonal matching", composed.allowDiagonalMatch)
        assertTrue(composed.beamWidth >= 260)
    }

    @Test
    fun `compose auto-enables puzzle rule when board contains puzzle tiles`() {
        val cells = List(30) { CellState(Element.WATER) }
        val flags = MutableList(30) { TileFlagBits.NONE }
        flags[7] = TileFlagBits.PUZZLE_1
        flags[8] = TileFlagBits.PUZZLE_1
        val board = BoardState(cols = 6, rows = 5, cells = cells, tileFlags = flags)

        val composed = RuleComposer.compose(TeamBasePreset("default", "Default"), emptyList(), board)
        assertTrue(composed.puzzle1.enabled)
        assertFalse(composed.puzzle2.enabled)
    }

    @Test
    fun `compose supports Phase 2_5 13-year ToS wave shield chips`() {
        val chips = listOf(
            WaveShieldChip("second_wave_combo", "次批消除", ShieldCategory.COMBO, paramInt = 2),
            WaveShieldChip("equal_pair_light_dark", "首消光暗同等", ShieldCategory.COMBO),
            WaveShieldChip("trail_corrosion", "轨迹腐蚀12格", ShieldCategory.HAZARD_AND_PATH, paramInt = 12),
            WaveShieldChip("race_orb_shield", "种族符石盾", ShieldCategory.COUNT_AND_ELEMENT)
        )
        val composed = RuleComposer.compose(TeamBasePreset("default", "Default"), chips)
        assertEquals(2, composed.minSecondWaveCombo)
        assertTrue(composed.requireEqualPairCombos)
        assertTrue(composed.trailCorrosionEnabled)
        assertEquals(12, composed.requiredUniqueCellsVisited)
        assertTrue(composed.requireAnyRaceOrb)
    }

    @Test
    fun `compose supports parameterized combo, arbitrary element, and decoupled puzzle shields`() {
        val customElemMask = Element.WATER.bitMask or Element.DARK.bitMask or Element.HEART.bitMask
        val puzzlePacked = 0 or 2 or ((Element.FIRE.code + 1) shl 2) or (3 shl 8) // Normal + Enchanted + Fire + Cross shape
        val chips = listOf(
            WaveShieldChip("min_first_combo", "首消 ≥5C", ShieldCategory.COMBO, paramInt = 5),
            WaveShieldChip("odd_combo", "奇数 Combo (总)", ShieldCategory.COMBO, paramInt = 1),
            WaveShieldChip("min_distinct_elements", "≥ 4 属", ShieldCategory.COUNT_AND_ELEMENT, paramInt = 4),
            WaveShieldChip("require_elements", "水暗心+", ShieldCategory.COUNT_AND_ELEMENT, paramInt = 1, paramElementMask = customElemMask),
            WaveShieldChip("puzzle_shield", "普通拼图·含强化·火", ShieldCategory.SHAPE_AND_PUZZLE, paramInt = puzzlePacked),
            WaveShieldChip(
                "cross_shield",
                "双十字·水火",
                ShieldCategory.SHAPE_AND_PUZZLE,
                paramInt = 2 or 0x20,
                paramElementMask = Element.WATER.bitMask or Element.FIRE.bitMask
            ),
            WaveShieldChip("equal_pair_light_dark", "水=火", ShieldCategory.COMBO, paramElementMask = (Element.FIRE.code shl 4) or Element.WATER.code)
        )

        val composed = RuleComposer.compose(TeamBasePreset("default", "Default"), chips)
        assertEquals(5, composed.minFirstWaveCombo)
        assertEquals(ComboParity.ODD, composed.comboParity)
        assertFalse(composed.comboParityFirstWaveOnly)
        assertEquals(4, composed.minDistinctElements)
        assertEquals(customElemMask, composed.requiredTotalElementsMask)
        assertEquals(customElemMask, composed.requiredEnchantedElementsMask)

        // Normal Puzzle + Enchanted + Fire (decoupled from Strict!)
        assertTrue(composed.puzzle1.enabled)
        assertFalse(composed.puzzle1.strictNoExternalTouch)
        assertTrue(composed.puzzle1.requireEnchanted)
        assertEquals(Element.FIRE.code, composed.puzzle1.requiredElement)

        // Multi-cross distinct colors + specific color mask
        assertEquals(2, composed.crossShield.requiredCount)
        assertTrue(composed.crossShield.requireDistinctColors)
        assertEquals(Element.WATER.bitMask or Element.FIRE.bitMask, composed.crossShield.requiredElementsMask)

        // Arbitrary equal-combo element pair
        assertTrue(composed.requireEqualPairCombos)
        assertEquals(Element.WATER.code, composed.equalPairElemA)
        assertEquals(Element.FIRE.code, composed.equalPairElemB)
    }

    @Test
    fun `compose supports 7-orb and 9-orb global goals and special orb-type shields`() {
        val base7 = TeamBasePreset("g7", "7-Orb", baseConfig = RuleConfig(goalMode = StrategyGoalMode.SEVEN_ORBS))
        val composed7 = RuleComposer.compose(base7, emptyList())
        assertEquals(TargetShape.COUNT_7, composed7.elementRules[0].targetShape)

        val base9 = TeamBasePreset("g9", "9-Orb", baseConfig = RuleConfig(goalMode = StrategyGoalMode.NINE_ORBS))
        val chips = listOf(
            WaveShieldChip("any_enchanted_orb_shield", "强化符石首消", ShieldCategory.COUNT_AND_ELEMENT),
            WaveShieldChip("char_orb_first_wave", "角色符石首消", ShieldCategory.COUNT_AND_ELEMENT),
            WaveShieldChip("forbid_weakened_orb", "避消弱化符石", ShieldCategory.COUNT_AND_ELEMENT),
            WaveShieldChip("luminous_orb_shield", "光晕符石首消", ShieldCategory.COUNT_AND_ELEMENT)
        )
        val composed9 = RuleComposer.compose(base9, chips)
        assertEquals(TargetShape.COUNT_9, composed9.elementRules[1].targetShape)
        assertTrue(composed9.requireAnyEnchantedOrb)
        assertTrue(composed9.requireCharOrbFirstWave)
        assertTrue(composed9.forbidWeakenedOrbs)
        assertTrue(composed9.requireLuminousOrbClear)
    }

    @Test
    fun `compose supports grouped parameterized goals (N-color, X-orb) and per-color independent enchanted shield`() {
        val baseSingle7Fire = TeamBasePreset(
            "g_single7",
            "Fire 7",
            baseConfig = RuleConfig(
                goalMode = StrategyGoalMode.SINGLE_COLOR_X_ORBS,
                goalParamX = 7,
                goalTargetElement = Element.FIRE.code
            )
        )
        val comp7 = RuleComposer.compose(baseSingle7Fire, emptyList())
        assertEquals(TargetShape.COUNT_7, comp7.elementRules[Element.FIRE.code].targetShape)
        assertEquals(ElementPriority.HIGH, comp7.elementRules[Element.FIRE.code].priority)
        assertEquals(TargetShape.ANY, comp7.elementRules[Element.WATER.code].targetShape)

        val baseMulti = TeamBasePreset(
            "g_multi",
            "3-Color 6-Orb",
            baseConfig = RuleConfig(
                goalMode = StrategyGoalMode.MULTI_COLOR_X_ORBS,
                goalParamN = 3,
                goalParamX = 6
            )
        )
        // Per-color independent enchanted: Water (normal) + Fire (enchanted!) + Dark (enchanted!)
        val reqMask = Element.WATER.bitMask or Element.FIRE.bitMask or Element.DARK.bitMask
        val enchMask = Element.FIRE.bitMask or Element.DARK.bitMask
        val chip = WaveShieldChip(
            "require_elements",
            "水火+暗+",
            ShieldCategory.COUNT_AND_ELEMENT,
            paramInt = 0,
            paramElementMask = reqMask or (enchMask shl 8)
        )
        val compMulti = RuleComposer.compose(baseMulti, listOf(chip))
        assertEquals(3, compMulti.minDistinctElements)
        assertEquals(TargetShape.COUNT_6, compMulti.elementRules[0].targetShape)
        assertEquals(TargetShape.COUNT_6, compMulti.elementRules[2].targetShape)
        assertEquals(TargetShape.ANY, compMulti.elementRules[3].targetShape)
        // Official Skills #4-#7 allow cascades by default (requiredTotalElementsMask), unless Wave-1 bit (paramInt=2) is set!
        assertEquals(reqMask, compMulti.requiredTotalElementsMask)
        assertEquals(0, compMulti.requiredFirstWaveElementsMask)
        assertEquals(enchMask, compMulti.requiredEnchantedElementsMask)

        val chipWave1Strict = chip.copy(paramInt = 2)
        val compWave1 = RuleComposer.compose(baseMulti, listOf(chipWave1Strict))
        assertEquals(reqMask, compWave1.requiredFirstWaveElementsMask)
    }

    @Test
    fun `compose and verdicts support official 98-skill encyclopedia upgrades`() {
        val equalWfgMask = 0x100 or Element.WATER.bitMask or Element.FIRE.bitMask or Element.WOOD.bitMask
        val stepRangePacked = 25 or (30 shl 8)
        val chips = listOf(
            WaveShieldChip("min_first_elem_combo", "首消≥5组属性珠", ShieldCategory.COMBO, paramInt = 5),
            WaveShieldChip("wave1_distinct_kind", "首消不同种类 5C", ShieldCategory.COMBO, paramInt = 5),
            WaveShieldChip("equal_pair_light_dark", "水=火=木", ShieldCategory.COMBO, paramElementMask = equalWfgMask),
            WaveShieldChip("step_range", "步数盾 25-30", ShieldCategory.HAZARD_AND_PATH, paramInt = stepRangePacked),
            WaveShieldChip("no_cascade", "禁天降(仅首批)", ShieldCategory.COMBO),
            WaveShieldChip("unmatchable_elements", "符石禁咒:水", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = Element.WATER.bitMask),
            WaveShieldChip("l_shape", "L形盾", ShieldCategory.SHAPE_AND_PUZZLE)
        )
        val composed = RuleComposer.compose(TeamBasePreset("default", "Default"), chips)
        assertEquals(5, composed.minFirstWaveElementalCombo)
        assertEquals(5, composed.exactFirstWaveCombo)
        assertEquals(Wave1KindMode.DISTINCT_KIND_HEART_WILD, composed.wave1KindMode)
        assertEquals(Element.WATER.bitMask or Element.FIRE.bitMask or Element.WOOD.bitMask, composed.equalComboElementsMask)
        assertEquals(25, composed.minSteps)
        assertEquals(30, composed.maxSteps)
        assertTrue(composed.disableCascades)
        assertEquals(Element.WATER.bitMask, composed.unmatchableElementsMask)
        assertTrue("Official #16 L-shape must default to strict 5-orb shape", composed.shapeShield.strictShape)

        // Verify ShieldVerdictEvaluator catches Heart-only non-elemental failure (#18) and step_range [25..30] (#60)!
        val board = BoardState(cols = 6, rows = 5, cells = List(30) { CellState(Element.WATER) })
        val solWith4Elem1Heart = SolutionPath(
            start = CellCoord(0, 0),
            usedCharacterOrb = false,
            characterOrbCol = 0,
            moves = List(27) { Direction.RIGHT },
            waypoints = listOf(CellCoord(0, 0), CellCoord(0, 1)),
            stepCount = 27,
            firstWaveCombo = 5,
            totalCombo = 5,
            firstWaveOrbs = 15,
            totalOrbs = 15,
            allHardConstraintsMet = false,
            satisfiedFlags = ShieldStatusBits.STEP_LIMIT or ShieldStatusBits.WAVE1_KIND_SHIELD,
            failedFlags = ShieldStatusBits.COMBO_SHIELD,
            score = 0.0,
            tag = "Test",
            groups = listOf(
                MatchSummary(Element.WATER, 3, 1, false),
                MatchSummary(Element.FIRE, 3, 1, false),
                MatchSummary(Element.WOOD, 3, 1, false),
                MatchSummary(Element.LIGHT, 3, 1, false),
                MatchSummary(Element.HEART, 3, 1, false)
            )
        )
        val verdicts = ShieldVerdictEvaluator.evaluate(
            listOf(
                WaveShieldChip("min_first_elem_combo", "首消≥5组属性珠", ShieldCategory.COMBO, paramInt = 5),
                WaveShieldChip("wave1_distinct_kind", "首消不同种类 5C", ShieldCategory.COMBO, paramInt = 5),
                WaveShieldChip("step_range", "步数盾 25-30", ShieldCategory.HAZARD_AND_PATH, paramInt = stepRangePacked)
            ),
            board,
            solWith4Elem1Heart
        ).associateBy { it.chipId }
        assertEquals("4 elemental + 1 Heart must FAIL #18 min_first_elem_combo >=5", ShieldVerdictStatus.FAILED, verdicts.getValue("min_first_elem_combo").status)
        assertEquals("4 distinct + 1 Heart wildcard must PASS #58 wave1_distinct_kind 5C", ShieldVerdictStatus.PASSED, verdicts.getValue("wave1_distinct_kind").status)
        assertEquals("27 steps inside [25..30] must PASS #60 step_range", ShieldVerdictStatus.PASSED, verdicts.getValue("step_range").status)
    }

    @Test
    fun `compose handles traffic_light chip and auto-detected start end markers without enabling diagonal move`() {
        val base = TeamBasePreset(
            id = "bl_ortho",
            name = "BottomLeft Ortho",
            baseConfig = RuleConfig(
                allowDiagonalMove = false,
                startPreset = StartPointPreset.BOTTOM_LEFT,
                beamWidth = 140
            )
        )
        // Case 1: explicit traffic_light wave chip
        val chip = WaveShieldChip("traffic_light", "红绿灯(起终点)", ShieldCategory.HAZARD_AND_PATH)
        val composedChip = RuleComposer.compose(base, listOf(chip))
        assertEquals(StartPointPreset.ANY, composedChip.startPreset)
        assertFalse(composedChip.allowDiagonalMove)
        assertTrue(composedChip.beamWidth >= 260)

        // Case 2: auto-detected START_ALLOWED + END_REQUIRED on board even without explicit chip
        val flags = MutableList(30) { TileFlagBits.NONE }
        flags[1 * 6 + 5] = TileFlagBits.START_ALLOWED
        flags[3 * 6 + 2] = TileFlagBits.END_REQUIRED
        val board = BoardState(cols = 6, rows = 5, cells = List(30) { CellState(Element.LIGHT) }, tileFlags = flags)
        val composedAuto = RuleComposer.compose(base, emptyList(), board)
        assertEquals(StartPointPreset.ANY, composedAuto.startPreset)
        assertFalse(composedAuto.allowDiagonalMove)
        assertTrue(composedAuto.beamWidth >= 260)
    }

    @Test
    fun `ShieldVerdictEvaluator reports exact per-shield pass fail and undetected verdicts across 3 composite shields`() {
        val chips = listOf(
            WaveShieldChip("traffic_light", "红绿灯(起终点)", ShieldCategory.HAZARD_AND_PATH),
            WaveShieldChip("anti_first_wave", "反首消叠珠:暗", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = Element.DARK.bitMask),
            WaveShieldChip("min_combo", "总连击 ≥6", ShieldCategory.COMBO, paramInt = 6)
        )
        val flagsBoth = MutableList(30) { TileFlagBits.NONE }.apply {
            this[4 * 6 + 0] = TileFlagBits.START_ALLOWED
            this[3 * 6 + 5] = TileFlagBits.END_REQUIRED
        }
        val boardBoth = BoardState(cols = 6, rows = 5, cells = List(30) { CellState(Element.LIGHT) }, tileFlags = flagsBoth)

        // Case A: All 3 satisfied (Dark only in wave 2 cascade)
        val solAllMet = SolutionPath(
            start = CellCoord(4, 0),
            usedCharacterOrb = false,
            characterOrbCol = 0,
            moves = listOf(Direction.RIGHT),
            waypoints = listOf(CellCoord(4, 0), CellCoord(3, 5)),
            stepCount = 42,
            firstWaveCombo = 5,
            totalCombo = 7,
            firstWaveOrbs = 15,
            totalOrbs = 21,
            allHardConstraintsMet = true,
            satisfiedFlags = ShieldStatusBits.TRAFFIC_LIGHT or ShieldStatusBits.FORBID_FIRST_WAVE or ShieldStatusBits.COMBO_SHIELD,
            failedFlags = 0,
            score = 1001000.0,
            tag = "Best",
            groups = listOf(
                MatchSummary(Element.WATER, 3, 1, false),
                MatchSummary(Element.WOOD, 3, 1, false),
                MatchSummary(Element.LIGHT, 3, 1, false),
                MatchSummary(Element.DARK, 3, 2, false)
            )
        )
        val vAll = ShieldVerdictEvaluator.evaluate(chips, boardBoth, solAllMet)
        assertEquals(3, vAll.size)
        assertTrue("All 3 shields must report PASSED", vAll.all { it.status == ShieldVerdictStatus.PASSED })

        // Case B: Anti-first-wave Dark violated (Dark matched in wave 1), while Traffic Light & >=6C passed
        val solFailDark = solAllMet.copy(
            allHardConstraintsMet = false,
            failedFlags = ShieldStatusBits.FORBID_FIRST_WAVE,
            groups = listOf(
                MatchSummary(Element.WATER, 3, 1, false),
                MatchSummary(Element.DARK, 3, 1, false),
                MatchSummary(Element.LIGHT, 3, 1, false)
            )
        )
        val vFail = ShieldVerdictEvaluator.evaluate(chips, boardBoth, solFailDark).associateBy { it.chipId }
        assertEquals(ShieldVerdictStatus.PASSED, vFail.getValue("traffic_light").status)
        assertEquals(ShieldVerdictStatus.FAILED, vFail.getValue("anti_first_wave").status)
        assertEquals(ShieldVerdictStatus.PASSED, vFail.getValue("min_combo").status)

        // Case C: Only Start detected on board while End was missed -> Traffic Light must report UNDETECTED(missing_end)
        val flagsStartOnly = MutableList(30) { TileFlagBits.NONE }.apply {
            this[4 * 6 + 0] = TileFlagBits.START_ALLOWED
        }
        val boardStartOnly = BoardState(cols = 6, rows = 5, cells = List(30) { CellState(Element.LIGHT) }, tileFlags = flagsStartOnly)
        val vPartial = ShieldVerdictEvaluator.evaluate(chips, boardStartOnly, solAllMet).associateBy { it.chipId }
        assertEquals(ShieldVerdictStatus.UNDETECTED, vPartial.getValue("traffic_light").status)
        assertEquals("missing_end", vPartial.getValue("traffic_light").detailTag)
    }

    @Test
    fun `runestone status hazard bits match C++20 solver ABI`() {
        // Verify bit positions for official ToS Runestone Statuses (#5 Coagulated, #12 EvilEnergy, #17 MagicOrb, #20 BloodThorn)
        assertEquals(1 shl 23, HazardBits.COAGULATED)
        assertEquals(1 shl 24, HazardBits.EVIL_ENERGY)
        assertEquals(1 shl 25, HazardBits.MAGIC_ORB)
        assertEquals(1 shl 26, HazardBits.BLOOD_THORN)
        val combined = HazardBits.COAGULATED or HazardBits.EVIL_ENERGY or HazardBits.MAGIC_ORB or HazardBits.BLOOD_THORN
        assertEquals(4, Integer.bitCount(combined))
    }

    @Test
    fun `compose maps race_orb_shield to specific RaceType bitmask or generic any-race mode`() {
        val base = TeamBasePreset("default", "Default")
        // Generic any-race shield (paramInt == 0)
        val generic = RuleComposer.compose(
            base,
            listOf(WaveShieldChip("race_orb_shield", "种族符石首消", ShieldCategory.COUNT_AND_ELEMENT, paramInt = 0))
        )
        assertTrue(generic.requireAnyRaceOrb)
        assertEquals(0, generic.requiredRaceTypesMask)

        // Specific God + Demon race shield
        val mask = RaceType.GOD.bitMask or RaceType.DEMON.bitMask
        val specific = RuleComposer.compose(
            base,
            listOf(WaveShieldChip("race_orb_shield", "种族符石首消", ShieldCategory.COUNT_AND_ELEMENT, paramInt = mask))
        )
        assertFalse(specific.requireAnyRaceOrb)
        assertEquals(mask, specific.requiredRaceTypesMask)
    }

    @Test
    fun `compose auto-engages Starting Buoy col, attribute, and Wave-1 clear while respecting forbidden elements`() {
        val base = TeamBasePreset(
            id = "default",
            name = "Default",
            baseConfig = RuleConfig(
                startPreset = StartPointPreset.BOTTOM_LEFT,
                autoDetectStartingBuoy = true,
                useCharacterOrb = false
            )
        )
        // Board with Slot-6 Wood Starting Buoy and >= 2 Wood orbs on board
        val cells = MutableList(30) { CellState(Element.DARK) }.apply {
            this[10] = CellState(Element.WOOD)
            this[11] = CellState(Element.WOOD)
            this[12] = CellState(Element.WOOD)
        }
        val buoyBoard = BoardState(
            cols = 6,
            rows = 5,
            cells = cells,
            startingBuoyCol = 5,
            startingBuoyElement = Element.WOOD
        )

        // Case 1: Zero-config auto-detection breaks BOTH No.5893 & No.5894
        val composed = RuleComposer.compose(base, emptyList(), buoyBoard)
        assertTrue(composed.useCharacterOrb)
        assertEquals(5, composed.characterOrbCol)
        assertEquals(Element.WOOD, composed.characterOrbElement)
        assertEquals(StartPointPreset.ANY, composed.startPreset)
        assertTrue(composed.requireCharOrbFirstWave)

        // Case 2: If Wood is explicitly Anti-First-Wave, do NOT force conflicting Wave-1 Wood elimination
        val antiWood = WaveShieldChip(
            "anti_first_wave",
            "反首消:木",
            ShieldCategory.COUNT_AND_ELEMENT,
            paramElementMask = Element.WOOD.bitMask
        )
        val composedConflict = RuleComposer.compose(base, listOf(antiWood), buoyBoard)
        assertTrue(composedConflict.useCharacterOrb)
        assertEquals(5, composedConflict.characterOrbCol)
        assertFalse(composedConflict.requireCharOrbFirstWave)

        // Case 3: ShieldVerdictEvaluator reports PASSED / FAILED / UNDETECTED for starting_buoy_shield
        val buoyChip = WaveShieldChip("starting_buoy_shield", "起动浮标盾", ShieldCategory.COUNT_AND_ELEMENT)
        val solPass = SolutionPath(
            start = CellCoord(0, 5),
            usedCharacterOrb = true,
            characterOrbCol = 5,
            moves = listOf(Direction.DOWN),
            waypoints = listOf(CellCoord(0, 5), CellCoord(1, 5)),
            stepCount = 1,
            firstWaveCombo = 3,
            totalCombo = 3,
            firstWaveOrbs = 9,
            totalOrbs = 9,
            allHardConstraintsMet = true,
            satisfiedFlags = ShieldStatusBits.CHAR_ORB_TERRAIN,
            failedFlags = 0,
            score = 1000.0,
            tag = "Best",
            groups = listOf(MatchSummary(Element.WOOD, 3, 1, false))
        )
        val vPass = ShieldVerdictEvaluator.evaluate(listOf(buoyChip), buoyBoard, solPass)
        assertEquals(ShieldVerdictStatus.PASSED, vPass.single().status)

        val solFail = solPass.copy(allHardConstraintsMet = false, failedFlags = ShieldStatusBits.CHAR_ORB_TERRAIN)
        val vFail = ShieldVerdictEvaluator.evaluate(listOf(buoyChip), buoyBoard, solFail)
        assertEquals(ShieldVerdictStatus.FAILED, vFail.single().status)

        val noBuoyBoard = buoyBoard.copy(startingBuoyCol = -1, startingBuoyElement = Element.UNKNOWN)
        val vUndetected = ShieldVerdictEvaluator.evaluate(listOf(buoyChip), noBuoyBoard, solPass.copy(usedCharacterOrb = false))
        assertEquals(ShieldVerdictStatus.UNDETECTED, vUndetected.single().status)
    }

    @Test
    fun `compose and verdicts support v0_9_0 499-skill gap closure and custom priority reserve hard marks`() {
        val chips = listOf(
            WaveShieldChip("max_combo", "连击上限 ≤6", ShieldCategory.COMBO, paramInt = 6),
            WaveShieldChip("forbid_cross", "避消十字(十之诅咒)", ShieldCategory.SHAPE_AND_PUZZLE),
            WaveShieldChip("square_3x3", "正方 3×3 盾", ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1, paramElementMask = Element.DARK.bitMask),
            WaveShieldChip("keep_element_at_least_one", "符石加护:心", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = Element.HEART.bitMask),
            WaveShieldChip("max_element_orbs", "限消水 ≤5粒", ShieldCategory.COUNT_AND_ELEMENT, paramInt = 5, paramElementMask = Element.WATER.bitMask),
            WaveShieldChip("exact_steps", "恰好 20 步", ShieldCategory.HAZARD_AND_PATH, paramInt = 20)
        )
        val composed = RuleComposer.compose(TeamBasePreset("default", "Default"), chips)
        assertEquals(6, composed.maxTotalCombo)
        assertTrue(composed.crossShield.forbidCross)
        assertEquals(0, composed.crossShield.requiredCount)
        assertEquals(1, composed.shapeShield.requireSquare3x3Count)
        assertEquals(Element.DARK.bitMask, composed.shapeShield.sq3x3ElementsMask)
        assertEquals(ElementPriority.KEEP_AT_LEAST_ONE, composed.elementRules[Element.HEART.code].priority)
        assertEquals(5, composed.elementRules[Element.WATER.code].maxTotalOrbs)
        assertEquals(20, composed.exactSteps)
        assertEquals(20, composed.minSteps)
        assertEquals(20, composed.maxSteps)

        // Verify custom PRIORITY & RESERVE verdicts in ShieldVerdictEvaluator
        val customCells = MutableList(30) { CellState(Element.WATER) }
        customCells[0] = CellState(Element.FIRE, hazards = HazardBits.PRIORITY)
        customCells[1] = CellState(Element.FIRE, hazards = HazardBits.PRIORITY)
        customCells[29] = CellState(Element.HEART, hazards = HazardBits.RESERVE)
        val customBoard = BoardState(cols = 6, rows = 5, cells = customCells)

        val solPass = SolutionPath(
            start = CellCoord(0, 0),
            usedCharacterOrb = false,
            characterOrbCol = 0,
            moves = List(20) { Direction.RIGHT },
            waypoints = listOf(CellCoord(0, 0), CellCoord(0, 1)),
            stepCount = 20,
            firstWaveCombo = 5,
            totalCombo = 5,
            firstWaveOrbs = 15,
            totalOrbs = 15,
            allHardConstraintsMet = true,
            satisfiedFlags = ShieldStatusBits.COMBO_SHIELD or ShieldStatusBits.CROSS_SHIELD or
                ShieldStatusBits.SHAPE_SHIELD or ShieldStatusBits.ELEMENT_SHIELD or
                ShieldStatusBits.STEP_LIMIT or ShieldStatusBits.CUSTOM_PRIORITY_CLEAR or ShieldStatusBits.CUSTOM_RESERVE_KEEP,
            failedFlags = 0,
            score = 1200.0,
            tag = "Best",
            groups = listOf(
                MatchSummary(Element.DARK, 9, 1, false),
                MatchSummary(Element.FIRE, 3, 1, false),
                MatchSummary(Element.WATER, 3, 1, false)
            )
        )
        val verdicts = ShieldVerdictEvaluator.evaluate(chips, customBoard, solPass).associateBy { it.chipId }
        assertEquals(ShieldVerdictStatus.PASSED, verdicts.getValue("max_combo").status)
        assertEquals(ShieldVerdictStatus.PASSED, verdicts.getValue("forbid_cross").status)
        assertEquals(ShieldVerdictStatus.PASSED, verdicts.getValue("square_3x3").status)
        assertEquals(ShieldVerdictStatus.PASSED, verdicts.getValue("exact_steps").status)
        assertEquals(ShieldVerdictStatus.PASSED, verdicts.getValue("board_custom_priority").status)
        assertEquals("2", verdicts.getValue("board_custom_priority").detailTag)
        assertEquals(ShieldVerdictStatus.PASSED, verdicts.getValue("board_custom_reserve").status)
        assertEquals("1", verdicts.getValue("board_custom_reserve").detailTag)
    }
}
