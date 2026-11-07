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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingsiwei.app.App
import com.tingsiwei.app.llm.CategoryMergeFlow
import com.tingsiwei.app.mindmap.CategoryMerger
import com.tingsiwei.app.ui.components.EmptyState
import com.tingsiwei.app.ui.components.MindMapPanel
import com.tingsiwei.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分类总导图页：把该分类下所有已生成导图按时间正序拼接成一幅完整导图（纯本地拼接、零 AI 调用），
 * 浏览为只读画布；想更精炼可点「AI 优化整合」，产出固化为一篇 source=MERGED 的笔记并跳转详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryMapScreen(
    categoryId: Long,
    onOpenNote: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val db = App.get().database
    val scope = rememberCoroutineScope()

    // 分类名：observe 保证改名后同步
    val categories by db.categoryDao().observeAll().collectAsState(initial = emptyList())
    val categoryName = categories.firstOrNull { it.id == categoryId }?.name ?: "总导图"

    var mapJson by remember { mutableStateOf<String?>(null) }
    var noteCount by remember { mutableIntStateOf(0) }
    var mergedNoteId by remember { mutableStateOf<Long?>(null) }

    // 现拼 + 订阅：该分类里有笔记完成生成时自动重拼（新课录完自动进总导图）；
    // key 带上分类名——首次 collect 时分类列表可能还没加载完（initial emptyList），
    // 名字到位后重拼一次，避免根节点回退成兜底名「总导图」
    LaunchedEffect(categoryId, categoryName) {
        db.noteDao().observeReadyWithMapInCategory(categoryId)
            .flowOn(Dispatchers.IO)
            .collect { notes ->
                val result = CategoryMerger.merge(notes, categoryName)
                mapJson = result?.mapJson
                noteCount = result?.noteCount ?: 0
            }
    }
    LaunchedEffect(categoryId) {
        withContext(Dispatchers.IO) {
            mergedNoteId = db.noteDao().mergedNoteInCategory(categoryId)?.id
        }
    }

    // AI 优化整合状态
    var merging by remember { mutableStateOf(false) }
    var stageText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "「$categoryName」总导图",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "$noteCount 篇导图按时间拼接",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            // 底部操作条：AI 优化整合（手动触发，不参与任何自动流程）
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md)) {
                    if (merging) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.md))
                            Text(
                                stageText.ifBlank { "整合中…" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                merging = true
                                errorMsg = null
                                scope.launch {
                                    try {
                                        val id = CategoryMergeFlow(App.get().database, App.get().settings)
                                            .run(categoryId) { stageText = it }
                                        mergedNoteId = id
                                        merging = false
                                        onOpenNote(id)
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        errorMsg = e.message ?: "整合失败，请重试"
                                        merging = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            enabled = noteCount > 0,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.Filled.AccountTree, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (mergedNoteId != null) "重新 AI 优化整合" else "AI 优化整合成精炼版")
                        }
                        errorMsg?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "拼接导图零 AI 调用、内容零丢失；「AI 优化整合」会把各篇大纲融合去重，" +
                                "产出一篇可编辑的笔记（旧版可在版本历史找回）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val json = mapJson
            when {
                json == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Outlined.AutoStories,
                        title = "还没有可拼接的导图",
                        description = "先把分类里的笔记生成导图，\n这里就会自动按时间拼成一幅完整导图。",
                    )
                }
                else -> Column {
                    if (noteCount > 50) {
                        Text(
                            "这个分类有 $noteCount 篇导图，拼接后的图会很大，建议用下方的「AI 优化整合」生成精炼版。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                        )
                    }
                    MindMapPanel(
                        mapJson = json,
                        editable = false,
                        onMapChanged = {},
                        onController = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
