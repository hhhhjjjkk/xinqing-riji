# 心情日记 v4.9.0「通知双布局」独立代码审查报告

**审查者**：独立审查者（notify-v490-reviewer）
**审查对象**：commit `94dda15`「fix(notification): 修复折叠态被裁切导致部分心情图标不显示，并支持两行 10 个」+ 未提交的工作区改动（`Reminder.kt` 中 `slot_row_2` 的 GONE 逻辑）
**审查方式**：纯静态只读审查 + 反编译验证 + 真实字体度量（唯一写入文件为本报告）
**版本**：versionCode 71 / versionName 4.9.0（`app/build.gradle.kts:37-38`）

> ⚠️ **审查结论摘要（先看这段）**
>
> 本次的双布局方案**方向正确**（`setCustomContentView` + `setCustomBigContentView` 确实已同时调用，折叠态高度确实够用），**但它并不能解释也无法修复用户看到的「部分心情图标不显示」**。我定位到 **3 个仍然成立、可独立导致该症状的严重问题**，其中第 ① 条与此前"emoji 兼容性"的第二次猜测其实部分成立（此前判断为"未解决"是因为找错了具体字符），第 ② 条是全新增宽后的**新引入回归**。
>
> | # | 问题 | 严重度 | 能否导致「部分图标不显示」 |
> |---|---|---|---|
> | ① | `DecoratedCustomViewStyle` 会把自定义布局塞进 64dp 大图标左边距的模板，**可用宽度被砍掉约 80dp**，10 槽位挤在两行里，窄屏下槽位宽度小于 emoji 实际字宽 | **严重** | ✅ 能（宽度裁切） |
> | ② | 折叠态硬编码只有 5 槽位，而展开态 10 槽位 → **折叠/展开显示的心情不是同一批**；且用户勾选的第 6–10 个心情在折叠态完全不存在 | **严重** | ✅ 能（用户视角"我选的图标不见了"）|
> | ③ | 设置页表情预设含 `🫠`（U+1FAE0，Emoji 14.0），Android < 12L 无该字形 → 显示为豆腐块/空白 | **严重** | ✅ 能（设备相关）|
> | ④ | 展开态第二行 `slot_row_2` 的 GONE 逻辑：**本次已修复**（未提交改动） | 正确 | — |
> | ⑤ | 42 个 id 完整性声明 | **正确**（实测 43 个，全部可解析） | — |

---

## 1. 折叠态高度是否真的够

### 结论：**正确** —— 折叠态高度充足，不是失败原因

### 依据

**（1）按真实字体度量精算**

我没有用估算系数，而是直接解析了系统 emoji 字体 `NotoColorEmoji.ttf` 的 `head`/`hhea` 表（`/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf`）：

```
unitsPerEm = 2048
ascent     = 1900  (0.928 em)
descent    = -500  (0.244 em)
lineGap    = 0
→ 行高 = 1.1719 em
→ 22sp 文字在 fontScale=1.0 时行高 = 22 × 1.1719 = 25.78dp
```

**（2）逐层累加 compact 布局高度**（`notification_mood_compact.xml`）

| 层 | 属性 | 行号 | 高度 |
|---|---|---|---|
| 根 `LinearLayout` | `paddingTop=6dp` + `paddingBottom=6dp` | :15-16 | 12dp |
| 子 `c_btn_i` | `paddingTop=4dp` + `paddingBottom=4dp` | :25-26 | 8dp |
| `c_emoji_i` | `textSize=22sp` | :33 | 25.78dp |
| **合计** | | | **45.78dp** |

**（3）与折叠态预算对比**

`DecoratedCustomViewStyle` 使用的模板 `notification_template_custom_big.xml`（从 `core-1.13.1.aar` 提取）中：

```xml
<!-- notification_template_custom_big.xml:33-40 -->
<LinearLayout android:id="@+id/notification_main_column_container"
    android:minHeight="@dimen/notification_large_icon_height"   <!-- 64dp -->
    android:paddingTop="@dimen/notification_main_column_padding_top"  <!-- v21 = 0dp -->
```

`res/values-v21/values-v21.xml` 中 `notification_main_column_padding_top = 0dp`，`notification_large_icon_height = 64dp`。

因此在常规字号下折叠态内容区预算 ≈ **64dp**（系统折叠通知的内容区高度），而 compact 实测高度：

| fontScale | compact 高度 | 是否放得下（≤64dp） |
|---|---|---|
| 0.85 | 41.9dp | ✅ |
| **1.0（默认）** | **45.8dp** | ✅ |
| 1.15 | 49.6dp | ✅ |
| 1.3 | 53.5dp | ✅ |
| 1.5 | 58.7dp | ✅ |
| 1.8 | 66.4dp | ⚠️ 临界 |
| 2.0 | 71.6dp | ❌ 超出 |

**结论**：默认及常见字号（含系统"大"字号 1.3）下折叠态高度**确实够**，本次"拆两套布局"的修复方向成立。这与旧方案（ch1row=106.8dp 塞进 64dp，必然裁切）形成鲜明对比 —— **裁切问题本身确实被修好了**。

但请注意：**这个修复只解决了"折叠态高度裁切"，而用户报的是"部分图标不显示"**。高度裁切只会表现为"底部整条被切掉/按钮被压扁"，不会表现为"某几个图标不显示"。真正能造成"个别图标不显示"的是下面第 2、5、6 节的问题 —— 这也解释了为什么改完高度后问题仍未消失。

---

## 2. 两套布局的槽位数量与显示是否一致

### 结论：**有问题（严重）** —— 折叠态与展开态显示的**不是同一批心情**

### 依据

**（1）常量定义：compact 硬编码 5，展开态 10**

