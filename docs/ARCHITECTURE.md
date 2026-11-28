# AutoDora-NEXT v3.0 系统架构、功能设计与演进规划

> **当前基线版本**：`v0.9.1`（`versionCode = 901`，`minSdk = 29` / `targetSdk = 35`，Android 10 ~ Android 16+）  
> **项目定位**：面向《神魔之塔》（Tower of Saviors）13 年全机制演进与现代 Android 设备的**免 Root、全规格动态棋盘自适应、可自由组合解盾、问号/按珠变盘双模破局、1:1 实时盘面编排、C++20 端侧高速引擎驱动**的新一代智能转珠辅助工程。  
> **设计基线**：全面继承并升级旧版 [AutoDora-V3.4.3](./LEGACY_AUTODORA_ANALYSIS.md) 经实战验证的悬浮窗交互、调参与「问号‧转/风化‧转」按珠重算精华；完整对照 [《神魔之塔》13 年全机制全景调研手册](./TOS_COMPLETE_MECHANICS_CATALOG.md)、[98 项官方敌技审计报告](./enemy_skill_audit.md) 与 [22 项符石状态核验报告](./runestone_status_audit.md)（官方百科 20 项 + 百科外经典 2 项）。  
> **标定与合规原则**：所有棋盘几何探测、校准基准与视觉特征阈值均严格以**真实《神魔之塔》游戏画面**实测标定；仓库仅包含工程代码、结构化技术规范与应用自身纯净截图，严禁提交任何第三方版权游戏素材。

---

## 1. 动态棋盘规格与自动探测架构（`core-capture` + `solver-core`）

### 1.1 支持的棋盘规格

| 规格类型 | 尺寸 (`Cols × Rows`) | 格数 | 触发场景与几何特征 |
| :--- | :--- | :---: | :--- |
| **标准盘面** | **`6 × 5`** | 30 | 游戏常态核心战斗盘面（6 直行 × 5 横行）。 |
| **扩版盘面（违建）** | **`6 × 8`**（主力）<br>兼容 `6 × 6` / `6 × 7` | 36–48 | 主角【界限突破】、合作角色及黑金上滑扩行。左右边界、底边与 `cellSize` 保持不变，在标准 `6×5` 正上方直接**向上延伸 1–3 行**。 |
| **任意动态矩形** | **`3 × 3` ~ `8 × 8`**<br>+ 局部失效格掩码 | ≤ 64 | 适配暴风/碎裂/冰封造成的等效缩盘、特殊活动关或未来新规格棋盘。 |

### 1.2 引擎底层：64-bit 位掩码动态网格

- `solver-core` 彻底消除硬编码 `6×5` 常量，所有棋盘结构参数化为 `(cols, rows)`（`3 ≤ cols, rows ≤ 8`，最大 64 格）。
- 全部空间状态掩码（拼图 1/2、起结有序绿/红漩涡、角色通行/停放、燃烧、灼热、黑洞/血浴终点禁停、结界、碎裂、暴风、瘴气、禁止踏入等）均采用 **`uint64_t` 单字位掩码**表示，单条 CPU 位运算指令即可完成整盘碰撞与条件判定，零动态内存分配。

### 1.3 两级棋盘规格探测与画面门控管线（`AutoGridDetector`）

```mermaid
flowchart LR
    Frame["MediaProjection 零拷贝采帧"] --> Gate["画面有效性门控: 过滤非战斗 / 转场 / VICTORY 结算 / 敌技详情弹窗"]
    Gate -->|"非有效战斗盘面"| Clear["静默隐藏悬浮轨迹与记忆叠层"]
    Gate -->|"有效战斗盘面"| FastProbe["一级血条锚定快检 (< 3ms)"]
    FastProbe -->|"检测到连续血条隔离带"| G65["锁定标准 6×5 (防头像栏假违建)"]
    FastProbe -->|"无血条隔离且上方 1..3 行连续命中符石"| G68["自动切换 6×(5+k) 违建盘面"]
    FastProbe -->|"开启全自动任意规格模式"| FullDetect["二级梯度投影 + 周期自相关探测"]
    FullDetect --> DynamicOut["输出动态 BoardGeometry (rect, cols, rows, cellSize)"]
```

- **双模免 Root 屏幕采集（兼容系统录屏）与血条锚定防误判**：
  - **无障碍截屏模式（`AccessibilityScreenCapturer`，Android 11+ 默认推荐）**：基于 `AccessibilityService.takeScreenshot()` 按需直采实时合成帧，**完全不占用系统独占的 `MediaProjection` 录屏通道**，小米 15 / HyperOS 等机型开启本应用后系统自带录屏全程正常可用，且免每次启动弹窗授权、无久放黑屏掉权限问题；内置 `370ms` 防限流节拍器、2 帧半周期闪烁融合封顶与 `850ms` 非阻塞后台问号记忆监听。
  - **高速录屏采集模式（`MediaProjectionCapturer` + `EglCaptureRelay` + `BuiltInScreenRecorder`，可选 / Android 10 回退）**：底层采用 OpenGL ES 2.0 / EGL 1.4 `SurfaceTexture`（`GL_TEXTURE_EXTERNAL_OES`）单 `VirtualDisplay` 零拷贝双路分发——CV 识盘时按需 0.4ms GPU 渲染至 `ImageReader`，录屏开启时以 `THREAD_PRIORITY_DISPLAY` 定频直推硬件 `MediaCodec`（真机硬编 `60fps / 8Mbps`，软件编码回退 `30fps / 6Mbps`，1s GOP、0-based PTS 重基，零 CPU 拷贝与软件缩放，完整收录游戏画面与全部悬浮识别层/路径/结果卡片），经 Scoped `MediaStore.Video.Media` 免存储权限直存系统相册（`Movies/AutoDora-NEXT/AutoDora_YYYYMMDD_HHmmss.mp4`），并在切回无障碍截屏、停服或系统 `onStop()` 时先自动收尾存片再释放录屏通道。
  - **全态血条锚定防假违建**：在 `base.topPx` 上方无缝覆盖 **`0%–100%`** 全态血条——绿色/珊瑚红（严格限定红粉紫 `H ≤ 22° || H ≥ 295°` 且附纵向薄条校验，拒绝暖米色/全高活动横幅）/青蓝 HP 血条隔离带、**`1%–30%` 极低/低血亮红危急数字血条（同色系血量残条与右侧红字均不误计为异色珠体）**，**以及 `1/584001` 空血暗槽 + 左侧红心徽章**；命中时严格锁定 `6×5`，同时在 `isBattleBoardPresent()` 兼容密集路径与左上角珠色徽章遮角（`darkCornerRatio >= 0.48` 联立高对比度与亮珠中心校验），并仅在全屏问号记忆圆圈覆盖态启用严格血条隔离，彻底消除计算后 1 秒路径被巡检误清现象。
