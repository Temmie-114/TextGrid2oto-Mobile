package com.tg2oto.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg2oto.mobile.R
import com.tg2oto.mobile.core.Backend
import com.tg2oto.mobile.core.SocInfo
import com.tg2oto.mobile.core.ModelEntry
import com.tg2oto.mobile.ui.AppViewModel
import com.tg2oto.mobile.ui.components.FileField
import com.tg2oto.mobile.ui.components.DropdownField
import com.tg2oto.mobile.ui.components.FolderField
import com.tg2oto.mobile.ui.components.RunBar
import com.tg2oto.mobile.ui.components.ScreenColumn
import com.tg2oto.mobile.ui.components.SectionCard
import com.tg2oto.mobile.ui.components.SwitchRow
import com.tg2oto.mobile.ui.components.TextFieldSimple
import com.tg2oto.mobile.util.rememberFolderPicker
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.tg2oto.mobile.ui.i18n.L

private fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun ModelRow(model: ModelEntry, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${model.type.uppercase()} · ${humanSize(model.sizeBytes)}" +
                    if (model.builtIn) L.s(" · 内置") else L.s(" · 导入"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_ms_delete), L.s("删除"), Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    description: String,
    running: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) L.s("收起") else L.s("展开"), fontSize = 12.sp)
            }
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
                RunBar(
                    running = running,
                    runLabel = L.s("执行"),
                    enabled = enabled,
                    onRun = onRun,
                    onCancel = onCancel
                )
            }
        }
    }
}

