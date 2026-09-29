package com.mooddiary.app.icons

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 4 个仅在 material-icons-extended 中提供的图标，在此以矢量路径自建。
 *
 * 为什么要自建：material-icons-extended 打包了上万个图标，整个库
 * 体积高达 34MB，而本项目只用到其中 4 个（其余 6 个在 material-icons-core
 * 里已有）。移除该库可省下绝大部分安装体积，且不需要开启 R8 代码裁剪
 * ——裁剪 Compose 运行时会带来闪退风险。
 *
 * 路径数据取自 Google 官方 Material Icons（materialicons v1，24dp），
 * 与库中版本图形完全一致。
 */
object ExtraIcons {

    /** 统计：柱状图 */
    val BarChart: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.BarChart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(5f, 9.2f); horizontalLineTo(8f); verticalLineTo(19f)
                horizontalLineTo(5f); close()
                moveTo(10.6f, 5f); horizontalLineTo(13.4f); verticalLineTo(19f)
                horizontalLineTo(10.6f); close()
                moveTo(16.2f, 13f); horizontalLineTo(19f); verticalLineTo(19f)
                horizontalLineTo(16.2f); close()
            }
        }.build()
    }

    /** 日历：月份视图 */
    val CalendarMonth: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.CalendarMonth",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 4f); horizontalLineTo(18f); verticalLineTo(2f)
                horizontalLineTo(16f); verticalLineTo(4f)
                horizontalLineTo(8f); verticalLineTo(2f)
                horizontalLineTo(6f); verticalLineTo(4f)
                horizontalLineTo(5f)
                curveTo(3.89f, 4f, 3.01f, 4.9f, 3.01f, 6f)
                lineTo(3f, 20f)
                curveTo(3f, 21.1f, 3.89f, 22f, 5f, 22f)
                horizontalLineTo(19f)
                curveTo(20.1f, 22f, 21f, 21.1f, 21f, 20f)
                verticalLineTo(6f)
                curveTo(21f, 4.9f, 20.1f, 4f, 19f, 4f)
                close()
                // 内部挖空部分
                moveTo(19f, 20f); horizontalLineTo(5f); verticalLineTo(10f)
                horizontalLineTo(19f); verticalLineTo(20f); close()
                // 六个小格
                moveTo(9f, 14f); horizontalLineTo(7f); verticalLineTo(12f)
                horizontalLineTo(9f); verticalLineTo(14f); close()
                moveTo(13f, 14f); horizontalLineTo(11f); verticalLineTo(12f)
                horizontalLineTo(13f); verticalLineTo(14f); close()
                moveTo(17f, 14f); horizontalLineTo(15f); verticalLineTo(12f)
                horizontalLineTo(17f); verticalLineTo(14f); close()
                moveTo(9f, 18f); horizontalLineTo(7f); verticalLineTo(16f)
                horizontalLineTo(9f); verticalLineTo(18f); close()
                moveTo(13f, 18f); horizontalLineTo(11f); verticalLineTo(16f)
                horizontalLineTo(13f); verticalLineTo(18f); close()
                moveTo(17f, 18f); horizontalLineTo(15f); verticalLineTo(16f)
                horizontalLineTo(17f); verticalLineTo(18f); close()
            }
        }.build()
    }

    /** 删除：永久删除（带叉的垃圾桶） */
    val DeleteForever: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.DeleteForever",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 19f)
                curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
                horizontalLineTo(16f)
                curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f)
                verticalLineTo(7f)
                horizontalLineTo(6f)
                verticalLineTo(19f)
                close()
                // 中间的叉
                moveTo(8.46f, 11.88f)
                lineTo(9.87f, 10.47f)
                lineTo(12f, 12.59f)
                lineTo(14.12f, 10.47f)
                lineTo(15.53f, 11.88f)
                lineTo(13.41f, 14f)
                lineTo(15.53f, 16.12f)
                lineTo(14.12f, 17.53f)
                lineTo(12f, 15.41f)
                lineTo(9.88f, 17.53f)
                lineTo(8.47f, 16.12f)
                lineTo(10.59f, 14f)
                close()
                // 桶盖
                moveTo(15.5f, 4f)
                lineTo(14.5f, 3f)
                horizontalLineTo(9.5f)
                lineTo(8.5f, 4f)
                horizontalLineTo(5f)
                verticalLineTo(6f)
                horizontalLineTo(19f)
                verticalLineTo(4f)
                close()
            }
        }.build()
    }

    /** 通知：已关闭（带斜杠的铃铛） */
    val NotificationsOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.NotificationsOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 18.69f)
                lineTo(7.84f, 6.14f)
                lineTo(5.27f, 3.49f)
                lineTo(4f, 4.76f)
                lineTo(6.8f, 7.56f)
                verticalLineTo(7.57f)
                curveTo(6.28f, 8.56f, 6f, 9.73f, 6f, 10.99f)
                verticalLineTo(15.99f)
                lineTo(4f, 17.99f)
                verticalLineTo(18.99f)
                horizontalLineTo(17.73f)
                lineTo(19.73f, 20.99f)
                lineTo(21f, 19.72f)
                close()
                moveTo(12f, 22f)
                curveTo(13.11f, 22f, 14f, 21.11f, 14f, 20f)
                horizontalLineTo(10f)
                curveTo(10f, 21.11f, 10.89f, 22f, 12f, 22f)
                close()
                moveTo(18f, 14.68f)
                verticalLineTo(11f)
                curveTo(18f, 7.92f, 16.36f, 5.36f, 13.5f, 4.68f)
                verticalLineTo(4f)
                curveTo(13.5f, 3.17f, 12.83f, 2.5f, 12f, 2.5f)
                curveTo(11.17f, 2.5f, 10.5f, 3.17f, 10.5f, 4f)
                verticalLineTo(4.68f)
                curveTo(10.35f, 4.71f, 10.21f, 4.76f, 10.08f, 4.8f)
                curveTo(9.98f, 4.83f, 9.88f, 4.87f, 9.78f, 4.91f)
                curveTo(9.77f, 4.91f, 9.77f, 4.91f, 9.76f, 4.92f)
                curveTo(9.53f, 5.01f, 9.3f, 5.12f, 9.08f, 5.23f)
                curveTo(9.08f, 5.23f, 9.07f, 5.23f, 9.07f, 5.24f)
                close()
            }
        }.build()
    }
}