- **实时视觉校准框**：主悬浮栏点 `▦` 随时开启可拖拽四角/四边的半透明校准框，并附带 `上 / 下 / 窄 / 宽 / 高- / 高+ / 自动 / 重置` 像素级微调条，按比例持久化至 DataStore。

---

## 2. 视觉识别、记忆快照与 1:1 盘面编排闭环（`core-vision` + `app`）

### 2.1 多帧融合真实游戏 CV 识盘（`CvBoardRecognizer`）

针对电击闪电、燃烧火焰、拼图流光、强化星芒与红绿灯漩涡的闪烁动画特性，采集层支持 `1~3` 帧短间隔多帧融合（默认 2 帧），使用零全屏拷贝的 `DirectBitmapVisionFrame` 稀疏坐标直采，完成以下要素识别：

1. **6 属性基础珠色分类**：按真实神魔 HSV 色域标定（暗 `248°–316°`、心 `316°–346°` 等），采样环**严格避让右上象限**（杜绝种族黑白徽章与四类敌技印记干扰原色）。
2. **珠型、异常符石状态与盘面地形自动识别（18 项实测 0 误报 CV 检出）**：
   - **强化符石（`+`）**：多帧高光峰值星芒特征；
   - **种族徽章提示（V3 圆章几何）**：在右上角 `[0.28r..0.78r]` 执行 `7×7` 采样，联立检验暗色圆章内核、外半区白/米金圆环（`ix >= 4 || iy >= 4`）与低鲜艳珠体溢出（`≤ 26%`），兼容魔族/人类等全部 7 大种族徽章（实测魔族盘面 `29/30`）同时对非种族高光心珠实现 `0/30` 零误报；
   - **电击符石（#01 電擊，`电`）**：在红绿灯与问号珠判定之前优先执行 13×13 网格多帧峰值检测，覆盖「强白炽核心 + 青蓝电弧外溢边角」闪光峰值相与「颜色无关低亮弱电弧（`avgV ≥ 0.58..0.62`，全 6 色木/火/暗珠同样覆盖）」双分支，并在 `detectTrafficLightMarkers` 中显式豁免以防白核+邻格木珠误判绿灯起手；通过外缘 `[0.22..0.385]` 饱和像素剔除青蓝电光智能还原 6 属真实底色（光珠金橙 `15°–74°` vs 火珠正红 `<15°/≥346°`），实机图 `2/2` 精准检出且 `0/328` 误报；
   - **冻结（#03 凍結，`冻`）与冰封（冰封，`冰`）**：基于 13×13 网格相邻像素饱和度跃迁晶格纹理（`latticeRatio ≥ 0.30`，全 6 色通用无水珠偏置）+ 四角亮面 + 任意色相饱和底珠（`satColorCore ≥ 0.20`）识别 6 色冻结，与深紫晶脉冰封（`icePurpBody ≥ 0.45` & `iceWhiteBody ∈ [0.20..0.44]`）清晰解耦（含暗珠冻结不误判冰封），并在红绿灯检测前优先扫盘豁免以防木/火方形冰块误触发绿/红漩涡；
   - **爆破炸弹（#09 爆破炸彈，`炸`）**：中心正红圆核（`r ≤ 0.145`）联立中环哑光灰金属六角壳（`r ∈ [0.18..0.355]`），并从最外缘恢复 6 属底层属性；
   - **邪恶能量（#12 邪惡能量，`邪`）与炼狱之火（#59 煉獄之火，`狱`）**：邪恶能量在窄带青绿幽火之外支持 `CV-Rule-v2.7` 全 6 色元素着色邪能外焰检测（前置 `brightMatchableCount >= 6` 门控防暗色说明弹窗误触发）；炼狱之火识别纯白强核外绕洋红紫焰并加入洋红主导保护（`purgMag ≥ elecCyanBorder × 1.5`）及红绿灯豁免；
   - **问号符石（`?`）**：暗灰蓝/深青绿低饱和特征与 `looksLikeQuestionGlyphAndRim`（`0.036f` 容差）支持 `±3px` 采样偏移，同时与鲜亮水珠严格解耦；
   - **石化符石（#04 石化，`石`）**：识别暗炭蓝岩体（`≥ 22%`）交织赤红熔岩裂纹（`≥ 18%`）；
   - **化血符石（#08，`血`）& 全版/单颗经典锁定符石**：统一识别灰石壳缠绕外观，覆盖全部 6 属性（含低饱和橄榄灰锁定光珠），支持全版 30/30 锁定盘面 100% 识别且不被误判为转场暗化界面；
   - **荆棘符石（百科外经典，`棘`）**：双侧中腰棕褐棘蔓带 + 上下珠体反差校验；
   - **燃烧地形（#25 燃燒，`燃`）、血浴地形（#31 血浴，`浴`）与碎裂地形（#35 碎裂，`碎`）**：在拼图框识别之后执行棋盘级地形后验扫盘——燃烧地形去除光珠高亮偏置（`maxAvgBodyV ≥ 0.40` 全 6 色暗珠通用），配合拼图金框下限收紧至纯黄金色 `35°–64°`（彻底隔离 `10°–33°` 橙红火焰，防止连续 `1×6` 燃烧横行/围城被误判为拼图盾），实机 `1/1` 检出、`0/329` 误报；血浴地形在 `CV-Rule-v2.7` 支持单格及全版 30/30 深暗赤红血池浸染（放行 `brightMatchableCount >= 8 || bloodbathCandidateCount >= 18`，并在 `measureBoardDimness` 排除深红暗池免误计暗化）；碎裂地形在确认存在 `≥ 8` 颗正常亮珠的活跃盘面上识别漆黑蛛网裂纹空洞并优先豁免暴风误判；
   - **暴风区域（`暴风`）**：多帧灰白龙卷风纹理与高饱和缺失联动判定；
   - **结界地形兼容**：针对结界滤镜与边框变暗优化色域容错，稳定维持盘面识别；
   - **拼图盾框自动抠图**：同时兼容经典金色亮边与现代金属雕花边框；
   - **起结有序 V4 自动识别**：在四角极角探针外叠加 24 角度外环绿芒扫描（突破右上角种族圆章遮挡与 ±8% 纵向漂移），结合火珠邻格红溢惩罚与非火非心红环救援，按官方机制严格择优锁定单一绿色 `GO` 起点漩涡与单一红色停手漩涡（并防止白色 `GO` 字母误触发问号或强化判定）；所有自动检出状态与地形均在 `PathOverlayView` 路径预览与编排网格同步呈现高辨识角标。