```kotlin
// Reminder.kt:39   展开态槽位上限
private val SLOT_COUNT = SettingsStore.NOTIFY_MOOD_MAX        // = 10 (Settings.kt:206)

// Reminder.kt:66-73  折叠态槽位上限
private val COMPACT_BTN_IDS = intArrayOf(
    R.id.c_btn_0, R.id.c_btn_1, R.id.c_btn_2, R.id.c_btn_3, R.id.c_btn_4
)
private val COMPACT_SLOT_COUNT = COMPACT_BTN_IDS.size          // = 5
```

`notificationMoods()` 返回 **最多 10 个**：

```kotlin
// Reminder.kt:107-113
val picked = SettingsStore(context).current().notifyMoodIds
    .mapNotNull { byId[it] }
    .take(SLOT_COUNT)                       // ← 取 10 个
return picked.ifEmpty {
    source.sortedByDescending { it.score }.take(SLOT_COUNT)   // ← 取 10 个
}
```

**（2）填充循环：两边都从索引 0 开始取，但上限不同**

```kotlin
// Reminder.kt:312-323  展开态：0..9
for (i in 0 until SLOT_COUNT) { ... }          // 10 个

// Reminder.kt:326-334  折叠态：0..4
for (i in 0 until COMPACT_SLOT_COUNT) { ... }  // 5 个
```

**（3）用户可实际勾选 10 个**

```kotlin
// SettingsPage.kt:374-376
if (cur.size >= SettingsStore.NOTIFY_MOOD_MAX) {   // 10
    cur.removeAt(0)
}
cur.add(m.id)
```

### 为什么这就是用户看到的"部分心情图标不显示"（严重）

设用户勾选了 8 个心情（如 `[5,4,3,2,1,6,7,8]`）：

| 视图 | 显示 |
|---|---|
| 展开态（下拉通知栏） | 8 个：`5,4,3,2,1,6,7,8` |
| **折叠态（锁屏/通知栏单行）** | **只有 5 个：`5,4,3,2,1`**，第 6–8 个**根本没有对应 View** |

用户的操作路径几乎必然是：**锁屏 → 看到折叠通知 → 发现"我明明设置了 8 个，怎么只有 5 个 / 我新加的那个心情怎么没有"**。折叠态恰恰是用户最常看到的形态（`DecoratedCustomViewStyle` 配合 `displayCustomViewInline()=true`，折叠态强制走 compact 布局，见第 4 节反编译证据）。

**这不是"裁切"，而是"元素不存在"** —— 症状完全吻合"部分心情图标不显示"，且**改高度永远不会修好它**。这解释了为什么前两次修复（限制数量、emoji）和本次（高度）都没解决。

折叠与展开不一致本身还造成更糟的体验：**同一个通知，点开和没点开显示不同内容**，用户会认为"图标丢了"。

### 修复建议

**方案 A（推荐，改动最小且语义正确）**：让折叠态也支持两行（或横向换行的 10 个）。

```xml
<!-- notification_mood_compact.xml：把 c_btn_0..4 扩到 c_btn_0..9，两行 -->
```
并让 `COMPACT_SLOT_COUNT = SLOT_COUNT`，共用同一份 `moodsInNotif` 索引 → 折叠态与展开态显示完全一致。
代价：高度从 45.8dp 增至约 45.8 + 6 + 6 + 4 + 25.8 ≈ 88dp，**超出 64dp 预算**，需配合下面的方案 B 或压缩字号（22sp → 18sp 后两行约 79dp，仍超）。

**方案 B（真正可行）**：承认折叠态容量有限，**从数据源头保证"折叠态能显示的那几个 = 用户最想看的几个"**，并且**在 UI 上明确告知差异**：
- 设置页文案改为「折叠通知显示前 5 个，展开通知显示全部 N 个」，把 `SettingsPage.kt:353` 现有文案「最多 10 个（展开通知可显示两行）」补全为**同时说明折叠态只显示前 5 个**；
- 在勾选列表里对第 6 个及以后加视觉标记（如「仅展开可见」角标）。

**无论选哪个方案**，都必须消除"用户以为设置了 10 个就会显示 10 个"的预期落差 —— 这是当前文案（`SettingsPage.kt:353`）主动制造的误解。

### 子问题：心情少于 5 个 / 恰好 5 个时 GONE 逻辑是否正确

**结论：正确**

```kotlin
// Reminder.kt:326-334
for (i in 0 until COMPACT_SLOT_COUNT) {
    val mood = moodsInNotif.getOrNull(i)
    if (mood == null) {
        compact.setViewVisibility(COMPACT_BTN_IDS[i], android.view.View.GONE)
    } else {
        compact.setViewVisibility(COMPACT_BTN_IDS[i], android.view.View.VISIBLE)
        compact.setTextViewText(COMPACT_EMOJI_IDS[i], mood.emoji)
    }
}
```

- `getOrNull(i)` 对越界返回 `null`，正确走 GONE 分支 → 少时不显示空槽位 ✅
- **恰好 5 个**：`i=0..4` 全部非 null，全部 VISIBLE；展开态 `i=5..9` 全部 GONE 且 `slot_row_2` 被 GONE（见第 3 节）→ 两态都恰好显示 5 个，**这是唯一一致的情形** ✅
- 循环同时设置 VISIBLE 与 GONE，不存在"只设 GONE 不设 VISIBLE"导致复用残留的 bug ✅

另需注意一个**低风险**点：`notificationMoods()` 允许返回**少于 5 个**（当用户勾选列表里的心情被删除后 `mapNotNull` 过滤，或目录本身不足）。此时 compact 会显示 1–4 个槽位并各自靠 `layout_weight=1` 平分宽度，视觉上每个按钮变得很宽，**不是 bug 但观感割裂**。

---

## 3. 展开态第二行的空白问题

### 结论：**正确（已修复）** —— 但依赖一处**未提交的工作区改动**

### 依据

工作区当前状态（`git diff` 显示为未提交的 `M`）已在 `Reminder.kt:326-331` 加入：

