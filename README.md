# AutoDora-NEXT

面向《神魔之塔》（Tower of Saviors）13 年全机制演进与 2026 现代版本、现代 Android 设备（Android 10 ~ Android 17）的**免 Root、全规格棋盘自适应、全自动识盘识盾、问号/按珠变盘双模破局、可组合解盾、端侧 AI 驱动**的新一代智能转珠辅助工程。

## 核心文档

- [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)：v2.3 完整系统架构、双模破局设计（记忆快照 + 按珠显形即时重算）与 4 阶段路线图
- [docs/TOS_COMPLETE_MECHANICS_CATALOG.md](./docs/TOS_COMPLETE_MECHANICS_CATALOG.md)：《神魔之塔》13 年（2013–2026）全机制、敌技、特殊符石、地形与盘面规则全景调研手册
- [docs/LEGACY_AUTODORA_ANALYSIS.md](./docs/LEGACY_AUTODORA_ANALYSIS.md)：旧版 `AutoDora-V3.4.3.apk` 深度拆解与继承升级清单

> **标定与测试原则**：所有棋盘几何探测、校准基准与识图调优一律严格以**真实《神魔之塔》游戏截图**为依据（在带 `arm64-v8a` 官方转译的 PC 模拟器中安装官方独立 APK 实测），不使用合成 Mock 图做标定。

## 仓库结构

- `solver-core/`：纯 C++20 高性能解盘引擎（CMake，零 Android 依赖，支持任意 `3×3`~`8×8` 动态棋盘规格含 `6×5` 与 `6×8` 违建、最高 **300 步**、64-bit 位掩码与全套现代神魔解盾约束）
- `android/`：Android 原生多模块工程（Kotlin 2.1 + Jetpack Compose Material 3 / Material You 系统配色 + Hilt + Room + DataStore + NDK/JNI + MediaProjection + AccessibilityService + LiteRT + ML Kit 离线中文 OCR）
  - `:core-model`：跨层不可变数据模型与两层规则组合器（`TeamBasePreset` + `WaveShieldChip`）
  - `:solver-jni`：C++20 解盘核心 NDK/JNI 桥接（`arm64-v8a` / `armeabi-v7a` / `x86_64`，开启 `-O3` 优化）
  - `:core-capture`：Android 14/15/16+ 合规 `MediaProjection` 零拷贝前台屏幕采集 + 三色/残血血条锚定与动态棋盘规格探测
  - `:core-vision`：多帧时间融合 CV 识盘（6 属性 + 强化星芒 + 白边护盾珠 + 问号/种族角标 + 拼图金边 + 暴风/龙卷风区域自动识别）、先露后藏问号记忆快照、以及 **ML Kit 离线中文 OCR 敌技识盾引擎**（覆盖「齿轮 ⚙ → 關卡資訊」与「长按敌技弹窗」双场景 `8%~87%` ROI + 截屏自动隐藏悬浮栏 + 热更新 `tos_enemy_skill_rules.json` + 2026 灰字已无视敌技自动过滤）
  - `:core-touch`：免 Root `AccessibilityService` 连续手势分段执行、45° 斜转优化、角色珠垂直下拉、按珠显形心跳续接、四档统一速度预设（`极速 2s` / `快转 3s` / `标准 5s` / `稳健 7s`）、方向逆转与急停强制抬手
  - `:core-data`：DataStore 设置持久化（含明亮/黑暗/随系统三主题、8~300 步上限、按住转开关）+ Room 队伍基底预设库（`AutoDoraDatabase` / `TeamPresetRepository`）+ `WaveShieldCatalog` / `ActiveStrategyStore`
  - `:app`：Material 3 4-Tab 启动台（概览 / 队伍 / 解盾 / 设置）、系统配色三态悬浮胶囊（`算` / `◀▶` / 统一 `行` / 内嵌 `停` / 仅路径 `线` / 仅网格 `校`）、游戏内 4-Tab 悬浮调参抽屉与 1:1 盘面编绘画笔
- `ml/`：端侧多头轻量 CNN（符石属性/强化/黑白/障碍/拼图/红绿灯）数据工具、训练与 LiteRT (`.tflite`) 导出管线
- `docs/`：架构规范、13 年神魔全机制全景手册与旧版继承分析报告

## 开发进度

- [x] **Phase 1**：纯 C++20 动态网格解盘内核（支持 `3×3~8×8` 与最高 300 步，26/26 单元测试通过）+ Android 多模块骨架 + JNI 跨平台编译
- [x] **Phase 2 & 2.5（真机/模拟器多图实测闭环）**：免 Root 零拷贝 `MediaProjection` + 6 组多渠道真实截图标定（170/170 实战珠 100% 精准识别、三色/残血血条防假违建、误差 ≤ 1px）+ 先露后藏记忆快照 + 免 Root 按住重算续滑 + 13 年全谱系盾牌与触控方向逆转
- [x] **Phase 3（交互重构 + 离线 OCR 识盾 + Room 预设库 + 盘面编绘）**：
  - 启动页重构为 Material 3 4-Tab（概览 / 队伍 / 解盾 / 设置），与悬浮栏同步应用系统 Material You 配色（明亮 / 黑暗 / 随系统）
  - 「按住转」统一为开关由「行」执行；「线」仅控转珠路径、「校」仅控校准网格；速度四档（`极速 2s` / `快转 3s` / `标准 5s` / `稳健 7s`）双端 1:1 同步与语义色阶
  - Room 队伍基底预设库（内置 5 套神魔常见队型 + 自由增删改复制）
  - Google ML Kit 离线中文 OCR 敌技识盾 + 可热更新 `tos_enemy_skill_rules.json` + 2026 灰字已无视自动过滤 + 启动页词条解析测试台
  - 游戏内 4-Tab 悬浮调参抽屉（盾牌 / 属性 / 专属 / 队伍）+ 1:1 实时盘面编绘画笔（支持手指连涂改珠/标盾并直接重算）
- [ ] **Phase 4**：真实截图数据集采集 + 端侧多头轻量 CNN 训练与 `.tflite` 热更新推理闭环、可选 Shizuku 触控引擎

## 快速构建与测试

### 1. C++20 解盘引擎单元测试（Windows PC）

```powershell
powershell -ExecutionPolicy Bypass -File solver-core/run-tests.ps1
```

### 2. Android 单元测试与 Debug APK 构建

```powershell
powershell -ExecutionPolicy Bypass -File android/build-android.ps1 :core-model:test :core-capture:testDebugUnitTest :core-vision:testDebugUnitTest :core-touch:testDebugUnitTest :app:assembleDebug
```
