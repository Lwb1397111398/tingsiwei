package com.tingsiwei.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
import com.tingsiwei.app.ui.theme.Spacing
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

            // 状态行：一个点 + 一句话，替代原来干巴巴的「正在录音」
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isRecording && !isPaused -> {
                        StatusDot(MaterialTheme.colorScheme.error)
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

            // 计时：不录时也显示 00:00，但用弱色，避免「大数字空着」的突兀
            Text(
                Formatters.duration(durationMs),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light,
                color = if (isRecording) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
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

            // 结束 / 取消：录音中才出现
            AnimatedVisibility(visible = isRecording, enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vm.stopAndSave() },
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

            error?.let {
                Spacer(Modifier.height(Spacing.lg))
                InlineNotice(
                    text = it,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            Spacer(Modifier.height(Spacing.xl))

            // 系统识别的实时文字：独立成块，长文时不跟按钮抢空间
            if (mode == TranscribeMode.SYSTEM) {
                val showText = finalText + if (partialText.isNotEmpty()) "\n$partialText" else ""
                if (showText.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            Modifier
                                .padding(Spacing.lg)
                                .verticalScroll(rememberScrollState()),
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
 * 录音主按钮：录音中换成红色暂停态 + 一圈缓慢扩散的光环（真实的「在录」反馈），
 * 暂停态为琥珀色，待机态是主色麦克风。
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

    // 呼吸光环：只在不暂停的录音中出现；用 scale + alpha，不改尺寸，不会引起布局抖动
    val transition = rememberInfiniteTransition(label = "rec")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )

    Box(contentAlignment = Alignment.Center) {
        if (isRecording && !isPaused) {
            Box(
                Modifier
                    .size(104.dp)
                    .scale(pulseScale)
                    .background(scheme.error.copy(alpha = pulseAlpha), CircleShape),
            )
        }
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(104.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container,
                contentColor = content,
            ),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(42.dp))
        }
    }
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
