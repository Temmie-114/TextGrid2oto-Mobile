package com.tg2oto.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import com.tg2oto.mobile.R
import com.tg2oto.mobile.ui.AppViewModel
import com.tg2oto.mobile.ui.components.FileField
import com.tg2oto.mobile.ui.components.FolderField
import com.tg2oto.mobile.ui.components.RunBar
import com.tg2oto.mobile.ui.components.ScreenColumn
import com.tg2oto.mobile.ui.components.DropdownField
import com.tg2oto.mobile.ui.components.SectionCard
import com.tg2oto.mobile.ui.components.SegmentedRow
import com.tg2oto.mobile.ui.components.TextFieldSimple
import com.tg2oto.mobile.ui.components.SwitchRow
import com.tg2oto.mobile.util.rememberFilePathPicker
import com.tg2oto.mobile.util.rememberFolderPicker
import org.json.JSONObject
import com.tg2oto.mobile.ui.i18n.L

// 官方 config/run-config-*.txt 的默认参数，按录制方式区分：
// 每项依次是 左边界 / 固定 / 右边界 / 预发声 / 交叉
private val CV_PARAMS: Map<String, String> = mapOf(
    "CVVC" to "1,3,1.5,1,2", "VCV" to "1,3,1.5,1,2",
    "CV" to "1,3,1,1,2", "ARPASING" to "1,3,1.5,1,2",
    "CVV" to "1,3,1,1,2",
)
private val VC_PARAMS: Map<String, String> = mapOf(
    "CVVC" to "3,0,2,1,2", "VCV" to "3,3,1.5,1,3,3",
    "CV" to "5,0,2,1,2", "ARPASING" to "3,0,2,1,2",
    "CVV" to "5,0,2,1,2",
)
private val VV_PARAMS: Map<String, String> = mapOf(
    "CVVC" to "3,3,1.5,1,3", "VCV" to "0,0,0,0,0",
    "CV" to "0,0,0,0,0", "ARPASING" to "3,3,1.5,1,1.5",
    "CVV" to "0,0,0,0,0",
)

