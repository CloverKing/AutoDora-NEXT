# AutoDora-NEXT

面向《神魔之塔》（Tower of Saviors）13 年全机制演进与 2026 现代版本、现代 Android 设备（Android 10 ~ Android 17）的**免 Root、全规格棋盘自适应、全自动识盘识盾、问号/按珠变盘双模破局、可组合解盾、端侧 AI 驱动**的新一代智能转珠辅助工程。

## 核心文档

- [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)：v2.3 完整系统架构、双模破局设计（记忆快照 + 按珠显形即时重算）与 4 阶段路线图
- [docs/TOS_COMPLETE_MECHANICS_CATALOG.md](./docs/TOS_COMPLETE_MECHANICS_CATALOG.md)：《神魔之塔》13 年（2013–2026）全机制、敌技、特殊符石、地形与盘面规则全景调研手册
- [docs/LEGACY_AUTODORA_ANALYSIS.md](./docs/LEGACY_AUTODORA_ANALYSIS.md)：旧版 `AutoDora-V3.4.3.apk` 深度拆解与继承升级清单

> **标定与测试原则**：所有棋盘几何探测、校准基准与识图调优一律严格以**真实《神魔之塔》游戏截图**为依据（在带 `arm64-v8a` 官方转译的 PC 模拟器中安装官方独立 APK 实测），不使用合成 Mock 图做标定。

## 仓库结构

- `solver-core/`：纯 C++20 高性能解盘引擎（CMake，零 Android 依赖，支持任意 `3×3`~`8×8` 动态棋盘规格含 `6×5` 与 `6×8` 违建、64-bit 位掩码与全套现代神魔解盾约束）
- `android/`：Android 原生多模块工程（Kotlin 2.1 + Jetpack Compose Material 3 + Hilt + DataStore + NDK/JNI + MediaProjection + AccessibilityService + LiteRT + ML Kit 离线中文 OCR）
  - `:core-model`：跨层不可变数据模型与两层规则组合器（`TeamBasePreset` + `WaveShieldChip`）
  - `:solver-jni`：C++20 解盘核心 NDK/JNI 桥接（`arm64-v8a` / `armeabi-v7a` / `x86_64`，开启 `-O3` 优化）
  - `:core-capture`：Android 14/15/16+ 合规 `MediaProjection` 零拷贝前台屏幕采集 + 绿血条锚定与动态棋盘规格探测
  - `:core-vision`：多帧时间融合 CV 识盘（6 属性 + 强化星芒 + 问号/种族角标处理 + 拼图金边）、问号记忆快照与可插拔 AI 推理槽位
  - `:core-touch`：免 Root `AccessibilityService` 连续手势分段执行、45° 斜转优化、角色珠垂直下拉、按珠显形心跳续接与急停强制抬手
  - `:core-data`：配置持久化与预设存储层
  - `:app`：紧凑高密度主控台、本机 C++20 压测页、1:1 透传路径画布与三态悬浮窗控制器
- `ml/`：端侧多头轻量 CNN（符石属性/强化/黑白/障碍/拼图/红绿灯）数据工具、训练与 LiteRT (`.tflite`) 导出管线
- `docs/`：架构规范、13 年神魔全机制全景手册与旧版继承分析报告

## 开发进度

- [x] **Phase 1**：纯 C++20 动态网格解盘内核（16/16 单元测试通过）+ Android 多模块骨架 + JNI 跨平台编译
- [x] **Phase 2（基础闭环）**：免 Root 零拷贝 `MediaProjection` + 动态网格探测 + 多帧 CV + 无障碍连续斜转 + 悬浮窗三态控制/校准/急停闭环（真机骁龙 8 Elite 算路 ~83ms）
- [ ] **Phase 2.5（真实游戏标定 + 全机制补强，进行中）**：基于模拟器真实《神魔之塔》截图重构校准与绿血条隔离防假违建、问号记忆快照（先露后藏）、免 Root 按珠显形即时重算（问号‧转/风化‧转/烟雾）、C++20 补齐 13 年全谱系遗漏盾牌与机制
- [ ] **Phase 3**：游戏内 4-Tab 悬浮调参抽屉、离线中文 OCR 敌技识盾（含 2026 灰字自动跳过）与自动选策、动态互动改珠/标盾网格、Room 队伍预设库
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
