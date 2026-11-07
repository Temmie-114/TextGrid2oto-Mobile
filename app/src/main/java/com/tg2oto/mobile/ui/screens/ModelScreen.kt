package com.tg2oto.mobile.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg2oto.mobile.R
import com.tg2oto.mobile.core.ModelEntry
import com.tg2oto.mobile.ui.AppViewModel
import com.tg2oto.mobile.ui.components.ScreenColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.tg2oto.mobile.ui.i18n.L

private fun human(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun typeLabel(type: String): String = when (type) {
    "hubertfa" -> L.s("HubertFA 强制对齐")
    "tifa" -> L.s("TIFA 音素对齐")
    "game" -> "GAME"
    else -> type
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModelScreen(vm: AppViewModel, onBack: () -> Unit) {
    val models by vm.models.collectAsState()
    val install by vm.installProgress.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<ModelEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<ModelEntry?>(null) }
    var importing by remember { mutableStateOf(false) }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "model_import.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                    }
                    val list = vm.importModel(tmp)
                    tmp.delete()
                    list
                }
            }
            importing = false
            ok.onSuccess {
                vm.appendLog("✔ 已导入 ${it.size} 个模型：" + it.joinToString { m -> "${m.name}(${m.precision})" })
                val first = it.firstOrNull()
                if (first != null) {
                    scope.launch {
                        vm.settingsStore().setModelId(first.id)
                    }
                }
            }.onFailure {
                vm.appendLog("✘ 导入失败：${it.message}")
                Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_ms_chevron_right),
                    contentDescription = L.s("返回"),
                    modifier = Modifier.rotate(180f).size(20.dp)
                )
            }
            Column {
                Text(L.s("模型管理"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    L.s("每个模型一个文件夹，存放在应用私有目录；长按可删除"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (install != null) {
            val pct = install ?: 0f
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(L.s("正在释放内置模型"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (models.isEmpty() && install == null) {
            Text(
                L.s("还没有模型。可以释放内置的 HubertFA / TIFA，或导入自己的 zip。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        models.forEach { model ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { detail = model },
                            onLongClick = { pendingDelete = model }
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            model.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            typeLabel(model.type),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${model.precision} · ${human(model.sizeBytes)}" +
                                if (model.builtIn) L.s(" · 内置") else L.s(" · 导入"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        painterResource(R.drawable.ic_ms_chevron_right),
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !importing && install == null
            ) {
                Icon(painterResource(R.drawable.ic_ms_upload), null, Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (importing) L.s("导入中…") else L.s("导入 zip"), fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { vm.startModelInstall() },
                enabled = install == null && models.none { it.builtIn }
            ) {
                Icon(painterResource(R.drawable.ic_ms_download), null, Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(L.s("释放内置模型"), fontSize = 12.sp)
            }
        }

        Text(
            L.s("zip 里的每个模型文件夹会被整体解压到私有目录（filesDir/models/imported/<名字>），") +
                L.s("识别依据：model.onnx + vocab.json（HubertFA）、model.onnx + spectrogram.onnx / vocabulary.json（TIFA）、") +
                "encoder.onnx + segmenter.onnx（GAME）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            L.s("精度自动识别，fp32 / fp16 / int8 的 onnx 都能直接跑；fp16 与 int8 请使用转换脚本产物，App 内不做量化转换。"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(60.dp))
    }

    detail?.let { model ->
        val files = remember(model.id) {
            model.dir.listFiles()?.sortedBy { it.name }?.map { it.name to it.length() } ?: emptyList()
        }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(model.name) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(typeLabel(model.type), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    Text("精度 ${model.precision} · ${human(model.sizeBytes)}", fontSize = 12.sp)
                    Text(
                        model.dir.absolutePath,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    files.forEach { (name, size) ->
                        Text("$name  ${human(size)}", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(L.s("关闭")) } },
            dismissButton = {
                TextButton(onClick = {
                    detail = null
                    pendingDelete = model
                }) { Text(L.s("删除"), color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(L.s("删除模型？")) },
            text = {
                Column {
                    Text(model.name)
                    Text(
                        "会连同文件夹一起删除（${human(model.sizeBytes)}），无法恢复。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteModel(model)
                    pendingDelete = null
                }) { Text(L.s("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(L.s("取消")) }
            }
        )
    }
}
