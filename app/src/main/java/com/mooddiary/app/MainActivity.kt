package com.mooddiary.app

import android.Manifest
import android.app.Application
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.*
import androidx.room.withTransaction
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** 备注长度上限，防止超长文本整段进内存与数据库 */
const val MAX_NOTE_LENGTH = 500

@Entity(tableName = "mood_entries", indices = [Index(value = ["date", "hour"], unique = true)])
data class MoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val hour: Int,
    val moodId: Int,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 保存结果。当目标 (日期, 小时) 已被另一条记录占用时返回 [Conflict]，
 * 由 UI 明确询问用户是否覆盖——绝不静默删掉已有记录。
 */
sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class Conflict(val existing: MoodEntry) : SaveOutcome
}

@Dao
interface MoodDao {
    @Query("SELECT * FROM mood_entries ORDER BY date DESC, hour DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<MoodEntry>>

    @Query("SELECT * FROM mood_entries WHERE date = :date AND hour = :hour LIMIT 1")
    suspend fun findByDateHour(date: String, hour: Int): MoodEntry?

    /** 同步版本：供 BroadcastReceiver 在 IO 线程调用（suspend 不便在 goAsync 里用） */
    @Query("SELECT id FROM mood_entries WHERE date = :date AND hour = :hour LIMIT 1")
    fun findByDateHourSync(date: String, hour: Int): Long?

    /** ABORT：冲突时抛异常而不是替换，配合上层显式冲突处理，避免静默丢数据 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: MoodEntry): Long

    @Update
    suspend fun update(entry: MoodEntry)

    @Delete
    suspend fun delete(entry: MoodEntry)
}

@Database(entities = [MoodEntry::class], version = 2, exportSchema = true)
abstract class MoodDatabase : RoomDatabase() {
    abstract fun dao(): MoodDao

    companion object {
        @Volatile private var instance: MoodDatabase? = null

        fun get(context: android.content.Context): MoodDatabase = instance ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, MoodDatabase::class.java, "mood_diary.db")
                // 仅对 v1 保留破坏性迁移：v1 的建表语句已无处可考，且历史版本正是这样处理的。
                // v2 往后的任何版本升级都必须显式提供 Migration，绝不会再清空用户日记。
                .fallbackToDestructiveMigrationFrom(1)
                .build()
                .also { instance = it }
        }
    }
}

/**
 * 心情记录的存储抽象。Room 实现见 [RoomMoodStore]；
 * 测试实现位于 `src/test`，让数据层回归测试无需 Android 运行时即可运行。
 */
interface MoodStore {
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<MoodEntry>>
    suspend fun findByDateHour(date: String, hour: Int): MoodEntry?
    suspend fun insert(entry: MoodEntry)
    suspend fun update(entry: MoodEntry)
    suspend fun delete(entry: MoodEntry)
    suspend fun <R> transaction(block: suspend () -> R): R
}

class RoomMoodStore(private val db: MoodDatabase) : MoodStore {
    private val dao = db.dao()
    override fun observeAll() = dao.observeAll()
    override suspend fun findByDateHour(date: String, hour: Int) = dao.findByDateHour(date, hour)
    override suspend fun insert(entry: MoodEntry) { dao.insert(entry) }
    override suspend fun update(entry: MoodEntry) { dao.update(entry) }
    override suspend fun delete(entry: MoodEntry) { dao.delete(entry) }
    override suspend fun <R> transaction(block: suspend () -> R): R = db.withTransaction { block() }
}

/**
 * 心情记录的数据入口。与 ViewModel 分离，便于用内存实现做回归测试。
 *
 * 关键约定：**任何路径都不得静默删除已有记录**。
 * 旧的实现使用 `OnConflictStrategy.REPLACE` + 唯一索引，用户把一条记录的日期
 * 改成另一条记录已占用的时段时，SQLite 会先删掉冲突行再插入，导致那条记录
 * 连同备注被无声抹掉。现在改为显式冲突检测：由调用方决定是否覆盖。
 */
class MoodRepository(private val store: MoodStore) {

    fun observeAll(): kotlinx.coroutines.flow.Flow<List<MoodEntry>> = store.observeAll()

    suspend fun findByDateHour(date: LocalDate, hour: Int): MoodEntry? =
        store.findByDateHour(date.toString(), hour)

    private fun buildEntry(date: LocalDate, hour: Int, moodId: Int, note: String, old: MoodEntry?) =
        MoodEntry(
            id = old?.id ?: 0,
            date = date.toString(),
            hour = hour,
            moodId = moodId,
            note = note.trim().take(MAX_NOTE_LENGTH),
            updatedAt = System.currentTimeMillis()
        )

    /**
     * 在同一事务内先查冲突再写入。目标时段已被**另一条**记录占用时返回
     * [SaveOutcome.Conflict]，交由 UI 询问用户，而不是直接覆盖。
     * 编辑自身记录（id 相同）不算冲突。
     */
    suspend fun save(date: LocalDate, hour: Int, moodId: Int, note: String, old: MoodEntry?): SaveOutcome =
        store.transaction {
            val existing = store.findByDateHour(date.toString(), hour)
            if (existing != null && existing.id != old?.id) {
                return@transaction SaveOutcome.Conflict(existing)
            }
            val entry = buildEntry(date, hour, moodId, note, old)
            if (old == null) store.insert(entry) else store.update(entry)
            SaveOutcome.Saved
        }

    /** 仅在用户于冲突弹窗中确认「覆盖」后调用：先删被覆盖的记录，再写入新记录。 */
    suspend fun overwrite(
        date: LocalDate, hour: Int, moodId: Int, note: String, old: MoodEntry?, conflict: MoodEntry
    ) = store.transaction {
        store.delete(conflict)
        val entry = buildEntry(date, hour, moodId, note, old)
        if (old == null) store.insert(entry) else store.update(entry)
    }

    suspend fun delete(entry: MoodEntry) = store.delete(entry)
}

class MoodViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = MoodRepository(RoomMoodStore(MoodDatabase.get(application)))

    val entries: StateFlow<List<MoodEntry>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun save(date: LocalDate, hour: Int, moodId: Int, note: String, old: MoodEntry?): SaveOutcome =
        repo.save(date, hour, moodId, note, old)

    suspend fun overwrite(
        date: LocalDate, hour: Int, moodId: Int, note: String, old: MoodEntry?, conflict: MoodEntry
    ) = repo.overwrite(date, hour, moodId, note, old, conflict)

    fun delete(entry: MoodEntry) = viewModelScope.launch { repo.delete(entry) }
}

data class Mood(val id: Int, val label: String, val emoji: String, val color: Color, val score: Int)

val moods = listOf(
    Mood(5, "开心", "😄", Color(0xFFFFB300), 5),
    Mood(4, "平静", "😌", Color(0xFF43A047), 4),
    Mood(3, "一般", "😐", Color(0xFF78909C), 3),
    Mood(2, "低落", "😔", Color(0xFF42A5F5), 2),
    Mood(1, "生气", "😡", Color(0xFFEF5350), 1)
)

fun moodOf(id: Int) = moods.firstOrNull { it.id == id } ?: moods[2]

class MainActivity : ComponentActivity() {
    private val openHour = mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openHour.value = intent.getIntExtra(EXTRA_HOUR, -1).takeIf { it >= 0 }
        val store = SettingsStore(this)
        store.syncReminder()
        setContent {
            val settings by store.settings.collectAsStateWithLifecycle(initialValue = store.current())
            MoodDiaryTheme(
                themeMode = settings.themeMode,
                accentColor = settings.accentColor,
                useDynamicColor = settings.useDynamicColor
            ) {
                MoodDiaryApp(
                    openHour = openHour,
                    settingsStore = store,
                    startTab = settings.startTab.index()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getIntExtra(EXTRA_HOUR, -1).takeIf { it >= 0 }?.let { openHour.value = it }
    }

    companion object {
        const val EXTRA_HOUR = "extra_hour"
    }
}

/**
 * 每个主题色的完整色板。
 *
 * 之前只覆盖 primary，而 Material3 组件大量使用 primaryContainer /
 * secondaryContainer / onXxxContainer（统计卡片、chip 选中底色、导航指示器等），
 * 导致换主题色后统计页等处仍是旧色。这里把整组都定义出来。
 */
data class AccentScheme(
    val lightPrimary: Long,
    val lightOnPrimary: Long,
    val lightContainer: Long,
    val lightOnContainer: Long,
    val darkPrimary: Long,
    val darkOnPrimary: Long,
    val darkContainer: Long,
    val darkOnContainer: Long
)

private val ACCENTS: Map<AccentColor, AccentScheme> = mapOf(
    AccentColor.AMBER to AccentScheme(
        lightPrimary = 0xFFE48600, lightOnPrimary = 0xFFFFFFFF,
        lightContainer = 0xFFFFDDB3, lightOnContainer = 0xFF2E1500,
        darkPrimary = 0xFFFFB95C, darkOnPrimary = 0xFF4A2800,
        darkContainer = 0xFF5D3F00, darkOnContainer = 0xFFFFDDB3
    ),
    AccentColor.BLUE to AccentScheme(
        lightPrimary = 0xFF2B54A8, lightOnPrimary = 0xFFFFFFFF,
        lightContainer = 0xFFD8E2FF, lightOnContainer = 0xFF001945,
        darkPrimary = 0xFFAEC6FF, darkOnPrimary = 0xFF002A65,
        darkContainer = 0xFF00306E, darkOnContainer = 0xFFD8E2FF
    ),
    AccentColor.GREEN to AccentScheme(
        lightPrimary = 0xFF2E7D5B, lightOnPrimary = 0xFFFFFFFF,
        lightContainer = 0xFFB0F0CE, lightOnContainer = 0xFF002115,
        darkPrimary = 0xFF5FC99A, darkOnPrimary = 0xFF003823,
        darkContainer = 0xFF00512F, darkOnContainer = 0xFFB0F0CE
    ),
    AccentColor.PURPLE to AccentScheme(
        lightPrimary = 0xFF7A4FA3, lightOnPrimary = 0xFFFFFFFF,
        lightContainer = 0xFFEFD9FF, lightOnContainer = 0xFF2A1150,
        darkPrimary = 0xFFC4A3E8, darkOnPrimary = 0xFF421B67,
        darkContainer = 0xFF4B2672, darkOnContainer = 0xFFEFD9FF
    ),
    AccentColor.PINK to AccentScheme(
        lightPrimary = 0xFFC2185B, lightOnPrimary = 0xFFFFFFFF,
        lightContainer = 0xFFFFD9E2, lightOnContainer = 0xFF3E001D,
        darkPrimary = 0xFFFF8CA8, darkOnPrimary = 0xFF5E1130,
        darkContainer = 0xFF732945, darkOnContainer = 0xFFFFD9E2
    )
)

@Composable
fun MoodDiaryTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentColor: AccentColor = AccentColor.AMBER,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = shouldUseDarkTheme(themeMode)
    val context = LocalContext.current
    val canDynamic = useDynamicColor && Build.VERSION.SDK_INT >= 31

    val colorScheme = when {
        canDynamic && dark -> dynamicDarkColorScheme(context)
        canDynamic && !dark -> dynamicLightColorScheme(context)
        else -> {
            val a = ACCENTS.getValue(accentColor)
            val primary = Color(if (dark) a.darkPrimary else a.lightPrimary)
            val onPrimary = Color(if (dark) a.darkOnPrimary else a.lightOnPrimary)
            val container = Color(if (dark) a.darkContainer else a.lightContainer)
            val onContainer = Color(if (dark) a.darkOnContainer else a.lightOnContainer)
            if (dark) darkColorScheme(
                primary = primary, onPrimary = onPrimary,
                primaryContainer = container, onPrimaryContainer = onContainer,
                secondary = primary, onSecondary = onPrimary,
                secondaryContainer = container, onSecondaryContainer = onContainer
            ) else lightColorScheme(
                primary = primary, onPrimary = onPrimary,
                primaryContainer = container, onPrimaryContainer = onContainer,
                secondary = primary, onSecondary = onPrimary,
                secondaryContainer = container, onSecondaryContainer = onContainer
            )
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** 待用户确认的覆盖请求 */
private data class PendingConflict(
    val date: LocalDate,
    val hour: Int,
    val moodId: Int,
    val note: String,
    val old: MoodEntry?,
    val conflict: MoodEntry
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MoodDiaryApp(
    vm: MoodViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    openHour: MutableState<Int?> = mutableStateOf(null),
    settingsStore: SettingsStore? = null,
    startTab: Int = 0
) {
    val context = LocalContext.current
    val store = settingsStore ?: remember { SettingsStore(context) }
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = store.current())
    val entries by vm.entries.collectAsStateWithLifecycle()
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var hourSheetDate by remember { mutableStateOf<LocalDate?>(null) }
    var editTarget by remember { mutableStateOf<Triple<LocalDate, Int, MoodEntry?>?>(null) }
    var pendingConflict by remember { mutableStateOf<PendingConflict?>(null) }
    val scope = rememberCoroutineScope()

    fun openEdit(date: LocalDate, hour: Int) {
        editTarget = Triple(date, hour, entries.firstOrNull { it.date == date.toString() && it.hour == hour })
    }

    // 点击通知跳转：直接打开当前小时的记录弹窗
    LaunchedEffect(openHour.value) {
        openHour.value?.let { h -> openEdit(LocalDate.now(), h); openHour.value = null }
    }

    val navItems = listOf(
        "日历" to Icons.Default.CalendarMonth,
        "记录" to Icons.Default.List,
        "统计" to Icons.Default.BarChart,
        "设置" to Icons.Default.Settings
    )

    // 分页器是「当前在哪一页」的唯一来源。
    // 之前同时保留 tab 状态 + pager，再用 LaunchedEffect 互相同步，
    // 形成回环：点击一项后，另一个 effect 又去滚回旧页，
    // 表现为卡住、跳到别页、或点一次不生效要点两次。
    // 现在点击直接滚动分页器，滑动自动反映到 currentPage，没有任何回写。
    val pagerState = rememberPagerState(
        initialPage = startTab,
        initialPageOffsetFraction = 0f,
        pageCount = { navItems.size }
    )

    // 记住最近一次的目标页：动画进行中重复点同一项不再重启动画
    // （animateScrollToPage 会取消并重建动画，连点会显得迟滞）
    var pendingPage by remember { mutableIntStateOf(startTab) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("心情日记", fontWeight = FontWeight.Bold) },
                actions = { if (pagerState.currentPage != 3) ReminderToggle(store) }
            )
        },
        // 悬浮胶囊导航：不贴边、圆角、四项等分
        bottomBar = {
            FloatingNavBar(
                items = navItems,
                selected = pagerState.currentPage,
                onSelect = { page ->
                    if (page != pendingPage || !pagerState.isScrollInProgress) {
                        pendingPage = page
                        scope.launch { pagerState.animateScrollToPage(page) }
                    }
                },
                // 长按高光椭圆拖动时用即时切换，避免连续动画互相打断
                onScrub = { page ->
                    scope.launch { pagerState.scrollToPage(page) }
                },
                showGlow = settings.immersiveGlow
            )
        },
        // 添加按钮回到右下角，但保留胶囊形状
        floatingActionButton = {
            Surface(
                onClick = { openEdit(LocalDate.now(), LocalTime.now().hour) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 0.dp,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add, "新增记录",
                        tint = Color.White, modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) { padding ->
        // 页面背景：在表面色上叠一层极淡的强调色渐变，
        // 作为玻璃面板的"背后"，否则同色半透明叠同色看不出玻璃感
        val bgScheme = MaterialTheme.colorScheme
        val bgDark = bgScheme.surface.luminance() < 0.5f
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            bgScheme.primary.copy(alpha = if (bgDark) 0.10f else 0.07f)
                                .compositeOver(bgScheme.surface),
                            bgScheme.surface,
                            bgScheme.secondary.copy(alpha = if (bgDark) 0.08f else 0.05f)
                                .compositeOver(bgScheme.surface)
                        )
                    )
                )
        ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top
        ) { page ->
            when (page) {
                0 -> CalendarPage(
                    month, entries,
                    weekStart = settings.weekStart,
                    showNote = settings.calendarShowNote,
                    setMonth = { month = it },
                    open = { d ->
                        when (settings.calendarTapAction) {
                            CalendarTapAction.OPEN_DAY_BOARD -> hourSheetDate = d
                            CalendarTapAction.QUICK_LOG_NOW ->
                                openEdit(LocalDate.now(), LocalTime.now().hour)
                        }
                    }
                )
                1 -> RecordsPage(entries) { e -> openEdit(LocalDate.parse(e.date), e.hour) }
                2 -> StatsPage(month, entries, { month = it })
                else -> SettingsPage(
                    store = store,
                    recordCount = entries.size,
                    onClearAll = {
                        scope.launch {
                            entries.forEach { vm.delete(it) }
                            toast(context, "已清空全部记录")
                        }
                    }
                )
            }
        }
        }
    }

