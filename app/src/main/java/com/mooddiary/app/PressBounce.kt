package com.mooddiary.app

/**
 * Q 弹按钮（1:1 移植 liquid_glass_widgets sample 的 GlassButton / LiquidStretch）。
 *
 * 上游构成（MIT License：Copyright 2025 Tim Lehmann / 2026 Sebastian Degenaar）：
 *
 * 1. 按下膨胀（nativePressGrowth = 17pt，施加在最长边上的中心等比缩放）
 *    - 按下弹簧：snappy(250ms, extraBounce 0.1) → bounce 0.25
 *      → dampingRatio 0.75、stiffness 4π²/0.25² = 631.65
 *    - 松开弹簧：bouncy(280ms, extraBounce 0.15) → bounce 0.45
 *      → dampingRatio 0.55、stiffness 4π²/0.28² = 503.55
 *      （56dp 圆钮按下 → ×1.30，松手回弹略带 undershoot）
 *
 * 2. 锚点拉伸（anchorStretch，拖动时按钮朝手指方向延展、垂直方向被挤压）
 *    - 拖动弹簧：interactive(150ms, extraBounce 0.01) → bounce 0.15
 *      → dampingRatio 0.85、stiffness 4π²/0.15² = 1754.6
 *    - 松手回弹：bouncy(500ms, extraBounce 0.15) → bounce 0.45
 *      → dampingRatio 0.55、stiffness 4π²/0.5² = 157.9
 *    - AnchorStretchSettings：intensity 0.5 / squashFactor 0.3 /
 *      translationDamping 0.15 / bounciness 0.15
 *    - resistance 0.01 非线性阻尼：mag → mag/(1+mag×r)
 *    - 位移乘数 stretch 0.5；pivot 用 signedSmoothStep(dx, 20px) 平滑
 *    - 延展上限 0.3、挤压上限 0.15；长轴按 aspectRatio 衰减
 *
 * 3. 环境光提亮（ambientBaseLight）：白蒙版 alpha = 提亮曲线 × 0.3（浅色）
 *    / 0.14（深色）；提亮曲线 150ms easeOut 升起、60ms 消退
 *    （上游 AnimationController 150ms / reverseDuration 60ms）
 *
 * 4. 触点辉光：半径 1.6×短边的白色柔光（上游 blur sigma 16，
 *    此处用多停靠点径向渐变近似），跟随手指（钳制在边界内），
 *    强度 = 提亮曲线；颜色白 0x1A（浅色）/ 0x12（深色）
 *
 * 变换合成（与上游矩阵链逐一对应）：
 *   上游 M = T(c)·S(ps)·T(-c) · T(p)·S(s)·T(-p)·T(t)
 *   即「先锚点链、后中心膨胀」。Compose 用三层嵌套 graphicsLayer
 *   等价实现（外层后作用于点）：
 *     L1 最外层：中心等比膨胀（pressScale）
 *     L2 中间层：pivot 缩放（TransformOrigin = 锚点）
 *     L3 最内层：纯平移（scale=1，无合成歧义）
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ── 上游参数（1:1） ─────────────────────────────────────────────────────────

/** nativePressGrowth：按下时最长边的增长量（pt → dp） */
private const val PRESS_GROWTH_DP = 17f

/** 位移 → 拉伸的乘数（LiquidStretch.stretch 默认值） */
private const val STRETCH = 0.5f

/** 拖动阻尼（LiquidStretch.resistance 默认值） */
private const val DRAG_RESISTANCE = 0.01f

/** AnchorStretchSettings 默认值 */
private const val ANCHOR_INTENSITY = 0.5f
private const val ANCHOR_SQUASH_FACTOR = 0.3f
private const val ANCHOR_TRANSLATION_DAMPING = 0.15f

/** pivot 平滑边界（signedSmoothStep 的 edge，px） */
private const val PIVOT_EDGE_PX = 20f

/** 延展 / 挤压上限 */
private const val ELONGATION_CAP = 0.3f
private const val SQUASH_CAP = 0.15f

/** 辉光半径 = 1.6 × 最短边（上游 iOS 26 校准值） */
private const val GLOW_RADIUS_FACTOR = 1.6f

/** GlassDefaults：ambientBaseLight / dark / lift 时长 */
private const val AMBIENT_LIGHT = 0.3f
private const val AMBIENT_LIGHT_DARK = 0.14f
private const val AMBIENT_LIFT_MS = 150
private const val AMBIENT_LIFT_REVERSE_MS = 60

/** 原生辉光色：浅色模式白 0x1A、深色模式白 0x12 */
private val GLOW_COLOR_LIGHT = Color(0x1AFFFFFF)
private val GLOW_COLOR_DARK = Color(0x12FFFFFF)

// ── 弹簧换算（Flutter SpringDescription.withDurationAndBounce → Compose） ──
// 上游公式（mass = 1）：stiffness = 4π² / duration²；
// dampingRatio = bounce > 0 ? 1 - bounce : 1 / (bounce + 1)

