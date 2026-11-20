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

        fun create(context: android.content.Context): TeamPresetRepository =
            TeamPresetRepository(AutoDoraDatabase.getInstance(context).teamPresetDao())

        fun defaultBuiltInPresets(): List<TeamBasePreset> = listOf(
            TeamBasePreset(
                id = ID_GENERAL,
                name = "通用高连击策略",
                description = "标准 3 消 · 8 向斜转 · 强化珠优先 · 兼顾高 Combo 与稳健手数",
                baseConfig = RuleConfig(
                    allowDiagonalMove = true,
                    preferEnchanted = true,
                    fourPlusPolicy = FourPlusPolicy.MAX_COMBO
                )
            ),
            TeamBasePreset(
                id = "builtin_two_match",
                name = "火木心二消策略",
                description = "火 / 木 / 心 2 颗即消 · 拆珠冲高 Combo · 适配二消与高倍率队长",
                baseConfig = RuleConfig(
                    allowDiagonalMove = true,
                    preferEnchanted = true,
                    elementRules = List(6) { idx ->
                        if (idx == Element.FIRE.code || idx == Element.WOOD.code || idx == Element.HEART.code) {
                            ElementRule(minMatchCount = 2, priority = ElementPriority.HIGH)
                        } else {
                            ElementRule(minMatchCount = 3)
                        }
                    }
                )
            ),
            TeamBasePreset(
                id = "builtin_column_ameliorate",
                name = "直排昇华爆发策略",
                description = "1~6 直行 ≥ 4 消昇华加权 · 优先触发直排剑 / 心增益",
                baseConfig = RuleConfig(
                    allowDiagonalMove = true,
                    preferEnchanted = true,
                    columnRules = List(8) { col ->
                        if (col < 6) ColumnAmeliorationRule(enabled = true, minOrbs = 4, hardRequired = false)
                        else ColumnAmeliorationRule()
                    }
                )
            ),
            TeamBasePreset(
                id = "builtin_char_orb",
                name = "角色符石起手策略",
                description = "1 号位角色珠下拉起手（不计首步）· 适配角色通行 / 停放地形",
                baseConfig = RuleConfig(
                    allowDiagonalMove = true,
                    useCharacterOrb = true,
                    characterOrbCol = 0,
                    characterOrbElement = Element.FIRE,
                    preferEnchanted = true
                )
            ),
            TeamBasePreset(
                id = "builtin_weijian_burst",
                name = "违建长步爆发策略",
                description = "6×8 扩版特化 · 允许更长步数（100 步）与高算力广度搜索",
                baseConfig = RuleConfig(
                    allowDiagonalMove = true,
                    useSystemSteps = false,
                    useSystemBeam = false,
                    maxSteps = 100,
                    beamWidth = 260,
                    maxTimeMs = 1100,
                    preferEnchanted = true
                )
            )
        )
    }
}
