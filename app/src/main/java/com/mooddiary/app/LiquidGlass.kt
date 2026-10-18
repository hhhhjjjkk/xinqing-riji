package com.mooddiary.app

import android.graphics.Color as AndroidColor
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（liquid_glass_widgets premium 档 1:1 移植）。
 *
 * 视觉构成与上游 `shaders/liquid_glass_render.frag` 逐项对应：
 *   - 几何：SDF 解析求表面法线 / 斜面高度（上游为预烘焙 geometry 纹理，
 *     此处形状限定为胶囊/圆角矩形，解析式数学等价）
 *   - 光学：Snell 折射（spherical / paraxial 双模型）、RGB 色散
 *   - 材质：iOS 26 luminosity-preserving tint、亮度自适应饱和度、
 *     白化面纱（luminance-gated）、bodyShade
 *   - 边缘：meniscus 暗环（半球剖面 + 光照调制吸收）、方向性高光、
 *     Fresnel、ambientRim 光环、rim light 双瓣、hairline 暗线
 *   - 交互：concave pinch（L4 squircle 透镜）、触点高光（x⁶ specular）
 *
 * 运行平台分支：
 *   - API 33+：AGSL RuntimeShader 全效果
 *   - API 24-32：Compose 分层玻璃降级（染色斜面 + 顶部反光 + 方向性描边），
 *     无折射扭曲——AGSL 需 Android 13，低版本无等价能力
 *
 * 关于「背后内容」：上游 Flutter 用 BackdropFilter 捕获背后真实像素；
 * Compose 没有跨 layer 捕获 API，本实现把背景（屏幕渐变/纯色）
 * 作为子图层喂给 shader 的 uBackground 采样器——折射、色散、光照
 * 等全部光学计算 1:1，但背后若有真实内容细节（图片、文字），
 * 该细节不参与折射（Android 平台限制）。
 *
 * 上游 MIT License：Copyright 2025 Tim Lehmann / 2026 Sebastian Degenaar
 */

// ── 上游 uniform 参数 ──────────────────────────────────────────────────────

/** uOpticalProps：折射率 / 色散 / 厚度（斜面半径，dp）/ 折射位移缩放 */
data class GlassOptics(
    val refractiveIndex: Float = 1.35f,
    val chromaticAberration: Float = 0.02f,
    /** 斜面半径（dp，最敏感参数，上游默认 28） */
    val thickness: Dp = 28.dp,
    /** DPR 归一化系数：上游 uOpticalProps.w 保证不同 DPR 折射量一致 */
    val refractScale: Float = 1f,
    /** false = spherical（精确 Snell），true = paraxial（薄棱镜近似） */
    val paraxialLens: Boolean = false
)

/** uLightConfig + uLightDirection：方向光 / 环境光 / 饱和度 */
data class GlassLight(
    val intensity: Float = 0.5f,
    val ambient: Float = 0.1f,
    val saturation: Float = 1.5f,
    /**
     * 光源方向（归一化，指向光源）。
     * 上游 lightAngle 默认 0.75π，render object 换算
     * uLightDirection = (cos(a), -sin(a)) = (-0.7071, -0.7071)（左上）
     */
    val directionX: Float = -0.7071f,
    val directionY: Float = -0.7071f
)

/** uEdgeConfig：ambientRim 光环宽度 / Fresnel 强度 / 边缘吸收 */
data class GlassEdge(
    val ambientRim: Float = 0f,
    val fresnelStrength: Float = 1f,
    /** uEdgeConfig.w：meniscus 暗环吸收强度（上游默认 0，样本导航栏 0.85） */
    val edgeAbsorption: Float = 0f
)

/** uRimConfig：hairline 暗线 / rim light / 暗线端部衰减（上游 rimShadeEnds 默认 0.2） */
data class GlassRim(
    val rimShade: Float = 0f,
    val rimLight: Float = 0f,
    val rimShadeEnds: Float = 0.2f
)

