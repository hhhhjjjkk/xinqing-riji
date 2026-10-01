# 心情日记 v4.8.1 三项修复独立验证报告

## 审查范围与结论汇总

本报告对指定源码做静态、只读审查；除本报告外未修改任何 `.kt`、`.xml` 或 Gradle 文件。由于唯一允许写入的是本报告，本次未执行会生成构建产物的 Gradle/设备测试。

| 项目 | 结论 | 摘要 |
|---|---|---|
| 1. 冷进程加载自定义心情（P0） | **已修复** | `notificationMoods(context)` 在建立 `byId`、过滤勾选项前直接调用 `MoodCatalog.load(context)`；整点广播不再依赖 `MainActivity` 初始化。 |
| 2. PendingIntent requestCode | **已修复** | 快速心情 requestCode 为 10000–10423 内的五段区间，与闹钟 9001、整体打开 0–23 均不重叠。 |
| 3. `getIdentifier` | **已修复** | 主源码中已无 `getIdentifier`；三个编译期 id 数组均为 5 项，并与 XML slot 0–4 一一对应。 |
| 4. 回归检查 | **已修复（未发现引入新问题）** | 广播路径在 IO 线程读偏好；常见损坏行可跳过/降级；删除心情后的残留 id 会被过滤。仅有一个低风险防御性缺口：`sendNotification` 没有总异常兜底。 |

**严重问题：未发现。**

---

## 1. P0：冷启动/开机后的自定义通知心情

### 结论：**已修复**

`notificationMoods(context)` 已在通知发送链路内部自行读取持久化目录，且读取发生在按 id 建索引和过滤 `notifyMoodIds` 之前。因此，即使系统在没有创建 `MainActivity` 的冷进程中触发整点广播，自定义 id（如 6/7/8）仍能匹配到实际心情。

### 依据

1. `sendNotification` 填槽前调用 `notificationMoods(context)`（`Reminder.kt:266–270`）：

   ```kotlin
   val moodsInNotif = notificationMoods(context)
   for (i in 0 until SLOT_COUNT) {
   ```

2. `notificationMoods` 先加载目录，再建立 `byId`（`Reminder.kt:74–92`）：

   ```kotlin
   val custom = MoodCatalog.load(context)
   val source = (custom ?: moods).ifEmpty { defaultMoods }
   val byId = source.associateBy { it.id }

   val picked = SettingsStore(context).current().notifyMoodIds
       .mapNotNull { byId[it] }
       .take(SLOT_COUNT)

   return picked.ifEmpty {
       source.sortedByDescending { it.score }.take(SLOT_COUNT)
   }
   ```

   `MoodCatalog.load(context)` 位于 `associateBy`/`mapNotNull` 之前，所以过滤使用刚从持久化层恢复的目录，不是冷进程中的全局默认值。

3. `MoodCatalog.load` 使用 application context 读取 `app_settings` SharedPreferences，不要求 Activity 存在（`Settings.kt:235–240`）：

   ```kotlin
   val prefs = context.applicationContext
       .getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE)
   val raw = prefs.getString(KEY, null) ?: return null
   if (raw.isBlank()) return null
   ```

4. `load == null` 的兜底正确。`moods` 初值就是 `defaultMoods`（`MainActivity.kt:284–300`），因此从未自定义时 `(custom ?: moods).ifEmpty { defaultMoods }` 得到内置默认目录。`MainActivity.onCreate` 虽也会加载目录（`MainActivity.kt:362–370`），但通知链路不再依赖它。

5. “load 成功但返回空列表”已被双重兜底：
   - `MoodCatalog.load` 最终执行 `return list.ifEmpty { null }`（`Settings.kt:241–260`），当前实现不会返回非 null 空列表；
   - 即使未来返回空列表，`Reminder.kt:83` 的 `.ifEmpty { defaultMoods }` 仍会回退默认目录。

