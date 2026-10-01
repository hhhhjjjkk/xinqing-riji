# 代码审查报告：v4.8.0「通知里显示的心情」自选功能

- **审查范围**：commit `e8b3a11` *feat(notification): 可自选通知里显示哪些心情（修复部分心情图标不显示）*
- **版本**：versionName 4.8.0 / versionCode 67
- **审查性质**：只读审查，未修改任何 `.kt` / `.xml` / `build.gradle.kts` 源文件
- **涉及文件**
  - `app/src/main/java/com/mooddiary/app/Reminder.kt`
  - `app/src/main/java/com/mooddiary/app/Settings.kt`
  - `app/src/main/java/com/mooddiary/app/SettingsPage.kt`
  - `app/src/main/res/layout/notification_mood_chooser.xml`
  - （辅助佐证）`MainActivity.kt`、`ReminderReceiver.kt`、`AndroidManifest.xml`

---

## 结论总览

| # | 项目 | 结论 |
|---|------|------|
| 1 | 空值解析（`""` → `emptyList()`，null 分支） | ✅ 正常 |
| 2 | 上限逻辑（`size >= 5` → `removeAt(0)`） | ✅ 正常（有 1 处非阻塞的 UI 显示瑕疵） |
| 3 | 已删除心情的过滤与槽位紧凑排列 | ✅ 正常（过滤 + `getOrNull` + `GONE` 链路正确） |
| 4 | 槽位一致性（SLOT_COUNT=5 ↔ 布局 15 个 id） | ⚠️ 有问题（当前全部命中，但存在**严重**隐患） |
| 5 | requestCode 冲突（`moodId * 100 + hour`） | 🔴 **严重** |

另附两项审查中额外发现的缺陷（A：心情目录未加载导致通知内容错乱；B：通知 id 跨天复用与静默截断）。

---

## 1. 空值解析

### 结论：✅ 正常

`"".split(',')` 在 Kotlin 中**不会**返回 `[]`，而是返回 `[""]`（含一个空串的单元素列表）。但紧随其后的 `mapNotNull { it.trim().toIntOrNull() }` 会把 `"".toIntOrNull() == null` 这一项过滤掉，最终结果确实是 `emptyList()`。不存在得到 `[0]` 或其他意外值的可能。

### 依据

`Settings.kt:146-149`：

```kotlin
notifyMoodIds = (prefs.getString(KEY_NOTIFY_MOODS, null) ?: "")
    .split(',')
    .mapNotNull { it.trim().toIntOrNull() }
```

逐步推演 `""` 的情形：

1. `prefs.getString(KEY_NOTIFY_MOODS, null)` 返回 `""`（**不是** null，见下）。
2. `"" ?: ""` → `""`（Elvis 的右分支被跳过）。
3. `"".split(',')` → `[""]`（Kotlin `split` 对空输入返回单元素列表，与 Java 的 `String.split` 行为一致）。
4. `"".trim().toIntOrNull()` → `null`。
5. `mapNotNull` 丢弃 `null` → **`emptyList()`**。✅

null 分支同样正确：`prefs.getString(key, null)` 仅在**该 key 从未写入过**时返回 null（例如老版本升级上来的用户），此时 `?: ""` 兜底为空串，再走一遍上面的链路，仍得到 `emptyList()` → 语义为「自动」，与 `AppSettings` 的默认值 `emptyList()`（`Settings.kt:82`）一致。✅

补充：`setNotifyMoodIds(emptyList())`（`Settings.kt:179-181`）写入的是 `emptyList().joinToString(",")` == `""`，与上面的读取链路严格对称，往返一致。✅

---

## 2. 上限逻辑

### 结论：✅ 正常（不会超出 5 个）

`if (cur.size >= 5) cur.removeAt(0)` 用的是 `>=` 而不是 `==`，因此即使存量数据已经超过 5 个（例如从备份恢复、或早期版本写入的脏数据），每勾选一个新的也会先弹出一个旧的，最终收敛到 5。

