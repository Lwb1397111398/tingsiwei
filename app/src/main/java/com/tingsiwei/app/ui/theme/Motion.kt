package com.tingsiwei.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 动效令牌：全 App 的动画时长、缓动、按压手感统一从这里取，页面里不再各写一版。
 *
 * 三条自制约束（保证「流畅不臃肿」）：
 * - 只动 scale / alpha / translation（graphicsLayer 层），不动尺寸布局，避免触发重新测量；
 * - 时长三档封顶 340ms——超过 400ms 的过渡一次就能被感知为「慢」；
 * - 无意义处不动画：常驻元素不呼吸（录音键的光环除外，那是状态本身）。
 */
object Motion {
    /** 微反馈：按压、小控件状态翻转 */
    const val Snappy = 150

    /** 常规过渡：横幅出入场、颜色渐变、展开收起 */
    const val Standard = 240

    /** 强调：页面/列表入场这类「一次性登场」 */
    const val Emphasized = 340

    /** Material 强调缓动：起步快、收尾缓，入场位移用它最有「落地感」 */
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> standard(duration: Int = Standard) = tween<T>(duration, easing = FastOutSlowInEasing)

    fun <T> emphasized(duration: Int = Emphasized) = tween<T>(duration, easing = EmphasizedEasing)
}

/**
 * 按压缩放：与 [androidx.compose.foundation.interaction] 联动。
 * 用法：自建一个 MutableInteractionSource 传给 clickable，同时喂给这里拿缩放值。
 */
@Composable
fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.standard(Motion.Snappy),
        label = "pressScale",
    ).value
}

/** 按 [rememberPressScale] 给出的值做等比缩放（纯 graphicsLayer，不触发布局）。 */
fun Modifier.pressScale(scale: Float) = graphicsLayer {
    scaleX = scale
    scaleY = scale
}

/**
 * 错峰入场：一次性 fade + 轻微上浮（12dp）。
 * [order] 是入场序号，每项比前一项晚 40ms，封顶 240ms——整组在半秒内依次落定。
 * [enabled] 为 false 时直接完全可见（列表项滚出视口再回来时不重播动画）。
 *
 * 必须是「分支式」实现而不是「参数式」：动画挂在 [LaunchedEffect](`Unit`) 上，
 * 同一个组合实例内 key 永不变、协程绝不会被重启取消。若改回把 [enabled] 当
 * effect key（`LaunchedEffect(enabled)`），enabled 在动画进行中翻转会取消在飞
 * 的协程，alpha 永久冻在中途（通常 0）——内容透明但可点击，即首页白屏 bug
 * （2026-10-31 真机实测）。
 */
@Composable
fun Modifier.staggerIn(order: Int, enabled: Boolean = true): Modifier {
    if (!enabled) return Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(min(order * 40L, 240L))
        progress.animateTo(1f, Motion.emphasized())
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 12.dp.toPx()
    }
}

/** 轻触感：确认类动作（开始/停止录音、发送）。API 30 以下退回按键触感。 */
fun android.view.View.hapticConfirm() {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
    } else {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/** 轻触感：开关类动作（展开/收起菜单），比确认更轻。 */
fun android.view.View.hapticTick() {
    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
}
