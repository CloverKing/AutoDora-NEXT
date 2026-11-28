package com.autodora.next.data

import com.autodora.next.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ActiveStageScriptStoreTest {

    @Before
    fun setUp() {
        ActiveStageScriptStore.clearActiveScript()
        ActiveStrategyStore.clearAll()
    }

    @After
    fun tearDown() {
        ActiveStageScriptStore.clearActiveScript()
        ActiveStrategyStore.clearAll()
    }

    @Test
    fun `activating, stepping, and advancing StageWaveScript preserves manual chips and swaps wave auto chips cleanly`() {
        val manualTeamChip = WaveShieldChip("anti_first_wave", "反首消心", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = false, paramElementMask = Element.HEART.bitMask)
        ActiveStrategyStore.upsertChip(manualTeamChip)

        val script = StageWaveScript(
            stageKey = "s1",
            stageName = "雙週測試關卡",
            waves = listOf(
                StageWaveEntry(
                    waveIndex = 1,
                    chips = listOf(WaveShieldChip("five_elements", "五屬盾", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = true))
                ),
                StageWaveEntry(
                    waveIndex = 2,
                    isBoss = true,
                    chips = listOf(WaveShieldChip("exact_first_combo", "首消 6C", ShieldCategory.COMBO, autoDetected = true, paramInt = 6)),
                    visualHints = BoardVisualHints(hasMonochrome = true)
                )
            )
        )

        val w1 = ActiveStageScriptStore.activateScript(script, startWaveIndex = 1)
        assertEquals(1, w1?.waveIndex)
        assertEquals(2, ActiveStrategyStore.activeChips.value.size)
        assertTrue(ActiveStrategyStore.activeChips.value.any { it.id == "five_elements" && it.autoDetected })
        assertTrue(ActiveStrategyStore.activeChips.value.any { it.id == "anti_first_wave" && !it.autoDetected })
        assertFalse(ActiveStrategyStore.activeVisualHints.value.hasMonochrome)

        // Advance wave 1 -> wave 2: W1 auto chip cleared, W2 auto chip + monochrome hint applied, manualHeart kept!
        val w2 = ActiveStageScriptStore.advanceWaveIfActive()
        assertEquals(2, w2?.waveIndex)
        assertEquals(2, ActiveStageScriptStore.currentWaveIndex.value)
        assertFalse(ActiveStrategyStore.activeChips.value.any { it.id == "five_elements" })
        assertTrue(ActiveStrategyStore.activeChips.value.any { it.id == "exact_first_combo" && it.paramInt == 6 })
        assertTrue(ActiveStrategyStore.activeChips.value.any { it.id == "anti_first_wave" && !it.autoDetected })
        assertTrue(ActiveStrategyStore.activeVisualHints.value.hasMonochrome)

        // Manual step back to wave 1
        val backW1 = ActiveStageScriptStore.stepWave(-1)
        assertEquals(1, backW1?.waveIndex)
        assertTrue(ActiveStrategyStore.activeChips.value.any { it.id == "five_elements" })

        // Step forward to wave 2, then advance past final wave -> clears active script & auto chips cleanly, preserving manualHeart!
        ActiveStageScriptStore.stepWave(+1)
        val afterBoss = ActiveStageScriptStore.advanceWaveIfActive()
        assertNull(afterBoss)
        assertNull(ActiveStageScriptStore.activeScript.value)
        assertEquals(1, ActiveStrategyStore.activeChips.value.size)
        assertEquals("anti_first_wave", ActiveStrategyStore.activeChips.value[0].id)
        assertFalse(ActiveStrategyStore.activeVisualHints.value.hasMonochrome)
    }
}
