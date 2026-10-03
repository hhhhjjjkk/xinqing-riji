package com.mooddiary.app

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = Reminder.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var clearConfirm by remember { mutableStateOf(false) }
    var moodEditorOpen by remember { mutableStateOf(false) }
    var hourPicker by remember { mutableStateOf<Pair<String, Int>?>(null) }

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
                onCheckedChange = { store.setReminderEnabled(it) }
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
                // 精确闹钟权限：Android 12+ 起默认可能未授予。
                // 未授予时闹钟只能走「带窗口的近似触发」，可能被系统推迟，
                // 这正是「加入白名单也不响」的常见原因，因此必须明确提示并给出入口。
                if (!Reminder.canScheduleExactAlarms(context)) {
                    Text(
                        "缺少「精确闹钟」权限，提醒可能被系统推迟甚至不触发。请点下方按钮开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= 31) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                                        ).setData(
                                            android.net.Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            }
                        },
                        modifier = Modifier.pressBounce(pressedScale = 0.94f)
                    ) { Text("开启精确闹钟权限") }
                    Spacer(Modifier.height(12.dp))
                }

                Text(
                    "已使用精确闹钟，关掉 app、重启手机后仍会提醒。\n通知里可直接点表情快速记录，不用打开 app。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!ignoringBattery) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "系统可能为了省电推迟或拦截提醒。建议把本应用加入电池优化白名单。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { Reminder.openBatteryOptimizationSettings(context) },
                        modifier = Modifier.pressBounce(pressedScale = 0.94f)
                    ) {
                        Text("去设置电池优化")
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已加入电池优化白名单 ✓",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
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

    // 玻璃主体：强调色轻染 + 白/黑偏移，确保与页面背景有可见差异
    val glassTop = if (dark) {
        accent.copy(alpha = 0.16f).compositeOver(scheme.surface)
    } else {
        Color.White.copy(alpha = 0.90f).compositeOver(accent.copy(alpha = 0.10f))
    }
    val glassBottom = if (dark) {
        accent.copy(alpha = 0.06f).compositeOver(scheme.surface)
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
 * 心情标签管理：增删改。
 *
 * 编辑的是一份本地副本，点「保存」才写回并持久化，
 * 避免边改边写导致历史记录引用的 id 中途失效。
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

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("心情标签") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                draft.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { editing = m }
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
                            }
                        }) {
                            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        editing = Mood(
                            id = nextMoodId(),
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

    Column {
        OutlinedTextField(
            value = mood.label,
            onValueChange = { onChange(mood.copy(label = it)) },
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
