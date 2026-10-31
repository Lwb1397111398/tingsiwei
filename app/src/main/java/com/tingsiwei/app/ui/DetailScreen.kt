package com.tingsiwei.app.ui

import android.media.MediaPlayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingsiwei.app.App
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteSource
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.data.db.VersionEntity
import com.tingsiwei.app.export.Exporter
import com.tingsiwei.app.llm.Generator
import com.tingsiwei.app.mindmap.TreeText
import com.tingsiwei.app.pipeline.NoteProcessor
import com.tingsiwei.app.tts.CloudTtsConfig
import com.tingsiwei.app.tts.TtsPlayer
import com.tingsiwei.app.ui.components.EmptyState
import com.tingsiwei.app.ui.components.MapController
import com.tingsiwei.app.ui.components.MindMapPanel
import com.tingsiwei.app.ui.theme.Spacing
import com.tingsiwei.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(noteId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(key = "note$noteId", factory = detailVmFactory(noteId))
    val note by vm.note.collectAsState(initial = null)
    val versions by vm.versions.collectAsState(initial = emptyList())
    val busyStage by vm.busyStage.collectAsState(initial = null)

    var tab by remember { mutableIntStateOf(0) }
    var showVersions by remember { mutableStateOf(false) }
    val suggestion by vm.suggestion.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val ttsMsg by vm.uiMessage.collectAsState()

    LaunchedEffect(note?.id, note?.status) { vm.maybeRunPipeline() }
    LaunchedEffect(ttsMsg) {
        ttsMsg?.let {
            snackbar.showSnackbar(it)
            vm.uiMessage.value = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            note?.title ?: "笔记",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        note?.let {
                            Text(
                                subtitleFor(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showVersions = true }, enabled = note?.mapJson != null) {
                        Icon(Icons.Filled.History, "版本历史", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = { vm.shareMarkdown() },
                        enabled = note?.mapJson != null,
                    ) {
                        Icon(Icons.Filled.Share, "分享", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val n = note
        Column(modifier = Modifier.padding(pad).fillMaxSize()) {
            // 状态提示条：三种情形（进行中 / 出错 / 完成但有提示）统一成一块，视觉上不再各写一版
            when {
                n == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                n.status == NoteStatus.TRANSCRIBING || n.status == NoteStatus.GENERATING -> {
                    BusyBanner(text = busyStage ?: "处理中…")
                }
                n.status == NoteStatus.ERROR -> {
                    MessageBanner(
                        text = n.errorMsg?.let { "出错：$it" } ?: "出错：未知原因",
                        actionLabel = "重试",
                        onAction = { vm.retry() },
                        tone = BannerTone.Error,
                    )
                }
                n.status == NoteStatus.READY && !n.errorMsg.isNullOrBlank() -> {
                    // errorMsg 在 READY 态下是"提示"（如个别段未纳入），不是错误
                    MessageBanner(
                        text = n.errorMsg,
                        actionLabel = "补全",
                        onAction = { vm.retry() },
                        tone = BannerTone.Notice,
                    )
                }
            }

            PillTabs(
                labels = listOf("导图", "原文", "思路"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            )

            // 三个标签叠放：导图 WebView 常驻组合（切页不销毁重建，保留缩放/平移位置），
            // 其他标签页用不透明背景盖在上面
            Box(Modifier.weight(1f)) {
                MapTab(n, vm, Modifier.fillMaxSize())
                if (tab == 1) {
                    TranscriptTab(
                        n, vm,
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    )
                }
                if (tab == 2) {
                    ThinkingTab(
                        n, vm,
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    )
                }
            }

            // 底部：AI 建议输入条（悬浮在内容之上，圆角与卡片一致）
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screen, vertical = Spacing.md),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Box {
                            if (suggestion.isEmpty()) {
                                Text(
                                    "想让 AI 怎么改？比如：补充相关案例、思路再口语化一点",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 14.dp),
                                )
                            }
                            BasicTextField(
                                value = suggestion,
                                onValueChange = { vm.suggestion.value = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 14.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 3,
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    FilledIconButton(
                        onClick = {
                            val s = suggestion.trim()
                            // 不清空输入：改图成功后由 ViewModel 清掉；失败了建议还留在框里，可直接重发
                            if (s.isNotEmpty()) vm.sendSuggestion(s)
                        },
                        enabled = n?.mapJson != null && busyStage == null,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, "让 AI 改")
                    }
                }
            }
        }
    }

    if (showVersions) {
        VersionsSheet(
            versions = versions,
            onDismiss = { showVersions = false },
            onRestore = { v ->
                showVersions = false
                vm.restore(v.id)
            },
        )
    }
}

@Composable
private fun MapTab(n: NoteEntity?, vm: DetailViewModel, modifier: Modifier = Modifier) {
    if (n == null) return
    val mapJson = n.mapJson
    if (mapJson == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (n.status != NoteStatus.TRANSCRIBING && n.status != NoteStatus.GENERATING) {
                EmptyState(
                    icon = Icons.Filled.AccountTree,
                    title = "还没有思维导图",
                    description = "点下面的按钮，AI 会通读原文，产出一张可拖拽的导图和一段思路。",
                    action = {
                        Button(
                            onClick = { vm.generate() },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("生成导图")
                        }
                    },
                )
            }
        }
        return
    }
    var controller by remember { mutableStateOf<MapController?>(null) }
    val isDark = isSystemInDarkTheme()
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        MindMapPanel(
            mapJson = mapJson,
            editable = n.status == NoteStatus.READY,
            onMapChanged = { vm.saveMap(it) },
            onController = { controller = it },
            modifier = Modifier.fillMaxSize(),
            darkTheme = isDark,
            onPngReady = { base64 -> vm.exportPng(base64) },
        )
        // 画布上的浮层控制：半透明底板 + 图标，压在导图上不抢视线
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CanvasToolButton(Icons.Filled.CenterFocusStrong, "居中") { controller?.toCenter() }
            CanvasToolButton(Icons.Filled.Undo, "撤销") { controller?.undo() }
            CanvasToolButton(Icons.Filled.Image, "导出图片") { controller?.exportPng() }
        }
    }
}

/** 导图上方的圆形小按钮，浅色半透明底，压在画布上也能看清。 */
@Composable
private fun CanvasToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(
                icon,
                label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TranscriptTab(n: NoteEntity?, vm: DetailViewModel, modifier: Modifier = Modifier) {
    if (n == null) return
    Column(modifier.fillMaxSize()) {
        if (n.audioPath != null) {
            AudioPlayerCard(vm)
        }
        val content = n.content.orEmpty()
        if (content.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.Article,
                    title = if (n.status == NoteStatus.TRANSCRIBING) "正在转写…" else "还没有文字",
                    description = if (n.status == NoteStatus.TRANSCRIBING)
                        "识别完会自动接上生成，不用留在这个页面等。"
                    else "这条笔记没有可直接阅读的原文。",
                )
            }
        } else {
            val ctx = LocalContext.current
            // 按段落拆成独立列表项：整篇塞进单个 item 时几万字要一次性测量排版，Lazy 形同虚设
            val paragraphs = remember(content) { content.split('\n').filter { it.isNotBlank() } }
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    contentPadding = PaddingValues(
                        start = Spacing.screen,
                        end = Spacing.screen,
                        top = Spacing.md,
                        bottom = Spacing.xl,
                    ),
                ) {
                    items(paragraphs.size) { i ->
                        Text(
                            paragraphs[i],
                            style = MaterialTheme.typography.bodyLarge,
                            // 原文是长文，行距和段距都放宽，手机上更好读
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                        )
                    }
                    item {
                        OutlinedButton(
                            onClick = {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("原文", content))
                            },
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制全文")
                        }
                    }
                }
            }
        }
    }
}