3. **非战斗与转场防误显保护**：后台 ~3Hz 监听强制在盘面上方校验血条隔离带（非违建状态下独立于悬浮绘制区，从根本上杜绝自身半透明问号记忆圆圈在活动列表页触发自激反馈循环）并配合双区暗箱弹窗校验（需同时满足上框 `41%..53%` 与盖盘区 `58%..78%` 暗化），自动拦截活动列表页、关卡切换动画、`VICTORY` 结算画面（同时放行全版 `?` 与全版 30/30 锁定/化血盘面）与敌技长按弹窗。
4. **端侧双架构 AI 识盘 + 非瀑布多得分先验融合 + 全时协同敌技图标 ONNX（`BoardMasterOnnxClassifier` + `LiteRtOrbClassifier` + `EnemyIconOnnxClassifier` + `HybridFusionPolicy`，`v0.5.0 – v0.9.0`）**：
   - **非瀑布多得分 CV + `SkillPriorVisionFilter`（`v0.9.0`）**：`CvBoardRecognizer` 废除硬编码 `if/else-if` 瀑布截断，对冰封、冻结、炼狱之火、电击、炸弹、邪能独立计算连续置信分并结合当前波次 18 维 `BoardVisualHints` 执行先验加减权；同时当存在电击/隐藏先验或上一帧检出闪烁态时，自动将采样帧数动态提升至 `4` 帧（`70ms` 间隔）。
   - **Stage 2A 主力网格 ONNX（`BoardMasterGridNetV2` · `board_master_v2.onnx`, `2,125.2 KB`, `536,719` 参数）+ `MonochromeOrbMatcher` 100% 复色融合**：单模型动态支持 `6×5` 与 `6×8` 违建双帧 Flicker 编码 → `[1, Rows×Cols, 47]`（`clean_6color_element = 97.17%`，零误报激活 `10/17` 类核心异常含 `BLACK_WHITE = 0.94`）；与内置 24×24 官方图鉴轮廓高通匹配器 `MonochromeOrbMatcher` 执行 `50/50` 概率融合（实机黑白拼图盲测纯 CV 与混合模式均达 `30/30 = 100%`），并通过 `ActiveStrategyStore.mergeBoardDerivedSignals` 实现 **`S2 → S1` 盘面反哺策略闭环**。
   - **LiteRT 回退通道（`AutoDoraOrbNetV1` · `orb_multihead_v1.tflite`, `340.4 KB`, `84,191` 参数）**：保留为自定义 `.tflite` 热覆盖与回退引擎。
   - **Stage 2B 全时协同 36 类 CenterNet 敌技图标 ONNX + 盒式抗混叠自遮罩 + 关键点数值/颜色直读（`EnemyIconNetV2` · `enemy_icon_v1.onnx`, `1,780.4 KB`, `449,388` 参数）**：升级为 `[1, 3, 160, 256]` 盒式面积抗混叠下采样（对齐训练端 `Image.Resampling.BOX`）+ 悬浮工具栏 `excludeRect` 自遮罩与 `/8` (`20×32`) Anchor-Free CenterNet 高斯热力图架构（引入锐利怪物躯干硬负样本重训后验证集宏 F1 `0.6175`、`neg_fp = 0/450`、激活 `25/36` 类），仅在 `BoardCaptureQualityGate` 连续 `≥2` 帧静止有效盘面（排除连击爆炸、过场横幅与系统录屏白色 Toast 遮挡）时触发；检出带参或单色图标时，自动调用 `EnemyBadgeDigitExtractor` + `PP-OCRv5` 数字白名单 CTC 与局部 HSV 判色提取精确连击/粒数/属性，并支持同波多怪掩码合并与局部拼图盾无损升级（如 `拼图 → 严格拼图·水`）。
