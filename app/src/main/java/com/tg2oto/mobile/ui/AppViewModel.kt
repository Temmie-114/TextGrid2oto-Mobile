package com.tg2oto.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tg2oto.mobile.core.DictFile
import com.tg2oto.mobile.core.InferClient
import com.tg2oto.mobile.core.InferService
import com.tg2oto.mobile.core.DictionaryManager
import com.tg2oto.mobile.core.JobControl
import com.tg2oto.mobile.core.LogSink
import com.tg2oto.mobile.core.ModelEntry
import com.tg2oto.mobile.core.ModelManager
import com.tg2oto.mobile.core.PythonBridge
import com.tg2oto.mobile.data.AppSettings
import com.tg2oto.mobile.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.tg2oto.mobile.ui.i18n.L

data class Presets(
    val presamp: List<String> = emptyList(),
    val dicts: List<String> = emptyList(),
    val presampRoot: String = "",
    val dictRoot: String = ""
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    val settings: StateFlow<AppSettings> = store.flow.let { flow ->
        val state = MutableStateFlow(AppSettings())
        viewModelScope.launch { flow.collect { state.value = it } }
        state.asStateFlow()
    }

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _progress = MutableStateFlow(-1f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _status = MutableStateFlow(L.s("就绪"))
    val status: StateFlow<String> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _installProgress = MutableStateFlow<Float?>(null)
    val installProgress: StateFlow<Float?> = _installProgress.asStateFlow()

    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

    private val _presets = MutableStateFlow(Presets())
    val presets: StateFlow<Presets> = _presets.asStateFlow()

    private val _dicts = MutableStateFlow<List<DictFile>>(emptyList())
    val dicts: StateFlow<List<DictFile>> = _dicts.asStateFlow()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun settingsStore(): SettingsStore = store

    /** 最近一次选择的文件夹：四个工作流共用同一个默认值。 */
    fun defaultDir(): String = settings.value.lastDir

    fun defaultPresamp(): String = settings.value.presampPath

    fun rememberPresamp(path: String) {
        if (path == settings.value.presampPath) return
        viewModelScope.launch { store.setPresampPath(path) }
    }

    fun rememberDir(path: String) {
        if (path.isBlank() || path == settings.value.lastDir) return
        viewModelScope.launch { store.setLastDir(path) }
    }

    // ---------------------------------------------------------------- 模型

    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { ModelManager.list(getApplication()) }.getOrDefault(emptyList())
            _models.value = list
            if (list.isEmpty() && _installProgress.value == null) {
                appendLog(L.s("首次启动：正在释放内置模型（约 277MB，需要一点时间）"))
                startModelInstall()
            }
        }
    }

    fun refreshDicts() {
        _dicts.value = DictionaryManager.imported(getApplication())
    }

    fun importDict(uri: android.net.Uri, displayName: String?) {
        viewModelScope.launch {
            runCatching { DictionaryManager.import(getApplication(), uri, displayName) }
                .onSuccess {
                    appendLog("✔ 已导入字典 ${it.name}.txt")
                    refreshDicts()
                }
                .onFailure { appendLog("✘ 导入字典失败：${it.message}") }
        }
    }

    fun deleteDict(dict: DictFile) {
        if (DictionaryManager.delete(dict.file)) {
            appendLog("已删除字典 ${dict.name}.txt")
            refreshDicts()
        }
    }

    fun loadResources() {
        viewModelScope.launch(Dispatchers.IO) {
            val raw = PythonBridge.resources(getApplication())
            runCatching { JSONObject(raw) }.onSuccess { json ->
                val presamp = json.optJSONArray("presamp")
                val dicts = json.optJSONArray("dicts")
                _presets.value = Presets(
                    presamp = (0 until (presamp?.length() ?: 0)).map { presamp!!.getString(it) },
                    dicts = (0 until (dicts?.length() ?: 0)).map { dicts!!.getString(it) },
                    presampRoot = json.optString("presamp_root"),
                    dictRoot = json.optString("dict_root")
                )
            }
        }
    }

    fun startModelInstall() {
        if (_installProgress.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _installProgress.value = 0f
            try {
                ModelManager.installBuiltin(getApplication()) { pct, file ->
                    _installProgress.value = pct
                    if (pct >= 1f) _status.value = L.s("内置模型就绪")
                    else if (file.isNotEmpty()) _status.value = "释放模型：${file.substringAfterLast('/')}"
                }
                appendLog(L.s("✔ 内置模型已释放到应用存储"))
            } catch (e: Exception) {
                appendLog("✘ 模型释放失败：${e.message}")
            } finally {
                _installProgress.value = null
                refreshModels()
            }
        }
    }

    suspend fun importModel(zip: java.io.File): List<ModelEntry> = withContext(Dispatchers.IO) {
        val result = ModelManager.importZip(getApplication(), zip, zip.name.removeSuffix(".zip")) { msg -> appendLog(msg) }
        refreshModels()
        result
    }

    fun deleteModel(entry: ModelEntry) {
        viewModelScope.launch(Dispatchers.IO) {
        if (ModelManager.delete(entry)) {
            appendLog("已删除模型 ${entry.name}")
            refreshModels()
        }
        }
    }

    // ---------------------------------------------------------------- 任务

    // ---------------------------------------------------------- 推理进程

    private var client: InferClient? = null

    data class CrashInfo(val detail: String, val logs: List<String>)

    private val _crash = MutableStateFlow<CrashInfo?>(null)
    val crash: StateFlow<CrashInfo?> = _crash.asStateFlow()

    private val _backendReport = MutableStateFlow<String?>(null)
    val backendReport: StateFlow<String?> = _backendReport.asStateFlow()

    private var lastKind: String? = null

    fun setLanguage(v: String) {
        viewModelScope.launch { store.setLanguage(v) }
    }

    fun setBackend(id: String) {
        viewModelScope.launch { store.setBackend(id) }
        appendLog("推理后端切换为 ${com.tg2oto.mobile.core.Backend.of(id).label}（下次任务生效）")
    }

    fun probeBackends() {
        if (_running.value) return
        _running.value = true
        _progress.value = 0f
        _status.value = L.s("检测推理后端")
        lastKind = InferService.PROBE_KIND
        appendLog(L.s("———————— 检测推理后端 ————————"))
        val payload = org.json.JSONObject().apply {
            put("kind", InferService.PROBE_KIND)
            put("title", L.s("检测推理后端"))
        }
        inferClient().run(payload.toString())
    }

    /** 用真实 HubertFA 模型跑各后端，输出耗时对比。 */
    fun benchBackends() {
        if (_running.value) return
        val entry = _models.value.firstOrNull { it.type == "hubertfa" }
        if (entry == null) {
            appendLog(L.s("✘ 没有可用的 HubertFA 模型，先到「模型管理」确认内置模型已释放"))
            return
        }
        val onnx = java.io.File(entry.dir, "model.onnx")
        if (!onnx.isFile) {
            appendLog("✘ 模型文件不存在：${onnx.absolutePath}")
            return
        }
        _running.value = true
        _progress.value = 0f
        _status.value = L.s("实机基准测试")
        lastKind = InferService.BENCH_KIND
        appendLog(L.s("———————— 实机基准测试（真实模型） ————————"))
        appendLog("模型 ${entry.name}（${entry.precision}）")
        val payload = org.json.JSONObject().apply {
            put("kind", InferService.BENCH_KIND)
            put("title", L.s("实机基准测试"))
            put("model", onnx.absolutePath)
            put("samples", 32000)
            put("runs", 3)
        }
        inferClient().run(payload.toString())
    }

    fun dismissBackendReport() {
        _backendReport.value = null
    }

    fun dismissCrash() {
        _crash.value = null
    }

    private fun inferClient(): InferClient = client ?: InferClient(
        context = getApplication(),
        onLog = { appendLog(it) },
        onProgress = { _progress.value = it },
        onResult = { raw -> handleResult(raw) },
        onCrash = { detail ->
            _running.value = false
            _progress.value = -1f
            _status.value = L.s("推理进程崩溃")
            appendLog(L.s("✘ 推理进程崩溃（详见弹窗）"))
            if (_crash.value == null) _crash.value = CrashInfo(detail, _logs.value.takeLast(240))
        }
    ).also {
        it.bind()
        client = it
    }

    private fun handleResult(raw: String) {
        val result = runCatching { JSONObject(raw) }
            .getOrElse { JSONObject().put("ok", false).put("error", raw) }
        if (lastKind == InferService.PROBE_KIND || lastKind == InferService.BENCH_KIND) {
            val bench = lastKind == InferService.BENCH_KIND
            lastKind = null
            _backendReport.value = if (bench) {
                result.optString("bench").ifBlank { raw }
            } else {
                result.optString("probe").ifBlank { raw }
            }
            _running.value = false
            _progress.value = -1f
            _status.value = if (bench) L.s("基准测试完成") else L.s("检测完成")
            return
        }
        val ok = result.optBoolean("ok", false)
        when {
            ok -> {
                _progress.value = 1f
                _status.value = L.s("完成")
            }
            result.optBoolean("cancelled", false) -> {
                _progress.value = -1f
                _status.value = L.s("已中止")
            }
            else -> {
                _progress.value = -1f
                _status.value = L.s("失败")
                val error = result.optString("error").ifBlank { L.s("未知错误") }
                appendLog("✘ $error")
                if (result.optString("traceback").isNotBlank()) {
                    appendLog(result.optString("traceback"))
                }
            }
        }
        _running.value = false
    }

    private val logPending = java.util.ArrayDeque<String>()
    private var logFlushScheduled = false

    /**
     * 日志按 100ms 合批刷新。推理时 Python 侧每秒可能输出几十行，
     * 逐行更新会让整页每行都重组一遍，这是之前界面卡顿的主因。
     */
    fun appendLog(line: String) {
        val stamped = "${timeFmt.format(Date())}  $line"
        synchronized(logPending) { logPending.addLast(stamped) }
        if (logFlushScheduled) return
        logFlushScheduled = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(100)
            while (true) {
                val batch = synchronized(logPending) {
                    if (logPending.isEmpty()) null
                    else ArrayList<String>(logPending).also { logPending.clear() }
                } ?: break
                _logs.value = (_logs.value + batch).takeLast(400)
            }
            logFlushScheduled = false
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun runJob(kind: String, params: JSONObject, title: String) {
        if (_running.value) return
        _running.value = true
        _progress.value = 0f
        _status.value = title
        params.put("kind", kind)
        params.put("title", title)
        params.put("backend", settings.value.backend)
        lastKind = kind
        appendLog("———————— $title ————————")
        // 任务在 :infer 进程执行，主进程只负责转发日志与进度
        inferClient().run(params.toString())
    }

    override fun onCleared() {
        client?.unbind()
        client = null
        super.onCleared()
    }

    fun cancelJob() {
        client?.cancel()
        appendLog(L.s("⏹ 正在中止…（当前步骤结束后停止）"))
    }
}