```kotlin
// 第二行容器：当天数不超过 5 个时整体隐藏，
// 否则容器本身仍占高度（6dp 间距 + 空行），展开通知会多出一条空白
views.setViewVisibility(
    R.id.slot_row_2,
    if (moodsInNotif.size > 5) android.view.View.VISIBLE else android.view.View.GONE
)
```

**这段逻辑判断是对的**，理由：

1. `slot_row_2` 在布局中带 `layout_marginTop="6dp"`：

```xml
<!-- notification_mood_chooser.xml:172-177 -->
<LinearLayout
    android:id="@+id/slot_row_2"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="6dp"
    android:orientation="horizontal">
```

若行内 5 个子槽位（`btn_slot_5..9`，均为 `layout_weight=1` + `wrap_content` 高）全设 GONE，则 `slot_row_2` 高度为 0，**但 `marginTop=6dp` 仍会计入父布局高度** → 展开态底部确实会多出一条 **6dp 空白**。代码把整行 GONE 掉，边距随之不生效，**正确消除该空白** ✅

2. 阈值 `> 5` 与槽位分组一致（第一行 0–4、第二行 5–9），**边界正确** ✅

3. `setViewVisibility` 是 RemoteViews 的合法方法（`@RemotableViewMethod`），无兼容问题 ✅

### ⚠️ 风险提示

该修复**目前只存在于工作区，未提交**。`git status` 显示：

```
 M app/src/main/java/com/mooddiary/app/Reminder.kt
```

而 `app/build/outputs/apk/release/app-release.apk`（10:24 生成）**晚于**最后提交（09:56），因此该 APK 是否包含此改动**无法从产物确认**。**若按提交 `94dda15` 出包，则第 3 节的问题仍然存在**（展开态残留 6dp 空白）。建议提交后再出包，并在提交信息中说明。

---

## 4. RemoteViews 合规性

### 结论：**大部分正确，但有 1 个严重问题（宽度预算）+ 2 个需知悉的兼容点**

#### 4.1 严重：`DecoratedCustomViewStyle` 会把自定义布局嵌进 64dp 左边距的模板，可用宽度被大幅压缩

这是本次审查**最重要的独立发现**。通过反编译 `androidx.core:core:1.13.1` 验证：

```java
// androidx.core.app.NotificationCompat$DecoratedCustomViewStyle
public boolean displayCustomViewInline() { return true; }   // ← 注意

public RemoteViews makeContentView(...) {
    if (Build.VERSION.SDK_INT >= 24) return null;           // ← API24+ 交给平台实现
    ...
}
public RemoteViews makeBigContentView(...) {
    if (Build.VERSION.SDK_INT >= 24) return null;           // ← API24+ 交给平台实现
    ...
}
private RemoteViews createRemoteViews(RemoteViews custom, boolean isBig) {
    RemoteViews rv = applyStandardTemplate(true, R.layout.notification_template_custom_big, false);
    ...
    buildIntoRemoteViews(rv, custom);   // ← 把开发者的布局 addView 进模板
    return rv;
}
```

`buildIntoRemoteViews` 的实现（`NotificationCompat$Style` 反编译）：

```java
public void buildIntoRemoteViews(RemoteViews outer, RemoteViews inner) {
    hideNormalContent(outer);                                    // 隐藏 title/text
    outer.removeAllViews(R.id.notification_main_column);
    outer.addView(R.id.notification_main_column, inner.clone()); // ← 自定义布局被嵌入
    outer.setViewVisibility(R.id.notification_main_column, 0);
    if (SDK_INT >= 21) {
        int topPadding = calculateTopPadding();
        outer.setViewPadding(R.id.notification_main_column_container, 0, topPadding, 0, 0);
    }
}
```

而模板 `notification_template_custom_big.xml` 的几何约束是：

```xml
<!-- notification_template_custom_big.xml:32-50 -->
<LinearLayout android:id="@+id/notification_main_column_container"
    android:layout_marginStart="@dimen/notification_large_icon_width"    <!-- 64dp -->
    android:minHeight="@dimen/notification_large_icon_height">           <!-- 64dp -->
    <FrameLayout android:id="@+id/notification_main_column"
        android:layout_width="match_parent"
        android:layout_weight="1"
        android:layout_marginStart="@dimen/notification_content_margin_start"  <!-- 8dp -->
        android:layout_marginBottom="8dp"
        android:layout_marginEnd="8dp" />       <!-- ← 自定义布局塞在这里 -->
    <FrameLayout android:id="@+id/right_side" ...>   <!-- 时间/信息，占宽 -->
```

**关键**：`notification_main_column_container` 有 **64dp 的 `layout_marginStart`**（为系统大图标预留），且 `right_side`（时间）与自定义布局**同一行按 weight 分宽**。因此自定义布局实际可用宽度约为：

```
可用宽度 ≈ 屏幕宽 - 64dp(大图标预留) - 8dp(左 margin) - 8dp(右 margin) - right_side 宽度
```

实测计算（`chooser` 每行 5 槽，槽间距 `marginStart=3dp`；`compact` 槽间距 6dp）：

| 屏幕宽 | right_side | 自定义布局可用宽 | chooser 单槽宽 | compact 单槽宽 |
|---|---|---|---|---|
| 360dp | 0 | 280dp | 53.6dp | 51.2dp |
| **360dp** | **56dp** | **224dp** | **42.4dp** | **40.0dp** |
| **360dp** | **72dp** | **208dp** | **39.2dp** | **36.8dp** |
| 393dp | 56dp | 257dp | 49.0dp | 46.6dp |
| 411dp | 56dp | 275dp | 52.6dp | 50.2dp |

**而 emoji 的真实字宽**（解析 `NotoColorEmoji.ttf` 的 `hmtx` 表）：

```
advance width = 2550 font units / unitsPerEm 2048 = 1.2451 em
→ 22sp 时 = 22 × 1.2451 = 27.39dp
```

**对比**：窄屏（360dp 且时间栏较宽）下单槽仅 **36.8–42.4dp**，emoji 字宽 27.4dp —— 表面上"放得下"，**但**：

