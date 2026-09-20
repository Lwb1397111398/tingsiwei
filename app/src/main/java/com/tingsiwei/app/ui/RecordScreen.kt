package com.tingsiwei.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingsiwei.app.App
import com.tingsiwei.app.data.TranscribeMode
import com.tingsiwei.app.record.RecordSession
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
        topBar = {
            TopAppBar(
                title = { Text("录音") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                if (isRecording) "正在录音" else "点下方按钮开始录音",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                Formatters.duration(durationMs),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                "识别方式：" + if (mode == TranscribeMode.SYSTEM) "手机自带识别（边录边转）" else "离线模型（录完后转写）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isRecording) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "锁屏或离开本页录音都会继续，从通知栏可回到本页或结束录音",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(32.dp))

            // 大录音按钮
            FilledIconButton(
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
                modifier = Modifier.size(96.dp),
            ) {
                Icon(
                    if (isRecording && !isPaused) Icons.Filled.Pause else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = if (isRecording && !isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(Modifier.height(24.dp))
            if (isRecording) {
                Row {
                    OutlinedButton(onClick = { vm.stopAndSave() }) {
                        Icon(Icons.Filled.Stop, null)
                        Spacer(Modifier.width(4.dp))
                        Text("完成")
                    }
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = { vm.cancelIfAny(); onBack() }) {
                        Text("取消")
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }

            // 系统识别的实时文字
            if (mode == TranscribeMode.SYSTEM) {
                Spacer(Modifier.height(24.dp))
                val showText = finalText + if (partialText.isNotEmpty()) "\n" + partialText else ""
                if (showText.isNotBlank()) {
                    Text(
                        showText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