@Composable
fun ToolsScreen(vm: AppViewModel, onBack: () -> Unit = {}) {
    val running by vm.running.collectAsState()
    val install by vm.installProgress.collectAsState()
    val dicts by vm.dicts.collectAsState()
    val settings by vm.settings.collectAsState()
    val models by vm.models.collectAsState()
    val backendReport by vm.backendReport.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // add_ph_num
    var phCsv by remember { mutableStateOf("") }
    var phDict by remember { mutableStateOf("") }
    // csv2tg
    var c2tCsv by remember { mutableStateOf("") }
    var c2tOut by remember { mutableStateOf("") }
    var c2tDict by remember { mutableStateOf("") }
    // words2lab
    var w2lTg by remember { mutableStateOf("") }
    var w2lOut by remember { mutableStateOf("") }
    // loudness
    var loudDir by remember { mutableStateOf("") }
    var loudThreshold by remember { mutableStateOf("-40") }
    // del sp
    var delDir by remember { mutableStateOf("") }
    var delPhones by remember { mutableStateOf("AP,SP,EP") }
    // dataset
    var dsWavs by remember { mutableStateOf("") }
    var dsTg by remember { mutableStateOf("") }
    var dsOut by remember { mutableStateOf("") }
    var dsRecursive by remember { mutableStateOf(false) }
    // GAME MIDI 标注
    var gameCsv by remember { mutableStateOf("") }
    var gameModelName by remember { mutableStateOf("") }
    var gameLang by remember { mutableStateOf("") }
    var gameChunk by remember { mutableStateOf("30") }
    var gameWorkers by remember { mutableStateOf("1") }
    var gameOverwrite by remember { mutableStateOf(true) }
    val gameModels = (models).filter { it.type == "game" }

    val dirPicker = rememberFolderPicker { }
    var target by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val picker = rememberFolderPicker { path -> target?.invoke(path) }

    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 抽屉式的全屏面板：退出用左上角的小叉，不用返回箭头
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_ms_close),
                    contentDescription = L.s("关闭"),
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(L.s("小工具"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            L.s("DiffSinger 数据集与格式转换辅助工具。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ToolCard(
            title = L.s("添加 ph_num"),
            description = L.s("为 transcriptions.csv 补 ph_num 列"),
            running = running,
            enabled = phCsv.isNotBlank(),
            onRun = {
                vm.runJob(
                    "phnum",
                    JSONObject().apply {
                        put("csv", phCsv)
                        if (phDict.isNotBlank()) put("dictionary", phDict)
                    },
                    L.s("添加 ph_num")
                )
            },
            onCancel = vm::cancelJob
        ) {
            FileField("transcriptions.csv", phCsv, { phCsv = it }) { }
            TextFieldSimple(L.s("两段式字典（可选）"), phDict, { phDict = it })
        }

        ToolCard(
            title = "CSV → TextGrid",
            description = L.s("把 transcriptions.csv 转回 TextGrid"),
            running = running,
            enabled = c2tCsv.isNotBlank(),
            onRun = {
                vm.runJob(
                    "csv2tg",
                    JSONObject().apply {
                        put("csv", c2tCsv)
                        if (c2tOut.isNotBlank()) put("out_dir", c2tOut)
                        if (c2tDict.isNotBlank()) put("dictionary", c2tDict)
                    },
                    "CSV → TextGrid"
                )
            },
            onCancel = vm::cancelJob
        ) {
            FileField("transcriptions.csv", c2tCsv, { c2tCsv = it }) { }
            FolderField(L.s("输出目录（可空）"), c2tOut, { c2tOut = it }, onPick = {
                target = { c2tOut = it }
                picker.launch(null)
            })
            TextFieldSimple(L.s("字典（可选）"), c2tDict, { c2tDict = it })
        }

        ToolCard(
            title = "TextGrid words → lab",
            description = L.s("用 words 层重建 lab 文件"),
            running = running,
            enabled = w2lTg.isNotBlank(),
            onRun = {
                vm.runJob(
                    "words2lab",
                    JSONObject().apply {
                        put("tg_dir", w2lTg)
                        if (w2lOut.isNotBlank()) put("out_dir", w2lOut)
                    },
                    "words → lab"
                )
            },
            onCancel = vm::cancelJob
        ) {
            FolderField(L.s("TextGrid 目录"), w2lTg, { w2lTg = it }, onPick = {
                target = { w2lTg = it }
                picker.launch(null)
            })
            FolderField(L.s("输出目录（可空）"), w2lOut, { w2lOut = it }, onPick = {
                target = { w2lOut = it }
                picker.launch(null)
            })
        }

        ToolCard(
            title = L.s("音量检测"),
            description = L.s("找出空白 / 音量过低的录音"),
            running = running,
            enabled = loudDir.isNotBlank(),
            onRun = {
                vm.runJob(
                    "loudness",
                    JSONObject().apply {
                        put("wav_dir", loudDir)
                        put("threshold_db", loudThreshold.toFloatOrNull() ?: -40f)
                    },
                    L.s("音量检测")
                )
            },
            onCancel = vm::cancelJob
        ) {
            FolderField(L.s("录音目录"), loudDir, { loudDir = it }, onPick = {
                target = { loudDir = it }
                picker.launch(null)
            })
            TextFieldSimple(L.s("RMS 阈值 (dBFS)"), loudThreshold, { loudThreshold = it })
        }

        ToolCard(
            title = L.s("删除 SP / AP"),
            description = L.s("清理 TextGrid 里的静音与呼吸段"),
            running = running,
            enabled = delDir.isNotBlank(),
            onRun = {
                vm.runJob(
                    "delsp",
                    JSONObject().apply {
                        put("folder", delDir)
                        put("phones", delPhones)
                    },
                    L.s("删除 SP")
                )
            },
            onCancel = vm::cancelJob
        ) {
            FolderField(L.s("TextGrid 目录"), delDir, { delDir = it }, onPick = {
                target = { delDir = it }
                picker.launch(null)
            })
            TextFieldSimple(L.s("要删除的音素"), delPhones, { delPhones = it })
        }

        ToolCard(
            title = L.s("生成 DiffSinger 数据集"),
            description = L.s("整理 wav + TextGrid 成 transcriptions.csv"),
            running = running,
            enabled = dsWavs.isNotBlank() && dsOut.isNotBlank(),
            onRun = {
                vm.runJob(
                    "dataset",
                    JSONObject().apply {
                        put("wavs_dir", dsWavs)
                        if (dsTg.isNotBlank()) put("tg_dir", dsTg)
                        put("dataset_dir", dsOut)
                        put("recursive", dsRecursive)
                    },
                    L.s("生成数据集")
                )
            },
            onCancel = vm::cancelJob
        ) {
            FolderField(L.s("切片 wav 目录"), dsWavs, { dsWavs = it }, onPick = {
                target = { dsWavs = it }
                picker.launch(null)
            })
            FolderField(L.s("TextGrid 目录（可空）"), dsTg, { dsTg = it }, onPick = {
                target = { dsTg = it }
                picker.launch(null)
            })
            FolderField(L.s("输出数据集目录"), dsOut, { dsOut = it }, onPick = {
                target = { dsOut = it }
                picker.launch(null)
            })
            SwitchRow(L.s("递归子文件夹"), null, dsRecursive) { dsRecursive = it }
        }

        ToolCard(
            title = "GAME MIDI",
            description = L.s("为 transcriptions.csv 生成音符标注 note_seq / note_dur / note_slur"),
            running = running,
            enabled = gameCsv.isNotBlank() && gameModels.isNotEmpty(),
            onRun = {
                val entry = gameModels.firstOrNull { it.name == gameModelName }
                    ?: gameModels.firstOrNull()
                if (entry != null) {
                    vm.runJob(
                        "game",
                        JSONObject().apply {
                            put("csv", gameCsv)
                            put("model_dir", entry.dir.absolutePath)
                            if (gameLang.isNotBlank()) put("language", gameLang)
                            put("max_chunk_sec", gameChunk.toFloatOrNull() ?: 30f)
                            put("workers", gameWorkers.toIntOrNull() ?: 1)
                            put("overwrite", gameOverwrite)
                        },
                        "GAME MIDI"
                    )
                }
            },
            onCancel = vm::cancelJob
        ) {
            FileField("transcriptions.csv", gameCsv, { gameCsv = it }) { }
            if (gameModels.isEmpty()) {
                Text(
                    L.s("还没有导入 GAME 模型：到「模型」页导入含 encoder / segmenter / estimator / dur2bd / bd2dur / config.json 的模型目录"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                DropdownField(
                    label = L.s("GAME 模型"),
                    value = gameModelName.ifBlank { gameModels.first().name },
                    options = gameModels.map { it.name },
                    onSelect = { gameModelName = it }
                )
                DropdownField(
                    label = L.s("语言"),
                    value = gameLang.ifBlank { L.s("自动") },
                    options = listOf(L.s("自动"), "en", "ja", "yue", "zh"),
                    onSelect = { gameLang = if (it == L.s("自动")) "" else it }
                )
                TextFieldSimple(
                    L.s("最大分块（秒）"),
                    gameChunk,
                    { gameChunk = it },
                    L.s("长音频按词边界切开推理，显存/内存不足时降到 8~16")
                )
                TextFieldSimple(L.s("并行条数"), gameWorkers, { gameWorkers = it }, L.s("1 = 串行"))
                SwitchRow(L.s("覆盖已有标注"), L.s("覆盖 CSV 里已有的 note 列"), gameOverwrite) {
                    gameOverwrite = it
                }
            }
        }

        Spacer(Modifier.height(70.dp))
    }
}
