# AutoDora-NEXT 端侧多头轻量 CNN 与双 ONNX 训练管线（`ml/` · `v0.9.1` Stage 2A + 2B 全量重训升级）

本目录实现了 Phase 4 端侧多代 AI 模型训练与导出流水线：① `v0.5.0` LiteRT 兜底/自定义基线 **`AutoDoraOrbNetV1`**（`orb_multihead_v1.tflite`, `340.4 KB`）；② `v0.9.1` 主力 Stage 2A 双帧全盘面动态 ONNX **`BoardMasterGridNetV2`**（`board_master_v2.onnx`, `2,125.2 KB`, `BoardMasterOnnxClassifier`）与 Stage 2B 战中 36 类 Anchor-Free CenterNet 敌技图标 ONNX **`EnemyIconNetV2`**（`enemy_icon_v1.onnx`, `1,774.2 KB`, 双输出 `icon_probs + icon_peaks`，联动 `EnemyBadgeDigitExtractor` 关键点数值徽标 OCR），均已未压缩打包正式集成进 `android/core-vision` 与 `android/app`。

## 1. 训练与导出流水线

### Step 1：半自动抽帧切珠（`ml/tools/extract_tos_dataset.py`）

基于 `yt-dlp` + `ffmpeg` + 自动 `6×5` 战斗盘面定位，支持竖屏录屏与 16:9 YouTube 视频去侧栏定位、去重与 `64×64` 切片导出（全部第三方原始媒体统一保存于被 `.gitignore` 忽略的 `backups/user_training_media/`）：

```powershell
ml\.venv\Scripts\python.exe ml/tools/extract_tos_dataset.py --url "https://www.youtube.com/watch?v=XXXX" --tag clip_tag --fps 0.5
ml\.venv\Scripts\python.exe ml/tools/extract_tos_dataset.py --input path/to/recording.mp4 --tag local_clip
```

### Step 2：多头数据集构建（`ml/tools/build_phase4_dataset.py`）

从本地整理标注媒体与 `is_rune = 0` 硬负样本弹窗构建按视频/盘面严格隔离分组的训练与验证集（输出至 `.gitignore` 忽略的 `ml/datasets/phase4_v1/{train,val}.npz`）：
- 当前 `phase4_v1` 规模：**`6,486` 格样本**（跨 `146` 个独立盘面/视频组：`train = 4,979`、`val = 1,507`），带局部标签掩码与 `qa_label_check.jpg` 坐标可视化校验。

### Step 3：RTX 4050 GPU 训练（`ml/train_orb_cnn.py`）

```powershell
ml\.venv\Scripts\python.exe -m ml.train_orb_cnn
```
- **架构 (`ml/model_arch.py`)**：`AutoDoraOrbNetV1`（`84,191` 参数，深度可分离卷积 + 双分支全局池化 & 2×2 空间分支，适配四角旋涡与右上角种族徽章）。
- **增强策略**：逆频率 `WeightedRandomSampler`、`±3px` 边框抖动、暗化遮罩滤镜与 `12%` 合成灰度黑白珠增强。
- **验证集实测指标（`ml/runs/phase4_v1/eval_report.json`）**：
  - `is_rune_acc = 0.9628`（有效符石与非战斗弹窗分离）
  - `element_acc = 0.9271` | `clean_6color_element_acc = 0.9626`
  - `variant_acc = 0.8646`（普通 / 强化 `+` / 弱化 `-`）
  - `race_acc = 0.8682`（`HUMAN recall = 1.000`，`NONE recall = 0.934`）

### Step 4：BN 折叠直出 FP32 LiteRT 与近零误报阈值校准（`ml/export_litert.py`）

```powershell
ml\.venv\Scripts\python.exe -m ml.export_litert
```
- **直接 Keras → TFLite FP32 导出**（绕过 TF 2.21 FP16 转换 `NaN` 缺陷）：生成 `340.4 KB` (`348,548 B`) 的 `orb_multihead_v1.tflite`，并以未压缩模式（`compress_type = 0`）打包进 APK `assets/models/`。
- **PyTorch vs LiteRT 数值一致性与桌面 4 线程 CPU 实测耗时**：
  - `6×5` (`batch = 30`)：`max_abs_diff = 1e-6`，`2.07 ms`
  - `6×8` (`batch = 48`)：`max_abs_diff = 1e-6`，`3.91 ms`
- **近零误报生产级阈值校准**：在 `val.npz` 上对 `[0.65..0.99]` 扫描，仅采纳满足 `FP == 0` 或 `(TP >= 5 && FP <= 1 && precision >= 0.94)` 的阈值，其余高噪声/低样本类别自动设为 **`0.98` 安全兜底（fail-safe）** 由规则 CV 主导，写入 `model_meta.json`：
  - `BOMB` (`th=0.93`, `P=1.00`, `R=0.9333`, `FP=0`)、`BLOOD_THORN` (`th=0.97`, `P=1.00`, `R=0.50`, `FP=0`)、`MAGIC_ORB` (`th=0.65`, `P=1.00`, `R=0.2857`, `FP=0`)、`BURNING_TILE` (`th=0.65`, `P=1.00`, `R=1.00`, `FP=0`)、`SCORCHING_TILE` (`th=0.65`, `P=1.00`, `R=0.05`, `FP=0`)。

