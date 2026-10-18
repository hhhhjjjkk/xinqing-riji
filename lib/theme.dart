import 'package:flutter/material.dart';

import 'models.dart';

/// 每个主题色的完整色板（与 Kotlin 版逐值一致）
class AccentScheme {
  const AccentScheme({
    required this.lightPrimary,
    required this.lightOnPrimary,
    required this.lightContainer,
    required this.lightOnContainer,
    required this.darkPrimary,
    required this.darkOnPrimary,
    required this.darkContainer,
    required this.darkOnContainer,
  });

  final int lightPrimary, lightOnPrimary, lightContainer, lightOnContainer;
  final int darkPrimary, darkOnPrimary, darkContainer, darkOnContainer;
}

const kAccents = <AccentColor, AccentScheme>{
  AccentColor.amber: AccentScheme(
    lightPrimary: 0xFFE48600, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFFFDDB3, lightOnContainer: 0xFF2E1500,
    darkPrimary: 0xFFFFB95C, darkOnPrimary: 0xFF4A2800,
    darkContainer: 0xFF5D3F00, darkOnContainer: 0xFFFFDDB3,
  ),
  AccentColor.blue: AccentScheme(
    lightPrimary: 0xFF2B54A8, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFD8E2FF, lightOnContainer: 0xFF001945,
    darkPrimary: 0xFFAEC6FF, darkOnPrimary: 0xFF002A65,
    darkContainer: 0xFF00306E, darkOnContainer: 0xFFD8E2FF,
  ),
  AccentColor.green: AccentScheme(
    lightPrimary: 0xFF2E7D5B, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFB0F0CE, lightOnContainer: 0xFF002115,
    darkPrimary: 0xFF5FC99A, darkOnPrimary: 0xFF003823,
    darkContainer: 0xFF00512F, darkOnContainer: 0xFFB0F0CE,
  ),
  AccentColor.purple: AccentScheme(
    lightPrimary: 0xFF7A4FA3, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFEFD9FF, lightOnContainer: 0xFF2A1150,
    darkPrimary: 0xFFC4A3E8, darkOnPrimary: 0xFF421B67,
    darkContainer: 0xFF4B2672, darkOnContainer: 0xFFEFD9FF,
  ),
  AccentColor.pink: AccentScheme(
    lightPrimary: 0xFFC2185B, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFFFD9E2, lightOnContainer: 0xFF3E001D,
    darkPrimary: 0xFFFF8CA8, darkOnPrimary: 0xFF5E1130,
    darkContainer: 0xFF732945, darkOnContainer: 0xFFFFD9E2,
  ),
  AccentColor.cyan: AccentScheme(
    lightPrimary: 0xFF00707C, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFAAEDF8, lightOnContainer: 0xFF001F24,
    darkPrimary: 0xFF4FD8E8, darkOnPrimary: 0xFF00363D,
    darkContainer: 0xFF004F58, darkOnContainer: 0xFFAAEDF8,
  ),
  AccentColor.red: AccentScheme(
    lightPrimary: 0xFFB3261E, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFFFDAD6, lightOnContainer: 0xFF410E0B,
    darkPrimary: 0xFFFFB4AB, darkOnPrimary: 0xFF690005,
    darkContainer: 0xFF93000A, darkOnContainer: 0xFFFFDAD6,
  ),
  AccentColor.indigo: AccentScheme(
    lightPrimary: 0xFF3F51B5, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFE0E4FF, lightOnContainer: 0xFF00105C,
    darkPrimary: 0xFFB6C4FF, darkOnPrimary: 0xFF00218B,
    darkContainer: 0xFF2331A8, darkOnContainer: 0xFFE0E4FF,
  ),
  AccentColor.teal: AccentScheme(
    lightPrimary: 0xFF00695C, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFA7F0E0, lightOnContainer: 0xFF00201A,
    darkPrimary: 0xFF5FDBC4, darkOnPrimary: 0xFF003730,
    darkContainer: 0xFF005046, darkOnContainer: 0xFFA7F0E0,
  ),
  AccentColor.orange: AccentScheme(
    lightPrimary: 0xFFBF5B00, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFFFDCC2, lightOnContainer: 0xFF341100,
    darkPrimary: 0xFFFFB77C, darkOnPrimary: 0xFF552000,
    darkContainer: 0xFF7A3200, darkOnContainer: 0xFFFFDCC2,
  ),
  AccentColor.slate: AccentScheme(
    lightPrimary: 0xFF4A5C6A, lightOnPrimary: 0xFFFFFFFF,
    lightContainer: 0xFFD5E4F3, lightOnContainer: 0xFF0D1D28,
    darkPrimary: 0xFFB6C8D9, darkOnPrimary: 0xFF1E2F3C,
    darkContainer: 0xFF354755, darkOnContainer: 0xFFD5E4F3,
  ),
};

ColorScheme _schemeFromAccent(AccentScheme a, bool dark) {
  final primary = Color(dark ? a.darkPrimary : a.lightPrimary);
  final onPrimary = Color(dark ? a.darkOnPrimary : a.lightOnPrimary);
  final container = Color(dark ? a.darkContainer : a.lightContainer);
  final onContainer = Color(dark ? a.darkOnContainer : a.lightOnContainer);
  final base = dark ? ThemeData.dark().colorScheme : ThemeData.light().colorScheme;
  return base.copyWith(
    primary: primary,
    onPrimary: onPrimary,
    primaryContainer: container,
    onPrimaryContainer: onContainer,
    secondary: primary,
    onSecondary: onPrimary,
    secondaryContainer: container,
    onSecondaryContainer: onContainer,
  );
}

/// 纯黑背景：深色模式下把 surface 压成纯黑（OLED 省电）
ColorScheme _applyPureBlack(ColorScheme s) => s.copyWith(
      surface: Colors.black,
      surfaceContainerLowest: Colors.black,
      surfaceContainerLow: const Color(0xFF0A0A0C),
      surfaceContainer: const Color(0xFF101012),
      surfaceContainerHigh: const Color(0xFF101012),
      surfaceContainerHighest: const Color(0xFF141416),
    );

/// 构建应用主题（含动态色 / 强调色 / 纯黑 / 字号缩放）
ThemeData buildMoodTheme({
  required bool dark,
  required AppSettings settings,
  ColorScheme? dynamicScheme,
}) {
  var scheme = (settings.useDynamicColor && dynamicScheme != null)
      ? dynamicScheme
      : _schemeFromAccent(kAccents[settings.accentColor]!, dark);
  if (dark && settings.pureBlack) scheme = _applyPureBlack(scheme);

  final fontScale = settings.fontLevel.scale;
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    brightness: dark ? Brightness.dark : Brightness.light,
    textTheme: ThemeData(brightness: dark ? Brightness.dark : Brightness.light)
        .textTheme
        .apply(
          bodyColor: scheme.onSurface,
          displayColor: scheme.onSurface,
          fontSizeFactor: fontScale,
        ),
  );
}