### 依据

`SettingsPage.kt:362-376`：

```kotlin
moods.forEach { m ->
    val checked = settings.notifyMoodIds.contains(m.id)
    FilterChip(
        selected = checked,
        onClick = {
            val cur = settings.notifyMoodIds.toMutableList()
            if (checked) {
                cur.remove(m.id)
            } else {
                // 已达上限：先移除最早选的那个，再加入新的
                if (cur.size >= 5) cur.removeAt(0)
                cur.add(m.id)
            }
            store.setNotifyMoodIds(cur)
        },
        ...
```

边界推演（`cur` 取自**已解析的** `settings.notifyMoodIds`）：

| 场景 | `cur.size` | 行为 | 结果 |
|------|-----------|------|------|
| 已有 0..4 个，勾选新的 | < 5 | 不移除，直接 `add` | size 1..5 ✅ |
| 已有 5 个，勾选第 6 个 | 5 | `removeAt(0)` → 4，`add` → 5 | **仍是 5** ✅ |
| 已有 6 个（脏数据），勾选新的 | 6 | `removeAt(0)` → 5，`add` → 6 | 保持 6，但**不会继续增长** |

严格说，第 3 行揭示了一个**理论上的不变量缺陷**：若存量长度 `n > 5`，该逻辑每次点击都是 `-1 +1`，只能保持 `n` 不变，**无法主动收敛回 5**。但触发前提是「存量已 >5」，而唯一的写入口 `setNotifyMoodIds` 只有上面这一处调用（另一处是「恢复自动」的 `emptyList()`），当前代码路径几乎不可能产生 >5 的存量。

⚠️ **次要问题（非阻塞）：UI 显示的数量可能大于 5**

`read()` 解析时**没有**做 `.take(5)` 截断，而 `Reminder.notificationMoods()` 里才做 `.take(SLOT_COUNT)`（`Reminder.kt:67`）。两处口径不一致的后果是：若存储值因故超过 5 条（外部改 SharedPreferences，或未来新增写入口），设置页会显示 6 个 chip 处于选中态，而通知里只显示前 5 个 —— UI 与实际行为不符。

**建议（加固，非必需）**：在 `read()` 里加 `.take(5)` 让「存储 → UI → 通知」三处口径统一；或在 `setNotifyMoodIds` 内做 `ids.take(5)` 兜底（把不变量收在写入口，更稳妥）。

**已确认无问题的点**：`cur.remove(m.id)` 使用 `Int` 重载的 `MutableList.remove(element)`，不是 `removeAt(index)`，语义正确（按 id 值移除，而非按下标）。✅

---

## 3. 已删除心情的过滤与槽位紧凑排列

### 结论：✅ 正常

勾选 3 个后删掉其中 1 个，通知里剩下的 2 个会**紧凑排列在 slot 0 和 slot 1**，slot 2..4 被设为 `GONE`，不会出现空白按钮或错位。

### 依据

**第一步：过滤**（`Reminder.kt:61-72`）

```kotlin
private fun notificationMoods(context: Context): List<Mood> {
    val source = moods.ifEmpty { defaultMoods }
    val byId = source.associateBy { it.id }

    val picked = SettingsStore(context).current().notifyMoodIds
        .mapNotNull { byId[it] }        // 过滤已删除的心情
        .take(SLOT_COUNT)

    return picked.ifEmpty {
        source.sortedByDescending { it.score }.take(SLOT_COUNT)
    }
}
```

- `byId[it]`：已删除的 id 在**当前目录**中查不到 → 返回 `null`。
- `mapNotNull`：丢弃 `null` → **剩下的元素自动前移、重新紧凑索引**。这是本条成立的关键：`mapNotNull` 的输出是一个全新的紧凑列表，**不会保留「空洞」**。
- 例：勾选 `[1,3,5]`，删掉 3 → `mapNotNull` 得 `[Mood(1), Mood(5)]`，长度 2，索引 0 和 1。