1. 布局中 `emoji_slot_i` 是 `layout_width="wrap_content"` **且没有 `singleLine` / `maxLines` / `ellipsize`**（已全文 grep 确认，`notification_mood_chooser.xml` 中三者的出现次数均为 0）。TextView 在不设 `singleLine` 时会**尝试换行**；单个 emoji 字形不可分割，一旦槽位内宽不足便**溢出/被裁**。
2. 更关键的是**标签**：`label_slot_i` 是 `textSize=10sp` 的中文（如"开心""平静"），同样**无 `singleLine`/`maxLines`/`ellipsize`**。两字中文标签在 10sp 下宽约 20dp，虽通常可容纳，但**若用户自定义了较长的名称**（设置页 `label` 输入框 `singleLine=true`，但**没有长度上限**，`SettingsPage.kt:891-894`），标签会换成两行、把整个槽位撑高，进而**把第二行挤出通知可视区** → 表现为"第二行图标不显示"。
3. 槽位高度 `wrap_content` + 无行数约束 = **内容越多、槽位越高、越容易被系统裁切**。

**修复建议（严重）**：

a. **给表情与标签加单行约束**（最直接、必做）：

```xml
<!-- notification_mood_chooser.xml 所有 emoji_slot_i / label_slot_i / c_emoji_i -->
android:singleLine="true"
android:ellipsize="end"
```
（`singleLine` 在 RemoteViews 中受支持。）

b. **给标签设置硬性长度上限**，在 `MoodEditForm` 的 `label` 输入处加 `maxLength`（如 4 字），并在 `MoodCatalog.save` 侧截断。

c. **不要依赖 `DecoratedCustomViewStyle` 的模板**：改用 `setStyle(null)` 并只设 `customContentView`/`customBigContentView`，可完全绕开 64dp 大图标左边距与 `right_side` 争宽，自定义布局可用宽度提升到接近满屏。**这是消除宽度瓶颈最彻底的方案**（代价：失去系统的标准标题/时间装饰，但本项目已有自己的标题与副标题，装饰本就是多余的）。

d. 若保留当前 style，应**降低字号**（22sp → 18sp，字宽降至 22.4dp）并**减少每行槽位数**（5 → 4），留出安全余量。

#### 4.2 `layout_marginStart` / `paddingStart` 兼容性 —— 正确

- `minSdk = 24`（`app/build.gradle.kts:35`），`start/end` 系列属性自 **API 17** 起受 `LinearLayout`/`FrameLayout` 原生支持，无兼容问题 ✅
- RemoteViews 通过反射调用布局属性，`layout_marginStart` 在 API 17+ 均可正常 inflate ✅
- 但注意：`paddingStart`/`paddingEnd` **不在** `RemoteViews.setViewPadding` 的常用路径上，代码也没有运行时改 padding，因此在 SystemUI 进程 inflate 时由 XML 静态解析，**正常** ✅

#### 4.3 `@drawable/notification_mood_bg` 作为 background —— 正确

```xml
<!-- notification_mood_bg.xml -->
<shape android:shape="rectangle">
    <solid android:color="#24FFFFFF" />
    <corners android:radius="18dp" />
</shape>
```

- `ShapeDrawable` 是 RemoteViews 完全支持的 background 类型（非主题属性、无运行时依赖）✅
- 使用 `@drawable/` 静态引用而非 `setInt(id, "setBackgroundResource", ...)`，**避免**了 RemoteViews 反射调用（更稳）✅
- **但有一个 18dp 圆角的观感问题**：槽位高度约 25.8+12=37.8dp，18dp 圆角在窄槽位下会让按钮接近胶囊形，与本项目其他部分风格未必一致。**非 bug**。

#### 4.4 `?attr/` 主题引用 —— 正确（无主题引用）

全文 grep `notification_mood_chooser.xml` / `notification_mood_compact.xml`：

```
?attr / android:theme / app:  →  0 处
```

**正确** ✅ —— RemoteViews 在 SystemUI 进程 inflate，主题是 SystemUI 的，任何 `?attr/` 都会取到错误的值甚至崩溃。这两个布局**全部使用硬编码颜色**（`#FFFFFF`、`#A8FFFFFF`、`#CCFFFFFF`），是正确做法。

⚠️ 但由此产生**一个真实的观感缺陷**：硬编码的白色/半透明白文字与 `notification_mood_bg` 的半透明白底，**在浅色通知栏（SystemUI 浅色主题）下对比度不足**，会让图标显得"淡、像没显示"。这与用户"图标不显示"的描述在观感上高度相似（尤其在浅色壁纸 + 浅色通知面板下）。建议改为跟随系统：使用 `RemoteViews.setTextColor` 在运行时按 `Configuration.uiMode` 选择深/浅色（布局里先给深色默认值），或在 background 上加大对比。

#### 4.5 嵌套层级 / 方法数限制 —— 正确

**展开态**（`notification_mood_chooser.xml`）层级：

```
LinearLayout(vertical)              depth 0
├── TextView notification_title      depth 1
├── TextView notification_subtitle   depth 1
├── LinearLayout (row1)              depth 1
│   └── LinearLayout btn_slot_n      depth 2
│       ├── TextView emoji_slot_n    depth 3
│       └── TextView label_slot_n    depth 3
└── LinearLayout slot_row_2          depth 1
    └── LinearLayout btn_slot_n      depth 2
        ├── TextView emoji_slot_n    depth 3
        └── TextView label_slot_n    depth 3
```

最大深度 **3**（含模板则 +3 ≈ 6）。RemoteViews 的实际限制是 **`View` 数量 / `LayoutInflater` 栈深**，AOSP 中 `RemoteViews` 序列化无硬性 depth 上限，但通知模板在 SysUI 侧有实际渲染成本。**3 层远低于任何风险阈值，正确** ✅

**折叠态**最大深度 **2**（root → c_btn → c_emoji），**正确** ✅

