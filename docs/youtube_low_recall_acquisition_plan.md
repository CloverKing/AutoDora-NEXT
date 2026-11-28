# AutoDora-NEXT v0.9.1 低识别率版面与敌技 YouTube 补充素材采集清单

本文档基于 **Phase 4.2 全量重训后** 的真实验证集指标（`board_master_meta.json` 与 `enemy_icon_meta.json`），系统梳理当前识别率偏低或因样本不足处于 `1.01` 安全熔断（回退至规则 CV / 弹窗 OCR）的 **版面状态（Stage 2A）** 与 **战中敌技小图标（Stage 2B）**，并给出可直接通过 `ml/tools/download_low_recall_topup.py` / `ml/tools/extract_tos_dataset.py` 自动检索下载的 YouTube 采集清单。

> **合规与零污染约束**
> 1. 所有从 YouTube 下载的视频与抽帧图像**严禁入库 Git**，统一存放于已被 `.gitignore` 忽略的 `backups/phase4_v2_inbox/topup_v090_low_recall/` 目录，且对既有 `backups/` 素材保持只读不覆盖。
> 2. 用户上传的实机测试视频严格保留为独立盲测基准（Zero-Leakage Held-out Benchmark），绝不混入训练集。

---

## 一、版面识别（`BoardMasterGridNetV2`）低召回 / 待补强清单

当前 `BoardMasterGridNetV2` 在 6 色基础符石（`97.17%`）及电击、冰封、炸弹、凝结、泡沫、流光、刺血、黑白符石（`vid3` 盲测 `30/30` 检出率、`96.7%` 颜色还原率）上已达标，但以下细分维度因真实静态帧稀缺，需重点从 YouTube 实况补充：

| 优先级 | 类别 / 状态位 | 当前模型表现 | 缺口根因分析 | 推荐繁体 YouTube 搜索关键词（1080p 竖屏/实况） | 目标净增有效静态盘面 |
| :--- | :--- | :--- | :--- | :--- | :---: |
| **P0** | **兽族 / 机械族符石**<br>(`Race: BEAST / MACHINA`) | `BEAST` 召回 `0%`<br>`MACHINA` 召回 `0%` | 现有兽族盘面仅 7 张；机械族旧样本盘面中央带巨大 `⊘` 禁消遮挡，无干净右上角机械徽章帧 | `神魔之塔 獸族符石 隊長 通關`<br>`神魔之塔 賈比爾 獸隊 實戰`<br>`神魔之塔 機械族符石 隊長 通關`<br>`神魔之塔 龐貝 機械隊 地獄級` | 各 `≥ 35` 盘<br>(`≥ 200` 颗带标珠) |
| **P0** | **神族 / 妖精 / 魔族符石**<br>(`Race: GOD / ELF / DEMON`) | `GOD` 缺净验证帧<br>`DEMON 33.3%` / `ELF 46.2%` | 旧检索视频多为纯色普通珠，队长/主动技未在盘面批量产出对应种族徽章珠 | `神魔之塔 神族符石 隊長 實戰`<br>`神魔之塔 妖精符石 隊長 通關`<br>`神魔之塔 魔族符石 隊長 地獄級` | 各 `≥ 30` 盘 |
| **P0** | **幽闭空间 / 黑白符石（多关卡）**<br>(`Hazard: BLACK_WHITE`) | 盲测 `30/30` 检出，`29/30` 还原（心/光微弱混淆） | 目前真机实况源仅 `yt_moon_wolf2` 单一关卡背景，缺强化黑白珠、弱化黑白珠及拼图叠加实况 | `神魔之塔 幽閉空間 地獄級`<br>`神魔之塔 黑白符石 災厄級` | `≥ 40` 盘 |
| **P0** | **炼狱之火 & 荆棘符石**<br>(`PURGATORY_FIRE` / `THORN`) | 验证正样本 `< 5` 颗（`th=1.01` 回退 CV） | 炼狱之火与电击亮核易混淆，荆棘藤蔓缺全 6 色底珠静态帧与 `(t, t+0.35s)` 双帧对 | `神魔之塔 煉獄之火 災厄級`<br>`神魔之塔 荊棘符石 地獄級` | 各 `≥ 30` 盘 + `10` 组双帧对 |
| **P1** | **魔力符石（含暗属）& 刺血符石（水/木/心）**<br>(`MAGIC_ORB` / `BLOOD_THORN`) | `MAGIC_ORB` `th=1.01`<br>`BLOOD_THORN` 缺水/木/心 | 魔力符石缺暗属与多队伍场景；刺血符石目前仅火/光/暗有样本 | `神魔之塔 魔力符石 實戰`<br>`神魔之塔 刺血符石 地獄級` | 各 `≥ 25` 盘 |
| **P1** | **暴风 / 碎裂 / 黑洞 / 灼热 / 血浴地形**<br>(`Tile Overlays`) | 清洗后验证集 `< 15` 格（当前由规则 CV 兜底） | YouTube 视频中常伴随转珠残影或 `Combo!!` 爆炸特效被质量门控过滤，需提取起手思考期静止帧 | `神魔之塔 暴風 地獄級 通關`<br>`神魔之塔 碎裂 黑洞 災厄級`<br>`神魔之塔 灼熱地型 血浴地型` | 各 `≥ 30` 盘 |
| **P1** | **风化符石 & 深青色问号珠**<br>(`WEATHERED` / `QUESTION`) | `WEATHERED` 召回 `10.5%`<br>`QUESTION` `th=1.01` | 风化颗粒在不同皮肤下对比度差异大；现代深青色 `?` 珠样本仅覆盖 2 个关卡 | `神魔之塔 風化符石 地獄級`<br>`神魔之塔 問號符石 災厄級` | 各 `≥ 30` 盘 |