/** 一整套光学参数（对应上游 LiquidGlassSettings 的渲染字段） */
data class GlassSpec(
    val tint: Color = Color(0x14FFFFFF),
    val optics: GlassOptics = GlassOptics(),
    val light: GlassLight = GlassLight(),
    val edge: GlassEdge = GlassEdge(),
    val rim: GlassRim = GlassRim(),
    /** uWhiten：乳白面纱强度 */
    val whiten: Float = 0f,
    /** true = luminance-gated（浅色模式，保护暗部）；false = 均匀提亮 */
    val whitenGated: Boolean = true,
    val bodyShade: Float = 0f,
    /** 0 = 关闭 pinch */
    val pinchStrength: Float = 0f
) {
    companion object {
        /**
         * 深色玻璃预设（1:1 对齐 sample 导航栏 LiquidGlassSettings）：
         * glassColor 0x1AFFFFFF、whiten 0.15 luminance-gated、
         * thickness 28 / IOR 1.35 / 色散 0.02 / 光强 0.5 / 环境 0.1 /
         * 饱和 1.5 / Fresnel 1.0 / 边缘吸收 0.85。
         */
        fun dark(tint: Color = Color(0x1AFFFFFF)) = GlassSpec(
            tint = tint,
            edge = GlassEdge(edgeAbsorption = 0.85f),
            whiten = 0.15f,
            whitenGated = true
        )

        /**
         * 浅色玻璃预设：whiten luminance-gated（保护文字/图标暗部）。
         * 样本只有深色主题，浅色为对称推导（tint 改深染、其余同深色档）。
         */
        fun light(tint: Color = Color(0x0A000000)) = GlassSpec(
            tint = tint,
            edge = GlassEdge(edgeAbsorption = 0.85f),
            whiten = 0.15f,
            whitenGated = true
        )

        /**
         * 药丸指示器玻璃（1:1 上游 AnimatedGlassIndicator.baseIndicatorSettings）：
         * 无染色（纯光学透镜）、厚度 20、IOR 1.10、无色散（消除彩虹边）、
         * 光强 2.0、无环境光、无边缘吸收、pinch 1.0。
         * 静止态由调用方叠加纯色药丸（样本 0x59FFFFFF）——上游同样分
         * 「静止纯色 / 拖动玻璃」两个指示器按拖动进度交叉淡入。
         */
        fun pill() = GlassSpec(
            tint = Color(0x00FFFFFF),
            optics = GlassOptics(
                refractiveIndex = 1.10f,
                chromaticAberration = 0f,
                thickness = 20.dp
            ),
            light = GlassLight(intensity = 2f, ambient = 0f),
            edge = GlassEdge(edgeAbsorption = 0f),
            whiten = 0f,
            whitenGated = true,
            pinchStrength = 1f
        )
    }
}

/** API 33+ 才有 AGSL RuntimeShader */
val supportsRuntimeGlass: Boolean get() = Build.VERSION.SDK_INT >= 33

