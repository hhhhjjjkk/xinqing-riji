package com.mooddiary.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar
import java.util.Locale

/**
 * 每小时心情提醒的调度与发送。
 *
 * 通知直接内嵌 5 个表情按钮，点一下就记录当前小时心情，不跳转 app。
 */
object Reminder {

    const val CHANNEL_ID = "mood_hourly_reminder"
    private const val REQ_ALARM = 9001
    /** 无精确闹钟权限时的触发窗口（15 分钟） */
    private const val WINDOW_MS = 15 * 60 * 1000L
    /** 通知里的心情按钮槽位数（与布局中的槽位数量一致；引用统一常量，避免多处各写） */
    private val SLOT_COUNT = SettingsStore.NOTIFY_MOOD_MAX
    /** 通知快速记录 PendingIntent 的 requestCode 基址（与 REQ_ALARM 等拉开距离） */
    private const val RC_QUICK_MOOD_BASE = 10000

    /**
     * 通知 id。把「一年中的第几天」纳入，使每天的 id 都不相同。
     *
     * 原先直接用 hour 作为 id：跨天后同一个整点会复用相同 id，
     * 从而替换掉昨天那条还没被划掉的通知，导致旧提醒无声消失。
     */
    fun notificationId(hour: Int): Int =
        LocalDate.now().dayOfYear * 100 + hour

    /** 展开态槽位的编译期 id 表（顺序与布局 slot_0..9 一致，两行各 5 个） */
    private val SLOT_BTN_IDS = intArrayOf(
        R.id.btn_slot_0, R.id.btn_slot_1, R.id.btn_slot_2, R.id.btn_slot_3, R.id.btn_slot_4,
        R.id.btn_slot_5, R.id.btn_slot_6, R.id.btn_slot_7, R.id.btn_slot_8, R.id.btn_slot_9
    )
    private val SLOT_EMOJI_IDS = intArrayOf(
        R.id.emoji_slot_0, R.id.emoji_slot_1, R.id.emoji_slot_2, R.id.emoji_slot_3, R.id.emoji_slot_4,
        R.id.emoji_slot_5, R.id.emoji_slot_6, R.id.emoji_slot_7, R.id.emoji_slot_8, R.id.emoji_slot_9
    )
    private val SLOT_LABEL_IDS = intArrayOf(
        R.id.label_slot_0, R.id.label_slot_1, R.id.label_slot_2, R.id.label_slot_3, R.id.label_slot_4,
        R.id.label_slot_5, R.id.label_slot_6, R.id.label_slot_7, R.id.label_slot_8, R.id.label_slot_9
    )
    /** 折叠态槽位（只有 5 个，且只显示表情） */
    private val COMPACT_BTN_IDS = intArrayOf(
        R.id.c_btn_0, R.id.c_btn_1, R.id.c_btn_2, R.id.c_btn_3, R.id.c_btn_4
    )
    private val COMPACT_EMOJI_IDS = intArrayOf(
        R.id.c_emoji_0, R.id.c_emoji_1, R.id.c_emoji_2, R.id.c_emoji_3, R.id.c_emoji_4
    )
    /** 折叠态一行最多显示的表情数 */
    private val COMPACT_SLOT_COUNT = COMPACT_BTN_IDS.size

    /** 整点触发广播 */
    const val ACTION_TICK = "com.mooddiary.app.ACTION_REMINDER_TICK"
    /** 在通知里点表情的快速记录广播 */
    const val ACTION_QUICK_MOOD = "com.mooddiary.app.ACTION_QUICK_MOOD"
    const val EXTRA_MOOD_ID = "extra_mood_id"
    const val EXTRA_HOUR = "extra_hour"

