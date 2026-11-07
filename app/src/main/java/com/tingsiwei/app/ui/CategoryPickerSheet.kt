package com.tingsiwei.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.tingsiwei.app.App
import com.tingsiwei.app.data.db.CategoryEntity
import com.tingsiwei.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 分类选择器（BottomSheet）：录音页 / 详情页 / 首页长按归类三处共用。
 * 列出全部分类 + 「未分类」选项，底部可顺手新建一个分类（建完直接选中）。
 * 自足读取分类列表与建库，调用方只需处理 [onPick]（null = 移出分类）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    current: Long?,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    val categories by App.get().database.categoryDao().observeAll()
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = Spacing.screen, end = Spacing.screen, bottom = 28.dp)) {
            Text(
                "归入哪个分类？",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(Spacing.md))

            PickerRow(label = "未分类", highlight = current == null) { onPick(null) }
            categories.forEach { c ->
                PickerRow(label = c.name, highlight = current == c.id, colorId = c.id) { onPick(c.id) }
            }

            Spacer(Modifier.height(Spacing.md))
            if (!creating) {
                TextButton(onClick = { creating = true; nameError = null }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建分类")
                }
            } else {
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
                    // 确认建库并直接选中：sortOrder 排到末尾
                    IconButton(
                        onClick = {
                            val name = newName.trim()
                            when {
                                name.isEmpty() -> nameError = "先起个名字"
                                categories.any { it.name == name } -> nameError = "已有同名分类"
                                else -> scope.launch(Dispatchers.IO) {
                                    val dao = App.get().database.categoryDao()
                                    val id = dao.insert(
                                        CategoryEntity(
                                            name = name,
                                            sortOrder = (dao.maxSortOrder() ?: 0) + 1,
                                            createdAt = System.currentTimeMillis(),
                                        )
                                    )
                                    onPick(id)
                                }
                            }
                        },
                    ) { Icon(Icons.Filled.Check, "确定", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { creating = false; newName = ""; nameError = null }) {
                        Icon(Icons.Filled.Close, "取消", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                nameError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 一行可选分类：色点 + 名字，选中态加对勾 */
@Composable
private fun PickerRow(
    label: String,
    highlight: Boolean,
    colorId: Long? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (highlight) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (colorId != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(CategoryUi.color(colorId)),
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            if (highlight) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
