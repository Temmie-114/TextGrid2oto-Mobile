package com.tg2oto.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.unit.sp
import com.tg2oto.mobile.R
import com.tg2oto.mobile.ui.i18n.L

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
fun FolderField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPick: () -> Unit,
    placeholder: String = "/storage/emulated/0/…"
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, fontSize = 12.sp) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onPick) {
                Icon(painterResource(R.drawable.ic_ms_folder_open), contentDescription = L.s("选择文件夹"))
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
fun FileField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPick: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onPick) {
                Icon(painterResource(R.drawable.ic_ms_upload), contentDescription = L.s("选择文件"))
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label, fontSize = 12.sp, maxLines = 1) }
            )
        }
    }
}

@Composable
fun RunBar(
    running: Boolean,
    runLabel: String,
    enabled: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onRun,
            enabled = enabled && !running,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(painterResource(R.drawable.ic_ms_play_arrow), null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(runLabel)
        }
        OutlinedButton(
            onClick = onCancel,
            enabled = running,
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(painterResource(R.drawable.ic_ms_stop_circle), null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(L.s("停止"))
        }
    }
}

/** 进度条：放在日志栏正上方。 */
@Composable
 fun ProgressStrip(
    runningFlow: StateFlow<Boolean>,
    progressFlow: StateFlow<Float>,
    statusFlow: StateFlow<String>,
    modifier: Modifier = Modifier
) {
    // 在组件内部订阅，日志/进度高频变化时只重组这里，不牵动整页
    val running by runningFlow.collectAsState()
    val progress by progressFlow.collectAsState()
    val status by statusFlow.collectAsState()
    val visible = running || progress > 0f
    AnimatedVisibility(visible = visible) {
        Column(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(
                    if (progress < 0f) "" else "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            if (progress < 0f) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 日志输出栏：与桌面版 print 内容一致，自动滚到底。 */
@Composable
fun LogPanel(
    logsFlow: StateFlow<List<String>>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val maxPanel = (LocalConfiguration.current.screenHeightDp * 0.5f).coerceAtLeast(220f)
    var panelHeight by remember { mutableFloatStateOf(180f) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val logs by logsFlow.collectAsState()
    // 用 Column + verticalScroll 而不是 LazyColumn：虚拟化列表里长按选择
    // 只能选中单行、跨行拖动会失效，这里要的是可以真的长按划选复制。
    val scrollState = rememberScrollState()
    LaunchedEffect(logs.size, expanded) {
        if (expanded && logs.isNotEmpty()) {
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 抽屉式：往上下拖标题栏改变日志区高度，收起到最小再松手就折叠
                .pointerInput(expanded) {
                    detectDragGestures(
                        onDragStart = { dragAccum = 0f },
                        onDrag = { change, drag ->
                            change.consume()
                            if (!expanded) {
                                dragAccum += drag.y
                                if (dragAccum < -50f) {
                                    onToggle()
                                    dragAccum = 0f
                                }
                            } else {
                                val next = (panelHeight - drag.y / density.density)
                                    .coerceIn(64f, maxPanel)
                                if (next <= 70f) {
                                    onToggle()
                                    panelHeight = 180f
                                } else {
                                    panelHeight = next
                                }
                            }
                        }
                    )
                }
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(R.drawable.ic_ms_terminal),
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                L.s("运行日志"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            val clipboard = LocalClipboardManager.current
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(logs.joinToString("\n")))
            }) { Text(L.s("复制"), fontSize = 12.sp) }
            TextButton(onClick = onClear) { Text(L.s("清空"), fontSize = 12.sp) }
            IconButton(onClick = onToggle) {
                Icon(
                    painterResource(
                        if (expanded) R.drawable.ic_ms_expand_more else R.drawable.ic_ms_expand_less
                    ),
                    contentDescription = if (expanded) L.s("收起日志") else L.s("展开日志"),
                    Modifier.size(20.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (expanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(panelHeight.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        L.s("尚无输出。执行任务后这里会实时显示与桌面版一致的进度信息。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SelectionContainer {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                        ) {
                            logs.forEach { line ->
                                Text(
                                    line,
                                    // 撑满整行，右侧空白也能长按起选
                                    modifier = Modifier.fillMaxWidth(),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = logColor(line)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun logColor(line: String): Color = when {
    line.contains("✘") || line.contains("Error") || line.contains("Traceback") ->
        MaterialTheme.colorScheme.error
    line.contains("✔") || line.contains(L.s("完成")) ->
        MaterialTheme.colorScheme.primary
    line.contains("⚠") -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun TextFieldSimple(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder, fontSize = 12.sp) }
        } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    )
}

/** 固定选项的下拉框，用于编码、精度这类枚举型参数。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}
