package com.mooddiary.app

import android.app.Application
import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Entity(tableName = "mood_entries", indices = [Index(value = ["date", "hour"], unique = true)])
data class MoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val hour: Int,
    val moodId: Int,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface MoodDao {
    @Query("SELECT * FROM mood_entries ORDER BY date DESC, hour DESC") fun observeAll(): kotlinx.coroutines.flow.Flow<List<MoodEntry>>
    @Query("SELECT * FROM mood_entries WHERE date = :date AND hour = :hour LIMIT 1") suspend fun findByDateHour(date: String, hour: Int): MoodEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entry: MoodEntry)
    @Delete suspend fun delete(entry: MoodEntry)
}

@Database(entities = [MoodEntry::class], version = 2, exportSchema = false)
abstract class MoodDatabase : RoomDatabase() {
    abstract fun dao(): MoodDao
    companion object {
        @Volatile private var instance: MoodDatabase? = null
        fun get(context: android.content.Context): MoodDatabase = instance ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, MoodDatabase::class.java, "mood_diary.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}

class MoodViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = MoodDatabase.get(application).dao()
    val entries: StateFlow<List<MoodEntry>> = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(date: LocalDate, hour: Int, moodId: Int, note: String, old: MoodEntry?) = viewModelScope.launch {
        dao.insert(MoodEntry(id = old?.id ?: 0, date = date.toString(), hour = hour, moodId = moodId, note = note.trim(), updatedAt = System.currentTimeMillis()))
    }
    fun delete(entry: MoodEntry) = viewModelScope.launch { dao.delete(entry) }
}

data class Mood(val id: Int, val label: String, val emoji: String, val color: Color, val score: Int)
val moods = listOf(
    Mood(5, "开心", "😄", Color(0xFFFFB300), 5), Mood(4, "平静", "😌", Color(0xFF43A047), 4),
    Mood(3, "一般", "😐", Color(0xFF78909C), 3), Mood(2, "低落", "😔", Color(0xFF42A5F5), 2),
    Mood(1, "生气", "😡", Color(0xFFEF5350), 1)
)
fun moodOf(id: Int) = moods.firstOrNull { it.id == id } ?: moods[2]

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { MoodDiaryTheme { MoodDiaryApp() } } }
}

@Composable fun MoodDiaryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFFFB300), secondary = Color(0xFFFFCC66)) else lightColorScheme(primary = Color(0xFFE48600), secondary = Color(0xFF9A6100), tertiary = Color(0xFF4F6F42)), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MoodDiaryApp(vm: MoodViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var hourSheetDate by remember { mutableStateOf<LocalDate?>(null) }
    var editTarget by remember { mutableStateOf<Triple<LocalDate, Int, MoodEntry?>?>(null) }

    fun openEdit(date: LocalDate, hour: Int) { editTarget = Triple(date, hour, entries.firstOrNull { it.date == date.toString() && it.hour == hour }) }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("心情日记", fontWeight = FontWeight.Bold) }) },
        bottomBar = { NavigationBar { listOf("日历" to Icons.Default.CalendarMonth, "记录" to Icons.Default.List, "统计" to Icons.Default.BarChart).forEachIndexed { i, item -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(item.second, null) }, label = { Text(item.first) }) } } },
        floatingActionButton = { if (tab != 2) FloatingActionButton(onClick = { openEdit(LocalDate.now(), java.time.LocalTime.now().hour) }) { Icon(Icons.Default.Add, "新增记录") } }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when(tab) {
                0 -> CalendarPage(month, entries, { month = it }, { d -> hourSheetDate = d })
                1 -> RecordsPage(entries) { e -> openEdit(LocalDate.parse(e.date), e.hour) }
                else -> StatsPage(month, entries, { month = it })
            }
        }
    }
    hourSheetDate?.let { d -> HourMoodSheet(d, entries.filter { it.date == d.toString() }, onPick = { h, _ -> openEdit(d, h) }, onClose = { hourSheetDate = null }) }
    editTarget?.let { (d, h, entry) -> MoodDialog(d, h, entry, onDismiss = { editTarget = null }, onSave = { dt, hh, m, n -> vm.save(dt, hh, m, n, editTarget?.third); editTarget = null }, onDelete = { entry?.let(vm::delete); editTarget = null }) }
}

