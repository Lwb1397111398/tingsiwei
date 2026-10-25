package com.tingsiwei.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌：「听的思维」的视觉底座。
 *
 * 配色思路：产品主线是「声音 → 结构」，主色取声波示意里的青（不是科技蓝，也不是笔记类
 * App 常见的紫）；中性色统一压向同一色系（色相 210~220），避免灰得发脏；琥珀色只用于
 * 「进行中 / 待处理」这类需要被注意到、但不该报警的状态。
 *
 * 所有颜色由 OKLCH 换算而来（L 亮度 / C 彩度 / H 色相）。浅色与深色两套各自独立取值，
 * 不是简单反相——深色下主色提亮、彩度略降，才不至于在暗底上刺眼。
 */
object Palette {

    // ---------- 浅色 ----------
    val LightPrimary = Color(0xFF096868)
    val LightOnPrimary = Color(0xFFF8FDFD)
    val LightPrimaryContainer = Color(0xFFD0EFEE)
    val LightOnPrimaryContainer = Color(0xFF00393A)

    val LightSecondary = Color(0xFF4B6D86)
    val LightOnSecondary = Color(0xFFF7FCFE)
    val LightSecondaryContainer = Color(0xFFDEECF6)
    val LightOnSecondaryContainer = Color(0xFF12303F)

    val LightTertiary = Color(0xFFC57722)
    val LightOnTertiary = Color(0xFFFFFCF7)
    val LightTertiaryContainer = Color(0xFFFFECD0)
    val LightOnTertiaryContainer = Color(0xFF522907)

    val LightError = Color(0xFFD02C2A)
    val LightOnError = Color(0xFFFFF8F7)
    val LightErrorContainer = Color(0xFFFFE6E3)
    val LightOnErrorContainer = Color(0xFF650E0E)

    val LightBackground = Color(0xFFF7FBFC)
    val LightOnBackground = Color(0xFF18252A)
    val LightSurface = Color(0xFFF1F7F8)
    val LightOnSurface = Color(0xFF18252A)
    val LightSurfaceVariant = Color(0xFFE2EBED)
    val LightOnSurfaceVariant = Color(0xFF5A676C)
    val LightOutline = Color(0xFFB4BEC1)
    val LightOutlineVariant = Color(0xFFD9DFE2)

    val LightSurfaceContainerLowest = Color(0xFFFBFDFE)
    val LightSurfaceContainerLow = Color(0xFFFBFDFE)
    val LightSurfaceContainer = Color(0xFFEAF2F3)
    val LightSurfaceContainerHigh = Color(0xFFE2EBED)
    val LightSurfaceContainerHighest = Color(0xFFDBE5E8)

    // ---------- 深色 ----------
    val DarkPrimary = Color(0xFF68D0CA)
    val DarkOnPrimary = Color(0xFF002425)
    val DarkPrimaryContainer = Color(0xFF064142)
    val DarkOnPrimaryContainer = Color(0xFFC1E7E5)

    val DarkSecondary = Color(0xFF98C0DE)
    val DarkOnSecondary = Color(0xFF06202E)
    val DarkSecondaryContainer = Color(0xFF1F3645)
    val DarkOnSecondaryContainer = Color(0xFFCFE4F4)

    val DarkTertiary = Color(0xFFF6BB70)
    val DarkOnTertiary = Color(0xFF2C1700)
    val DarkTertiaryContainer = Color(0xFF573410)
    val DarkOnTertiaryContainer = Color(0xFFF9E5C7)

    val DarkError = Color(0xFFF97770)
    val DarkOnError = Color(0xFF3A0807)
    val DarkErrorContainer = Color(0xFF6F191A)
    val DarkOnErrorContainer = Color(0xFFFCE1DE)

    val DarkBackground = Color(0xFF0E1518)
    val DarkOnBackground = Color(0xFFE2E9EB)
    val DarkSurface = Color(0xFF121B1E)
    val DarkOnSurface = Color(0xFFE2E9EB)
    val DarkSurfaceVariant = Color(0xFF2A363A)
    val DarkOnSurfaceVariant = Color(0xFFA1ADB0)
    val DarkOutline = Color(0xFF5F6B6F)
    val DarkOutlineVariant = Color(0xFF333E42)

    val DarkSurfaceContainerLowest = Color(0xFF0B1214)
    val DarkSurfaceContainerLow = Color(0xFF101719)
    val DarkSurfaceContainer = Color(0xFF182226)
    val DarkSurfaceContainerHigh = Color(0xFF1F2B2F)
    val DarkSurfaceContainerHighest = Color(0xFF273438)
}

/**
 * 中文正文的行高比 Material 默认值更松（约 1.6~1.65），一行里混排中英文和标点时不至于挤成一片。
 * 关掉字体自带的上下留白（includeFontPadding），标题与图标垂直居中才不会差一两个像素。
 */
private fun cjk(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

val TingsiweiTypography = Typography(
    displayLarge = cjk(48, 58, FontWeight.Medium, -0.5),
    displayMedium = cjk(40, 50, FontWeight.Medium, -0.5),
    displaySmall = cjk(34, 44, FontWeight.Medium, -0.25),
    headlineLarge = cjk(30, 40, FontWeight.SemiBold, -0.25),
    headlineMedium = cjk(26, 36, FontWeight.SemiBold, -0.2),
    headlineSmall = cjk(23, 32, FontWeight.SemiBold, -0.1),
    titleLarge = cjk(20, 29, FontWeight.SemiBold),
    titleMedium = cjk(16, 25, FontWeight.SemiBold),
    titleSmall = cjk(14, 22, FontWeight.SemiBold),
    bodyLarge = cjk(16, 27),
    bodyMedium = cjk(14, 24),
    bodySmall = cjk(13, 22),
    labelLarge = cjk(14, 20, FontWeight.Medium, 0.1),
    labelMedium = cjk(12, 17, FontWeight.Medium, 0.2),
    labelSmall = cjk(11, 16, FontWeight.Medium, 0.3),
)

/** 圆角尺度：小元素 8、卡片 20、浮层 26、全屏面板 28。 */
val TingsiweiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 页面级间距，统一从这里取，避免每个页面各写一版 12/16 混用。 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** 页面左右边距 */
    val screen = 20.dp

    /** 底部操作区预留高度（避开手势条） */
    val bottomBar = 8.dp
}
