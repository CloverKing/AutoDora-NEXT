# AutoDora-NEXT

面向《神魔之塔》（Tower of Saviors）现代版本与现代 Android 设备（Android 10 ~ Android 15+）的**免 Root、全规格棋盘自适应、全自动识盘识盾、可组合解盾、端侧 AI 驱动**的新一代智能转珠辅助工程。

> 详细架构设计与需求规格请参阅 [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) 与 [docs/LEGACY_AUTODORA_ANALYSIS.md](./docs/LEGACY_AUTODORA_ANALYSIS.md)。

## 仓库结构

- `solver-core/`：纯 C++20 高性能解盘引擎（CMake，零 Android 依赖，支持任意 `3×3`~`8×8` 动态棋盘规格含 `6×5` 与 `6×8` 违建、64-bit 位掩码、激光诱导启发式与全套现代神魔解盾约束）
- `android/`：Android 原生多模块工程（Kotlin 2.1 + Jetpack Compose Material 3 + Hilt + DataStore + NDK/JNI + MediaProjection + AccessibilityService + LiteRT + ML Kit 离线中文 OCR）
  - `:core-model`：跨层不可变数据模型与两层规则组合器（`TeamBasePreset` + `WaveShieldChip`）
  - `:solver-jni`：C++20 解盘核心 NDK/JNI 桥接（支持 `arm64-v8a` / `armeabi-v7a` / `x86_64`）
  - `:core-capture`：Android 14/15 合规 `MediaProjection` 前台屏幕采集 + 两阶段动态棋盘规格探测（`6×5` ↔ `6×8` 违建秒切 & 任意网格探测）
  - `:core-vision`：多帧时间融合 CV 识盘（6 属性 + 强化星芒 + 拼图金边 + 疑珠告警）与可插拔 AI 推理槽位
  - `:core-touch`：免 Root `AccessibilityService` 连续手势分段执行、45° 斜转优化、角色珠垂直下拉与急停强制抬手
  - `:core-data`：配置持久化与预设存储层
  - `:app`：主控台、本机 C++20 压测页、1:1 透传路径画布与三态悬浮窗控制器
- `ml/`：端侧多头轻量 CNN（符石属性/强化/黑白/障碍/拼图/红绿灯）数据工具、训练与 LiteRT (`.tflite`) 导出管线
- `docs/`：架构设计文档与旧版继承分析报告

## 开发进度

- [x] **Phase 1**：纯 C++20 动态网格解盘内核（16/16 单元测试通过）+ Android 多模块骨架 + JNI 跨平台编译
- [x] **Phase 2**：免 Root `MediaProjection` 录屏 + `6×5`/`6×8` 违建自动识别 + 多帧 CV 识图 + 无障碍平滑/斜转/角色珠自动执行 + 悬浮窗三态控制/校准/急停闭环
- [ ] **Phase 3**：游戏内悬浮调参抽屉、两层解盾快选 + 离线中文 OCR 敌技识盾与自动选策略、动态互动改珠/标盾网格、Room 队伍预设库
- [ ] **Phase 4**：端侧多头轻量 CNN 训练与热更新 `.tflite` 推理闭环、纠错样本采集器、可选 Shizuku 触控引擎

## 快速构建与测试

### 1. C++20 解盘引擎单元测试（Windows PC）

```powershell
powershell -ExecutionPolicy Bypass -File solver-core/run-tests.ps1
```

### 2. Android 单元测试与 Debug APK 构建

```powershell
powershell -ExecutionPolicy Bypass -File android/build-android.ps1 :core-model:test :core-capture:testDebugUnitTest :core-vision:testDebugUnitTest :core-touch:testDebugUnitTest :app:assembleDebug
```
