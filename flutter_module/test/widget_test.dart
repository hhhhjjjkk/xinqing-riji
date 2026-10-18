// NavBar 模块的冒烟测试。
//
// 只覆盖不依赖上游着色器的降级路径（glassInitError 非空 → 纯色 UI）——
// 真玻璃路径需要 GPU（flutter test 无 GPU 上下文），由真机验证。
// 纯逻辑（弹簧解析解 / 橡皮筋 / 页码换算）见单元测试。

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mooddiary_navbar/navbar_app.dart';

void main() {
  testWidgets('降级路径渲染四个 tab 且可点', (WidgetTester tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SizedBox(
            height: 72,
            child: NavBarApp(glassInitError: 'test'),
          ),
        ),
      ),
    );

    expect(find.text('日历'), findsOneWidget);
    expect(find.text('记录'), findsOneWidget);
    expect(find.text('统计'), findsOneWidget);
    expect(find.text('设置'), findsOneWidget);

    // 点击「设置」→ onSelect 走 MethodChannel（测试中无原生对端，
    // invoke 静默失败，但 setState 已更新选中态，不抛异常即通过）。
    await tester.tap(find.text('设置'));
    await tester.pump();
  });
}