**第二步：槽位填充**（`Reminder.kt:251-276`）

```kotlin
for (i in 0 until SLOT_COUNT) {
    val btnId = res.getIdentifier("btn_slot_$i", "id", pkg)
    val emojiId = res.getIdentifier("emoji_slot_$i", "id", pkg)
    val labelId = res.getIdentifier("label_slot_$i", "id", pkg)
    val mood = moodsInNotif.getOrNull(i)
    if (mood == null) {
        // 心情不足 5 个：隐藏多余槽位
        views.setViewVisibility(btnId, android.view.View.GONE)
        continue
    }
    views.setViewVisibility(btnId, android.view.View.VISIBLE)
    views.setTextViewText(emojiId, mood.emoji)
    views.setTextViewText(labelId, mood.label)
    ...
}
```

- `getOrNull(i)`：越界返回 `null` 而非抛异常。✅
- `mood == null` → `setViewVisibility(btnId, GONE)` → 整个 `btn_slot_i` 这个 `LinearLayout`（含其内部的 emoji/label 两个 TextView）一起隐藏。✅
- 由于是 `GONE`（而非 `INVISIBLE`），该槽位**不占据布局空间**，外层 `LinearLayout` 的 `layout_weight=1` 会把剩余空间重新分配给可见槽位，视觉上是 2 个按钮更宽地铺开。✅
- `GONE` 的是 `btn_slot_i` 本身（它带着 `android:background="@drawable/notification_mood_bg"`），因此**不会**残留一个空的圆角背景块。✅

**边界确认**：

- 心情目录只剩 1 个（`SettingsPage.kt:794` 限制 `draft.size <= 2` 时禁止删除，故实际最少 2 个）→ 显示对应的个数，其余槽位 GONE。✅
- 勾选的心情**全部**被删除 → `picked` 为 `emptyList()` → `picked.ifEmpty { ... }` 回退到「按分值倒序自动取」分支。✅ 这个 `ifEmpty` 兜底设计正确，避免了「用户勾选了心情但全删了，通知里 5 个槽位全空」的最坏情况。
- `moods` 全局变量为空时 `source = defaultMoods`，不会崩。✅

⚠️ **一处小瑕疵**：`continue` 跳过了 `emojiId` / `labelId` 的清理，但因为整个父容器 `btn_slot_i` 已是 `GONE`，子 View 的旧文本不会显示，故**无害**。若未来改成只隐藏 emoji 而保留按钮底框，才会暴露。

---

## 4. 槽位一致性

### 结论：⚠️ 有问题 —— 当前全部命中，但存在**严重**的脆弱性隐患

**当前状态：一致。** `SLOT_COUNT = 5` 与布局中的 5 组 id 严格一一对应，全部 15 个 id 拼写正确，`getIdentifier` 均能命中。

**但动态查找机制本身构成一处「严重」隐患**（失败时整条通知静默崩溃，且异常发生在 SystemUI 进程，本应用的崩溃统计捕获不到）。

### 依据（一）：数量与拼写核对 ✅

`Reminder.kt:39`：`private const val SLOT_COUNT = 5`

`notification_mood_chooser.xml` 中的 id 全量清点（逐条 grep 去重，各出现 1 次）：

```
btn_slot_0 .. btn_slot_4      (5 个，无缺号、无重复)
emoji_slot_0 .. emoji_slot_4  (5 个)
label_slot_0 .. label_slot_4  (5 个)
+ notification_title / notification_subtitle（由 R.id 静态引用）
```

命名拼写与代码中的字符串模板 `"btn_slot_$i"` / `"emoji_slot_$i"` / `"label_slot_$i"` 完全匹配。✅

### 依据（二）🔴 严重隐患：`getIdentifier` 失败时的静默崩溃

`Reminder.kt:252-254` 用**运行时字符串查找**而非编译期常量引用：

```kotlin
val btnId = res.getIdentifier("btn_slot_$i", "id", pkg)
```