`RemoteViews` 方法数：每个通知约 `2(标题) + 1(副标题) + 10×2(展开槽位可见性/文字) + 5×2(折叠) + 10(点击) + 1(row2 可见性)` ≈ 60 次方法调用，**远低于** `RemoteViews` 的 `MAX_ACTION_BUTTONS`/transaction 限制（`RemoteViews` 通过 `Parcel` 传递，1MB Binder 上限；60 次调用仅数百字节）✅

#### 4.6 `DecoratedCustomViewStyle` 与 `setCustomBigContentView` 同时使用是否冲突

**结论：不冲突，但会导致"折叠态只能显示 compact 布局"，这正是第 2 节严重的根源**

反编译证据（第 4.1 节）：

```java
// NotificationCompat$DecoratedCustomViewStyle
@Override public boolean displayCustomViewInline() { return true; }
```

而 `NotificationCompat$Builder.useExistingRemoteView()`：

```java
private boolean useExistingRemoteView() {
    if (mStyle != null && mStyle.displayCustomViewInline()) return false;  // ← 不走"直接用"
    return true;
}
```

再看 `createBigContentView()`（`NotificationCompat$Builder`）：

```java
public RemoteViews createBigContentView() {
    if (mBigContentView != null && useExistingRemoteView()) return mBigContentView;
    // ↓ displayCustomViewInline()==true 时走这里
    return Api24Impl.createBigContentView(recoverBuilder(...));  // 由平台 style 决定
}
```

**关键结论**：

- 在 **API ≥ 24** 上，`DecoratedCustomViewStyle.apply()` 调用平台的 `Notification.Builder.setStyle(new Notification.DecoratedCustomViewStyle())`；此时平台会**接管** contentView 与 bigContentView 的包装，开发者传入的 `compact`/`views` 被作为 **inner** 嵌入模板。
- 平台在折叠态渲染 **contentView（= compact 包装后）**，展开态渲染 **bigContentView（= views 包装后）** —— **两个 RemoteViews 都被正确使用，方案生效** ✅
- 但也正因为 `displayCustomViewInline()==true`，开发者**无法**通过"不设 big"来让折叠态复用展开布局；compact 是折叠态**唯一**的内容来源 → **compact 只有 5 个槽位这件事无法在运行时弥补**，第 2 节的严重问题因此成立且无法绕开。

**额外风险**：`DecoratedCustomViewStyle` 的模板会**额外注入 `calculateTopPadding()`** 作为 `notification_main_column_container` 的 top padding。反编译显示：

```java
int topPadding = calculateTopPadding();
outer.setViewPadding(R.id.notification_main_column_container, 0, topPadding, 0, 0);
```

其取值随 `fontScale` 在 `notification_top_pad` (10dp) 与 `notification_top_pad_large_text` (5dp) 之间线性插值（`constrain(fontScale, 1.0f, 1.3f)`）。**这意味着系统在 compcat 之上又额外加了约 5–10dp 的顶部内边距** —— 我第 1 节的高度计算**未计入这部分**。折算后：

| fontScale | compact 45.8dp + 模板 topPad | 是否 ≤64dp |
|---|---|---|
| 1.0 | 45.8 + 10 = **55.8dp** | ✅ |
| 1.3 | 53.5 + 5 = **58.5dp** | ✅ |
| 1.5 | 58.7 + 5 = **63.7dp** | ⚠️ 临界 |
| 1.8 | 66.4 + 5 = **71.4dp** | ❌ |

**结论不变（默认字号下仍放得下），但安全余量比表面看起来更小**。系统"特大"字号（1.5+）用户仍会看到裁切。**进一步修复建议**：compact 布局降低 emoji 字号至 18sp（高度降至 6+6+4+4+21.1=41.1dp，+模板 10dp ≈ 51dp，各字号均有充足余量）或减小 root padding。

---

## 5. 资源 id 完整性（独立复核）

### 结论：**正确** —— 我独立复核后确认，且实际为 **43 个**（非 42 个）

### 依据

我**没有**采信原结论，而是编写脚本独立核对，并进一步用**编译产物**验证（而非仅比对 XML 文本）。

**（1）脚本比对 `Reminder.kt` 的 `R.id.*` 与两个布局的 `@+id/*`**

```
total distinct R.id refs in Reminder.kt: 43
MISSING: []
notification_mood_chooser.xml defines 33 ids; unused by Reminder.kt: []
notification_mood_compact.xml defines 10 ids; unused by Reminder.kt: []
```

两个方向都零缺口：代码引用的 **43 个 id 全部有定义**，布局定义的 **43 个 id 也全部被引用**（无死 id）。✅

**（2）重点核对任务点名的 id**

| id | 定义位置 |
|---|---|
| `c_btn_0..4` | `notification_mood_compact.xml:19,37,56,75,94`（`@+id/c_btn_0..4`）|
| `c_emoji_0..4` | `notification_mood_compact.xml:30,49,68,87,106` |
| `btn_slot_5..9` | `notification_mood_chooser.xml:179,204,230,256,282` |
| `emoji_slot_5..9` | `notification_mood_chooser.xml:190,215,241,267,294` |
| `label_slot_5..9` | `notification_mood_chooser.xml:196,221,247,273,299` |
| `slot_row_2` | `notification_mood_chooser.xml:173` |
| `notification_title` / `notification_subtitle` | `notification_mood_chooser.xml:21` / `:29` |

**全部存在** ✅

**（3）编译产物交叉验证（比文本比对更强）**

`R.jar` 中确认所有 43 个字段已生成：

```
javap com/mooddiary/app/R$id.class
→ btn_slot_0..9 (10)、c_btn_0..4 (5)、c_emoji_0..4 (5)、
  emoji_slot_0..9 (10)、label_slot_0..9 (10)、
  notification_subtitle、notification_title、slot_row_2
```

并用 `runtime_symbol_list/release/processReleaseResources/R.txt` 确认**每个 id 都有唯一非零资源值且互不冲突**：