// ── AGSL：liquid_glass_render.frag 的 1:1 移植 ─────────────────────────────
//
// 与上游的差异（平台适配，非效果折扣）：
//   1. 无 geometry 纹理：SDF 法线/高度改为解析计算（形状限定圆角矩形）
//   2. 无 frost / PlatformView passthrough / capture offset（上游为
//      Flutter 引擎路径服务，本项目无对应场景）
//   3. 背景来自 uBackground 子图层（屏幕渐变/纯色副本），textureBilinear
//      手工双线性保留（AGSL 的 shader.eval 为 nearest）
const val LIQUID_GLASS_AGSL = """
const vec3 LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);

uniform shader uBackground;   // 背景副本子图层（layer 自身绘制内容）
uniform vec2 uSize;           // layer 物理像素尺寸
uniform vec2 uHalfSize;       // 形状半宽 / 半高（px）
uniform float uCornerRadius;  // 圆角半径（px）；胶囊 = 半高
uniform vec4 uGlassColor;     // tint（直色 + alpha）
uniform vec4 uOpticalProps;   // x: refractiveIndex y: chromaticAberration z: thickness w: refractScale
uniform vec3 uLightConfig;    // x: lightIntensity y: ambientStrength z: saturation
uniform vec2 uLightDirection; // 光源方向（归一化，指向光源）
uniform float uLensModel;     // 0 = spherical, 1 = paraxial
uniform float uWhiten;
uniform float uWhitenGated;
uniform float uPinchStrength;
uniform vec4 uEdgeConfig;     // x: ambientRim y: fresnelStrength z: dprScale w: edgeAbsorption
uniform vec3 uRimConfig;      // x: rimShade y: rimLight z: rimShadeEnds
uniform float uBodyShade;
uniform vec2 uTouchPosition;  // 触点（layer 本地 px）
uniform float uTouchIntensity; // 触点强度 0..1（弹簧动画）

// ── 几何：圆角矩形 SDF（胶囊 = 圆角半径取半高） ────────────────────────────
float sdRoundRect(vec2 p, vec2 halfSize, float r) {
    vec2 d = abs(p) - halfSize + vec2(r);
    return length(max(d, vec2(0.0))) + min(max(d.x, d.y), 0.0) - r;
}

// SDF 数值梯度（上游 geometry pass 用 dFdx/dFdy，解析形状下等价）
vec2 sdfGrad(vec2 p, vec2 halfSize, float r) {
    float e = 1.0;
    float dx = sdRoundRect(p + vec2(e, 0.0), halfSize, r)
             - sdRoundRect(p - vec2(e, 0.0), halfSize, r);
    float dy = sdRoundRect(p + vec2(0.0, e), halfSize, r)
             - sdRoundRect(p - vec2(0.0, e), halfSize, r);
    return vec2(dx, dy) / (2.0 * e);
}

// ── 手工双线性（AGSL shader.eval 为 nearest，保留上游 textureBilinear 逻辑）
vec4 textureBilinear(vec2 px) {
    vec2 p0 = floor(px);
    vec2 f = px - p0;
    vec4 c0 = uBackground.eval(p0);
    vec4 c1 = uBackground.eval(p0 + vec2(1.0, 0.0));
    vec4 c2 = uBackground.eval(p0 + vec2(0.0, 1.0));
    vec4 c3 = uBackground.eval(p0 + vec2(1.0, 1.0));
    vec4 cTop = mix(c0, c1, f.x);
    vec4 cBot = mix(c2, c3, f.x);
    return mix(cTop, cBot, f.y);
}

// ── render.glsl 工具函数 1:1 ──────────────────────────────────────────────
vec3 getHighlightColor(vec3 backgroundColor, float targetBrightness) {
    float luminance = dot(backgroundColor, LUMA_WEIGHTS);
    float maxComponent = max(max(backgroundColor.r, backgroundColor.g), backgroundColor.b);
    float lum = luminance * 2.5;
    float lumFactor = lum / (1.0 + lum);
    float sat = maxComponent * 2.5;
    float satFactor = sat / (1.0 + sat);
    float colorInfluence = lumFactor * satFactor;
    vec3 tinted = (backgroundColor / max(luminance, 0.001)) * targetBrightness;
    return mix(vec3(targetBrightness), tinted, colorInfluence);
}

vec3 applySaturation(vec3 color, float saturation) {
    float luminance = dot(color, LUMA_WEIGHTS);
    vec3 saturatedColor = mix(vec3(luminance), color, saturation);
    return clamp(saturatedColor, 0.0, 1.0);
}

vec4 applyGlassColor(vec4 liquidColor, vec4 glassColor, float bodyMode) {
    vec3 directMix = mix(liquidColor.rgb, glassColor.rgb, glassColor.a);
    if (bodyMode > 0.5) {
        return vec4(directMix, liquidColor.a);
    }
    float backdropLuminance = dot(liquidColor.rgb, LUMA_WEIGHTS);
    float glassLuminance    = dot(glassColor.rgb, LUMA_WEIGHTS);
    vec3 tinted = clamp(glassColor.rgb + (backdropLuminance - glassLuminance), 0.0, 1.0);
    float chroma = max(max(glassColor.r, glassColor.g), glassColor.b)
                 - min(min(glassColor.r, glassColor.g), glassColor.b);
    float chromaWeight = clamp(chroma * 8.0, 0.0, 1.0);
    vec3 luminosityMix = mix(liquidColor.rgb, tinted, glassColor.a);
    return vec4(mix(directMix, luminosityMix, chromaWeight), liquidColor.a);
}

// ── 主流程（对应 liquid_glass_render.frag main()）──────────────────────────
vec4 main() {
    vec2 fragCoord = gl_FragCoord.xy;
    vec2 invSize = 1.0 / uSize;

    float refractiveIndex     = uOpticalProps.x;
    float chromaticAberration = uOpticalProps.y;
    float thickness           = uOpticalProps.z;
    float lightIntensity      = uLightConfig.x;
    float ambientStrength     = uLightConfig.y;
    float saturation          = uLightConfig.z;
    float dprScale            = max(0.1, uEdgeConfig.z);

    // —— 几何：SDF / 法线 / 高度 ——
    vec2 p = fragCoord - uSize * 0.5;
    float sdf = sdRoundRect(p, uHalfSize, uCornerRadius);
    float alpha = 1.0 - smoothstep(-1.0, 1.0, sdf);
    if (alpha <= 0.0) {
        return vec4(0.0);
    }

    vec2 grad = sdfGrad(p, uHalfSize, uCornerRadius);
    float gradLen = length(grad);
    vec2 normalXY = gradLen > 1e-6 ? -grad / gradLen : vec2(0.0);
    float normalZ = sqrt(max(0.0, 1.0 - dot(normalXY, normalXY)));
    vec3 normal = vec3(normalXY, normalZ);

    // normalizedHeight：上游 geometryData.b 语义 —— rim 处 0，平坦内部 1
    float rimDistPx = max(0.0, -sdf);
    float normalizedHeight = 1.0 - clamp(rimDistPx / max(thickness, 0.001), 0.0, 1.0);
    float height = normalizedHeight * thickness;

    // —— 折射位移（spherical / paraxial 双模型，与上游一致）——
    float baseHeight = thickness * 8.0;
    vec2 displacement;
    if (uLensModel > 0.5) {
        displacement = -normalXY * length(normalXY) * baseHeight
                     * (refractiveIndex - 1.0);
    } else {
        vec3 incident = vec3(0.0, 0.0, -1.0);
        float invN = 1.0 / max(refractiveIndex, 0.001);
        vec3 baseRefract = refract(incident, normal, invN);
        float refractLen = (height + baseHeight) / max(0.001, abs(baseRefract.z));
        displacement = baseRefract.xy * refractLen;
    }
    displacement *= uOpticalProps.w;

    // 位移钳制在形状自身范围内（上游 maxReach）
    float maxReach = 0.5 * min(uSize.x, uSize.y);
    float reach = length(displacement);
    if (reach > maxReach) {
        displacement *= maxReach / reach;
    }

    // rimDist（上游由 encoded height 反推：thickness*(1-cos)）
    float cosTerm = sqrt(max(0.0, 1.0 - normalizedHeight * normalizedHeight));
    float rimDist = thickness * (1.0 - cosTerm);

    // —— hairline（iOS 27 半像素线）——
    vec2 lensDisplacement = displacement;
    float hairline = 0.0;
    if (uRimConfig.x > 0.001) {
        hairline = 1.0 - smoothstep(0.75 * dprScale, 2.0 * dprScale, rimDist);
        hairline *= 1.0 - step(0.999, normalizedHeight);
        vec2 outward = normalXY / max(length(normalXY), 1e-4) * (2.5 * dprScale);
        displacement = mix(displacement, outward, hairline);
    }

    // —— concave pinch（L4 squircle 透镜，iOS 26 指示器药丸）——
    vec2 screenUV = fragCoord * invSize;
    if (uPinchStrength > 0.001) {
        vec2 centered = p / uHalfSize;
        vec2 absCentered = abs(centered);
        float x2 = absCentered.x * absCentered.x;
        float ax4 = x2 * x2;
        float y2 = absCentered.y * absCentered.y;
        float ay4 = y2 * y2;
        float s = ax4 + ay4;
        float squircleDist = sqrt(sqrt(s));
        float pinchRamp = smoothstep(0.0, 1.0, squircleDist);
        vec2 pinchShift = centered * pinchRamp * uPinchStrength * 0.025;
        pinchShift *= alpha;
        screenUV += pinchShift;
        screenUV = clamp(screenUV, vec2(0.001), vec2(0.999));
    }

    // —— 背景采样（色散 / 平坦快路径 / hairline 三 tap）——
    vec4 refractColor;
    if (dot(normalXY, normalXY) < 1e-4) {
        refractColor = textureBilinear(fragCoord);
    } else if (chromaticAberration < 0.01) {
        vec2 refractedPx = fragCoord + displacement;
        refractColor = textureBilinear(refractedPx);
        if (hairline > 0.0) {
            vec2 tangent = vec2(-normalXY.y, normalXY.x)
                         / max(length(normalXY), 1e-4) * (1.5 * dprScale);
            vec4 side = textureBilinear(refractedPx + tangent)
                      + textureBilinear(refractedPx - tangent);
            refractColor = mix(refractColor, (refractColor + side) / 3.0, hairline);
        }
    } else {
        float dispersionStrength = chromaticAberration * 0.5;
        vec2 redOffset  = displacement * (1.0 + dispersionStrength);
        vec2 blueOffset = displacement * (1.0 - dispersionStrength);
        float red       = textureBilinear(fragCoord + redOffset).r;
        vec4  greenS    = textureBilinear(fragCoord + displacement);
        float blue      = textureBilinear(fragCoord + blueOffset).b;
        refractColor = vec4(red, greenS.g, blue, greenS.a);
    }

    if (refractColor.a > 0.001) {
        refractColor.rgb /= refractColor.a;
    }

    // —— 玻璃染色 + 亮度自适应 ——
    vec4 bodyGlassColor = uGlassColor;
    bodyGlassColor.a *= 1.0 - hairline;
    vec4 finalColor = applyGlassColor(refractColor, bodyGlassColor, 0.0);

    float backdropLuma     = dot(refractColor.rgb, LUMA_WEIGHTS);
    float adaptiveStrength = mix(1.2, 0.8, backdropLuma);

    finalColor.rgb = applySaturation(
        finalColor.rgb, mix(saturation * adaptiveStrength, 1.0, hairline));

    finalColor.rgb = mix(finalColor.rgb,
                         uGlassColor.rgb,
                         uGlassColor.a * 0.12 * (adaptiveStrength - 1.0) * (1.0 - hairline));

    // —— 白化面纱 ——
    const float WHITEN_LO = 0.40;
    const float WHITEN_HI = 0.80;
    float whitenLuma = dot(finalColor.rgb, LUMA_WEIGHTS);
    float whitenGate = mix(1.0, smoothstep(WHITEN_LO, WHITEN_HI, whitenLuma), uWhitenGated);
    finalColor.rgb = mix(finalColor.rgb, vec3(1.0),
                         clamp(uWhiten, 0.0, 1.0) * whitenGate * (1.0 - hairline));

    // —— bodyShade（iOS 27 暗色材质把亮背景压下来）——
    if (uBodyShade > 0.0) {
        float shadeLuma = dot(finalColor.rgb, LUMA_WEIGHTS);
        finalColor.rgb *= 1.0 - uBodyShade * shadeLuma * shadeLuma;
    }

    // —— meniscus 暗环（VQ5：半球剖面 + 光照调制 + 边缘加权色散）——
    float baseScale      = 40.0 * dprScale;
    float thicknessScale = clamp(baseScale / max(thickness, 1.0), 1.0, 4.0);
    float edgeThreshold  = mix(0.8, 0.5, 1.0 / thicknessScale);
    float edgeFactor     = thickness < 0.01 ? 0.0
                            : 1.0 - smoothstep(0.0, edgeThreshold, normalizedHeight);

    float r_rim = clamp(1.0 - normalizedHeight, 0.0, 1.0);
    float lensThickness = thickness < 0.01 ? 0.0
                        : sqrt(max(0.0, 1.0 - r_rim * r_rim));
    float rimThickness = 1.0 - lensThickness;

    float len2D = max(length(normalXY), 1e-4);
    vec2 rimN = normalXY / len2D;
    float litness = dot(rimN, uLightDirection);
    float dirScale = mix(1.4, 0.6, litness * 0.5 + 0.5);

    float absorption = 1.0 - sqrt(rimThickness) * uEdgeConfig.w * dirScale;
    finalColor.rgb *= max(0.0, absorption);

    // —— 边缘光（方向性高光 + 环境 + 触点高光）——
    if (edgeFactor > 0.01 && (lightIntensity > 0.0 || ambientStrength > 0.0)) {
        float mainLight     = max(0.0, dot(rimN, uLightDirection));
        float oppositeLight = max(0.0, dot(rimN, -uLightDirection));
        float totalInfluence = mainLight + oppositeLight * 0.8;
        float directional = totalInfluence * sqrt(totalInfluence) * lightIntensity * 3.0;
        float ambient = ambientStrength * 0.5;
        float brightnessRaw = (directional + ambient) * edgeFactor * thicknessScale * 0.8;
        float brightness = brightnessRaw / (1.0 + brightnessRaw);
        vec3 highlightColor = getHighlightColor(refractColor.rgb, 1.0);
        finalColor.rgb = mix(finalColor.rgb, highlightColor, brightness);

        if (uTouchIntensity > 0.001) {
            vec2 toFragPx = fragCoord - uTouchPosition;
            float touchDist = length(toFragPx);
            float dpr = max(1.0, dprScale * 3.0);
            float touchRadius = max(70.0 * dpr, uSize.y * 1.5);
            float distFactor = smoothstep(touchRadius, 0.0, touchDist);
            if (distFactor > 0.001 && touchDist > 1.0) {
                vec2 touchDir = toFragPx / touchDist;
                float rimTouchDot = max(0.0, dot(rimN, touchDir));
                float rtd2 = rimTouchDot * rimTouchDot;
                float tSpec = rtd2 * rtd2 * rtd2 * distFactor;
                float tBrightnessRaw = tSpec * uTouchIntensity * lightIntensity * 2.5;
                float tBrightness = tBrightnessRaw / (1.0 + tBrightnessRaw);
                finalColor.rgb = mix(finalColor.rgb, highlightColor,
                                     tBrightness * edgeFactor);
            }
        }
    }

    // —— Fresnel + ambientRim 光环 + rim light 双瓣 ——
    float rimBase = (1.0 - normalZ) * edgeFactor;
    float ringWindow = 0.75 * dprScale;
    float ring = (1.0 - smoothstep(uEdgeConfig.x - ringWindow, uEdgeConfig.x + ringWindow, rimDist))
               * step(0.001, uEdgeConfig.x);
    float fresnel = rimBase * 0.12 * uEdgeConfig.y + ring * 0.45;
    finalColor.rgb = clamp(finalColor.rgb + vec3(fresnel), 0.0, 1.0);

    if (uRimConfig.y > 0.001 && rimDist < 30.0 * dprScale) {
        float along = dot(rimN, uLightDirection);
        float lit = max(along, 0.0);
        float opp = max(-along, 0.0);
        float lobe = lit * lit * lit + 0.85 * opp * opp * opp;
        float inset = max(0.0, rimDist - 2.25 * dprScale) / dprScale;
        float core = exp(-inset / 1.5);
        float tail = exp(-inset / 8.0);
        float bodyLuma = clamp(dot(finalColor.rgb, LUMA_WEIGHTS), 0.0, 1.0);
        float ease = pow(1.0 - bodyLuma, 0.3);
        float rimLight = uRimConfig.y * lobe * ease
                       * (0.175 * core + 0.035 * tail) * (1.0 - hairline);
        finalColor.rgb = clamp(finalColor.rgb + vec3(rimLight), 0.0, 1.0);
    }

    // —— hairline 暗线（线性减淡）——
    if (hairline > 0.0) {
        float across = 1.0 - dot(rimN, uLightDirection) * dot(rimN, uLightDirection);
        across = pow(max(across, 0.0), 1.75);
        float shade = uRimConfig.x * 0.314 * mix(uRimConfig.z, 1.0, across);
        finalColor.rgb = max(finalColor.rgb - vec3(shade * hairline), 0.0);
    }

    return vec4(finalColor.rgb * alpha, alpha);
}
"""

