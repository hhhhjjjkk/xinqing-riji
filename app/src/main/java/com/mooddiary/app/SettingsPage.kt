package com.mooddiary.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
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

@Composable
fun SettingsPage(
    store: SettingsStore,
    recordCount: Int,
    onClearAll: () -> Unit
) {
    val context = LocalContext.current
    // 直接从 store collect，确保任何修改立刻反映到 UI
    val settings by store.settings.collectAsStateWithLifecycle()

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { store.setThemeMode(mode) },
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
                            .clickable { store.setDefaultMood(m.id) }
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
            SettingLabel("每周起始日")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.weekStart == WeekStart.SUNDAY,
                    onClick = { store.setWeekStart(WeekStart.SUNDAY) },
                    label = { Text("周日") }
                )
                FilterChip(
                    selected = settings.weekStart == WeekStart.MONDAY,
                    onClick = { store.setWeekStart(WeekStart.MONDAY) },
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

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
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