/** 录音播放条：一整块卡片，播放键为实心主色，进度和时长并排。 */
@Composable
private fun AudioPlayerCard(vm: DetailViewModel) {
    val playing by vm.playing.collectAsState()
    val posMs by vm.playPosMs.collectAsState()
    val durationMs by vm.playDurationMs.collectAsState()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.md, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = { vm.togglePlay() },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "播放/暂停",
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Slider(
                    value = if (durationMs > 0) posMs.toFloat() / durationMs else 0f,
                    onValueChange = { vm.seekTo(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        Formatters.duration(posMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        Formatters.duration(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingTab(n: NoteEntity?, vm: DetailViewModel, modifier: Modifier = Modifier) {
    if (n == null) return
    val speaking by vm.ttsSpeaking.collectAsState()
    val rate by vm.ttsRate.collectAsState()
    Column(modifier.fillMaxSize()) {
        val thinking = n.thinking
        // 占位提示只占剩余空间：朗读按钮必须始终留在屏幕里，思路还没生成时也知道去哪按
        Box(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (thinking.isNullOrBlank()) {
                EmptyState(
                    icon = Icons.Filled.Lightbulb,
                    title = if (n.status == NoteStatus.GENERATING) "正在生成思路…" else "还没有思路",
                    description = if (n.status == NoteStatus.GENERATING) "生成完成后就能在这里朗读。"
                    else "生成完成后，点下面的按钮就能朗读。",
                )
            } else {
                SelectionContainer {
                    Text(
                        thinking,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.screen, vertical = Spacing.md),
                    )
                }
            }
        }
        // 朗读控制条：实心播放键 + 语速滑杆，整条贴在底部
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            ) {
                FilledIconButton(
                    onClick = { vm.toggleTts() },
                    enabled = !thinking.isNullOrBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        if (speaking) "停止朗读" else "朗读思路",
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    if (speaking) "朗读中" else "朗读思路",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (speaking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "%.1fx".format(rate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = rate,
                    onValueChange = { vm.setTtsRate(it) },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.width(132.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionsSheet(
    versions: List<VersionEntity>,
    onDismiss: () -> Unit,
    onRestore: (VersionEntity) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text("版本历史（每次 AI 改动前的快照）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (versions.isEmpty()) {
                Text("还没有快照。让 AI 修改导图后会自动保存。", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(versions.size) { i ->
                        val v = versions[i]
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected = false, onClick = { onRestore(v) })
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(Formatters.dateTime(v.createdAt), style = MaterialTheme.typography.bodyMedium)
                                val preview = remember(v.id) {
                                    runCatching {
                                        TreeText.fromMapData(v.mapJson, v.virtualRoot).lines().firstOrNull().orEmpty()
                                    }.getOrDefault("")
                                }
                                Text(
                                    preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            TextButton(onClick = { onRestore(v) }) { Text("恢复") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 页面顶部的胶囊标签页。
 *
 * 没有用 Material 的 TabRow：TabRow 自带一条贯穿全宽的指示条，在内容边缘是圆角卡片的
 * 页面里显得很硬。这里改成三个等宽药丸，选中态用主色实底，切换时位置连续。
 */
@Composable
private fun PillTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(4.dp)) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                val bg by androidx.compose.animation.animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.primary
                    else androidx.compose.ui.graphics.Color.Transparent,
                    animationSpec = androidx.compose.animation.core.tween(160),
                    label = "tabBg$i",
                )
                Surface(
                    onClick = { onSelect(i) },
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                    color = bg,
                ) {
                    Box(
                        Modifier.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private enum class BannerTone { Busy, Error, Notice }

/** 顶部状态横幅：进行中 / 出错 / 完成但有提示，共用一种形状，只换色调与动作。 */
@Composable
private fun MessageBanner(
    text: String,
    tone: BannerTone,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (container, content) = when (tone) {
        BannerTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        BannerTone.Notice -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.Busy -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm)
            .background(container, MaterialTheme.shapes.medium)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = content,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(Spacing.sm))
            TextButton(onClick = onAction) {
                Text(actionLabel, color = content, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun BusyBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm)
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 顶栏副标题：从哪来、多长、什么时候，一行说清，避免用户在三个标签间找上下文。 */
private fun subtitleFor(n: NoteEntity): String {
    val source = when (n.source) {
        com.tingsiwei.app.data.db.NoteSource.RECORD -> "录音"
        com.tingsiwei.app.data.db.NoteSource.IMPORT -> "导入音频"
        else -> "文字"
    }
    val parts = buildList {
        add(source)
        if (n.durationMs > 0) add(Formatters.duration(n.durationMs))
        add(Formatters.dateTime(n.updatedAt))
    }
    return parts.joinToString(" · ")
}

private fun detailVmFactory(noteId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DetailViewModel(noteId) as T
}

class DetailViewModel(private val noteId: Long) : ViewModel() {

    private val db = App.get().database
    private val settingsRepo = App.get().settings

    val note: Flow<NoteEntity?> =
        if (noteId <= 0L) flowOf(null) else db.noteDao().observeById(noteId)
    val versions: Flow<List<VersionEntity>> =
        if (noteId <= 0L) flowOf(emptyList()) else db.versionDao().observeForNote(noteId)

    private val localStage = MutableStateFlow<String?>(null)

    /** 阶段文案：转写/生成来自 App 级 NoteProcessor（离开页面也在跑），本地小任务（恢复版本）就地补充 */
    val busyStage: Flow<String?> = combine(NoteProcessor.stageFlow(noteId), localStage) { p, l -> l ?: p }

    // 播放器
    private var player: MediaPlayer? = null
    val playing = MutableStateFlow(false)
    val playPosMs = MutableStateFlow(0L)
    val playDurationMs = MutableStateFlow(0L)
    private var playerJob: Job? = null

    // TTS
    private val tts by lazy { TtsPlayer(App.get()) { msg -> uiMessage.value = msg } }
    val ttsSpeaking = MutableStateFlow(false)
    val ttsRate = MutableStateFlow(1.0f)
    /** TtsPlayer、播放器、恢复版本等本地操作的可见提示（如「离线模型未下载，先用系统语音」），UI 弹 Snackbar */
    val uiMessage = MutableStateFlow<String?>(null)

    /** AI 建议输入框内容：放 ViewModel 是为了改图失败后建议不丢，成功才清空 */
    val suggestion = MutableStateFlow("")
    private var reviseAwaitingResult = false

    init {
        viewModelScope.launch { ttsRate.value = settingsRepo.current().ttsRate }
        // 导入时就已知时长，让播放器一开始就显示出来（首次 prepare 后用真实时长覆盖）
        viewModelScope.launch {
            db.noteDao().byId(noteId)?.let { n ->
                if (n.durationMs > 0) playDurationMs.value = n.durationMs
            }
        }
        viewModelScope.launch {
            note.collect { n ->
                if (!reviseAwaitingResult) return@collect
                when (n?.status) {
                    // 改图成功：输入框已完成使命，清掉
                    NoteStatus.READY -> {
                        reviseAwaitingResult = false
                        suggestion.value = ""
                    }
                    // 改图失败：建议留在框里，用户可以直接改字重发，不用重新打一份
                    NoteStatus.ERROR -> reviseAwaitingResult = false
                    else -> {}
                }
            }
        }
    }

    /** 笔记有未完成流程时自动续跑（含进程被杀后的恢复） */
    fun maybeRunPipeline() {
        viewModelScope.launch { NoteProcessor.maybeRun(noteId) }
    }

    fun retry() {
        viewModelScope.launch {
            val n = db.noteDao().byId(noteId) ?: return@launch
            when {
                // 只是"改图失败"：原导图还在也没坏，清掉提示即可，绝不重跑整篇生成（省额度）
                n.status == NoteStatus.ERROR && n.errorMsg.orEmpty().startsWith("修改失败") ->
                    db.noteDao().update(
                        n.copy(status = NoteStatus.READY, errorMsg = null, updatedAt = System.currentTimeMillis())
                    )
                // generate() 覆盖前会自动存版本快照，所以重生成不会弄丢手动整理的导图（时钟图标里可回退）
                n.content.isNullOrBlank() && n.audioPath != null ->
                    NoteProcessor.start(noteId, NoteProcessor.Kind.TRANSCRIBE)
                else -> NoteProcessor.start(noteId, NoteProcessor.Kind.GENERATE)
            }
        }
    }

    fun generate() {
        NoteProcessor.start(noteId, NoteProcessor.Kind.GENERATE)
    }

    fun sendSuggestion(text: String) {
        // 只有真正起跑才算"等待结果"；渠道忙导致没起跑时输入框原样保留
        if (NoteProcessor.start(noteId, NoteProcessor.Kind.REVISE, text)) reviseAwaitingResult = true
    }

    /** 拖动/编辑导图后保存（只在 READY 状态写库，避免和 AI 生成互相覆盖） */
    fun saveMap(json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = db.noteDao().byId(noteId) ?: return@launch
            if (n.status == NoteStatus.READY && n.mapJson != json) {
                db.noteDao().update(n.copy(mapJson = json, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun restore(versionId: Long) {
        // 转写/生成进行中不能改版本：在飞的那次写库用的是开跑前的快照，会把恢复结果覆盖掉
        if (NoteProcessor.isBusy(noteId)) return
        viewModelScope.launch {
            localStage.value = "正在恢复版本…"
            try {
                Generator(db, settingsRepo).restoreVersion(noteId, versionId)
            } catch (e: Exception) {
                // 磁盘满/数据库异常等：报给用户而不是闪退（此前无 catch，Room 写异常会让 App 直接崩）
                uiMessage.value = "恢复版本失败：${e.message ?: "未知错误"}"
            } finally {
                localStage.value = null
            }
        }
    }

    fun shareMarkdown() {
        viewModelScope.launch {
            val n = db.noteDao().byId(noteId) ?: return@launch
            val ctx = App.get()
            val file = Exporter.exportMarkdown(ctx, n)
            Exporter.shareFile(ctx, file, "text/markdown")
        }
    }

    /**
     * 导图画布「导出图片」：WebView 把画布渲染成 PNG 回传 base64，落盘后调起系统分享。
     * 这条链路此前只写了没接（onPngCb 空实现、无触发按钮），2026-10-31 接通。
     */
    fun exportPng(base64: String) {
        viewModelScope.launch {
            try {
                val n = db.noteDao().byId(noteId) ?: return@launch
                val ctx = App.get()
                val bytes = withContext(Dispatchers.IO) { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }
                val file = Exporter.savePng(ctx, bytes, n)
                Exporter.shareFile(ctx, file, "image/png")
            } catch (e: Exception) {
                uiMessage.value = "导出图片失败：${e.message ?: "未知错误"}"
            }
        }
    }

    // ---------- 播放 ----------

    fun togglePlay() {
        viewModelScope.launch {
            val n = db.noteDao().byId(noteId) ?: return@launch
            val path = n.audioPath ?: return@launch
            val p = player
            if (p == null) {
                try {
                    val mp = withContext(Dispatchers.IO) {
                        val m = MediaPlayer()
                        try {
                            m.setDataSource(path)
                            m.prepare()
                            m
                        } catch (e: Exception) {
                            m.release() // 半初始化的播放器也要放掉，否则 native 资源泄漏
                            throw e
                        }
                    }
                    mp.setOnCompletionListener {
                        playing.value = false
                        playPosMs.value = playDurationMs.value
                    }
                    playDurationMs.value = mp.duration.toLong()
                    player = mp
                    mp.start()
                    playing.value = true
                    startTicker()
                } catch (e: Exception) {
                    // 此前静默吞掉：录音文件损坏/被清理时点播放毫无反应，用户不知道为什么
                    playing.value = false
                    uiMessage.value = "音频播放失败：${e.message ?: "文件可能已损坏"}"
                }
            } else if (p.isPlaying) {
                p.pause()
                playing.value = false
            } else {
                p.start()
                playing.value = true
                startTicker()
            }
        }
    }

    private fun startTicker() {
        playerJob?.cancel()
        playerJob = viewModelScope.launch {
            while (playing.value) {
                player?.let { playPosMs.value = it.currentPosition.toLong() }
                delay(300)
            }
        }
    }

    fun seekTo(fraction: Float) {
        val p = player ?: return
        val target = (fraction * playDurationMs.value).toInt()
        p.seekTo(target)
        playPosMs.value = target.toLong()
    }

    // ---------- TTS ----------

    fun toggleTts() {
        viewModelScope.launch {
            val n = db.noteDao().byId(noteId) ?: return@launch
            val thinking = n.thinking ?: return@launch
            toggleTtsText(thinking)
        }
    }

    /** 思路 / 原文共用一个播放器：正在读时就停，否则读传入的文本 */
    fun toggleTtsText(text: String) {
        viewModelScope.launch {
            if (tts.isSpeaking) {
                tts.stop()
                ttsSpeaking.value = false
            } else {
                val s = settingsRepo.current()
                val cloud = CloudTtsConfig.fromSettings(s)
                tts.speak(text, ttsRate.value, s.ttsEngine, cloud)
                ttsSpeaking.value = true
                while (tts.isSpeaking) delay(300)
                ttsSpeaking.value = false
            }
        }
    }

    fun setTtsRate(rate: Float) {
        ttsRate.value = rate
        // 朗读中拖动调速条也要立刻生效，而不是只存起来下次朗读再用
        tts.updateRate(rate)
        viewModelScope.launch { settingsRepo.setTtsRate(rate) }
    }

    override fun onCleared() {
        playerJob?.cancel()
        player?.release()
        player = null
        tts.shutdown()
        super.onCleared()
    }
}