// ── Compose 封装 ───────────────────────────────────────────────────────────

/**
 * AGSL 玻璃渲染节点（API 33+）：一个玻璃层持有**独立**实例
 * （uniform 每帧更新；共享实例会导致同帧多个玻璃层互相覆盖 uniform）。
 *
 * 渲染方式：Compose 没有跨 layer 捕获 API，也没有 RuntimeShader 版的
 * `Modifier.graphicsLayer(renderEffect = …)`（Compose 的 RenderEffect
 * 封装只有 Blur/Offset 工厂）。这里直接用平台公开 API 自建管线：
 *   RenderNode 录制背景副本（uBackground 采样源）→ 挂
 *   RenderEffect.createRuntimeShaderEffect(shader, "uBackground")
 *   → Canvas.drawRenderNode 画进 Compose 画布。
 * 每帧重新录制显示列表 → HWUI 标记脏区 → shader 以当前 uniform 重新求值。
 */
class GlassNode {
    val shader: RuntimeShader = RuntimeShader(LIQUID_GLASS_AGSL)
    private val node: RenderNode = RenderNode("liquidGlass")
    private val effect: RenderEffect =
        RenderEffect.createRuntimeShaderEffect(shader, "uBackground")

    init {
        node.setRenderEffect(effect)
    }

