package com.mooddiary.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigInteger

/**
 * 心情目录的颜色编解码回归测试。
 *
 * 曾经的事故：保存时写入 Color.value（打包后的 ULong），其数值可超出 Long
 * 范围（0xFFFFB300 打包后为 18446659411314212864）。读取时 toLong() 溢出，
 * 整份目录解析为空，用户手动添加的心情在重启/更新后全部消失。
 */
class MoodCatalogCodecTest {

    /** 新格式：存 Int ARGB，必须无损往返，且不超出 Long */
    @Test
    fun `ARGB整数编码可无损往返且不溢出`() {
        val colors = listOf(
            Color(0xFFFFB300), Color(0xFF43A047), Color(0xFF78909C),
            Color(0xFF42A5F5), Color(0xFFEF5350), Color(0xFF26A69A),
            Color(0xFF7E57C2), Color(0xFFEC407A), Color(0xFFFFFFFF),
            Color(0xFF000000)
        )
        colors.forEach { c ->
            val text = c.toArgb().toString()
            // 必须能被 Long 安全解析（不会溢出）
            assertNotNull("颜色 $text 超出 Long 范围", text.toLongOrNull())
            // 还原后必须与原色一致
            assertEquals(c, Color(text.toInt()))
        }
    }

    /** 旧的 Color.value 文本确实超出 Long 范围（复现事故条件） */
    @Test
    fun `旧的ColorValue编码会溢出Long`() {
        val packed = Color(0xFFFFB300).value
        val text = packed.toString()
        // 复现：这个数字无法用 toLong 解析
        assertEquals(null, text.toLongOrNull())
        // 但用 BigInteger 取高 32 位可以还原
        val argb = BigInteger(text).shiftRight(32).toLong() and 0xFFFFFFFFL
        assertEquals(Color(0xFFFFB300), Color(argb.toInt()))
    }

    /** 旧格式数据必须能被兼容读取，不能丢失 */
    @Test
    fun `旧格式颜色可被兼容还原`() {
        val original = listOf(
            Color(0xFFFFB300), Color(0xFF43A047), Color(0xFF42A5F5)
        )
        original.forEach { c ->
            val legacyText = c.value.toString()          // 旧版写入的内容
            // 新版的兼容解析路径
            val restored = if (legacyText.toIntOrNull() != null) {
                Color(legacyText.toInt())
            } else {
                val argb = BigInteger(legacyText).shiftRight(32).toLong() and 0xFFFFFFFFL
                Color(argb.toInt())
            }
            assertEquals(c, restored)
        }
    }
}
