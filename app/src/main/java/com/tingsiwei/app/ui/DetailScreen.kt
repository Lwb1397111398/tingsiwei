package com.tingsiwei.app.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tingsiwei.app.App
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.data.db.VersionEntity
import com.tingsiwei.app.export.Exporter
import com.tingsiwei.app.llm.Generator
import com.tingsiwei.app.mindmap.TreeText
import com.tingsiwei.app.transcribe.Transcriber
import com.tingsiwei.app.tts.TtsPlayer
import com.tingsiwei.app.ui.components.MapController
import com.tingsiwei.app.ui.components.MindMapPanel
import com.tingsiwei.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(noteId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(key = "note$noteId", factory = detailVmFactory(noteId))
    val note by vm.note.collectAsState(initial = null)
    val versions by vm.versions.collectAsState(initial = emptyList())
    val busyStage by vm.busyStage.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    var showVersions by remember { mutableStateOf(false) }
    var suggestion by remember { mutableStateOf("") }

    LaunchedEffect(note?.id, note?.status) { vm.maybeRunPipeline() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note?.title ?: "笔记", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showVersions = true }, enabled = note?.mapJson != null) {
                        Icon(Icons.Filled.History, "版本历史")
                    }
                    TextButton(
                        onClick = { vm.shareMarkdown() },
                        enabled = note?.mapJson != null,
                    ) { Text("分享") }
                },
            )
        },
        snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) },
    ) { pad ->
        val n = note
        Column(modifier = Modifier.padding(pad).fillMaxSize()) {
            // 状态条
            when {
                n == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                n.status == NoteStatus.TRANSCRIBING || n.status == NoteStatus.GENERATING -> {
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(busyStage ?: "处理中…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                n.status == NoteStatus.ERROR -> {
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "出错：${n.errorMsg ?: "未知"}",
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { vm.retry() }) {
                                Icon(Icons.Filled.Refresh, null)
                                Text("重试")
                            }
                        }
                    }
                }
                n.status == NoteStatus.READY && !n.errorMsg.isNullOrBlank() -> {
                    // errorMsg 在 READY 态下是"提示"（如个别段未纳入），不是错误
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                n.errorMsg,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { vm.retry() }) {
                                Icon(Icons.Filled.Refresh, null)
                                Text("补全")
                            }
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = tab) {
                listOf("导图", "原文", "思路").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }

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

            // 底部：AI 建议输入
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = suggestion,
                        onValueChange = { suggestion = it },
                        modifier = Modifier.weight(1f).height(72.dp),
                        placeholder = {
                            Text(
                                "对导图提建议，如：把重点标成一级、补充相关案例、思路再口语化一点",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val s = suggestion.trim()
                            if (s.isNotEmpty()) {
                                suggestion = ""
                                vm.sendSuggestion(s)
                            }
                        },
                        enabled = n?.mapJson != null && busyStage == null,
                    ) {
                        Text("让 AI 改")
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (n.status != NoteStatus.TRANSCRIBING && n.status != NoteStatus.GENERATING) {
                    Text("还没有思维导图")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.generate() }) { Text("生成导图") }
                }
            }
        }
        return
    }
    var controller by remember { mutableStateOf<MapController?>(null) }
    val isDark = isSystemInDarkTheme()
    Box(modifier.fillMaxSize()) {
        MindMapPanel(
            mapJson = mapJson,
            editable = n.status == NoteStatus.READY,
            onMapChanged = { vm.saveMap(it) },
            onController = { controller = it },
            modifier = Modifier.fillMaxSize(),
            darkTheme = isDark,
        )
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            IconButton(onClick = { controller?.toCenter() }) {
                Icon(Icons.Filled.CenterFocusStrong, "居中")
            }
            IconButton(onClick = { controller?.undo() }) {
                Icon(Icons.Filled.Undo, "撤销")
            }
        }
    }
}

