# 心情日记（Mood Diary）

一个按小时记录心情的 Android 应用：日历热力图 + 24 小时心情记录 + 情绪统计 + 每小时提醒。

## ✨ 功能

- **按小时记录**：日历热力图按天填充心情颜色；点任意一天，弹出 24 小时心情板（00:00-23:00），每小时独立记录心情 + 备注
- **自定义心情标签**：名称、表情、颜色与分值（1-5）均可调整；历史记录引用的心情被删除时自动回退到相近心情，不会丢数据
- **统计**：当月概览、情绪趋势折线图、情绪分布
- **每小时提醒**：整点对齐提醒（当前小时已记录则不打扰），通知里点表情即可快速记录；支持免打扰时段
- **外观**：11 款主题色（或跟随壁纸取色）、浅色/深色/纯黑背景、界面字号与卡片圆角可调

## 📦 下载安装

在 [Releases](../../releases) 页面下载最新 APK，传到手机安装即可。

> v1.4.0 更换过签名：v1.3.0 及更早版本无法覆盖升级，需卸载后重装（记录会清除）。v1.4.0 之后可正常覆盖升级。

## 🛠 技术栈

Kotlin 1.9.24 · Jetpack Compose · Material 3 · Room · AlarmManager 精确闹钟 · minSdk 24 / targetSdk 34

## 🔨 本地构建

需要 JDK 17 与 Android SDK（platform 34、build-tools 34.0.0）：

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease        # 产物：app/build/outputs/apk/release/app-release.apk
./gradlew test                   # 数据层回归测试
```

签名凭据不在版本库中。要构建已签名的 release 包，在仓库根目录创建 `keystore.properties`（已被 .gitignore 忽略）：

```properties
MOODDIARY_STORE_FILE=/绝对路径/mood-diary-release.keystore
MOODDIARY_STORE_PASSWORD=你的密码
MOODDIARY_KEY_ALIAS=mooddiary
MOODDIARY_KEY_PASSWORD=你的密码
```

也支持同名环境变量。未配置时 release 仍可构建，只是产物未签名。

## 📂 结构

```
app/src/main/java/com/mooddiary/app/
    MainActivity.kt      # 数据层（Room 实体/DAO/仓库）+ 主体 Compose UI
    Settings.kt          # 设置项读写与心情标签持久化
    SettingsPage.kt      # 设置页 UI（含心情标签管理）
    Reminder.kt          # 每小时提醒：通知渠道、精确闹钟调度
    ReminderReceiver.kt  # 提醒广播：整点、开机、通知内快速记录
app/src/test/            # 数据层回归测试（纯 JVM）
app/schemas/             # Room schema 导出
```
