package com.tingsiwei.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tingsiwei.app.ui.theme.Spacing

/**
 * 设置页的零件。
 *
 * 设置页有 20 多个控件，如果每个都直接用 Material 默认样式，会出现「有的输入框小一号、
 * 有的开关颜色跟主题对不上」这类细碎不一致。这里统一成三个：SettingsField 输入框、
 * ToggleRow 开关行、ChoiceRow 单选行、ChipPicker 可点选标签组。
 */

/** 统一输入框：圆角容器底、无描边，聚焦时描边才出现（安静，但聚焦清楚）。 */
@Composable
fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    obscure: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodySmall,
        shape = MaterialTheme.shapes.small,
        visualTransformation = if (obscure) PasswordVisualTransformation()
        else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    )
}

/** 开关行：左边标题 + 说明，右边开关。整行可点，不用精确戳中开关。 */
@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/**
 * 单选行：用一个自绘小圆点代替 RadioButton。
 * Material 的 RadioButton 直径 20dp、离文字较远，一列四五项时显得松散；这里收成 18dp 并贴紧文字。
 * 选中态切换全部带动画：底色渐变、圆点变色、内芯缩放弹出——不用生硬的「选中瞬间换色」。
 */
@Composable
fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else Color.Transparent,
        animationSpec = tween(200),
        label = "choiceBg",
    )
    val dotColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(200),
        label = "choiceDot",
    )
    val innerScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "choiceInner",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 10.dp),
            verticalAlignment = if (description == null) Alignment.CenterVertically else Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = if (description == null) 0.dp else 3.dp)
                    .size(18.dp)
                    .background(dotColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .graphicsLayer {
                            scaleX = innerScale
                            scaleY = innerScale
                        }
                        .background(MaterialTheme.colorScheme.onPrimary, CircleShape),
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 可点选标签组（模型名、音色名这类不定长选项）。传 onDelete 时每个标签尾部带 ✕ 删除钮。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipPicker(
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: ((String) -> Unit)? = null,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selectedValue == value,
                onClick = { onSelect(value) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                        if (onDelete != null) {
                            // 内层 clickable 消费掉点按，不会误触发标签本身的 onSelect
                            Box(
                                Modifier
                                    .padding(start = 5.dp)
                                    .size(16.dp)
                                    .clickable { onDelete(value) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "删除 $label",
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedValue == value,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

/** 一行「标签 + 值」的只读信息行，例如「当前版本 v1.5.84」。 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 小加载行：图标 + 文字，统一设置页所有「请求中…」的呈现。 */
@Composable
fun BusyLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 设置项里的一段说明文字，统一颜色与行距。 */
@Composable
fun SettingHint(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 小节内的细分割线，用来分隔同组里的不同子项。 */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}