而同一个文件里 `notification_title` / `notification_subtitle` 却用的是**编译期安全的** `R.id.xxx`（`Reminder.kt:238-243`）：

```kotlin
views.setTextColor(R.id.notification_title, accentArgb)
views.setTextViewText(R.id.notification_title, "此刻心情怎么样？")
```

**同一个文件里两种风格混用**，说明动态查找并非刻意为之，而是一处可以消除的不一致。

失败后果分析：

1. `Resources.getIdentifier()` 在**查不到时返回 `0`**，不抛异常。
2. `RemoteViews.setViewVisibility(0, GONE)`：id `0` 不是合法 View id。`RemoteViews` 只是把该操作记录成一条 `ReflectionAction` 写入 Parcel，**不会在应用进程内立即报错**，而是延迟到 `SystemUI` 进程 inflate 并 apply 时执行。
3. 在 SystemUI 侧，找不到 id=0 的 View 会抛出异常，导致**整条通知布局 apply 失败**。
4. **后果**：通知不显示（或显示为空/降级样式）。且异常发生在**另一个进程**，本应用的崩溃统计**捕获不到**，用户只看到「通知没弹 / 弹了个空白」，排查成本极高。

触发失败的具体途径（现实中都会发生）：

- **a) 重命名/删除任一 `*_slot_N` id**：编译器**不会报错**（`R.id.btn_slot_0` 根本没被代码静态引用），重构时极易漏改。
- **b) R8 / 资源缩减**：当前 `isMinifyEnabled = false` / `isShrinkResources = false`（`build.gradle.kts` release 块）所以安全。但一旦按注释里「日后要开启 R8」的设想打开 `isShrinkResources`，这些**仅通过字符串引用的 id** 不在任何 `R.id.*` 的字节码引用里，缩减器**可能判定为未使用而移除**，直接导致 `getIdentifier` 返回 0。这与 `build.gradle.kts` 中「开启 R8 必须先回归通知」的注释形成呼应 —— 动态查找正是让该风险成真的具体机制。
- **c) 包名/多进程场景**：`getIdentifier` 依赖 `context.packageName` 的资源表，包名变化或资源表未就绪时同样可能落空。

`emojiId` / `labelId` 取到 0 时，`setTextViewText(0, text)` 走同样路径，症状相同。

### 修复建议（推荐，收益/成本比高）

用编译期常量数组替代运行时查找，把失败从「运行时静默崩溃」前移到「编译期报错」：

```kotlin
private val SLOT_BTN_IDS = intArrayOf(
    R.id.btn_slot_0, R.id.btn_slot_1, R.id.btn_slot_2, R.id.btn_slot_3, R.id.btn_slot_4
)
private val SLOT_EMOJI_IDS = intArrayOf(
    R.id.emoji_slot_0, R.id.emoji_slot_1, R.id.emoji_slot_2, R.id.emoji_slot_3, R.id.emoji_slot_4
)
private val SLOT_LABEL_IDS = intArrayOf(
    R.id.label_slot_0, R.id.label_slot_1, R.id.label_slot_2, R.id.label_slot_3, R.id.label_slot_4
)
```

循环改为 `for (i in SLOT_BTN_IDS.indices)`。这样：

- id 改名/删除 → **编译失败**，不可能漏改；
- 开启 R8/shrinkResources → 资源被静态引用，不会被误删；
- 顺带省掉每次发通知 15 次 `getIdentifier`（内部要做字符串拼接 + 资源表查找，在广播接收器的 `onTick` 路径上执行 15 次并不划算）。

若坚持保留动态查找，至少应加**防御性跳过**：

```kotlin
if (btnId == 0 || emojiId == 0 || labelId == 0) continue
```

以免 id 缺失时污染整条通知。

---

## 5. requestCode 冲突 🔴 严重

### 结论：🔴 严重 —— requestCode 用 `moodId` 编码，存在多重数值重叠与未定义行为

### 依据

**碰撞点一（🔴 数值完全重叠）：`moodId = 0` 退化为 `hour`**