    /**
     * 录制并绘制一帧。
     * @param record 背景副本绘制回调（画进 RenderNode 的 RecordingCanvas，
     *        作为 shader 的 uBackground 输入）
     */
    fun drawTo(
        canvas: android.graphics.Canvas,
        width: Int,
        height: Int,
        record: (android.graphics.Canvas) -> Unit
    ) {
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        record(recording)
        node.endRecording()
        canvas.drawRenderNode(node)
    }
}

/** 每个玻璃层独立的 GlassNode（API <33 返回 null，调用方走分层降级） */
@Composable
fun rememberGlassNode(): GlassNode? = remember(supportsRuntimeGlass) {
    if (supportsRuntimeGlass) GlassNode() else null
}

/**
 * 液态玻璃修饰符（玻璃底层，无子内容——内容由调用方叠在其上，
 * 否则整层会被 shader 输出替换）。
 *
 * @param glassNode 独立的 GlassNode（API <33 传 null）
 * @param cornerRadius 圆角半径（胶囊传超大值即可——内部钳制到半高）
 * @param spec 光学参数
 * @param backdropColor 背景底色（纯色区场景）
 * @param backdropTop/backdropBottom 屏幕渐变副本参数：
 *        screenTopY = layer 顶部在窗口中的 y（px），windowH = 窗口高度（px），
 *        bgTop = 屏幕渐变 0% 处颜色，bgBottom = 40% 处颜色（100% 同 bgBottom）。
 *        传 windowH = 0 表示纯色背景（bottomBar 常见）。
 * @param touchPosition 触点（layer 本地 px）的**读取器**，驱动 shader 触点高光。
 *       传 lambda 而非值：调用方常在组合期读 State，写成 lambda 可把读取
 *       推迟到绘制阶段（每帧只重绘、不重组）
 * @param touchIntensity 触点强度 0..1 的读取器
 * @param pinchStrength pinch 强度 0..1 的读取器（可弹簧动画）
 */
