# 心情日记（Mood Diary）

一个按小时记录心情的 Android 应用：日历热力图 + 24 小时心情记录 + 情绪统计 + 每小时提醒。

## ✨ 功能

- **日历热力图**：月历网格按当天心情颜色填充，今天高亮，可前后切换月份
- **按小时记录**：点任意一天，弹出该天的 24 小时心情板（00:00-23:00），每小时独立记录心情 + 备注
- **5 种情绪**：😄 开心 / 😌 平静 / 😐 一般 / 😔 低落 / 😡 生气（含 1-5 分值）
- **每小时提醒**：顶栏铃铛开关，整点对齐提醒；当前小时已记录则不打扰；点通知直达记录弹窗
- **设置页**：主题模式、免打扰时段、默认心情、每周起始日、清空数据
- **记录列表**：按日期 + 小时倒序展示，点击可编辑、删除
- **统计页**：当月记录天数、平均心情分、情绪分布百分比、逐日热力点条
- **本地持久化**：Room 数据库，重启不丢数据
- 中文界面，支持系统深色/浅色模式

## 📦 下载安装

在 [Releases](../../releases) 页面下载最新 APK，传到手机安装即可（需允许“安装未知来源应用”）。

> **关于升级**：v1.4.0 因原签名密钥曾在仓库中公开而**更换了签名**。
> 已安装 v1.3.0 及更早版本的设备无法原地覆盖升级，需要先卸载旧版再安装（本地记录会一并清除）。
> v1.4.0 及之后的版本之间可以正常覆盖升级。

## 🛠 技术栈

- Kotlin 1.9.24 · Jetpack Compose（BOM 2024.06.00）· Material 3
- Room 2.6.1（数据持久化）· WorkManager 2.9.1（每小时提醒）· AGP 8.4.2 · Gradle 8.7
- minSdk 24 / targetSdk 34，core library desugaring 支持 java.time

## 🔨 本地构建

需要 JDK 17 与 Android SDK（platform 34、build-tools 34.0.0）。仓库自带 Gradle Wrapper：

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease        # 产物：app/build/outputs/apk/release/app-release.apk
./gradlew test                   # 运行数据层回归测试
```

### 签名配置

签名凭据**不在版本库中**。要构建已签名的 release 包，在仓库根目录创建
`keystore.properties`（该文件已被 `.gitignore` 忽略）：

```properties
MOODDIARY_STORE_FILE=/绝对路径/mood-diary-release.keystore
MOODDIARY_STORE_PASSWORD=你的密码
MOODDIARY_KEY_ALIAS=mooddiary
MOODDIARY_KEY_PASSWORD=你的密码
```

也支持同名环境变量。**未配置时 release 仍可构建**，只是产物未签名，方便直接体验。

> ⚠️ 请务必把 keystore 和密码保存在仓库外，并做好离线备份。一旦丢失，将无法再发布可覆盖安装的更新。
> 历史上仓库曾提交过 `mood-diary.keystore` 及明文密码，该密钥已作废。

## 📂 结构

```
app/src/main/java/com/mooddiary/app/
    MainActivity.kt      # 数据层（实体/DAO/数据库/仓库）+ 全部 Compose UI
    ReminderWorker.kt    # 每小时提醒：开关、通知渠道、WorkManager 调度
app/src/main/res/
    drawable/ic_launcher_background.xml   # 自适应图标背景层（纯白矢量，铺满画布）
    mipmap-{m,h,xh,xxh,xxx}dpi/           # 各密度的前景图与传统方形/圆形图标
    mipmap-anydpi-v26/                    # 自适应图标定义（API 26+）
app/src/test/            # 数据层回归测试（纯 JVM，用内存 FakeMoodStore）
app/schemas/             # Room schema 导出，用于审查与迁移测试
```

## 🎨 应用图标

图标为「日记本 + 铅笔 + 爱心」线稿（深蓝 `#193879`），由用户提供的
JPEG 生成：白底已转透明，边缘抗锯齿保留为半透明，任何底色下都不露白边。

- **自适应图标**（Android 8.0+）：图案占可见区（中心 72dp）约 76%，
  处于系统安全区内，不会被圆形/方形遮罩裁到
- **传统图标**：同时提供方形与圆形，兼容 Android 7.x（API 24/25）
- **白色背景层**：图案是深蓝线条，白底保证在深色与浅色壁纸下都清晰；
  背景矢量铺满整个 108dp 画布（只画中心会让可见区四角露出壁纸）

重新生成图标见 `tools/make-icons.md`。

## 🗄 数据库

`mood_entries` 表以 `(date, hour)` 为唯一键，一天最多 24 条记录。

- **改日期撞车不会丢数据**：编辑记录时若目标时段已被占用，应用会提示并让你选择是否覆盖
- **升级不丢数据**：数据库 v2 起不再使用破坏性迁移；任何版本升级都必须显式提供 Migration