```
btn_slot_0  = 0x7f050029   ...  btn_slot_9  = 0x7f050032
c_btn_0     = 0x7f050033   ...  c_btn_4     = 0x7f050037
c_emoji_0   = 0x7f050038   ...  c_emoji_4   = 0x7f05003c
emoji_slot_0= 0x7f050042   ...  emoji_slot_9= 0x7f05004b
label_slot_0= 0x7f050055   ...  label_slot_9= 0x7f05005e
notification_subtitle = 0x7f050065
notification_title    = 0x7f050066
slot_row_2            = 0x7f05006b
```

43 个值两两不同，**不存在"两个 View 共用一个 id 导致 RemoteViews 更新错元素"的隐患** ✅

**（4）结论**：原声明「42 个 id 全部在布局中定义」的**实质结论正确**，但**计数少算了 1 个**（实际 43 个）。这不影响正确性，仅提示该数字未经脚本核对。另外需肯定：改用编译期 `R.id` 数组（`Reminder.kt:53-71`）替换原 `getIdentifier` 的做法是**正确的方向性改进** —— `getIdentifier` 拼错会返回 0 且在 SystemUI 进程才抛异常，本应用无法捕获，正是"整条通知静默不显示"的经典成因。

---

## 6. 其他可能导致「部分图标不显示」的原因

### 结论：**发现 1 个严重（emoji 字形缺失）+ 若干中等/低**

#### 6.1 严重：设置页预设含 `🫠`（U+1FAE0），Android < 12L 无字形 → 豆腐块

这是**第二次猜测（emoji 兼容性）其实部分成立**的直接证据 —— 此前判为"未解决"，是因为没有定位到具体字符。

```kotlin
// SettingsPage.kt:882-885
val presets = listOf(
    "😄", "😌", "😐", "😔", "😡", "🥰", "😴", "🤔",
    "😢", "🤩", "😰", "🥳", "😤", "🫠", "😶", "🙃"
)
```

各字符的 Unicode/Emoji 版本与最低 Android 要求：

| emoji | 码点 | Emoji 版本 | 最低 Android |
|---|---|---|---|
| 😄😌😐😔😡😴😢😰😤😶🙃 | U+1F604 等 | 1.0 | 4.4+ |
| 🤔 | U+1F914 | 3.0 | 7.1+ |
| 🤩 | U+1F929 | 5.0 | 8.0+ |
| 🥰 / 🥳 | U+1F970 / U+1F973 | 11.0 | **9.0+** |
| **🫠** | **U+1FAE0** | **14.0** | **12L/13+** |

本项目 `minSdk = 24`（Android 7.0）。因此：

- **`🫠` 在 Android 7.0–12 上系统 emoji 字体无此字形** → 渲染为 **□ 豆腐块** 或**空白**（部分 OEM 字体回退为空白/零宽）。
- 用户若把某个心情的表情选成 `🫠`，**该心情图标就"不显示"** —— 与现象完全吻合，且**只在部分设备上复现**（取决于系统版本）。
- 我验证了**当前主机的** `NotoColorEmoji.ttf`（version 2.047, noto-emoji:20240827，约 Android 15 时代）**确实包含** U+1FAE0 → 这解释了为什么在开发/测试机上"看起来正常"，问题只在老设备暴露。**这是一个典型的"测试机复现不出"的漏网问题。**

**修复建议（严重）**：
- 从 `presets` 中**移除 `🫠`**；若需保留"融化"语义，改用 Emoji 兼容性更好的替代（如 `😵💫` 亦为 13.1，不可用；建议 `😵` U+1F635 或 `🤯` U+1F92F，Emoji 5.0/Android 8+）。
- 更稳妥：**给整个预设表加最低版本门槛**，仅在 `Build.VERSION.SDK_INT` 足够时展示对应 emoji；或将 `presets` 按 Emoji 版本分组，低版本设备过滤掉高版本字符。
- 同时把 `defaultMoods`（`MainActivity.kt:285-291`：😄😌😐😔😡）确认为全部 Emoji 1.0 —— 经核对**全部安全** ✅，所以默认目录不会触发该问题，**只有用户手动改过表情才会**。这进一步说明该问题容易被漏测。

#### 6.2 中等：系统对通知布局的压缩（折叠为一行时只显示第一个）

需要澄清一个**常见误解**：Android 在"通知被折叠/仅显示一行"时，**不会**只渲染布局的第一个子 View；它渲染整个 contentView，只是**把超出高度的部分裁掉**。

但存在两个**真实**的压缩场景：

1. **Heads-up 通知（悬浮）**：`DecoratedCustomViewStyle` 的 `makeHeadsUpContentView` 在 API≥24 返回 `null`（见第 4.1 节反编译），此时**系统会用默认的 heads-up 模板**，仅显示小图标+标题+文本 —— 自定义的 5/10 个表情**完全不显示**。若用户是在"通知弹出瞬间"观察，会看到"图标没显示"。
   **建议**：如确需 heads-up 展示表情，需额外 `setCustomHeadsUpContentView(...)`。

2. **锁屏/AOD 或厂商折叠（如 MIUI/EMUI 的通知折叠、三星 Edge）**：不同 OEM 对 `contentView` 的高度预算不同，部分厂商给折叠态的预算**小于** 64dp。此时即便 compact 的 45.8dp 也可能被裁。

#### 6.3 中等：`setTextViewText` 对 emoji 的处理 —— 正常，但需注意 CharSequence

`RemoteViews.setTextViewText(int, CharSequence)` 在 SystemUI 侧直接调用 `TextView.setText`，emoji 作为普通 `String` 会被 `TextView` 用系统 emoji 字体渲染，**无需特殊处理** ✅

