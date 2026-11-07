package com.tingsiwei.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.tingsiwei.app.App
import com.tingsiwei.app.data.db.CategoryEntity
import com.tingsiwei.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 分类管理页：新建 / 改名 / 上移下移 / 删除。
 * 删除的安全语义：分类里的普通笔记全部回到「未分类」（笔记和录音绝不删），
 * 该分类的 AI 整合总导图笔记随分类一起删——删除走一个事务，三步善后。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManageScreen(onBack: () -> Unit) {
    val db = App.get().database
    val scope = rememberCoroutineScope()
    val categories by db.categoryDao().observeAll().collectAsState(initial = emptyList())

    var newName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("管理分类", style = MaterialTheme.typography.titleMedium) },
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
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = Spacing.screen),
        ) {
            // 新建行：输入名字 + 添加
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.weight(1f),
                ) {
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it; nameError = null },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                IconButton(
                    onClick = {
                        val name = newName.trim()
                        when {
                            name.isEmpty() -> nameError = "先起个名字"
                            categories.any { it.name == name } -> nameError = "已有同名分类"
                            else -> scope.launch(Dispatchers.IO) {
                                db.categoryDao().insert(
                                    CategoryEntity(
                                        name = name,
                                        sortOrder = (db.categoryDao().maxSortOrder() ?: 0) + 1,
                                        createdAt = System.currentTimeMillis(),
                                    )
                                )
                                newName = ""
                            }
                        }
                    },
                ) { Icon(Icons.Filled.Add, "新建分类", tint = MaterialTheme.colorScheme.primary) }
            }
            nameError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "分类就是你自己起的名字（课程、当事人、项目都行）。删除分类不会删笔记，只会把它们移到「未分类」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(categories, key = { it.id }) { c ->
                    CategoryManageRow(
                        category = c,
                        canUp = c.id != categories.first().id,
                        canDown = c.id != categories.last().id,
                        onUp = { scope.launch(Dispatchers.IO) { move(db, c, -1) } },
                        onDown = { scope.launch(Dispatchers.IO) { move(db, c, +1) } },
                        onRename = { renameTarget = c; renameText = c.name },
                        onDelete = { deleteTarget = c },
                    )
                }
            }
        }
    }

    // 改名对话框
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名分类") },
            text = {
                BasicTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameText.trim()
                        if (name.isNotEmpty() && categories.none { it.name == name && it.id != target.id }) {
                            scope.launch(Dispatchers.IO) {
                                db.categoryDao().update(target.copy(name = name))
                            }
                            renameTarget = null
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    // 删除确认：人话讲清后果
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除分类「${target.name}」？") },
            text = {
                Text(
                    "里面的笔记会全部移到「未分类」，笔记和录音都不会删；" +
                        "这个分类的「AI 优化总导图」笔记会一起删除。分类本身删除后不可恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            db.withTransaction {
                                db.noteDao().deleteMergedInCategory(target.id)
                                db.noteDao().detachNotesInCategory(target.id)
                                db.categoryDao().delete(target)
                            }
                        }
                        deleteTarget = null
                    },
                ) { Text("删除分类", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 管理列表的一行：色点 + 名字 + 上移/下移/改名/删除 */
@Composable
private fun CategoryManageRow(
    category: CategoryEntity,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = Spacing.md, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(CategoryUi.color(category.id)),
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                category.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onUp, enabled = canUp) {
                Icon(
                    Icons.Filled.KeyboardArrowUp, "上移",
                    tint = if (canUp) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
            }
            IconButton(onClick = onDown, enabled = canDown) {
                Icon(
                    Icons.Filled.KeyboardArrowDown, "下移",
                    tint = if (canDown) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, "改名", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 上移/下移：与相邻分类交换 sortOrder（建分类时递增分配，不会重复） */
private suspend fun move(db: com.tingsiwei.app.data.db.AppDatabase, c: CategoryEntity, delta: Int) {
    val all = db.categoryDao().observeAll().first()
    val idx = all.indexOfFirst { it.id == c.id }
    val j = idx + delta
    if (idx < 0 || j < 0 || j >= all.size) return
    val other = all[j]
    db.withTransaction {
        db.categoryDao().update(c.copy(sortOrder = other.sortOrder))
        db.categoryDao().update(other.copy(sortOrder = c.sortOrder))
    }
}
