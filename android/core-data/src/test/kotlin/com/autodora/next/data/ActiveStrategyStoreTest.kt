package com.autodora.next.data

import com.autodora.next.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ActiveStrategyStoreTest {

    @Before
    fun setUp() {
        ActiveStrategyStore.clearAll()
    }

    @After
    fun tearDown() {
        ActiveStrategyStore.clearAll()
    }

    @Test
    fun `manual user chip overrides auto OCR chip in same exclusive group (tier 1 beats tier 2)`() {
        val manualCombo = WaveShieldChip("exact_first_combo", "首消 6C", ShieldCategory.COMBO, autoDetected = false, paramInt = 6)
        ActiveStrategyStore.upsertChip(manualCombo)

        val autoConflict = WaveShieldChip("min_combo", "≥ 8C", ShieldCategory.COMBO, autoDetected = true, paramInt = 8)
        val autoNonConflict = WaveShieldChip("five_elements", "五属盾", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = true)

        val applied = ActiveStrategyStore.applyAutoDetectedChips(listOf(autoConflict, autoNonConflict))
        assertEquals(1, applied)

        val chips = ActiveStrategyStore.activeChips.value
        assertEquals(2, chips.size)
        assertTrue(chips.any { it.id == "exact_first_combo" && !it.autoDetected && it.paramInt == 6 })
        assertTrue(chips.any { it.id == "five_elements" && it.autoDetected })
        assertFalse(chips.any { it.id == "min_combo" })
    }

    @Test
    fun `dismissing an auto chip prevents re-injection within the same wave until wave advance`() {
        val autoChip = WaveShieldChip("cross_shield", "十字盾", ShieldCategory.SHAPE_AND_PUZZLE, autoDetected = true, paramInt = 1)
        assertEquals(1, ActiveStrategyStore.applyAutoDetectedChips(listOf(autoChip)))
        assertEquals(1, ActiveStrategyStore.activeChips.value.size)

        // User dismisses auto chip manually with ×
        ActiveStrategyStore.removeChip("cross_shield")
        assertTrue(ActiveStrategyStore.activeChips.value.isEmpty())

        // Same popup re-scanned later in the SAME wave -> must NOT bounce back!
        assertEquals(0, ActiveStrategyStore.applyAutoDetectedChips(listOf(autoChip)))
        assertTrue(ActiveStrategyStore.activeChips.value.isEmpty())

        // Wave advances -> clearAutoDetectedChips resets dismissedAutoChipIds
        ActiveStrategyStore.clearAutoDetectedChips()
        assertEquals(1, ActiveStrategyStore.applyAutoDetectedChips(listOf(autoChip)))
        assertEquals(1, ActiveStrategyStore.activeChips.value.size)
    }

    @Test
    fun `clearAutoDetectedChips preserves manual team chips and restores bypassed grey skills manually`() {
        val manualHeart = WaveShieldChip("anti_first_wave", "反首消心", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = false, paramElementMask = Element.HEART.bitMask)
        val autoSteps = WaveShieldChip("step_range", "步数盾 20-25", ShieldCategory.HAZARD_AND_PATH, autoDetected = true, paramInt = (25 shl 8) or 20)
        val greyPuzzle = BypassedEnemySkill(
            titleZhTw = "指定形狀盾",
            chips = listOf(WaveShieldChip("puzzle_shield", "严格拼图盾", ShieldCategory.SHAPE_AND_PUZZLE, autoDetected = true, paramInt = 1))
        )

        ActiveStrategyStore.upsertChip(manualHeart)
        ActiveStrategyStore.applyAutoDetectedChips(listOf(autoSteps), bypassed = listOf(greyPuzzle))
        assertEquals(2, ActiveStrategyStore.activeChips.value.size)
        assertEquals(1, ActiveStrategyStore.bypassedGreySkills.value.size)

        // Wave advance clears ONLY auto chip + bypassed grey skills, keeping manualHeart!
        val cleared = ActiveStrategyStore.clearAutoDetectedChips()
        assertEquals(1, cleared)
        assertEquals(1, ActiveStrategyStore.activeChips.value.size)
        assertEquals("anti_first_wave", ActiveStrategyStore.activeChips.value[0].id)
        assertTrue(ActiveStrategyStore.bypassedGreySkills.value.isEmpty())

        // Test restoring a bypassed skill promotes it to a manual chip
        ActiveStrategyStore.applyAutoDetectedChips(emptyList(), bypassed = listOf(greyPuzzle))
        assertEquals(1, ActiveStrategyStore.restoreBypassedSkill(greyPuzzle))
        assertTrue(ActiveStrategyStore.bypassedGreySkills.value.isEmpty())
        assertTrue(ActiveStrategyStore.activeChips.value.any { it.id == "puzzle_shield" && !it.autoDetected })
    }
}