    /**
     * 通知里的表情按钮。按分值倒序，最多 5 个（RemoteViews 布局只有 5 个槽位）。
     * 跟随用户自定义的心情目录，而不是写死的 5 个内置心情。
     */
    /**
     * 通知里要显示的心情（最多 [SLOT_COUNT] 个，受通知布局槽位限制）。
     *
     * 优先级：
     * 1. 若用户在设置里指定了「通知显示的心情」，按指定顺序取
     * 2. 否则自动取分值最高的若干个
     *
     * 注意必须过滤掉当前目录中已不存在的心情，避免出现空白按钮。
     */
    private fun notificationMoods(context: Context): List<Mood> {
        // 关键：不能只依赖全局状态 moods。
        // moods 仅在 MainActivity.onCreate 里由 MoodCatalog.load 初始化，
        // 而本项目没有自定义 Application 类；开机 BOOT_COMPLETED、
        // 或进程被杀后的整点广播会新建进程且不经过 MainActivity，
        // 此时 moods 还是内置默认(1..5)，用户在设置里勾选的自定义心情
        // 会全部查不到，导致通知退回默认心情——本次修复也就失效了。
        // 因此这里自行从存储载入一次自定义目录。
        val custom = MoodCatalog.load(context)
        val source = (custom ?: moods).ifEmpty { defaultMoods }
        val byId = source.associateBy { it.id }

        val picked = SettingsStore(context).current().notifyMoodIds
            .mapNotNull { byId[it] }        // 过滤已删除的心情
            .take(SLOT_COUNT)

        return picked.ifEmpty {
            source.sortedByDescending { it.score }.take(SLOT_COUNT)
        }
    }