5. **纯离线 PP-OCRv5 ONNX + 四级敌技协同匹配引擎 + 策略抽屉自动回显（`PaddleOcrOnnxEngine` + `TosEnemySkillParserV2` + `EnemySkillKnowledgeMatcher` + `BoardAutoPolicySummary`，`v0.6.0 – v0.9.1+`）**：
   - 设置拆分为独立的 **「实时采集战前关卡资讯」**（`autoCapturePreBattleStageInfo`）与 **「实时采集战中敌人技能」**（`autoCaptureInBattleEnemyInfo`）双开关；`FlashRevealMemoryTracker.classifyDarkModalForCapture` 联立校验队伍头像/血条带 `[0.33..0.41]` 色彩可见度并在暗箱出现时切入 `130ms` 高速轮询，确保暗夜/深渊背景关卡中的长按敌技弹窗 100% 归入战中即时装配链路。
   - 新增 [`EnemySkillKnowledgeMatcher`](../android/core-vision/src/main/kotlin/com/autodora/next/vision/EnemySkillKnowledgeMatcher.kt) 四级解析体系：**Tier 0** 用户手动编修词条（`MANUAL_EDIT`）最高优先覆写 → **Tier 1** `TosEnemySkillParserV2`（`tos_enemy_skill_rules_v2.json v2026.11-v2.2`，补齐 `forbid_cross`、`max_combo`、`square_3x3`、`keep_element_at_least_one`、`max_element_orbs`、无数字 `【指定連擊法印】` 及复合属性拼图/形状盾）实时提取 → **Tier 2/3** 内置 `499` 条权威敌技库与本地历史库骨架模糊兜底（标记 `✦库`）；AI 挂载波次盾后自动联动双端策略页展开对应分组卡片、切换当前激活的「属性消除限制」子标签并渲染 `✦` 青色 AI 高亮。
   - `BoardAutoPolicySummary` 独立拆分 `FROZEN`（`解冻结`）与 `ICE_SEALED`（`解冰封`），且结果卡片所有 `🛡` 策略胶囊均支持点 `×` 本波临时豁免。
6. **三级游戏入口波次剧本库与全内嵌敌技中枢（`Room v4` + `StageHierarchyHelper`，`v0.7.0 – v0.9.0`）**：
   - Room 升级至 `AutoDoraDatabase v4`（`MIGRATION_3_4` 无损扩展 `entryCategory` / `subCategory` / `dungeonSeries` 三级分类字段），首次启动自动灌入内置 `140` 关权威剧本包（`tos_stage_wiki_bundle_140.json`），亦可在「敌技」页一键重导 `140` 关或 `499` 条权威敌技；「敌技」页彻底取消浏览器弹窗，全部采用每页 `6` 条内嵌分页、三级入口联动过滤与原位波次详情展开。

### 2.2 全屏问号珠双模破局引擎

| 模式 | 适用场景 | 实现机制 |
| :--- | :--- | :--- |
| **模式 A：先露后藏记忆快照**（`FlashRevealMemoryTracker`） | 进场或回合初短暂展示真实盘面后翻为全版 `?` 问号珠 | 后台监听仅在血条验证有效战斗态下锁定翻面前最后一张稳定清晰盘面；当连续 `≥ 2` 帧检测到 `≥ 60%` 格子为 `?` 问号珠时自动触发透视叠画并直接送入求解器。 |
| **模式 B：免 Root 按住重算**（`Hold-and-Replan`） | 问号‧转 / 风化‧转 / 烟雾等须先按住或微移 1 格才显形/变盘的关卡 | 主开关「按住转珠重算」下设隶属触发子项：**「原地按住」**（`HOLD_IN_PLACE`）与 **「微移 1 格」**（`NUDGE_ONE_CELL_HOLD`）。`AccessibilityTouchExecutor` 以动态上限 `maxHoldWaitMs` 心跳保活不抬手 → 显形动画早停轮询（`holdReplanDelayMs = 240–900ms`）→ 以当前按住格为 `fixedStartCell`（解除单起点早期 `maxPerStart` 波束坍缩）执行 **用户可配时间预算的 C++20 深度求解（`holdReplanSolveTimeMs = 150–3500ms`，默认 `400ms`；自动分级解锁 `36 / 52 / 80` 步与 `200 / 360 / 600` 算力）** → 同一根手指 `continueStroke()` 无缝续滑全程。 |

### 2.3 1:1 交互式盘面编排（`OverlayBoardEditor`）

针对极少数物理像素不可见或叠加态复杂的关卡（如伪装珠、黑白符石、复合多障碍/多地形），提供高效率 1:1 实时盘面编排，作为全自动识盘的极速兜底与自定义练习沙盒：