/** 按下：snappy(250ms, extraBounce 0.1) → bounce 0.25 */
private val PRESS_IN_SPRING: SpringSpec<Float> =
    spring(dampingRatio = 0.75f, stiffness = 631.65f)

/** 松开：bouncy(280ms, extraBounce 0.15) → bounce 0.45 */
private val PRESS_OUT_SPRING: SpringSpec<Float> =
    spring(dampingRatio = 0.55f, stiffness = 503.55f)

/** 拖动跟随：interactive(150ms, extraBounce 0.01) → bounce 0.15 */
private val DRAG_SPRING: SpringSpec<Float> =
    spring(dampingRatio = 0.85f, stiffness = 1754.6f)

/** 松手回弹：bouncy(500ms, extraBounce 0.15) → bounce 0.45 */
private val DRAG_RELEASE_SPRING: SpringSpec<Float> =
    spring(dampingRatio = 0.55f, stiffness = 157.9f)

// ── 上游工具函数 ────────────────────────────────────────────────────────────

/**
 * withResistance：方向不变、幅度按 mag/(1+mag×r) 压缩
 * （上游 _StretchOffset.withResistance）——越拖越费劲的橡皮筋手感
 */
private fun Offset.applyResistance(resistance: Float): Offset {
    if (resistance == 0f) return this
    val mag = getDistance()
    if (mag <= 0f) return this
    val k = (mag / (1f + mag * resistance)) / mag
    return Offset(x * k, y * k)
}

/**
 * signedSmoothStep：[-edge..+edge] → [-1..+1] 平滑三次曲线
 * （上游 RenderRawLiquidStretch._signedSmoothStep）——
 * 拖动方向在零附近反转时让 pivot 平滑滑过中心，避免位置跳变
 */
private fun signedSmoothStep(x: Float, edge: Float): Float {
    val t = (x / edge).coerceIn(-1f, 1f)
    val a = abs(t)
    val s = a * a * (3f - 2f * a)
    return if (t >= 0f) s else -s
}

// ── Q 弹按钮 ────────────────────────────────────────────────────────────────

/**
 * 带 Q 弹交互的表面按钮（1:1 上游 GlassButton 交互层）。
 *
 * @param onClick 点击回调（手指未越过 touchSlop 即松手时触发）
 * @param shape 形状（辉光与环境光蒙版均按此形状裁剪）
 * @param color 表面底色
 * @param ambientBaseLight 环境光提亮强度；null = 按底色亮度自动选
 *        0.3（浅色）/ 0.14（深色）——上游 GlassTheme.brightnessOf 行为
 * @param glowColor 辉光颜色；null = 白 0x1A（浅色）/ 0x12（深色）
 * @param enabled false 时禁用全部交互与动画
 */