    /**
     * 开关状态的唯一真值在 SettingsStore 的 "app_settings"（key: reminder_enabled）。
     *
     * 此前这里另开一个 "settings" 文件存自己的 KEY_ENABLED，与设置页写入的
     * "app_settings" 是两份互不相干的真值：onTick 读到旧文件里的 false 就直接
     * return，于是无论是否加入电池优化白名单都不会响。现统一读取同一份配置。
     */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE)
            .getBoolean(SettingsStore.KEY_REMINDER, false)

    /** 只负责排程/取消闹钟；开关状态本身由 SettingsStore 持有 */
    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            ensureChannel(context)
            schedule(context)
        } else {
            cancel(context)
        }
    }

    fun rescheduleIfEnabled(context: Context) {
        if (isEnabled(context)) {
            ensureChannel(context)
            schedule(context)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "每小时心情提醒", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "每小时提醒你记录当下的心情" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    // ---------- 调度 ----------

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ACTION_TICK }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQ_ALARM, intent, flags)
    }

    fun nextHourMillis(now: Calendar = Calendar.getInstance()): Long {
        val cal = now.clone() as Calendar
        cal.add(Calendar.HOUR_OF_DAY, 1)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 排下一次整点提醒。
     *
     * 关键修正（此前通知不响的根因）：
     * 1. 不能对同一个 PendingIntent 既设精确闹钟又设重复闹钟——
     *    后设的 setRepeating 会覆盖掉先设的 setExactAndAllowWhileIdle，
     *    而 setRepeating 自 Android 4.4 起是不精确的，在 Doze 下会被系统推迟到维护窗口，
     *    甚至整夜不触发。实际生效的其实是这个不可靠的重复闹钟，精确闹钟从未真正生效过。
     *
     * 2. 精确闹钟是一次性的，因此必须「链式」调度：本次触发后在 onTick 里再排下一次。
     *
     * 3. Android 12+ 若没有精确闹钟权限，改用 setWindow 的允许空闲触发（比 setRepeating 可靠得多），
     *    并暴露 canScheduleExactAlarms 供界面提示用户去授权。
     */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context)
        val triggerAt = nextHourMillis()

        // 先清掉旧的，避免同一 PendingIntent 残留多个调度
        am.cancel(pi)

        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            // 无精确闹钟权限：用带窗口的「允许空闲时触发」。
            // 比 setRepeating 可靠：系统会在窗口内唤醒，且 Doze 下仍有机会触发。
            am.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                WINDOW_MS,
                pi
            )
            return
        }

        // 有权限：单次精确闹钟（会在 Doze 下触发）。
        // 注意：不要在此之后再设 setRepeating，否则会把它覆盖掉。
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    /** Android 12+ 是否已获准使用精确闹钟；未获准时界面应引导用户开启 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context))
    }

    // ---------- 通知 ----------

    /**
     * 整点触发入口。返回值是本次触发「做了什么 / 为什么没发」的诊断说明，
     * 供设置页的「模拟整点触发」展示给用户。
     *
     * 关键修复（此前通知长期不响的真正根因之一）：
     * 精确闹钟是一次性的，靠触发后再排下一次（链式）。
     * 但原先「未授予通知权限」等分支直接 return 而**没有续排下一次**——
     * 只要某次触发时恰好不满足条件（例如 Android 13+ 的通知权限弹窗
     * 还没点掉闹钟就响了），链路就永久断裂，之后每个整点都静默，
     * 直到用户重新开关提醒。这就是反复修调度、修渠道都无效的原因。
     *
     * 现在除「开关已关闭」外，任何分支都会续上下一小时的闹钟。
     */
    fun onTick(context: Context): String {
        if (!isEnabled(context)) {
            // 开关已关：清掉闹钟。重新开启时 setEnabled 会重新排程。
            cancel(context)
            return "提醒开关当前是关闭的"
        }
        if (!hasNotificationPermission(context)) {
            // 修复链路断裂：即使本次发不出，也必须续上下一小时
            schedule(context)
            return "未授予通知权限（已续排下一次，请到系统设置开启通知权限）"
        }

        val hour = LocalTime.now().hour

        if (QuietHours.isQuiet(SettingsStore(context).current(), hour)) {
            schedule(context)
            return "当前处于免打扰时段，本次不提醒（已续排下一次）"
        }

        val recorded = runCatching {
            MoodDatabase.get(context).dao()
                .findByDateHourSync(LocalDate.now().toString(), hour) != null
        }.getOrDefault(false)
        if (recorded) {
            schedule(context)
            return "当前小时（$hour 点）已记录过，本次不重复提醒（已续排下一次）"
        }

        sendNotification(context, hour)
        schedule(context)
        return "已发送 $hour 点的提醒通知"
    }

    /**
     * 发送整点提醒。
     * 整体由外层 onTick 的 runCatching 保护；此处再加一层，
     * 确保任何偏好读取/资源异常都不会中断后续 schedule 链式排程。
     */
    fun sendNotification(context: Context, hour: Int) = runCatching {
        sendNotificationInternal(context, hour)
    }

    private fun sendNotificationInternal(context: Context, hour: Int) {
        ensureChannel(context)

        // 点通知整体仍可跳转 app（打开记录弹窗），但不是必须的
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_HOUR, hour)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, hour, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 自定义布局：5 个表情按钮，点任意一个直接记录
        // 标题颜色跟随当前主题色
        val accentArgb = when (SettingsStore(context).current().accentColor) {
            AccentColor.AMBER -> 0xFFE48600.toInt()
            AccentColor.BLUE -> 0xFF2B54A8.toInt()
            AccentColor.GREEN -> 0xFF2E7D5B.toInt()
            AccentColor.PURPLE -> 0xFF7A4FA3.toInt()
            AccentColor.PINK -> 0xFFC2185B.toInt()
            AccentColor.CYAN -> 0xFF00707C.toInt()
            AccentColor.RED -> 0xFFB3261E.toInt()
            AccentColor.INDIGO -> 0xFF3F51B5.toInt()
            AccentColor.TEAL -> 0xFF00695C.toInt()
            AccentColor.ORANGE -> 0xFFBF5B00.toInt()
            AccentColor.SLATE -> 0xFF4A5C6A.toInt()
        }
        val views = RemoteViews(context.packageName, R.layout.notification_mood_chooser)
        val compact = RemoteViews(context.packageName, R.layout.notification_mood_compact)
        views.setTextColor(R.id.notification_title, accentArgb)
        views.setTextViewText(R.id.notification_title, "此刻心情怎么样？")
        views.setTextViewText(
            R.id.notification_subtitle,
            "点一个表情，记录 ${String.format(Locale.CHINA, "%02d:00", hour)} 的心情"
        )

        // 按当前心情目录填充槽位（不再是写死的 5 个内置心情）：
        // 表情、文字、点击目标全部在此绑定，因此自定义心情后
        // 通知显示的内容与实际记录的心情一致。
        //
        // 同时生成两个 RemoteViews：
        // - 折叠态（compact）：高度受限，只放一行表情
        // - 展开态（views）：标题 + 副标题 + 两行共 10 个表情
        // 若只设置内容视图而不设置大视图，折叠态会直接套用高布局并被系统裁掉，
        // 表现为「有的心情图标看不见」——这正是本次要修的问题。
        val moodsInNotif = notificationMoods(context)

        // 记录每个槽位对应的 moodId，供两种布局共用的点击绑定
        val slotMoodIds = arrayOfNulls<Int>(SLOT_COUNT)

        // —— 展开态：最多 10 个槽位 ——
        for (i in 0 until SLOT_COUNT) {
            val mood = moodsInNotif.getOrNull(i)
            if (mood == null) {
                // 心情不足：隐藏多余槽位（含其背景）
                views.setViewVisibility(SLOT_BTN_IDS[i], android.view.View.GONE)
            } else {
                views.setViewVisibility(SLOT_BTN_IDS[i], android.view.View.VISIBLE)
                views.setTextViewText(SLOT_EMOJI_IDS[i], mood.emoji)
                views.setTextViewText(SLOT_LABEL_IDS[i], mood.label)
                slotMoodIds[i] = mood.id
            }
        }

        // 第二行容器：当天数不超过 5 个时整体隐藏，
        // 否则容器本身仍占高度（6dp 间距 + 空行），展开通知会多出一条空白
        views.setViewVisibility(
            R.id.slot_row_2,
            if (moodsInNotif.size > 5) android.view.View.VISIBLE else android.view.View.GONE
        )

        // —— 折叠态：只有一行，取前几个（只显示表情） ——
        for (i in 0 until COMPACT_SLOT_COUNT) {
            val mood = moodsInNotif.getOrNull(i)
            if (mood == null) {
                compact.setViewVisibility(COMPACT_BTN_IDS[i], android.view.View.GONE)
            } else {
                compact.setViewVisibility(COMPACT_BTN_IDS[i], android.view.View.VISIBLE)
                compact.setTextViewText(COMPACT_EMOJI_IDS[i], mood.emoji)
            }
        }

        // —— 点击绑定：两种布局共用同一份 PendingIntent ——
        // 用「槽位下标」而非 moodId 编码 requestCode：
        // 原写法 moodId*100+hour 中，moodId=0 会退化为 hour（与通知整体的
        // 跳转 PendingIntent 相同），moodId=90&hour=1 则等于 9001
        // （与闹钟 REQ_ALARM 完全相同且同为广播，仅靠 action 不同勉强区分）。
        for (i in 0 until SLOT_COUNT) {
            val moodId = slotMoodIds[i] ?: continue
            val pending = PendingIntent.getBroadcast(
                context,
                RC_QUICK_MOOD_BASE + i * 100 + hour,
                Intent(context, ReminderReceiver::class.java).apply {
                    action = ACTION_QUICK_MOOD
                    putExtra(EXTRA_MOOD_ID, moodId)
                    putExtra(EXTRA_HOUR, hour)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(SLOT_BTN_IDS[i], pending)
            if (i < COMPACT_SLOT_COUNT) {
                compact.setOnClickPendingIntent(COMPACT_BTN_IDS[i], pending)
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mood)
            // 折叠态用紧凑布局、展开态用完整布局。
            // 两者都必须设置，否则折叠态会套用高布局被裁切。
            .setCustomContentView(compact)
            .setCustomBigContentView(views)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(hour), notification)
    }

    /** 通知里点了表情：直接写库，不跳转 app */
    fun recordQuickMood(context: Context, hour: Int, moodId: Int) {
        val db = MoodDatabase.get(context)
        val dao = db.dao()
        val dateStr = LocalDate.now().toString()

        // 用事务保证原子性：有则更新，无则插入
        kotlinx.coroutines.runBlocking {
            db.withTransaction {
                val existing = dao.findByDateHour(dateStr, hour)
                if (existing != null) {
                    dao.update(existing.copy(moodId = moodId, updatedAt = System.currentTimeMillis()))
                } else {
                    dao.insert(MoodEntry(date = dateStr, hour = hour, moodId = moodId))
                }
            }
        }

        // 取消通知：已记录，不再需要提醒
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(hour))
    }

    // ---------- 电池优化 ----------

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 跳转到系统的电池优化设置页。
     * 逐级降级并给出提示，避免点了没反应：
     * 1. 专属白名单授权页（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）
     * 2. 系统电池优化列表页
     * 3. 应用详情页兜底
     * 4. 都不行就提示用户手动设置
     */
    fun openBatteryOptimizationSettings(context: Context) {
        fun tryStart(intent: Intent): Boolean = try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }

        val opened = if (Build.VERSION.SDK_INT < 23) {
            false
        } else {
            when {
                tryStart(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                ) -> true
                tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) -> true
                tryStart(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                ) -> true
                else -> false
            }
        }

        val msg = if (opened) "已打开系统设置，请允许后台运行"
        else "无法自动跳转，请手动在系统设置中找到本应用并允许后台运行"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