@Composable fun CalendarPage(month: YearMonth, entries: List<MoodEntry>, setMonth: (YearMonth)->Unit, open: (LocalDate)->Unit) {
    val latestByDay = remember(entries) { entries.groupBy { it.date }.mapValues { (_, list) -> list.maxByOrNull { it.hour } } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            TextButton(onClick = { setMonth(month.minusMonths(1)) }) { Text("‹ 上月") }
            Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { setMonth(month.plusMonths(1)) }) { Text("下月 ›") }
        }
        TextButton(onClick = { setMonth(YearMonth.now()) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("回到今天") }
        Row(Modifier.fillMaxWidth()) { listOf("日","一","二","三","四","五","六").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) } }
        val start = month.atDay(1); val paddingDays = start.dayOfWeek.value % 7; val total = paddingDays + month.lengthOfMonth(); val rows = (total + 6) / 7
        repeat(rows) { row -> Row(Modifier.fillMaxWidth()) { repeat(7) { col ->
            val day = row*7+col-paddingDays+1
            Box(Modifier.weight(1f).aspectRatio(0.82f).padding(2.dp)) {
                if (day in 1..month.lengthOfMonth()) { val d = month.atDay(day); CalendarCell(d, latestByDay[d.toString()], d == LocalDate.now(), { open(d) }) }
            }
        } } }
        Spacer(Modifier.height(8.dp))
        Text("点击某天 → 按小时记录心情", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("颜色说明：" + moods.joinToString("  ") { "${it.emoji}${it.label}" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun CalendarCell(date: LocalDate, entry: MoodEntry?, today: Boolean, click: ()->Unit) {
    val mood = entry?.let { moodOf(it.moodId) }; val bg = mood?.color ?: MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (mood != null && bg.luminance() < .55f) Color.White else MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(bg).then(if(today) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier).clickable(onClick = click), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(date.dayOfMonth.toString(), color = textColor, fontWeight = if(today) FontWeight.Bold else FontWeight.Normal); if(mood != null) Text(mood.emoji, fontSize = 15.sp) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HourMoodSheet(date: LocalDate, dayEntries: List<MoodEntry>, onPick: (Int, MoodEntry?) -> Unit, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("${date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))} 的心情", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("点一个小时，记录那个时刻的心情", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(440.dp)) {
                items((0..23).toList()) { h ->
                    val e = dayEntries.firstOrNull { it.hour == h }
                    val m = e?.let { moodOf(it.moodId) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(m?.color ?: MaterialTheme.colorScheme.surfaceVariant).clickable { onPick(h, e) }.padding(vertical = 10.dp).fillMaxWidth()
                    ) {
                        Text(String.format(Locale.CHINA, "%02d:00", h), fontSize = 11.sp, color = if (m != null && m.color.luminance() < .55f) Color.White else MaterialTheme.colorScheme.onSurface)
                        Text(if (m != null) m.emoji else "＋", fontSize = 20.sp)
                        Text(if (m != null) m.label else "未记录", fontSize = 10.sp, color = if (m != null && m.color.luminance() < .55f) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable fun RecordsPage(entries: List<MoodEntry>, open: (MoodEntry)->Unit) {
    if(entries.isEmpty()) EmptyState("还没有记录", "点击右下角 +，或从日历选一天按小时记录") else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(entries, key={it.id}) { e -> val m = moodOf(e.moodId); Card(Modifier.fillMaxWidth().clickable { open(e) }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(m.emoji, fontSize=30.sp); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("${e.date.substring(5)} ${String.format(Locale.CHINA,"%02d:00",e.hour)}", fontWeight=FontWeight.Bold); Text(if(e.note.isBlank()) m.label else e.note, maxLines=2, overflow=TextOverflow.Ellipsis, color=MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Default.Edit, "编辑", tint=m.color) } } } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun StatsPage(month: YearMonth, entries: List<MoodEntry>, setMonth:(YearMonth)->Unit) {
    val inMonth = entries.filter { runCatching { YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == month }
    val latestByDay = inMonth.groupBy { it.date }.mapValues { (_, list) -> list.maxByOrNull { it.hour }!! }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { TextButton(onClick={setMonth(month.minusMonths(1))}){Text("‹")}; Text("${month.year}年${month.monthValue}月统计", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold); TextButton(onClick={setMonth(month.plusMonths(1))}){Text("›")} }
        TextButton(onClick={setMonth(YearMonth.now())}, Modifier.align(Alignment.CenterHorizontally)){Text("本月")}
        if(inMonth.isEmpty()) { EmptyState("本月还没有心情记录", "按小时记下心情后，这里会展示你的情绪分布") } else {
            val days = latestByDay.values
            val avg = days.map { moodOf(it.moodId).score }.average()
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                StatCard("记录天数", "${days.size} 天", Modifier.weight(1f)); StatCard("平均心情", String.format(Locale.CHINA,"%.1f / 5",avg), Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth()) { Text("本月共记录 ${inMonth.size} 条心情时段", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(22.dp)); Text("情绪分布（按天）", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold)
            moods.forEach { m -> val count=days.count{it.moodId==m.id}; val pct=count.toFloat()/days.size; Row(Modifier.fillMaxWidth().padding(top=12.dp), verticalAlignment=Alignment.CenterVertically) { Text("${m.emoji} ${m.label}", Modifier.width(88.dp)); LinearProgressIndicator(pct, Modifier.weight(1f).height(10.dp).clip(CircleShape), color=m.color, trackColor=MaterialTheme.colorScheme.surfaceVariant); Text("  $count (${(pct*100).toInt()}%)", Modifier.width(74.dp), fontSize=12.sp) } }
            Spacer(Modifier.height(24.dp)); Text("本月心情热力条", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold)
            val map=latestByDay; FlowRow(Modifier.padding(top=10.dp), horizontalArrangement=Arrangement.spacedBy(5.dp), verticalArrangement=Arrangement.spacedBy(5.dp)) { (1..month.lengthOfMonth()).forEach { day -> val e=map[month.atDay(day).toString()]; Box(Modifier.size(18.dp).clip(CircleShape).background(e?.let{moodOf(it.moodId).color} ?: MaterialTheme.colorScheme.surfaceVariant), contentAlignment=Alignment.Center){ Text(day.toString(), fontSize=7.sp, color=if(e==null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White) } } }
        }
    }
}

@Composable fun StatCard(label:String, value:String, modifier:Modifier=Modifier) { Card(modifier) { Column(Modifier.padding(16.dp)) { Text(label, color=MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold) } } }
@Composable fun EmptyState(title:String, subtitle:String) { Column(Modifier.fillMaxWidth().padding(top=70.dp), horizontalAlignment=Alignment.CenterHorizontally) { Text("✦", fontSize=48.sp); Spacer(Modifier.height(10.dp)); Text(title, style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold); Text(subtitle, Modifier.padding(16.dp), textAlign=TextAlign.Center, color=MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable fun MoodDialog(date: LocalDate, hour: Int, entry: MoodEntry?, onDismiss:()->Unit, onSave:(LocalDate,Int,Int,String)->Unit, onDelete:()->Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var d by remember(entry, date){mutableStateOf(date)}; var selected by remember(entry){mutableIntStateOf(entry?.moodId ?: 5)}; var note by remember(entry){mutableStateOf(entry?.note ?: "")}
    AlertDialog(onDismissRequest=onDismiss,
        title={Text(if(entry==null) "记录心情" else "编辑心情")},
        text={ Column {
            OutlinedButton(onClick={ val c=d; DatePickerDialog(context, {_,y,m,day->d=LocalDate.of(y,m+1,day)},c.year,c.monthValue-1,c.dayOfMonth).show() }, modifier=Modifier.fillMaxWidth()){Text("日期：${d.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))}")}
            Spacer(Modifier.height(8.dp))
            Text("时间：${String.format(Locale.CHINA,"%02d:00-%02d:59",hour,hour)}", style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp)); Text("心情是？")
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly){ moods.forEach { m -> Column(horizontalAlignment=Alignment.CenterHorizontally, modifier=Modifier.clip(RoundedCornerShape(10.dp)).then(if(selected==m.id) Modifier.background(m.color.copy(alpha=.25f)) else Modifier).clickable{selected=m.id}.padding(5.dp)){Text(m.emoji,fontSize=25.sp); Text(m.label,fontSize=10.sp)} } }
            Spacer(Modifier.height(10.dp)); OutlinedTextField(note,{note=it}, Modifier.fillMaxWidth(), label={Text("写点备注（可选）")}, minLines=3)
        } },
        confirmButton={TextButton(onClick={onSave(d,hour,selected,note)}){Text("保存")}},
        dismissButton={Row { if(entry!=null) TextButton(onClick=onDelete){Icon(Icons.Default.Delete,null); Text("删除")}; TextButton(onClick=onDismiss){Text("取消")} }})
}