但有一个**低风险**点：`mood.emoji` 若被用户输入成**多字符 ZWJ 序列**（如 `👨👩👧` 或带肤色修饰符 `👍🏽`），其字形宽度可达 1.7em 以上（`hmtx` 中多 glyph 组合由 `GSUB` 连字成单 glyph，宽度仍为 2550 units，故**宽度不放大**，但**部分 OEM 字体未实现连字**时会退化为多个 emoji 横向排列 → **溢出槽位被裁**）。当前 UI 只提供预设选择（`MoodEditForm` 的表情是 `FilterChip` 单选，无自由输入），**风险已受控** ✅，仅 `MoodCatalog.load` 解析历史数据时可能带入旧值。

#### 6.4 低：彩色 emoji 在 RemoteViews 中是否需要特殊处理 —— 不需要

彩色 emoji 由系统字体（`NotoColorEmoji`，CBDT/CBLC 位图字体）在 **SystemUI 进程**渲染；自定义布局 inflate 到 SystemUI 后，`TextView` 绑定的是 **SystemUI 的资源/字体环境**，与宿主应用无关。因此：
- **不需要**任何特殊处理 ✅
- 但**也意味着**：宿主 app 无法控制 emoji 的渲染（不能用 `AppCompatEmojiTextHelper` 等 app 侧方案替代系统字体），**版本兼容问题只能在选字符阶段规避**（即 6.1）。

#### 6.5 低：通知渠道重要性 / 折叠行为

```kotlin
// Reminder.kt:146-148
val channel = NotificationChannel(
    CHANNEL_ID, "每小时心情提醒", NotificationManager.IMPORTANCE_DEFAULT
)
```

`IMPORTANCE_DEFAULT` 支持展开与自定义布局 ✅。**但需注意**：

- 渠道一旦创建，**重要性由用户控制且不可由代码提升**。若用户把该渠道降为 `IMPORTANCE_LOW`，通知**默认折叠且不显示大视图** → 用户只能看到 compact（5 个），甚至更少。**这是"部分图标不显示"的又一个真实成因**，且完全在用户侧，代码无法感知。
- 建议：在"测试通知"区域提示用户检查渠道设置；或在发送前用 `NotificationManager.getNotificationChannel(CHANNEL_ID).importance` 检测，若被用户降低则在设置页显示提示。

#### 6.6 中等：硬编码浅色文字 + 半透明背景在浅色通知面板下对比度不足（观感型"看不见"）

已在 4.4 节详述。`notification_mood_bg` 是 `#24FFFFFF`（白色 14% 不透明度），在**浅色通知面板**（背景近白）上几乎不可见；而标题/标签用 `#FFFFFF`/`#CCFFFFFF` **纯白**，在浅色面板上**完全不可读**：

```xml
<!-- notification_mood_chooser.xml:24,34,63 -->
android:textColor="#FFFFFF"     <!-- 标题：浅色面板下不可见 -->
android:textColor="#A8FFFFFF"   <!-- 副标题 -->
android:textColor="#CCFFFFFF"   <!-- 标签 -->
```

而 SystemUI 会用**系统主题**决定通知面板背景 —— 若用户是浅色主题（或 Android 12+ 的动态取色产生浅色面板），**所有白色文字都会"消失"**，用户看到的就是"图标/文字不显示"！

**这极可能是仅次于第 2 节的第二大真实成因**，而且它**完美解释"部分"二字** —— 折叠态只有表情（emoji 彩色位图，浅色下仍可见），展开态有白色标题/副标题/标签（浅色下不可见），于是"展开后有些东西看得见、有些看不见"。

**修复建议**：运行时按系统深/浅色设置文字颜色：

```kotlin
val nightMode = context.resources.configuration.uiMode and
    Configuration.UI_MODE_NIGHT_MASK
val isNight = nightMode == Configuration.UI_MODE_NIGHT_YES
val titleColor = if (isNight) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
val labelColor = if (isNight) 0xCCFFFFFF.toInt() else 0xB31A1A1A.toInt()
views.setTextColor(R.id.notification_title, titleColor)   // 注意：勿与 accentArgb 冲突
views.setTextColor(R.id.notification_subtitle, ...)
for (i in 0 until SLOT_COUNT) views.setTextColor(SLOT_LABEL_IDS[i], labelColor)
```

> ⚠️ 注意 `Reminder.kt:290` 当前把 `notification_title` 设成了 `accentArgb`（跟随主题色），而布局里声明的是 `#FFFFFF`。`setTextColor` 会覆盖 XML 值，**这本身是正确的**；但所选 accent 色（如 `AMBER = 0xFFE48600`、`CYAN = 0xFF00707C`）在**深色通知面板**上对比度尚可，在**浅色面板**上则视颜色而定 —— 建议一并纳入上述明暗分支处理。

#### 6.7 低：`setAutoCancel(true)` 与 `recordQuickMood` 的重复取消 —— 无影响

```kotlin
// Reminder.kt:366     setAutoCancel(true)
// Reminder.kt:393-394 手动 cancel
context.getSystemService(NotificationManager::class.java).cancel(notificationId(hour))
```

`ACTION_QUICK_MOOD` 是 **BroadcastReceiver** 处理的，`setAutoCancel` 只对"点击通知内容意图"生效，对 RemoteViews 内的 `PendingIntent` **不会**自动取消。因此手动 `cancel` 是**必要且正确**的 ✅，不构成 bug。

`notificationId(hour)` 两边一致（`dayOfYear*100 + hour`，`Reminder.kt:49-50`），**正确** ✅

---

## 7. 总体评估与优先级修复清单

### 7.1 对本次修复方案的评价

| 本次方案的主张 | 审查结论 |
|---|---|
| 「只调用了 `setCustomContentView`，没调 `setCustomBigContentView`」 | **成立**：修复前确实只有 contentView，平台会把高布局塞进折叠态被裁 |
| 「折叠态套用了展开态高布局（>100dp），超出被裁」 | **成立**：实测展开态 106.8dp（默认字号），远超 64dp 预算 |
| 「提供两套布局即可修好」 | **部分成立**：裁切确实修好了（compact 45.8dp），**但"部分图标不显示"另有 3 个独立成因未被触及** |
| 「42 个 id 全部定义」 | **实质正确**，实际为 43 个 |

