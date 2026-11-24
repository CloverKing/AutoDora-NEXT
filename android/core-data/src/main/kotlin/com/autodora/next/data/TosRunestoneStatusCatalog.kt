package com.autodora.next.data

import com.autodora.next.model.HazardBits
import com.autodora.next.model.Variant

/**
 * Structured Tower of Saviors Runestone Status Catalog (22 items: official encyclopedia 1..20 + 2 classic non-encyclopedia runestones 21..22).
 * Generated from audited official in-game specifications (`docs/runestone_status_encyclopedia_spec.json`) and live gameplay verification.
 * Enables deterministic lookup from Traditional/Simplified status names or HazardBits to exact solver physics.
 */
enum class RunestoneTouchEffect {
    NONE,
    STOP_MOVEMENT,
    DEALS_DAMAGE,
    IMMOVABLE_WALL
}

data class RunestoneStatusSpec(
    val index: Int,
    val nameZhTw: String,
    val nameZhCn: String,
    val hazardBit: Int = HazardBits.NONE,
    val variant: Variant? = null,
    val movable: Boolean,
    val touchEffect: RunestoneTouchEffect,
    val directMatchable: Boolean,
    val adjacentClear: Boolean = false,
    val elimHarmful: Boolean = false,
    val mustClear: Boolean = false,
    val comboBonusPerOrb: Int = 0,
    val ruleTextZhTw: String
)

