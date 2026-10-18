import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart' show Ticker, TickerProvider;
import 'package:flutter/services.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

/// 原生 ↔ Flutter 桥接通道（与 MainActivity 中的常量保持一致）。
///
/// Native → Dart：
///   - `setTab`   {'index': int}              原生分页器位置变化（滑动/点击回显）
///   - `setTheme` {'dark': bool, 'bgTop': int, 'bgBottom': int}
///                                               主题/背景色同步（ARGB int）
///   - `ready`                                   原生侧确认通道已就绪
/// Dart → Native：
///   - `ready`     {}                          Dart 侧 handler 已注册（原生补发初始状态）
///   - `onSelect`  {'index': int}               点击切换 tab
///   - `scrub`     {'page': int, 'fraction': double}  拖动中每帧的连续页位置
///   - `scrubEnd`  {'page': int}               松手吸附的目标页
const MethodChannel navBarChannel = MethodChannel('com.mooddiary/navbar');

/// 导航栏玻璃设置 —— 1:1 复刻 sample 的 `_navSettings`（HUD 校准值）。
///
/// 与上游 shader uniform 的对应：
///   thickness ↔ uOpticalProps.z；blur ↔ uOpticalProps.y；
///   refractiveIndex ↔ uOpticalProps.x；chromaticAberration ↔ 折射色散；
///   edgeAbsorption ↔ uEdgeConfig.w；whitenStrength ↔ uWhiten；
///   glassColor.a ↔ uGlassColor.a。
LiquidGlassSettings navBarSettings() => LiquidGlassSettings(
      thickness: 28,
      blur: 3,
      refractiveIndex: 1.35,
      chromaticAberration: 0.02,
      lightIntensity: 0.5,
      ambientStrength: 0.1,
      saturation: 1.5,
      fresnelStrength: 1.0,
      glowIntensity: 0.75,
      specularSharpness: GlassSpecularSharpness.medium,
      edgeAbsorption: 0.85,
      whitenStrength: 0.15,
      whitenGated: true,
      glassColor: const Color.fromARGB(26, 255, 255, 255),
    );

/// 拖动态药丸玻璃 —— 1:1 复刻上游 `baseIndicatorSettings`
/// （纯光学透镜：厚度 20 / IOR 1.10 / 无色散 / 光强 2.0 / 无染色 / 无模糊）。
LiquidGlassSettings pillSettings() => LiquidGlassSettings(
      glassColor: const Color(0x00FFFFFF),
      thickness: 20,
      refractiveIndex: 1.10,
      chromaticAberration: 0.0,
      lightIntensity: 2.0,
      ambientStrength: 0.0,
      blur: 0,
    );

/// 四个 tab（图标 1:1 对应原生的 Material Icons 同源图形）。
const List<GlassTab> kNavTabs = [
  GlassTab(icon: Icon(Icons.calendar_month), label: '日历'),
  GlassTab(icon: Icon(Icons.list), label: '记录'),
  GlassTab(icon: Icon(Icons.bar_chart), label: '统计'),
  GlassTab(icon: Icon(Icons.settings), label: '设置'),
];

/// 长按进入拖动的阈值（原生 ShortLongPressViewConfiguration 的 Dart 等价）。
const Duration kLongPressTimeout = Duration(milliseconds: 250);

/// Compose Spring 常量换算（mass = 1）：
///   stiffness = 1500（StiffnessMedium）
///   dampingRatio：LowBouncy 0.75 → damping 58.09；NoBouncy 1.0 → damping 77.46
const double _kStiffness = 1500;

/// 阻尼谐振子弹簧的解析解（零依赖，替代 physics 包的 SpringSimulation）。
///
/// 闭式解比数值积分更精确且无收敛问题：
///   x(t) = xf + e^(−ζω₀t)·[a·cos(ω_d t) + b·sin(ω_d t)]
///   a = x₀ − xf；b = (v₀ + ζω₀a) / ω_d
/// ζ 钳制到 0.999 以内（临界阻尼用同式近似，视觉无损）以保证 ω_d 为正。
class SpringMotion extends Simulation {
  SpringMotion({
    required this.from,
    required this.to,
    required double damping,
    double initialVelocity = 0,
    double stiffness = _kStiffness,
  })  : _omega0 = math.sqrt(stiffness),
        _zeta = (damping / (2 * math.sqrt(stiffness))).clamp(0.001, 0.999),
        super(tolerance: const Tolerance()) {
    _omegaD = _omega0 * math.sqrt(1 - _zeta * _zeta);
    _a = from - to;
    _b = (initialVelocity + _zeta * _omega0 * _a) / _omegaD;
  }

