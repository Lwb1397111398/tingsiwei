package com.tingsiwei.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingsiwei.app.App
import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.data.TranscribeMode
import com.tingsiwei.app.llm.LlmClient
import com.tingsiwei.app.llm.LlmPolicy
import com.tingsiwei.app.transcribe.ModelManager
import com.tingsiwei.app.transcribe.SherpaTranscriber
import com.tingsiwei.app.util.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val url by vm.url.collectAsState()
    val key by vm.key.collectAsState()
    val model by vm.model.collectAsState()
    val models by vm.models.collectAsState()
    val transcribeMode by vm.transcribeMode.collectAsState()
    val allowExpand by vm.allowExpand.collectAsState()
    val ttsRate by vm.ttsRate.collectAsState()
    val contextWindow by vm.contextWindow.collectAsState()
    val minIntervalMs by vm.minIntervalMs.collectAsState()
    val conservative by vm.conservative.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()

    var modelsOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }
    LaunchedEffect(Unit) { vm.refreshModelState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- LLM 接口 ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LLM 接口（OpenAI 兼容）", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { vm.url.value = it },
                        label = { Text("接口地址，如 https://api.xxx.com 或 https://api.xxx.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { vm.key.value = it },
                        label = { Text("API KEY") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    ExposedDropdownMenuBox(expanded = modelsOpen, onExpandedChange = { modelsOpen = it }) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { vm.model.value = it },
                            label = { Text("模型（可手填）") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        if (models.isNotEmpty()) {
                            ExposedDropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                                models.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m, style = MaterialTheme.typography.bodySmall) },
                                        onClick = {
                                            vm.model.value = m
                                            modelsOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.fetchModels() }, enabled = !busy) { Text("获取模型列表") }
                        OutlinedButton(onClick = { vm.test() }, enabled = !busy) { Text("测试连接") }
                        Button(onClick = { vm.save() }, enabled = !busy) { Text("保存") }
                    }
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("请求中…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ---- 语音转文字 ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("语音转文字", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = transcribeMode == TranscribeMode.OFFLINE,
                            onClick = { vm.setMode(TranscribeMode.OFFLINE) },
                        )
                        Text("离线模型（推荐，免费、录音完转写）", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = transcribeMode == TranscribeMode.SYSTEM,
                            onClick = { vm.setMode(TranscribeMode.SYSTEM) },
                        )
                        Text("手机自带识别（边录边转，各手机效果不一）", style = MaterialTheme.typography.bodySmall)
                    }
                    // 模型下载卡片
                    val (stateText, ready) = when (modelState.state) {
                        ModelState.READY -> "已下载（${Formatters.fileSize(modelState.bytes)}）" to true
                        ModelState.DOWNLOADING -> "下载中 ${(modelState.progress * 100).toInt()}%（${Formatters.fileSize(modelState.downloaded)} / ${Formatters.fileSize(modelState.total)}）" to false
                        else -> "未下载（约 230MB，一次性下载）" to false
                    }
                    Text("SenseVoice 离线识别模型：$stateText", style = MaterialTheme.typography.bodySmall)
                    if (modelState.state == ModelState.DOWNLOADING) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { modelState.progress.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!ready) {
                            Button(onClick = { vm.downloadModel() }, enabled = modelState.state != ModelState.DOWNLOADING) {
                                Text(if (modelState.state == ModelState.DOWNLOADING) "下载中…" else "下载模型")
                            }
                        } else {
                            TextButton(onClick = { vm.deleteModel() }) { Text("删除模型") }
                        }
                    }
                }
            }

            // ---- 生成偏好 ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("生成偏好", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = allowExpand, onCheckedChange = { vm.setAllowExpand(it) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("允许 AI 拓展知识", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "开启后 AI 会把与主题直接相关的知识自然补充进导图；关闭则只整理原文内容",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---- 长录音与限流 ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("长录音与限流", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "接口返回 429（限流）时会自动等待重试，已提炼的段落会保留，可从断点继续。" +
                            "长录音总是失败就把节奏放慢、窗口调小。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("请求间隔", style = MaterialTheme.typography.bodyMedium)
                        listOf(600L to "快", 1200L to "中", 2500L to "慢").forEach { (ms, label) ->
                            if (minIntervalMs == ms) Button(onClick = {}) { Text(label) }
                            else TextButton(onClick = { vm.setMinInterval(ms) }) { Text(label) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("上下文窗口", style = MaterialTheme.typography.bodyMedium)
                        listOf(4096, 8192, 16384, 32768).forEach { n ->
                            val label = "${n / 1024}k"
                            if (contextWindow == n) Button(onClick = {}) { Text(label) }
                            else TextButton(onClick = { vm.setContextWindow(n) }) { Text(label) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = conservative, onCheckedChange = { vm.setConservative(it) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("保守模式", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "分段更少、间隔翻倍、窗口减半，给限流严重的渠道",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---- 朗读 ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("朗读语速", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = ttsRate,
                            onValueChange = { vm.setTtsRate(it) },
                            valueRange = 0.5f..2.0f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("%.1fx".format(ttsRate), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

enum class ModelState { UNKNOWN, READY, DOWNLOADING, NOT_DOWNLOADED }

data class ModelStateUi(
    val state: ModelState = ModelState.UNKNOWN,
    val bytes: Long = 0,
    val downloaded: Long = 0,
    val total: Long = ModelManager.MODEL_BYTES,
    val progress: Float = 0f,
)

class SettingsViewModel : ViewModel() {

    private val repo: SettingsRepository = App.get().settings
    private val client = LlmClient(repo)
    private val context = App.get()

    val url = MutableStateFlow("")
    val key = MutableStateFlow("")
    val model = MutableStateFlow("")
    val models = MutableStateFlow<List<String>>(emptyList())
    val transcribeMode = MutableStateFlow(TranscribeMode.OFFLINE)
    val allowExpand = MutableStateFlow(true)
    val ttsRate = MutableStateFlow(1.0f)
    val contextWindow = MutableStateFlow(LlmPolicy.DEFAULT_CONTEXT_WINDOW)
    val minIntervalMs = MutableStateFlow(LlmPolicy.DEFAULT_MIN_INTERVAL_MS)
    val conservative = MutableStateFlow(false)
    val modelState = MutableStateFlow(ModelStateUi())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val s = repo.current()
            url.value = s.llmUrl
            key.value = s.llmKey
            model.value = s.llmModel
            transcribeMode.value = s.transcribeMode
            allowExpand.value = s.allowExpand
            ttsRate.value = s.ttsRate
            contextWindow.value = s.llmContextWindow
            minIntervalMs.value = s.llmMinIntervalMs
            conservative.value = s.llmConservative
        }
    }

    fun refreshModelState() {
        val ready = SherpaTranscriber.isModelReady(context)
        modelState.value = if (ready) {
            ModelStateUi(ModelState.READY, bytes = ModelManager.totalBytes(context))
        } else {
            val partial = ModelManager.downloadedBytes(context, "model.int8.onnx")
            if (partial > 0) {
                ModelStateUi(ModelState.DOWNLOADING, downloaded = partial, total = ModelManager.MODEL_BYTES, progress = partial.toFloat() / ModelManager.MODEL_BYTES)
            } else {
                ModelStateUi(ModelState.NOT_DOWNLOADED)
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            busy.value = true
            try {
                repo.setLlm(url.value, key.value, model.value)
                message.value = "已保存"
            } finally {
                busy.value = false
            }
        }
    }

    fun fetchModels() {
        viewModelScope.launch {
            busy.value = true
            try {
                // 先保存当前填写内容再拉取
                repo.setLlm(url.value, key.value, model.value)
                val list = client.listModels(url.value, key.value)
                models.value = list
                message.value = if (list.isEmpty()) "没有获取到模型，可手动填写模型名" else "获取到 ${list.size} 个模型"
            } catch (e: Exception) {
                message.value = e.message ?: "获取失败"
            } finally {
                busy.value = false
            }
        }
    }

    fun test() {
        viewModelScope.launch {
            busy.value = true
            try {
                val reply = client.chat(url.value, key.value, model.value, "", "请只回复两个字：成功")
                message.value = "连接正常，模型回复：${reply.take(40)}"
            } catch (e: Exception) {
                message.value = "测试失败：${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun setMode(mode: String) {
        transcribeMode.value = mode
        viewModelScope.launch { repo.setTranscribeMode(mode) }
    }

    fun setAllowExpand(flag: Boolean) {
        allowExpand.value = flag
        viewModelScope.launch { repo.setAllowExpand(flag) }
    }

    fun setTtsRate(rate: Float) {
        ttsRate.value = rate
        viewModelScope.launch { repo.setTtsRate(rate) }
    }

    fun setMinInterval(ms: Long) {
        minIntervalMs.value = ms
        persistLimits()
    }

    fun setContextWindow(n: Int) {
        contextWindow.value = n
        persistLimits()
    }

    fun setConservative(flag: Boolean) {
        conservative.value = flag
        persistLimits()
    }

    private fun persistLimits() {
        viewModelScope.launch {
            repo.setLlmLimits(contextWindow.value, minIntervalMs.value, conservative.value)
        }
    }

    fun downloadModel() {
        if (modelState.value.state == ModelState.DOWNLOADING) return
        modelState.value = ModelStateUi(ModelState.DOWNLOADING, downloaded = 0, total = ModelManager.MODEL_BYTES, progress = 0f)
        viewModelScope.launch {
            try {
                message.value = "正在下载 tokens…"
                ModelManager.downloadFile(context, "tokens.txt", ModelManager.TOKENS_URL, 100_000) { _, _ -> }
                message.value = "正在下载识别模型（约 230MB，断了再点会续传）…"
                ModelManager.downloadFile(
                    context, "model.int8.onnx", ModelManager.MODEL_URL, 200_000_000
                ) { done, total ->
                    modelState.value = modelState.value.copy(
                        downloaded = done,
                        total = total,
                        progress = if (total > 0) done.toFloat() / total else 0f,
                    )
                }
                message.value = "模型下载完成"
            } catch (e: Exception) {
                message.value = "下载失败：${e.message}（已下载部分保留，再点一次继续）"
            } finally {
                refreshModelState()
            }
        }
    }

    fun deleteModel() {
        ModelManager.deleteModel(context)
        refreshModelState()
        message.value = "已删除离线模型"
    }
}
