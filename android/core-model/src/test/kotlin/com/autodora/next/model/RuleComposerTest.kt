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

        // Cooperative high-difficulty auto-boost activated
        assertTrue(composed.allowDiagonalMove)
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
}
