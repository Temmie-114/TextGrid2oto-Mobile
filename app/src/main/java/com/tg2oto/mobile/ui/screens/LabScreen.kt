package com.tg2oto.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.tg2oto.mobile.ui.components.FolderField
import com.tg2oto.mobile.ui.components.RunBar
import com.tg2oto.mobile.ui.components.ScreenColumn
import com.tg2oto.mobile.ui.components.FileField
import com.tg2oto.mobile.ui.components.SectionCard
import com.tg2oto.mobile.ui.components.TextFieldSimple
import com.tg2oto.mobile.ui.components.SwitchRow
import com.tg2oto.mobile.util.rememberFilePathPicker
import com.tg2oto.mobile.util.rememberFolderPicker
import org.json.JSONObject
import com.tg2oto.mobile.ui.i18n.L

@Composable
fun LabScreen(vm: AppViewModel) {
    var folder by remember { mutableStateOf(vm.defaultDir()) }
    var cuts by remember { mutableStateOf("_,-") }
    var autoSp by remember { mutableStateOf(true) }
    var dropR by remember { mutableStateOf(false) }
    var indexMode by remember { mutableStateOf(false) }
    var indexPath by remember { mutableStateOf("") }

    val running by vm.running.collectAsState()
    val picker = rememberFolderPicker { folder = it; vm.rememberDir(it) }
    val filePicker = rememberFilePathPicker { indexPath = it }

    ScreenColumn {
        Text(
            L.s("① 生成 LAB"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            L.s("按 wav 文件名（拼音 / 假名 / 罗马音）自动生成同名 .lab 标注文件，供强制对齐使用。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionCard(L.s("输入")) {
            FolderField(L.s("录音文件夹"), folder, { folder = it }, { picker.launch(null) })
            Text(
                L.s("该文件夹下的 .wav 会被逐个生成 .lab（已存在则跳过）。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(L.s("切分规则")) {
            TextFieldSimple(L.s("文件名分隔符"), cuts, { cuts = it }, L.s("用逗号分隔，例如 _, -"))
            SwitchRow(L.s("自动补 SP"), L.s("音素之间插入静音标记"), autoSp) { autoSp = it }
            SwitchRow(L.s("去掉 R 音"), L.s("英文 r 音素不带标记"), dropR) { dropR = it }
            SwitchRow(L.s("使用索引表"), L.s("改为按 index 表批量生成"), indexMode) { indexMode = it }
            if (indexMode) {
                FileField(
                    L.s("索引文件"), indexPath, { indexPath = it }
                ) { filePicker.launch(arrayOf("*/*")) }
            }
        }

        Spacer(Modifier.height(4.dp))
        RunBar(
            running = running,
            runLabel = L.s("生成 LAB"),
            enabled = folder.isNotBlank() || indexMode,
            onRun = {
                val params = JSONObject().apply {
                    put("folder", folder.trim())
                    put("cuts", cuts.split(Regex("[,，、;；\\s]+"))
                        .map { it.trim() }.filter { it.isNotEmpty() })
                    put("auto_sp", autoSp)
                    put("drop_r", dropR)
                    if (indexMode && indexPath.isNotBlank()) put("index_path", indexPath.trim())
                }
                vm.runJob("lab", params, L.s("生成 LAB"))
            },
            onCancel = vm::cancelJob
        )
        Spacer(Modifier.height(70.dp))
    }
}