@Composable
fun OtoScreen(vm: AppViewModel) {
    var wavDir by remember { mutableStateOf(vm.defaultDir()) }
    var presamp by remember { mutableStateOf(vm.defaultPresamp()) }
    var mode by remember { mutableStateOf("CVVC") }
    var cvParams by remember { mutableStateOf("1,3,1.5,1,2") }
    var vcParams by remember { mutableStateOf(VC_PARAMS["CVVC"]!!) }
    var vvParams by remember { mutableStateOf(VV_PARAMS["CVVC"]!!) }
    var cutoffFromEnd by remember { mutableStateOf(false) }
    // 官方公式下句首「- X」的播放区间够不到第一条 VCV 条目，会留空档；
    // 打开这盏才按非官方推导把它延伸过去。
    var extendInitialCutoff by remember { mutableStateOf(false) }
    // 句首辅音常被对齐判给前面的 R/SP 段，offset 落在辅音之后；这里可把 offset 往前探几毫秒收回来
    var initialPrefetch by remember { mutableStateOf("0") }
    var cvRepeat by remember { mutableStateOf("1") }
    var vcRepeat by remember { mutableStateOf("1") }
    var ignore by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var encoding by remember { mutableStateOf("UTF-8") }
    var cover by remember { mutableStateOf("n") }
    var pitch by remember { mutableStateOf("") }

    val running by vm.running.collectAsState()
    val presets by vm.presets.collectAsState()
    val picker = rememberFolderPicker { wavDir = it; vm.rememberDir(it) }
    val tplPicker = rememberFilePathPicker { template = it }
    var showPresamp by remember { mutableStateOf(false) }
    // 导入自定义 presamp.ini：选中后会复制到应用私有目录，
    // 这里存绝对路径，runner 侧见到绝对路径就直接用，不再拼内置目录。
    val presampPicker = rememberFilePathPicker { presamp = it; vm.rememberPresamp(it) }

    ScreenColumn {
        Text(L.s("④ 生成 oto.ini"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            L.s("读取 json 里的音素边界，按 CV / CVVC / VCV / ARPAsing 规则生成 oto.ini。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionCard(L.s("输入")) {
            FolderField(L.s("录音文件夹"), wavDir, { wavDir = it }, { picker.launch(null) })
            Text(
                L.s("需要该目录下已有 json/word_phone.json（由上一步生成）。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { showPresamp = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_ms_subtitles), null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        presamp.isBlank() -> L.s("选择 presamp 规则")
                        presamp.startsWith("/") -> java.io.File(presamp).name
                        else -> presamp
                    },
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(painterResource(R.drawable.ic_ms_expand_more), null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = showPresamp, onDismissRequest = { showPresamp = false }) {
                presets.presamp.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name, fontSize = 12.sp) },
                        onClick = {
                            presamp = name
                            vm.rememberPresamp("")
                            showPresamp = false
                        }
                    )
                }
                if (presets.presamp.isEmpty()) {
                    DropdownMenuItem(text = { Text(L.s("内置 presamp 尚未加载"), fontSize = 12.sp) }, onClick = {})
                }
                DropdownMenuItem(
                    text = { Text(L.s("导入自定义 presamp.ini…"), fontSize = 12.sp) },
                    onClick = {
                        showPresamp = false
                        presampPicker.launch(arrayOf("*/*"))
                    }
                )
            }
        }

        SectionCard(L.s("录音方案")) {
            SegmentedRow(
                options = listOf(
                    "CVVC" to "CVVC",
                    "VCV" to "VCV",
                    "CVV" to "CVV",
                    "ARPASING" to "ARPAsing"
                ),
                selected = mode,
                onSelect = { m ->
                    mode = m
                    CV_PARAMS[m]?.let { cvParams = it }
                    VC_PARAMS[m]?.let { vcParams = it }
                    VV_PARAMS[m]?.let { vvParams = it }
                }
            )
            Text(
                L.s("需要与录制方式一致，否则 oto 参数会错位。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(L.s("oto 参数")) {
            Text(L.s("CV 参数（左边界 / 固定 / 右边界 / 预发声 / 交叉）"), fontSize = 11.sp)
            TextFieldSimple("CV", cvParams, { cvParams = it })
            Text(L.s("VC 参数（左边界 / 固定 / 右边界 / 预发声 / 交叉）"), fontSize = 11.sp)
            TextFieldSimple("VC", vcParams, { vcParams = it })
            Text(L.s("VV 参数（左边界 / 固定 / 右边界 / 预发声 / 交叉）"), fontSize = 11.sp)
            TextFieldSimple("VV", vvParams, { vvParams = it })
            SwitchRow(
                L.s("cutoff 按文件末尾算"),
                L.s("关：按 UTAU 规范从 offset 起算；开：从音频末尾倒算。") +
                    L.s("若 OpenUtau 里采样被切成前一个母音（例如 a て 只剩 a），就打开它。"),
                cutoffFromEnd
            ) { cutoffFromEnd = it }
            SwitchRow(
                L.s("句首 cutoff 延伸到下一音节"),
                L.s("默认关，严格按官方公式。打开后句首「- X」的播放区间会接到第一条 VCV 条目，") +
                    L.s("消除句首 10~170ms 的空档；数值会比官方公式长一截。"),
                extendInitialCutoff
            ) { extendInitialCutoff = it }
            TextFieldSimple(L.s("首音前探 (ms)"), initialPrefetch, { initialPrefetch = it })
            Text(
                L.s("句首条目的 offset 往前挪这么多毫秒，preutterance 同步加等长，音头位置不变。") +
                    L.s("听上去句首只剩元音、辅音像被切掉时，填 20~50 试试；0 表示按官方公式。"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextFieldSimple(L.s("CV 重复次数"), cvRepeat, { cvRepeat = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextFieldSimple(L.s("VC 重复次数"), vcRepeat, { vcRepeat = it })
            }
            TextFieldSimple(L.s("忽略音素"), ignore, { ignore = it }, "AP,EP,SP")
        }

        SectionCard(L.s("输出")) {
            FileField(L.s("模板 oto.ini（可选）"), template, { template = it }) { tplPicker.launch(arrayOf("*/*")) }
            DropdownField(
                label = L.s("编码"),
                value = encoding,
                options = listOf("UTF-8", "GBK", "GB18030", "SHIFT-JIS", "BIG5", "UTF-16LE"),
                onSelect = { encoding = it }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextFieldSimple("cover", cover, { cover = it }, "n")
            }
            TextFieldSimple(L.s("pitch 后缀"), pitch, { pitch = it }, L.s("如 C4"))
        }

        Spacer(Modifier.height(4.dp))
        RunBar(
            running = running,
            runLabel = L.s("生成 oto.ini"),
            enabled = wavDir.isNotBlank() && presamp.isNotBlank(),
            onRun = {
                vm.runJob(
                    "oto",
                    JSONObject().apply {
                        put("wav_dir", wavDir.trim())
                        put("presamp", presamp.trim())
                        put("mode", mode)
                        put("cv_params", cvParams)
                        put("vc_params", vcParams)
                        put("cutoff_from_end", cutoffFromEnd)
                        put("extend_initial_cutoff", extendInitialCutoff)
                        put("initial_offset_prefetch", initialPrefetch.toDoubleOrNull() ?: 0.0)
                        put("vv_params", vvParams)
                        put("cv_repeat", cvRepeat.toIntOrNull() ?: 1)
                        put("vc_repeat", vcRepeat.toIntOrNull() ?: 1)
                        put("ignore", ignore)
                        put("template", template.trim())
                        put("encoding", encoding)
                        put("cover", cover)
                        put("pitch", pitch)
                    },
                    L.s("生成 oto.ini")
                )
            },
            onCancel = vm::cancelJob
        )
        Spacer(Modifier.height(70.dp))
    }
}
