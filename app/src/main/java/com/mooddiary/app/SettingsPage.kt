package com.mooddiary.app

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.DeleteForever
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
    var hourPicker by remember { mutableStateOf<Pair<String, Int>?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 外观
        SettingsSection("外观") {
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
                        }
                    )
                }
            }
        }

        // 个性化：主题色 / 动态取色
        SettingsSection("个性化") {
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
                        label = { Text(c.label()) }
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
        }

        // 启动与操作习惯
        SettingsSection("启动与操作") {
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
                        label = { Text(t.label()) }
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
                    label = { Text("展开 24 小时") }
                )
                FilterChip(
                    selected = settings.calendarTapAction == CalendarTapAction.QUICK_LOG_NOW,
                    onClick = { store.setCalendarTapAction(CalendarTapAction.QUICK_LOG_NOW) },
                    label = { Text("直接记录此刻") }
                )
            }
        }

        // 提醒
        SettingsSection("提醒") {
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
            SettingsSection("提醒可靠性") {
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
                    OutlinedButton(onClick = { Reminder.openBatteryOptimizationSettings(context) }) {
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
            }
        }

        // 测试通知：立即弹出一条带表情的通知，方便验证设置是否生效
        SettingsSection("测试") {
            Text(
                "立即弹出一条测试通知，上面有 5 个表情可以直接点。\n点完会自动记录当前小时的心情，通知随即消失。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                android.widget.Toast.makeText(context, "已发送测试通知", android.widget.Toast.LENGTH_SHORT).show()
                Reminder.sendNotification(context, java.time.LocalTime.now().hour)
            }) {
                Text("立即弹出测试通知")
            }
        }

        // 记录
        SettingsSection("记录") {
            SettingLabel("默认心情")
            Text(
                "打开记录弹窗时预先选中的心情",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                moods.forEach { m ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (settings.defaultMoodId == m.id)
                                    Modifier.background(m.color.copy(alpha = .25f))
                                else Modifier
                            )
                            .clickable { store.setDefaultMood(m.id); android.widget.Toast.makeText(context, "默认心情：${m.label}", android.widget.Toast.LENGTH_SHORT).show() }
                            .padding(5.dp)
                    ) {
                        Text(m.emoji, fontSize = 25.sp)
                        Text(m.label, fontSize = 10.sp)
                    }
                }
            }
        }

        // 日历
        SettingsSection("日历") {
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
                    label = { Text("周日") }
                )
                FilterChip(
                    selected = settings.weekStart == WeekStart.MONDAY,
                    onClick = { store.setWeekStart(WeekStart.MONDAY); android.widget.Toast.makeText(context, "已设为周一起始", android.widget.Toast.LENGTH_SHORT).show() },
                    label = { Text("周一") }
                )
            }
        }

        // 数据
        SettingsSection("数据") {
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
                )
            ) {
                Icon(Icons.Default.DeleteForever, null)
                Spacer(Modifier.width(6.dp))
                Text("清空所有记录")
            }
        }

        // 关于
        SettingsSection("关于") {
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
    content: @Composable ColumnScope.() -> Unit
) {
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
            .clip(RoundedCornerShape(22.dp))
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(glassTop, glassBottom)
                ),
                RoundedCornerShape(22.dp)
            )
            // 顶部反光：一条极窄的亮带，模拟玻璃上缘受光
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = if (dark) 0.10f else 0.55f),
                    0.06f to Color.Transparent
                ),
                RoundedCornerShape(22.dp)
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
                RoundedCornerShape(22.dp)
            )
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(Modifier.padding(bottom = 16.dp)) {
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
        Modifier.fillMaxWidth(),
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
    OutlinedButton(onClick = onClick, modifier = modifier) {
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
