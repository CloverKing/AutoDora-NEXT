package com.autodora.next.data

import com.autodora.next.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class TrainingDataRepositoryTest {

    @Test
    fun `dHash distance separates identical vs shifted boards and diff indices track editor edits`() {
        val gradA = IntArray(72) { idx -> (idx % 9) * 28 }
        val gradB = IntArray(72) { idx -> (idx % 9) * 27 + 2 }
        val invGrad = IntArray(72) { idx -> (8 - (idx % 9)) * 28 }

        val hA = TrainingSampleHasher.computeDHashFromLumaGrid(gradA)
        val hB = TrainingSampleHasher.computeDHashFromLumaGrid(gradB)
        val hInv = TrainingSampleHasher.computeDHashFromLumaGrid(invGrad)

        assertEquals(0, TrainingSampleHasher.hammingDistance(hA, hB))
        assertTrue(
            "Opposite horizontal gradient must have large Hamming distance",
            TrainingSampleHasher.hammingDistance(hA, hInv) >= 48
        )

        val aiBoard = BoardState(
            cols = 6,
            rows = 5,
            cells = List(30) { CellState(element = Element.WATER, variant = Variant.NORMAL) },
            tileFlags = List(30) { TileFlagBits.NONE }
        )
        val editedCells = aiBoard.cells.toMutableList()
        editedCells[4] = editedCells[4].copy(element = Element.FIRE, hazards = HazardBits.EVIL_ENERGY)
        editedCells[19] = editedCells[19].copy(race = RaceType.DRAGON)
        val editedFlags = aiBoard.tileFlags.toMutableList()
        editedFlags[25] = TileFlagBits.END_FORBIDDEN
        val userBoard = aiBoard.copy(cells = editedCells, tileFlags = editedFlags)

        val diffs = TrainingSampleHasher.computeBoardDiffIndices(aiBoard, userBoard)
        assertEquals(listOf(4, 19, 25), diffs)

        val tags = TrainingSampleHasher.extractDetectedTags(userBoard, isEditorCorrected = true, lowConfCount = 2)
        assertTrue(tags.contains("P0_EDITOR_CORRECTION"))
        assertTrue(tags.contains("P1_LOW_CONF"))
        assertTrue(tags.contains("HAS_RACE"))
        assertTrue(tags.contains("HAZ_EVIL_ENERGY"))
        assertTrue(tags.contains("OV_BLOODBATH"))
    }

    @Test
    fun `TrainingZipPackager packs all samples without count or byte cap`() {
        val tmpRoot = Files.createTempDirectory("autodora_train_zip_test").toFile()
        try {
            val inbox = File(tmpRoot, "inbox").apply { mkdirs() }
            File(inbox, "boards").mkdirs()
            File(inbox, "enemy_popups").mkdirs()
            File(inbox, "meta").mkdirs()

            val entities = ArrayList<TrainingSampleEntity>()
            val totalCount = 125
            for (i in 0 until totalCount) {
                val isPopup = i % 5 == 0
                val id = "sample_$i"
                val relImg = if (isPopup) "enemy_popups/${id}_full.webp" else "boards/${id}_a.webp"
                val relMeta = "meta/${id}.json"
                val imgFile = File(inbox, relImg).apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, i.toByte())) }
                val metaFile = File(inbox, relMeta).apply { writeText("{\"id\":\"$id\"}") }
                entities.add(
                    TrainingSampleEntity(
                        id = id,
                        domain = if (isPopup) TrainingSampleDomains.ENEMY_POPUP_LONG_PRESS else TrainingSampleDomains.BOARD_SOLVE,
                        priority = if (isPopup) 1 else 2,
                        perceptualHash = i.toLong(),
                        primaryImageRelPath = relImg,
                        secondaryImageRelPath = null,
                        fullViewportRelPath = null,
                        metadataRelPath = relMeta,
                        tagsCsv = if (isPopup) "ENEMY_MODAL" else "HAZ_BOMB",
                        totalBytes = imgFile.length() + metaFile.length(),
                        createdAtMs = 1_700_000_000_000L + i
                    )
                )
            }
            val skills = listOf(
                CapturedEnemySkillEntity(
                    skillHash = "abc123",
                    titleZhTw = "首消 18 粒盾",
                    rawBodyZhTw = "首批消除 18 粒符石才可對敵人造成攻擊傷害",
                    sourceType = "ENEMY_POPUP_LONG_PRESS",
                    stageName = "",
                    waveIndex = -1,
                    parseStatus = "RAW",
                    mappedChipsJson = "[]",
                    hitCount = 3,
                    firstSeenMs = 1_700_000_000_000L,
                    lastSeenMs = 1_700_000_005_000L
                )
            )

            val destZip = File(tmpRoot, "export.zip")
            val zipSize = TrainingZipPackager.packDirectoryToZip(
                inboxRoot = inbox,
                samples = entities,
                enemySkills = skills,
                destZip = destZip,
                appVersionName = "0.5.1",
                deviceLabel = "UnitTest"
            )
            assertTrue(zipSize > 0L)
            val magic = destZip.inputStream().use { inp ->
                val b = ByteArray(4)
                inp.read(b)
                b
            }
            assertArrayEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04), magic)

            ZipFile(destZip).use { zf ->
                assertNotNull(zf.getEntry("manifest.json"))
                assertNotNull(zf.getEntry("enemy_skills_master.json"))
                val manifestText = zf.getInputStream(zf.getEntry("manifest.json")).bufferedReader().readText()
                assertTrue(manifestText.contains("\"totalSamples\": $totalCount"))
                assertTrue(manifestText.contains("\"unlimitedRetention\": true"))
                for (i in 0 until totalCount) {
                    val isPopup = i % 5 == 0
                    val expectedImg = if (isPopup) "enemy_popups/sample_${i}_full.webp" else "boards/sample_${i}_a.webp"
                    assertNotNull("Missing $expectedImg in zip", zf.getEntry(expectedImg))
                    assertNotNull("Missing meta/sample_${i}.json in zip", zf.getEntry("meta/sample_${i}.json"))
                }
            }
        } finally {
            tmpRoot.deleteRecursively()
        }
    }
}