  final double from;
  final double to;
  final double _omega0;
  final double _zeta;
  late final double _omegaD;
  late final double _a;
  late final double _b;

  @override
  double x(double time) {
    final e = math.exp(-_zeta * _omega0 * time);
    return to + e * (_a * math.cos(_omegaD * time) + _b * math.sin(_omegaD * time));
  }

  @override
  double dx(double time) {
    final e = math.exp(-_zeta * _omega0 * time);
    final c = math.cos(_omegaD * time);
    final s = math.sin(_omegaD * time);
    // d/dt[e·(a·cos + b·sin)] = e·[−ζω₀(a·cos + b·sin) + ω_d(−a·sin + b·cos)]
    return e * (-_zeta * _omega0 * (_a * c + _b * s) +
        _omegaD * (-_a * s + _b * c));
  }

  @override
  bool isDone(double time) {
    // 位置与速度都收敛到容差内即结束（另加 3s 硬上限兜底）。
    if (time > 3.0) return true;
    return (x(time) - to).abs() < 0.1 && dx(time).abs() < 1.0;
  }
}

/// 一个 double 值的弹簧轨道：Ticker 驱动 SpringMotion 解析解，
/// 值变化时通知监听者（AnimatedBuilder 重建药丸层）。
class _SpringTrack extends ChangeNotifier {
  _SpringTrack(this._provider);

  final TickerProvider _provider;
  Ticker? _ticker;
  SpringMotion? _motion;
  double _value = 0;

  double get value => _value;

  set value(double v) {
    _ticker?.stop();
    _motion = null;
    _value = v;
    notifyListeners();
  }

  /// 弹簧动画到目标（damping 为绝对阻尼系数 = 2√stiffness × ratio）。
  void animateTo(double target, double damping) {
    _motion = SpringMotion(from: _value, to: target, damping: damping);
    _ticker?.stop();
    _ticker = _provider.createTicker(_onTick);
    _ticker!.start();
  }

  void _onTick(Duration elapsed) {
    final m = _motion;
    if (m == null) {
      _ticker?.stop();
      return;
    }
    final t = elapsed.inMicroseconds / 1e6;
    _value = m.x(t);
    notifyListeners();
    if (m.isDone(t)) {
      _value = m.to;
      notifyListeners();
      _ticker?.stop();
      _motion = null;
    }
  }

  @override
  void dispose() {
    _ticker?.dispose();
    super.dispose();
  }
}

/// 药丸纯色态（样本 indicatorColor：白 0x59 = 35%）。
const Color kPillRestColor = Color(0x59FFFFFF);

/// 导航栏根组件。可由 `main()`（独立调试）或 `navBarMain()`（原生嵌入）启动。
class NavBarApp extends StatefulWidget {
  const NavBarApp({super.key, this.glassInitError});

  /// `LiquidGlassWidgets.initialize()` 失败时的错误信息；非空 → 降级渲染。
  final String? glassInitError;

  @override
  State<NavBarApp> createState() => _NavBarAppState();
}

class _NavBarAppState extends State<NavBarApp> {
  int _tab = 0;
  bool _dark = true;
  Color _bgTop = const Color(0xFF14161D);
  Color _bgBottom = const Color(0xFF14161D);
  bool _scrubbing = false;

  @override
  void initState() {
    super.initState();
    navBarChannel.setMethodCallHandler(_onNativeCall);
    // 通知原生：Dart 侧已就绪，可以补发 setTab / setTheme。
    navBarChannel.invokeMethod('ready');
  }

  Future<void> _onNativeCall(MethodCall call) async {
    switch (call.method) {
      case 'setTab':
        final i = (call.arguments as Map?)?['index'];
        if (i is int && i >= 0 && i < kNavTabs.length && mounted) {
          setState(() => _tab = i);
        }
      case 'setTheme':
        final args = (call.arguments as Map?) ?? const {};
        final dark = args['dark'];
        final top = args['bgTop'];
        final bottom = args['bgBottom'];
        if (mounted) {
          setState(() {
            if (dark is bool) _dark = dark;
            if (top is int) _bgTop = Color(top);
            if (bottom is int) _bgBottom = Color(bottom);
          });
        }
      default:
        break;
    }
  }

