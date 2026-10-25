package com.tingsiwei.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingsiwei.app.App
import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.data.TtsCloudDefaults
import com.tingsiwei.app.data.TtsCloudProvider
import com.tingsiwei.app.data.TtsElevenDefaults
import com.tingsiwei.app.data.TtsEngine
import com.tingsiwei.app.data.TranscribeMode
import com.tingsiwei.app.llm.LlmClient
import com.tingsiwei.app.llm.LlmPolicy
import com.tingsiwei.app.transcribe.ModelManager
import com.tingsiwei.app.transcribe.SherpaTranscriber
import com.tingsiwei.app.tts.CloudTts
import com.tingsiwei.app.tts.CloudTtsConfig
import com.tingsiwei.app.tts.ElevenTts
import com.tingsiwei.app.tts.OkHttpCloudTtsTransport
import com.tingsiwei.app.tts.TtsPlayer
import com.tingsiwei.app.ui.components.BusyLine
import com.tingsiwei.app.ui.components.ChipPicker
import com.tingsiwei.app.ui.components.ChoiceRow
import com.tingsiwei.app.ui.components.InlineNotice
import com.tingsiwei.app.ui.components.InfoRow
import com.tingsiwei.app.ui.components.LabeledProgress
import com.tingsiwei.app.ui.components.SectionCard
import com.tingsiwei.app.ui.components.SettingsField
import com.tingsiwei.app.ui.components.SettingHint
import com.tingsiwei.app.ui.components.ThinDivider
import com.tingsiwei.app.ui.components.ToggleRow
import com.tingsiwei.app.ui.theme.Spacing
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
    val ttsCloudProvider by vm.ttsCloudProvider.collectAsState()
    val ttsElevenKey by vm.ttsElevenKey.collectAsState()
    val ttsElevenVoice by vm.ttsElevenVoice.collectAsState()
    val ttsElevenModel by vm.ttsElevenModel.collectAsState()
    val elevenVoices by vm.elevenVoices.collectAsState()
    val elevenBusy by vm.elevenBusy.collectAsState()
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Spacer(Modifier.height(Spacing.xs))
            StatusOverview(
                modelReady = modelState.state == ModelState.READY,
                ttsReady = ttsModelState.state == TtsModelState.READY,
                engine = ttsEngine,
                model = model,
            )

            // ---- AI 模型 ----
            SettingsSection(
                icon = Icons.Filled.SmartToy,
                title = "AI 模型",
                subtitle = "决定用哪个大模型来生成导图和思路",
            ) {
                SettingsField(
                    value = url,
                    onValueChange = { vm.url.value = it },
                    label = "接口地址",
                    placeholder = "https://api.xxx.com 或 https://api.xxx.com/v1",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                SettingsField(
                    value = key,
                    onValueChange = { vm.key.value = it },
                    label = "API Key",
                    placeholder = "sk-…",
                    obscure = true,
                )
                SettingsField(
                    value = model,
                    onValueChange = { vm.model.value = it },
                    label = "模型",
                    placeholder = "可手填，如 deepseek-chat",
                )
                if (models.isNotEmpty()) {
                    // 拉取到的模型直接平铺展示，点选即用；仍可手填
                    ChipPicker(
                        options = models.map { it to it },
                        selectedValue = model,
                        onSelect = {
                            vm.model.value = it
                            keyboard?.hide()
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { vm.fetchModels() }, enabled = !busy) {
                        Text("获取模型列表", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = { vm.test() }, enabled = !busy) {
                        Text("测试连接", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(onClick = { vm.save() }, enabled = !busy) {
                        Text("保存", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (busy) BusyLine("请求中…")
            }

            // ---- 语音转文字 ----
            SettingsSection(
                icon = Icons.Filled.GraphicEq,
                title = "语音转文字",
                subtitle = "录音和导入的音频用哪种方式识别",
            ) {
                ChoiceRow(
                    label = "离线模型",
                    description = "免费、不联网，推荐。录完自动转写",
                    selected = transcribeMode == TranscribeMode.OFFLINE,
                    onClick = { vm.setMode(TranscribeMode.OFFLINE) },
                )
                ChoiceRow(
                    label = "手机自带识别",
                    description = "边录边出字，各手机效果不一",
                    selected = transcribeMode == TranscribeMode.SYSTEM,
                    onClick = { vm.setMode(TranscribeMode.SYSTEM) },
                )
                ThinDivider()
                val modelReady = modelState.state == ModelState.READY
                InfoRow(
                    "SenseVoice 识别模型",
                    when (modelState.state) {
                        ModelState.READY -> "已下载 ${Formatters.fileSize(modelState.bytes)}"
                        ModelState.DOWNLOADING -> "下载中"
                        else -> "未下载"
                    },
                )
                if (modelState.state == ModelState.DOWNLOADING) {
                    LabeledProgress(
                        label = "正在下载识别模型",
                        progress = modelState.progress,
                        trailing = "${Formatters.fileSize(modelState.downloaded)} / ${Formatters.fileSize(modelState.total)}",
                    )
                } else if (!modelReady) {
                    SettingHint("约 230MB，只需下载一次。中途断开再点一次会接着下。")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (!modelReady) {
                        Button(
                            onClick = { vm.downloadModel() },
                            enabled = modelState.state != ModelState.DOWNLOADING,
                        ) {
                            Text(if (modelState.state == ModelState.DOWNLOADING) "下载中…" else "下载模型")
                        }
                    } else {
                        TextButton(onClick = { vm.deleteModel() }) {
                            Text("删除模型", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // ---- 生成偏好 ----
            SettingsSection(
                icon = Icons.Filled.AutoAwesome,
                title = "生成偏好",
                subtitle = "AI 整理内容时的取舍",
            ) {
                ToggleRow(
                    title = "允许 AI 拓展知识",
                    description = "开启后会把与主题直接相关的知识自然补进导图；关闭则只整理原文内容",
                    checked = allowExpand,
                    onCheckedChange = { vm.setAllowExpand(it) },
                )
                ThinDivider()
                ToggleRow(
                    title = "思路精修（更慢）",
                    description = "先起草思路、再自查优化、最后按思路出图，图文更一致，但多等约 2 次请求",
                    checked = stagedThinking,
                    onCheckedChange = { vm.setStagedThinking(it) },
                )
            }

            // ---- 长录音与限流 ----
            SettingsSection(
                icon = Icons.Filled.Speed,
                title = "长录音与限流",
                subtitle = "长内容怎么切、出错怎么退避",
            ) {
                SettingHint(
                    "接口报 429（限流）时会自动等待重试，已提炼的段落会保留，可从断点继续。" +
                        "长录音总是失败，就把节奏放慢、窗口调小。"
                )
                ThinDivider()
                SettingHint("请求间隔", color = MaterialTheme.colorScheme.onSurface)
                ChipPicker(
                    options = listOf("600" to "快", "1200" to "中", "2500" to "慢"),
                    selectedValue = minIntervalMs.toString(),
                    onSelect = { vm.setMinInterval(it.toLong()) },
                )
                SettingHint("上下文窗口", color = MaterialTheme.colorScheme.onSurface)
                ChipPicker(
                    options = listOf(8192, 16384, 32768, 65536, 131072, 262144)
                        .map { it.toString() to "${it / 1024}k" },
                    selectedValue = contextWindow.toString(),
                    onSelect = { vm.setContextWindow(it.toInt()) },
                )
                SettingHint(
                    "决定长内容怎么切分与单次请求的预算。大模型（尤其推理模型，思维链也占输出）建议 128k 起步；" +
                        "限流严重的渠道再调小。"
                )
                ThinDivider()
                ToggleRow(
                    title = "保守模式",
                    description = "分段更少、间隔翻倍、窗口减半，给限流严重的渠道",
                    checked = conservative,
                    onCheckedChange = { vm.setConservative(it) },
                )
            }

            // ---- 朗读 ----
            SettingsSection(
                icon = Icons.Filled.Headphones,
                title = "朗读",
                subtitle = "把「思路」读给你听，走路吃饭时也能记",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语速", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(Spacing.md))
                    Slider(
                        value = ttsRate,
                        onValueChange = { vm.setTtsRate(it) },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%.1fx".format(ttsRate),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ThinDivider()
                ChoiceRow(
                    label = "系统语音",
                    description = "手机自带，无需下载",
                    selected = ttsEngine == TtsEngine.SYSTEM,
                    onClick = { vm.setTtsEngine(TtsEngine.SYSTEM) },
                )
                ChoiceRow(
                    label = "离线朗读模型",
                    description = "离线可用，音色更自然，约 215MB",
                    selected = ttsEngine == TtsEngine.LOCAL,
                    onClick = { vm.setTtsEngine(TtsEngine.LOCAL) },
                )
                ChoiceRow(
                    label = "在线朗读",
                    description = "联网，声音最自然",
                    selected = ttsEngine == TtsEngine.CLOUD,
                    onClick = { vm.setTtsEngine(TtsEngine.CLOUD) },
                )

                if (ttsEngine == TtsEngine.LOCAL) {
                    val ready = ttsModelState.state == TtsModelState.READY
                    if (ttsModelState.state == TtsModelState.DOWNLOADING) {
                        LabeledProgress(
                            label = "正在下载 kokoro 模型",
                            progress = ttsModelState.progress,
                            trailing = "${Formatters.fileSize(ttsModelState.downloaded)} / ${Formatters.fileSize(ttsModelState.total)}",
                        )
                    } else {
                        InfoRow("kokoro 中英模型", if (ready) "已下载 ${Formatters.fileSize(ttsModelState.bytes)}" else "未下载")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (!ready) {
                            Button(
                                onClick = { vm.downloadTtsModel() },
                                enabled = ttsModelState.state != TtsModelState.DOWNLOADING,
                            ) {
                                Text(
                                    if (ttsModelState.state == TtsModelState.DOWNLOADING) "下载中…"
                                    else if (ttsModelState.downloaded > 0) "继续下载" else "下载模型"
                                )
                            }
                        } else {
                            TextButton(onClick = { vm.deleteTtsModel() }) {
                                Text("删除模型", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (ttsEngine == TtsEngine.CLOUD) {
                    ThinDivider()
                    // 两档：官方 ElevenLabs 只填 Key；自定义填任意 OpenAI 兼容渠道。两档配置互不覆盖
                    ChoiceRow(
                        label = "官方 ElevenLabs",
                        description = "内置地址，填 Key 即用；免费档每月 1 万字符",
                        selected = ttsCloudProvider == TtsCloudProvider.ELEVEN,
                        onClick = { vm.setTtsCloudProvider(TtsCloudProvider.ELEVEN) },
                    )
                    ChoiceRow(
                        label = "自定义渠道",
                        description = "任何 OpenAI 兼容的语音服务",
                        selected = ttsCloudProvider == TtsCloudProvider.CUSTOM,
                        onClick = { vm.setTtsCloudProvider(TtsCloudProvider.CUSTOM) },
                    )
                    if (ttsCloudProvider == TtsCloudProvider.ELEVEN) {
                        SettingsField(
                            value = ttsElevenKey,
                            onValueChange = { vm.setTtsElevenKey(it) },
                            label = "API Key（elevenlabs.io 免费注册领取）",
                            placeholder = "sk_…",
                            obscure = true,
                        )
                        SettingsField(
                            value = ttsElevenModel,
                            onValueChange = { vm.setTtsElevenModel(it) },
                            label = "朗读模型",
                        )
                        ChipPicker(
                            options = ElevenTts.MODELS.map { it.first to it.second },
                            selectedValue = ttsElevenModel,
                            onSelect = {
                                vm.setTtsElevenModel(it)
                                keyboard?.hide()
                            },
                        )
                        SettingsField(
                            value = ttsElevenVoice,
                            onValueChange = { vm.setTtsElevenVoice(it) },
                            label = "音色",
                            placeholder = "点「获取音色」选择，或手填音色 ID",
                        )
                        if (elevenVoices.isNotEmpty()) {
                            ChipPicker(
                                options = elevenVoices.map { it.id to it.label },
                                selectedValue = ttsElevenVoice,
                                onSelect = {
                                    vm.setTtsElevenVoice(it)
                                    keyboard?.hide()
                                },
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = { vm.fetchElevenVoices() }, enabled = !elevenBusy) {
                                Text("获取音色")
                            }
                            if (elevenBusy) BusyLine("获取中…")
                        }
                        SettingHint("官方音色都能读中文；换模型或音色后点「试听」验证。")
                    } else {
                        SettingsField(
                            value = ttsCloudUrl,
                            onValueChange = { vm.setTtsCloudUrl(it) },
                            label = "语音服务地址",
                            placeholder = "https://api.siliconflow.cn/v1",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        SettingsField(
                            value = ttsCloudKey,
                            onValueChange = { vm.setTtsCloudKey(it) },
                            label = "API Key（渠道注册领取）",
                            placeholder = "sk-…",
                            obscure = true,
                        )
                        SettingsField(
                            value = ttsCloudModel,
                            onValueChange = { vm.setTtsCloudModel(it) },
                            label = "朗读模型",
                            placeholder = "点「检索模型」选，或手填",
                        )
                        if (ttsModels.isNotEmpty()) {
                            ChipPicker(
                                options = ttsModels.map { it to it },
                                selectedValue = ttsCloudModel,
                                onSelect = {
                                    vm.setTtsCloudModel(it)
                                    keyboard?.hide()
                                },
                            )
                            if (ttsModelsAll) {
                                SettingHint("这个渠道没标出明确的语音模型，上面是全部模型——点选后试听验证。")
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = { vm.fetchTtsModels() }, enabled = !ttsBusy) {
                                Text("检索模型")
                            }
                            if (ttsBusy) BusyLine("检索中…")
                        }
                        SettingsField(
                            value = ttsCloudVoice,
                            onValueChange = { vm.setTtsCloudVoice(it) },
                            label = "音色（可手填）",
                        )
                        val voicePresets = CloudTts.voicePresetsFor(ttsCloudModel)
                        if (voicePresets.isNotEmpty()) {
                            ChipPicker(
                                options = voicePresets.map { (name, label) ->
                                    name to label
                                },
                                selectedValue = ttsCloudVoice,
                                onSelect = { vm.setTtsCloudVoice(it) },
                            )
                        }
                        SettingHint(
                            "任何 OpenAI 兼容渠道都能用：填好地址和 Key 点「检索模型」，筛出语音类模型点选即用。" +
                                "朗读正文出错会自动回落到离线/系统语音并提示原因。"
                        )
                    }
                } else if (ttsCloudKey.isNotBlank() || ttsElevenKey.isNotBlank()) {
                    // 最常见的「填了没反应」：配置都在，引擎却没切过来
                    InlineNotice(
                        text = "已填写在线朗读的 Key，但当前引擎不是「在线朗读」——要用它的声音，点选上面的「在线朗读」。",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }

                ThinDivider()
                Button(
                    onClick = { vm.togglePreview() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (previewSpeaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (previewSpeaking) "停止试听" else "试听当前引擎")
                }
                SettingHint("朗读入口在笔记详情页「思路」标签底部。")
            }

            // ---- 应用更新 ----
            SettingsSection(
                icon = Icons.Filled.SystemUpdate,
                title = "应用更新",
                subtitle = "手机上直接升级，不用连电脑传 APK",
            ) {
                InfoRow("当前版本", "v${updateVm.currentVersion}")
                ToggleRow(
                    title = "自动检查更新",
                    description = "每天最多联网检查一次，发现新版本弹窗提醒",
                    checked = updateVm.autoCheck,
                    onCheckedChange = { updateVm.changeAutoCheck(it) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        onClick = { updateVm.checkNow() },
                        enabled = !updateVm.checking && !updateVm.downloading,
                    ) { Text(if (updateVm.checking) "检查中…" else "检查更新") }
                    TextButton(onClick = { updateVm.openGuide() }) { Text("如何获取令牌？") }
                }
                SettingsField(
                    value = updateVm.tokenInput,
                    onValueChange = { updateVm.tokenInput = it },
                    label = "GitHub 访问令牌（可选）",
                    placeholder = if (updateVm.hasToken) "已保存（输入以更换）" else "github_pat_…",
                    obscure = true,
                    trailing = {
                        if (updateVm.hasToken) {
                            TextButton(onClick = { updateVm.clearToken() }) { Text("清除") }
                        }
                    },
                )
                OutlinedButton(
                    onClick = { updateVm.saveToken() },
                    enabled = updateVm.tokenInput.isNotBlank(),
                ) { Text("保存令牌") }
                updateVm.status?.let {
                    SettingHint(
                        it,
                        color = if (updateVm.statusError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ---- 关于 ----
            SettingsSection(
                icon = Icons.Filled.Info,
                title = "关于",
                subtitle = "听的思维 · 录音变成思维导图",
            ) {
                InfoRow("版本", "v${updateVm.currentVersion}")
                SettingHint(
                    "录下上课、聊天或面试，自动转写成文字，再整理成可拖拽的思维导图 + 一段帮你记忆的思路。\n" +
                        "全部笔记与录音只存在本机；AI 接口的 Key 也只存在本机。"
                )
            }
        }
    }
}

/**
 * 顶部状态概览：三行小字，一眼看出「AI 配了没、模型装了没、声音用的哪个」。
 *
 * 未就绪的状态用灰色空心圈、就绪用主色实心点——不用红色或橙色，因为「还没下载」
 * 不是错误，只是还没做，用报警色会让人以为出了问题。
 */
@Composable
private fun StatusOverview(modelReady: Boolean, ttsReady: Boolean, engine: String, model: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "当前配置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusLine(
                "AI 模型",
                if (model.isBlank()) "未选择，填下面三项即可" else model,
                ok = model.isNotBlank(),
            )
            StatusLine("识别模型", if (modelReady) "已就绪" else "未下载", ok = modelReady)
            StatusLine(
                "朗读",
                when (engine) {
                    TtsEngine.LOCAL -> if (ttsReady) "离线模型" else "离线模型（未下载）"
                    TtsEngine.CLOUD -> "在线"
                    else -> "系统语音"
                },
                ok = engine != TtsEngine.LOCAL || ttsReady,
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (ok) {
            Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        } else {
            Box(
                Modifier
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 设置分组：图标 + 标题 + 一句说明 + 内容。
 * 不用 Card 的阴影，改用浅色底容器——设置页一屏有六组，堆阴影会很脏。
 */
@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionCard(content = content)
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
    val ttsCloudProvider = MutableStateFlow(TtsCloudProvider.ELEVEN)
    val ttsElevenKey = MutableStateFlow("")
    val ttsElevenVoice = MutableStateFlow(TtsElevenDefaults.VOICE)
    val ttsElevenModel = MutableStateFlow(TtsElevenDefaults.MODEL)
    /** 官方档「获取音色」拉到的列表；空 = 没拉过或本次没解析到 */
    val elevenVoices = MutableStateFlow<List<ElevenTts.ElevenVoice>>(emptyList())
    val elevenBusy = MutableStateFlow(false)
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
            ttsCloudProvider.value = s.ttsCloudProvider
            ttsElevenKey.value = s.ttsElevenKey
            ttsElevenVoice.value = s.ttsElevenVoice
            ttsElevenModel.value = s.ttsElevenModel
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

    // 官方 ElevenLabs 档：服务档位与三项配置改动即存（与自定义档同惯例），两档互不覆盖
    fun setTtsCloudProvider(p: String) {
        ttsCloudProvider.value = p
        viewModelScope.launch { repo.setTtsCloudProvider(p) }
    }

    fun setTtsElevenKey(k: String) {
        ttsElevenKey.value = k
        persistTtsEleven()
    }

    fun setTtsElevenVoice(v: String) {
        ttsElevenVoice.value = v
        persistTtsEleven()
    }

    fun setTtsElevenModel(m: String) {
        ttsElevenModel.value = m
        persistTtsEleven()
    }

    private fun persistTtsEleven() {
        viewModelScope.launch {
            repo.setTtsEleven(ttsElevenKey.value, ttsElevenVoice.value, ttsElevenModel.value)
        }
    }

    /** 拉官方音色列表：先存当前填写值，再 GET /voices 列成点选 chips（网络必须 IO 线程，同「检索模型」的坑） */
    fun fetchElevenVoices() {
        if (elevenBusy.value) return
        viewModelScope.launch {
            elevenBusy.value = true
            try {
                repo.setTtsEleven(ttsElevenKey.value, ttsElevenVoice.value, ttsElevenModel.value)
                val list = withContext(Dispatchers.IO) {
                    ElevenTts(
                        OkHttpCloudTtsTransport,
                        CloudTtsConfig(TtsElevenDefaults.BASE_URL, ttsElevenKey.value, "", ttsElevenModel.value, TtsCloudProvider.ELEVEN),
                    ).fetchVoices()
                }
                elevenVoices.value = list
                message.value = if (list.isEmpty()) "没解析到音色，可在音色框手填官方音色 ID" else "拉到 ${list.size} 个官方音色，点选即可"
            } catch (e: Exception) {
                message.value = e.message ?: "获取音色失败"
            } finally {
                elevenBusy.value = false
            }
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

    /** 在线引擎需要的配置从当前填写值组装（未选在线引擎时返回 null；按服务档位取对应一档的界面值） */
    private fun cloudConfig(): CloudTtsConfig? =
        if (ttsEngine.value == TtsEngine.CLOUD) {
            if (ttsCloudProvider.value == TtsCloudProvider.ELEVEN) {
                CloudTtsConfig(TtsElevenDefaults.BASE_URL, ttsElevenKey.value, ttsElevenVoice.value, ttsElevenModel.value, TtsCloudProvider.ELEVEN)
            } else {
                CloudTtsConfig(ttsCloudUrl.value, ttsCloudKey.value, ttsCloudVoice.value, ttsCloudModel.value)
            }
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
