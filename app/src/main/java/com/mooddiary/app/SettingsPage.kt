package com.mooddiary.app

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPage(
    store: SettingsStore,
    recordCount: Int,
    onClearAll: () -> Unit
) {
    val context = LocalContext.current
    // 直接从 store collect，确保任何修改立刻反映到 UI
    // initialValue 必须用当前真实值（store.current()），不能用 AppSettings()。
    // 用全默认值会让开关先渲染成"关闭"，等 Flow 发出真实值再跳到"开启"，
    // 表现为每次进入设置页开关都重新动画一遍。
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = store.current())

    // 进入页面时刷新一次：用户从系统设置返回后状态会变
    var ignoringBattery by remember { mutableStateOf(Reminder.isIgnoringBatteryOptimizations(context)) }
    // 精确闹钟权限也要用状态承接：直接在组合里调 canScheduleExactAlarms()
    // 没有状态订阅，用户授权后从系统设置返回时不会重组，
    // 红色警告会一直挂在页面上（反之被撤销后也不出现）。
    var exactAlarmsGranted by remember { mutableStateOf(Reminder.canScheduleExactAlarms(context)) }
    // 通知权限同理：从系统设置返回后要立即反映到状态面板上
    var notifGranted by remember { mutableStateOf(Reminder.hasNotificationPermission(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = Reminder.isIgnoringBatteryOptimizations(context)
                exactAlarmsGranted = Reminder.canScheduleExactAlarms(context)
                notifGranted = Reminder.hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var clearConfirm by remember { mutableStateOf(false) }
    var moodEditorOpen by remember { mutableStateOf(false) }
    var hourPicker by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // 自启动跳转结果：null = 还没点过；跳转失败时在页面上展开手动路径指引
    var autoStartResult by remember { mutableStateOf<Reminder.AutoStartResult?>(null) }

    // 通知权限请求器：提醒开关与权限状态面板都要用，因此提到最外层。
    // 授予后才真正把提醒打开——否则闹钟排了、每小时却静默不发。
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted
        if (granted) {
            store.setReminderEnabled(true)
        } else {
            android.widget.Toast.makeText(
                context,
                "没有通知权限，提醒发了你也看不到。请到系统设置里允许本应用发送通知。",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 外观
        SettingsSection("外观", cornerDp = settings.cornerLevel.dp()) {
            SettingLabel("主题模式")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { store.setThemeMode(mode); android.widget.Toast.makeText(context, "已切换主题", android.widget.Toast.LENGTH_SHORT).show() },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "跟随系统"
                                    ThemeMode.LIGHT -> "浅色"
                                    ThemeMode.DARK -> "深色"
                                }
                            )
                        },
                    modifier = Modifier.pressBounce(pressedScale = 0.94f)
                    )
                }
            }
        }

        // 个性化：主题色 / 动态取色
        SettingsSection("个性化", cornerDp = settings.cornerLevel.dp()) {
            SettingLabel("主题色")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AccentColor.entries.forEach { c ->
                    FilterChip(
                        selected = settings.accentColor == c,
                        onClick = {
                            store.setAccentColor(c)
                            android.widget.Toast.makeText(context, "主题色：${c.label()}", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        label = { Text(c.label()) },
                    modifier = Modifier.pressBounce(pressedScale = 0.94f)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                SwitchRow(
                    title = "跟随壁纸取色",
                    subtitle = "Android 12+ 用系统壁纸颜色，会覆盖上面的主题色",
                    checked = settings.useDynamicColor,
                    onCheckedChange = { store.setUseDynamicColor(it) }
                )
            } else {
                Text(
                    "跟随壁纸取色需要 Android 12 及以上",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow(
                title = "沉浸光感",
                subtitle = "长按按钮时发光，并照亮旁边元素的轮廓",
                checked = settings.immersiveGlow,
                onCheckedChange = { store.setImmersiveGlow(it) }
            )

            // 光效强度：仅在开启光感时可用
            if (settings.immersiveGlow) {
                Spacer(Modifier.height(10.dp))
                SettingLabel("光效强度")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlowLevel.entries.forEach { lv ->
                        FilterChip(
                            selected = settings.glowLevel == lv,
                            onClick = { store.setGlowLevel(lv) },
                            label = { Text(lv.label()) },
                            modifier = Modifier.pressBounce(pressedScale = 0.94f)
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingLabel("卡片圆角")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CornerLevel.entries.forEach { lv ->
                    FilterChip(
                        selected = settings.cornerLevel == lv,
                        onClick = { store.setCornerLevel(lv) },
                        label = { Text(lv.label()) },
                        modifier = Modifier.pressBounce(pressedScale = 0.94f)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            SettingLabel("界面字号")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FontLevel.entries.forEach { lv ->
                    FilterChip(
                        selected = settings.fontLevel == lv,
                        onClick = { store.setFontLevel(lv) },
                        label = { Text(lv.label()) },
                        modifier = Modifier.pressBounce(pressedScale = 0.94f)
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow(
                title = "纯黑背景",
                subtitle = "深色模式下使用纯黑，OLED 屏幕更省电",
                checked = settings.pureBlack,
                onCheckedChange = { store.setPureBlack(it) }
            )
        }

        // 启动与操作习惯
        SettingsSection("启动与操作", cornerDp = settings.cornerLevel.dp()) {
            SettingLabel("启动时打开")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StartTab.entries.forEach { t ->
                    FilterChip(
                        selected = settings.startTab == t,
                        onClick = {
                            store.setStartTab(t)
                            android.widget.Toast.makeText(context, "启动时打开：${t.label()}", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        label = { Text(t.label()) },
                    modifier = Modifier.pressBounce(pressedScale = 0.94f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingLabel("点日历某天时")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = settings.calendarTapAction == CalendarTapAction.OPEN_DAY_BOARD,
                    onClick = { store.setCalendarTapAction(CalendarTapAction.OPEN_DAY_BOARD) },
                    label = { Text("展开 24 小时") },
                modifier = Modifier.pressBounce(pressedScale = 0.94f)
                )
                FilterChip(
                    selected = settings.calendarTapAction == CalendarTapAction.QUICK_LOG_NOW,
                    onClick = { store.setCalendarTapAction(CalendarTapAction.QUICK_LOG_NOW) },
                    label = { Text("直接记录此刻") },
                modifier = Modifier.pressBounce(pressedScale = 0.94f)
                )
            }
        }

        // 提醒
        SettingsSection("提醒", cornerDp = settings.cornerLevel.dp()) {
            SwitchRow(
                title = "每小时提醒",
                subtitle = "整点提醒你记录当下的心情",
                checked = settings.reminderEnabled,
                onCheckedChange = { want ->
                    if (want && Reminder.needsNotificationPermission(context)) {
                        // 未授予通知权限：先弹系统权限框，授予后再真正开启
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        store.setReminderEnabled(want)
                    }
                }
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow(
                title = "免打扰时段",
                subtitle = "设定一个安静时段，期间不再提醒",
                checked = settings.quietHoursEnabled,
                onCheckedChange = { store.setQuietHoursEnabled(it) }
            )
            if (settings.quietHoursEnabled) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HourButton("开始", settings.quietStart, Modifier.weight(1f)) {
                        hourPicker = "start" to settings.quietStart
                    }
                    Text("→")
                    HourButton("结束", settings.quietEnd, Modifier.weight(1f)) {
                        hourPicker = "end" to settings.quietEnd
                    }
                }
                Text(
                    "当前：${hourText(settings.quietStart)} 至 ${hourText(settings.quietEnd)} 之间不提醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 提醒可靠性
        if (settings.reminderEnabled) {
            SettingsSection("提醒可靠性", cornerDp = settings.cornerLevel.dp()) {
                // —— 权限状态面板 ——
                // 这三项是系统里真实存在、应用可以主动请求的权限。
                // 之前页面只展示文案、从不发起任何请求，用户看到的就是
                // 「软件没有请求任何权限」——提醒因此静默失效也毫无提示。
                SettingLabel("权限状态")
                PermissionRow(
                    name = "通知权限",
                    granted = notifGranted,
                    why = "没有它，整点提醒发了也不会显示",
                    actionText = "去开启",
                    onAction = {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // 低于 13 没有运行时权限，只能去系统通知设置页
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(
                                            android.provider.Settings.EXTRA_APP_PACKAGE,
                                            context.packageName
                                        )
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                PermissionRow(
                    name = "精确闹钟权限",
                    granted = exactAlarmsGranted,
                    why = "没有它，闹钟可能被系统推迟甚至不触发",
                    actionText = "去开启",
                    onAction = { Reminder.requestExactAlarmPermission(context) }
                )
                Spacer(Modifier.height(8.dp))
                PermissionRow(
                    name = "电池优化白名单",
                    granted = ignoringBattery,
                    why = "没有它，深度休眠时会清掉闹钟和后台进程",
                    actionText = "去设置",
                    onAction = { Reminder.openBatteryOptimizationSettings(context) }
                )

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(Modifier.padding(bottom = 12.dp))

                // —— 自启动 ——
                // 用户会期待一个「请求自启动权限」的按钮。这里必须把事实讲清楚：
                // Android 权限模型里根本没有「自启动」这一项，也没有对应 API。
                // 之前页面把按钮写成「① 开启「自启动」权限」，暗示应用能申请它，
                // 用户点了没生效自然认为功能坏了——问题出在措辞误导，不是功能缺失。
                SettingLabel("自启动")
                Text(
                    "「自启动」不是一个 Android 权限，系统里没有可申请的开关，应用无法像申请" +
                        "通知权限那样向你弹出授权框。它是手机厂商在「手机管家 / 安全中心」里" +
                        "额外加的管控项，只能由你在那个页面里手动打开。\n\n" +
                        "不打开的影响：在最近任务里划掉本应用、或用系统管家清理后台后，" +
                        "系统会清除已注册的闹钟并禁止后台启动，整点提醒就不再响，" +
                        "直到你重新打开本应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { autoStartResult = Reminder.openAutoStartSettings(context) },
                    modifier = Modifier.fillMaxWidth().pressBounce(pressedScale = 0.94f)
                ) {
                    Text("打开系统的自启动设置")
                }
                // 跳转结果反馈。之前是静默的，用户点了没反应会以为功能坏了。
                when (autoStartResult) {
                    Reminder.AutoStartResult.ENABLED_DIRECTLY -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已通过系统接口直接为你开启自启动 ✓（华为/荣耀机型支持）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Reminder.AutoStartResult.VENDOR_PAGE -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已进入本机的自启动管理页。请在本应用的开关处打开自启动" +
                                "（有的机型还需要把「省电策略 / 后台运行」一并放开）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Reminder.AutoStartResult.APP_DETAIL -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "本机没能直达自启动管理页，已改为打开应用详情页。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Reminder.AutoStartResult.FAILED -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "无法自动跳转系统设置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    null -> Unit
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(Modifier.padding(bottom = 12.dp))

                // 手动路径始终显示：即使成功跳到厂商页，用户也常常在那一堆开关里
                // 找不到「自启动」到底在哪（尤其是把自启动藏在「应用启动管理」
                // 或「耗电管理」里的 ROM）。
                Spacer(Modifier.height(8.dp))
                Text(
                    "手动路径：${Reminder.autoStartManualHint()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "已使用精确闹钟。正常情况下重启手机后会自动恢复；若系统清除了提醒，重新打开 app 也会自动恢复。\n通知里可直接点表情快速记录，不用打开 app。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!ignoringBattery) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "为什么在应用里测试正常、到了整点却不响：\n" +
                            "部分手机在「最近任务」里划掉本应用或清理后台后，会清除已注册的闹钟并限制后台运行；" +
                            "清除期间应用无法唤醒。重新打开 app 会自动恢复排程。" +
                            "若希望不手动打开也能及时提醒，请按上面的「电池优化白名单」放行后台运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // —— 模拟整点触发（诊断） ——
                // 不用等到整点：直接走一遍完整的触发逻辑（含续排），
                // 把「做了什么/为什么没发」显示出来，一眼看出断在哪一环。
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                SettingLabel("诊断提醒")
                Text(
                    "不用等到整点，立即按真实流程走一遍：会检查开关、权限、" +
                        "免打扰与当日记录，并把结果与下一步排程显示出来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                var diagResult by remember { mutableStateOf<String?>(null) }
                OutlinedButton(
                    onClick = {
                        val r = Reminder.onTick(context)
                        diagResult = r
                        // 顺手刷新几项会变化的状态
                        ignoringBattery = Reminder.isIgnoringBatteryOptimizations(context)
                        exactAlarmsGranted = Reminder.canScheduleExactAlarms(context)
                        notifGranted = Reminder.hasNotificationPermission(context)
                    },
                    modifier = Modifier.pressBounce(pressedScale = 0.94f)
                ) { Text("模拟一次整点触发") }
                diagResult?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "结果：$r",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(12.dp))

                // —— 通知里显示哪些心情 ——
                // 通知布局只有 5 个槽位，心情较多时原先按分值截断，
                // 导致部分心情的图标不会出现在通知里。
                // 这里让用户自己勾选，并明确提示上限。
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                SettingLabel("通知里显示的心情")
                Text(
                    "最多 ${SettingsStore.NOTIFY_MOOD_MAX} 个（展开通知可显示两行）。" +
                        "不选则自动使用分值最高的几个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                    // 用统一常量，避免上限在多处各写一遍
                                    if (cur.size >= SettingsStore.NOTIFY_MOOD_MAX) {
                                        cur.removeAt(0)
                                    }
                                    cur.add(m.id)
                                }
                                store.setNotifyMoodIds(cur)
                            },
                            // 标注无法渲染的表情，便于定位「某个心情不显示」
                            label = {
                                Text("${m.emoji}${m.label}${if (!isEmojiRenderable(m.emoji)) " ⚠" else ""}")
                            },
                            modifier = Modifier.pressBounce(pressedScale = 0.94f)
                        )
                    }
                }
                if (settings.notifyMoodIds.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { store.setNotifyMoodIds(emptyList()) }) {
                        Text("恢复自动")
                    }
                }
            }
        }

        // 测试通知：立即弹出一条带表情的通知，方便验证设置是否生效
        SettingsSection("测试", cornerDp = settings.cornerLevel.dp()) {
            Text(
                "立即弹出一条测试通知，上面有 5 个表情可以直接点。\n点完会自动记录当前小时的心情，通知随即消失。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    android.widget.Toast.makeText(context, "已发送测试通知", android.widget.Toast.LENGTH_SHORT).show()
                    Reminder.sendNotification(context, java.time.LocalTime.now().hour)
                },
                modifier = Modifier.pressBounce(pressedScale = 0.94f)
            ) {
                Text("立即弹出测试通知")
            }
        }

        // 记录
        SettingsSection("记录", cornerDp = settings.cornerLevel.dp()) {
            SettingLabel("心情标签")
            Text(
                "可增删改：名称、表情、颜色与分值",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { moodEditorOpen = true },
                modifier = Modifier.pressBounce(pressedScale = 0.94f)
            ) {
                Icon(Icons.Default.Edit, null)
                Spacer(Modifier.width(6.dp))
                Text("管理心情标签（${moods.size} 个）")
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            SettingLabel("默认心情")
            Text(
                "打开记录弹窗时预先选中的心情",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            // 用 FlowRow：心情标签较多时自动换行，避免固定单行被挤扁或溢出
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                moods.forEach { m ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .widthIn(min = 58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (settings.defaultMoodId == m.id)
                                    Modifier.background(m.color.copy(alpha = .25f))
                                else Modifier
                            )
                            .pressBounce(pressedScale = 0.9f)
                            .clickable { store.setDefaultMood(m.id); android.widget.Toast.makeText(context, "默认心情：${m.label}", android.widget.Toast.LENGTH_SHORT).show() }
                            .padding(6.dp)
                    ) {
                        Text(m.emoji, fontSize = 25.sp)
                        Text(m.label, fontSize = 10.sp)
                    }
                }
            }
        }

        // 日历
        SettingsSection("日历", cornerDp = settings.cornerLevel.dp()) {
            SwitchRow(
                title = "格子里显示心情表情",
                subtitle = "关闭后日历只显示日期，界面更清爽",
                checked = settings.calendarShowEmoji,
                onCheckedChange = { store.setCalendarShowEmoji(it) }
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow(
                title = "格子里显示备注",
                subtitle = "有备注时在日历格子里显示一行摘要",
                checked = settings.calendarShowNote,
                onCheckedChange = { store.setCalendarShowNote(it) }
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingLabel("每周起始日")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = settings.weekStart == WeekStart.SUNDAY,
                    onClick = { store.setWeekStart(WeekStart.SUNDAY); android.widget.Toast.makeText(context, "已设为周日起始", android.widget.Toast.LENGTH_SHORT).show() },
                    label = { Text("周日") },
                modifier = Modifier.pressBounce(pressedScale = 0.94f)
                )
                FilterChip(
                    selected = settings.weekStart == WeekStart.MONDAY,
                    onClick = { store.setWeekStart(WeekStart.MONDAY); android.widget.Toast.makeText(context, "已设为周一起始", android.widget.Toast.LENGTH_SHORT).show() },
                    label = { Text("周一") },
                modifier = Modifier.pressBounce(pressedScale = 0.94f)
                )
            }
        }

        // 数据
        SettingsSection("数据", cornerDp = settings.cornerLevel.dp()) {
            Text("共 $recordCount 条心情记录")
            Text(
                "数据只保存在本机，未开启云备份",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { clearConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.pressBounce(pressedScale = 0.94f)
            ) {
                Icon(com.mooddiary.app.icons.ExtraIcons.DeleteForever, null)
                Spacer(Modifier.width(6.dp))
                Text("清空所有记录")
            }
        }

        // 关于
        SettingsSection("关于", cornerDp = settings.cornerLevel.dp()) {
            Text("心情日记", fontWeight = FontWeight.Bold)
            Text(
                "版本 ${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "按小时记录心情，数据全部留在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    hourPicker?.let { (which, value) ->
        HourPickerDialog(
            title = if (which == "start") "免打扰开始时间" else "免打扰结束时间",
            initial = value,
            onDismiss = { hourPicker = null },
            onPick = { h ->
                hourPicker = null
                if (which == "start") store.setQuietRange(h, settings.quietEnd)
                else store.setQuietRange(settings.quietStart, h)
            }
        )
    }

    if (moodEditorOpen) {
        MoodEditorDialog(onClose = { moodEditorOpen = false })
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("清空所有记录？") },
            text = { Text("将删除全部 $recordCount 条心情记录，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    onClearAll()
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } }
        )
    }
}

private fun hourText(hour: Int) = String.format(Locale.CHINA, "%02d:00", hour)

/**
 * 玻璃拟态面板。
 *
 * 关键：面板必须比页面背景**更亮或更暗、且带一点强调色染色**，
 * 否则同色半透明叠在同色上等于没有效果（上一版就是这样，完全看不出来）。
 * 这里用「强调色轻染 + 白色高光」制造与背景的差异，
 * 再配受光描边和顶部反光，形成磨砂玻璃观感。
 *
 * 注：真正的背景模糊需 API 31+，此处用渐变与描边模拟，全版本观感一致。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    /** 圆角大小（dp）。由设置决定，默认 22dp */
    cornerDp: Int = 22,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerDp.dp)
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val accent = scheme.primary
    // 纯黑模式判定：surface 几乎无亮度时，页面背景是纯黑，面板必须靠自身
    // 的明度台阶而不是「比背景深色一点」来区分，否则会和背景糊成一片。
    val pureBlack = dark && scheme.surface.luminance() < 0.005f

    // 玻璃基底。
    // 深色下不能用 surface（纯黑模式下是 #000000）当基底——强调色 16% 叠在纯黑上
    // 只比页面渐变亮 1.05 倍，肉眼分不出面板边界。改用 surfaceContainer，
    // 它本身就是「比背景高一阶的容器面」，再叠强调色就有清晰的悬浮感。
    val base = if (dark) scheme.surfaceContainer else scheme.surface

    // 玻璃主体：强调色轻染 + 白/黑偏移，确保与页面背景有可见差异
    val glassTop = if (dark) {
        accent.copy(alpha = if (pureBlack) 0.20f else 0.16f).compositeOver(base)
    } else {
        Color.White.copy(alpha = 0.90f).compositeOver(accent.copy(alpha = 0.10f))
    }
    val glassBottom = if (dark) {
        accent.copy(alpha = if (pureBlack) 0.08f else 0.06f).compositeOver(base)
    } else {
        Color.White.copy(alpha = 0.62f).compositeOver(accent.copy(alpha = 0.05f))
    }

    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(glassTop, glassBottom)
                ),
                shape
            )
            // 顶部反光：一条极窄的亮带，模拟玻璃上缘受光
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.10f else 0.55f),
                    0.06f to Color.Transparent
                ),
                shape
            )
            .border(
                1.dp,
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.26f else 0.95f),
                        accent.copy(alpha = 0.28f),
                        Color.White.copy(alpha = if (dark) 0.10f else 0.45f)
                    )
                ),
                shape
            )
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SettingsSection(
    title: String,
    cornerDp: Int = 22,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassPanel(
        modifier = Modifier.padding(bottom = 16.dp),
        cornerDp = cornerDp
    ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            content()
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, fontWeight = FontWeight.Medium)
}

/**
 * 一行权限状态：左侧名称 + 说明 + 已授予/待开启标记，右侧「去开启」按钮。
 * 已授予时按钮收起、只留一个绿色对勾，避免用户反复点已经开好的权限。
 */
@Composable
private fun PermissionRow(
    name: String,
    granted: Boolean,
    why: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (granted) "$name ✓" else name,
                fontWeight = FontWeight.Medium,
                color = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                if (granted) "已开启" else why,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) {
            Spacer(Modifier.width(10.dp))
            TextButton(
                onClick = onAction,
                modifier = Modifier.pressBounce(pressedScale = 0.94f)
            ) { Text(actionText) }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressBounce(pressedScale = 0.97f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HourButton(
    label: String,
    hour: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.pressBounce(pressedScale = 0.94f)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hourText(hour), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HourPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.height(300.dp)
            ) {
                items((0..23).toList()) { h ->
                    val selected = h == initial
                    TextButton(
                        onClick = { onPick(h) },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary
                            else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    ) { Text(hourText(h)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}


/**
 * 心情标签管理：增删改 + 长按拖动排序。
 *
 * 编辑的是一份本地副本，点「保存」才写回并持久化，
 * 避免边改边写导致历史记录引用的 id 中途失效。
 *
 * 排序交互：长按任意一行可拖动换位（点按仍是编辑、右侧按钮仍是删除）。
 * 实现要点：拖动中通过 graphicsLayer 平移被拖行，越过相邻行中点时
 * 立刻在 draft 里换位；换位后布局槽位随之变化，必须把已累积的
 * 拖动偏移抵消掉相邻行的高度，被拖行才不会在指尖下跳动。
 */
@Composable
fun MoodEditorDialog(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(moods.toList()) }
    var editing by remember { mutableStateOf<Mood?>(null) }
    // 在 composable 上下文里先取出，供 onClick 等非 composable 闭包使用
    val accentColor = MaterialTheme.colorScheme.primary
    val haptic = LocalHapticFeedback.current

    // —— 长按拖动排序状态 ——
    // 正在拖动的心情 id；null 表示没有拖动
    var draggingId by remember { mutableStateOf<Int?>(null) }
    // 被拖行相对其「当前布局槽位」的 Y 偏移
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // 每行的高度（id → px）。只在 onGloballyPositioned 里写入、
    // 只在手势回调里读取，不参与组合，因此不会引起额外重组。
    val rowHeights = remember { HashMap<Int, Float>() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("心情标签") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "点按编辑，长按拖动可调整顺序（保存后生效）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                draft.forEach { m ->
                    // 必须用 key 包住：换位时若靠「槽位」复用节点，
                    // pointerInput 的 key 会从 A.id 变成 C.id，手势协程被取消，
                    // 拖拽在第一次换位的瞬间就断掉。key 让节点跟随心情移动。
                    key(m.id) {
                    val isDragging = draggingId == m.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                // 高度只由内容决定，换位前后不变，随时记录即可
                                rowHeights[m.id] = coords.size.height.toFloat()
                            }
                            // graphicsLayer 必须在 clip 之前：clip 会裁掉其后所有
                            // 绘制内容，若放在前面，被拖行的平移会被裁在原位圆角内
                            .graphicsLayer {
                                if (isDragging) {
                                    translationY = dragOffsetY
                                    // 拾起感：轻微放大 + 压一点透明度
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    alpha = 0.92f
                                }
                            }
                            .zIndex(if (isDragging) 1f else 0f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isDragging) accentColor.copy(alpha = 0.10f)
                                else Color.Transparent
                            )
                            .pointerInput(m.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = m.id
                                        dragOffsetY = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        var newOffset = dragOffsetY + amount.y
                                        val myH = rowHeights[m.id] ?: return@detectDragGesturesAfterLongPress
                                        var idx = draft.indexOfFirst { it.id == m.id }
                                        if (idx >= 0) {
                                            // 向下越过下一行中点：与下一行换位
                                            if (newOffset > 0 && idx < draft.lastIndex) {
                                                val belowH = rowHeights[draft[idx + 1].id]
                                                if (belowH != null && newOffset > (myH + belowH) / 2f) {
                                                    draft = draft.toMutableList().apply {
                                                        add(idx, removeAt(idx + 1))
                                                    }
                                                    // 换位后布局槽位下移了 belowH，
                                                    // 抵消掉才能保持手指下的位置连续
                                                    newOffset = newOffset - belowH
                                                    idx += 1
                                                }
                                            }
                                            // 向上越过上一行中点：与上一行换位
                                            // （idx 已在上方换位后重算，不会用过期下标）
                                            if (newOffset < 0 && idx > 0) {
                                                val aboveH = rowHeights[draft[idx - 1].id]
                                                if (aboveH != null && -newOffset > (myH + aboveH) / 2f) {
                                                    draft = draft.toMutableList().apply {
                                                        add(idx - 1, removeAt(idx))
                                                    }
                                                    newOffset = newOffset + aboveH
                                                    idx -= 1
                                                }
                                            }
                                            // 边缘限制：拖到头后最多再滑出半行，
                                            // 避免整行飞出列表范围
                                            if (idx == 0) newOffset = newOffset.coerceAtLeast(-myH * 0.5f)
                                            if (idx == draft.lastIndex) newOffset = newOffset.coerceAtMost(myH * 0.5f)
                                        }
                                        dragOffsetY = newOffset
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        dragOffsetY = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffsetY = 0f
                                    }
                                )
                            }
                            .clickable {
                                // 同「添加心情」：切换编辑目标前，把尚未入列表的
                                // 新心情先落进 draft，避免静默丢失
                                val cur = editing
                                if (cur != null && draft.none { it.id == cur.id }) {
                                    draft = draft + cur
                                }
                                editing = m
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(m.color),
                            contentAlignment = Alignment.Center
                        ) { Text(m.emoji, fontSize = 16.sp) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.label, fontWeight = FontWeight.Medium)
                            Text(
                                "分值 ${m.score}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            if (draft.size <= 2) {
                                android.widget.Toast.makeText(
                                    context, "至少保留 2 个心情",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                draft = draft.filter { it.id != m.id }
                                // 修复「删除后又被保存回来」：若正在编辑的正是这条，
                                // 必须同时清掉 editing——否则保存时合并逻辑会把
                                // editing（还持有被删的心情）重新追加回列表，
                                // 用户的删除被静默撤销。
                                if (editing?.id == m.id) editing = null
                            }
                        }) {
                            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    }
                    HorizontalDivider()
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        // 先把正在编辑的心情落进 draft（若它还没在列表里，
                        // 说明是刚添加、尚未保存的新心情——直接切换 editing
                        // 会把它整个丢掉）。
                        val cur = editing
                        if (cur != null && draft.none { it.id == cur.id }) {
                            draft = draft + cur
                        }
                        // 传 Context：id 计数器持久化。max+1 会在
                        // 「删掉最大 id 的心情后再新增」时复用旧 id，
                        // 导致历史记录被重新归属到新心情（详见 nextMoodId 注释）。
                        editing = Mood(
                            id = nextMoodId(context),
                            label = "新心情",
                            emoji = "🙂",
                            color = accentColor,
                            score = 3
                        )
                    },
                    modifier = Modifier.fillMaxWidth().pressBounce(pressedScale = 0.96f)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("添加心情")
                }

                if (editing != null) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    MoodEditForm(
                        mood = editing!!,
                        onChange = { editing = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val merged = draft.map { d ->
                    editing?.takeIf { it.id == d.id } ?: d
                }.let { list ->
                    // 新添加的（id 不在 draft 中）需要并入
                    val e = editing
                    if (e != null && list.none { it.id == e.id }) list + e else list
                }
                applyMoods(merged)
                MoodCatalog.save(context, merged)
                android.widget.Toast.makeText(
                    context, "已保存", android.widget.Toast.LENGTH_SHORT
                ).show()
                onClose()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    MoodCatalog.reset(context)
                    applyMoods(defaultMoods)
                    android.widget.Toast.makeText(
                        context, "已恢复默认", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    onClose()
                }) { Text("恢复默认") }
                TextButton(onClick = onClose) { Text("取消") }
            }
        }
    )
}

/** 单个心情的编辑表单 */
/**
 * 检测某个表情能否被系统字体渲染。
 *
 * 通知里的表情由 SystemUI 渲染，若系统字体缺少对应字形会显示为
 * 空白或方框（例如 🫠 属 Emoji 14.0，需 Android 12L+ 才有字形）。
 * 用它可以在用户选择表情时提前告知风险，而不是等显示在通知里才发现。
 */
internal fun isEmojiRenderable(emoji: String): Boolean {
    if (emoji.isBlank()) return false
    return runCatching {
        android.graphics.Paint().hasGlyph(emoji)
    }.getOrDefault(true)   // 检测失败时按「可用」处理，避免误报
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodEditForm(
    mood: Mood,
    onChange: (Mood) -> Unit
) {
    val presets = listOf(
        // 仅使用 Emoji 4.0（Android 7.0）及更早就有的码点。
        // 本项目 minSdk = 24，更新的 emoji 在老设备上没有字形，
        // 会被渲染成空白或豆腐块（例如 🫠 属 Emoji 14.0，需 Android 12L+）。
        "😄", "😌", "😐", "😔", "😡", "😴", "🤔", "😢",
        "😰", "😤", "😶", "🙃", "😊", "😍", "😭", "😅"
    )
    val colors = listOf(
        0xFFFFB300, 0xFF43A047, 0xFF78909C, 0xFF42A5F5, 0xFFEF5350,
        0xFFAB47BC, 0xFF26A69A, 0xFFEC407A, 0xFF7E57C2, 0xFF8D6E63
    )

    // 名称含换行或 \u0001（存储分隔符）会破坏 MoodCatalog 的行/字段结构：
    // 解析时该行会被丢弃，用户的心情在重启后消失。在输入处直接剔除。
    // （singleLine=true 只挡住键盘回车，粘贴进来的换行仍会进文本。）
    fun sanitizeLabel(raw: String) = raw.replace("\n", " ").replace("\r", " ").replace("\u0001", " ")

    Column {
        OutlinedTextField(
            value = mood.label,
            onValueChange = { onChange(mood.copy(label = sanitizeLabel(it))) },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Text("表情", style = MaterialTheme.typography.bodySmall)
        // 当前表情若无法被系统字体渲染则明确提示，
        // 避免用户选了一个自己手机上显示不出来的表情（通知里会更明显）
        if (!isEmojiRenderable(mood.emoji)) {
            Spacer(Modifier.height(6.dp))
            Text(
                "当前表情（${mood.emoji}）在本设备上无法显示为图形，" +
                    "可能显示为空白或方框，建议从下方另选一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { e ->
                FilterChip(
                    selected = mood.emoji == e,
                    onClick = { onChange(mood.copy(emoji = e)) },
                    label = { Text(e, fontSize = 16.sp) },
                    modifier = Modifier.pressBounce(pressedScale = 0.9f)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("颜色", style = MaterialTheme.typography.bodySmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            colors.forEach { c ->
                val col = Color(c)
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(col)
                        .pressBounce(pressedScale = 0.85f)
                        .clickable { onChange(mood.copy(color = col)) }
                        .then(
                            if (mood.color.value == col.value)
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("分值 ${mood.score}（用于趋势统计）", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = mood.score.toFloat(),
            onValueChange = { onChange(mood.copy(score = it.roundToInt().coerceIn(1, 5))) },
            valueRange = 1f..5f,
            steps = 3
        )
    }
}