    hourSheetDate?.let { d ->
        HourMoodSheet(
            d,
            entries.filter { it.date == d.toString() },
            onPick = { h, _ -> openEdit(d, h) },
            onClose = { hourSheetDate = null }
        )
    }

    editTarget?.let { (d, h, entry) ->
        MoodDialog(
            d, h, entry,
            defaultMoodId = settings.defaultMoodId,
            onDismiss = { editTarget = null },
            onSave = { dt, hh, m, n ->
                val old = editTarget?.third
                editTarget = null
                scope.launch {
                    when (val r = vm.save(dt, hh, m, n, old)) {
                        is SaveOutcome.Saved -> Unit
                        is SaveOutcome.Conflict -> pendingConflict = PendingConflict(dt, hh, m, n, old, r.existing)
                    }
                }
            },
            onDelete = { entry?.let(vm::delete); editTarget = null }
        )
    }

    pendingConflict?.let { p ->
        ConflictDialog(
            pending = p,
            onCancel = { pendingConflict = null },
            onOverwrite = {
                pendingConflict = null
                scope.launch { vm.overwrite(p.date, p.hour, p.moodId, p.note, p.old, p.conflict) }
            }
        )
    }
}

/**
 * 悬浮胶囊导航栏（四项等分）。
 *
 * 高光椭圆是独立浮层，绘制在导航栏之上，放大时可超出导航栏边界。
 *
 * 手势全部使用框架内置实现，不再自写长按/命中检测（自写版本连续出错三次）：
 * - 点击：内置 detectTapGestures，铺满导航栏
 * - 长按拖动：内置 detectDragGesturesAfterLongPress，**只挂在椭圆上**，
 *   因此只有按住椭圆才会放大并进入拖动，按其他位置不会触发
 *
 * 另外两点（此前踩过的坑）：
 * - 椭圆尺寸固定、放大只用 graphicsLayer 缩放，不参与布局测量，
 *   否则放大时会把底栏撑高，导航栏与角落按钮一起抖动
 * - 椭圆水平位置用补间动画，点按切换时平滑滑动而不是瞬移
 */
