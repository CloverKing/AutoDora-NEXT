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
}
