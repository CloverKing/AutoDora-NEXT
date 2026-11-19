# AutoDora-NEXT 端侧多头轻量 CNN 训练管线 (`ml/`)

本目录负责端侧符石与盘面状态识别模型的训练、合成数据增强与 LiteRT (`.tflite`) 量化导出：

## 模型多头设计（单次 Batch = `Cols × Rows` 整盘推理）
- **输入**：`[N, 64, 64, 3]` RGB 归一化张量（支持多帧极值融合输入）
- **输出头**：
  1. `head_is_rune` (1, Sigmoid)：是否有效符石格（用于 `6×5` ↔ `6×8` 违建自动规格快检与通用规格识别）
  2. `head_element` (8, Softmax)：水 / 火 / 木 / 光 / 暗 / 心 / 隐藏 / 未知
  3. `head_variant` (4, Softmax)：普通 / 强化 / 种族 / 弱化
  4. `head_hazards` (12, Sigmoid 多标签)：风化 / 电击 / 燃烧 / 黏腐 / 冻结 / 冰封 / 锁定 / 石化 / 化血 / 炸弹 / 黑白 / 问号
  5. `head_tile_overlay` (4, Sigmoid 多标签)：拼图框 1 / 拼图框 2 / 红绿灯起手 / 红绿灯停手

## 数据飞轮
App 悬浮窗「采样保存 / 纠错保存」导出的 ZIP 数据集解压至 `ml/datasets/` 后，配合合成数据增强（灰度黑白模拟、色偏、亮度与边框微移）训练并一键导出 INT8/FP16 `.tflite` 至 `android/app/src/main/assets/models/`。
