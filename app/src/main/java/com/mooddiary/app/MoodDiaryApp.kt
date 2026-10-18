package com.mooddiary.app

import android.app.Application
import android.content.Context
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/**
 * 导航栏 Flutter 引擎的宿主单例。
 *
 * 预热时机：[MoodDiaryApp.onCreate]——Application 启动即创建引擎并执行
 * Dart 入口（navBarMain），避免用户看到导航栏区域白屏。
 *
 * 引擎为进程级单例：Activity 重建（旋转/主题切换）时复用同一 isolate，
 * 只有 FlutterView 会 detach/attach。
 */
object FlutterNavHost {

    /** 桥接通道名（与 Dart 侧 navBarChannel 一致）。 */
    const val CHANNEL = "com.mooddiary/navbar"

    /** Dart 入口函数名（@pragma('vm:entry-point')）。 */
    private const val ENTRYPOINT = "navBarMain"

    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null

    // ── Dart → 原生 回调（Activity 注册，销毁时清空） ──
    var onSelect: ((Int) -> Unit)? = null
    var onScrub: ((Int, Float) -> Unit)? = null
    var onScrubEnd: ((Int) -> Unit)? = null
    var onReady: (() -> Unit)? = null

    @Synchronized
    fun obtainEngine(context: Context): FlutterEngine {
        engine?.let { return it }
        val e = FlutterEngine(context.applicationContext)
        // 执行 Dart 入口（navBarMain）：初始化上游玻璃 + 注册通道 handler。
        e.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint(
                FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                ENTRYPOINT
            )
        )
        val ch = MethodChannel(e.dartExecutor.binaryMessenger, CHANNEL)
        ch.setMethodCallHandler { call, result ->
            when (call.method) {
                "onSelect" -> {
                    val i = (call.arguments as? Map<*, *>)?.get("index") as? Int
                    if (i != null) onSelect?.invoke(i)
                    result.success(null)
                }
                "scrub" -> {
                    val args = call.arguments as? Map<*, *>
                    val page = args?.get("page") as? Int
                    val fraction = (args?.get("fraction") as? Number?)?.toFloat()
                    if (page != null && fraction != null) onScrub?.invoke(page, fraction)
                    result.success(null)
                }
                "scrubEnd" -> {
                    val page = (call.arguments as? Map<*, *>)?.get("page") as? Int
                    if (page != null) onScrubEnd?.invoke(page)
                    result.success(null)
                }
                "ready" -> {
                    onReady?.invoke()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
        engine = e
        channel = ch
        return e
    }

    /** 原生 → Dart：同步当前选中页。 */
    fun setTab(index: Int) {
        channel?.invokeMethod("setTab", mapOf("index" to index))
    }

    /** 原生 → Dart：同步主题与背景渐变色。 */
    fun setTheme(dark: Boolean, bgTopArgb: Int, bgBottomArgb: Int) {
        channel?.invokeMethod(
            "setTheme",
            mapOf("dark" to dark, "bgTop" to bgTopArgb, "bgBottom" to bgBottomArgb)
        )
    }
}

/**
 * 应用入口：进程启动即预热导航栏 Flutter 引擎。
 *
 * 注意：Flutter 模块构建产物位于 flutter_module/，首次构建本 App 前
 * 需在该目录执行过一次 `flutter pub get`（生成 .android/local.properties，
 * Gradle 集成脚本依赖其中的 flutter.sdk 路径）。
 */
class MoodDiaryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FlutterNavHost.obtainEngine(this)
    }
}