@Composable
private fun TranscriptTab(n: NoteEntity?, vm: DetailViewModel, modifier: Modifier = Modifier) {
    if (n == null) return
    Column(modifier.fillMaxSize()) {
        if (n.audioPath != null) {
            AudioPlayerRow(vm)
        }
        val content = n.content.orEmpty()
        if (content.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (n.status == NoteStatus.TRANSCRIBING) "正在转写…" else "暂无文字内容")
            }
        } else {
            val ctx = LocalContext.current
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Text(content, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(24.dp))
                        TextButton(onClick = {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("原文", content))
                        }) { Text("复制全文") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPlayerRow(vm: DetailViewModel) {
    val playing by vm.playing.collectAsState()
    val posMs by vm.playPosMs.collectAsState()
    val durationMs by vm.playDurationMs.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.togglePlay() }) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "播放/暂停")
        }
        Slider(
            value = if (durationMs > 0) posMs.toFloat() / durationMs else 0f,
            onValueChange = { vm.seekTo(it) },
            modifier = Modifier.weight(1f),
        )
        Text(
            "${Formatters.duration(posMs)} / ${Formatters.duration(durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ThinkingTab(n: NoteEntity?, vm: DetailViewModel, modifier: Modifier = Modifier) {
    if (n == null) return
    val speaking by vm.ttsSpeaking.collectAsState()
    val rate by vm.ttsRate.collectAsState()
    Column(modifier.fillMaxSize().padding(16.dp)) {
        val thinking = n.thinking
        if (thinking.isNullOrBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (n.status == NoteStatus.GENERATING) "正在生成…" else "还没有思路")
            }
        } else {
            SelectionContainer {
                Text(
                    thinking,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.toggleTts() }) {
                Icon(if (speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text(if (speaking) "停止" else "朗读思路")
            }
            Spacer(Modifier.width(16.dp))
            Text("%.1fx".format(rate), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = rate,
                onValueChange = { vm.setTtsRate(it) },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.weight(1f),
            )
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
            Text("版本历史（AI 修改前的快照）", style = MaterialTheme.typography.titleMedium)
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

    val busyStage = MutableStateFlow<String?>(null)
    /** 当前转写/生成/修改任务；防止状态变化触发的自动续跑和手动操作重复执行 */
    private var pipelineJob: Job? = null

    // 播放器
    private var player: MediaPlayer? = null
    val playing = MutableStateFlow(false)
    val playPosMs = MutableStateFlow(0L)
    val playDurationMs = MutableStateFlow(0L)
    private var playerJob: Job? = null

    // TTS
    private val tts by lazy { TtsPlayer(App.get()) }
    val ttsSpeaking = MutableStateFlow(false)
    val ttsRate = MutableStateFlow(1.0f)

    init {
        viewModelScope.launch { ttsRate.value = settingsRepo.current().ttsRate }
    }

    private fun startPipeline(block: suspend () -> Unit) {
        if (pipelineJob?.isActive == true) return
        pipelineJob = viewModelScope.launch { block() }
    }

    /** 笔记有未完成流程时自动续跑（含进程被杀后的恢复） */
    fun maybeRunPipeline() {
        if (noteId <= 0L) return
        viewModelScope.launch {
            if (pipelineJob?.isActive == true) return@launch
            val n = db.noteDao().byId(noteId) ?: return@launch
            when {
                n.status == NoteStatus.TRANSCRIBING && n.content.isNullOrBlank() && n.audioPath != null ->
                    startPipeline { transcribe() }
                n.status == NoteStatus.DRAFT ->
                    startPipeline { generateInternal() }
                // 生成中途被打断且没有结果：重新生成
                n.status == NoteStatus.GENERATING && n.mapJson.isNullOrBlank() ->
                    startPipeline { generateInternal() }
                // 修改中途被打断但旧导图完好：旧导图没有丢，直接恢复可用
                n.status == NoteStatus.GENERATING ->
                    db.noteDao().update(
                        n.copy(status = NoteStatus.READY, errorMsg = null, updatedAt = System.currentTimeMillis())
                    )
            }
        }
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
                n.content.isNullOrBlank() && n.audioPath != null -> startPipeline { transcribe() }
                else -> startPipeline { generateInternal() }
            }
        }
    }

    private suspend fun transcribe() {
        val dao = db.noteDao()
        val n = dao.byId(noteId) ?: return
        val audio = n.audioPath ?: return
        dao.update(n.copy(status = NoteStatus.TRANSCRIBING, errorMsg = null, updatedAt = System.currentTimeMillis()))
        busyStage.value = "正在解码音频…"
        try {
            val text = Transcriber.transcribeOffline(App.get(), audio) { _, msg ->
                busyStage.value = msg
            }
            if (text.isBlank()) {
                throw IllegalStateException("没有识别到内容，请检查录音质量；也可到设置里改用「手机自带识别」重新录一次")
            }
            dao.update(n.copy(content = text, status = NoteStatus.DRAFT, updatedAt = System.currentTimeMillis()))
            generateInternal()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            dao.update(
                n.copy(
                    status = NoteStatus.ERROR,
                    errorMsg = e.message ?: "转写失败",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            busyStage.value = null
        }
    }

    fun generate() {
        if (noteId <= 0L) return
        startPipeline { generateInternal() }
    }

    private suspend fun generateInternal() {
        busyStage.value = "正在生成导图…"
        try {
            Generator(db, settingsRepo).generate(noteId) { busyStage.value = it }
        } finally {
            busyStage.value = null
        }
    }

    fun sendSuggestion(text: String) {
        startPipeline {
            busyStage.value = "正在按你的要求修改…"
            try {
                Generator(db, settingsRepo).revise(noteId, text) { busyStage.value = it }
            } finally {
                busyStage.value = null
            }
        }
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
        viewModelScope.launch {
            busyStage.value = "正在恢复版本…"
            Generator(db, settingsRepo).restoreVersion(noteId, versionId)
            busyStage.value = null
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
                        m.setDataSource(path)
                        m.prepare()
                        m
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
                    playing.value = false
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
            if (tts.isSpeaking) {
                tts.stop()
                ttsSpeaking.value = false
            } else {
                tts.speak(thinking, ttsRate.value)
                ttsSpeaking.value = true
                while (tts.isSpeaking) delay(300)
                ttsSpeaking.value = false
            }
        }
    }

    fun setTtsRate(rate: Float) {
        ttsRate.value = rate
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