`Reminder.kt:265-274`（心情按钮）：

```kotlin
val pending = PendingIntent.getBroadcast(
    context,
    moodId * 100 + hour,          // moodId=0  →  requestCode == hour
    Intent(context, ReminderReceiver::class.java).apply {
        action = ACTION_QUICK_MOOD
        putExtra(EXTRA_MOOD_ID, moodId)
        putExtra(EXTRA_HOUR, hour)
    },
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
views.setOnClickPendingIntent(btnId, pending)
```

`Reminder.kt:217-220`（点通知整体跳转 app）：

```kotlin
val openPending = PendingIntent.getActivity(
    context, hour, openIntent,       // requestCode == hour (0..23)
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
```

当 `moodId == 0` 时，`moodId * 100 + hour == hour`，**与 `openPending` 的 requestCode 数值完全相同**。

此处因一个是 `getBroadcast`、一个是 `getActivity`（PendingIntent 类型不同），在 Android 内部的缓存表中属于不同 Key 类型，**严格来说不会互相覆盖**。但：

- 这依赖「类型恰好不同」这一**脆弱前提**；
- 更关键的是，`Mood(id = 0, ...)` 在代码中**真实存在**（`MainActivity.kt:337` 的兜底）：

```kotlin
return catalog.firstOrNull() ?: Mood(0, "未知", "•", Color.Gray, 3)
```

id=0 一旦进入通知槽位，requestCode 就落进 `0..23` 这一段与通知 id、contentIntent 高度耦合的区间。

**碰撞点二（🔴 数值完全重叠）：与 `REQ_ALARM = 9001`**

`Reminder.kt:119-123`：

```kotlin
private const val REQ_ALARM = 9001
...
return PendingIntent.getBroadcast(context, REQ_ALARM, intent, flags)
```

当 `moodId = 90` 且 `hour = 1` 时，`90 * 100 + 1 = 9001` —— **与闹钟的 requestCode 数值完全相同**，且**两者同为 `getBroadcast`、同为 `ReminderReceiver`**。

- 当前未实际互相覆盖，是因为两者的 Intent `action` 不同（`ACTION_TICK` vs `ACTION_QUICK_MOOD`），PendingIntent 判等要求 Intent 等价，故不判等。
- 但这依赖「action 恰好不同」这一**脆弱前提**。若有一天 `ACTION_QUICK_MOOD` 的 Intent 被重构（例如去掉 action 改用 component + extra 区分 —— 这是很常见的"简化"），则两者会判等，导致：
  - 点该心情按钮 → 实际触发 `ACTION_TICK` → 走 `handleTick` → 又发一条通知（而不是记录心情）；
  - 或 `Reminder.kt:154` 的 `am.cancel(pi)` 把已设的闹钟意外取消。

**碰撞点三（✅ 安全，已核实）：不同 moodId 之间不碰撞**

`hour ∈ [0,23]`，间隔 100。`moodId=k` 与 `moodId=k+1` 的区间分别是 `[100k, 100k+23]` 与 `[100k+100, 100k+123]`，**不相交**。所以 moodId 为正整数时，不同心情之间不会碰撞。✅

**碰撞点四（✅ 重新核对后判定为「实际无害」）：`FLAG_UPDATE_CURRENT` 的覆写语义**

初看会担心「同一通知 id + 同小时内重建通知时，槽位 PendingIntent 的 extras 被覆写，导致记录错误心情」。逐条核对后确认**不会发生**：

- 同一小时内重复 `sendNotification`（例如连点两次「立即弹出测试通知」，`SettingsPage.kt:402`）时，requestCode 同为 `moodId*100+hour`，`FLAG_UPDATE_CURRENT` 会用新 Intent 的 extras 覆写旧的 —— 但若该槽位仍是**同一个心情**，覆写前后完全一致，无害；若该槽位**换成了另一个心情**（用户刚改了设置），覆写为**新心情**恰恰是**期望行为**。
- 第二条通知以**相同的通知 id** `hour` 发布（`Reminder.kt:287`），会**整体替换**第一条；RemoteViews 是新建对象，不存在「旧通知残留旧 PendingIntent」的问题。

