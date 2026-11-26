package com.autodora.next.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.autodora.next.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TrainingSampleDomains {
    const val BOARD_SOLVE = "BOARD_SOLVE"
    const val BOARD_EDITOR_CORRECTION = "BOARD_EDITOR_CORRECTION"
    const val BOARD_AUTO_IDLE = "BOARD_AUTO_IDLE"
    const val ENEMY_POPUP_LONG_PRESS = "ENEMY_POPUP_LONG_PRESS"
    const val STAGE_INFO_MODAL = "STAGE_INFO_MODAL"
}

data class TrainingStorageStats(
    val totalSamples: Int = 0,
    val boardSamples: Int = 0,
    val editorCorrectionSamples: Int = 0,
    val enemyModalSamples: Int = 0,
    val capturedEnemySkillsCount: Int = 0,
    val totalBytes: Long = 0L
)

data class TrainingExportResult(
    val zipFile: File,
    val publicDownloadsPath: String?,
    val sampleCount: Int,
    val zipBytes: Long
)

data class CapturedEnemySkillInput(
    val skillHash: String,
    val titleZhTw: String,
    val rawBodyZhTw: String,
    val sourceType: String,
    val stageName: String = "",
    val waveIndex: Int = 0,
    val parseStatus: String,
    val mappedChips: List<WaveShieldChip>
)

@Serializable
data class TrainingSampleSidecar(
    val schemaVersion: Int = 1,
    val sampleId: String,
    val domain: String,
    val priority: Int,
    val perceptualHashHex: String,
    val captureMode: String,
    val calibrationSource: String,
    val createdAtMs: Long,
    val avgConfidence: Float = 0f,
    val lowConfidenceIndices: List<Int> = emptyList(),
    val activeWaveChips: List<WaveShieldChip> = emptyList(),
    val geometry: BoardGeometry? = null,
    val aiBoard: BoardState? = null,
    val userCorrectedBoard: BoardState? = null,
    val correctedCellIndices: List<Int> = emptyList(),
    val tags: List<String> = emptyList(),
    val modalKind: String? = null,
    val files: TrainingSampleFiles
)

@Serializable
data class TrainingSampleFiles(
    val primaryImage: String,
    val secondaryImage: String? = null,
    val fullViewportImage: String? = null
)

@Serializable
data class TrainingExportManifestItem(
    val id: String,
    val domain: String,
    val priority: Int,
    val perceptualHashHex: String,
    val primaryImage: String,
    val secondaryImage: String?,
    val fullViewport: String?,
    val metadataJson: String,
    val tags: List<String>,
    val totalBytes: Long,
    val createdAtMs: Long
)

@Serializable
data class TrainingExportManifest(
    val schemaVersion: Int = 1,
    val exportedAtMs: Long,
    val appVersionName: String,
    val deviceLabel: String,
    val unlimitedRetention: Boolean = true,
    val totalSamples: Int,
    val capturedEnemySkillsCount: Int,
    val items: List<TrainingExportManifestItem>
)

@Serializable
data class ExportedEnemySkillMasterItem(
    val skillHash: String,
    val titleZhTw: String,
    val rawBodyZhTw: String,
    val sourceType: String,
    val stageName: String,
    val waveIndex: Int,
    val parseStatus: String,
    val mappedChipsJson: String,
    val hitCount: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long
)

/**
 * Pure Kotlin perceptual hashing and board-diff utilities (JVM unit-testable).
 */
object TrainingSampleHasher {

