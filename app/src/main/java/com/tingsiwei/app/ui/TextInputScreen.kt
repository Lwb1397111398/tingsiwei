package com.tingsiwei.app.ui

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tingsiwei.app.App
import com.tingsiwei.app.data.db.NoteEntity
import com.tingsiwei.app.data.db.NoteSource
import com.tingsiwei.app.data.db.NoteStatus
import com.tingsiwei.app.mindmap.TreeText
import com.tingsiwei.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextInputScreen(onSaved: (Long) -> Unit, onBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save(asMap: Boolean) {
        error = null
        if (content.isBlank()) {
            error = if (asMap) "请先粘贴导图文本" else "请先输入内容"
            return
        }
        if (submitting) return
        submitting = true
        scope.launch(Dispatchers.IO) {
            try {
                val forest = if (asMap) TreeText.parse(content) else null
                if (asMap && (forest == null || forest.isEmpty())) {
                    withContext(Dispatchers.Main) {
                        error = "没有解析到有效内容（需要 TAB 缩进层级）"
                        submitting = false
                    }
                    return@launch
                }
                val t = title.ifBlank {
                    if (forest != null) forest.first().topic.take(30)
                    else content.trim().lines().firstOrNull()?.take(20) ?: "文字笔记"
                }
                val entity = if (forest != null) {
                    val (mapJson, virtualRoot) = TreeText.toMapData(forest, t)
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
                } else {
                    NoteEntity(
                        title = t,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        status = NoteStatus.DRAFT,
                        source = NoteSource.TEXT,
                        content = content,
                    )
                }
                val id = App.get().database.noteDao().insert(entity)
                withContext(Dispatchers.Main) { onSaved(id) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = "保存失败：${e.message}"
                    submitting = false
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("新笔记", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = Spacing.screen),
        ) {
            // 标题：无边框，用大字号直接写，像在纸上起标题
            TitleField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Box(Modifier.padding(Spacing.lg)) {
                    if (content.isEmpty()) {
                        Column {
                            Text(
                                "粘贴学习内容，或粘贴 TAB 缩进的思维导图",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.md))
                            // 空状态给一个真实可用的样例，用户一眼知道「TAB 缩进」长什么样
                            SampleTree()
                        }
                    }
                    BasicTextField(
                        value = content,
                        onValueChange = {
                            content = it
                            if (error != null) error = null
                        },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = TextStyle(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // 两个动作并排，主按钮说明它会做什么
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { save(asMap = false) },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("生成导图 + 思路")
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                OutlinedButton(
                    onClick = { save(asMap = true) },
                    enabled = !submitting,
                    modifier = Modifier.height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Filled.AccountTree, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("直接导入导图")
                }
            }

            Spacer(Modifier.height(Spacing.md))
            Text(
                "「生成导图 + 思路」会让 AI 通读内容，产出可拖拽的导图和一段记忆思路；" +
                    "「直接导入导图」则按 TAB 缩进层级原样变成导图，不调用 AI、不花额度，适合粘贴你已整理好的大纲。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

/** 无边框标题输入：占位文案即说明「可以留空」。 */
@Composable
private fun TitleField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = Spacing.md)) {
        if (value.isEmpty()) {
            Text(
                "标题（留空则自动取开头）",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 样例导图：告诉用户 TAB 缩进是什么样，点一下可以直接用它试。 */
@Composable
private fun SampleTree() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Edit,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "导图长这样（每级一个 TAB）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "正当防卫\n\t成立条件\n\t\t不法侵害正在进行\n\t\t防卫意图\n\t法律后果\n\t\t不负刑事责任",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
