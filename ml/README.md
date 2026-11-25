# AutoDora-NEXT 端侧多头轻量 CNN 训练管线（`ml/` · Phase 4 规划）

本目录用于 Phase 4 端侧符石与盘面状态识别模型的素材抽帧切珠、训练、合成数据增强与 LiteRT (`.tflite`) 量化导出，直接对接 `android/core-vision` 中的 `HybridBoardRecognizer` 接口。

## 工具链：半自动抽帧切珠脚本（`ml/tools/extract_tos_dataset.py`）

基于 `yt-dlp` + `ffmpeg`（自动发现系统 `ffmpeg` 或 `imageio-ffmpeg`）+ 自动 `6×5` 战斗盘面定位，支持：
- **竖屏手机录屏 & 16:9 横屏 YouTube 攻略视频自动去侧栏定位**（基于血条与棋盘暗角精确裁切 `6×5` 盘面）；
- **自动过滤非战斗画面与重复回合帧**（支持 `--fps` 与 `--dedup` 阈值）；
- **一键导出 `64×64` 单格符石切片 + 可视化缩略图拼图（`contact_sheet_boards.jpg` / `contact_sheet_full.jpg`）+ `preview_grid_check.png` 对齐校验图**。

### 依赖安装
```powershell
pip install yt-dlp imageio-ffmpeg pillow numpy
```

### 常用命令
```powershell
# 1. 从单个公开 YouTube 视频抽帧切珠（支持 --start / --end 截取指定波次）
python ml/tools/extract_tos_dataset.py --url "https://www.youtube.com/watch?v=XXXX" --tag jail_r3 --fps 0.5

# 2. 按时间段批量处理多个链接（每行格式：URL | 标签 | 01:20-03:00）
python ml/tools/extract_tos_dataset.py --urls-file backups/video_queue.txt --fps 0.5

# 3. 从本地录屏 MP4 或截图目录直接裁切 6x5 盘面与 64x64 符石切片
python ml/tools/extract_tos_dataset.py --input path/to/recording.mp4 --tag local_clip
python ml/tools/extract_tos_dataset.py --images-dir backups/raw_screenshots --tag wiki_batch

# 4. 仅检索公开 YouTube 候选视频（不下载）
python ml/tools/extract_tos_dataset.py --yt-search "神魔之塔 絕亡刑獄 災厄級" --max-results 3 --dry-run
```

> 默认输出至被 `.gitignore` 忽略的 `backups/user_training_media/yt_extracted/<tag>/`，严禁提交第三方素材入库。

## 模型多头设计（单次 Batch = `Cols × Rows` 整盘推理）

- **输入**：`[N, 64, 64, 3]` RGB 归一化张量（支持多帧极值融合输入，兼容 `6×5` 标准盘与 `6×8` 违建盘）
- **输出头（对齐全量 98 敌技 + 22 符石状态（官方百科 20 项 + 百科外锁定/荆棘 2 项）+ 7 大种族规范）**：
  1. `head_is_rune` (1, Sigmoid)：是否有效符石格（辅助 `6×5` ↔ `6×8` 违建与任意规格探测）
  2. `head_element` (8, Softmax)：水 / 火 / 木 / 光 / 暗 / 心 / 隐藏 / 问号 `?`（配合灰度增强攻克黑白符石）
  3. `head_variant` (3, Softmax)：普通 / 强化 `+` / 弱化 `-`
  4. `head_race` (8, Softmax)：无种族 / 神族 / 魔族 / 人类 / 兽类 / 龙类 / 妖精 / 机械（识别右上角官方种族徽章）
  5. `head_hazards` (17, Sigmoid 多标签)：风化 / 电击 / 冻结 / 冰封 / 石化 / 凝结 / 化血 / 炸弹 / 泡沫 / 邪恶能量 / 刺血 / 魔力 / 流光 / 炼狱之火 / 荆棘 / 五类敌技印记 / 锁定
  6. `head_tile_overlay` (8, Sigmoid 多标签)：拼图框 1 / 拼图框 2 / 绿色漩涡 / 红色漩涡 / 燃烧 / 灼热 / 黑洞·血浴 / 暴风·碎裂

## 数据飞轮与合规规范

- 原始真实游戏截图、YouTube 抽帧与百科采集切片仅存放于 `.gitignore` 忽略的本地 `backups/` 目录，**严禁提交任何第三方游戏素材至 Git 仓库**。
- 后续接入悬浮窗「纠错样本导出」后，ZIP 数据集解压至本地 `ml/datasets/`，配合合成数据增强（灰度黑白模拟、结界暗化滤镜、亮度与边框微移）训练并导出 INT8/FP16 `.tflite` 至 `android/app/src/main/assets/models/`，目标整盘推理耗时 `< 20ms`。