@Composable
fun BouncyPressSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    ambientBaseLight: Float? = null,
    glowColor: Color? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    // 环境明暗由底色亮度判定（上游 GlassTheme.brightnessOf(context)）
    val dark = color.luminance() < 0.5f
    val ambient = ambientBaseLight ?: if (dark) AMBIENT_LIGHT_DARK else AMBIENT_LIGHT
    val glow = glowColor ?: if (dark) GLOW_COLOR_DARK else GLOW_COLOR_LIGHT

    // 三个弹簧通道（Animatable 的逐轴弹簧与上游 VectorizedSpringSpec 等价）。
    // 状态对象而非 by 委托：绘制阶段（drawBehind / graphicsLayer lambda）
    // 读 .value 只触发重绘，不触发重组
    val press = remember { Animatable(0f) }
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    var pressed by remember { mutableStateOf(false) }
    var glowPos by remember { mutableStateOf<Offset?>(null) }

    // 环境光提亮曲线：150ms easeOut 升起 / 60ms 消退
    // （上游 _saturationController + CurvedAnimation(easeOut)）
    val ambientLift by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (pressed && enabled) AMBIENT_LIFT_MS else AMBIENT_LIFT_REVERSE_MS,
            easing = EaseOut
        ),
        label = "ambientLift"
    )

    Box(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    // 辉光锚在按下点（本地坐标；pointerInput 在变换层之外，
                    // 坐标与 drawBehind 的绘制空间一致）
                    glowPos = Offset(down.position.x, down.position.y)
                    scope.launch { press.animateTo(1f, PRESS_IN_SPRING) }

                    var drag = Offset.Zero
                    var slopBreached = false
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        // Compose 1.6.8 无 changedToUp/changedToDownIgnoreConsumed
                        // （1.7+ API）：pressed=false 即覆盖 抬起/取消/移出 三种终止
                        if (change == null || !change.pressed) {
                            finished = true
                        } else {
                            drag += change.positionChange()
                            change.consume()      // 阻止下层（Pager 等）抢走手势
                            if (!slopBreached &&
                                drag.getDistance() > viewConfiguration.touchSlop
                            ) {
                                slopBreached = true
                            }
                            if (slopBreached) {
                                glowPos = Offset(change.position.x, change.position.y)
                                // resistance 0.01 → stretch 0.5 → 逐轴弹簧跟随
                                val target = drag.applyResistance(DRAG_RESISTANCE)
                                scope.launch { dragX.animateTo(target.x * STRETCH, DRAG_SPRING) }
                                scope.launch { dragY.animateTo(target.y * STRETCH, DRAG_SPRING) }
                            }
                        }
                    }

                    // 松开：膨胀回弹（bouncy 280ms）+ 位移回弹（bouncy 500ms）
                    pressed = false
                    scope.launch { press.animateTo(0f, PRESS_OUT_SPRING) }
                    scope.launch { dragX.animateTo(0f, DRAG_RELEASE_SPRING) }
                    scope.launch { dragY.animateTo(0f, DRAG_RELEASE_SPRING) }
                    if (!slopBreached) onClick()
                }
            }
            // L1 最外层：按下膨胀（中心等比，作用于最长边）
            .graphicsLayer {
                val longest = max(size.width, size.height).coerceAtLeast(1f)
                val growthPx = PRESS_GROWTH_DP.dp.toPx()
                val ps = 1f + press.value * growthPx / longest
                scaleX = ps
                scaleY = ps
            }
            // L2 中间层：锚点拉伸（pivot 缩放 + 长轴 aspectRatio 衰减）
            .graphicsLayer {
                val w = size.width
                val h = size.height
                val dx = dragX.value
                val dy = dragY.value
                val absDx = abs(dx)
                val absDy = abs(dy)
                if (absDx > 0.01f || absDy > 0.01f) {
                    val refDim = max(w, h).coerceAtLeast(1f)
                    val relX = absDx / refDim
                    val relY = absDy / refDim
                    val aspect = (w / h.coerceAtLeast(1f)).coerceAtLeast(0.01f)
                    val xDamp = if (aspect > 1f) 1f / aspect else 1f
                    val yDamp = if (aspect < 1f) aspect else 1f
                    val stretchX = 1f +
                        (relX * ANCHOR_INTENSITY * xDamp).coerceAtMost(ELONGATION_CAP)
                    val stretchY = 1f +
                        (relY * ANCHOR_INTENSITY * yDamp).coerceAtMost(ELONGATION_CAP)
                    val squashX = 1f -
                        (relY * ANCHOR_SQUASH_FACTOR * xDamp).coerceAtMost(SQUASH_CAP)
                    val squashY = 1f -
                        (relX * ANCHOR_SQUASH_FACTOR * yDamp).coerceAtMost(SQUASH_CAP)
                    val pivotX = w * (0.5f - signedSmoothStep(dx, PIVOT_EDGE_PX) * 0.5f)
                    val pivotY = h * (0.5f - signedSmoothStep(dy, PIVOT_EDGE_PX) * 0.5f)
                    transformOrigin = TransformOrigin(pivotX / w, pivotY / h)
                    scaleX = (stretchX * squashX).coerceAtLeast(0.01f)
                    scaleY = (stretchY * squashY).coerceAtLeast(0.01f)
                } else {
                    scaleX = 1f
                    scaleY = 1f
                }
            }
            // L3 最内层：跟随手指的阻尼平移（scale=1，无合成歧义）
            .graphicsLayer {
                translationX = dragX.value * ANCHOR_TRANSLATION_DAMPING
                translationY = dragY.value * ANCHOR_TRANSLATION_DAMPING
            }
            .clip(shape)
            .background(color)
            .drawBehind {
                // 触点辉光：1.6×短边柔光，跟随手指，强度 = 提亮曲线。
                // 上游为径向渐变 + sigma 16 高斯模糊，此处以多停靠点
                // 径向渐变近似（Compose 绘制期无等价模糊）
                val intensity = ambientLift
                val pos = glowPos
                if (intensity > 0.001f && pos != null) {
                    val cx = pos.x.coerceIn(0f, size.width)
                    val cy = pos.y.coerceIn(0f, size.height)
                    val radius = min(size.width, size.height) * GLOW_RADIUS_FACTOR
                    val center = Offset(cx, cy)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.00f to glow.copy(alpha = intensity),
                                0.35f to glow.copy(alpha = intensity * 0.55f),
                                0.65f to glow.copy(alpha = intensity * 0.18f),
                                1.00f to Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
            },
        contentAlignment = contentAlignment
    ) {
        // 环境光提亮蒙版（在图标之下，保持图标清晰——上游 ambientOverlay 顺序）
        if (ambientLift > 0.001f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Color.White.copy(alpha = (ambient * ambientLift).coerceIn(0f, 1f))
                    )
            )
        }
        content()
    }
}
