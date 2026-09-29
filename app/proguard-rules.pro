# —— 心情日记 R8 保留规则 ——
#
# 说明：本项目使用 Compose + Room + 协程，其中若干入口在运行时被
# 「按名字/顺序/反射」访问。若被 R8 重命名或裁剪，会在启动时直接崩溃。
# 因此这里采取「安全优先」策略：宁可多保留一些代码，也要确保可运行。

# ---------- 1. 本项目全部类 ----------
# 本项目代码量很小（约 3000 行），整体保留的体积代价可忽略，
# 却能彻底避免 Room/枚举/数据类被裁剪导致的启动崩溃。
-keep class com.mooddiary.app.** { *; }
-keep interface com.mooddiary.app.** { *; }
-keep enum com.mooddiary.app.** { *; }

# 枚举的 values()/valueOf()/顺序必须保留：
# 设置项大量使用 entries/ordinal，且 ordinal 被持久化到 SharedPreferences。
-keepclassmembers enum com.mooddiary.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

# ---------- 2. Room ----------
# 生成类由 Room 按名字反射查找（<数据库类名>_Impl），必须保留。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class **_Impl { *; }
-keep @androidx.room.Entity class * { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# ---------- 3. Compose 运行时 ----------
# Compose 的编译器插件会生成大量按名字查找的合成方法，
# 裁剪它们会导致运行时崩溃。整体保留运行时与 UI 基础库。
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.material.icons.** { *; }
-keep class androidx.compose.animation.** { *; }

# ---------- 4. Kotlin 与协程 ----------
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ---------- 5. 通知：RemoteViews 依赖资源 id ----------
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ---------- 6. AndroidX 基础库 ----------
-keep class androidx.lifecycle.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }

# ---------- 7. 元数据 ----------
# 保留签名与注解，Room/Kotlin 反射依赖它们。
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