因此 `FLAG_UPDATE_CURRENT` 在本设计中是**正确且必要**的，不构成缺陷。

**碰撞点五（⚠️ 未定义行为）：负 id / 超大 id**

`MoodCatalog.load()`（`Settings.kt:244`）只做 `f[0].toIntOrNull()`，**未校验取值范围**（已复核源码：无 `id > 0` 之类的检查）。损坏或手改的 SharedPreferences 可写入任意 Int，包括负数与超大值。负 moodId 会让 requestCode 变负，虽不必然碰撞，但属于未定义行为。

**碰撞点六（⚠️ 重复 id 的静默降级）**

`MoodCatalog.load()` 不**去重**；而 `associateBy { it.id }`（`Reminder.kt:63`）对重复 id **只保留最后一个**。若目录里有两个 `id=6` 的心情，用户以为勾选的是 A，通知里显示并记录的却是 B。`nextMoodId()` 用 `max+1` 正常不会产出重复，但损坏数据或未来的导入/恢复功能可以。

### 综合判定

| 子项 | 判定 |
|------|------|
| moodId 之间（1..N，间隔 100 > hour 跨度 23） | ✅ 不碰撞 |
| `moodId=0` vs `openPending` 的 `hour` | 🔴 数值完全相同；仅因 getBroadcast/getActivity 类型不同而未实际覆盖；`Mood(0,...)` 在代码中真实存在 |
| `moodId*100+hour` vs `REQ_ALARM=9001` | 🔴 moodId=90,hour=1 时数值相同且同为 getBroadcast+同 Receiver；仅因 action 不同而未实际覆盖，**依赖脆弱前提** |
| `FLAG_UPDATE_CURRENT` 覆写 extras | ✅ 实际无害（覆写为新心情是期望行为） |
| 负/超大/重复 moodId（`MoodCatalog.load` 无校验去重） | ⚠️ 未定义行为 / 静默降级 |

### 修复建议

1. **用 slotIndex 而非 moodId 参与编码**，并把区段与 `hour`、`REQ_ALARM` 完全隔开：

```kotlin
private const val RC_QUICK_MOOD_BASE = 10_000
// requestCode = RC_QUICK_MOOD_BASE + slotIndex * 100 + hour   // slotIndex ∈ 0..4
```

   一举解决三个问题：
   - 不再依赖 moodId 的取值范围（0、负数、超大值都无所谓）；
   - 天然不会与 `hour`（0..23）或 `REQ_ALARM`（9001）重叠；
   - 同一 slot 在同一小时内重建时，extras 被 `FLAG_UPDATE_CURRENT` 正确覆写为**新心情** —— 这正是期望行为。

2. **不要让 moodId 进入 requestCode**：moodId 是用户可控、可被持久化损坏的数据，不应作为系统级标识的输入。

3. 在 `MoodCatalog.load()` 中**校验并去重 id**（丢弃 `id <= 0` 与重复项），从源头杜绝非法 id 进入通知路径。

---

## 附：审查中额外发现的两项缺陷

### A. 🔴 严重 —— 心情目录在通知路径上可能未加载，导致通知显示错误的心情

`notificationMoods()`（`Reminder.kt:62`）读取的是**全局可变状态** `moods`：

```kotlin
val source = moods.ifEmpty { defaultMoods }
```

而 `moods` 的初始化**只在 `MainActivity.onCreate` 里发生**（`MainActivity.kt:369`）：

```kotlin
MoodCatalog.load(this)?.let { applyMoods(it) }
```

项目**没有自定义 `Application` 类**（已核对 `AndroidManifest.xml`，`<application>` 未设 `android:name`），因此不存在比 Activity 更早的初始化时机。

