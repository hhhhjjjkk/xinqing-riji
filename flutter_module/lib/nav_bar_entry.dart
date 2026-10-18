import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'navbar_app.dart';

/// 原生宿主（MainActivity 的 FlutterEngine）通过
/// `executeDartEntrypoint(..., "navBarMain")` 启动本入口。
///
/// 与 sample 的 main() 一致：
///   1. 预加载全部着色器（失败则降级渲染，不能黑屏）；
///   2. wrap 时钉死 premium 画质（min=max=premium）——
///      非 premium 路径会在手势进行中 reparent 交互子树，
///      导致长按冻结 bug（见 sample main.dart 的长注释）。
@pragma('vm:entry-point')
void navBarMain() async {
  WidgetsFlutterBinding.ensureInitialized();

  String? initError;
  try {
    await LiquidGlassWidgets.initialize();
  } catch (e) {
    initError = e.toString();
  }

  runApp(
    LiquidGlassWidgets.wrap(
      brightnessResolver: Theme.maybeBrightnessOf,
      adaptiveQuality: true,
      adaptiveConfig: const GlassAdaptiveScopeConfig(
        minQuality: GlassQuality.premium,
        maxQuality: GlassQuality.premium,
        initialQuality: GlassQuality.premium,
        allowStepUp: false,
      ),
      theme: GlassThemeData.simple(
        blur: 3,
        thickness: 28,
        quality: GlassQuality.premium,
      ),
      child: NavBarApp(glassInitError: initError),
    ),
  );
}
