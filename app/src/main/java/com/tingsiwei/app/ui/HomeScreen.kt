package com.tingsiwei.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteSource
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
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
    val snackbar = remember { SnackbarHostState() }

    var fabOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NoteEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }

    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听的思维") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, "设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabOpen = !fabOpen }) {
                    Icon(Icons.Filled.Add, "新建")
                }
                DropdownMenu(expanded = fabOpen, onDismissRequest = { fabOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("开始录音") },
                        leadingIcon = { Icon(Icons.Filled.Mic, null) },
                        onClick = { fabOpen = false; onOpenRecord() },
                    )
                    DropdownMenuItem(
                        text = { Text("导入音频文件") },
                        leadingIcon = { Icon(Icons.Filled.GraphicEq, null) },
                        onClick = {
                            fabOpen = false
                            importLauncher.launch(arrayOf("audio/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("输入文字 / 粘贴导图") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { fabOpen = false; onOpenTextInput() },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索标题或原文") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "点右下角 + 开始：\n录上课 / 聊天 / 面试，自动变成思维导图和记忆思路",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                    items(notes.size) { i ->
                        val note = notes[i]
                        NoteCard(
                            note = note,
                            onClick = { onOpenNote(note.id) },
                            onLongClick = { pendingDelete = note },
                        )
                        Spacer(Modifier.height(8.dp))
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
                TextButton(onClick = { vm.delete(note); pendingDelete = null }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(note: NoteEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                StatusChip(note.status)
            }
            Spacer(Modifier.height(4.dp))
            val preview = note.thinking ?: note.content ?: "（还没有内容）"
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    Formatters.dateTime(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (note.durationMs > 0) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "时长 ${Formatters.duration(note.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val (label, color) = when (status) {
        NoteStatus.READY -> "已完成" to MaterialTheme.colorScheme.primaryContainer
        NoteStatus.GENERATING -> "生成中…" to MaterialTheme.colorScheme.secondaryContainer
        NoteStatus.TRANSCRIBING -> "转写中…" to MaterialTheme.colorScheme.secondaryContainer
        NoteStatus.DRAFT -> "待生成" to MaterialTheme.colorScheme.tertiaryContainer
        NoteStatus.ERROR -> "出错" to MaterialTheme.colorScheme.errorContainer
        else -> status to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

class HomeViewModel : ViewModel() {
    private val db = App.get().database
    val query = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)

    val notes = combine(db.noteDao().observeAll(), query) { list, q ->
        if (q.isBlank()) list
        else list.filter { it.title.contains(q, true) || (it.content?.contains(q, true) ?: false) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(note: NoteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            note.audioPath?.let { path ->
                try {
                    File(path).delete()
                } catch (_: Exception) {
                }
            }
            db.noteDao().delete(note)
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
                message.value = "导入成功！打开这条笔记就会开始转写并生成导图"
            } catch (e: Exception) {
                message.value = "导入失败：${e.message}"
            }
        }
    }
}