- **打开自动实时识盘 + 一键「重新识盘」**：在未处于「已保存版面」态打开盘面编排时，系统自动瞬时隐藏编排栏与网格、清空旧帧缓存并采集当前实时战斗画面，完成几何定位与全量 18 项珠色/状态/地形自动识盘后直接预填 `editedBoard`（全屏问号关自动叠加先露后藏记忆快照），彻底告别打开编排全是默认假珠；同时在工具行提供独立 **`重新识盘`** 按钮与 `识盘中...` 状态提示，随时一键重抓实盘。
- **6 大彩色单开互斥手风琴抽屉（每组均配 `(?)` 帮助说明）** + **独立色框外「重新识盘」与「清除格标」工具**：
  1. **靛蓝 · 珠色与珠型**：水 / 火 / 木 / 光 / 暗 / 心 + 强化`+` / 弱化`-`（默认展开）
  2. **紫色 · 种族符石**：神族 / 魔族 / 人类 / 兽类 / 龙类 / 妖精 / 机械（7 大官方种族）
  3. **琥珀 · 拼图与起结有序**：拼图 / **绿色漩涡**（严格单点互斥）/ **红色漩涡**（严格单点互斥）/ **起动浮标** / **角色通行**（支持多格）/ **角色停放**（支持多格）
  4. **青绿 · 符石状态**：风化 / 电击 / 冻结 / 冰封 / 石化 / 凝结 / 化血 / 炸弹 / 泡沫 / 邪能 / 刺血 / 魔力 / 流光 / 炼狱 / 荆棘 / 锁定（纯粹官方原生符石状态）
  5. **绯红 · 盘面地形**：燃烧 / 灼热 / 黑洞 / 血浴 / 碎裂 / 暴风
  6. **深蓝 · 自定义操作（`v0.9.0` 新增最高优先级战术层）**：**必消此珠（`必`）/ 保留不消（`留`）/ 锁定不动（`定`）/ 禁碰禁行（`禁`）/ 顺序途经 `①~④` / 禁止停手 / 格内禁消 / 指定起点 / 指定终点**，内置 **`跨回合锁定自定义标记`**（同波换盘自动合并继承，换波清零）与 **`清空自定义`**（仅剥离自定义格标，完整保留识盘珠色与原生障碍）。
- **极速工作流**：点击/连涂编排完格标后，既可点右上角 `保存版面`，亦可**直接点击主悬浮栏 `✦ 计算` 或 `▶ 执行` 自动保存脏盘面并立即求解**；结果卡片逐条核验 `✓ 编排必消×N` 与 `✓ 编排保留×N`。

---

## 3. C++20 求解引擎与全机制规则模型（`solver-core` + `core-model`）

### 3.1 两层叠加策略合并架构（`RuleComposer`）

```mermaid
flowchart LR
    Base["底层: Room 队伍常驻策略 (默认策略 / 用户自建策略)"] --> Merge["RuleComposer 动态规则合并器"]
    Wave["表层: 39 项参数化波次盾 Chips (ActiveStrategyStore)"] --> Merge
    Board["盘面实况: CV 自动识别 / 1:1 编排版面 (拼图/漩涡/障碍/地形/种族)"] --> Merge
    Merge --> Final["不可变 RuleConfig + BoardState -> JNI -> C++20 Beam Search"]
    Final --> Verdict["Top-5 候选解 + ShieldVerdictEvaluator 逐盾 ✓/✗ 核验"]
```

### 3.2 当前已全面落地的核心物理与解盾能力

1. **玩家队伍与连消机制**：
   - 按属性独立 `1~5` 消门槛（支持指定色一消、二消、三消以及四/五消起消队长技）、独立于斜转的 **8 向斜向消除**、`6×6` **跨色相连等效矩阵**（如水火相连即消、心兼全属连消）。
   - **角色符石与起动浮标起手（No.5893 / 7403 / 5894）**：支持暗化盘面线性增益还原、发光头像「起动浮标」与头像左上角属性自动识别、第 `1~6` 列垂直下拉（下拉不计步且自动覆盖第 1 行障碍）、自动纳入首批消除破「消除起动浮标角色符石盾」，完整支持角色符石通行与停放地形，策略端可自由开关自动浮标或手动选择槽位与属性（自动/5 色），盘面编排内置「起动浮标」画笔。
   - **6 直排独立昇华**（每列独立配置 3/4/5 消/整列、必达 vs 加分、指定属性）与横排强化。
   - **单属性 6 色四列规则矩阵**：双端 4×4 选择器完整暴露 16 种目标形态（`默认 / 切3 / 2珠 / 4–9珠 / 横排 / 直排 / 十字 / L形 / T形 / 田字 / 全消`）× 优先权（`普通 / 优先 / 保留`）× 消除时机（`任意 / 首消 / 叠珠 / 禁消`）× 最低消（`1–5消`），内置 **`全矩阵重置`** 一键复位。
2. **全量 98 项敌技与 22 项符石状态精确建模**（含官方百科 20 项 + 百科外经典锁定/荆棘 2 项，详表见 [enemy_skill_audit.md](./enemy_skill_audit.md) & [runestone_status_audit.md](./runestone_status_audit.md)）：
   - **连击类**：高 C、最低总/首 C、**首消属性组数（排除心珠）**、固定总/首 C、奇/偶 C、次批叠珠 C、同属连击、**任意 2~6 属连击相等**、**首消相同/不同种类盾（心符石通用）**、**只能消除首批/寒之气禁天降**、0C 空转、防天降安全度评分。
   - **属性/珠数/种族类**：二/三/四/五属/六色（默认支持天降叠珠，可选严格首消）、逐属强化突破、**7 大具体种族盾（`RaceType` 位掩码）**、数字盾、首消全版、首消全某属、**符石禁咒（模拟层惰性不可消）**、反首消叠珠、绝对禁消、回复颠倒、单属最大化累积消除。
   - **形状类**：单/双拼图（严格不黏连 / 宽松 / 强化 / 指定色）、严格 5 粒单·双·三十字限 / T 字限 / L 字限（含形态拉珠引导势能）、2×2 田字。
   - **障碍与地形物理**：风化/电击/炼狱之火/黏腐/光牢/暴风禁入避障；石化与凝结按波次四邻至多解一重；泡沫可移动且实时追踪重力落点四邻破裂；化血全波消除；爆爆炸弹触碰扣血避让；魔力每消 `+3 Combo`；邪恶能量/刺血/弱化避消惩罚或硬禁消；灼热地形消除扣血可穿行；黑洞与血浴可穿行仅禁止停手；结界地形第 2 波起屏蔽消除；步数区间盾 `[minSteps, maxSteps]` 与一步一伤。