@Composable
fun FloatingNavBar(
    items: List<Pair<String, androidx.compose.ui.graphics.vector.ImageVector>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onScrub: (Int) -> Unit,
    showGlow: Boolean = true
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var scrubbing by remember { mutableStateOf(false) }
    var scrubIndex by remember { mutableIntStateOf(selected) }

    // 沉浸光感：记录当前被按住的按钮，用于绘制光晕并照亮邻近元素轮廓
    var glowIndex by remember { mutableStateOf<Int?>(null) }
    val glowEnabled = showGlow

    val currentSelected by rememberUpdatedState(selected)
    val currentOnScrub by rememberUpdatedState(onScrub)

    val highlightScale by animateFloatAsState(
        targetValue = if (scrubbing) HIGHLIGHT_LIFT_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "highlightScale"
    )

    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        val rowPad = 8.dp
        // 四项等宽，槽位中心可直接算出，无需测量
        val slotW = ((maxWidth - rowPad * 2) / items.size).coerceAtLeast(1.dp)
        fun centerX(i: Int) = rowPad + slotW * i + slotW / 2

        val activeIndex = if (scrubbing) scrubIndex else selected
        val accent = MaterialTheme.colorScheme.primary
        val slotPx = with(density) { slotW.toPx() }
        val rowPadPx = with(density) { rowPad.toPx() }

        // 椭圆水平位置（dp）。用普通状态 + 手动动画：
        // - 点击切换 / 松手归位：用 animate() 平滑滑动
        // - 拖动过程：直接赋值，零延迟跟随手指，因此绝对连贯
        //
        // 之前用 animateDpAsState + 80ms tween，拖动时每次跨格动画都重新启动，
        // 所以看起来一顿一顿的。
        val scope = rememberCoroutineScope()
        var pillX by remember { mutableFloatStateOf(0f) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        var firstLayout by remember { mutableStateOf(true) }

        fun pillTarget(i: Int) =
            with(density) { (centerX(i) - HIGHLIGHT_W / 2).toPx() }

        fun animatePillTo(target: Float) {
            settleJob?.cancel()
            settleJob = scope.launch {
                animate(
                    initialValue = pillX,
                    targetValue = target,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) { value, _ -> pillX = value }
            }
        }

        LaunchedEffect(activeIndex, maxWidth) {
            if (scrubbing) return@LaunchedEffect
            val target = pillTarget(activeIndex)
            if (firstLayout) {
                pillX = target
                firstLayout = false
            } else {
                animatePillTo(target)
            }
        }

        // ① 导航栏本体（纯展示）
        // 玻璃拟态导航条：强调色轻染 + 顶部反光 + 受光描边
        // （与设置页 GlassPanel 保持同一套观感）
        val scheme = MaterialTheme.colorScheme
        val glassDark = scheme.surface.luminance() < 0.5f
        val navGlassTop = if (glassDark) {
            scheme.primary.copy(alpha = 0.18f).compositeOver(scheme.surface)
        } else {
            Color.White.copy(alpha = 0.92f).compositeOver(scheme.primary.copy(alpha = 0.10f))
        }
        val navGlassBottom = if (glassDark) {
            scheme.primary.copy(alpha = 0.07f).compositeOver(scheme.surface)
        } else {
            Color.White.copy(alpha = 0.68f).compositeOver(scheme.primary.copy(alpha = 0.05f))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(navGlassTop, navGlassBottom)
                    )
                )
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = if (glassDark) 0.10f else 0.55f),
                        0.10f to Color.Transparent
                    )
                )
                .border(
                    1.dp,
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = if (glassDark) 0.28f else 0.95f),
                            scheme.primary.copy(alpha = 0.30f),
                            Color.White.copy(alpha = if (glassDark) 0.10f else 0.45f)
                        )
                    ),
                    RoundedCornerShape(percent = 50)
                )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = rowPad, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { idx, item ->
                    // 光感：光源按钮自身发光；其余按钮按「离光源多远」得到受光强度，
                    // 越近越亮，隔得远几乎不受影响 —— 像光洒在旁边的东西上
                    val src = glowIndex
                    val litAmount = if (glowEnabled && src != null) {
                        val d = kotlin.math.abs(idx - src)
                        when (d) {
                            1 -> 0.75f      // 紧邻：明显受光
                            2 -> 0.35f      // 隔一个：微弱余光
                            else -> 0f
                        }
                    } else 0f
                    NavItem(
                        label = item.first,
                        icon = item.second,
                        active = idx == activeIndex,
                        accent = accent,
                        glowing = glowEnabled && src == idx,
                        litAmount = litAmount,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ② 点击层：铺满导航栏，只处理点击
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(items.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val idx = ((down.position.x - rowPadPx) / slotPx)
                            .toInt().coerceIn(0, items.size - 1)
                        // 按下即点亮光感
                        if (glowEnabled) glowIndex = idx
                        var tapped = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { tapped = true; break }
                            val dx = ch.position.x - down.position.x
                            val dy = ch.position.y - down.position.y
                            if (dx * dx + dy * dy >
                                viewConfiguration.touchSlop * viewConfiguration.touchSlop
                            ) break
                        }
                        glowIndex = null          // 抬起/取消即熄灭
                        if (tapped) onSelect(idx)
                    }
                }
        )

        // ③ 高光椭圆：绘制在最上层，只在自己的范围内接收长按拖动
        val baseViewConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
        val shortViewConfig = remember(baseViewConfig) {
            ShortLongPressViewConfiguration(baseViewConfig)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalViewConfiguration provides shortViewConfig
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = with(density) { pillX.toDp() }, y = 0.dp)
                    .size(HIGHLIGHT_W, HIGHLIGHT_H)
                    .graphicsLayer {
                        scaleX = highlightScale
                        scaleY = highlightScale
                    }
                    .clip(RoundedCornerShape(percent = 50))
                    .background(accent.copy(alpha = 0.16f))
                    .border(1.5.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(percent = 50))
                    .pointerInput(items.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                scrubIndex = currentSelected
                                scrubbing = true
                                if (glowEnabled) glowIndex = currentSelected
                                // 打断进行中的归位动画，准备接管
                                settleJob?.cancel()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                scrubbing = false
                                glowIndex = null
                                // 松手：平滑吸附到当前所在槽位
                                animatePillTo(pillTarget(scrubIndex))
                            },
                            onDragCancel = {
                                scrubbing = false
                                glowIndex = null
                                animatePillTo(pillTarget(scrubIndex))
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (!scrubbing) return@detectDragGesturesAfterLongPress

                                // ① 连续跟手，并夹紧在首尾槽位之间，不会滑出导航栏
                                val minX = pillTarget(0)
                                val maxX = pillTarget(items.size - 1)
                                pillX = (pillX + dragAmount.x).coerceIn(minX, maxX)

                                // ② 由椭圆的连续位置直接反推目标槽位。
                                // 之前用「阈值累加」的写法有 bug：跨一格只需要
                                // slotPx/3，却把 acc 减掉一整格 slotPx，acc 立刻变成
                                // 大负数，紧接着触发反向循环又退回来，于是来回横跳、
                                // 永远拖不到最后一格。改用位置反推后天然单调。
                                val half = with(density) { HIGHLIGHT_W.toPx() } / 2f
                                val idx = ((pillX + half - rowPadPx - slotPx / 2f) / slotPx)
                                    .roundToInt()
                                    .coerceIn(0, items.size - 1)
                                if (idx != scrubIndex) {
                                    scrubIndex = idx
                                    currentOnScrub(idx)
                                }
                            }
                        )
                    }
            )
        }
    }
}

