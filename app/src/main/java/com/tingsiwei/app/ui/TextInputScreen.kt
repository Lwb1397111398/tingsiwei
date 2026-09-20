package com.tingsiwei.app.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tingsiwei.app.App
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteSource
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.mindmap.TreeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextInputScreen(onSaved: (Long) -> Unit, onBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("输入文字 / 粘贴导图") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题（可留空）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("粘贴学习内容，或粘贴 TAB 缩进的思维导图") },
                modifier = Modifier.fillMaxWidth().height(320.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row {
                Button(
                    onClick = {
                        error = null
                        if (content.isBlank()) {
                            error = "请先输入内容"
                            return@Button
                        }
                        if (submitting) return@Button
                        submitting = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val t = title.ifBlank {
                                    content.trim().lines().firstOrNull()?.take(20) ?: "文字笔记"
                                }
                                val id = App.get().database.noteDao().insert(
                                    NoteEntity(
                                        title = t,
                                        createdAt = System.currentTimeMillis(),
                                        updatedAt = System.currentTimeMillis(),
                                        status = NoteStatus.DRAFT,
                                        source = NoteSource.TEXT,
                                        content = content,
                                    )
                                )
                                kotlinx.coroutines.withContext(Dispatchers.Main) { onSaved(id) }
                            } catch (e: Exception) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    error = "保存失败：${e.message}"
                                    submitting = false
                                }
                            }
                        }
                    },
                    enabled = !submitting,
                ) { Text(if (submitting) "保存中…" else "生成导图 + 思路") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = {
                        error = null
                        if (content.isBlank()) {
                            error = "请先粘贴导图文本"
                            return@OutlinedButton
                        }
                        val forest = TreeText.parse(content)
                        if (forest.isEmpty()) {
                            error = "没有解析到有效内容"
                            return@OutlinedButton
                        }
                        if (submitting) return@OutlinedButton
                        submitting = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val t = title.ifBlank { forest.first().topic.take(30) }
                                val (mapJson, virtualRoot) = TreeText.toMapData(forest, t)
                                val id = App.get().database.noteDao().insert(
                                    NoteEntity(
                                        title = t,
                                        createdAt = System.currentTimeMillis(),
                                        updatedAt = System.currentTimeMillis(),
                                        status = NoteStatus.READY,
                                        source = NoteSource.TEXT,
                                        content = content,
                                        mapJson = mapJson,
                                        virtualRoot = virtualRoot,
                                    )
                                )
                                kotlinx.coroutines.withContext(Dispatchers.Main) { onSaved(id) }
                            } catch (e: Exception) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    error = "保存失败：${e.message}"
                                    submitting = false
                                }
                            }
                        }
                    },
                    enabled = !submitting,
                ) { Text("导入为导图") }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "说明：「导入为导图」会把文本按 TAB 缩进层级直接变成可拖拽的导图（例如你在电脑上整理的导图），之后可以在导图页让 AI 修改拓展。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