fun Modifier.liquidGlass(
    glassNode: GlassNode?,
    cornerRadius: Dp,
    spec: GlassSpec,
    backdropColor: Color,
    screenTopY: Float = 0f,
    windowHeightPx: Float = 0f,
    backdropTop: Color = backdropColor,
    backdropBottom: Color = backdropColor,
    touchPosition: () -> Offset = { Offset.Zero },
    touchIntensity: () -> Float = { 0f },
    pinchStrength: () -> Float = { spec.pinchStrength }
): Modifier {
    // 注意：本函数是 Modifier 扩展（非 @Composable），不能读 CompositionLocal；
    // DrawScope 自身实现 Density，dp→px 换算在 drawBehind 的 block 内完成。
    return if (glassNode != null && supportsRuntimeGlass) {
        this.drawBehind {
            // DrawScope 即 Density；touch/pinch 读取器在此调用——
            // 绘制阶段读 State 只触发重绘，不触发重组
            val dpr = density.toFloat().coerceAtLeast(0.1f)
            val cornerPx = cornerRadius.toPx()
            val thicknessPx = spec.optics.thickness.toPx()
            updateGlassUniforms(
                glassNode.shader, spec, size, cornerPx, thicknessPx, dpr,
                touchPosition(), touchIntensity(), pinchStrength()
            )
            val w = size.width.toInt().coerceAtLeast(1)
            val h = size.height.toInt().coerceAtLeast(1)
            drawIntoCanvas { canvas ->
                glassNode.drawTo(canvas.nativeCanvas, w, h) { rc ->
                    // 背景副本：屏幕渐变换算进 layer 本地坐标（uBackground 输入）。
                    // bottomBar 位于渐变纯色区 → 纯色。
                    if (windowHeightPx > 0f) {
                        drawBackdropGradient(
                            rc, size.width, size.height,
                            screenTopY, windowHeightPx,
                            backdropTop, backdropBottom
                        )
                    } else {
                        rc.drawColor(backdropColor.toArgb())
                    }
                }
            }
        }
    } else {
        drawFallbackGlass(spec, backdropColor)
    }
}