## 2. 47 维多头输出 Schema（`ml/schema.py` ↔ `HybridFusionPolicy.kt`）

- **输入**：`[Cols × Rows, 64, 64, 3]` Float32 RGB（`[0, 1]` 归一化，支持 `6×5` 与 `6×8` 动态 batch）。
- **输出（47 维拼接向量）**：
  1. `head_is_rune` `[0]` (1, Sigmoid)：是否有效符石格
  2. `head_element` `[1..9]` (9, Softmax)：对齐 `Element(0..8)`（水/火/木/光/暗/心/问号 `?`/未知/墙）
  3. `head_variant` `[10..13]` (4, Softmax)：对齐 `Variant(0..3)`（普通/强化 `+`/弱化 `-`/未知）
  4. `head_race` `[14..21]` (8, Softmax)：无种族 / 神 / 魔 / 人 / 兽 / 龙 / 妖精 / 机械
  5. `head_hazards` `[22..38]` (17, Sigmoid 多标签)：17 类核心异常/锁定/刺血/炸弹等位
  6. `head_tile_overlay` `[39..46]` (8, Sigmoid 多标签)：拼图 / 暴风 / 碎裂 / 燃烧 / 灼热 / 血浴 / 黑洞 / 红绿灯

## 3. 端侧无上限双轨采集飞轮、PP-OCRv5 ONNX 离线识字与合规规范（`v0.5.1–v0.6.0`）

