import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'glass_nav_bar.dart';

/// 按压 Q 弹（可复用于任意控件）：按下时缩小到 [pressedScale]，
/// 松手用带回弹的 LowBouncy 弹簧弹回（与 Kotlin 版 pressBounce 同参数）。
///
/// 通过 Listener 自行追踪按压，不消费事件，
/// 不会阻断子树自身的点击/手势行为。
class PressBounce extends StatefulWidget {
  const PressBounce({
    super.key,
    required this.child,
    this.pressedScale = 0.92,
    this.enabled = true,
  });

  final Widget child;
  final double pressedScale;
  final bool enabled;

  @override
  State<PressBounce> createState() => _PressBounceState();
}

class _PressBounceState extends State<PressBounce>
    with SingleTickerProviderStateMixin {
  late final SpringTrack _track;
  bool _pressed = false;

  @override
  void initState() {
    super.initState();
    _track = SpringTrack(this);
    _track.value = 1;
  }

  @override
  void dispose() {
    _track.dispose();
    super.dispose();
  }

  void _setPressed(bool v) {
    if (!widget.enabled) return;
    if (_pressed == v) return;
    _pressed = v;
    // Compose spring 换算：LowBouncy ζ=0.75 / StiffnessMedium k=1500
    _track.animateTo(
      v ? widget.pressedScale : 1,
      2 * math.sqrt(kSpringStiffness) * 0.75,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerDown: (_) => _setPressed(true),
      onPointerUp: (_) => _setPressed(false),
      onPointerCancel: (_) => _setPressed(false),
      child: AnimatedBuilder(
        animation: _track,
        builder: (context, child) => Transform.scale(
          scale: _track.value,
          child: child,
        ),
        child: widget.child,
      ),
    );
  }
}

/// FAB 的 Q 弹按压：按下放大到 [pressedScale]（1:1 移植 BouncyPressSurface
/// 的「按下膨胀」——56dp 圆钮按下最长边增长 17dp ≈ ×1.30），松手 bouncy 回弹。
class BouncyFab extends StatefulWidget {
  const BouncyFab({
    super.key,
    required this.onTap,
    this.pressedScale = 1.30,
  });

  final VoidCallback onTap;
  final double pressedScale;

  @override
  State<BouncyFab> createState() => _BouncyFabState();
}

class _BouncyFabState extends State<BouncyFab>
    with SingleTickerProviderStateMixin {
  late final SpringTrack _track;

  @override
  void initState() {
    super.initState();
    _track = SpringTrack(this);
    _track.value = 1;
  }

  @override
  void dispose() {
    _track.dispose();
    super.dispose();
  }

  void _setPressed(bool v) {
    // 按下：ζ=0.85 / k≈1754.6（BouncyPressSurface 的膨胀弹簧）
    _track.animateTo(
      v ? widget.pressedScale : 1,
      v ? 2 * math.sqrt(1754.6) * 0.85 : 2 * math.sqrt(503.55) * 0.55,
    );
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Listener(
      onPointerDown: (_) => _setPressed(true),
      onPointerUp: (_) => _setPressed(false),
      onPointerCancel: (_) => _setPressed(false),
      child: AnimatedBuilder(
        animation: _track,
        builder: (context, child) => Transform.scale(
          scale: _track.value,
          child: child,
        ),
        child: GestureDetector(
          onTap: widget.onTap,
          child: Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: scheme.primary,
              boxShadow: [
                BoxShadow(
                  color: scheme.primary.withValues(alpha: 0.35),
                  blurRadius: 12,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: Icon(Icons.add, color: scheme.onPrimary, size: 28),
          ),
        ),
      ),
    );
  }
}
