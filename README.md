# 心情日记（Flutter 版）

按小时记录心情的轻量日记应用。**v5.0.0 起为纯 Flutter 实现**——单一代码库、
单引擎渲染，液态玻璃底部导航栏在 Android 7+ 全版本原生可用（不再有
Android 13+ 的系统材质门禁，也不再需要原生/Flutter 混编桥接）。

## 功能

- **按小时记录**：一天 24 格，每格一条心情 + 备注；时段冲突显式确认，绝不静默覆盖
- **日历月视图**：心情色块、表情、备注摘要开关、周起始日切换、今天高亮
- **统计**：主色横幅（主要情绪/均分）、按日均分趋势折线（渐变填充）、
  情绪分布条（最大余数法凑整 100%）、本月心情热力条
- **液态玻璃导航栏**：`liquid_glass_widgets` premium 渲染管线 1:1 移植，
  长按 250ms 激活拖动、橡皮筋阻尼、解析解弹簧回位；着色器初始化失败自动降级
- **FAB Q 弹按压**：按下膨胀 ×1.30、LowBouncy 弹簧回弹
- **每小时提醒**：flutter_local_notifications 精确闹钟调度，免打扰时段排除，
  通知 actions 快速记录（直接写库不打开 app），点击通知打开当前小时弹窗
- **设置**：主题模式/强调色（11 色）/动态取色/纯黑/字号/圆角、启动默认页、
  日历点击行为、心情标签自定义（名称/表情/颜色/分值）、数据清空
- **数据继承**：数据库沿用 Kotlin 版 Room 的 `mood_diary.db` / `mood_entries`
  表结构与唯一索引；包名保持 `com.mooddiary.app.debug`，从混编版覆盖安装后
  **历史记录无缝继承**；启动时自动把失配的心情 id 永久改写为当前目录就近项

## 与 Kotlin 版（v4.x）的差异

- 通知内嵌表情按钮（RemoteViews）改为 Android 通知 actions（上限 3 个）
- 设置项与心情目录的自定义数据不迁移（SharedPreferences 文件格式不同），需重新设置一次
- 历史记录数据库完全继承

## 构建

```bash
# 国内环境建议先配置镜像
export PUB_HOSTED_URL=https://pub.flutter-io.cn
export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn

flutter pub get
flutter build apk --release
```

产物：`build/app/outputs/flutter-apk/app-release.apk`（debug 签名，可直接安装）。

## 技术栈

Flutter 3.x · provider · sqflite · shared_preferences ·
flutter_local_notifications · dynamic_color · liquid_glass_widgets

上游玻璃渲染 MIT License：Copyright 2025 Tim Lehmann / 2026 Sebastian Degenaar