---

## 二、战中敌技图标（`EnemyIconNetV2` + 徽标 OCR）补充清单

当前 `EnemyIconNetV2`（`[1, 3, 160, 256]` Anchor-Free CenterNet，36 类）100% 依赖官方图鉴透明小图标在背景条上的合成训练，**尚缺真实 1080p 战中 `CD` 下方带动态特效/血条遮挡的实况标注帧**。重点采集以下两类战中画面（脚本同步截取 `upper_strips/` 顶部敌技条）：

| 优先级 | 敌技图标组（`EnemyIconNetV2` 类别） | 当前状态与痛点 | 推荐繁体 YouTube 搜索关键词 | 采集用途 |
| :--- | :--- | :--- | :--- | :--- |
| **P0** | **带数值徽标类敌技**<br>`ICON_MIN_COMBO` (`≥N COM`)<br>`ICON_MAX_COMBO` (`≤N COM`)<br>`ICON_EXACT_COMBO` (`N COM`)<br>`ICON_DIGITAL_COUNT` (`NUM N`)<br>`ICON_STEP_LIMIT` (`≤N 步`) | 图标定位已打通，需覆盖 `5~12 COM`、`NUM 10~30`、金字/白字/红字及不同手机分辨率，联动端侧 `PP-OCRv5` 数值读取校准 | `神魔之塔 首消粒數盾 地獄級`<br>`神魔之塔 固定連擊盾 災厄級`<br>`神魔之塔 步數限制盾 通關` | 战中 `CD` 图标 + 右侧/内部数字端到端校验集 |
| **P0** | **细微银盾 / 屬性盾簇**<br>`ICON_THREE_ELEMENTS` (二/三/四属)<br>`ICON_FIVE_ELEMENTS` (五属)<br>`ICON_ENCHANTED_BREAKTHROUGH` (强化突破)<br>`ICON_UNMATCHABLE` (符石禁咒)<br>`ICON_FORBID_ALL` (禁消/回复颠倒) | 银色盾牌底板相同、内部彩球/小角标仅 `6~10px`，纯合成域泛化有限（`F1 = 0.11~0.54`） | `神魔之塔 五屬強化盾 地獄級`<br>`神魔之塔 三屬盾 四屬盾 災厄級`<br>`神魔之塔 符石禁咒 禁消 通關` | 补充真实战中 `upper_strip` 正样本微调 CenterNet 分类头 |
| **P0** | **天降 / 轨迹 / 种类盾簇**<br>`ICON_NO_CASCADE` (禁天降/寒之气)<br>`ICON_TRAFFIC_LIGHT` (起结有序)<br>`ICON_BURNING_TRAIL` / `ICON_CORROSION_TRAIL`<br>`ICON_WAVE1_KIND` (首消同/异种类)<br>`ICON_WEAKENED_FORBID` (禁消弱化) | 多变体共用概念（如禁天降含雪花/锁链双图标，首消种类含同/异双图标），合成召回偏低 | `神魔之塔 禁天降 寒之氣 地獄級`<br>`神魔之塔 起結有序 黏腐 燃燒`<br>`神魔之塔 首消相同種類盾 首消不同種類盾` | 提升战中免长按直接激活率 |
| **P1** | **盘面异常预警小图标**<br>`ICON_ELECTRIFIED_HINT`<br>`ICON_FROZEN_PETRIFIED_HINT`<br>`ICON_WEATHERED_BOMB_HINT`<br>`ICON_ELEM_BURST` (累积符石盾) | 辅助 `SkillPriorVisionFilter` 提前注入先验，需实况帧降低复杂 Boss 光效误判 | `神魔之塔 電擊 凍結 風化 地獄級`<br>`神魔之塔 累積符石盾 災厄級` | 强化 `S1 → S2` 视觉先验协同 |

---

## 三、自动化执行与清洗要求

1. **一键批量检索下载与切图命令**：
   ```powershell
   .\ml\.venv\Scripts\python.exe ml/tools/download_low_recall_topup.py --max-per-query 2 --workers 3
   ```
2. **自动产出目录结构**（位于 `backups/phase4_v2_inbox/topup_v090_low_recall/<category>/<tag>/`）：
   - `frames/`：自动裁去 16:9 左右黑边/主播侧栏后的纯净竖屏战斗视口帧
   - `boards/`：血条锚定切出的 `6×5` 盘面图（用于 `BoardMasterGridNetV2`）
   - `upper_strips/`：同步切出的 `[0.04W..0.96W] × [0.08H..0.42H]` 战中敌技图标条（用于 `EnemyIconNetV2` 实况微调与徽标 OCR 回归测试）
   - `pairs/`：自动筛选手指未触碰、盘面静止但特效闪烁的 `(t, t+0.35s)` 真实双帧对
   - `contact_sheet_boards.jpg` & `metadata.json`：供人工 10 秒快速巡检剔除残影帧
