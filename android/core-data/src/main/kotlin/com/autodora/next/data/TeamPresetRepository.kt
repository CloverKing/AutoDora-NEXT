package com.autodora.next.data

import com.autodora.next.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class TeamPresetRecord(
    val preset: TeamBasePreset,
    val isBuiltIn: Boolean,
    val updatedAtMs: Long
)

class TeamPresetRepository(private val dao: TeamPresetDao) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        initScope.launch {
            seedBuiltInsIfNeeded()
        }
    }

    fun observePresets(): Flow<List<TeamPresetRecord>> {
        return dao.observeAll().map { entities ->
            entities.map { e ->
                val cfg = runCatching { json.decodeFromString<RuleConfig>(e.ruleConfigJson) }
                    .getOrElse { RuleConfig() }
                TeamPresetRecord(
                    preset = TeamBasePreset(
                        id = e.id,
                        name = e.name,
                        description = e.description,
                        baseConfig = cfg
                    ),
                    isBuiltIn = e.isBuiltIn,
                    updatedAtMs = e.updatedAtMs
                )
            }
        }
    }

    suspend fun getById(id: String): TeamBasePreset? {
        val e = dao.getById(id) ?: return null
        val cfg = runCatching { json.decodeFromString<RuleConfig>(e.ruleConfigJson) }
            .getOrElse { RuleConfig() }
        return TeamBasePreset(e.id, e.name, e.description, cfg)
    }

    suspend fun savePreset(preset: TeamBasePreset, isBuiltIn: Boolean = false) {
        dao.upsert(
            TeamPresetEntity(
                id = preset.id.ifBlank { "custom_${UUID.randomUUID().toString().take(8)}" },
                name = preset.name.trim().ifBlank { "未命名策略" },
                description = preset.description.trim(),
                ruleConfigJson = json.encodeToString(preset.baseConfig),
                isBuiltIn = isBuiltIn,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun duplicatePreset(source: TeamBasePreset): String {
        val newId = "custom_${UUID.randomUUID().toString().take(8)}"
        savePreset(
            source.copy(
                id = newId,
                name = "${source.name} 副本"
            ),
            isBuiltIn = false
        )
        return newId
    }

    suspend fun deleteCustomPreset(id: String) {
        dao.deleteCustom(id)
    }

    suspend fun seedBuiltInsIfNeeded() {
        // Prune retired legacy built-in presets so only the single Default Strategy remains pre-seeded
        dao.deleteByIds(RETIRED_BUILTIN_IDS)
        dao.renameIfExact(ID_GENERAL, "通用高连击策略", "默认策略")

        val now = System.currentTimeMillis()
        val builtIns = defaultBuiltInPresets().mapIndexed { index, preset ->
            TeamPresetEntity(
                id = preset.id,
                name = preset.name,
                description = preset.description,
                ruleConfigJson = json.encodeToString(preset.baseConfig),
                isBuiltIn = true,
                updatedAtMs = now - index * 1000L
            )
        }
        dao.insertAllIgnore(builtIns)
    }

    companion object {
        const val ID_GENERAL = "builtin_general"
        private val RETIRED_BUILTIN_IDS = listOf(
            "builtin_two_match",
            "builtin_column_ameliorate",
            "builtin_char_orb",
            "builtin_weijian_burst"
        )

        fun create(context: android.content.Context): TeamPresetRepository =
            TeamPresetRepository(AutoDoraDatabase.getInstance(context).teamPresetDao())

        fun defaultBuiltInPresets(): List<TeamBasePreset> = listOf(
            TeamBasePreset(
                id = ID_GENERAL,
                name = "默认策略",
                description = "标准 3 消 · 八向斜转 · 强化珠优先 · 兼顾高连击与稳健手数",
                baseConfig = RuleConfig(
                    allowDiagonalMove = true,
                    preferEnchanted = true,
                    fourPlusPolicy = FourPlusPolicy.MAX_COMBO
                )
            )
        )
    }
}