3. **跨平台确定性验证**：
   - `solver-core/tests/test_main.cpp` 包含 **52 项 C++20 单元测试**，每项测试执行独立路径回放核验（`verifyPathReplay`），确保零非法步、零穿墙、模拟结果 100% 吻合。

---

## 4. 免 Root 触控执行与 6 档统一速度（`core-touch`）

- **精准分段手势链（`AccessibilityTouchExecutor` + `PathInterpolator`）**：
  - 将整条路径拆分为「起手按稳（`55ms`）→ 首步防滑拐保护段 → 45° 斜转加速/直行分段（每段上限 14 步经 `willContinue` 无缝续接）→ 终点停稳（`45ms`）」，避免 Android `PathMeasure` 匀速压缩导致的丢步与首消错位。
  - **角色符石 / 起动浮标下拉**：校准至真实队伍头像中心 `topPx - 0.77*cellHeight`，按稳 `≥45ms` 后经 `topPx` 严格垂直下拉至 `(0, col)`（`72–140ms`）再无缝衔接盘内路径。
  - **四态颠倒方向操控镜像**：原生独立支持 `invertX` / `invertY` 坐标镜像输出，在启动页「设置」与悬浮窗「快捷 / 设置」1:1 统一暴露 **`正常 / 左右颠倒 / 上下颠倒 / 双向 180°`** 四态选择器，覆盖心镜迷宫/镜面反转及左右移动颠倒等全部反转关卡。
  - **急停中断**：随时点击主栏 `■` 立即取消协程与后续 `continueStroke` 分段。
- **6 档统一速度（`SwipeSpeedPreset` 双端 1:1 唯一数据源）**：
  - `极速 2s (24ms)` → `快转 3s (38ms)` → `敏捷 4s (52ms)` → `标准 5s (68ms)` → `舒缓 6s (82ms)` → `稳健 7s (95ms)`，支持滑杆 `20–140ms` 精细调节与拟人化微抖动。

---

## 5. UI 交互架构、多语言与应用内更新（`app` + `core-data`）

### 5.1 启动页与悬浮窗的双端一致性设计

| 维度 | 启动页（Jetpack Compose Material 3） | 游戏内悬浮窗（WindowManager + 原生高帧视图） |
| :--- | :--- | :--- |
| **分页结构** | 4-Tab：**概览 / 策略 / 敌技 / 设置** | 3-Tab：**快捷 / 策略 / 设置**（与启动页 1:1 同步） |
| **抽屉色彩** | 6 色语义色条 + 浅色边框卡片（蓝/靛蓝/青绿/琥珀/绯红/紫） | 与启动页对应模块使用**完全一致的抽屉配色** |
| **展开模式** | 默认**全部展开**，支持多抽屉同时展开或单独收起 | 调参分页与盘面编排均采用**单抽屉手风琴互斥展开**，防止遮挡游戏盘面 |
| **主题与语言** | 跟随系统 / 明亮 / 黑暗 Material You（暗色模式下对「本波已启用解盾」激活条与彩色抽屉摘要徽章独立高对比度适配）；跟随系统 / 简中 / 繁中 / English（`728` 词条纯终态文案对齐） | 与启动页**实时同步换肤与热切语言**，零重启 |
| **主悬浮胶囊** | 概览页一键启停悬浮助手；设置支持控制 **高速录屏模式下悬浮栏是否显示录屏按钮** 以及 **横排 / 竖排** 与 **靠左 / 靠右** 4 态切换（`floatingBarVertical` + `floatingBarDockRight`） | 圆形 App 图标手柄 + 8 个常驻矢量图标（`✦` `▶` `■` `〰` `▤` `▦` `🎚` `✕`）+ 高速录屏模式下可配置增显 **`● 录屏`** 图标；支持 **4 态实时热切**（横排靠右时 App 图标居右、主栏向左展开；竖排靠左向右/靠右向左展开面板）、**拖拽松手平滑左右吸边**及 **DataStore 持久化记忆停靠侧与纵坐标** |

### 5.2 单一自动保存策略与应用内更新闭环

- **单一实时作战策略持久化（`TeamPresetDatabase` + `TeamPresetRepository`）**：
  - 界面聚焦唯一一套实时生效的作战策略，所有参数改动自动持久化并即时联动悬浮窗；顶部提供一键 **「恢复默认」**，单属性规则矩阵内提供独立 **「全矩阵重置」**。
- **应用内双通道检更、流式下载与自动安装（`UpdateChecker` + `MainActivity` + `FileProvider`）**：
  - 优先通过 GitHub Releases API 拉取最新正式版/预览版信息并进行 SemVer 语义化版本比对；遇到共享 IP/代理节点触发匿名 API `HTTP 403`/`429` 限流或请求失败时，自动无缝回退至免限流的 `github.com/.../releases.atom` 订阅源与 `releases/expanded_assets/<tag>` 片段解析最新版本号、更新说明及精准 `.apk` 资产链接（并附规范直链兜底）；
  - 点击 **「下载并安装」** 在 App 内执行协程流式下载：手动跟随最多 6 跳跨域名 302 重定向（覆盖 `objects.githubusercontent.com`）、100ms 节流进度回调（已下载 MB / 总大小 / 百分比）、支持随时取消并清理 `.part` 临时文件、下载前自动清理旧版本缓存、完成后校验 `PK\x03\x04` APK/ZIP 魔数头；
  - 安装包落盘至应用私有 `cacheDir/updates/`，经 `FileProvider` `<cache-path>` 授予临时读权限（Android 10–16 零存储权限需求）自动拉起 `ACTION_VIEW` 系统更新安装弹窗；若尚未开启「安装未知应用」，自动跳转系统授权开关并通过 `ActivityResultLauncher` 在返回后自动续接拉起安装器。