后果：通知的发送入口是 `ReminderReceiver.onReceive`（进程可能由闹钟**全新创建**，完全不经过 `MainActivity`），此时：

- `moods` 仍是**编译期初始值 `defaultMoods`**（`MainActivity.kt:300`，只有 id 1..5）；
- 而 `notifyMoodIds` 是从 SharedPreferences **正确读出**的用户勾选（例如用户自定义的 id 6、7、8）。

于是 `byId[6]`、`byId[7]`、`byId[8]` 在 `defaultMoods` 中**全部查不到** → `picked` 为 `emptyList()` → 落到 `ifEmpty` 回退分支 → **通知里显示的是 5 个内置默认心情，而不是用户勾选的自定义心情**。

这正是本次 v4.8.0 想修复的「部分心情图标不显示」的一类表现，且在如下常见路径上**必然**发生：

- 开机后 `BOOT_COMPLETED` → 进程新建 → 未启动过 MainActivity → 闹钟触发 → 通知显示错误内容；
- 用户从最近任务划掉 app → 进程被杀 → 下一个整点 → 同上；
- 低内存被系统回收后同理。

**修复建议**：在 `notificationMoods()` 内部自行加载目录，不依赖 Activity 的初始化副作用：

```kotlin
val source = (MoodCatalog.load(context) ?: defaultMoods).ifEmpty { defaultMoods }
```

`MoodCatalog.load()` 是同步的、直接读 SharedPreferences，每次发通知调用一次，开销可接受。这样无论进程从哪个入口起来，通知内容都与用户设置一致。

（顺带：本次改动为 `notificationMoods` 新增了 `context` 参数，方向是对的 —— 但内部仍依赖全局状态，应一并修掉，否则「用户自定义了心情，开机后通知却显示默认心情」这个 bug 依然存在。）

### B. ⚠️ 有问题 —— 通知 id 跨天复用；超过 5 个心情被静默截断

**通知 id 复用**（`Reminder.kt:287`）：

```kotlin
.notify(hour, notification)
```

通知 id 直接用 `hour`（0..23）。同一天的同一小时只会有一条，看起来合理；但**跨天复用同一 id**：昨天的 10 点通知若未被取消（用户没点、也没划掉），今天 10 点的新通知会**替换**它，用户看到的是「昨天的通知变成了今天的」。`setAutoCancel(true)` 和用户手动划掉可缓解，但未划掉的残留会跨天留存。

建议：通知 id 叠加日期因子，或在 `sendNotification` 开头先 `cancel(hour)`。

**静默截断**（`Reminder.kt:67`）：

```kotlin
.take(SLOT_COUNT)
```

用户勾选超过 5 个时**静默丢弃第 6 个及之后**。UI 层已限制最多 5 个（第 2 条），所以当前不会触发；但一旦 UI 约束被绕过（脏数据、未来新增写入口），用户不会得到任何提示。建议在 `read()` 中统一截断（同第 2 条建议），让三处口径一致。

---

## 优先级建议

| 优先级 | 问题 | 位置 |
|--------|------|------|
| P0 🔴 | 心情目录未加载 → 通知显示错误心情（开机/进程被杀后必现） | `Reminder.kt:62` + `MainActivity.kt:369` |
| P1 🔴 | requestCode 用 `moodId` 编码 → moodId=0 退化为 `hour`、moodId=90&hour=1 撞 `REQ_ALARM` | `Reminder.kt:267` |
| P1 🔴 | `getIdentifier` 动态查找 → id 失效时整条通知静默崩溃（异常在 SystemUI 进程，捕获不到） | `Reminder.kt:252-254` |
| P2 ⚠️ | 通知 id 跨天复用；`take(5)` 静默截断；`MoodCatalog.load` 未校验/去重 id | `Reminder.kt:287, 67`；`Settings.kt:241-259` |
| P3 | 设置页 chip 选中数可能 >5（UI 与通知口径不一致） | `Settings.kt:146` |

---

*本报告为只读审查产出，未修改任何源码文件。*
