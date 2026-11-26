package com.autodora.next.data

import android.content.Context
import com.autodora.next.model.BoardVisualHints
import com.autodora.next.model.StageWaveEntry
import com.autodora.next.model.StageWaveScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room persistence for parsed pre-battle Stage Info & imported Wiki multi-wave scripts.
 */
class StageWaveScriptRepository(db: AutoDoraDatabase) {

    private val dao = db.stageWaveScriptDao()
    private val json = Json { ignoreUnknownKeys = true }

    fun observeScripts(): Flow<List<StageWaveScript>> = dao.observeAll().map { entities ->
        entities.map { entity ->
            val waves = runCatching { json.decodeFromString<List<StageWaveEntry>>(entity.wavesJson) }
                .getOrElse { emptyList() }
            StageWaveScript(
                stageKey = entity.stageKey,
                stageName = entity.stageName,
                waves = waves,
                sourceType = entity.sourceType,
                updatedAtMs = entity.updatedAtMs
            )
        }
    }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun saveScript(script: StageWaveScript) = withContext(Dispatchers.IO) {
        if (script.stageKey.isBlank() || script.waves.isEmpty()) return@withContext
        dao.upsert(toEntity(script))
    }

    suspend fun saveScripts(scripts: List<StageWaveScript>): Int = withContext(Dispatchers.IO) {
        val valid = scripts.filter { it.stageKey.isNotBlank() && it.waves.isNotEmpty() }
        if (valid.isEmpty()) return@withContext 0
        dao.upsertAll(valid.map { toEntity(it) })
        valid.size
    }

    suspend fun deleteScript(stageKey: String) = withContext(Dispatchers.IO) {
        dao.deleteByKey(stageKey)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }

    private fun toEntity(s: StageWaveScript) = StageWaveScriptEntity(
        stageKey = s.stageKey,
        stageName = s.stageName,
        wavesJson = json.encodeToString(s.waves),
        sourceType = s.sourceType,
        updatedAtMs = if (s.updatedAtMs > 0L) s.updatedAtMs else System.currentTimeMillis()
    )

    companion object {
        fun create(context: Context): StageWaveScriptRepository {
            return StageWaveScriptRepository(AutoDoraDatabase.getInstance(context.applicationContext))
        }
    }
}

/**
 * Live active dungeon wave-script coordinator shared between Dashboard and OverlayControllerService.
 * Applying a wave replaces only `autoDetected` chips and `activeVisualHints`, never erasing the user's manual team base!
 */
object ActiveStageScriptStore {
    private val _activeScript = MutableStateFlow<StageWaveScript?>(null)
    val activeScript: StateFlow<StageWaveScript?> = _activeScript.asStateFlow()

    private val _currentWaveIndex = MutableStateFlow(1)
    val currentWaveIndex: StateFlow<Int> = _currentWaveIndex.asStateFlow()

    @Synchronized
    fun activateScript(script: StageWaveScript, startWaveIndex: Int = 1): StageWaveEntry? {
        if (script.waves.isEmpty()) return null
        val sortedWaves = script.waves.sortedBy { it.waveIndex }
        val normalized = script.copy(waves = sortedWaves)
        val entry = sortedWaves.firstOrNull { it.waveIndex == startWaveIndex } ?: sortedWaves.first()
        _activeScript.value = normalized
        _currentWaveIndex.value = entry.waveIndex
        applyWaveConstraints(entry)
        return entry
    }

    @Synchronized
    fun stepWave(delta: Int): StageWaveEntry? {
        val script = _activeScript.value ?: return null
        if (script.waves.isEmpty()) return null
        val curPos = script.waves.indexOfFirst { it.waveIndex == _currentWaveIndex.value }.takeIf { it >= 0 } ?: 0
        val nextPos = (curPos + delta).coerceIn(0, script.waves.lastIndex)
        val nextEntry = script.waves[nextPos]
        _currentWaveIndex.value = nextEntry.waveIndex
        applyWaveConstraints(nextEntry)
        return nextEntry
    }

    /**
     * Advances to the next wave in [activeScript] on battle wave progression.
     * If already at the final wave, finishes cleanly by clearing the active script and auto chips (returns null).
     */
    @Synchronized
    fun advanceWaveIfActive(): StageWaveEntry? {
        val script = _activeScript.value ?: return null
        if (script.waves.isEmpty()) return null
        val curPos = script.waves.indexOfFirst { it.waveIndex == _currentWaveIndex.value }.takeIf { it >= 0 } ?: 0
        if (curPos >= script.waves.lastIndex) {
            clearActiveScript()
            return null
        }
        val nextEntry = script.waves[curPos + 1]
        _currentWaveIndex.value = nextEntry.waveIndex
        applyWaveConstraints(nextEntry)
        return nextEntry
    }

    @Synchronized
    fun clearActiveScript() {
        _activeScript.value = null
        _currentWaveIndex.value = 1
        ActiveStrategyStore.clearAutoDetectedChips()
    }

    private fun applyWaveConstraints(entry: StageWaveEntry) {
        ActiveStrategyStore.clearAutoDetectedChips()
        ActiveStrategyStore.applyAutoDetectedChips(entry.chips)
        ActiveStrategyStore.setActiveVisualHints(entry.visualHints)
    }
}