---

## 6. 系统分层组件架构与 Monorepo 目录

```mermaid
flowchart TD
    subgraph UI["表现与交互层 (android/app)"]
        MainApp["DashboardScreen & UnifiedStrategyTab (4-Tab M3 + 敌技中枢 + 置顶 AI 抽屉 + 引擎测速)"]
        OverlayMgr["OverlayControllerService (4 态横/竖排×左/右胶囊 + 左右磁吸记忆 + Top-5 结果卡片 + 逐盾灯号)"]
        TuningDrawer["OverlayTuningDrawer (游戏内 3-Tab 单抽屉手风琴面板)"]
        BoardEdit["OverlayBoardEditor (5 色单开互斥抽屉 + 激活徽标 + 独立清格标 + 保存版面)"]
        PathView["PathOverlayView (透传路径线 + 珠色/状态/拼图/漩涡叠层 + 拖拽校准框)"]
    end

    subgraph Data["数据与策略层 (core-model & core-data)"]
        Composer["RuleComposer & ShieldVerdictEvaluator"]
        Catalogs["WaveShieldCatalog(39) / TosEncyclopediaCatalog(98) / TosRunestoneStatusCatalog(22)"]
        Store["Room TeamPresetDatabase + DataStore SettingsRepository"]
    end

    subgraph CaptureVision["采集与视觉层 (core-capture & core-vision)"]
        MediaProj["MediaProjectionCapturer (前台零拷贝取帧)"]
        GridDetect["AutoGridDetector (血条锚定 6×5↔6×8 + 非战斗/弹窗门控)"]
        CvRecog["CvBoardRecognizer (CV-Rule-v2.7 多帧融合 + 拼图/漩涡/暴风/血浴/结界)"]
        FlashMem["FlashRevealMemoryTracker (全屏问号先露后藏记忆快照 + 弹窗分类)"]
        Hybrid["HybridBoardRecognizer (LiteRT AutoDoraOrbNetV1 + HybridFusionPolicy)"]
        OcrEngine["PaddleOcrOnnxEngine (ONNX PP-OCRv5 Mobile) + TosEnemySkillParserV2 (98敌技+灰字过滤)"]
    end

    subgraph Solver["纯 C++20 求解引擎 (solver-core + solver-jni)"]
        JniBridge["NativeSolver / solver_jni.cpp"]
        BeamEngine["多线程 Beam Search (3×3~8×8, uint64_t 掩码, 8~300 步)"]
        SimEval["精确消除物理模拟 + 全组合解盾评估器"]
    end

    subgraph Touch["免 Root 触控层 (core-touch)"]
        PathSmooth["PathInterpolator (首步防拐 / 45°斜转 / 角色珠下拉 / 镜像反转)"]
        A11yExec["AccessibilityTouchExecutor (分段续接 + 按住重算心跳 + 急停)"]
        ShizukuExec["ShizukuTouchExecutor (Phase 4 预留)"]
    end

    MainApp --> Store
    OverlayMgr --> TuningDrawer
    OverlayMgr --> BoardEdit
    OverlayMgr --> PathView
    OverlayMgr --> Composer
    OverlayMgr --> MediaProj
    OverlayMgr --> A11yExec
    Store --> Composer
    Catalogs --> Composer
    MediaProj --> GridDetect
    GridDetect --> CvRecog
    CvRecog --> FlashMem
    CvRecog -.-> Hybrid
    Composer --> JniBridge
    JniBridge --> BeamEngine
    BeamEngine --> SimEval
    A11yExec --> PathSmooth
    PathSmooth -.-> ShizukuExec
```

### 仓库模块与测试矩阵（当前 `189` 项自动化测试全通过）