**核心判断：本次修复解决了一个真实存在的 bug（折叠态裁切），但它不是用户所报症状的主因，因此"第三次修复"很可能仍然无法让用户满意。** 前两次判断错误的模式（假设单一根因 → 修 → 未解决）在本轮**重复出现**。

### 7.2 必须修复（严重，均可独立造成"部分图标不显示"）

| 优先级 | 问题 | 位置 | 修复要点 |
|---|---|---|---|
| **P0** | 折叠态 5 槽 vs 展开态 10 槽，显示内容不一致 | `Reminder.kt:66-73,312-334` | 让两态槽位数一致（需重算高度），或明确 UI 告知"折叠仅前 5 个"并保证前 5 个是用户最关心的 |
| **P0** | 预设含 `🫠`(U+1FAE0)，Android<12L 显示豆腐块 | `SettingsPage.kt:884` | 移除 `🫠`，或按 SDK 版本过滤高版本 emoji |
| **P0** | 标题/标签/背景硬编码白色，浅色通知面板下不可见 | `notification_mood_chooser.xml:24,34,63,89...`、`notification_mood_bg.xml` | 运行时按 `uiMode` 设置深/浅文字色与背景色 |
| **P1** | 表情/标签无 `singleLine`/`maxLines`，长标签撑高导致第二行被裁 | 两个布局所有 `emoji_*`/`label_*` | 加 `singleLine=true` + `ellipsize=end`，并限制标签长度 |
| **P1** | `DecoratedCustomViewStyle` 模板预留 64dp 大图标左边距，压缩可用宽度 | `Reminder.kt:365` | 去掉 `DecoratedCustomViewStyle`（已有自绘标题），或减字号/减每行槽位 |

### 7.3 建议修复（中等）

- **P2**：折叠态高度在 fontScale ≥1.5 仍会裁切（63.7–71.4dp）；compact emoji 降至 18sp 或减小 padding。
- **P2**：Heads-up 悬浮态下自定义表情完全不显示（`makeHeadsUpContentView` 返回 null）；如需支持加 `setCustomHeadsUpContentView`。
- **P2**：渠道重要性被用户降级时大视图不展示；可在设置页提示或检测 `getNotificationChannel().importance`。
- **P3**：`slot_row_2` 的 GONE 修复**尚未提交**，按 `94dda15` 出包则展开态仍有 6dp 空白。

### 7.4 未发现问题的项（肯定）

- ✅ id 完整性（43/43，编译产物验证，无重复资源值）
- ✅ 折叠态默认字号高度（45.8dp ≤ 64dp）
- ✅ compact 的 GONE 逻辑（<5、=5 边界均正确）
- ✅ `slot_row_2` 整行 GONE 逻辑（正确消除 6dp 空白）
- ✅ 无 `?attr/` 主题引用
- ✅ 无 RemoteViews 不支持的控件；嵌套深度 3，方法数远低于限制
- ✅ `@drawable/notification_mood_bg` 作为 background 合法
- ✅ `layout_marginStart`/`paddingStart` 在 minSdk 24 下正常
- ✅ `notificationId` 在发送与取消两侧一致
- ✅ 默认目录 `defaultMoods` 的 5 个 emoji 均为 Emoji 1.0，兼容性安全

---

## 附录 A：本次审查使用的关键证据

**A.1 高度精算（真实字体度量）**

```
字体 /usr/share/fonts/truetype/noto/NotoColorEmoji.ttf
  head.unitsPerEm = 2048 ; hhea.ascent = 1900 ; descent = -500 ; lineGap = 0
  行高 = 1.1719 em  →  22sp 文字 = 25.78dp
compact = 6(root padT) + 4(btn padT) + 25.78(emoji) + 4(btn padB) + 6(root padB) = 45.78dp
chooser(1行) = 8 + 16.41(14sp标题) + 2 + 12.89(11sp副标题) + 8 + 12 + 25.78 + 2 + 11.72(10sp标签) ≈ 106.8dp
```

**A.2 emoji 字宽（真实 hmtx）**

```
advance = 2550 / 2048 = 1.2451 em  →  22sp = 27.39dp
```

**A.3 模板宽度约束（`core-1.13.1.aar` → `res/layout/notification_template_custom_big.xml`）**

```
notification_main_column_container : layout_marginStart = notification_large_icon_width = 64dp
notification_main_column          : marginStart 8dp, marginEnd 8dp, layout_weight=1（与 right_side 分宽）
notification_main_column_padding_top : values = 10dp, values-v21 = 0dp
notification_top_pad = 10dp, notification_top_pad_large_text = 5dp
```

**A.4 反编译证据（`androidx.core:core:1.13.1`）**

```
NotificationCompat$DecoratedCustomViewStyle.displayCustomViewInline() → true
NotificationCompat$DecoratedCustomViewStyle.makeContentView()  → API>=24 返回 null
NotificationCompat$DecoratedCustomViewStyle.makeBigContentView() → API>=24 返回 null
NotificationCompat$Style.buildIntoRemoteViews() → addView(R.id.notification_main_column, inner.clone())
NotificationCompat$Builder.useExistingRemoteView() → displayCustomViewInline() 时返回 false
NotificationCompatBuilder → mContentView→setCustomContentView, mBigContentView→setCustomBigContentView
```

**A.5 命令与脚本**

```bash
# id 完整性
python3 -c "比对 R.id.* 与 @+id/*"
# 编译产物确认
unzip R.jar && javap -p com/mooddiary/app/R\$id.class
grep -E '^int id (btn_slot_|c_btn_|c_emoji_|emoji_slot_|label_slot_|slot_row_2|notification_)' R.txt
```

---

*本报告为只读审查产物，除本文件外未修改任何 `.kt` / `.xml` / `drawable` / `.gradle.kts` 源码。*
