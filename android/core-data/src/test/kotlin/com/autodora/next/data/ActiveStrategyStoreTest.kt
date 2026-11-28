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

    @Test
    fun `collaborative icon detection supplements missing shields without downgrading OCR or dismissed chips`() {
        // 1. OCR already mounted a strict Enchanted Fire Puzzle shield
        val ocrPuzzle = WaveShieldChip(
            "puzzle_shield",
            "严格拼图·火+",
            ShieldCategory.SHAPE_AND_PUZZLE,
            autoDetected = true,
            paramInt = 1 or 2 or ((Element.FIRE.code + 1) shl 2)
        )
        ActiveStrategyStore.applyAutoDetectedChips(listOf(ocrPuzzle))

        // 2. EnemyIconV1 sees generic puzzle + traffic_light + monochrome hint
        val iconPuzzleGeneric = WaveShieldChip("puzzle_shield", "拼图盾", ShieldCategory.SHAPE_AND_PUZZLE, autoDetected = true, paramInt = 0)
        val iconTraffic = WaveShieldChip("traffic_light", "起结有序", ShieldCategory.HAZARD_AND_PATH, autoDetected = true)
        val added = ActiveStrategyStore.mergeCollaborativeIconDetections(
            iconChips = listOf(iconPuzzleGeneric, iconTraffic),
            iconHints = BoardVisualHints(hasMonochrome = true)
        )
        assertEquals("Only missing traffic_light should be newly added", 1, added)
        val byId = ActiveStrategyStore.activeChips.value.associateBy { it.id }
        assertEquals("Existing detailed OCR puzzle paramInt must NEVER be downgraded by generic icon", ocrPuzzle.paramInt, byId.getValue("puzzle_shield").paramInt)
        assertTrue(byId.containsKey("traffic_light"))
        assertTrue(ActiveStrategyStore.activeVisualHints.value.hasMonochrome)

        // 3. Dismissing traffic_light & monochrome hint prevents re-injection within the same wave
        ActiveStrategyStore.removeChip("traffic_light")
        ActiveStrategyStore.dismissVisualHint("monochrome")
        assertFalse(ActiveStrategyStore.activeVisualHints.value.hasMonochrome)
        val reAdded = ActiveStrategyStore.mergeCollaborativeIconDetections(
            iconChips = listOf(iconTraffic),
            iconHints = BoardVisualHints(hasMonochrome = true)
        )
        assertEquals(0, reAdded)
        assertFalse(ActiveStrategyStore.activeChips.value.any { it.id == "traffic_light" })
        assertFalse(ActiveStrategyStore.activeVisualHints.value.hasMonochrome)
    }

    @Test
    fun `removeElementFromChip supports 2-way per-element matrix synchronization`() {
        val maskWaterFire = Element.WATER.bitMask or Element.FIRE.bitMask
        ActiveStrategyStore.upsertChip(
            WaveShieldChip("forbid_all", "禁消:水火", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = true, paramElementMask = maskWaterFire)
        )

        // Removing Water keeps Fire active
        ActiveStrategyStore.removeElementFromChip("forbid_all", Element.WATER.code)
        val remaining = ActiveStrategyStore.activeChips.value.single()
        assertEquals(Element.FIRE.bitMask, remaining.paramElementMask and 0x3F)

        // Removing Fire clears the last element and removes the chip completely (+ marks dismissed)
        ActiveStrategyStore.removeElementFromChip("forbid_all", Element.FIRE.code)
        assertTrue(ActiveStrategyStore.activeChips.value.isEmpty())
    }

    @Test
    fun `mergeBoardDerivedSignals avoids electrified self-feedback loop and gates unconfirmed puzzle fallback`() {
        val cells = List(30) { idx ->
            CellState(
                element = Element.MATCHABLE[idx % 6],
                hazards = if (idx < 24) HazardBits.BLACK_WHITE else HazardBits.ELECTRIFIED
            )
        }
        val flags = MutableList(30) { TileFlagBits.NONE }.apply {
            this[0] = TileFlagBits.PUZZLE_1
            this[1] = TileFlagBits.PUZZLE_1
            this[2] = TileFlagBits.PUZZLE_1
            this[6] = TileFlagBits.START_ALLOWED
            this[29] = TileFlagBits.END_REQUIRED
        }
        val board = BoardState(
            cols = 6,
            rows = 5,
            cells = cells,
            tileFlags = flags,
            startingBuoyCol = 2,
            startingBuoyElement = Element.WOOD
        )

        // Single unconfirmed frame: must NOT emit puzzle_shield and must NEVER pollute activeVisualHints.hasElectrified!
        val addedUnconfirmed = ActiveStrategyStore.mergeBoardDerivedSignals(board, allowPuzzleFallback = false)
        assertEquals(2, addedUnconfirmed)
        assertTrue(ActiveStrategyStore.activeVisualHints.value.hasMonochrome)
        assertFalse("Board electrified noise must NOT feed back into activeVisualHints!", ActiveStrategyStore.activeVisualHints.value.hasElectrified)
        assertFalse(ActiveStrategyStore.activeChips.value.any { it.id == "puzzle_shield" })

        // 2-tick static confirmed frame: emits loose puzzle_shield(paramInt=0), which upgrades cleanly when OCR brings Water Strict Puzzle!
        val addedConfirmed = ActiveStrategyStore.mergeBoardDerivedSignals(board, allowPuzzleFallback = true)
        assertEquals(1, addedConfirmed)
        val p0 = ActiveStrategyStore.activeChips.value.first { it.id == "puzzle_shield" }
        assertEquals(0, p0.paramInt)

        val waterStrict = WaveShieldChip(
            "puzzle_shield",
            "严格拼图盾·水",
            ShieldCategory.SHAPE_AND_PUZZLE,
            autoDetected = true,
            paramInt = 1 or ((Element.WATER.code + 1) shl 2)
        )
        assertEquals(1, ActiveStrategyStore.applyAutoDetectedChips(listOf(waterStrict)))
        assertEquals(waterStrict.paramInt, ActiveStrategyStore.activeChips.value.first { it.id == "puzzle_shield" }.paramInt)

        // User dismisses monochrome hint & puzzle_shield -> must NOT re-trigger on next board tick in same wave!
        ActiveStrategyStore.dismissVisualHint("monochrome")
        ActiveStrategyStore.removeChip("puzzle_shield")
        val reAdded = ActiveStrategyStore.mergeBoardDerivedSignals(board, allowPuzzleFallback = true)
        assertEquals(0, reAdded)
        assertFalse(ActiveStrategyStore.activeVisualHints.value.hasMonochrome)
        assertFalse(ActiveStrategyStore.activeChips.value.any { it.id == "puzzle_shield" })
    }

    @Test
    fun `applyAutoDetectedChips merges multi-enemy element masks within the same wave`() {
        val fireClear = WaveShieldChip("clear_all_element", "首消全火", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = true, paramElementMask = Element.FIRE.bitMask)
        val woodClear = WaveShieldChip("clear_all_element", "首消全木", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = true, paramElementMask = Element.WOOD.bitMask)

        assertEquals(1, ActiveStrategyStore.applyAutoDetectedChips(listOf(fireClear)))
        assertEquals(1, ActiveStrategyStore.applyAutoDetectedChips(listOf(woodClear)))

        val merged = ActiveStrategyStore.activeChips.value.single { it.id == "clear_all_element" }
        assertEquals(Element.FIRE.bitMask or Element.WOOD.bitMask, merged.paramElementMask)
    }
}