| 模块 | 职责说明 | 自动化测试覆盖 |
| :--- | :--- | :--- |
| `solver-core/` | 纯 C++20 求解引擎（CMake，零 Android 依赖） | `test_main.cpp`：**55 / 55** 通过（含独立路径回放核验、硬必消/硬保留、正方 3×3 & 避消十字、符石加护留底≥1 & 限消≤N粒） |
| `android/core-model/` | 不可变棋盘/规则模型（含 `StageHierarchyHelper` 三级入口 / 18 维 `BoardVisualHints`）、`RuleComposer`、`ShieldVerdictEvaluator` | `RuleComposerTest`：**14 / 14** 通过 |
| `android/core-capture/` | `AccessibilityScreenCapturer`（Android 11+ 兼容系统录屏）+ `MediaProjectionCapturer` + `EglCaptureRelay` + `BuiltInScreenRecorder`、`AutoGridDetector` | `AutoGridDetectorTest` (14) + `ScreenCaptureSessionTest` (3) + `AccessibilityScreenCapturerTest` (3) + `BuiltInScreenRecorderTest` (3)：**23 / 23** 通过 |
| `android/core-vision/` | 非瀑布多得分 `CvBoardRecognizer` + `MonochromeOrbMatcher` + `SkillPriorVisionFilter`、`FlashRevealMemoryTracker`、`BoardCaptureQualityGate`、`BoardMasterOnnxClassifier` + `HybridBoardRecognizer`、36 类 CenterNet `EnemyIconOnnxClassifier` + `EnemyBadgeDigitExtractor`、`PaddleOcrOnnxEngine`、`TosEnemySkillParserV2` + `EnemySkillKnowledgeMatcher`、`StageWaveScriptParser`、`WaveTransitionDetector`、`BoardAutoPolicySummary` | `CvBoardRecognizerTest` (24) + `HybridBoardRecognizerTest` (4) + `BoardMasterOnnxClassifierTest` (2) + `EnemyIconOnnxClassifierTest` (2) + `EnemyBadgeDigitExtractorTest` (3) + `MonochromeOrbMatcherTest` (2) + `PaddleOcrPostprocessTest` (4) + `TosEnemySkillParserV2Test` (6) + `EnemySkillKnowledgeMatcherTest` (3) + `StageWaveScriptParserTest` (4) + `WaveTransitionDetectorTest` (1) + `BoardCaptureQualityGateTest` (2)：**57 / 57** 通过 |
| `android/core-touch/` | `AccessibilityTouchExecutor`、`PathInterpolator`、`SwipeSpeedPreset` | `PathInterpolatorTest`：**9 / 9** 通过 |
| `android/core-data/` | DataStore 设置、`AutoDoraDatabase v4`（`MIGRATION_3_4` 三级分类关卡波次剧本 + 敌技库 + 训练样本）、`ActiveStrategyStore`（含 `S2→S1` 盘面闭环反哺、双向矩阵同步与协同图标合并）、`StageWaveScriptRepository`、`TrainingDataRepository` | `ActiveStrategyStoreTest` (6) + `TrainingDataRepositoryTest` (3) + `ActiveStageScriptStoreTest` (1)：**10 / 10** 通过 |
| `android/solver-jni/` | C++20 NDK/JNI 桥接（`arm64-v8a` / `armeabi-v7a` / `x86_64`） | 随 Release/Debug APK 与真机/模拟器测速验证 |
| `android/app/` | 4-Tab 紧凑零截断启动台（`概览 / 策略 / 内嵌敌技中枢 / 设置` + 全抽屉 `(?)` 帮助）、4 态悬浮服务、3-Tab 同步单抽屉面板、6 抽屉盘面编排（含蓝色「自定义操作」）、`UpdateChecker` | `UpdateCheckerTest` (7) + `BoardEditorBrushTest` (5)：**12 / 12** 通过；四语言词条一致性 **864 / 864** |
| `ml/` | `AutoDoraOrbNetV1`、`BoardMasterGridNetV2`（Stage 2A 双帧动态 ONNX）、`EnemyIconNetV2`（Stage 2B 36 类 CenterNet 双输出图标 ONNX）、权威库清洗与三级关卡百科编译器 | `test_ml_pipeline.py` (4) + `test_enemy_skill_cleaner.py` (4) + `test_stage_wiki_compiler.py` (1)：**9 / 9** 通过 |

---

## 7. 分阶段研发路线图（Phase 1 – Phase 4）

| 阶段 | 版本范围 | 核心交付与状态 |
| :--- | :---: | :--- |
| **Phase 1：纯 C++20 动态内核 + 多模块骨架** | `v0.1.0` | **已完成** — `3×3~8×8` 64-bit 位掩码求解器、最高 300 步、Android 7 模块工程与 JNI 三架构编译。 |
| **Phase 2 & 2.5：免 Root 全链路 + 真图高精度标定** | `v0.2.x` – `v0.3.x` | **已完成** — 零拷贝 `MediaProjection`、血条防假违建、先露后藏记忆快照、免 Root 按住重算续滑、拼图/红绿灯/暴风/化血/结界识别、非战斗/VICTORY 过滤、方向逆转与逐盾 `✓/✗` 判定。 |
| **Phase 3 & 3.5：百科全量审计 + 统一交互 + 全 6 色特殊符石 CV 与长时稳定** | `v0.4.0` – `v0.4.9` | **已完成** — 全量 98 敌技 + 22 符石状态 + 7 大官方种族全链路升级；成员起动浮标（No.5893/5894/7403）全链路支持；双模屏幕采集与内置 60fps H.264 录屏；HyperOS / Android 14–16 稳态、`0%–100%` 全态血条锚定、18 项状态/地形自动 CV 识别与统一 M3 悬浮/编排闭环。 |
| **Phase 4：端侧多头 AI 识盘 + 多源敌技协同 + 紧凑零截断终版闭环** | `v0.5.0` – `v0.9.1`（当前）– `v1.0.0` | **已落地（`v0.5.0–v0.9.1`）** — ① `v0.5.0–v0.6.0` LiteRT `AutoDoraOrbNetV1`、无上限双轨采集飞轮与纯离线 `PP-OCRv5` 弹窗识字；② `v0.7.0–v0.8.1` 波次剧本、采集稳定门控、Stage 2A `BoardMasterGridNetV2` & Stage 2B `EnemyIconNetV1` 双 ONNX 上线与 4-Tab 启动台；③ **`v0.9.0–v0.9.1` 全界面高密度紧凑零 `...` 截断重构 + 全抽屉 `(?)` 帮助说明 + 非瀑布多得分 CV & 24×24 黑白轮廓 `MonochromeOrbMatcher` 100% 复色融合 + `S2→S1` 盘面反哺策略闭环 + 499 敌技缺口闭环与 6×5 双向同步单属性矩阵 + `EnemySkillKnowledgeMatcher` 四级模糊重水合 + 36 类 CenterNet `EnemyIconNetV2` 关键点数值徽标 OCR（`≥N COM` / `NUM N`）+ 硬必消/保留自定义操作第 6 编排抽屉 + Room `v4` 内嵌三级 `140` 关剧本 & `499` 敌技库**。<br>**后续迭代（`v0.9.x–v1.0.0`）**：小种族与长尾 C 类异态样本扩充、R8/ABI 瘦身（Phase D）、可选 Shizuku 触控与稳定版 `v1.0.0`。 |
