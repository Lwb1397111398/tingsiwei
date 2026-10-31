package com.tingsiwei.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingsiwei.app.App
import com.tingsiwei.app.data.TranscribeMode
import com.tingsiwei.app.record.RecordSession
import com.tingsiwei.app.ui.components.InlineNotice
import com.tingsiwei.app.ui.components.StatusDot
import com.tingsiwei.app.ui.theme.Motion
import com.tingsiwei.app.ui.theme.Spacing
import com.tingsiwei.app.ui.theme.hapticConfirm
import com.tingsiwei.app.ui.theme.pressScale
import com.tingsiwei.app.ui.theme.rememberPressScale
import com.tingsiwei.app.util.Formatters
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(onSaved: (Long) -> Unit, onBack: () -> Unit) {
    val vm: RecordViewModel = viewModel(factory = recordVmFactory)
    val isRecording by vm.isRecording.collectAsState()
    val isPaused by vm.isPaused.collectAsState()
    val durationMs by vm.durationMs.collectAsState()
    val partialText by vm.partialText.collectAsState()
    val finalText by vm.finalText.collectAsState()
    val mode by vm.transcribeMode.collectAsState()
    val error by vm.error.collectAsState()

    // 保存完成 → 跳详情页（saved 是一次性事件流）
    LaunchedEffect(Unit) {
        vm.saved.collect { id -> onSaved(id) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) vm.start()
        else vm.error.value = "没有麦克风权限，无法录音"
    }
    val view = LocalView.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("录音", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = Spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.lg))

            // 状态行：一个点 + 一句话，替代原来干巴巴的「正在录音」；录音中的红点缓慢呼吸
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isRecording && !isPaused -> {
                        PulsingDot(MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "正在录音",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    isPaused -> {
                        StatusDot(MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已暂停",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    else -> Text(
                        "准备就绪",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // 计时：不录时也显示 00:00，但用弱色，避免「大数字空着」的突兀；色变跟随渐变
            val timerColor by animateColorAsState(
                targetValue = if (isRecording) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(Motion.Standard),
                label = "timerColor",
            )
            Text(
                Formatters.duration(durationMs),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light,
                color = timerColor,
            )

            Spacer(Modifier.height(Spacing.sm))
            Text(
                if (mode == TranscribeMode.SYSTEM) "手机自带识别 · 边录边出字"
                else "离线模型 · 录完自动转写",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.xxl))

            RecordButton(
                isRecording = isRecording,
                isPaused = isPaused,
                onClick = {
                    if (!isRecording) {
                        val granted = ContextCompat.checkSelfPermission(
                            App.get(), Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            view.hapticConfirm()
                            vm.start()
                        } else {
                            val perms = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permLauncher.launch(perms.toTypedArray())
                        }
                    } else if (isPaused) vm.resume() else vm.pause()
                },
            )

            Spacer(Modifier.height(Spacing.lg))
            Text(
                when {
                    !isRecording -> "点一下开始"
                    isPaused -> "点上方按钮继续"
                    else -> "点上方按钮暂停"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.xl))

            // 结束 / 取消：录音中才出现，从按钮下方展开
            AnimatedVisibility(
                visible = isRecording,
                enter = expandVertically(Motion.standard()) + fadeIn(tween(Motion.Snappy)),
                exit = shrinkVertically(Motion.standard()) + fadeOut(tween(Motion.Snappy)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = {
                            view.hapticConfirm()
                            vm.stopAndSave()
                        },
                        shape = CircleShape,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("完成并保存")
                    }
                    Spacer(Modifier.width(Spacing.md))
                    TextButton(onClick = { vm.cancelIfAny(); onBack() }) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 错误提示：展开出现、收起消失（缓存最后一次文案让退场有内容可显示）
            val lastError = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            if (error != null) lastError.value = error
            AnimatedVisibility(
                visible = error != null,
                enter = expandVertically(Motion.standard()) + fadeIn(tween(Motion.Snappy)),
                exit = shrinkVertically(Motion.standard()) + fadeOut(tween(Motion.Snappy)),
            ) {
                lastError.value?.let {
                    Column {
                        Spacer(Modifier.height(Spacing.sm))
                        InlineNotice(
                            text = it,
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            // 系统识别的实时文字：独立成块，长文时不跟按钮抢空间；新字进来时自动滚到底
            if (mode == TranscribeMode.SYSTEM) {
                val showText = finalText + if (partialText.isNotEmpty()) "\n$partialText" else ""
                val liveScroll = rememberScrollState()
                LaunchedEffect(showText) {
                    if (showText.isNotBlank()) {
                        liveScroll.animateScrollTo(liveScroll.maxValue, Motion.standard())
                    }
                }
                AnimatedVisibility(
                    visible = showText.isNotBlank(),
                    enter = expandVertically(Motion.standard()) + fadeIn(tween(Motion.Snappy)),
                    exit = shrinkVertically(Motion.standard()) + fadeOut(tween(Motion.Snappy)),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            Modifier
                                .padding(Spacing.lg)
                                .verticalScroll(liveScroll),
                        ) {
                            Text(
                                "实时文字",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                showText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            } else if (!isRecording) {
                InlineNotice(
                    text = "录音中可以锁屏、切到别的应用，录音不会中断；完成后会在通知栏出现进度。",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * 录音主按钮：录音中换成红色暂停态 + 双波纹光环（两圈错相扩散，像声波一圈圈发出去），
 * 暂停态为琥珀色，待机态是主色麦克风。颜色渐变、图标缩放切换、按压微缩，全程无硬切。
 */
@Composable
private fun RecordButton(isRecording: Boolean, isPaused: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        !isRecording -> scheme.primaryContainer
        isPaused -> scheme.tertiaryContainer
        else -> scheme.errorContainer
    }
    val content = when {
        !isRecording -> scheme.onPrimaryContainer
        isPaused -> scheme.onTertiaryContainer
        else -> scheme.onErrorContainer
    }
    val icon = when {
        isPaused -> Icons.Filled.PlayArrow
        isRecording -> Icons.Filled.Pause
        else -> Icons.Filled.Mic
    }
    val containerColor by animateColorAsState(container, tween(Motion.Standard), label = "btnContainer")
    val contentColor by animateColorAsState(content, tween(Motion.Standard), label = "btnContent")

    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.94f)

    Box(contentAlignment = Alignment.Center) {
        if (isRecording && !isPaused) {
            RippleRing(delayMs = 0, color = scheme.error)
            RippleRing(delayMs = RingPeriodMs / 2, color = scheme.error)
        }
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier
                .size(104.dp)
                .pressScale(scale),
            shape = CircleShape,
            interactionSource = interaction,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
        ) {
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    (scaleIn(Motion.standard(Motion.Snappy), initialScale = 0.55f) + fadeIn(tween(Motion.Snappy))) togetherWith
                        (scaleOut(Motion.standard(Motion.Snappy), targetScale = 0.55f) + fadeOut(tween(Motion.Snappy)))
                },
                label = "recordIcon",
            ) { ic ->
                Icon(ic, contentDescription = null, modifier = Modifier.size(42.dp))
            }
        }
    }
}

/** 波纹光环周期（单圈从发出到散尽）。 */
private const val RingPeriodMs = 2000

/**
 * 一圈波纹：从按钮边缘向外扩散（scale 1→1.4），alpha 先快速亮起再缓缓散尽；
 * [delayMs] 错开相位，两圈叠出「持续发出声波」的节奏。只动 scale/alpha，不抖布局。
 */
@Composable
private fun RippleRing(delayMs: Int, color: Color) {
    val transition = rememberInfiniteTransition(label = "ring$delayMs")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = RingPeriodMs
                1f at 0 using LinearEasing
                1.4f at RingPeriodMs using LinearEasing
            },
            initialStartOffset = StartOffset(delayMs),
        ),
        label = "ringScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = RingPeriodMs
                0f at 0 using LinearEasing
                0.30f at RingPeriodMs / 6 using LinearEasing
                0f at RingPeriodMs using FastOutSlowInEasing
            },
            initialStartOffset = StartOffset(delayMs),
        ),
        label = "ringAlpha",
    )
    Box(
        Modifier
            .size(104.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .background(color, CircleShape),
    )
}

/** 录音中的状态小点：缓慢呼吸（alpha 1↔0.35），给「在录」一个活的信号。 */
@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulseDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseDotAlpha",
    )
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}

private val recordVmFactory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordViewModel() as T
}

/**
 * 录音状态都在进程级 RecordSession（配合前台服务）里，ViewModel 只是转发给界面。
 * 离开页面、锁屏、划掉后台都不会中断录音。
 */
class RecordViewModel : ViewModel() {

    val isRecording = RecordSession.isRecording
    val isPaused = RecordSession.isPaused
    val durationMs = RecordSession.durationMs
    val partialText = RecordSession.partialText
    val finalText = RecordSession.finalText
    val error = RecordSession.error
    val transcribeMode = RecordSession.transcribeMode
    val saved = RecordSession.saved

    init {
        viewModelScope.launch {
            RecordSession.transcribeMode.value = App.get().settings.current().transcribeMode
        }
    }

    fun start() = RecordSession.start()
    fun pause() = RecordSession.pause()
    fun resume() = RecordSession.resume()
    fun stopAndSave() = RecordSession.stopAndSave()
    fun cancelIfAny() = RecordSession.cancelIfAny()
}
