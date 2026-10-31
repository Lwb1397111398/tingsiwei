package com.tingsiwei.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingsiwei.app.App
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteListItem
import com.tingsiwei.app.data.db.NoteSource
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.llm.SegmentStore
import com.tingsiwei.app.pipeline.NoteProcessor
import com.tingsiwei.app.ui.components.EmptyState
import com.tingsiwei.app.ui.components.StatusDot
import com.tingsiwei.app.ui.components.StatusPill
import com.tingsiwei.app.ui.theme.Motion
import com.tingsiwei.app.ui.theme.Spacing
import com.tingsiwei.app.ui.theme.hapticTick
import com.tingsiwei.app.ui.theme.pressScale
import com.tingsiwei.app.ui.theme.rememberPressScale
import com.tingsiwei.app.ui.theme.staggerIn
import com.tingsiwei.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenNote: (Long) -> Unit,
    onOpenRecord: () -> Unit,
    onOpenTextInput: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val notes by vm.notes.collectAsState()
    val query by vm.query.collectAsState()
    val message by vm.message.collectAsState()
    val stages by NoteProcessor.stages.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NoteListItem?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "听的思维",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (notes.isEmpty()) "把听到的，变成看懂的" else "${notes.size} 条笔记",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            val view = LocalView.current
            // 「+」旋转 135° 变成「×」——图标连续转动，比 Add/Close 两枚图标硬切自然
            val fabRotation by animateFloatAsState(
                targetValue = if (menuOpen) 135f else 0f,
                animationSpec = Motion.emphasized(),
                label = "fabRotate",
            )
            Column(horizontalAlignment = Alignment.End) {
                // 展开态：三个动作竖排在主按钮上方，每个都带文字，不用猜图标意思；
                // 容器淡入锚在右下角（贴近 FAB），药丸各自再错峰上浮，先后落定
                AnimatedVisibility(
                    visible = menuOpen,
                    enter = fadeIn(tween(Motion.Snappy)) +
                        scaleIn(
                            Motion.standard(),
                            initialScale = 0.9f,
                            transformOrigin = TransformOrigin(1f, 1f),
                        ),
                    exit = fadeOut(tween(Motion.Snappy)) +
                        scaleOut(
                            Motion.standard(Motion.Snappy),
                            targetScale = 0.9f,
                            transformOrigin = TransformOrigin(1f, 1f),
                        ),
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        NewActionPill(
                            icon = Icons.Filled.Mic,
                            label = "开始录音",
                            modifier = Modifier.staggerIn(0),
                            onClick = { menuOpen = false; onOpenRecord() },
                        )
                        NewActionPill(
                            icon = Icons.Filled.GraphicEq,
                            label = "导入音频文件",
                            modifier = Modifier.staggerIn(1),
                            onClick = { menuOpen = false; importLauncher.launch(arrayOf("audio/*")) },
                        )
                        NewActionPill(
                            icon = Icons.Filled.Edit,
                            label = "输入文字 / 粘贴导图",
                            modifier = Modifier.staggerIn(2),
                            onClick = { menuOpen = false; onOpenTextInput() },
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 6.dp,
                ) {
                    IconButton(
                        onClick = {
                            menuOpen = !menuOpen
                            view.hapticTick()
                        },
                        modifier = Modifier.size(60.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = if (menuOpen) "收起" else "新建",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(28.dp)
                                .graphicsLayer { rotationZ = fabRotation },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChange = { vm.query.value = it },
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            )
            when {
                notes.isEmpty() && query.isBlank() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Outlined.AutoStories,
                            title = "还没有笔记",
                            description = "录一段上课、聊天或面试，\n自动变成思维导图和一段帮你记忆的思路。",
                            action = {
                                FilledTonalButton(onClick = onOpenRecord) {
                                    Icon(Icons.Filled.Mic, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("录第一条")
                                }
                            },
                        )
                    }
                }
                notes.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "没有找到「$query」相关的笔记",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    // 已展示过的笔记不再重播入场动画（滚出去再滚回来时直接就位）
                    val shownIds = remember { mutableSetOf<Long>() }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Spacing.screen,
                            end = Spacing.screen,
                            top = Spacing.sm,
                            // 底部留出浮动按钮展开后的高度，最后一条笔记不会被挡住
                            bottom = 150.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        // 稳定 key：删除/更新时 animateItem 才能做出「淡出移位」而不是整列跳动
                        itemsIndexed(
                            notes,
                            key = { _, n -> n.id },
                            contentType = { _, _ -> "note" },
                        ) { i, note ->
                            val playEnter = note.id !in shownIds
                            LaunchedEffect(note.id) { shownIds.add(note.id) }
                            Box(Modifier.animateItem()) {
                                NoteCard(
                                    note = note,
                                    stage = stages[note.id],
                                    modifier = Modifier.staggerIn(i, enabled = playEnter),
                                    onClick = { onOpenNote(note.id) },
                                    onLongClick = { pendingDelete = note },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条笔记？") },
            text = { Text("「${note.title}」和它的录音会一起删除，版本历史也会删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(note); pendingDelete = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 搜索框：自绘圆角容器，不用 OutlinedTextField 那圈描边（在浅底上太重）。 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.lg, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "搜索标题或原文",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = 12.dp),
                )
            }
            // 清除按钮占一格固定宽度，出现/消失做缩放淡入，占位不跳
            Box(Modifier.width(36.dp)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = scaleIn(
                        Motion.standard(Motion.Snappy),
                        initialScale = 0.6f,
                    ) + fadeIn(Motion.standard(Motion.Snappy)),
                    exit = scaleOut(
                        Motion.standard(Motion.Snappy),
                        targetScale = 0.6f,
                    ) + fadeOut(Motion.standard(Motion.Snappy)),
                ) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            "清除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 新建入口的「药丸」按钮：图标 + 文字，浮在主按钮上方。 */
@Composable
private fun NewActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteListItem,
    stage: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val busy = note.status == NoteStatus.TRANSCRIBING || note.status == NoteStatus.GENERATING
    // 按压缩放：按下时整卡轻轻缩到 97%，松手弹回——列表的「手感」来自这里
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(scale)
            // 阶段药丸出现/消失时卡片高度平滑过渡，列表不跳
            .animateContentSize(Motion.standard())
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 1.dp,
    ) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.Top) {
            // 左侧来源标记：一眼区分录音 / 导入 / 手输
            SourceMark(source = note.source)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    StatusChip(note.status)
                }
                val preview = note.preview?.trim().orEmpty()
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        preview.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        relativeTime(note.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (note.durationMs > 0) {
                        Text(
                            "  ·  ${Formatters.duration(note.durationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
                // 进度行：真实阶段文案来自 NoteProcessor，不是假进度；
                // 文案变化（如「第2/9块 → 第3/9块」）用上滑过渡，而不是瞬间跳字
                AnimatedVisibility(
                    visible = busy && !stage.isNullOrBlank(),
                    enter = fadeIn(tween(Motion.Snappy)) + slideInVertically(Motion.standard()) { it / 2 },
                    exit = fadeOut(tween(Motion.Snappy)) + slideOutVertically(Motion.standard(Motion.Snappy)) { -it / 2 },
                ) {
                    Column {
                        Spacer(Modifier.height(Spacing.sm))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusDot(MaterialTheme.colorScheme.primary, size = 6.dp)
                                Spacer(Modifier.width(6.dp))
                                AnimatedContent(
                                    targetState = stage.orEmpty(),
                                    transitionSpec = {
                                        (slideInVertically(Motion.standard()) { it } + fadeIn(tween(Motion.Snappy))) togetherWith
                                            (slideOutVertically(Motion.standard()) { -it } + fadeOut(tween(Motion.Snappy)))
                                    },
                                    label = "stageText",
                                ) { s ->
                                    Text(
                                        s,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 来源标记方块：录音=麦克风、导入=声波、手输=笔。 */
@Composable
private fun SourceMark(source: String) {
    val (icon, tint, bg) = when (source) {
        NoteSource.RECORD -> Triple(
            Icons.Filled.Mic,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primaryContainer,
        )
        NoteSource.IMPORT -> Triple(
            Icons.Filled.GraphicEq,
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
        )
        else -> Triple(
            Icons.Filled.Edit,
            MaterialTheme.colorScheme.onTertiaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer,
        )
    }
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** 状态药丸：日常克制，只有出错才用红；进行中用琥珀色提示「在动」。 */
@Composable
fun StatusChip(status: String) {
    val scheme = MaterialTheme.colorScheme
    val style = when (status) {
        NoteStatus.READY -> StatusStyle(
            "已完成", scheme.primaryContainer, scheme.onPrimaryContainer, null,
        )
        NoteStatus.GENERATING -> StatusStyle(
            "生成中", scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary,
        )
        NoteStatus.TRANSCRIBING -> StatusStyle(
            "转写中", scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary,
        )
        NoteStatus.DRAFT -> StatusStyle(
            "待生成", scheme.secondaryContainer, scheme.onSecondaryContainer, null,
        )
        NoteStatus.ERROR -> StatusStyle(
            "出错", scheme.errorContainer, scheme.onErrorContainer, null,
        )
        else -> StatusStyle(status, scheme.surfaceContainerHigh, scheme.onSurfaceVariant, null)
    }
    StatusPill(
        text = style.label,
        container = style.container,
        content = style.content,
        leadingDot = style.dot,
    )
}

private data class StatusStyle(
    val label: String,
    val container: Color,
    val content: Color,
    val dot: Color?,
)

/**
 * 相对时间：今天只给「HH:mm」，昨天给「昨天 HH:mm」，一周内给「周三」，更早给「9月29日」。
 * 列表里满是「2026-09-29 09:21」既占地方又难扫。
 */
private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val diff = System.currentTimeMillis() - ts
    return when {
        sameDay(cal, today) -> time
        sameDay(cal, yesterday) -> "昨天 $time"
        diff in 0 until 7L * 24 * 3600 * 1000 -> {
            val names = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            names[cal.get(Calendar.DAY_OF_WEEK) - 1]
        }
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ts))
    }
}

class HomeViewModel : ViewModel() {
    private val db = App.get().database
    val query = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)

    // 轻量投影 + SQL 搜索：整篇原文/导图 JSON 不再拉进内存（旧实现 observeAll 全实体 + 内存过滤，
    // 笔记多且长时首页常驻内存随内容线性膨胀）；有搜索词时切到 LIKE 查询
    @OptIn(ExperimentalCoroutinesApi::class)
    val notes = query.flatMapLatest { q ->
        if (q.isBlank()) db.noteDao().observeList()
        else db.noteDao().searchList(q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(note: NoteListItem) {
        viewModelScope.launch(Dispatchers.IO) {
            // 还在转写/生成的笔记先停掉任务，避免它继续往已删除的笔记上写
            NoteProcessor.cancel(note.id)
            note.audioPath?.let { path ->
                try {
                    File(path).delete()
                } catch (_: Exception) {
                }
            }
            // 分段落盘的断点成果也要一起清掉，否则留成孤儿文件
            try {
                SegmentStore(File(App.get().filesDir, "pipeline")).clear(note.id)
            } catch (_: Exception) {
            }
            // 轻量投影只有路径等展示列，删除前取整实体（版本历史靠外键 CASCADE 一并清）
            val full = db.noteDao().byId(note.id) ?: return@launch
            db.noteDao().delete(full)
        }
    }

    fun importAudio(uri: Uri) {
        val context: Context = App.get()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "recordings").apply { mkdirs() }
                val name = "IMP_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())}"
                val dst = File(dir, "$name.m4a")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                } ?: throw Exception("打不开这个文件")
                // 尝试取文件名做标题
                var display = "导入的音频"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) display = c.getString(idx) ?: display
                }
                // 取时长
                var durationMs = 0L
                try {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(dst.absolutePath)
                    durationMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    mmr.release()
                } catch (_: Exception) {
                }
                val title = display.substringBeforeLast('.').take(40)
                val id = db.noteDao().insert(
                    NoteEntity(
                        title = title,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        status = NoteStatus.TRANSCRIBING,
                        source = NoteSource.IMPORT,
                        audioPath = dst.absolutePath,
                        durationMs = durationMs,
                    )
                )
                // 导入即开跑：不必再要求用户点开一次笔记（App 级协程，离开页面也不会断）
                NoteProcessor.start(id, NoteProcessor.Kind.TRANSCRIBE)
                message.value = "导入成功！已开始转写并生成导图，点开笔记可看进度"
            } catch (e: Exception) {
                message.value = "导入失败：${e.message}"
            }
        }
    }
}