6. 开机/冷进程实际链路：
   - 清单注册了 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED`、`ACTION_REMINDER_TICK`，且 `<application>` 没有自定义 `android:name`（`AndroidManifest.xml:14–22, 34–45`）。
   - 开机广播先在 `ReminderReceiver.kt:20–24` 调用 `Reminder.rescheduleIfEnabled(context)`；`Reminder.kt:116–120, 140–144` 重新安排 action 为 `ACTION_TICK` 的广播 PendingIntent。
   - 闹钟触发后，`ReminderReceiver.kt:21, 28–33` 经 IO 协程调用 `Reminder.onTick`；`Reminder.kt:208–227` 调用 `sendNotification`，继而执行上述 `notificationMoods → MoodCatalog.load`。

   即：`BOOT_COMPLETED → rescheduleIfEnabled → schedule(ACTION_TICK) → ACTION_TICK → handleTick → onTick → sendNotification → notificationMoods → MoodCatalog.load`。全程不要求 `MainActivity` 已创建。

7. 设置页保存选择的 id 与顺序：`SettingsPage.kt:362–375` 调用 `setNotifyMoodIds(cur)`；`Settings.kt:146–149, 178–181` 负责解析和持久化。

---

## 2. PendingIntent requestCode

### 结论：**已修复**

新的快速心情 requestCode 与闹钟、通知整体点击及项目中其他 PendingIntent 均不重叠。

### 依据

常量见 `Reminder.kt:35–41`：

```kotlin
private const val REQ_ALARM = 9001
private const val SLOT_COUNT = 5
private const val RC_QUICK_MOOD_BASE = 10000
```

快速操作计算见 `Reminder.kt:287–302`：

```kotlin
RC_QUICK_MOOD_BASE + i * 100 + hour
```

对 `i ∈ [0,4]`、`hour ∈ [0,23]`：

| i | requestCode 区间 |
|---:|---:|
| 0 | 10000–10023 |
| 1 | 10100–10123 |
| 2 | 10200–10223 |
| 3 | 10300–10323 |
| 4 | 10400–10423 |

- 总体边界为 10000–10423（并非全连续，中间有空档）。
- 闹钟为 `PendingIntent.getBroadcast(..., 9001, ...)`（`Reminder.kt:140–144`），不重叠。
- `openPending` 为 `PendingIntent.getActivity(..., hour, ...)`，数值 0–23（`Reminder.kt:233–241`），不重叠，类型也不同。
- 搜索整个 `app/src` 的 `PendingIntent.getActivity/getBroadcast/getService`，只发现 `Reminder.kt` 的闹钟、整体打开、快速心情三处，无其他 requestCode。
- requestCode 不再包含 `moodId`，所以不受 id=0、id=90 或未来更大自定义 id 影响。同一槽位/小时重建时，`FLAG_UPDATE_CURRENT` 会更新 extras，行为正确。

---

## 3. `getIdentifier` 与槽位数组

### 结论：**已修复**

运行时字符串查找已彻底移除；当前 id 由资源编译器校验，三个数组与循环边界一致。

### 依据

1. 对整个 `app/src` 的 Kotlin/Java 源码搜索 `getIdentifier(` 无匹配。

2. `SLOT_COUNT = 5`，三个数组均明确列出 slot 0–4，共 5 项（`Reminder.kt:38–52`）：

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

3. 循环是 `0 until SLOT_COUNT`，当前严格为 0..4；三个数组长度都为 5，`SLOT_*_IDS[i]` 不越界（`Reminder.kt:269–278`）。

4. XML 中所有对应 id 均存在：
   - `btn_slot_0..4`：`notification_mood_chooser.xml:44–45, 71–72, 99–100, 127–128, 155–156`
   - `emoji_slot_0..4`：`notification_mood_chooser.xml:56–57, 84–85, 112–113, 140–141, 167–168`
   - `label_slot_0..4`：`notification_mood_chooser.xml:62–63, 90–91, 118–119, 146–147, 173–174`

5. 心情不足 5 个时，代码通过 `getOrNull(i)` 将整个无数据按钮设为 `GONE`（`Reminder.kt:278–284`），不会留下可见空白按钮。

维护提示（非当前缺陷）：`SLOT_COUNT` 与数组长度仍需人工同步；目前均为 5，无越界。编译期 `R.id` 能防止 id 改名/缺失，但不能自动证明数组长度等于 `SLOT_COUNT`。

---

## 4. 回归：线程、损坏数据与删除心情

### 结论：**已修复（未发现引入新问题）**

### 4.1 每次通知读取 SharedPreferences

- `handleTick` 在 `goAsync()` 后通过 `Dispatchers.IO` 执行 `Reminder.onTick`（`ReminderReceiver.kt:28–33`）。
- `MoodCatalog.load` 只读一个字符串并按行解析（`Settings.kt:236–260`），且使用 application context（`Settings.kt:237–238`）。对每小时一次的小型目录而言，IO/CPU 开销可忽略，不阻塞广播主线程。
- 设置页“立即弹出测试通知”在点击回调直接调用 `sendNotification`（`SettingsPage.kt:399–403`），这一路会在主线程读一次同样的小型偏好；当前目录规模很小，不构成实际性能问题。若未来允许超大目录，可再后台化。

### 4.2 损坏目录

`MoodCatalog.load` 对题目所列损坏情况已有逐级兜底（`Settings.kt:239–260, 280–290`）：

- 不存在/空白：返回 null；
- 字段不足 5 个：跳过行；
- id/score 非整数或溢出：`toIntOrNull()` 失败并跳过行；
- 新格式颜色用 `toIntOrNull()`；旧版超大 `Color.value` 用 `BigInteger` 取高 32 位；
- 非法颜色由 `runCatching(...).getOrNull()` 捕获，再回退 `ScoreColors.forScore(score)`；
- 全部行无效：`list.ifEmpty { null }`，通知侧回退 `moods/defaultMoods`。

因此，颜色溢出、缺字段、非数字等内容级损坏不会抛异常导致通知发不出。

低风险残余：`sendNotification` 没有总 `try/catch`（`Reminder.kt:230–316`），`handleTick` 也只有 `try/finally`（`ReminderReceiver.kt:28–33`）。若 `mood_catalog_v1` 被外部/旧代码写成非 String 类型，`getString` 仍可能抛 `ClassCastException`。当前 `MoodCatalog.save` 始终写 String（`Settings.kt:263–277`），正常产品路径不会制造该状态；这不影响对题目列举损坏格式的“已修复”判断，但可作为后续防御性增强。

### 4.3 删除心情后的残留 id

- `Reminder.kt:83–88` 从当前有效目录建立 `byId`，再通过 `mapNotNull { byId[it] }` 过滤残留 id。
- 部分 id 失效时，只返回仍有效的心情；剩余槽位在 `Reminder.kt:278–284` 被设为 `GONE`。
- 全部选中 id 都失效时，`picked.ifEmpty` 在 `Reminder.kt:90–92` 自动选择当前目录分值最高的最多 5 项，通知仍有有效按钮。

无效 id 不会在删除时立即从偏好清理，但显示/点击链路不会使用它；设置页也可“恢复自动”清空（`SettingsPage.kt:382–386`）。这是可选的数据清理优化，不是空白按钮或错记心情的功能缺陷。

---

## 最终判断

1. **P0 已修复**：冷进程通知路径直接加载持久化自定义目录。
2. **requestCode 已修复**：快速操作编码与所有现有 PendingIntent requestCode 分区。
3. **getIdentifier 已修复**：使用与 XML 对应的编译期 `R.id` 数组。
4. **回归检查通过**：未发现严重问题或由上述修复引入的新功能回归；仅保留 `sendNotification` 无总异常兜底这一低风险增强项。
