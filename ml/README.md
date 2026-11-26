# AutoDora-NEXT 端侧多头轻量 CNN 训练管线（`ml/` · `v0.5.0` Phase 4 首版已落地）

本目录实现了 Phase 4 端侧符石与盘面状态识别模型 **`AutoDoraOrbNetV1`** 的半自动抽帧切珠、数据集构建、PyTorch GPU 训练、近零误报阈值校准与 BN 折叠直出 LiteRT (`.tflite`) 管线，已正式接入 `android/core-vision` (`LiteRtOrbClassifier` + `HybridFusionPolicy` + `HybridBoardRecognizer`) 与 `android/app` Settings 开关。

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
- **PC 端 Stage 2A / 2B 训练、回流加权与 ONNX 导出管线（`v0.7.0`）**：
  - `ml/tools/ingest_device_export.py`：一键解包真机训练 ZIP，按 `BOARD_EDITOR_CORRECTION = 4.0×` / `P1 = 2.0×` / `P2 = 1.0×` 分配采样权重，并与 `tos_enemy_skill_rules_v2.json` 比对输出未覆盖新技能清单 `unparsed_skills_report.json`；
  - `ml/tools/extract_youtube_frames.py`：提取间距约 `0.35s` 的静止双帧 WebP 配对（保留电击/燃烧/漩涡闪烁态）并执行 64-bit dHash 去重；
  - `ml/board_master_arch.py` (`BoardMasterGridNet`，双帧 6 通道 + 2 层网格 Transformer 自注意力，动态支持 `6×5`/`6×8` → `[B, R×C, 47]`) + `ml/enemy_icon_arch.py` (`EnemyIconNet`，24 类敌人图标多标签预测) + `ml/train_board_master.py` + `ml/export_onnx.py`（opset 17 导出并强制 `onnxruntime` 等价性检验 `< 1e-4`）；完整合成回归由 `ml/tests/test_ml_pipeline.py`（`4/4` 通过）保障。
- **严禁提交第三方版权素材或大体积训练中间产物**：`backups/`、`ml/datasets/`、`ml/runs/`、`ml/checkpoints/` 与 `ml/.venv/` 均已配置 `.gitignore` 忽略，Git 仓库仅保留纯训练/导出脚本与轻量化自主/公开端侧推理资产（`assets/models/` 与 `assets/ocr/`）。
