package com.tingsiwei.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingsiwei.app.App
import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.data.TtsCloudDefaults
import com.tingsiwei.app.data.TtsEngine
import com.tingsiwei.app.data.TranscribeMode
import com.tingsiwei.app.llm.LlmClient
import com.tingsiwei.app.llm.LlmPolicy
import com.tingsiwei.app.transcribe.ModelManager
import com.tingsiwei.app.transcribe.SherpaTranscriber
import com.tingsiwei.app.tts.CloudTts
import com.tingsiwei.app.tts.CloudTtsConfig
import com.tingsiwei.app.tts.OkHttpCloudTtsTransport
import com.tingsiwei.app.tts.TtsPlayer
import com.tingsiwei.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, updateVm: UpdateViewModel) {
    val vm: SettingsViewModel = viewModel()
    val url by vm.url.collectAsState()
    val key by vm.key.collectAsState()
    val model by vm.model.collectAsState()
    val models by vm.models.collectAsState()
    val transcribeMode by vm.transcribeMode.collectAsState()
    val allowExpand by vm.allowExpand.collectAsState()
    val stagedThinking by vm.stagedThinking.collectAsState()
    val ttsRate by vm.ttsRate.collectAsState()
    val ttsEngine by vm.ttsEngine.collectAsState()
    val ttsCloudUrl by vm.ttsCloudUrl.collectAsState()
    val ttsCloudKey by vm.ttsCloudKey.collectAsState()
    val ttsCloudVoice by vm.ttsCloudVoice.collectAsState()
    val ttsCloudModel by vm.ttsCloudModel.collectAsState()
    val ttsModels by vm.ttsModels.collectAsState()
    val ttsModelsAll by vm.ttsModelsAll.collectAsState()
    val ttsBusy by vm.ttsBusy.collectAsState()
    val ttsModelState by vm.ttsModelState.collectAsState()
    val previewSpeaking by vm.previewSpeaking.collectAsState()
    val contextWindow by vm.contextWindow.collectAsState()
    val minIntervalMs by vm.minIntervalMs.collectAsState()
    val conservative by vm.conservative.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()

    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }
    LaunchedEffect(Unit) {
        vm.refreshModelState()
        vm.refreshTtsModelState()
    }

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
                    OutlinedTextField(
                        value = model,
                        onValueChange = { vm.model.value = it },
                        label = { Text("模型（可手填）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    if (models.isNotEmpty()) {
                        // 拉取到的模型直接平铺展示，点选即用；仍可手填
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            models.forEach { m ->
                                FilterChip(
                                    selected = model == m,
                                    onClick = {
                                        vm.model.value = m
                                        keyboard?.hide()
                                    },
                                    label = { Text(m, style = MaterialTheme.typography.bodySmall) },
                                )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = stagedThinking, onCheckedChange = { vm.setStagedThinking(it) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("思路精修（更慢）", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "默认关：AI 一次读完成稿，速度最快。开启后先起草思路、再自查优化、最后按思路出图，" +
                                    "图文更一致、拓展标注更准，但要多等约 2 次请求的时间",
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
                            "输出上限会在被截断时自动抬高重试，一般不用管。长录音总是失败就把节奏放慢、窗口调小。",
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
                    Column {
                        Text("上下文窗口", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(8192, 16384, 32768, 65536, 131072, 262144).forEach { n ->
                                val label = "${n / 1024}k"
                                if (contextWindow == n) Button(onClick = {}) { Text(label) }
                                else TextButton(onClick = { vm.setContextWindow(n) }) { Text(label) }
                            }
                        }
                        Text(
                            "决定长内容怎么切分与单次请求的预算。大模型（尤其推理模型，思维链也占输出）建议 128k 起步；限流严重的渠道再调小",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    Text("朗读", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "朗读入口在笔记详情页「思路」标签底部：点开笔记 → 切到「思路」 → 点「朗读思路」。朗读中拖动下面的语速条立刻生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("语速", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(12.dp))
                        Slider(
                            value = ttsRate,
                            onValueChange = { vm.setTtsRate(it) },
                            valueRange = 0.5f..2.0f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("%.1fx".format(ttsRate), style = MaterialTheme.typography.labelMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = ttsEngine == TtsEngine.SYSTEM,
                            onClick = { vm.setTtsEngine(TtsEngine.SYSTEM) },
                        )
                        Text("系统语音（手机自带，无需下载）", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = ttsEngine == TtsEngine.LOCAL,
                            onClick = { vm.setTtsEngine(TtsEngine.LOCAL) },
                        )
                        Text("离线朗读模型（离线可用，约 215MB）", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = ttsEngine == TtsEngine.CLOUD,
                            onClick = { vm.setTtsEngine(TtsEngine.CLOUD) },
                        )
                        Text("在线朗读（联网，声音最自然）", style = MaterialTheme.typography.bodySmall)
                    }
                    if (ttsEngine == TtsEngine.LOCAL) {
                        val (stateText, ready) = when (ttsModelState.state) {
                            TtsModelState.READY -> "已下载（${Formatters.fileSize(ttsModelState.bytes)}）" to true
                            TtsModelState.DOWNLOADING -> "下载中 ${(ttsModelState.progress * 100).toInt()}%（${Formatters.fileSize(ttsModelState.downloaded)} / ${Formatters.fileSize(ttsModelState.total)}）" to false
                            else -> if (ttsModelState.downloaded > 0) {
                                "已下载 ${Formatters.fileSize(ttsModelState.downloaded)}，点下载继续" to false
                            } else {
                                "未下载（约 215MB，一次性下载）" to false
                            }
                        }
                        Text("kokoro 中英模型（v1.1 int8）：$stateText", style = MaterialTheme.typography.bodySmall)
                        if (ttsModelState.state == TtsModelState.DOWNLOADING) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { ttsModelState.progress.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!ready) {
                                Button(
                                    onClick = { vm.downloadTtsModel() },
                                    enabled = ttsModelState.state != TtsModelState.DOWNLOADING,
                                ) {
                                    Text(if (ttsModelState.state == TtsModelState.DOWNLOADING) "下载中…" else "下载模型")
                                }
                            } else {
                                TextButton(onClick = { vm.deleteTtsModel() }) { Text("删除模型") }
                            }
                        }
                    }
                    if (ttsEngine == TtsEngine.CLOUD) {
                        OutlinedTextField(
                            value = ttsCloudUrl,
                            onValueChange = { vm.setTtsCloudUrl(it) },
                            label = { Text("语音服务地址（默认硅基流动，其他 OpenAI 兼容渠道也可以）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = ttsCloudKey,
                            onValueChange = { vm.setTtsCloudKey(it) },
                            label = { Text("API Key（渠道注册领取）") },
                            placeholder = { Text("sk-…") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = ttsCloudModel,
                            onValueChange = { vm.setTtsCloudModel(it) },
                            label = { Text("朗读模型（可手填，或点「检索模型」选）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        if (ttsModels.isNotEmpty()) {
                            // 检索到的模型直接平铺展示，点选即用；仍可手填
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ttsModels.forEach { m ->
                                    FilterChip(
                                        selected = ttsCloudModel == m,
                                        onClick = {
                                            vm.setTtsCloudModel(m)
                                            keyboard?.hide()
                                        },
                                        label = { Text(m, style = MaterialTheme.typography.bodySmall) },
                                    )
                                }
                            }
                            if (ttsModelsAll) {
                                Text(
                                    "没筛出明确的语音模型，以上是全部模型——点选后点下面「试听当前引擎」验证能不能出声",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { vm.fetchTtsModels() }, enabled = !ttsBusy) {
                                Text("检索模型")
                            }
                            if (ttsBusy) {
                                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                                Text("检索中…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedTextField(
                            value = ttsCloudVoice,
                            onValueChange = { vm.setTtsCloudVoice(it) },
                            label = { Text("音色（可手填）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        val voicePresets = CloudTts.voicePresetsFor(ttsCloudModel)
                        if (voicePresets.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                voicePresets.forEach { (name, label) ->
                                    FilterChip(
                                        selected = ttsCloudVoice == name || ttsCloudVoice == CloudTts.voiceId(name),
                                        onClick = { vm.setTtsCloudVoice(name) },
                                        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                    )
                                }
                            }
                        }
                        Text(
                            "任何 OpenAI 兼容渠道都能用：填好地址和 Key 点「检索模型」，筛出语音类模型点选即用。" +
                                "换模型后音色可能要换（CosyVoice 点上面音色片，OpenAI 式模型手填 alloy 等），" +
                                "选完点「试听当前引擎」验证。联网时边生成边播；朗读正文出错会自动先用离线/系统语音并提示原因",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (ttsCloudKey.isNotBlank()) {
                        // 最常见的「填了没反应」：配置都在，引擎却没切过来
                        Text(
                            "已填写在线朗读的 Key，但当前引擎不是「在线朗读」——要用它的声音，点选上面的「在线朗读」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    OutlinedButton(onClick = { vm.togglePreview() }) {
                        Text(if (previewSpeaking) "停止试听" else "试听当前引擎")
                    }
                }
            }

            // ---- 应用更新（自更新：手机上直接升级，不用连电脑传 APK） ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("应用更新", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "当前版本 v${updateVm.currentVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("自动检查更新", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "每天最多联网检查一次，发现新版本弹窗提醒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = updateVm.autoCheck, onCheckedChange = { updateVm.changeAutoCheck(it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { updateVm.checkNow() },
                            enabled = !updateVm.checking && !updateVm.downloading,
                        ) { Text(if (updateVm.checking) "检查中…" else "检查更新") }
                        TextButton(onClick = { updateVm.openGuide() }) { Text("如何获取令牌？") }
                    }
                    OutlinedTextField(
                        value = updateVm.tokenInput,
                        onValueChange = { updateVm.tokenInput = it },
                        label = { Text("GitHub 访问令牌（可选——仓库已公开，通常不用填）") },
                        placeholder = { Text(if (updateVm.hasToken) "已保存（输入以更换）" else "github_pat_…") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (updateVm.hasToken) TextButton(onClick = { updateVm.clearToken() }) { Text("清除") }
                        },
                    )
                    OutlinedButton(
                        onClick = { updateVm.saveToken() },
                        enabled = updateVm.tokenInput.isNotBlank(),
                    ) { Text("保存令牌") }
                    updateVm.status?.let {
                        Text(
                            it,
                            color = if (updateVm.statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
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

enum class TtsModelState { UNKNOWN, READY, DOWNLOADING, NOT_DOWNLOADED }

data class TtsModelStateUi(
    val state: TtsModelState = TtsModelState.UNKNOWN,
    val bytes: Long = 0,
    val downloaded: Long = 0,
    val total: Long = ModelManager.TTS_TOTAL_BYTES,
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
    val stagedThinking = MutableStateFlow(false)
    val ttsRate = MutableStateFlow(1.0f)
    val ttsEngine = MutableStateFlow(TtsEngine.SYSTEM)
    val ttsCloudUrl = MutableStateFlow(TtsCloudDefaults.BASE_URL)
    val ttsCloudKey = MutableStateFlow("")
    val ttsCloudVoice = MutableStateFlow(TtsCloudDefaults.VOICE)
    val ttsCloudModel = MutableStateFlow(TtsCloudDefaults.MODEL)
    val ttsModels = MutableStateFlow<List<String>>(emptyList())
    /** 检索结果里没筛出明确的语音模型时展示了全部模型，提示用户点选后试听验证 */
    val ttsModelsAll = MutableStateFlow(false)
    val ttsBusy = MutableStateFlow(false)
    val ttsModelState = MutableStateFlow(TtsModelStateUi())
    val previewSpeaking = MutableStateFlow(false)
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
            stagedThinking.value = s.llmStagedThinking
            ttsRate.value = s.ttsRate
            ttsEngine.value = s.ttsEngine
            ttsCloudUrl.value = s.ttsCloudUrl
            ttsCloudKey.value = s.ttsCloudKey
            ttsCloudVoice.value = s.ttsCloudVoice
            ttsCloudModel.value = s.ttsCloudModel
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
                message.value = if (list.isEmpty()) "没有获取到模型，可手动填写模型名" else "获取到 ${list.size} 个模型，已在下方列出，点选即可"
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

    fun setStagedThinking(flag: Boolean) {
        stagedThinking.value = flag
        viewModelScope.launch { repo.setLlmStagedThinking(flag) }
    }

    fun setTtsRate(rate: Float) {
        ttsRate.value = rate
        if (previewSpeaking.value) previewTts.updateRate(rate)
        viewModelScope.launch { repo.setTtsRate(rate) }
    }

    fun setTtsEngine(e: String) {
        ttsEngine.value = e
        viewModelScope.launch { repo.setTtsEngine(e) }
    }

    // 在线朗读三项改动即存，免去单独的保存按钮（避免「填了 Key 忘了保存」）
    fun setTtsCloudUrl(u: String) {
        ttsCloudUrl.value = u
        persistTtsCloud()
    }

    fun setTtsCloudKey(k: String) {
        ttsCloudKey.value = k
        persistTtsCloud()
    }

    fun setTtsCloudVoice(v: String) {
        ttsCloudVoice.value = v
        persistTtsCloud()
    }

    fun setTtsCloudModel(m: String) {
        ttsCloudModel.value = m
        persistTtsCloud()
    }

    private fun persistTtsCloud() {
        viewModelScope.launch {
            repo.setTtsCloud(ttsCloudUrl.value, ttsCloudKey.value, ttsCloudVoice.value, ttsCloudModel.value)
        }
    }

    /** 检索在线朗读渠道的可用模型：先存界面当前值，再 GET /models，筛出语音类模型列成点选 chips */
    fun fetchTtsModels() {
        if (ttsBusy.value) return
        viewModelScope.launch {
            ttsBusy.value = true
            try {
                // 先保存当前填写内容，检索用的就是界面上的地址和 Key
                repo.setTtsCloud(ttsCloudUrl.value, ttsCloudKey.value, ttsCloudVoice.value, ttsCloudModel.value)
                // 网络必须切 IO 线程（对齐 LlmClient.listModels 的做法，主线程发请求会直接抛异常）
                val all = withContext(Dispatchers.IO) {
                    CloudTts(
                        OkHttpCloudTtsTransport,
                        CloudTtsConfig(ttsCloudUrl.value, ttsCloudKey.value, "", ttsCloudModel.value),
                    ).fetchModels()
                }
                val filtered = CloudTts.filterTtsModels(all)
                ttsModels.value = filtered.ifEmpty { all }
                ttsModelsAll.value = filtered.isEmpty() && all.isNotEmpty()
                message.value = when {
                    all.isEmpty() -> "没有获取到模型，可手动填写模型名"
                    filtered.isEmpty() -> "这个渠道没标出明确的语音模型，全部 ${all.size} 个已列出——点选或手填，选完试听验证"
                    else -> "筛出 ${filtered.size} 个语音类模型（渠道共 ${all.size} 个），点选即可"
                }
            } catch (e: Exception) {
                message.value = e.message ?: "检索模型失败"
            } finally {
                ttsBusy.value = false
            }
        }
    }

    fun refreshTtsModelState() {
        val ready = ModelManager.isTtsReady(context)
        ttsModelState.value = if (ready) {
            TtsModelStateUi(TtsModelState.READY, bytes = ModelManager.ttsDownloadedBytes(context))
        } else {
            val partial = ModelManager.ttsDownloadedBytes(context)
            if (partial > 0) {
                // 有残缺进度但没在下载：不算"下载中"，按钮可点续传
                TtsModelStateUi(TtsModelState.NOT_DOWNLOADED, downloaded = partial, progress = partial.toFloat() / ModelManager.TTS_TOTAL_BYTES)
            } else {
                TtsModelStateUi(TtsModelState.NOT_DOWNLOADED)
            }
        }
    }

    fun downloadTtsModel() {
        if (ttsModelState.value.state == TtsModelState.DOWNLOADING) return
        viewModelScope.launch {
            ttsModelState.value = TtsModelStateUi(
                TtsModelState.DOWNLOADING,
                downloaded = ModelManager.ttsDownloadedBytes(context),
                progress = ModelManager.ttsDownloadedBytes(context).toFloat() / ModelManager.TTS_TOTAL_BYTES,
            )
            try {
                ModelManager.downloadTtsModel(
                    context,
                    onFileStart = { i, count, rel ->
                        // espeak 语音数据是一大串小文件，只报阶段，免得进度提示刷屏
                        message.value = when {
                            rel.startsWith("espeak-ng-data/") ->
                                "下载语音数据（${i + 1}/$count）…"
                            else -> "下载 ${i + 1}/$count：$rel"
                        }
                    },
                    onProgress = { done, total ->
                        ttsModelState.value = ttsModelState.value.copy(
                            downloaded = done,
                            total = total,
                            progress = if (total > 0) done.toFloat() / total else 0f,
                        )
                    },
                )
                message.value = "离线朗读模型下载完成"
            } catch (e: Exception) {
                message.value = "下载失败：${e.message}（已下载部分保留，再点一次继续）"
            } finally {
                refreshTtsModelState()
            }
        }
    }

    fun deleteTtsModel() {
        ModelManager.deleteTtsModel(context)
        refreshTtsModelState()
        message.value = "已删除离线朗读模型"
    }

    private val previewTts by lazy { TtsPlayer(context) { msg -> message.value = msg } }

    fun togglePreview() {
        if (previewSpeaking.value) {
            previewTts.stop()
            previewSpeaking.value = false
            return
        }
        viewModelScope.launch {
            previewTts.speak(
                "你好，这是听的思维的朗读试听。一二三四五，上山打老虎。",
                ttsRate.value,
                ttsEngine.value,
                cloudConfig(),
                // 试听在线引擎时不要静默回落：失败直接报准确原因，试听就是要验证在线通不通
                fallback = ttsEngine.value != TtsEngine.CLOUD,
            )
            previewSpeaking.value = true
            while (previewTts.isSpeaking) kotlinx.coroutines.delay(300)
            previewSpeaking.value = false
        }
    }

    /** 在线引擎需要的配置从当前填写值组装（未选在线引擎时返回 null） */
    private fun cloudConfig(): CloudTtsConfig? =
        if (ttsEngine.value == TtsEngine.CLOUD) {
            CloudTtsConfig(ttsCloudUrl.value, ttsCloudKey.value, ttsCloudVoice.value, ttsCloudModel.value)
        } else null

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
