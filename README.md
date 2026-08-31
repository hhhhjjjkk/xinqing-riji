# 心情日记（Mood Diary）

一个按小时记录心情的 Android 应用：日历热力图 + 24 小时心情记录 + 情绪统计。

## ✨ 功能

- **日历热力图**：月历网格按当天心情颜色填充，今天高亮，可前后切换月份
- **按小时记录**：点任意一天，弹出该天的 24 小时心情板（00:00-23:00），每小时独立记录心情 + 备注
- **5 种情绪**：😄 开心 / 😌 平静 / 😐 一般 / 😔 低落 / 😡 生气（含 1-5 分值）
- **记录列表**：按日期 + 小时倒序展示，点击可编辑、删除
- **统计页**：当月记录天数、平均心情分、情绪分布百分比、逐日热力点条
- **本地持久化**：Room 数据库，重启不丢数据
- 中文界面，支持系统深色/浅色模式

## 📦 下载安装

在 [Releases](../../releases) 页面下载最新 APK（`心情日记-v1.2.2.apk`），传到手机安装即可（需允许"安装未知来源应用"）。
本目录 `dist/` 下也有同版本 APK。

## 🛠 技术栈

- Kotlin 1.9.24 · Jetpack Compose（BOM 2024.06.00）· Material 3
- Room 2.6.1（数据持久化）· AGP 8.4.2 · Gradle 8.7
- minSdk 24 / targetSdk 34，core library desugaring 支持 java.time

## 🔨 本地构建

```bash
# 需要 JDK 17 和 Android SDK（platform 34, build-tools 34.0.0）
export ANDROID_HOME=/path/to/android-sdk
gradle assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

> 项目自带 `mood-diary.keystore`（密码 `mooddiary123`，仅本应用演示签名用），release 构建会自动签名。

## 📂 结构

```
app/src/main/java/com/mooddiary/app/MainActivity.kt   # 全部逻辑（单文件）
app/src/main/res/                                     # 资源
dist/                                                 # 已构建的 APK
```
