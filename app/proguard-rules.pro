# —— 心情日记 R8 保留规则 ——
#
# 绝大多数情况无需手写规则：Compose、Lifecycle、Room 等都已自带 consumer rules。
# 这里只保留少数「运行时通过反射查找」的入口。

# Room 生成的实现类由 Room 按名字反射查找（<数据库类名>_Impl），
# 因此必须保留，否则开机会因找不到实现类而崩溃。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class **_Impl { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# 通知使用 RemoteViews 通过资源 id 引用布局与控件，
# 保留 R 类字段，保证 setTextViewText/setOnClickPendingIntent 不失效。
-keepclassmembers class **.R$* {
    public static <fields>;
}

# 保留行号，便于崩溃定位；体积代价很小
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