/**
 * 背景副本 = 屏幕垂直渐变在 layer 区间的精确子段。
 * 屏幕渐变定义：0% → top，40% → bottom，100% → bottom（分段线性）。
 * 用平台 LinearGradient 画进 RenderNode 录制画布。
 */
private fun drawBackdropGradient(
    rc: android.graphics.Canvas,
    w: Float,
    h: Float,
    screenTopY: Float,
    windowH: Float,
    top: Color,
    bottom: Color
) {
    fun colorAt(screenY: Float): Int {
        val t = (screenY / (windowH * 0.4f)).coerceIn(0f, 1f)
        return lerp(top, bottom, t).toArgb()
    }
    val yTop = screenTopY
    val yBottom = screenTopY + h
    val kink = windowH * 0.4f
    val paint = android.graphics.Paint()
    when {
        // layer 整体在 40% 以下 → 纯 bottom 色
        yTop >= kink -> rc.drawColor(bottom.toArgb())
        // layer 整体在屏幕顶之上 → 纯 top 色（理论情形）
        yBottom <= 0f -> rc.drawColor(top.toArgb())
        // 折点在 layer 之下 → 两端各自取样，两停靠点渐变
        yBottom <= kink -> {
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                colorAt(yTop), colorAt(yBottom),
                android.graphics.Shader.TileMode.CLAMP
            )
            rc.drawRect(0f, 0f, w, h, paint)
        }
        // 折点落在 layer 内 → 三停靠点
        else -> {
            val kinkLocal = ((kink - yTop) / h).coerceIn(0f, 1f)
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(colorAt(yTop), bottom.toArgb(), bottom.toArgb()),
                floatArrayOf(0f, kinkLocal, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            rc.drawRect(0f, 0f, w, h, paint)
        }
    }
}