- **App 端侧双轨采集导出格式**：`v0.5.1` 已上线 `TrainingDataRepository` + `Room AutoDoraDatabase v2`，在日常游玩时无上限收集两类高价值样本并一键打包导出 `AutoDora_TrainingData_<ts>.zip`（含根目录 `manifest.json`、`enemy_skills_master.json`、`boards/*_{a,b}.webp`、`viewports/*_upper.webp`、`enemy_popups/*_full.webp` 与 `meta/*.json` 含 P0 人工编排纠错 diff）；导出的 ZIP 可直接解压归档至 `backups/phase4_v2_inbox/device_exports/` 对接下一代 `BoardMasterGridNet` 与 `EnemyPopupParser` 训练。
- **纯离线 PP-OCRv5 Mobile ONNX 推理资产（`v0.6.0`）**：`android/app/src/main/assets/ocr/` 集成 RapidAI `ch_PP-OCRv5_det_mobile.onnx` (`4,819,576 B`)、`ch_PP-OCRv5_rec_mobile.onnx` (`16,631,306 B`) 与 `ppocrv5_dict.txt` (`18,383` 字表)，由 `ONNX Runtime Mobile 1.32.0` 驱动 `PaddleOcrOnnxEngine` + `TosEnemySkillParserV2`；识别出的全部技能（含灰字无视技能）均按 `skillHash` 实时累积写入 Room `captured_enemy_skills` 知识库（随训练集 ZIP 导出至 `enemy_skills_master.json`）。
- **PC 端权威敌技整合、关卡百科编译与 Stage 2A / 2B 训练管线（`v0.7.0–v0.8.0`）**：
  - `ml/tools/build_unified_enemy_skill_master.py`：只读融合 A1 实爬 `authoritative_enemy_skills_dedup.json`（最高权威）与 `docs/enemy_skill_encyclopedia_spec.json`（仅补充未覆盖条目），执行跨行 `【标题】` 修复、排版换行智能连接、场景尾巴剥离、严格近重复合并与 `rules_v2` 前后覆盖率审计，输出唯一权威库到 gitignored `ml/datasets/enemy_skill_master_clean/unified_enemy_skill_master.json`（当前 **`499` 条**：`A1 = 429 + 百科兜底 = 70`）；
  - `ml/tools/compile_stage_wiki_bundle.py`：只读编译 A1 `tos_current_version_stage_wiki.json`，传播每波 `scene_skill`、将多阶段 Boss (`phases`) 拆分为独立连续波次并生成 App 可直接导入的 `tos_stage_wiki_import_bundle.json`（当前 **`140` 关 / `498` 波 / `101` 阶段 / `1,255` 技能**；随 A1 爬虫增量随时重跑秒级刷新）；
  - `ml/tools/ingest_device_export.py`：一键解包真机训练 ZIP，按 `BOARD_EDITOR_CORRECTION = 4.0×` / `P1 = 2.0×` / `P2 = 1.0×` 分配采样权重，并与 `tos_enemy_skill_rules_v2.json` 比对输出未覆盖新技能清单 `unparsed_skills_report.json`；
  - `ml/tools/extract_youtube_frames.py`：提取间距约 `0.35s` 的静止双帧 WebP 配对（保留电击/燃烧/漩涡闪烁态）并执行 64-bit dHash 去重；
  - `ml/tools/download_low_recall_topup.py` & `ml/tools/download_broad_youtube_corpus.py`：对照 [docs/youtube_low_recall_acquisition_plan.md](../docs/youtube_low_recall_acquisition_plan.md) 与 [docs/youtube_broad_corpus_plan.md](../docs/youtube_broad_corpus_plan.md)，不仅完成首批 `42` 部低召回针对性视频（`549` 组盘面与顶栏，经周期接缝精裁并入当前增量重训），还完成了面向下一代大参数模型储备的 **10 大维度 72 词簇广谱双帧采集**（首轮已入库 **`110` 部新视频**：`1,737` 组 `(t, t+0.33s)` 标准 `6×5` 静止双帧对、`138` 组 `6×8` 违建双帧对、`1,737` 条战中顶栏与 `62` 张暗色敌技弹窗，保存于 `backups/phase4_v2_inbox/youtube_broad_corpus_v1/`）；
  - **Stage 2A `BoardMasterGridNetV2`（`ml/tools/build_phase4_v2_board_dataset.py` + `ml/train_board_master.py` + `ml/board_master_arch.py`）**：并入 `topup_v090_low_recall` 亚像素 `6×5` 接缝周期精裁盘面并搭载平移容错圆章检测器 `detect_race_medallion_cell64`，在 **`1,128` 张高净度盘面（`33,840` 格：`train = 864`、`val = 264`，`83` 组真实闪烁双帧）** 上完成增量重训导出 `board_master_v2.onnx`（`536,719` 参数，`2,125.2 KB`，动态支持 `6×5`/`6×8` → `[1, Rows×Cols, 47]`，ORT parity `1.61e-06`，桌面 4T CPU `6.16 ms` / `9.52 ms`）；验证集 `clean_6color_element = 0.9731`、`variant_acc = 0.8986`、**8 类种族宏平均召回率跃升至 `0.6802`**（`NONE 93.6%`、`GOD 45.2%`、`DEMON 72.3%`、`HUMAN 77.1%`、`BEAST 46.0%`、`DRAGON 83.1%`、`ELF 72.6%`、`MACHINA 54.4%`），零误报激活 `ICE_SEALED (0.95)`、`BLOOD_DRAIN_LOCKED (0.64)`、`PETRIFIED (0.58)`、`BOMB (0.90)`、`BLACK_WHITE (0.99)`、`COAGULATED (0.58)`、`FOAM (0.85)`、`LUMINOUS (0.95)`、`MAGIC_ORB (0.94)`、`BLOOD_THORN (0.84)` 与 `TRAFFIC_LIGHT_MARKER (0.81)`，配合置信度门控融合 `MonochromeOrbMatcher` 在盲测黑白拼图盘保持 **`30/30 = 100%`** 复色；
  - **Stage 2B `EnemyIconNetV2`（`ml/tools/build_enemy_icon_dataset.py` + `ml/train_enemy_icon.py` + `ml/enemy_icon_arch.py`）**：并入 `topup_v090_low_recall`、`youtube_mechanic_v1` 与 `youtube_broad_corpus_v1` 实战顶栏背景（`train = 688`、`val = 166`，每轮 `14,000` 条合成条带），修复负样本右侧敌人图标镜像泄漏并在上区注入锐利怪物躯干/悬浮 UI 胶囊硬负样本（保留下部怪物立绘与头像栏 100% 原生锐度），导出双输出 `enemy_icon_v1.onnx`（`449,388` 参数，`1,780.4 KB`，输出 `icon_probs [B, 36]` + `icon_peaks [B, 36, 2]`，ORT parity `1.91e-06`，桌面 4T CPU `3.53 ms`，验证集宏 F1 跃升至 **`0.6175`**，`neg_fp = 0/450`，零误报激活 **`25/36`** 类），配合端侧 `EnemyIconStripPreprocessor` 盒式面积抗混叠下采样与悬浮栏 `excludeRect` 自遮罩，在 10 段真机盲测视频实现 **0 幻觉误报** 并直读 `≥5 COM`、`指定連擊法印` 与 `NUM 17`；
  - 完整 Python 自动化单测 **`9/9` 通过**（`test_ml_pipeline.py` 4 项含 `6×5`/`6×8` ONNX 检验 + `test_enemy_skill_cleaner.py` 4 项 + `test_stage_wiki_compiler.py` 1 项）。
- **严禁提交第三方版权素材或大体积训练中间产物**：`backups/`、`ml/datasets/`、`ml/runs/`、`ml/checkpoints/` 与 `ml/.venv/` 均已配置 `.gitignore` 忽略，Git 仓库仅保留纯训练/导出脚本与轻量化自主/公开端侧推理资产（`assets/models/` 与 `assets/ocr/`）。
