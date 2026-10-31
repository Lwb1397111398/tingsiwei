package com.tingsiwei.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 应用主题。
 *
 * 这里刻意**不跟随系统取色**（旧的 dynamicColorScheme 会让界面在每台手机上长得不一样，
 * 且导图画布、状态色都无法与之协调）。品牌色固定为 [Palette] 里那套青绿 + 琥珀。
 */
@Composable
fun TingsiweiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 状态栏/导航栏图标颜色跟着主题走（浅色背景用深色图标），否则沉浸式状态栏看不见图标
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = TingsiweiTypography,
        shapes = TingsiweiShapes,
        content = content,
    )
}

private val LightColors = lightColorScheme(
    primary = Palette.LightPrimary,
    onPrimary = Palette.LightOnPrimary,
    primaryContainer = Palette.LightPrimaryContainer,
    onPrimaryContainer = Palette.LightOnPrimaryContainer,
    secondary = Palette.LightSecondary,
    onSecondary = Palette.LightOnSecondary,
    secondaryContainer = Palette.LightSecondaryContainer,
    onSecondaryContainer = Palette.LightOnSecondaryContainer,
    tertiary = Palette.LightTertiary,
    onTertiary = Palette.LightOnTertiary,
    tertiaryContainer = Palette.LightTertiaryContainer,
    onTertiaryContainer = Palette.LightOnTertiaryContainer,
    error = Palette.LightError,
    onError = Palette.LightOnError,
    errorContainer = Palette.LightErrorContainer,
    onErrorContainer = Palette.LightOnErrorContainer,
    background = Palette.LightBackground,
    onBackground = Palette.LightOnBackground,
    surface = Palette.LightSurface,
    onSurface = Palette.LightOnSurface,
    surfaceVariant = Palette.LightSurfaceVariant,
    onSurfaceVariant = Palette.LightOnSurfaceVariant,
    outline = Palette.LightOutline,
    outlineVariant = Palette.LightOutlineVariant,
    surfaceContainerLowest = Palette.LightSurfaceContainerLowest,
    surfaceContainerLow = Palette.LightSurfaceContainerLow,
    surfaceContainer = Palette.LightSurfaceContainer,
    surfaceContainerHigh = Palette.LightSurfaceContainerHigh,
    surfaceContainerHighest = Palette.LightSurfaceContainerHighest,
    inverseSurface = Palette.LightOnSurface,
    inverseOnSurface = Palette.LightBackground,
    scrim = Palette.LightOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = Palette.DarkPrimary,
    onPrimary = Palette.DarkOnPrimary,
    primaryContainer = Palette.DarkPrimaryContainer,
    onPrimaryContainer = Palette.DarkOnPrimaryContainer,
    secondary = Palette.DarkSecondary,
    onSecondary = Palette.DarkOnSecondary,
    secondaryContainer = Palette.DarkSecondaryContainer,
    onSecondaryContainer = Palette.DarkOnSecondaryContainer,
    tertiary = Palette.DarkTertiary,
    onTertiary = Palette.DarkOnTertiary,
    tertiaryContainer = Palette.DarkTertiaryContainer,
    onTertiaryContainer = Palette.DarkOnTertiaryContainer,
    error = Palette.DarkError,
    onError = Palette.DarkOnError,
    errorContainer = Palette.DarkErrorContainer,
    onErrorContainer = Palette.DarkOnErrorContainer,
    background = Palette.DarkBackground,
    onBackground = Palette.DarkOnBackground,
    surface = Palette.DarkSurface,
    onSurface = Palette.DarkOnSurface,
    surfaceVariant = Palette.DarkSurfaceVariant,
    onSurfaceVariant = Palette.DarkOnSurfaceVariant,
    outline = Palette.DarkOutline,
    outlineVariant = Palette.DarkOutlineVariant,
    surfaceContainerLowest = Palette.DarkSurfaceContainerLowest,
    surfaceContainerLow = Palette.DarkSurfaceContainerLow,
    surfaceContainer = Palette.DarkSurfaceContainer,
    surfaceContainerHigh = Palette.DarkSurfaceContainerHigh,
    surfaceContainerHighest = Palette.DarkSurfaceContainerHighest,
    inverseSurface = Palette.DarkOnSurface,
    inverseOnSurface = Palette.DarkBackground,
    scrim = Palette.DarkBackground,
)