/** layer 阶段：更新 shader 的全部 uniform（1:1 上游 uniform 布局） */
private fun updateGlassUniforms(
    shader: RuntimeShader,
    spec: GlassSpec,
    sizePx: Size,
    cornerPx: Float,
    thicknessPx: Float,
    dpr: Float,
    touch: Offset,
    touchIntensity: Float,
    pinch: Float
) {
    val halfW = sizePx.width / 2f
    val halfH = sizePx.height / 2f
    val corner = cornerPx.coerceIn(0f, halfH)
    shader.setFloatUniform("uSize", sizePx.width, sizePx.height)
    shader.setFloatUniform("uHalfSize", halfW, halfH)
    shader.setFloatUniform("uCornerRadius", corner)
    val argb = spec.tint.toArgb()
    shader.setFloatUniform(
        "uGlassColor",
        AndroidColor.red(argb) / 255f,
        AndroidColor.green(argb) / 255f,
        AndroidColor.blue(argb) / 255f,
        AndroidColor.alpha(argb) / 255f
    )
    shader.setFloatUniform(
        "uOpticalProps",
        spec.optics.refractiveIndex,
        spec.optics.chromaticAberration,
        thicknessPx * dpr,
        spec.optics.refractScale * dpr
    )
    shader.setFloatUniform(
        "uLightConfig",
        spec.light.intensity,
        spec.light.ambient,
        spec.light.saturation
    )
    shader.setFloatUniform("uLightDirection", spec.light.directionX, spec.light.directionY)
    shader.setFloatUniform("uLensModel", if (spec.optics.paraxialLens) 1f else 0f)
    shader.setFloatUniform("uWhiten", spec.whiten)
    shader.setFloatUniform("uWhitenGated", if (spec.whitenGated) 1f else 0f)
    shader.setFloatUniform("uPinchStrength", pinch)
    shader.setFloatUniform(
        "uEdgeConfig",
        spec.edge.ambientRim * dpr,
        spec.edge.fresnelStrength,
        dpr / 3f,
        spec.edge.edgeAbsorption
    )
    shader.setFloatUniform(
        "uRimConfig",
        spec.rim.rimShade,
        spec.rim.rimLight,
        spec.rim.rimShadeEnds
    )
    shader.setFloatUniform("uBodyShade", spec.bodyShade)
    shader.setFloatUniform("uTouchPosition", touch.x, touch.y)
    shader.setFloatUniform("uTouchIntensity", touchIntensity)
}

/**
 * API 24-32 的分层玻璃降级：染色斜面 + 顶部反光滑带 + 方向性描边。
 * 无折射扭曲（AGSL 需 API 33），保留液态玻璃的色彩与光学分层语言。
 */
fun Modifier.drawFallbackGlass(
    spec: GlassSpec,
    backdropColor: Color
): Modifier = this.drawBehind {
    val w = size.width
    val h = size.height
    val glassDark = backdropColor.luminance() < 0.5f
    val tint = spec.tint
    val tintStops: Array<Pair<Float, Color>> = if (glassDark) {
        arrayOf(
            0.00f to androidx.compose.ui.graphics.lerp(
                backdropColor, Color.White, 0.10f
            ).copy(alpha = 0.9f),
            0.45f to androidx.compose.ui.graphics.lerp(
                backdropColor, tint, 0.16f
            ).copy(alpha = 0.94f),
            1.00f to androidx.compose.ui.graphics.lerp(
                backdropColor, tint, 0.06f
            ).copy(alpha = 0.96f)
        )
    } else {
        arrayOf(
            0.00f to androidx.compose.ui.graphics.lerp(
                Color.White, backdropColor, 0.10f
            ).copy(alpha = 0.92f),
            0.45f to androidx.compose.ui.graphics.lerp(
                Color.White, tint, 0.10f
            ).copy(alpha = 0.94f),
            1.00f to androidx.compose.ui.graphics.lerp(
                Color.White, backdropColor, 0.38f
            ).copy(alpha = 0.96f)
        )
    }
    drawRect(
        Brush.verticalGradient(*tintStops)
    )
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (glassDark) 0.10f else 0.30f),
            0.18f to Color.White.copy(alpha = if (glassDark) 0.02f else 0.06f),
            0.35f to Color.Transparent,
            1f to Color.Transparent
        )
    )
    val strokePx = 2.dp.toPx()
    val edgeStops: Array<Pair<Float, Color>> = if (glassDark) {
        arrayOf(
            0f to Color.White.copy(alpha = 0.32f),
            0.55f to tint.copy(alpha = 0.22f),
            1f to Color.White.copy(alpha = 0.05f)
        )
    } else {
        arrayOf(
            0f to Color.White.copy(alpha = 0.95f),
            0.55f to tint.copy(alpha = 0.20f),
            1f to Color.White.copy(alpha = 0.40f)
        )
    }
    drawRect(
        brush = Brush.linearGradient(
            colorStops = edgeStops,
            start = Offset(0f, 0f),
            end = Offset(w, h)
        ),
        topLeft = Offset(strokePx / 2f, strokePx / 2f),
        size = Size(w - strokePx, h - strokePx),
        style = Stroke(width = strokePx)
    )
}