/**
 * 缩短长按阈值的 ViewConfiguration：系统默认约 500ms，对"长按拖动"偏高，
 * 这里压到 250ms。其余参数沿用系统值。
 */
private class ShortLongPressViewConfiguration(
    private val base: androidx.compose.ui.platform.ViewConfiguration
) : androidx.compose.ui.platform.ViewConfiguration by base {
    override val longPressTimeoutMillis: Long get() = 250L
}

/** 长按时椭圆放大的倍数 */
private const val HIGHLIGHT_LIFT_SCALE = 1.25f

/** 选中态高光椭圆的尺寸：四个位置统一 */
private val HIGHLIGHT_W = 66.dp
private val HIGHLIGHT_H = 48.dp

/**
 * 导航项：纯展示，点击与长按由上层统一处理。
 *
 * 沉浸光感（纯光效，不改变任何元素尺寸）：
 * - glowing：被按住的按钮 —— 自身发光
 * - litAmount：被邻近光源照亮的程度 0..1
 *
 * 关键：发光用**径向渐变**实现，不能用 Modifier.blur()。
 * blur 需要 API 31（Android 12）以上才生效，在 Android 10 等旧版本上是
 * 静默空操作，光晕会完全消失。径向渐变是纯着色器绘制，全版本可用。
 */
