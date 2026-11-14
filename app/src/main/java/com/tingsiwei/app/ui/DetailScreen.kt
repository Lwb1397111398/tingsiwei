package com.tingsiwei.app.ui

import android.media.MediaPlayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.imePadding
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingsiwei.app.App
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteSource
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.data.db.VersionEntity
import com.tingsiwei.app.diagram.DiagramDoc
import com.tingsiwei.app.diagram.DiagramParser
import com.tingsiwei.app.diagram.DetailDoc
import com.tingsiwei.app.diagram.DetailParser
import com.tingsiwei.app.diagram.composeFullText
import com.tingsiwei.app.export.Exporter
import com.tingsiwei.app.llm.Generator
import com.tingsiwei.app.mindmap.TreeText
import com.tingsiwei.app.pipeline.NoteProcessor
import com.tingsiwei.app.transcribe.Timeline
import com.tingsiwei.app.transcribe.TimelineLocator
import com.tingsiwei.app.tts.CloudTtsConfig
import com.tingsiwei.app.tts.TtsPlayer
import com.tingsiwei.app.ui.components.DiagramView
import com.tingsiwei.app.ui.components.EmptyState
import com.tingsiwei.app.ui.components.InlineNotice
import com.tingsiwei.app.ui.components.MapController
import com.tingsiwei.app.ui.components.MindMapPanel
import com.tingsiwei.app.ui.theme.Motion
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(noteId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(key = "note$noteId", factory = detailVmFactory(noteId))
    val note by vm.note.collectAsState(initial = null)
    val versions by vm.versions.collectAsState(initial = emptyList())
    val busyStage by vm.busyStage.collectAsState(initial = null)

    var tab by remember { mutableIntStateOf(0) }
    // 思路页跳转的待对准句索引：跳转成功后原文页首次组合时滚到这句居中，对准后清空
    var pendingCenterIdx by remember { mutableStateOf<Int?>(null) }
    var showVersions by remember { mutableStateOf(false) }
    val suggestion by vm.suggestion.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val ttsMsg by vm.uiMessage.collectAsState()
    val scope = rememberCoroutineScope()

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
        Column(modifier = Modifier.padding(pad).fillMaxSize().imePadding()) {
            // 分类标签：点击改归类（总导图笔记绑定分类，不显示）
            n?.let { noteVal ->
                if (noteVal.source != NoteSource.MERGED) {
                    CategoryTag(
                        categoryId = noteVal.categoryId,
                        onPick = { cid ->
                            scope.launch(Dispatchers.IO) {
                                App.get().database.noteDao().setCategory(noteVal.id, cid)
                            }
                        },
                    )
                }
            }
            // 状态提示条：三种情形（进行中 / 出错 / 完成但有提示）统一成一块；
            // 出现/消失带展开收起动画——生成完成时横幅是「收回去」而不是「啪一下没了」。
            // banner 变 null 后退场动画期间仍显示最后一份内容（lastBanner 缓存）。
            data class BannerUi(val text: String, val tone: BannerTone, val actionLabel: String? = null)
            val banner: BannerUi? = when {
                n == null -> null
                n.status == NoteStatus.TRANSCRIBING || n.status == NoteStatus.GENERATING ->
                    BannerUi(busyStage ?: "处理中…", BannerTone.Busy)
                n.status == NoteStatus.ERROR -> BannerUi(
                    n.errorMsg?.let { "出错：$it" } ?: "出错：未知原因",
                    BannerTone.Error,
                    actionLabel = "重试",
                )
                n.status == NoteStatus.READY && !n.errorMsg.isNullOrBlank() ->
                    // errorMsg 在 READY 态下是"提示"（如个别段未纳入），不是错误
                    BannerUi(n.errorMsg, BannerTone.Notice, actionLabel = "补全")
                else -> null
            }
            val lastBanner = remember { mutableStateOf<BannerUi?>(null) }
            if (banner != null) lastBanner.value = banner
            AnimatedVisibility(
                visible = banner != null,
                enter = expandVertically(Motion.standard()) + fadeIn(tween(Motion.Snappy)),
                exit = shrinkVertically(Motion.standard()) + fadeOut(tween(Motion.Snappy)),
            ) {
                val b = lastBanner.value
                if (b != null) {
                    MessageBanner(
                        text = b.text,
                        tone = b.tone,
                        actionLabel = b.actionLabel,
                        onAction = b.actionLabel?.let { { vm.retry() } },
                    )
                }
            }

            if (n == null) {
                // 笔记还没从数据库加载出来：整页居中加载
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            PillTabs(
                labels = listOf("导图", "原文", "思路"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            )

            // 三个标签叠放：导图 WebView 常驻组合（切页不销毁重建，保留缩放/平移位置）；
            // 原文/思路叠在 AnimatedContent 里，按切换方向左右轻滑——往右翻从右进，往左翻从左进
            Box(Modifier.weight(1f)) {
                MapTab(n, vm, Modifier.fillMaxSize())
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val enter = slideInHorizontally(Motion.standard(260)) { full ->
                            if (forward) full / 12 else -full / 12
                        } + fadeIn(tween(Motion.Standard))
                        val exit = slideOutHorizontally(Motion.standard(260)) { full ->
                            if (forward) -full / 12 else full / 12
                        } + fadeOut(tween(Motion.Snappy))
                        enter togetherWith exit
                    },
                    label = "detailTab",
                ) { t ->
                    when (t) {
                        1 -> TranscriptTab(
                            n, vm, pendingCenterIdx, { pendingCenterIdx = null },
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        )
                        2 -> ThinkingTab(
                            n, vm,
                            onJump = { idx -> pendingCenterIdx = idx; tab = 1 },
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        )
                        else -> Box(Modifier.fillMaxSize())
                    }
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
                        modifier = Modifier
                            .weight(1f)
                            // 多行输入时高度平滑增长，底栏不跳
                            .animateContentSize(Motion.standard()),
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
        // 画布浮层只留「导出图片」：撤销/居中在底部工具条里都有，不再重复占画布空间
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.md),
        ) {
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
private fun TranscriptTab(
    n: NoteEntity?,
    vm: DetailViewModel,
    pendingCenterIdx: Int?,
    onCentered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (n == null) return
    // 时间轴随笔记解析一次（几百句 JSON 毫秒级），不随播放进度反复解析
    val segments = remember(n.id, n.timings) { Timeline.decode(n.timings) }
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
        } else if (segments.isNotEmpty()) {
            // 有时间轴：逐句渲染，播放时高亮当前句并自动滚动对准
            SyncedTranscript(vm, segments, content, pendingCenterIdx, onCentered)
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

/**
 * 有时间轴的原文：逐句渲染，播放到哪句就高亮哪句，并把页面滚动到让这句居中。
 *
 * 跟随规则（按老板要求是「强制对准」）：
 * - 只认「当前句变化」这一事件滚动，不逐帧跟（进度流 300ms 一跳，句子几秒才换）；
 * - 用户手指拖着列表时不抢（拖着走），一松手，下一句变化时立刻拉回对准；
 * - 点任意一句 = 从那句开始播（在播就跳过去，没播就起播），方便单句反复听。
 */
@Composable
private fun SyncedTranscript(
    vm: DetailViewModel,
    segments: List<Timeline.Segment>,
    content: String,
    pendingCenterIdx: Int?,
    onCentered: () -> Unit,
) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    val posMs by vm.playPosMs.collectAsState()
    // 手指拖动期间暂停自动跟滚；松手后由下一次句变化自然接管
    val dragging = remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> dragging.value = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> dragging.value = false
            }
        }
    }
    LaunchedEffect(listState, segments) {
        var last = -1
        vm.playPosMs.collect { pos ->
            if (pos <= 0L) return@collect
            val idx = Timeline.activeIndex(segments, pos)
            // 拖动期间只记不滚：last 不更新，松手后下一跳必然触发一次对准
            if (idx != last && !dragging.value) {
                last = idx
                centerItem(listState, idx)
            }
        }
    }
    // 思路页跳转过来时：列表刚重新组合，自动跟随只认「句变化」可能不触发，主动对准一次
    LaunchedEffect(pendingCenterIdx) {
        val idx = pendingCenterIdx ?: return@LaunchedEffect
        centerItem(listState, idx)
        onCentered()
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            contentPadding = PaddingValues(
                start = Spacing.screen,
                end = Spacing.screen,
                top = Spacing.md,
                bottom = Spacing.xl,
            ),
        ) {
            items(segments.size) { i ->
                val seg = segments[i]
                val active = i == Timeline.activeIndex(segments, posMs)
                val bg by animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    animationSpec = tween(Motion.Snappy),
                    label = "segBg$i",
                )
                Text(
                    seg.text,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg, MaterialTheme.shapes.medium)
                        .clickable { vm.onSentenceTap(seg.startMs) }
                        // 高亮态与普通态 padding 一致，切换时文字不跳位
                        .padding(horizontal = 10.dp, vertical = 6.dp),
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

/** 把第 index 句滚到视口中部：不在屏内先瞬移到跟前，等一帧布局落定后再按实测高度居中 */
private suspend fun centerItem(listState: LazyListState, index: Int) {
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
        listState.scrollToItem(index)
    }
    withFrameNanos { }
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val layout = listState.layoutInfo
    val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    val delta = (info.offset + info.size / 2 - viewportCenter).toFloat()
    if (abs(delta) > 48f) {
        listState.animateScrollBy(delta, Motion.standard(280))
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
private fun ThinkingTab(
    n: NoteEntity?,
    vm: DetailViewModel,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (n == null) return
    val speaking by vm.ttsSpeaking.collectAsState()
    val rate by vm.ttsRate.collectAsState()
    // 分段图示随笔记解析一次；null = 未生成/已损坏 → 回退整段显示
    val doc = remember(n.id, n.diagramJson) { DiagramParser.decode(n.diagramJson) }
    // 详细讲解随笔记解析一次；null = 未生成 → 卡片无展开内容，文字版/朗读回退思路原文
    val detailDoc = remember(n.id, n.detailJson) { DetailParser.decode(n.detailJson) }
    // 文字版全文：有详解 = 各节详解拼接（课堂细节完整，适合连读与听书）；没有详解回退思路原文
    val textVersion = remember(n.id, n.thinking, n.detailJson) {
        detailDoc?.let(::composeFullText) ?: n.thinking.orEmpty()
    }
    // 双版本切换：默认图示卡片（外观与从前一致），文字版适合连读、复制
    var textMode by remember(n.id) { mutableStateOf(false) }
    // 跳原声的可行性：有录音 + 有句级时间轴。文字笔记/旧笔记自动没有此功能，界面不显示入口
    val segments = remember(n.id, n.timings) { Timeline.decode(n.timings) }
    val canJump = segments.isNotEmpty() && n.audioPath != null
    // 点一节/一段 → 在时间轴里定位起点句；命中起播并跳原文页（onJump 切页+对准），
    // 置信不足给提示不乱跳
    val jump: (String) -> Unit = { query ->
        val idx = TimelineLocator.locate(segments, query)
        if (idx == null) {
            vm.uiMessage.value = "没在录音里找到这段话对应的位置，可能改写幅度太大"
        } else {
            vm.onSentenceTap(segments[idx].startMs)
            onJump(idx)
        }
    }
    Column(modifier.fillMaxSize()) {
        val thinking = n.thinking
        // 占位提示只占剩余空间：朗读按钮必须始终留在屏幕里，思路还没生成时也知道去哪按
        Box(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (thinking.isNullOrBlank() && detailDoc == null) {
                EmptyState(
                    icon = Icons.Filled.Lightbulb,
                    title = if (n.status == NoteStatus.GENERATING) "正在生成思路…" else "还没有思路",
                    description = if (n.status == NoteStatus.GENERATING) "生成完成后就能在这里朗读。"
                    else "生成完成后，点下面的按钮就能朗读。",
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (doc != null) {
                        ViewModeToggle(textMode) { textMode = it }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (textMode && doc != null) {
                            TextVersionView(textVersion)
                        } else {
                            ThinkingSections(n, vm, doc, detailDoc, canJump, jump)
                        }
                    }
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

/**
 * 思路的两种形态：
 * 有分段图示（diagramJson 非空）→ 小节卡片流，讲关系的小节下面挂一张解释图，点卡片展开详细讲解；
 * 没有 → 回退整段文本（老笔记、图示失败、开关关闭都走这里），并给一个手动生成的入口。
 */
@Composable
private fun ThinkingSections(
    n: NoteEntity,
    vm: DetailViewModel,
    doc: DiagramDoc?,
    detailDoc: DetailDoc?,
    canJump: Boolean,
    onJump: (String) -> Unit,
) {
    val busyStage by vm.busyStage.collectAsState(initial = null)
    val sections = doc?.sections
    // 详解与图示小节按标题对齐；标题对不上的条目 = 总结漏掉的整个话题（补充小节），排在最后
    val detailByTitle = remember(n.id, n.detailJson) {
        detailDoc?.items?.filter { !it.extra && it.title.isNotEmpty() }
            ?.associateBy { it.title }
            .orEmpty()
    }
    val extras = remember(n.id, n.detailJson) {
        detailDoc?.items?.filter { it.extra && it.detail.isNotBlank() }.orEmpty()
    }
    // 展开状态按渲染序号记（小节 0..size-1，补充小节接着排）；详解重生成后整体收起
    val expanded = remember(n.id, n.detailJson) { mutableStateListOf<Int>() }
    fun toggle(i: Int) {
        if (i in expanded) expanded.remove(i) else expanded.add(i)
    }
    val hasDetail = detailByTitle.isNotEmpty() || extras.isNotEmpty()
    if (sections.isNullOrEmpty()) {
        // 回退形态：按段显示 + 手动生成图示入口。有录音时间轴时段落可点（跳原声），
        // 文字笔记/旧笔记则只读。结构必须用 Box(weight) 外包 + Text(fillMaxSize) 内填：
        // weight 直接写在 SelectionContainer/Text 上会被 wrapper 挡住（Column 只认直接
        // child 的 ParentDataModifier），文本区占满全高把按钮挤扁——两种写法都实测踩过。
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val paragraphs = remember(n.id, n.thinking) {
                    TimelineLocator.splitForDisplay(n.thinking.orEmpty())
                }
                SelectionContainer {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.screen, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        if (canJump) {
                            Text(
                                "点任意一段，跳到录音里讲这段的位置听原声",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        paragraphs.forEach { p ->
                            Text(
                                p,
                                style = MaterialTheme.typography.bodyLarge,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canJump) { onJump(p) }
                                    .padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            if (busyStage == null) {
                OutlinedButton(
                    onClick = { vm.regenDiagrams() },
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = Spacing.sm),
                ) {
                    Icon(Icons.Filled.Image, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("生成分段图示")
                }
            }
        }
        return
    }
    // 图示/详解任务进行中（READY 态下 stage 只来自图示化/详解/版本恢复这类本地任务）：顶部一条轻提示
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
            busyStage?.takeIf { it.isNotBlank() }?.let { stage ->
                item(key = "diagramBusy") {
                    InlineNotice(stage)
                }
            }
            item(key = "hint") {
                val hint = when {
                    !hasDetail && canJump -> "生成详细讲解后点卡片展开；点 ▶ 跳到录音对应位置听原声"
                    !hasDetail -> "生成详细讲解后，点卡片可展开更多内容"
                    canJump -> "点卡片展开详细讲解；点 ▶ 跳到录音对应位置听原声"
                    else -> "点卡片展开详细讲解"
                }
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(sections.size) { i ->
                val s = sections[i]
                val d = detailByTitle[s.title.trim()]
                val isExpanded = i in expanded
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = d != null) { toggle(i) },
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        if (s.title.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${i + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    s.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (d != null) {
                                    val chevron by animateFloatAsState(
                                        if (isExpanded) 180f else 0f,
                                        label = "detailChevron",
                                    )
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        if (isExpanded) "收起详细讲解" else "展开详细讲解",
                                        modifier = Modifier.size(20.dp).rotate(chevron),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (canJump) {
                                    Box(
                                        Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .clickable { onJump(s.title + " " + s.text) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            "跳到录音里讲这段的位置听原声",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            s.text,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                        )
                        s.diagram?.let { dg ->
                            DiagramView(
                                dg,
                                modifier = Modifier.padding(top = Spacing.xs),
                            )
                        }
                        if (d != null) {
                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    Text(
                                        "详细讲解",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        d.detail,
                                        style = MaterialTheme.typography.bodyMedium,
                                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 补充小节：录音里有、总结整个漏掉的话题。没有简短正文可收，收起时预览前三行
            items(extras.size) { j ->
                val e = extras[j]
                val idx = sections.size + j
                val isExpanded = idx in expanded
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggle(idx) },
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "补",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                e.title.ifBlank { "补充内容" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            val chevron by animateFloatAsState(
                                if (isExpanded) 180f else 0f,
                                label = "extraChevron",
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                if (isExpanded) "收起补充内容" else "展开补充内容",
                                modifier = Modifier.size(20.dp).rotate(chevron),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (canJump) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable { onJump(e.title + " " + e.detail.take(80)) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        "跳到录音里讲这段的位置听原声",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Text(
                            e.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.animateContentSize(),
                        )
                    }
                }
            }
            item(key = "regenDiagram") {
                if (busyStage == null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        OutlinedButton(
                            onClick = { vm.regenDiagrams() },
                            shape = CircleShape,
                        ) {
                            Text("重新生成图示")
                        }
                    }
                }
            }
            item(key = "regenDetail") {
                if (busyStage == null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        OutlinedButton(
                            onClick = { vm.regenDetail() },
                            shape = CircleShape,
                        ) {
                            Text(if (detailDoc != null) "重新生成讲解" else "生成详细讲解")
                        }
                    }
                }
            }
        }
    }
}

/** 图示/文字双版本切换：样式对齐页面顶部的 PillTabs（小号），默认停在图示卡片 */
@Composable
private fun ViewModeToggle(textMode: Boolean, onTextMode: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
    ) {
        TogglePill("图示卡片", !textMode) { onTextMode(false) }
        TogglePill("文字版", textMode) { onTextMode(true) }
    }
}

@Composable
private fun TogglePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * 文字版：详解拼接的全文（没详解时是思路原文），适合连读、听书与一键复制。
 * 思路原文此前没有任何复制入口，这个视图顺带把缺口补上。
 */
@Composable
private fun TextVersionView(text: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    SelectionContainer {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
            )
            OutlinedButton(
                onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("文字版", text))
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
                val fg by androidx.compose.animation.animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = androidx.compose.animation.core.tween(160),
                    label = "tabFg$i",
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
                            color = fg,
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
        // 阶段文案变化（「正在转写…」→「生成导图…」）时上滑换字，不生硬跳变
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (slideInVertically(Motion.standard()) { it / 2 } + fadeIn(tween(Motion.Snappy))) togetherWith
                    (slideOutVertically(Motion.standard()) { -it / 2 } + fadeOut(tween(Motion.Snappy)))
            },
            label = "busyText",
        ) { t ->
            Text(
                t,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 顶栏副标题：从哪来、多长、什么时候，一行说清，避免用户在三个标签间找上下文。 */
private fun subtitleFor(n: NoteEntity): String {
    val source = when (n.source) {
        com.tingsiwei.app.data.db.NoteSource.RECORD -> "录音"
        com.tingsiwei.app.data.db.NoteSource.IMPORT -> "导入音频"
        com.tingsiwei.app.data.db.NoteSource.MERGED -> "分类总导图"
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

    /**
     * 生成/重画分段图示（思路页按钮）。开关关闭或没思路时给一句人话提示；
     * 同笔记已有任务在跑时宿主幂等拒绝，这里把拒绝转成可见提示。
     */
    fun regenDiagrams() {
        viewModelScope.launch {
            val s = settingsRepo.current()
            if (!s.diagramsEnabled) {
                uiMessage.value = "分段图示在设置里是关闭的，先到设置页「生成偏好」打开"
                return@launch
            }
            val n = db.noteDao().byId(noteId) ?: return@launch
            if (n.thinking.isNullOrBlank()) {
                uiMessage.value = "还没有思路，先生成一次再配图"
                return@launch
            }
            if (!NoteProcessor.start(noteId, NoteProcessor.Kind.DIAGRAM)) {
                uiMessage.value = "这篇笔记还有任务在跑，等它完成再试"
            }
        }
    }

    /**
     * 生成/重写详细讲解（思路页按钮）。前置：有思路、有分段图示；开关关闭给提示。
     * DetailFlow 是软失败（不写状态不抛错），这里等任务跑完比对 detailJson 有没有变化，
     * 没变化给一句可见提示，不让按钮「点了没反应」。
     */
    fun regenDetail() {
        viewModelScope.launch {
            val s = settingsRepo.current()
            if (!s.detailsEnabled) {
                uiMessage.value = "详细讲解在设置里是关闭的，先到设置页「生成偏好」打开"
                return@launch
            }
            val n = db.noteDao().byId(noteId) ?: return@launch
            if (n.thinking.isNullOrBlank()) {
                uiMessage.value = "还没有思路，先生成一次再写讲解"
                return@launch
            }
            if (n.diagramJson.isNullOrBlank()) {
                uiMessage.value = "还没有分段图示，先点「生成分段图示」"
                return@launch
            }
            val before = n.detailJson
            if (NoteProcessor.start(noteId, NoteProcessor.Kind.DETAIL, "manual")) {
                while (NoteProcessor.isBusy(noteId)) delay(300)
                val after = db.noteDao().byId(noteId)
                if (after != null && after.detailJson == before) {
                    uiMessage.value = "详细讲解没生成出来（接口没返回可用内容），稍后再试或换个模型"
                }
            } else {
                uiMessage.value = "这篇笔记还有任务在跑，等它完成再试"
            }
        }
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
            val p = ensurePlayer() ?: return@launch
            if (p.isPlaying) {
                p.pause()
                playing.value = false
            } else {
                p.start()
                playing.value = true
                startTicker()
            }
        }
    }

    /** 播放器懒创建：首次点播放/点句子才 prepare；失败报 Snackbar 返回 null */
    private suspend fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        val path = db.noteDao().byId(noteId)?.audioPath ?: return null
        return try {
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
            mp
        } catch (e: Exception) {
            // 此前静默吞掉：录音文件损坏/被清理时点播放毫无反应，用户不知道为什么
            playing.value = false
            uiMessage.value = "音频播放失败：${e.message ?: "文件可能已损坏"}"
            null
        }
    }

    /**
     * 点某一句：从那句的起点播——在播就跳过去，没播就起播。
     * 跟读场景「这句没听清，从这里再来一遍」。
     */
    fun onSentenceTap(startMs: Long) {
        viewModelScope.launch {
            val p = ensurePlayer() ?: return@launch
            val target = startMs.coerceIn(0L, playDurationMs.value).toInt()
            p.seekTo(target)
            playPosMs.value = target.toLong()
            if (!p.isPlaying) {
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
            // 听书读「文字版」：有详解读拼接全文（课堂细节完整，适合收听），没有详解回退思路原文
            val text = DetailParser.decode(n.detailJson)?.let(::composeFullText) ?: n.thinking ?: return@launch
            toggleTtsText(text)
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

/** 详情页顶部的分类标签：色点 + 分类名，点击弹分类选择器改归类（不动 updatedAt）。 */
@Composable
private fun CategoryTag(categoryId: Long?, onPick: (Long?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val categories by App.get().database.categoryDao().observeAll()
        .collectAsState(initial = emptyList())
    val name = categories.firstOrNull { it.id == categoryId }?.name

    Surface(
        onClick = { showPicker = true },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(CategoryUi.color(categoryId)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                name ?: "未分类 · 点击归类",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPicker) {
        CategoryPickerSheet(
            current = categoryId,
            onDismiss = { showPicker = false },
            onPick = { onPick(it); showPicker = false },
        )
    }
}
