package com.tg2oto.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg2oto.mobile.ui.AppViewModel
import com.tg2oto.mobile.core.ModelEntry
import com.tg2oto.mobile.ui.components.DictField
import com.tg2oto.mobile.ui.components.FolderField
import com.tg2oto.mobile.ui.components.RunBar
import com.tg2oto.mobile.ui.components.ScreenColumn
import com.tg2oto.mobile.ui.components.SectionCard
import com.tg2oto.mobile.ui.components.SwitchRow
import com.tg2oto.mobile.ui.components.TextFieldSimple
import com.tg2oto.mobile.util.rememberFolderPicker
import org.json.JSONObject
import com.tg2oto.mobile.ui.i18n.L

@Composable
fun JsonScreen(
    vm: AppViewModel,
    models: List<ModelEntry>,
    selectedModelId: String
) {
    var folder by remember { mutableStateOf(vm.defaultDir()) }
    var dictionary by remember { mutableStateOf("") }
    var ignore by remember { mutableStateOf("AP,EP,SP") }
    var recPreset by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(true) }

    val running by vm.running.collectAsState()
    val picker = rememberFolderPicker { folder = it; vm.rememberDir(it) }
    val dicts by vm.dicts.collectAsState()
    val presets by vm.presets.collectAsState()
    val model = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()
    // 和对齐页同一套：用模型自带的官方字典，音素带语言前缀才和 TextGrid 对得上
    val officialDicts = remember(model?.dir?.absolutePath) {
        model?.dir?.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?.map { it.name to it.absolutePath }
            ?: emptyList()
    }
    val dictImport = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importDict(uri, uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.'))
    }

    ScreenColumn {
        Text("③ TextGrid → JSON", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            L.s("把 TextGrid 里的音素区间整理成 DiffSinger 可用的 json，再按字典过滤并重建 words 层。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionCard(L.s("输入")) {
            FolderField(L.s("TextGrid 文件夹"), folder, { folder = it }, { picker.launch(null) })
            Text(
                L.s("默认递归处理子文件夹；每个目录下会生成 json/ 子目录。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DictField(
                label = L.s("字典文件"),
                value = dictionary,
                builtIn = officialDicts,
                imported = dicts,
                onSelect = { dictionary = it },
                onImport = { dictImport.launch(arrayOf("text/plain", "*/*")) },
                onDelete = { vm.deleteDict(it) }
            )
            Text(
                L.s("用于把音素还原成文字；中文用 zh.txt，日文用 ja-hira-假名.txt，英文用 en-arpasing.txt。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(L.s("过滤")) {
            TextFieldSimple(L.s("忽略音素"), ignore, { ignore = it }, "AP,EP,SP")
            TextFieldSimple(L.s("录音方案预设"), recPreset, { recPreset = it }, L.s("留空则自动判断"))
            SwitchRow(L.s("递归子文件夹"), null, recursive) { recursive = it }
        }

        Spacer(Modifier.height(4.dp))
        RunBar(
            running = running,
            runLabel = L.s("生成 JSON"),
            enabled = folder.isNotBlank() && dictionary.isNotBlank(),
            onRun = {
                vm.runJob(
                    "json",
                    JSONObject().apply {
                        put("folder", folder.trim())
                        put("dictionary", dictionary.trim())
                        put("model_dir", model?.dir?.absolutePath ?: "")
                        put("ignore", ignore)
                        put("rec_preset", recPreset)
                        put("recursive", recursive)
                    },
                    "TextGrid → JSON"
                )
            },
            onCancel = vm::cancelJob
        )
        Spacer(Modifier.height(70.dp))
    }
}
