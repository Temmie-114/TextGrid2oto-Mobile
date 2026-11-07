package com.tg2oto.mobile.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import com.tg2oto.mobile.R
import com.tg2oto.mobile.core.ModelEntry
import com.tg2oto.mobile.ui.AppViewModel
import com.tg2oto.mobile.ui.components.DictField
import com.tg2oto.mobile.ui.components.DropdownField
import com.tg2oto.mobile.ui.components.FolderField
import com.tg2oto.mobile.ui.components.RunBar
import com.tg2oto.mobile.ui.components.ScreenColumn
import com.tg2oto.mobile.ui.components.SectionCard
import com.tg2oto.mobile.ui.components.SegmentedRow
import com.tg2oto.mobile.ui.components.SwitchRow
import com.tg2oto.mobile.ui.components.TextFieldSimple
import com.tg2oto.mobile.util.rememberFolderPicker
import org.json.JSONObject
import com.tg2oto.mobile.ui.i18n.L

@Composable
fun ModelDropdown(
    models: List<ModelEntry>,
    selectedId: String,
    onSelect: (ModelEntry) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = models.firstOrNull { it.id == selectedId }
    OutlinedButton(
        onClick = { expanded = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(painterResource(R.drawable.ic_ms_model_training), null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            current?.let { "${it.name}  ·  ${it.type.uppercase()}" } ?: "选择模型",
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(painterResource(R.drawable.ic_ms_expand_more), null, Modifier.size(18.dp))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        models.forEach { model ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(model.name, fontSize = 13.sp)
                        Text(
                            "${model.type.uppercase()} · ${formatSize(model.sizeBytes)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(model)
                }
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes > 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes > 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.1f KB".format(bytes / 1024.0)
}

@Composable
fun AlignScreen(vm: AppViewModel, models: List<ModelEntry>, selectedModelId: String, onSelectModel: (ModelEntry) -> Unit) {
    var wavDir by remember { mutableStateOf(vm.defaultDir()) }
    // 默认日语：这个工具链最常见的素材是假名/罗马音音素库，中文可在下拉里切
    var language by remember { mutableStateOf("ja") }
    var dictionary by remember { mutableStateOf("") }
    var insertSp by remember { mutableStateOf(true) }
    var mergePhonemes by remember { mutableStateOf(true) }
    var padTimes by remember { mutableStateOf("1") }
    var padLength by remember { mutableStateOf("5") }
    var nonLexical by remember { mutableStateOf("AP,SP") }
    var batchSize by remember { mutableStateOf("2") }
    var skipPenalty by remember { mutableStateOf("1.0") }
    var threadLabel by remember { mutableStateOf(L.s("自动")) }

    val running by vm.running.collectAsState()
    val picker = rememberFolderPicker { wavDir = it; vm.rememberDir(it) }
    val dicts by vm.dicts.collectAsState()
    val presets by vm.presets.collectAsState()
    val dictImport = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importDict(uri, uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.'))
    }
    val model = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull { it.type == "hubertfa" }
    // 对齐用的字典必须是模型目录里自带的那几本：它们的音素带语言前缀（ja/y、zh/l），
    // 才和 vocab.json 对得上。通用 dictionary/ 下那些是裸音素，只适合 oto 侧。
    val officialDicts = remember(model?.dir?.absolutePath) {
        model?.dir?.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?.map { it.name to it.absolutePath }
            ?: emptyList()
    }

    ScreenColumn {
        Text(L.s("② 强制对齐"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            L.s("用内置的 ONNX 声学模型把 wav 与 lab 对齐，输出带音素边界的 TextGrid。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionCard(L.s("模型")) {
            ModelDropdown(models, model?.id ?: "", onSelect = onSelectModel)
            if (model == null) {
                Text(
                    L.s("还没有可用模型，请到「更多」页释放内置模型或导入。"),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            } else {
                Text(
                    if (model.type == "tifa")
                        "TIFA 推理：48kHz 梅尔频谱 + 相似度矩阵，batch=${batchSize}"
                    else
                        L.s("HubertFA 推理：16kHz 波形直接输入，Viterbi 解码"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard(L.s("数据")) {
            FolderField(L.s("wav 文件夹"), wavDir, { wavDir = it }, { picker.launch(null) })
            Text(
                L.s("同目录下的同名 .lab 会被自动读取；对齐结果写回同目录 .TextGrid。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(L.s("语言"), style = MaterialTheme.typography.bodyMedium)
            SegmentedRow(
                options = listOf(
                    "zh" to L.s("中文"),
                    "en" to "English",
                    "ja" to L.s("日本語"),
                    "yue" to L.s("粤语")
                ),
                selected = language,
                onSelect = { language = it }
            )
            DictField(
                label = L.s("字典文件（留空则按语言自动匹配）"),
                value = dictionary,
                builtIn = officialDicts,
                imported = dicts,
                onSelect = { dictionary = it },
                onImport = { dictImport.launch(arrayOf("text/plain", "*/*")) },
                onDelete = { vm.deleteDict(it) }
            )
        }

        SectionCard(L.s("对齐参数")) {
            SwitchRow(L.s("自动插入 SP"), L.s("为静音段补静音音素"), insertSp) { insertSp = it }
            SwitchRow(L.s("合并相同音素"), L.s("连续同音素合成一个区间"), mergePhonemes) { mergePhonemes = it }
            if (model?.type == "hubertfa") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextFieldSimple(L.s("推理次数"), padTimes, { padTimes = it }, L.s("多次推理取最优，1 最快"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextFieldSimple(L.s("填充长度(秒)"), padLength, { padLength = it }, L.s("每次在首尾补的静音上限"))
                }
            } else {
                Text(
                    "当前是 ${model?.name ?: "TIFA"}：它对同一输入是确定性解码，"
                        + L.s("推理次数与填充长度都不参与对齐，TIFA 走 batch_size 与 skip_penalty。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextFieldSimple(L.s("非语言音素"), nonLexical, { nonLexical = it }, "AP,SP")
            DropdownField(
                label = L.s("推理线程数"),
                value = threadLabel,
                options = listOf(L.s("自动"), "2", "4", "6", "8"),
                onSelect = { threadLabel = it }
            )
            Text(
                L.s("线程越多跑得越快，但内存占用同步增加（模型权重约 200MB 常驻，外加每个线程的临时张量）。")
                    + L.s("出现卡死、闪退或系统杀进程，说明运存不够，改回「自动」或 2 线程。"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (model?.type == "tifa") {
                TextFieldSimple("batch_size", batchSize, { batchSize = it })
                TextFieldSimple("skip_penalty", skipPenalty, { skipPenalty = it }, L.s("越小越容易把弱音素跳过，0.5 偏松，建议 1.0~2.0"))
            }
        }

        Spacer(Modifier.height(4.dp))
        RunBar(
            running = running,
            runLabel = L.s("开始对齐"),
            enabled = model != null && wavDir.isNotBlank(),
            onRun = {
                val params = JSONObject().apply {
                    put("wav_dir", wavDir.trim())
                    put("model_dir", model!!.dir.absolutePath)
                    put("engine", if (model.type == "tifa") "tifa" else "hubertfa")
                    put("language", language)
                    if (dictionary.isNotBlank()) put("dictionary", dictionary.trim())
                    put("insert_sp", insertSp)
                    put("merge_phonemes", mergePhonemes)
                    put("pad_times", padTimes.toIntOrNull() ?: 1)
                    put("pad_length", padLength.toFloatOrNull() ?: 5f)
                    put("non_lexical", nonLexical)
                    put("batch_size", batchSize.toIntOrNull() ?: 2)
                    put("skip_penalty", skipPenalty.toFloatOrNull() ?: 1.0f)
                    put("threads", if (threadLabel == L.s("自动")) 0 else threadLabel.toIntOrNull() ?: 0)
                }
                vm.runJob("align", params, L.s("强制对齐"))
            },
            onCancel = vm::cancelJob
        )
        Spacer(Modifier.height(70.dp))
    }
}
