package com.tg2oto.mobile.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tg2oto.mobile.core.ModelManager
import com.tg2oto.mobile.ui.AppViewModel
import com.tg2oto.mobile.ui.components.FolderField
import com.tg2oto.mobile.ui.components.RunBar
import com.tg2oto.mobile.ui.components.ScreenColumn
import com.tg2oto.mobile.ui.components.SectionCard
import com.tg2oto.mobile.ui.components.TextFieldSimple
import com.tg2oto.mobile.ui.i18n.L
import com.tg2oto.mobile.util.rememberFolderPicker
import org.json.JSONObject
import java.io.File

/**
 * 独立工作流：只产 .frq，不产 oto.ini。
 *
 * 原本这一步是 oto 页面里的一个开关，但它和 oto 没有输入输出上的耦合，
 * 挂在 oto 后面只会让一次点击变成两次长时间等待，所以单独成页。
 */
@Composable
fun FrqScreen(vm: AppViewModel) {
    var wavDir by remember { mutableStateOf(vm.defaultDir()) }
    var threads by remember { mutableStateOf("2") }
    val running by vm.running.collectAsState()
    val ctx = LocalContext.current
    val picker = rememberFolderPicker { wavDir = it; vm.rememberDir(it) }

    val rmvpe = remember(ctx) {
        File(ModelManager.modelsRoot(ctx), "rmvpe/rmvpe.onnx")
    }
    // 模型可能在本次停留期间才释放完，所以每次重组都 stat 一次（开销可以忽略）
    val modelReady = rmvpe.isFile && rmvpe.length() > 0
    val modelMb = rmvpe.length() / 1048576

    ScreenColumn {
        Text(
            L.s("⑤ 生成 frq 基频率表"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            L.s("用 RMVPE 逐个分析目录下的 wav，写出同名 .frq 供 UTAU / OpenUtau 读取。" +
                "这一步只写 frq，不改动 oto.ini。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionCard(L.s("输入")) {
            FolderField(L.s("录音文件夹"), wavDir, { wavDir = it }, { picker.launch(null) })
            Text(
                if (modelReady) L.s("RMVPE 模型已就绪") + " · $modelMb MB"
                else L.s("没有找到 RMVPE 模型。请先在「资源管理」里装好内置模型，等释放进度走完再回来。"),
                style = MaterialTheme.typography.bodySmall,
                color = if (modelReady) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            TextFieldSimple(L.s("线程数"), threads, { threads = it }, "2")
            Text(
                L.s("上面的数字既是「同时处理几个文件」，也是每个文件分到的推理线程数；" +
                    "手机上 2~4 通常最快，开太大反而互相抢核。可以随时取消。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        RunBar(
            running = running,
            runLabel = L.s("生成 frq"),
            enabled = wavDir.isNotBlank() && modelReady,
            onRun = {
                vm.runJob(
                    "frq",
                    JSONObject().apply {
                        put("wav_dir", wavDir.trim())
                        put("rmvpe_model", rmvpe.absolutePath)
                        put("threads", threads.toIntOrNull() ?: 2)
                    },
                    L.s("生成 frq 基频率表")
                )
            },
            onCancel = vm::cancelJob
        )
        Spacer(Modifier.height(70.dp))
    }
}