@Composable
private fun NavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    accent: Color,
    glowing: Boolean = false,
    litAmount: Float = 0f,
    modifier: Modifier = Modifier
) {
    val glow by animateFloatAsState(
        targetValue = if (glowing) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "navGlow"
    )
    val lit by animateFloatAsState(
        targetValue = litAmount.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 260),
        label = "navLit"
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        // ① 被照亮：极淡的受光底色（不放大、不加边框）
        if (lit > 0.01f) {
            Box(
                Modifier
                    .size(HIGHLIGHT_W, HIGHLIGHT_H)
                    .graphicsLayer { alpha = lit }
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
            )
        }

        // ② 自身发光：外周 + 内层两层径向渐变，自然衰减成柔光
        if (glow > 0.01f) {
            // 外层：范围大、很淡，负责"照亮周围"
            Box(
                Modifier
                    .size(HIGHLIGHT_W * 2.1f, HIGHLIGHT_H * 2.6f)
                    .graphicsLayer { alpha = glow * 0.9f }
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            0.00f to accent.copy(alpha = 0.38f),
                            0.35f to accent.copy(alpha = 0.20f),
                            0.65f to accent.copy(alpha = 0.07f),
                            1.00f to Color.Transparent
                        ),
                        RoundedCornerShape(percent = 50)
                    )
            )
            // 内层：紧贴按钮的一圈亮光
            Box(
                Modifier
                    .size(HIGHLIGHT_W * 1.18f, HIGHLIGHT_H * 1.32f)
                    .graphicsLayer { alpha = glow }
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            0.00f to accent.copy(alpha = 0.75f),
                            0.55f to accent.copy(alpha = 0.38f),
                            1.00f to Color.Transparent
                        ),
                        RoundedCornerShape(percent = 50)
                    )
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.size(HIGHLIGHT_W, HIGHLIGHT_H)
        ) {
            val tint = when {
                active -> accent
                glow > 0.01f -> accent
                lit > 0.01f -> androidx.compose.ui.graphics.lerp(
                    MaterialTheme.colorScheme.onSurfaceVariant, accent, lit * 0.55f
                )
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
            Text(
                label,
                fontSize = 11.sp,
                color = tint,
                fontWeight = if (active || glow > 0.01f) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/** 目标时段已被占用时的确认弹窗——用户明确选择后才覆盖 */
@Composable
private fun ConflictDialog(pending: PendingConflict, onCancel: () -> Unit, onOverwrite: () -> Unit) {
    val m = moodOf(pending.conflict.moodId)
    val slot = pending.date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")) +
        " " + String.format(Locale.CHINA, "%02d:00", pending.hour)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("该时段已有记录") },
        text = {
            Column {
                Text("$slot 已经记过一条心情：")
                Spacer(Modifier.height(8.dp))
                Text(
                    "${m.emoji} ${m.label}" +
                        if (pending.conflict.note.isBlank()) "" else " · ${pending.conflict.note}",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "继续保存会删除上面这条原有记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = { TextButton(onClick = onOverwrite) { Text("覆盖") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

@Composable
fun CalendarPage(
    month: YearMonth,
    entries: List<MoodEntry>,
    weekStart: WeekStart = WeekStart.SUNDAY,
    showNote: Boolean = false,
    setMonth: (YearMonth) -> Unit,
    open: (LocalDate) -> Unit
) {
    val latestByDay = remember(entries) {
        entries.groupBy { it.date }.mapValues { (_, list) -> list.maxByOrNull { it.hour } }
    }
    val weekLabels = if (weekStart == WeekStart.SUNDAY)
        listOf("日", "一", "二", "三", "四", "五", "六")
    else
        listOf("一", "二", "三", "四", "五", "六", "日")
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            TextButton(onClick = { setMonth(month.minusMonths(1)) }) { Text("‹ 上月") }
            Text(
                "${month.year}年${month.monthValue}月",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { setMonth(month.plusMonths(1)) }) { Text("下月 ›") }
        }
        TextButton(
            onClick = { setMonth(YearMonth.now()) },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("回到今天") }

        Row(Modifier.fillMaxWidth()) {
            weekLabels.forEach {
                Text(
                    it, Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        val start = month.atDay(1)
        // dayOfWeek.value: 周一=1 … 周日=7
        val paddingDays = if (weekStart == WeekStart.SUNDAY) {
            start.dayOfWeek.value % 7          // 周日排在首列
        } else {
            (start.dayOfWeek.value + 5) % 7    // 周一排在首列
        }
        val total = paddingDays + month.lengthOfMonth()
        val rows = (total + 6) / 7
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val day = row * 7 + col - paddingDays + 1
                    Box(Modifier.weight(1f).aspectRatio(0.82f).padding(2.dp)) {
                        if (day in 1..month.lengthOfMonth()) {
                            val d = month.atDay(day)
                            CalendarCell(
                                d, latestByDay[d.toString()],
                                today = d == LocalDate.now(),
                                showNote = showNote
                            ) { open(d) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("点击某天 → 按小时记录心情", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "颜色说明：" + moods.joinToString("  ") { "${it.emoji}${it.label}" },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CalendarCell(
    date: LocalDate,
    entry: MoodEntry?,
    today: Boolean,
    showNote: Boolean = false,
    click: () -> Unit
) {
    val mood = entry?.let { moodOf(it.moodId) }
    val bg = mood?.color ?: MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (mood != null && bg.luminance() < .55f) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(
                if (today) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier
            )
            .clickable(onClick = click),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                color = textColor,
                fontWeight = if (today) FontWeight.Bold else FontWeight.Normal
            )
            if (mood != null) Text(mood.emoji, fontSize = 15.sp)
            // 可选：在格子里显示备注摘要
            if (showNote && entry != null && entry.note.isNotBlank()) {
                Text(
                    entry.note.trim(),
                    fontSize = 6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = textColor.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HourMoodSheet(
    date: LocalDate,
    dayEntries: List<MoodEntry>,
    onPick: (Int, MoodEntry?) -> Unit,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                "${date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))} 的心情",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "点一个小时，记录那个时刻的心情",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(440.dp)
            ) {
                items((0..23).toList()) { h ->
                    val e = dayEntries.firstOrNull { it.hour == h }
                    val m = e?.let { moodOf(it.moodId) }
                    val onColor =
                        if (m != null && m.color.luminance() < .55f) Color.White
                        else MaterialTheme.colorScheme.onSurface
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(m?.color ?: MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPick(h, e) }
                            .padding(vertical = 10.dp)
                            .fillMaxWidth()
                    ) {
                        Text(String.format(Locale.CHINA, "%02d:00", h), fontSize = 11.sp, color = onColor)
                        Text(if (m != null) m.emoji else "＋", fontSize = 20.sp)
                        Text(
                            if (m != null) m.label else "未记录",
                            fontSize = 10.sp,
                            color = if (m != null) onColor else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecordsPage(entries: List<MoodEntry>, open: (MoodEntry) -> Unit) {
    if (entries.isEmpty()) {
        EmptyState("还没有记录", "点击右下角 +，或从日历选一天按小时记录")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(entries, key = { it.id }) { e ->
            val m = moodOf(e.moodId)
            Card(Modifier.fillMaxWidth().clickable { open(e) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.emoji, fontSize = 30.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${e.date.substring(5)} ${String.format(Locale.CHINA, "%02d:00", e.hour)}",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (e.note.isBlank()) m.label else e.note,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.Edit, "编辑这条记录", tint = m.color)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsPage(month: YearMonth, entries: List<MoodEntry>, setMonth: (YearMonth) -> Unit) {
    val inMonth = entries.filter {
        runCatching { YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == month
    }
    val latestByDay = inMonth.groupBy { it.date }.mapValues { (_, list) -> list.maxByOrNull { it.hour }!! }
    val days = latestByDay.values
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // —— 月份切换 + 主色横幅 ——
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            TextButton(onClick = { setMonth(month.minusMonths(1)) }) { Text("‹ 上月") }
            Text(
                "${month.year} 年 ${month.monthValue} 月",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { setMonth(month.plusMonths(1)) }) { Text("下月 ›") }
        }

        if (days.isEmpty()) {
            EmptyState("本月还没有心情记录", "按小时记下心情后，这里会展示你的情绪分布")
            return@Column
        }

        val avg = days.map { moodOf(it.moodId).score }.average()
        // 占比最高的心情
        val topMood = moods.maxByOrNull { m -> days.count { it.moodId == m.id } }
        val topCount = days.count { it.moodId == topMood!!.id }

        // 主色横幅：一眼看到这个月的概况
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(accent)
                .padding(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${topMood!!.emoji} 主要情绪是${topMood.label}",
                        color = onAccent, fontWeight = FontWeight.Bold, fontSize = 18.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "共记录 ${inMonth.size} 条 · ${days.size} 天",
                        color = onAccent.copy(alpha = 0.85f), fontSize = 13.sp
                    )
                    Text(
                        "平均 ${String.format(Locale.CHINA, "%.1f", avg)} / 5 分",
                        color = onAccent.copy(alpha = 0.85f), fontSize = 13.sp
                    )
                }
                // 大号 emoji
                Text(topMood!!.emoji, fontSize = 52.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        // —— 情绪分布（用主色调进度条，且右侧显示百分比） ——
        Text(
            "情绪分布",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            moods.forEach { m ->
                val count = days.count { it.moodId == m.id }
                val pct = if (days.isEmpty()) 0f else count.toFloat() / days.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.emoji, fontSize = 22.sp, modifier = Modifier.width(34.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(m.label, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${(pct * 100).toInt()}%",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                Modifier.fillMaxWidth(pct.coerceIn(0.01f, 1f)).height(8.dp)
                                    .clip(CircleShape).background(m.color)
                            )
                        }
                    }
                    Text(
                        "${count} 天",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // —— 心情热力条 ——
        Text(
            "本月心情热力条",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (1..month.lengthOfMonth()).forEach { day ->
                val e = latestByDay[month.atDay(day).toString()]
                val mood = e?.let { moodOf(it.moodId) }
                val isToday = month.atDay(day) == LocalDate.now()
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(mood?.color ?: MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (isToday) Modifier.border(
                                2.dp, accent, RoundedCornerShape(12.dp)
                            ) else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            day.toString(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (mood != null && mood.color.luminance() < .55f) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (mood != null) Text(mood.emoji, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("✦", fontSize = 48.sp)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            subtitle, Modifier.padding(16.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MoodDialog(
    date: LocalDate,
    hour: Int,
    entry: MoodEntry?,
    defaultMoodId: Int = 5,
    onDismiss: () -> Unit,
    onSave: (LocalDate, Int, Int, String) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    // 关键：remember 的 key 必须包含日期与小时。
    // 否则连续打开两个都无记录的时段时（entry 均为 null），
    // remember 不会重置，上一段输入的心情/备注会残留到下一次弹窗。
    var d by remember(entry?.id, date) { mutableStateOf(date) }
    var selected by remember(entry?.id, date, hour) { mutableIntStateOf(entry?.moodId ?: defaultMoodId) }
    var note by remember(entry?.id, date, hour) { mutableStateOf(entry?.note ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "记录心情" else "编辑心情") },
        text = {
            Column {
                OutlinedButton(
                    onClick = {
                        val c = d
                        DatePickerDialog(
                            context,
                            { _, y, m, day -> d = LocalDate.of(y, m + 1, day) },
                            c.year, c.monthValue - 1, c.dayOfMonth
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("日期：${d.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))}") }

                Spacer(Modifier.height(8.dp))
                Text(
                    "时间：${String.format(Locale.CHINA, "%02d:00-%02d:59", hour, hour)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))
                Text("心情是？")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    moods.forEach { m ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (selected == m.id) Modifier.background(m.color.copy(alpha = .25f))
                                    else Modifier
                                )
                                .clickable { selected = m.id }
                                .padding(5.dp)
                        ) {
                            Text(m.emoji, fontSize = 25.sp)
                            Text(m.label, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_NOTE_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("写点备注（可选）") },
                    supportingText = { Text("${note.length} / $MAX_NOTE_LENGTH") },
                    minLines = 3
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(d, hour, selected, note) }) { Text("保存") } },
        dismissButton = {
            Row {
                if (entry != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, null)
                        Text("删除")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
fun ReminderToggle(store: SettingsStore) {
    val context = LocalContext.current
    // 状态由设置统一管理，避免顶栏开关与设置页出现两份真值不同步
    val reminderEnabled by produceState(initialValue = store.current().reminderEnabled, store) {
        store.settings.collect { value = it.reminderEnabled }
    }

    fun enable() {
        store.setReminderEnabled(true)
        toast(context, "已开启每小时心情提醒")
    }

    val permLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enable() else toast(context, "需要通知权限才能提醒你记录心情")
        }

    IconButton(onClick = {
        when {
            reminderEnabled -> {
                store.setReminderEnabled(false)
                toast(context, "已关闭每小时心情提醒")
            }
            Reminder.hasNotificationPermission(context) -> enable()
            else -> permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }) {
        Icon(
            if (reminderEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
            if (reminderEnabled) "关闭每小时提醒" else "开启每小时提醒",
            tint = if (reminderEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun toast(context: android.content.Context, msg: String) =
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
