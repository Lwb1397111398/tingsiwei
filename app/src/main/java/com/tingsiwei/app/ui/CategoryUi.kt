package com.tingsiwei.app.ui

import androidx.compose.ui.graphics.Color

/**
 * 分类 UI 共享小工具。分类不存颜色字段，按 id 从固定色板取：
 * 同一分类在筛选条、卡片徽标、选择器里永远同色，8 个轮转足够区分。
 */
object CategoryUi {
    private val palette = listOf(
        0xFF6750A4, // 紫
        0xFF00639B, // 蓝
        0xFF00696E, // 青
        0xFF3F6E24, // 绿
        0xFF8C5100, // 橙棕
        0xFF9C4146, // 红
        0xFF7D5260, // 玫瑰灰
        0xFF58565F, // 中灰
    )

    fun color(id: Long?): Color =
        if (id == null) Color(0xFF8A8A8E)
        else Color(palette[(id % palette.size).toInt()])
}