  void _onTabSelected(int index) {
    if (index == _tab) return;
    setState(() => _tab = index);
    navBarChannel.invokeMethod('onSelect', {'index': index});
  }

  void _onScrubbingChanged(bool value) {
    if (_scrubbing != value) setState(() => _scrubbing = value);
  }

  @override
  Widget build(BuildContext context) {
    final content = Material(
      color: Colors.transparent,
      child: Stack(
        fit: StackFit.expand,
        children: [
          // 屏幕渐变（玻璃的 backdrop）。混编下 Flutter 采样不到原生列表，
          // 用与原生屏幕渐变一致的子段近似：0% → top，40% → bottom，100% → bottom。
          DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [_bgTop, _bgBottom, _bgBottom],
                stops: const [0.0, 0.4, 1.0],
              ),
            ),
          ),
          if (widget.glassInitError != null)
            _buildFallback()
          else
            _buildGlassBar(),
        ],
      ),
    );
    return content;
  }

  Widget _buildGlassBar() {
    return Center(
      child: SizedBox(
        width: double.infinity,
        height: 60,
        child: Stack(
          children: [
            // 上游玻璃导航栏：只贡献渲染（玻璃 + indicator 动画），
            // 手势全部由 _ScrubLayer 接管（IgnorePointer 关掉自带手势）。
            IgnorePointer(
              child: GlassTabBar.bottom(
                tabs: kNavTabs,
                selectedIndex: _tab,
                onTabSelected: _onTabSelected,
                quality: GlassQuality.premium,
                indicatorColor: kPillRestColor,
                settings: navBarSettings(),
                indicatorSettings: pillSettings(),
                showIndicator: !_scrubbing,
                barHeight: 60,
                verticalPadding: 0,
                horizontalPadding: 12,
                barBorderRadius: 999,
                indicatorPinchStrength: 1.0,
              ),
            ),
            // 手势层 + 拖动态自绘药丸（1:1 翻译原生 FloatingNavBar 逻辑）。
            _ScrubLayer(
              tab: _tab,
              barHeight: 60,
              onTabSelected: _onTabSelected,
              onScrubbingChanged: _onScrubbingChanged,
            ),
          ],
        ),
      ),
    );
  }

  /// 着色器初始化失败的降级：纯色导航栏（保留点击能力）。
  Widget _buildFallback() {
    return Center(
      child: SizedBox(
        width: double.infinity,
        height: 60,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: _dark
                ? Colors.white.withValues(alpha: 0.10)
                : Colors.black.withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(999),
          ),
          child: Row(
            children: [
              for (var i = 0; i < kNavTabs.length; i++)
                Expanded(
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: () => _onTabSelected(i),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        IconTheme(
                          data: IconThemeData(
                            color: i == _tab
                                ? Theme.of(context).colorScheme.primary
                                : (_dark ? Colors.white70 : Colors.black54),
                          ),
                          child: kNavTabs[i].icon ?? const SizedBox.shrink(),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          kNavTabs[i].label ?? '',
                          style: TextStyle(
                            fontSize: 11,
                            color: i == _tab
                                ? Theme.of(context).colorScheme.primary
                                : (_dark ? Colors.white70 : Colors.black54),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 手势层 + 拖动态药丸。
///
/// 1:1 翻译原生 FloatingNavBar 的指针层逻辑：
///   - 点击（未超 slop 的快速起落）→ onTabSelected
///   - 药丸上长按 250ms → 进入拖动（haptic + activation 弹簧 0→1）
///   - 拖动 → 橡皮筋越界阻尼 + 每帧 scrub(page, fraction) 通知原生
///   - 松手 → activation 0 + 药丸弹簧回位 + scrubEnd(page)
class _ScrubLayer extends StatefulWidget {
  const _ScrubLayer({
    required this.tab,
    required this.barHeight,
    required this.onTabSelected,
    required this.onScrubbingChanged,
  });

  /// 当前选中 tab（原生 setTab / 点击回显同步过来）。
  final int tab;
  final double barHeight;
  final ValueChanged<int> onTabSelected;
  final ValueChanged<bool> onScrubbingChanged;

  @override
  State<_ScrubLayer> createState() => _ScrubLayerState();
}

class _ScrubLayerState extends State<_ScrubLayer>
    with TickerProviderStateMixin {
  // dp 尺寸（与原生 HIGHLIGHT_W/H、rowPad、tol 一致）
  static const double _pillWDp = 66;
  static const double _pillHDp = 48;
  static const double _rowPadDp = 12;
  static const double _tolDp = 14;

  // 三个弹簧通道（位置 X / 位置 Y / 激活度），各自独立 Ticker 驱动。
  late final _SpringTrack _trackX;
  late final _SpringTrack _trackY;
  late final _SpringTrack _trackAct;

  // 拖动状态
  Offset? _down;
  double _startPillX = 0;
  bool _slopBreached = false;
  Timer? _longPress;
  bool _scrubbing = false;
  int _scrubIndex = 0;
  int _defaultTab = 0;
  double _barW = 0;
  double _lastVx = 0;
  DateTime? _lastMoveAt;

  @override
  void didUpdateWidget(covariant _ScrubLayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.tab != widget.tab) {
      _defaultTab = widget.tab;
      if (!_scrubbing && _barW > 0) {
        // tab 变化（原生滑动/点击）→ 药丸滑到新位置
        // （原生 LaunchedEffect(activeIndex) → animatePillTo）
        _springTo(_trackX, _pillCenterX(_defaultTab) - _pillW / 2, 0.75);
      }
    }
  }

  double get _dpr => MediaQuery.devicePixelRatioOf(context);
  double get _rowPad => _rowPadDp * _dpr;
  double get _slotW => (_barW - _rowPad * 2) / kNavTabs.length;
  double get _pillW => _pillWDp * _dpr;
  double get _pillH => _pillHDp * _dpr;
  double get _tol => _tolDp * _dpr;
  double get _pillTop => (widget.barHeight - _pillH) / 2;

  double _pillCenterX(int i) => _rowPad + _slotW * i + _slotW / 2;

  double get _pillX => _trackX.value;

  @override
  void initState() {
    super.initState();
    _trackX = _SpringTrack(this);
    _trackY = _SpringTrack(this);
    _trackAct = _SpringTrack(this);
  }

  @override
  void dispose() {
    _longPress?.cancel();
    _trackX.dispose();
    _trackY.dispose();
    _trackAct.dispose();
    super.dispose();
  }

  /// 弹簧到目标值（Compose spring → SpringMotion 解析解）。
  void _springTo(_SpringTrack track, double target, double dampingRatio) {
    track.animateTo(target, 2 * math.sqrt(_kStiffness) * dampingRatio);
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final newW = constraints.maxWidth;
        if ((newW - _barW).abs() > 0.5) {
          _barW = newW;
          // 尺寸变化（首帧/旋转）：立即校准，不走动画
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted) return;
            _trackX.value =
                (_pillCenterX(_defaultTab) - _pillW / 2).clamp(0.0, _barW);
            _trackY.value = 0;
          });
        }
        return Listener(
          behavior: HitTestBehavior.translucent,
          onPointerDown: _onDown,
          onPointerMove: _onMove,
          onPointerUp: _onUp,
          onPointerCancel: _onCancel,
          child: AnimatedBuilder(
            animation: Listenable.merge([_trackX, _trackY, _trackAct]),
            builder: (context, _) {
              return Stack(
                children: [
                  if (_scrubbing || _trackAct.value > 0.01)
                    Positioned(
                      left: _trackX.value,
                      top: _pillTop + _trackY.value,
                      width: _pillW,
                      height: _pillH,
                      child: IgnorePointer(
                        child: AnimatedGlassIndicator(
                          velocity: _lastVx,
                          itemCount: kNavTabs.length,
                          alignment: Alignment.center,
                          thickness: _trackAct.value,
                          quality: GlassQuality.premium,
                          indicatorColor: kPillRestColor,
                          isBackgroundIndicator: false,
                          exactWidth: _pillW,
                          exactOffset: _trackX.value,
                          padding: const EdgeInsets.all(4),
                          expansion: const EdgeInsets.symmetric(
                              horizontal: 12, vertical: 8),
                          settings: pillSettings(),
                          borderRadius: 999,
                          pinchStrength: 1.0,
                        ),
                      ),
                    ),
                ],
              );
            },
          ),
        );
      },
    );
  }

  void _onDown(PointerDownEvent e) {
    final x = e.localPosition.dx;
    final y = e.localPosition.dy;
    _down = e.localPosition;
    _slopBreached = false;
    _startPillX = _pillX;

    final inPill = x >= _pillX - _tol &&
        x <= _pillX + _pillW + _tol &&
        y >= _pillTop - _tol &&
        y <= _pillTop + _pillH + _tol;
    if (!inPill) return;

    _longPress?.cancel();
    _longPress = Timer(kLongPressTimeout, () {
      if (!mounted || _down == null) return;
      _scrubbing = true;
      _scrubIndex = _defaultTab;
      HapticFeedback.mediumImpact();
      _springTo(_trackAct, 1.0, 1.0);
      widget.onScrubbingChanged(true);
    });
  }

  void _onMove(PointerMoveEvent e) {
    final down = _down;
    if (down == null) return;
    final now = DateTime.now();
    final dx = e.localPosition.dx - down.dx;
    final dy = e.localPosition.dy - down.dy;
    final last = _lastMoveAt;
    if (last != null) {
      final dt = now.difference(last).inMicroseconds / 1e6;
      if (dt > 0) _lastVx = dx / dt;
    }
    _lastMoveAt = now;

    if (!_scrubbing) {
      final slop = 3.0 * _dpr; // 触摸 slop 近似
      if (!_slopBreached && math.sqrt(dx * dx + dy * dy) > slop) {
        _slopBreached = true;
        _longPress?.cancel();
      }
      return;
    }

    // 橡皮筋越界（原生 rubberBand：constant 0.55，行程 0.4 格）
    final minX = _pillCenterX(0) - _pillW / 2;
    final maxX = _pillCenterX(kNavTabs.length - 1) - _pillW / 2;
    final dragRange = _slotW * 0.4;
    final raw = _startPillX + dx;
    final nx = raw < minX
        ? minX - _rubberBand(minX - raw, dragRange)
        : raw > maxX
            ? maxX + _rubberBand(raw - maxX, dragRange)
            : raw;
    final vertRange = _pillH * 0.35;
    final upRange = vertRange * 0.45;
    final ny = _rubberBandSigned(dy, dy < 0 ? upRange : vertRange);

    _trackX.value = nx;
    _trackY.value = ny;

    // 连续页位置 → (page, fraction)（原生 pagePosOf 换算）
    final pageF = (nx + _pillW / 2 - _rowPad - _slotW / 2) / _slotW;
    final base = pageF.floor();
    final frac = pageF - base;
    final pp = frac > 0.5 ? base + 1 : base;
    final ff = frac > 0.5 ? frac - 1 : frac;
    final page = pp.clamp(0, kNavTabs.length - 1).toInt();
    if (page != _scrubIndex) _scrubIndex = page;
    navBarChannel.invokeMethod('scrub', {'page': page, 'fraction': ff});
  }

  void _onUp(PointerUpEvent e) {
    final down = _down;
    _down = null;
    _lastMoveAt = null;
    _longPress?.cancel();

    if (_scrubbing) {
      _scrubbing = false;
      _springTo(_trackAct, 0.0, 1.0);
      _springTo(_trackY, 0.0, 0.75);
      _springTo(_trackX, _pillCenterX(_scrubIndex) - _pillW / 2, 0.75);
      navBarChannel.invokeMethod('scrubEnd', {'page': _scrubIndex});
      widget.onScrubbingChanged(false);
    } else if (down != null && !_slopBreached) {
      final slot =
          ((down.dx - _rowPad) / _slotW).floor().clamp(0, kNavTabs.length - 1);
      widget.onTabSelected(slot);
    }
  }

  void _onCancel(PointerCancelEvent e) {
    _down = null;
    _lastMoveAt = null;
    _longPress?.cancel();
    if (_scrubbing) {
      _scrubbing = false;
      _springTo(_trackAct, 0.0, 1.0);
      _springTo(_trackY, 0.0, 0.75);
      _springTo(_trackX, _pillCenterX(_scrubIndex) - _pillW / 2, 0.75);
      navBarChannel.invokeMethod('scrubEnd', {'page': _scrubIndex});
      widget.onScrubbingChanged(false);
    }
  }

  /// 原生 rubberBand 的 Dart 等价（1:1）。
  static double _rubberBand(double over, double dimension) {
    final d = math.max(dimension, 1.0);
    final abs = over.abs();
    if (abs <= 0) return 0;
    return (1 - 1 / (abs * 0.55 / d + 1)) * d;
  }

  static double _rubberBandSigned(double offset, double dimension) {
    final sign = offset < 0 ? -1.0 : 1.0;
    return sign * _rubberBand(offset.abs(), dimension);
  }
}
