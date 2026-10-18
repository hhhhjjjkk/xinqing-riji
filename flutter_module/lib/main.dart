import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'navbar_app.dart';

/// 独立调试入口（`flutter run` 跑 module 自身，不经原生宿主）。
/// 嵌入场景走 [nav_bar_entry.dart] 的 `navBarMain`。
void main() async {
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
      child: _DemoHost(glassInitError: initError),
    ),
  );
}

/// 模拟原生的宿主环境：深色背景 + 底部导航栏（供桌面/模拟器单独调试）。
class _DemoHost extends StatelessWidget {
  const _DemoHost({this.glassInitError});

  final String? glassInitError;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF14161D),
      ),
      home: Scaffold(
        body: Column(
          children: [
            const Expanded(
              child: Center(
                child: Text(
                  'NavBar 模块独立调试\n（点击 / 长按拖动试试）',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.white54),
                ),
              ),
            ),
            SizedBox(
              height: 72,
              child: NavBarApp(glassInitError: glassInitError),
            ),
          ],
        ),
      ),
    );
  }
}