/**
 * 导图画布配色：与 Compose 侧同一套色值，经 WebView 注入成 CSS 变量。
 * 分支颜色按层级轮转（根 → 一级分支各一色），避免整张图都是同一种蓝。
 */
object MapTheme {
    val Light = MapColors(
        background = "#F7FBFC",
        rootBackground = "#096868",
        rootText = "#F8FDFD",
        mainText = "#18252A",
        mainBackground = "#FFFFFF",
        nodeText = "#5A676C",
        selected = "#096868",
        panelBackground = "#FBFDFE",
        panelText = "#18252A",
        panelBorder = "#E2EBED",
        branchColors = listOf(
            "#096868", "#3E7FA0", "#C57722", "#2F7D5B", "#7A5BA6",
            "#B5503C", "#4B6D86", "#8A6B1F", "#1F6E70", "#6B5B95",
        ),
    )

    val Dark = MapColors(
        background = "#0E1518",
        rootBackground = "#68D0CA",
        rootText = "#002425",
        mainText = "#E2E9EB",
        mainBackground = "#1F2B2F",
        nodeText = "#A1ADB0",
        selected = "#68D0CA",
        panelBackground = "#1F2B2F",
        panelText = "#E2E9EB",
        panelBorder = "#333E42",
        branchColors = listOf(
            "#68D0CA", "#98C0DE", "#F6BB70", "#7FD3A9", "#B79BDB",
            "#F0937E", "#A9C6DD", "#E3C77E", "#7FC7C5", "#C4A9E0",
        ),
    )
}

data class MapColors(
    val background: String,
    val rootBackground: String,
    val rootText: String,
    val mainText: String,
    val mainBackground: String,
    val nodeText: String,
    val selected: String,
    val panelBackground: String,
    val panelText: String,
    val panelBorder: String,
    val branchColors: List<String>,
) {

    /**
     * 转成 mind-elixir 认识的主题对象（JSON）。
     *
     * 字段三块：`palette` 是一级分支的连接线颜色轮转，`cssVar` 是画布 CSS 变量，
     * `toolbar` 是底部工具栏的颜色（非 mind-elixir 字段，由 index.html 自己消费）。
     * `type` 必须是 light/dark，mind-elixir 用它补默认值。
     */
    fun toMindElixirThemeJson(dark: Boolean): String {
        val type = if (dark) "dark" else "light"
        val palette = branchColors.joinToString(",", "[", "]") { "\"$it\"" }
        val toolbar = if (dark) {
            """{"--tb-bg":"rgba(31,43,47,.97)","--tb-fg":"#E2E9EB","--tb-border":"rgba(226,233,235,.12)",""" +
                """"--tb-shadow":"rgba(0,0,0,.45)","--tb-active":"rgba(104,208,202,.20)"}"""
        } else {
            """{"--tb-bg":"rgba(255,255,255,.97)","--tb-fg":"#18252A","--tb-border":"rgba(24,37,42,.10)",""" +
                """"--tb-shadow":"rgba(24,37,42,.14)","--tb-active":"rgba(9,104,104,.12)"}"""
        }
        return """
            {
              "name": "${if (dark) "Tingsiwei Dark" else "Tingsiwei"}",
              "type": "$type",
              "palette": $palette,
              "toolbar": $toolbar,
              "cssVar": {
                "--node-gap-x": "28px",
                "--node-gap-y": "10px",
                "--main-gap-x": "60px",
                "--main-gap-y": "40px",
                "--root-radius": "24px",
                "--main-radius": "14px",
                "--root-color": "$rootText",
                "--root-bgcolor": "$rootBackground",
                "--root-border-color": "rgba(0,0,0,0)",
                "--main-border": "none",
                "--main-color": "$mainText",
                "--main-bgcolor": "$mainBackground",
                "--main-bgcolor-transparent": "$mainBackground",
                "--topic-padding": "4px 2px",
                "--color": "$nodeText",
                "--bgcolor": "$background",
                "--selected": "$selected",
                "--accent-color": "$selected",
                "--panel-color": "$panelText",
                "--panel-bgcolor": "$panelBackground",
                "--panel-border-color": "$panelBorder",
                "--map-padding": "36px 56px"
              }
            }
        """.trimIndent()
    }
}
