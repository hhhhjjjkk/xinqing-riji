package com.mooddiary.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.flutter.embedding.android.FlutterView

/**
 * 导航栏容器：把 Flutter 模块（mooddiary_navbar）渲染的玻璃导航栏
 * 嵌入 Compose 底部（混编）。
 *
 * 与 Flutter 的桥接（[FlutterNavHost]）：
 *   - selected / 主题色变化 → invoke setTab / setTheme（原生 → Dart）
 *   - Dart → 原生：onSelect（点击）、scrub（拖动每帧）、scrubEnd（松手）
 *
 * 引擎是进程级单例（Application 预热）；本组件只负责
 * FlutterView 的 attach/detach 与 Activity 生命周期转发。
 */
@Composable
fun FlutterNavBar(
    selected: Int,
    dark: Boolean,
    bgTop: Color,
    bgBottom: Color,
    onSelect: (Int) -> Unit,
    onScrub: (Int, Float) -> Unit,
    onScrubEnd: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val engine = remember(context) { FlutterNavHost.obtainEngine(context) }
    val channel = remember(engine) {
        io.flutter.plugin.common.MethodChannel(
            engine.dartExecutor.binaryMessenger, FlutterNavHost.CHANNEL
        )
    }

    // Dart → 原生回调：随本组件注册/注销（Activity 销毁时置空，避免泄漏）
    DisposableEffect(Unit) {
        FlutterNavHost.onSelect = onSelect
        FlutterNavHost.onScrub = onScrub
        FlutterNavHost.onScrubEnd = onScrubEnd
        // Dart handler 就绪后补发当前状态：防止引擎预热完成前
        // 首次组合的 setTab/setTheme 早于 Dart 注册 handler 而丢失
        FlutterNavHost.onReady = {
            channel.invokeMethod("setTab", mapOf("index" to selected))
            channel.invokeMethod(
                "setTheme",
                mapOf(
                    "dark" to dark,
                    "bgTop" to bgTop.toArgb(),
                    "bgBottom" to bgBottom.toArgb()
                )
            )
        }
        onDispose {
            FlutterNavHost.onSelect = null
            FlutterNavHost.onScrub = null
            FlutterNavHost.onScrubEnd = null
            FlutterNavHost.onReady = null
        }
    }

    // 选中页变化（分页器滑动/点击）→ Dart 侧药丸位置
    LaunchedEffect(selected) {
        channel.invokeMethod("setTab", mapOf("index" to selected))
    }

    // 主题/背景色变化 → Dart 侧玻璃配色
    LaunchedEffect(dark, bgTop, bgBottom) {
        channel.invokeMethod(
            "setTheme",
            mapOf(
                "dark" to dark,
                "bgTop" to bgTop.toArgb(),
                "bgBottom" to bgBottom.toArgb()
            )
        )
    }

    // Activity 生命周期 → 引擎（Flutter 的 AppLifecycleState 依赖它）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> engine.lifecycleChannel.appIsResumed()
                Lifecycle.Event.ON_PAUSE -> engine.lifecycleChannel.appIsInactive()
                Lifecycle.Event.ON_STOP -> engine.lifecycleChannel.appIsPaused()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FlutterView(ctx).apply { attachToFlutterEngine(engine) }
        },
        onRelease = { view ->
            view.detachFromFlutterEngine()
        }
    )
}