object TosRunestoneStatusCatalog {
    val entries: List<RunestoneStatusSpec> = listOf(
        RunestoneStatusSpec(
            index = 1, nameZhTw = "電擊", nameZhCn = "电击",
            hazardBit = HazardBits.ELECTRIFIED, movable = false,
            touchEffect = RunestoneTouchEffect.STOP_MOVEMENT, directMatchable = true, mustClear = true,
            ruleTextZhTw = "• 電擊符石一經觸碰 ⇒ 即時停止移動符石\n• 如有電擊符石未被消除 ⇒ 全隊攻擊力變 0"
        ),
        RunestoneStatusSpec(
            index = 2, nameZhTw = "風化", nameZhCn = "风化",
            hazardBit = HazardBits.WEATHERED, movable = false,
            touchEffect = RunestoneTouchEffect.STOP_MOVEMENT, directMatchable = true,
            ruleTextZhTw = "• 風化符石一經觸碰 ⇒ 即時停止移動符石"
        ),
        RunestoneStatusSpec(
            index = 3, nameZhTw = "凍結", nameZhCn = "冻结",
            hazardBit = HazardBits.FROZEN or HazardBits.ICE_SEALED, movable = false,
            touchEffect = RunestoneTouchEffect.IMMOVABLE_WALL, directMatchable = false,
            ruleTextZhTw = "• 回合結束時，將未凍結狀態的凍結符石 ⇒ 添加為冰封狀態\n• 冰封狀態下的符石 3 回合內 ⇒ 不能消除、轉化或引爆"
        ),
        RunestoneStatusSpec(
            index = 4, nameZhTw = "石化", nameZhCn = "石化",
            hazardBit = HazardBits.PETRIFIED, movable = false,
            touchEffect = RunestoneTouchEffect.IMMOVABLE_WALL, directMatchable = false, adjacentClear = true,
            ruleTextZhTw = "• 於石化符石四側消除符石 ⇒ 可解除「一重石化」（首批消除的符石每次只可解除「一重石化」）"
        ),
        RunestoneStatusSpec(
            index = 5, nameZhTw = "凝結", nameZhCn = "凝结",
            hazardBit = HazardBits.COAGULATED, movable = false,
            touchEffect = RunestoneTouchEffect.IMMOVABLE_WALL, directMatchable = false, adjacentClear = true,
            ruleTextZhTw = "• 於凝結符石四側消除符石 ⇒ 可解除「一重凝結」（首批消除的符石每次只可解除「一重凝結」）\n• 場上凝結符石愈多 ⇒ 敵人所受傷害減少愈多"
        ),
        RunestoneStatusSpec(
            index = 6, nameZhTw = "加護", nameZhCn = "加护",
            hazardBit = HazardBits.PRIORITY, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true,
            ruleTextZhTw = "• 適用於擁有「加護符石」敵技的敵人\n• 「加護符石」不能引爆"
        ),
        RunestoneStatusSpec(
            index = 7, nameZhTw = "弱化", nameZhCn = "弱化",
            variant = Variant.WEAKENED, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true, elimHarmful = true,
            ruleTextZhTw = "• 消除弱化符石 ⇒ 全隊攻擊力減少（發動龍刻脈動視作消除場上的弱化符石）"
        ),
        RunestoneStatusSpec(
            index = 8, nameZhTw = "化血", nameZhCn = "化血",
            hazardBit = HazardBits.BLOOD_DRAIN, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true, mustClear = true,
            ruleTextZhTw = "• 如有化血符石未被消除 ① 無法對敵人造成傷害 ② 敵人回復相等於該傷害的血量"
        ),
        RunestoneStatusSpec(
            index = 9, nameZhTw = "爆破炸彈", nameZhCn = "爆爆炸弹",
            hazardBit = HazardBits.BOMB, movable = true,
            touchEffect = RunestoneTouchEffect.DEALS_DAMAGE, directMatchable = true, mustClear = true,
            ruleTextZhTw = "• 爆破炸彈一經觸碰 ⇒ 對召喚師造成指定傷害\n※ 【護盾】可抵擋此傷害"
        ),
        RunestoneStatusSpec(
            index = 10, nameZhTw = "音樂", nameZhCn = "音乐",
            hazardBit = HazardBits.PRIORITY, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true,
            ruleTextZhTw = "• 適用於擁有「音樂符石」敵技的敵人\n• 「音樂符石」不能轉化"
        ),
        RunestoneStatusSpec(
            index = 11, nameZhTw = "流光", nameZhCn = "流光",
            hazardBit = HazardBits.LUMINOUS, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true,
            ruleTextZhTw = "• 隊伍裝備「時光牌」後，進入關卡後附上「時晷」的符石\n• 可被引爆或還原"
        ),
        RunestoneStatusSpec(
            index = 12, nameZhTw = "邪惡能量", nameZhCn = "邪恶能量",
            hazardBit = HazardBits.EVIL_ENERGY, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true, elimHarmful = true,
            ruleTextZhTw = "• 「邪惡能量符石」不能還原\n• 「邪惡能量符石」不能轉化為強化符石\n• 每消除 1 粒「邪惡能量符石」⇒ 全隊攻擊力減少"
        ),
        RunestoneStatusSpec(
            index = 13, nameZhTw = "泡沫", nameZhCn = "泡沫",
            hazardBit = HazardBits.FOAM, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = false, adjacentClear = true, mustClear = true,
            ruleTextZhTw = "• 移動符石並消除泡沫符石四側其中 1 個位置的符石可解除泡沫\n• 泡沫符石 ⇒ 不能消除、轉化或引爆\n• 如有泡沫符石未被消除 ⇒ 全隊攻擊力變 0"
        ),
        RunestoneStatusSpec(
            index = 14, nameZhTw = "淡化印記", nameZhCn = "淡化印记",
            hazardBit = HazardBits.PRIORITY, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true,
            ruleTextZhTw = "• 消除淡化印記符石 ⇒ 敵人減少陽之氣"
        ),
        RunestoneStatusSpec(
            index = 15, nameZhTw = "淨化印記", nameZhCn = "净化印记",
            hazardBit = HazardBits.PRIORITY, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true,
            ruleTextZhTw = "• 消除淨化印記符石 ⇒ 敵人減少陰之氣"
        ),
        RunestoneStatusSpec(
            index = 16, nameZhTw = "虛之印記", nameZhCn = "虚之印记",
            hazardBit = HazardBits.RESERVE, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true,
            ruleTextZhTw = "• 每消除 1 粒虛之印記符石 ⇒ 獲得印記力量\n• 疊至最高 10 重印記力量 ⇒ 回合結束時召喚師會死亡\n• 召喚師死亡後，清空虛之印記及印記力量"
        ),
        RunestoneStatusSpec(
            index = 17, nameZhTw = "魔力", nameZhCn = "魔力",
            hazardBit = HazardBits.MAGIC_ORB, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true, comboBonusPerOrb = 3,
            ruleTextZhTw = "• 「移動及消除符石」期間外 ⇒ 解除魔力符石位置的「連環光牢」及「暴風」\n• 每消除 1 粒魔力符石 ⇒ 增加 3 連擊 (Combo)\n• 消除屬性魔力符石 ⇒ 該屬性成員攻擊力 2 倍\n• 消除心魔力符石 ⇒ 完全回復生命力\n• 魔力符石於召喚師死亡後消失"
        ),
        RunestoneStatusSpec(
            index = 18, nameZhTw = "消炎印記", nameZhCn = "消炎印记",
            hazardBit = HazardBits.PRIORITY, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true,
            ruleTextZhTw = "• 每消除 1 粒消炎印記符石 ⇒ 敵人減少 1 層炎之氣\n• 召喚師死亡後 ⇒ 清空消炎印記"
        ),
        RunestoneStatusSpec(
            index = 19, nameZhTw = "除雷印記", nameZhCn = "除雷印记",
            hazardBit = HazardBits.PRIORITY, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true,
            ruleTextZhTw = "• 每消除 1 粒除雷印記符石 ⇒ 敵人減少 1 層雷之氣\n• 召喚師死亡後 ⇒ 清空除雷印記"
        ),
        RunestoneStatusSpec(
            index = 20, nameZhTw = "刺血符石", nameZhCn = "刺血符石",
            hazardBit = HazardBits.BLOOD_THORN, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true, elimHarmful = true,
            ruleTextZhTw = "• 每消除刺血符石 ⇒ 扣除召喚師生命力"
        ),
        // Extended Classic / Non-Encyclopedia Special Runestones verified via live gameplay & ToS Wiki
        RunestoneStatusSpec(
            index = 21, nameZhTw = "鎖定符石", nameZhCn = "锁定符石",
            hazardBit = HazardBits.LOCKED_HURT, movable = true,
            touchEffect = RunestoneTouchEffect.NONE, directMatchable = true, mustClear = true,
            ruleTextZhTw = "• 經典敵技（與 #8 化血共用灰石殼纏繞外觀，百科未單列）：鎖定全版或指定屬性符石\n• 如有鎖定符石未被消除 ⇒ 依敵技觸發敵人回復等額傷害血量 / 扣血 / 引爆等懲罰"
        ),
        RunestoneStatusSpec(
            index = 22, nameZhTw = "荊棘符石", nameZhCn = "荆棘符石",
            hazardBit = HazardBits.THORN, movable = true,
            touchEffect = RunestoneTouchEffect.DEALS_DAMAGE, directMatchable = true,
            ruleTextZhTw = "• 經典敵技（亦稱鋼線符石，百科未單列）：每回合隨機添加於指定數量符石\n• 荊棘符石不能轉化\n• 移動符石時每觸碰 1 次荊棘符石 ⇒ 根據敵人攻擊力扣除召喚師生命力（未觸碰可安全保留或由鄰珠配消）"
        )
    )

    private val byIndex: Map<Int, RunestoneStatusSpec> = entries.associateBy { it.index }

    fun findByIndex(index: Int): RunestoneStatusSpec? = byIndex[index]

    fun findByName(query: String): List<RunestoneStatusSpec> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return entries.filter { it.nameZhTw.contains(q, ignoreCase = true) || it.nameZhCn.contains(q, ignoreCase = true) }
    }
}