    /**
     * Computes a 64-bit horizontal difference hash (dHash) from a 9x8 row-major luminance grid (size 72).
     */
    fun computeDHashFromLumaGrid(luma9x8: IntArray): Long {
        if (luma9x8.size < 72) return 0L
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            val rowOff = y * 9
            for (x in 0 until 8) {
                if (luma9x8[rowOff + x] > luma9x8[rowOff + x + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun computeBoardDiffIndices(before: BoardState?, after: BoardState): List<Int> {
        if (before == null) return after.cells.indices.toList()
        if (before.cols != after.cols || before.rows != after.rows) {
            return after.cells.indices.toList()
        }
        val changed = ArrayList<Int>()
        val n = after.cells.size
        for (i in 0 until n) {
            val c1 = before.cells[i]
            val c2 = after.cells[i]
            val f1 = before.tileFlags.getOrElse(i) { TileFlagBits.NONE }
            val f2 = after.tileFlags.getOrElse(i) { TileFlagBits.NONE }
            if (c1.element != c2.element ||
                c1.variant != c2.variant ||
                c1.race != c2.race ||
                c1.hazards != c2.hazards ||
                f1 != f2
            ) {
                changed.add(i)
            }
        }
        if (before.startingBuoyCol != after.startingBuoyCol || before.startingBuoyElement != after.startingBuoyElement) {
            val col = after.startingBuoyCol
            if (col in 0 until after.cols && col !in changed) {
                changed.add(col)
            }
        }
        return changed
    }

    fun extractDetectedTags(
        board: BoardState,
        isEditorCorrected: Boolean,
        lowConfCount: Int
    ): List<String> {
        val tags = LinkedHashSet<String>()
        if (isEditorCorrected) tags.add("P0_EDITOR_CORRECTION")
        if (lowConfCount > 0) tags.add("P1_LOW_CONF")
        if (board.startingBuoyCol >= 0) tags.add("STARTING_BUOY")
        if (board.rows > 5) tags.add("WEIJIAN_${board.cols}x${board.rows}")

        var combinedHaz = 0
        var combinedFlags = 0
        var hasRace = false
        var hasEnch = false
        var hasWeak = false
        for (i in board.cells.indices) {
            val cell = board.cells[i]
            combinedHaz = combinedHaz or cell.hazards
            combinedFlags = combinedFlags or board.tileFlags.getOrElse(i) { TileFlagBits.NONE }
            if (cell.race != RaceType.NONE) hasRace = true
            if (cell.variant == Variant.ENCHANTED) hasEnch = true
            if (cell.variant == Variant.WEAKENED) hasWeak = true
        }
        if (hasRace) tags.add("HAS_RACE")
        if (hasEnch) tags.add("HAS_ENCHANTED")
        if (hasWeak) tags.add("HAS_WEAKENED")

        val hazardTagMap = listOf(
            HazardBits.WEATHERED to "HAZ_WEATHERED",
            HazardBits.ELECTRIFIED to "HAZ_ELECTRIFIED",
            HazardBits.FROZEN to "HAZ_FROZEN",
            HazardBits.ICE_SEALED to "HAZ_ICE_SEALED",
            HazardBits.PETRIFIED to "HAZ_PETRIFIED",
            HazardBits.BLOOD_DRAIN to "HAZ_BLOOD_DRAIN",
            HazardBits.LOCKED to "HAZ_LOCKED",
            HazardBits.BOMB to "HAZ_BOMB",
            HazardBits.BLACK_WHITE to "HAZ_BLACK_WHITE",
            HazardBits.QUESTION to "HAZ_QUESTION",
            HazardBits.FOAM to "HAZ_FOAM",
            HazardBits.PURGATORY_FIRE to "HAZ_PURGATORY_FIRE",
            HazardBits.LUMINOUS to "HAZ_LUMINOUS",
            HazardBits.COAGULATED to "HAZ_COAGULATED",
            HazardBits.EVIL_ENERGY to "HAZ_EVIL_ENERGY",
            HazardBits.MAGIC_ORB to "HAZ_MAGIC_ORB",
            HazardBits.BLOOD_THORN to "HAZ_BLOOD_THORN",
            HazardBits.THORN to "HAZ_THORN",
            HazardBits.PRIORITY to "HAZ_MARK_PRIORITY",
            HazardBits.RESERVE to "HAZ_MARK_RESERVE"
        )
        for ((bit, label) in hazardTagMap) {
            if ((combinedHaz and bit) != 0) tags.add(label)
        }

        val flagTagMap = listOf(
            TileFlagBits.PUZZLE_1 to "OV_PUZZLE",
            TileFlagBits.PUZZLE_2 to "OV_PUZZLE_2",
            TileFlagBits.START_ALLOWED to "OV_START_VORTEX",
            TileFlagBits.END_REQUIRED to "OV_END_VORTEX",
            TileFlagBits.STORM to "OV_STORM",
            TileFlagBits.SHATTERED to "OV_SHATTERED",
            TileFlagBits.BURNING_TILE to "OV_BURNING",
            TileFlagBits.SCORCHING_TILE to "OV_SCORCHING",
            TileFlagBits.BLACK_HOLE to "OV_BLACK_HOLE",
            TileFlagBits.END_FORBIDDEN to "OV_BLOODBATH",
            TileFlagBits.BARRIER_FIRST_WAVE_ONLY to "OV_BARRIER",
            TileFlagBits.CHAR_ORB_PASS to "OV_CHAR_PASS",
            TileFlagBits.CHAR_ORB_END to "OV_CHAR_END"
        )
        for ((bit, label) in flagTagMap) {
            if ((combinedFlags and bit) != 0) tags.add(label)
        }
        return tags.toList()
    }
}

/**
 * Pure Kotlin ZIP archive builder for exporting ALL locally collected board & enemy-skill samples without cap.
 */
object TrainingZipPackager {

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun packDirectoryToZip(
        inboxRoot: File,
        samples: List<TrainingSampleEntity>,
        enemySkills: List<CapturedEnemySkillEntity>,
        destZip: File,
        appVersionName: String,
        deviceLabel: String
    ): Long {
        destZip.parentFile?.mkdirs()
        val manifestItems = samples.map { e ->
            TrainingExportManifestItem(
                id = e.id,
                domain = e.domain,
                priority = e.priority,
                perceptualHashHex = e.perceptualHash.toULong().toString(16).padStart(16, '0'),
                primaryImage = e.primaryImageRelPath,
                secondaryImage = e.secondaryImageRelPath,
                fullViewport = e.fullViewportRelPath,
                metadataJson = e.metadataRelPath,
                tags = if (e.tagsCsv.isBlank()) emptyList() else e.tagsCsv.split(',').filter { it.isNotBlank() },
                totalBytes = e.totalBytes,
                createdAtMs = e.createdAtMs
            )
        }
        val manifest = TrainingExportManifest(
            exportedAtMs = System.currentTimeMillis(),
            appVersionName = appVersionName,
            deviceLabel = deviceLabel,
            unlimitedRetention = true,
            totalSamples = samples.size,
            capturedEnemySkillsCount = enemySkills.size,
            items = manifestItems
        )
        val skillMaster = enemySkills.map { s ->
            ExportedEnemySkillMasterItem(
                skillHash = s.skillHash,
                titleZhTw = s.titleZhTw,
                rawBodyZhTw = s.rawBodyZhTw,
                sourceType = s.sourceType,
                stageName = s.stageName,
                waveIndex = s.waveIndex,
                parseStatus = s.parseStatus,
                mappedChipsJson = s.mappedChipsJson,
                hitCount = s.hitCount,
                firstSeenMs = s.firstSeenMs,
                lastSeenMs = s.lastSeenMs
            )
        }

        val addedEntries = HashSet<String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destZip))).use { zos ->
            fun writeStringEntry(entryName: String, content: String) {
                if (!addedEntries.add(entryName)) return
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            fun writeFileEntry(relPath: String?) {
                if (relPath.isNullOrBlank() || !addedEntries.add(relPath)) return
                val f = File(inboxRoot, relPath)
                if (!f.isFile) return
                zos.putNextEntry(ZipEntry(relPath))
                f.inputStream().use { inp -> inp.copyTo(zos) }
                zos.closeEntry()
            }

            writeStringEntry("manifest.json", prettyJson.encodeToString(manifest))
            writeStringEntry("enemy_skills_master.json", prettyJson.encodeToString(skillMaster))

            for (sample in samples) {
                writeFileEntry(sample.primaryImageRelPath)
                writeFileEntry(sample.secondaryImageRelPath)
                writeFileEntry(sample.fullViewportRelPath)
                writeFileEntry(sample.metadataRelPath)
            }
        }
        return destZip.length()
    }
}

/**
 * Unlimited dual-track on-device collector storing both:
 *  1) Board ROI WebP pairs (`frame_a`, optional `frame_b`, upper viewport) + P0 Board Editor corrections;
 *  2) In-battle long-press enemy skill popups & Stage Info modals (`full.webp`).
 *
 * Retention policy: ZERO size cap and ZERO count cap (never auto-deletes user samples).
 */
class TrainingDataRepository(
    private val context: Context,
    private val db: AutoDoraDatabase
) {
    private val sampleDao = db.trainingSampleDao()
    private val enemySkillDao = db.capturedEnemySkillDao()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val inboxRoot: File get() = File(context.filesDir, "training_inbox")
    private val dedupLock = Any()
    private var lastBoardHash: Long = 0L
    private var lastBoardRecordMs: Long = 0L
    private var lastModalHash: Long = 0L
    private var lastModalRecordMs: Long = 0L

    fun observeStats(): Flow<TrainingStorageStats> = combine(
        sampleDao.observeTotalCount(),
        sampleDao.observeBoardCount(),
        sampleDao.observeEditorCorrectionCount(),
        sampleDao.observeEnemyModalCount(),
        combine(enemySkillDao.observeCount(), sampleDao.observeTotalBytes()) { enemyCnt, bytes ->
            enemyCnt to bytes
        }
    ) { total, boards, editorCnt, enemyModals, (enemyCnt, bytes) ->
        TrainingStorageStats(
            totalSamples = total,
            boardSamples = boards,
            editorCorrectionSamples = editorCnt,
            enemyModalSamples = enemyModals,
            capturedEnemySkillsCount = enemyCnt,
            totalBytes = bytes
        )
    }

    fun recordBoardSample(
        rawFrameA: Bitmap,
        rawFrameB: Bitmap?,
        geometry: BoardGeometry,
        aiBoard: BoardState,
        avgConfidence: Float,
        lowConfidenceIndices: List<Int>,
        activeWaveChips: List<WaveShieldChip>,
        captureMode: String,
        domain: String,
        userCorrectedBoard: BoardState? = null,
        calibrationSource: String = "auto"
    ) {
        if (rawFrameA.isRecycled || rawFrameA.width <= 16 || rawFrameA.height <= 16) return

        val hash = computeRegionDHash(
            bmp = rawFrameA,
            left = geometry.leftPx.toInt(),
            top = geometry.topPx.toInt(),
            right = geometry.rightPx.toInt(),
            bottom = geometry.bottomPx.toInt()
        )
        val now = System.currentTimeMillis()
        val isEditorCorrection = domain == TrainingSampleDomains.BOARD_EDITOR_CORRECTION
        if (!isEditorCorrection) {
            synchronized(dedupLock) {
                if (lastBoardRecordMs > 0L &&
                    (now - lastBoardRecordMs) < 20_000L &&
                    TrainingSampleHasher.hammingDistance(hash, lastBoardHash) <= 4
                ) {
                    return
                }
                lastBoardHash = hash
                lastBoardRecordMs = now
            }
        }

        val boardBitmapA = cropSafeBitmap(
            rawFrameA,
            geometry.leftPx.toInt(),
            geometry.topPx.toInt(),
            geometry.rightPx.toInt(),
            geometry.bottomPx.toInt()
        ) ?: return

        val boardBitmapB = rawFrameB?.takeIf { !it.isRecycled && it.width > 16 && it.height > 16 }?.let { bmpB ->
            cropSafeBitmap(
                bmpB,
                geometry.leftPx.toInt(),
                geometry.topPx.toInt(),
                geometry.rightPx.toInt(),
                geometry.bottomPx.toInt()
            )
        }

        val effectiveBoard = userCorrectedBoard ?: aiBoard
        val tags = TrainingSampleHasher.extractDetectedTags(
            board = effectiveBoard,
            isEditorCorrected = isEditorCorrection,
            lowConfCount = lowConfidenceIndices.size
        )
        val priority = when {
            isEditorCorrection -> 0
            lowConfidenceIndices.isNotEmpty() || tags.any { it.startsWith("HAZ_") || it.startsWith("OV_") || it == "STARTING_BUOY" } -> 1
            else -> 2
        }

        // Capture upper battle screen (enemies + portraits + HP bar) for high-value samples
        val viewportBitmap = if (priority <= 1 && geometry.topPx >= 64f) {
            cropSafeBitmap(
                rawFrameA,
                geometry.leftPx.toInt(),
                0,
                geometry.rightPx.toInt(),
                geometry.topPx.toInt()
            )
        } else null

        val correctedIndices = if (isEditorCorrection) {
            TrainingSampleHasher.computeBoardDiffIndices(aiBoard, effectiveBoard)
        } else {
            emptyList()
        }

        ioScope.launch {
            try {
                val id = makeSampleId(now, if (isEditorCorrection) "ed" else "bd")
                val boardsDir = File(inboxRoot, "boards").apply { mkdirs() }
                val vpDir = File(inboxRoot, "viewports").apply { mkdirs() }
                val metaDir = File(inboxRoot, "meta").apply { mkdirs() }

                val relA = "boards/${id}_a.webp"
                writeWebp(boardBitmapA, File(inboxRoot, relA), quality = 92)

                var relB: String? = null
                if (boardBitmapB != null) {
                    relB = "boards/${id}_b.webp"
                    writeWebp(boardBitmapB, File(inboxRoot, relB), quality = 92)
                }

                var relVp: String? = null
                if (viewportBitmap != null) {
                    relVp = "viewports/${id}_upper.webp"
                    writeWebp(viewportBitmap, File(inboxRoot, relVp), quality = 88)
                }

                val relMeta = "meta/${id}.json"
                val sidecar = TrainingSampleSidecar(
                    sampleId = id,
                    domain = domain,
                    priority = priority,
                    perceptualHashHex = hash.toULong().toString(16).padStart(16, '0'),
                    captureMode = captureMode,
                    calibrationSource = calibrationSource,
                    createdAtMs = now,
                    avgConfidence = avgConfidence,
                    lowConfidenceIndices = lowConfidenceIndices,
                    activeWaveChips = activeWaveChips,
                    geometry = geometry,
                    aiBoard = aiBoard,
                    userCorrectedBoard = userCorrectedBoard,
                    correctedCellIndices = correctedIndices,
                    tags = tags,
                    files = TrainingSampleFiles(
                        primaryImage = relA,
                        secondaryImage = relB,
                        fullViewportImage = relVp
                    )
                )
                val metaFile = File(inboxRoot, relMeta)
                metaFile.writeText(json.encodeToString(sidecar), Charsets.UTF_8)

                val totalBytes = File(inboxRoot, relA).length() +
                    (relB?.let { File(inboxRoot, it).length() } ?: 0L) +
                    (relVp?.let { File(inboxRoot, it).length() } ?: 0L) +
                    metaFile.length()

                sampleDao.insert(
                    TrainingSampleEntity(
                        id = id,
                        domain = domain,
                        priority = priority,
                        perceptualHash = hash,
                        primaryImageRelPath = relA,
                        secondaryImageRelPath = relB,
                        fullViewportRelPath = relVp,
                        metadataRelPath = relMeta,
                        tagsCsv = tags.joinToString(","),
                        totalBytes = totalBytes,
                        createdAtMs = now
                    )
                )
            } catch (_: Throwable) {
            } finally {
                if (!boardBitmapA.isRecycled) boardBitmapA.recycle()
                if (boardBitmapB != null && !boardBitmapB.isRecycled) boardBitmapB.recycle()
                if (viewportBitmap != null && !viewportBitmap.isRecycled) viewportBitmap.recycle()
            }
        }
    }

    fun recordEnemyModalSample(
        rawFrame: Bitmap,
        domain: String,
        activeWaveChips: List<WaveShieldChip>,
        captureMode: String
    ) {
        if (rawFrame.isRecycled || rawFrame.width <= 64 || rawFrame.height <= 64) return
        val w = rawFrame.width
        val h = rawFrame.height
        val hash = computeRegionDHash(
            bmp = rawFrame,
            left = (w * 0.08f).toInt(),
            top = (h * 0.40f).toInt(),
            right = (w * 0.92f).toInt(),
            bottom = (h * 0.85f).toInt()
        )
        val now = System.currentTimeMillis()
        synchronized(dedupLock) {
            if (lastModalRecordMs > 0L &&
                (now - lastModalRecordMs) < 12_000L &&
                TrainingSampleHasher.hammingDistance(hash, lastModalHash) <= 4
            ) {
                return
            }
            lastModalHash = hash
            lastModalRecordMs = now
        }

        val fullCopy = rawFrame.copy(Bitmap.Config.ARGB_8888, false) ?: return
        ioScope.launch {
            try {
                val id = makeSampleId(now, "em")
                File(inboxRoot, "enemy_popups").mkdirs()
                File(inboxRoot, "meta").mkdirs()

                val relImg = "enemy_popups/${id}_full.webp"
                writeWebp(fullCopy, File(inboxRoot, relImg), quality = 92)

                val tags = listOf("ENEMY_MODAL", domain)
                val relMeta = "meta/${id}.json"
                val sidecar = TrainingSampleSidecar(
                    sampleId = id,
                    domain = domain,
                    priority = 1,
                    perceptualHashHex = hash.toULong().toString(16).padStart(16, '0'),
                    captureMode = captureMode,
                    calibrationSource = "auto",
                    createdAtMs = now,
                    activeWaveChips = activeWaveChips,
                    tags = tags,
                    modalKind = domain,
                    files = TrainingSampleFiles(primaryImage = relImg)
                )
                val metaFile = File(inboxRoot, relMeta)
                metaFile.writeText(json.encodeToString(sidecar), Charsets.UTF_8)

                val totalBytes = File(inboxRoot, relImg).length() + metaFile.length()
                sampleDao.insert(
                    TrainingSampleEntity(
                        id = id,
                        domain = domain,
                        priority = 1,
                        perceptualHash = hash,
                        primaryImageRelPath = relImg,
                        secondaryImageRelPath = null,
                        fullViewportRelPath = null,
                        metadataRelPath = relMeta,
                        tagsCsv = tags.joinToString(","),
                        totalBytes = totalBytes,
                        createdAtMs = now
                    )
                )
            } catch (_: Throwable) {
            } finally {
                if (!fullCopy.isRecycled) fullCopy.recycle()
            }
        }
    }

    /**
     * Persists ALL recognized enemy skill blocks (including greyed-out bypassed skills!) into
     * Room `captured_enemy_skills` keyed by [CapturedEnemySkillInput.skillHash] so the user's
     * authoritative enemy-skill knowledge base keeps growing with zero loss of bypassed entries.
     */
    fun recordCapturedEnemySkills(items: List<CapturedEnemySkillInput>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        ioScope.launch {
            for (item in items) {
                if (item.skillHash.isBlank() || item.rawBodyZhTw.isBlank()) continue
                runCatching {
                    val existing = enemySkillDao.getByHash(item.skillHash)
                    val chipsJson = json.encodeToString(item.mappedChips)
                    val entity = CapturedEnemySkillEntity(
                        skillHash = item.skillHash,
                        titleZhTw = item.titleZhTw.ifBlank { existing?.titleZhTw.orEmpty() },
                        rawBodyZhTw = item.rawBodyZhTw,
                        sourceType = if (existing == null) item.sourceType else existing.sourceType,
                        stageName = item.stageName.ifBlank { existing?.stageName.orEmpty() },
                        waveIndex = if (item.waveIndex > 0) item.waveIndex else (existing?.waveIndex ?: 0),
                        parseStatus = item.parseStatus,
                        mappedChipsJson = chipsJson,
                        hitCount = (existing?.hitCount ?: 0) + 1,
                        firstSeenMs = existing?.firstSeenMs ?: now,
                        lastSeenMs = now
                    )
                    enemySkillDao.upsert(entity)
                }
            }
        }
    }

    suspend fun exportDatasetZip(appVersionName: String): TrainingExportResult = withContext(Dispatchers.IO) {
        val samples = sampleDao.getAllOrdered()
        val enemySkills = enemySkillDao.getAllOrdered()
        val exportsDir = File(context.cacheDir, "training_exports").apply { mkdirs() }
        // Keep only the latest temporary export in cacheDir (actual training samples in inboxRoot are NEVER pruned!)
        exportsDir.listFiles()?.forEach { old -> if (old.isFile) old.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipName = "AutoDora_TrainingData_$stamp.zip"
        val destZip = File(exportsDir, zipName)
        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val zipBytes = TrainingZipPackager.packDirectoryToZip(
            inboxRoot = inboxRoot,
            samples = samples,
            enemySkills = enemySkills,
            destZip = destZip,
            appVersionName = appVersionName,
            deviceLabel = deviceLabel
        )
        val publicPath = copyZipToDownloads(destZip, zipName)
        TrainingExportResult(
            zipFile = destZip,
            publicDownloadsPath = publicPath,
            sampleCount = samples.size,
            zipBytes = zipBytes
        )
    }

    suspend fun clearAllCollectedSamples() = withContext(Dispatchers.IO) {
        sampleDao.deleteAll()
        inboxRoot.deleteRecursively()
        synchronized(dedupLock) {
            lastBoardHash = 0L
            lastBoardRecordMs = 0L
            lastModalHash = 0L
            lastModalRecordMs = 0L
        }
    }

    private fun copyZipToDownloads(srcZip: File, fileName: String): String? {
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AutoDora-NEXT")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                srcZip.inputStream().use { inp -> inp.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Download/AutoDora-NEXT/$fileName"
        }.getOrNull()
    }

    private fun computeRegionDHash(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Long {
        val x0 = left.coerceIn(0, bmp.width - 2)
        val y0 = top.coerceIn(0, bmp.height - 2)
        val x1 = right.coerceIn(x0 + 1, bmp.width - 1)
        val y1 = bottom.coerceIn(y0 + 1, bmp.height - 1)
        val rw = (x1 - x0).coerceAtLeast(9)
        val rh = (y1 - y0).coerceAtLeast(8)
        val luma = IntArray(9 * 8)
        for (gy in 0 until 8) {
            val py = (y0 + ((gy + 0.5f) / 8f * rh)).toInt().coerceIn(0, bmp.height - 1)
            for (gx in 0 until 9) {
                val px = (x0 + ((gx + 0.5f) / 9f * rw)).toInt().coerceIn(0, bmp.width - 1)
                val argb = bmp.getPixel(px, py)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                luma[gy * 9 + gx] = (r * 30 + g * 59 + b * 11) / 100
            }
        }
        return TrainingSampleHasher.computeDHashFromLumaGrid(luma)
    }

    private fun cropSafeBitmap(src: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Bitmap? {
        val x0 = left.coerceIn(0, src.width - 1)
        val y0 = top.coerceIn(0, src.height - 1)
        val w = (right.coerceAtMost(src.width) - x0).coerceAtLeast(0)
        val h = (bottom.coerceAtMost(src.height) - y0).coerceAtLeast(0)
        if (w < 16 || h < 16) return null
        return Bitmap.createBitmap(src, x0, y0, w, h)
    }

    private fun writeWebp(bmp: Bitmap, dest: File, quality: Int) {
        dest.parentFile?.mkdirs()
        val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        FileOutputStream(dest).use { out ->
            bmp.compress(fmt, quality.coerceIn(50, 100), out)
        }
    }

    private fun makeSampleId(tsMs: Long, prefix: String): String {
        val rand = UUID.randomUUID().toString().substring(0, 6)
        return "${prefix}_${tsMs}_$rand"
    }

    companion object {
        fun create(context: Context): TrainingDataRepository {
            val appCtx = context.applicationContext
            return TrainingDataRepository(appCtx, AutoDoraDatabase.getInstance(appCtx))
        }
    }
}
