package com.tingsiwei.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingsiwei.app.diagram.Diagram
import com.tingsiwei.app.diagram.DiagramType
import com.tingsiwei.app.ui.theme.Spacing

/**
 * 解释性小图的原生渲染器（思路页分段卡片正文下方）。
 *
 * 全部用 Compose 布局组件而不是 Canvas：配色自动跟随明暗主题、字号跟随系统设置、
 * 长文字自动换行不溢出。图的类型由 LLM 按内容关系挑选（见 Prompts.diagramSystem），
 * 这里只负责把结构画对——结构层/渲染层分离，坏数据在 DiagramParser 已被拦下。
 */
@Composable
fun DiagramView(diagram: Diagram, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        // 图标签行：类型徽标 + 图标题，让「这是一张图」在正文流里一眼可辨
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    typeLabel(diagram.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            if (diagram.title.isNotBlank()) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    diagram.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        when (diagram.type) {
            DiagramType.FLOW -> FlowDiagram(diagram)
            DiagramType.TIMELINE -> TimelineDiagram(diagram)
            DiagramType.MATRIX -> MatrixDiagram(diagram)
            DiagramType.HIERARCHY -> HierarchyDiagram(diagram)
            DiagramType.DECISION -> DecisionDiagram(diagram)
            else -> {} // 未知类型不渲染（DiagramParser 已拦，这里再兜一道）
        }
    }
}

private fun typeLabel(type: String): String = when (type) {
    DiagramType.FLOW -> "流程"
    DiagramType.TIMELINE -> "时间线"
    DiagramType.MATRIX -> "对比"
    DiagramType.HIERARCHY -> "结构"
    DiagramType.DECISION -> "判断"
    else -> "图"
}

/** 图内容统一放在一块微色底板里，与正文文字区分开 */
@Composable
private fun DiagramBoard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            content = content,
        )
    }
}

/** 流程图：纵向步骤块，块间一个下箭头。步骤文字自动换行，长步骤也放得下 */
@Composable
private fun FlowDiagram(d: Diagram) {
    val steps = d.steps.filter { it.isNotBlank() }
    DiagramBoard {
        steps.forEachIndexed { i, step ->
            if (i > 0) ArrowDown()
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ArrowDown() {
    Text(
        "↓",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/**
 * 时间线：左侧竖轴 + 圆点，右侧「时间 · 事件」。竖排不用横滚——
 * 事件多了往下排就好，手机上竖向滚动才是自然的阅读方向。
 */
@Composable
private fun TimelineDiagram(d: Diagram) {
    val items = d.items.filter { it.text.isNotBlank() }
    DiagramBoard {
        items.forEachIndexed { i, item ->
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .width(10.dp)
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    if (i < items.lastIndex) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(22.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    if (item.t.isNotBlank()) {
                        Text(
                            item.t,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * 对比矩阵：列头（对比对象）+ 维度行。列宽固定 + 横向滚动——
 * 手机竖屏放不下 3 列以上的中文内容，硬挤只会出「每个字都竖排」的惨状。
 * 注意不能用 weight 分列宽：horizontalScroll 给 Row 无限主轴约束，weight 会算出 0 宽（实测踩过）。
 */
@Composable
private fun MatrixDiagram(d: Diagram) {
    val columns = d.columns.filter { it.isNotBlank() }
    val rows = d.rows.filter { it.label.isNotBlank() || it.cells.any { c -> c.isNotBlank() } }
    DiagramBoard {
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            // 角列固定 72dp，数据列固定 100dp：2 列正好满屏，3 列起横向滚动
            Row {
                MatrixCell("", width = 72.dp, header = true)
                columns.forEach { MatrixCell(it, width = 100.dp, header = true) }
            }
            rows.forEach { row ->
                Row {
                    MatrixCell(row.label, width = 72.dp)
                    val cells = row.cells.filter { it.isNotBlank() }
                    columns.forEachIndexed { ci, _ ->
                        MatrixCell(cells.getOrElse(ci) { "" }, width = 100.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatrixCell(text: String, width: androidx.compose.ui.unit.Dp, header: Boolean = false) {
    Surface(
        color = when {
            header -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.width(width),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            color = if (header) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
        )
    }
}

/** 层级图：总概念块居中，下面分支块各带一排子项 chips */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HierarchyDiagram(d: Diagram) {
    val children = d.children.filter { it.text.isNotBlank() }
    DiagramBoard {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                d.root,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 8.dp),
            )
        }
        ArrowDown()
        children.forEach { branch ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
                    Text(
                        branch.text,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (branch.children.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.xs))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            branch.children.filter { it.isNotBlank() }.forEach { leaf ->
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                ) {
                                    Text(
                                        leaf,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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

/** 决策图：顶部判断问题，下面「满足 / 不满足」两条路径并列 */
@Composable
private fun DecisionDiagram(d: Diagram) {
    val yes = d.yes.filter { it.isNotBlank() }
    val no = d.no.filter { it.isNotBlank() }
    DiagramBoard {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                d.question,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            DecisionBranch("✓ 满足", yes, primaryTone = true, Modifier.weight(1f))
            DecisionBranch("✗ 不满足", no, primaryTone = false, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DecisionBranch(label: String, steps: List<String>, primaryTone: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (primaryTone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        steps.forEachIndexed { i, step ->
            if (i > 0) Text(
                "↓",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 1.dp),
            )
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (primaryTone) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    step,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = 6.dp),
                )
            }
        }
    }
}
