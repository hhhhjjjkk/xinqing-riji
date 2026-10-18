package com.mooddiary.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 把 Flutter 引擎预热从 `Application.onCreate` 左移到 `ContentProvider.onCreate`。
 *
 * 执行顺序：`attachBaseContext` → `installContentProviders`（本 Provider）→
 * `callApplicationOnCreate`（[MoodDiaryApp]）。因此引擎初始化比原先提前启动，
 * 与 Activity 创建、Compose 首帧并行推进，缩短冷启动的感知时间。
 *
 * 该 Provider 不提供任何数据访问：authorities 唯一、`exported=false`、
 * 无 intent-filter，外部不可触达；增删改查方法一律空实现。
 * [MoodDiaryApp] 中的预热保留作兜底（provider 被系统禁用时仍可工作），
 * [FlutterNavHost.obtainEngine] 为幂等单例，重复调用无副作用。
 */
class FlutterWarmupProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        FlutterNavHost.obtainEngine(context ?: return false)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun getType(uri: Uri): String? = null
}
