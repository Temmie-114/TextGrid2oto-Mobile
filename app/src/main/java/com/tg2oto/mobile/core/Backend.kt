package com.tg2oto.mobile.core

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.io.File
import java.nio.FloatBuffer

/**
 * 推理后端。
 *
 * Android 上 ONNX Runtime 能用的加速后端其实很有限：
 *  - **XNNPACK**：CPU 上的卷积/矩阵加速，官方 AAR 已编入，最稳；
 *  - **NNAPI**：系统级加速（厂商可把算子派给 GPU / DSP / NPU），Android 15 起
 *    Google 已弃用，Android 16 多数机型的驱动被移除，所以只能“有就用、没有就退”；
 *    「GPU 加速」= NNAPI 的 `CPU_DISABLED` 模式，强制只用硬件加速器，失败会退回 CPU；
 *  - **GPU**：ORT 在 Android 上没有 OpenCL EP，GPU 只有通过 NNAPI 的硬件模式间接使用。
 *
 * 本版不接入厂商 NPU（高通 QNN / 联发科 NeuroPilot）：前者需要自行编译带 QNN 的
 * ONNX Runtime，后者只支持 TFLite，且该模型算子结构不适合 delegate 接管。
 */
enum class Backend(val id: String, val label: String, val desc: String) {
    AUTO("auto", "自动", "优先 XNNPACK，失败退 CPU"),

    CPU("cpu", "CPU", "纯 CPU，最稳但最慢"),
    XNNPACK("xnnpack", "XNNPACK", "CPU 卷积/矩阵加速"),
    NNAPI("nnapi", "NNAPI", "系统加速，允许 CPU 兜底"),
    NNAPI_HW("nnapi_hw", "GPU 加速", "NNAPI 硬件模式：禁用 CPU 回退，交给 GPU/DSP");

    companion object {
        fun of(id: String?): Backend = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * 芯片 / NPU 接口映射。
 *
 * 安卓没有统一的 NPU API，两家 SoC 走的是完全不同的栈：
 *  - **高通**：QNN（AI Engine Direct），运行时库 `libQnnHtp.so`，ONNX 侧需要
 *    编入了 QNN EP 的 ONNX Runtime（构建参数 `--use_qnn`）；
 *  - **联发科**：NeuroPilot，只提供 **TFLite / LiteRT delegate**（`libneuropilot_*.so`），
 *    没有 ONNX 运行时，ONNX 模型必须先转 TFLite 才能吃到 NPU。
 */
enum class Soc { QUALCOMM, MEDIATEK, OTHER }

object SocInfo {

    private fun cpuInfoHardware(): String = runCatching {
        File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Hardware") }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
    }.getOrDefault("")

    fun hardwareSummary(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        return listOf(soc, Build.HARDWARE, cpuInfoHardware())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" / ")
    }

    fun detect(): Soc {
        val hay = buildString {
            append(Build.HARDWARE).append(' ')
            append(Build.MANUFACTURER).append(' ')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                append(Build.SOC_MODEL).append(' ')
                append(Build.SOC_MANUFACTURER).append(' ')
            }
            append(cpuInfoHardware())
        }.lowercase()

        return when {
            "qualcomm" in hay || "qcom" in hay || Regex("\\b(sm|msm|sdm|apq)\\d{3,4}\\b").find(hay) != null -> Soc.QUALCOMM
            "mediatek" in hay || "mtk" in hay || Regex("\\bmt\\d{4}\\b").find(hay) != null -> Soc.MEDIATEK
            else -> Soc.OTHER
        }
    }

    fun label(soc: Soc): String = when (soc) {
        Soc.QUALCOMM -> "高通（Qualcomm）"
        Soc.MEDIATEK -> "联发科（MediaTek）"
        Soc.OTHER -> "其他 / 无法识别"
    }

    /** 这家芯片的 NPU 接口（本版不接入，仅作信息展示）。 */
    fun npuApi(soc: Soc): String = when (soc) {
        Soc.QUALCOMM -> "QNN（本版未接入，仅 CPU / GPU / NNAPI）"
        Soc.MEDIATEK -> "NeuroPilot（本版未接入，仅 CPU / GPU / NNAPI）"
        Soc.OTHER -> "无厂商 NPU 栈，本版仅 CPU / GPU / NNAPI"
    }
}

/**
 * 后端能力探测：在推理进程里真正尝试建一个 session，失败就把原因留下来。
 *
 * 探测用的是模型包里 14KB 的 `select.onnx`——小到瞬间加载，又能覆盖基本算子。
 * 注意：探测通过只说明「后端能加载」，不代表它支持大模型的全部算子；
 * 真跑的时候 [OrtEngine] 还会再兜一次底。
 */
object BackendProbe {

    private val PROBE_CANDIDATES = listOf(
        "TIFA_model/TIFA-1.0-ST/select.onnx",
        "TIFA_model/TIFA-1.0-ST/prepare.onnx",
        "TIFA_model/TIFA-1.0-ST/spectrogram.onnx"
    )

    fun probeModel(context: Context): File? {
        val root = File(context.filesDir, "models")
        return PROBE_CANDIDATES.map { File(root, it) }.firstOrNull { it.isFile }
    }

    /** 返回一份可直接展示 / 复制的诊断报告。 */
    fun report(context: Context): String {
        val json = JSONObject()
        val device = deviceInfo(context)
        json.put("device", device)

        val providers = JSONArray()
        runCatching {
            OrtEnvironmentProviders().forEach { providers.put(it) }
        }
        json.put("providers", providers)
        json.put("libs", npuLibs())

        val soc = SocInfo.detect()
        val npu = JSONObject().apply {
            put("soc", SocInfo.label(soc))
            put("hardware", SocInfo.hardwareSummary())
            put("api", SocInfo.npuApi(soc))
            put("vendor_libs", npuLibs())
        }
        json.put("npu", npu)

        val probe = probeModel(context)
        val backends = JSONArray()
        if (probe == null) {
            json.put("probe_model", "")
            json.put("backends", backends)
            json.put("note", "模型尚未释放到应用存储，无法做实机探测；下面是编译期能力")
            return render(json)
        }
        json.put("probe_model", probe.absolutePath)

        for (backend in listOf(Backend.CPU, Backend.XNNPACK, Backend.NNAPI, Backend.NNAPI_HW)) {
            val item = JSONObject().put("id", backend.id).put("label", backend.label)
            val started = System.currentTimeMillis()
            val error = runCatching {
                OrtEngine.probeSession(probe.absolutePath, backend.id)
            }.exceptionOrNull()
            item.put("ok", error == null)
            item.put("ms", System.currentTimeMillis() - started)
            if (error != null) {
                item.put("error", "${error.javaClass.simpleName}: ${error.message}")
            }
            backends.put(item)
        }
        json.put("backends", backends)
        return render(json)
    }

    /**
     * 用真实模型实测推理耗时。创建 session 成功不代表算子真的跑在加速器上，
     * 必须实跑才能看出是否真加速（不支持的算子会被驱动静默回退到 CPU）。
     */
    fun bench(modelPath: String, samples: Int = 32000, runs: Int = 3): String {
        val sb = StringBuilder()
        sb.appendLine("模型 ${File(modelPath).name} · ${samples} 采样点（%.1f 秒 @16k）".format(samples / 16000.0))
        sb.appendLine("先热身 1 次，再取 $runs 次平均；耗时与 CPU 接近或更慢 = 没有真加速。")
        val env = OrtEnvironment.getEnvironment()
        // 稳定可复现的合成波形，避免随机数据触发不同分支
        val data = FloatArray(samples) { i -> (kotlin.math.sin(i * 0.05) * 0.3).toFloat() }
        for (backend in listOf(Backend.CPU, Backend.XNNPACK, Backend.NNAPI_HW, Backend.NNAPI)) {
            val total0 = System.currentTimeMillis()
            runCatching {
                val session = env.createSession(modelPath, OrtEngine.options(backend, 4))
                val inName = session.inputNames.first()
                val tensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(data), longArrayOf(1, samples.toLong())
                )
                session.run(mapOf(inName to tensor))
                val t1 = System.nanoTime()
                repeat(runs) { session.run(mapOf(inName to tensor)) }
                val ms = (System.nanoTime() - t1) / 1e6 / runs
                val total = System.currentTimeMillis() - total0
                sb.appendLine("%s — %.1f ms/次（建 session 共 %d ms）".format(backend.label, ms, total))
                tensor.close()
                session.close()
            }.onFailure {
                val msg = "${it.javaClass.simpleName}: ${it.message}"
                sb.appendLine("%s — 失败：%s".format(backend.label, msg.take(160)))
            }
        }
        return sb.toString()
    }

    private fun OrtEnvironmentProviders(): List<String> =
        runCatching {
            ai.onnxruntime.OrtEnvironment.getAvailableProviders()
                .map { it.name }
                .sorted()
        }.getOrDefault(emptyList())

    fun deviceInfo(context: Context): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("hardware", Build.HARDWARE)
        put("abi", Build.SUPPORTED_ABIS.joinToString(","))
        put("sdk", Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            put("soc_model", Build.SOC_MODEL)
            put("soc_manufacturer", Build.SOC_MANUFACTURER)
        }
    }

    /** 扫描系统库目录，看有没有厂商 NPU / DSP 运行库。 */
    private fun npuLibs(): JSONArray {
        val out = JSONArray()
        val pattern = Regex("lib(Qnn|Neuron|apu|npu|dsp|Snpe|SNPE|htp|Htp)", RegexOption.IGNORE_CASE)
        for (dir in listOf("/vendor/lib64", "/vendor/lib", "/system/lib64", "/system/lib", "/odm/lib64")) {
            val files = runCatching { File(dir).listFiles() }.getOrNull() ?: continue
            files.filter { pattern.containsMatchIn(it.name) }
                .take(12)
                .forEach { out.put("$dir/${it.name}") }
        }
        return out
    }

    private fun render(json: JSONObject): String {
        val sb = StringBuilder()
        val device = json.optJSONObject("device")
        sb.appendLine("【设备】")
        if (device != null) {
            sb.appendLine("  ${device.optString("manufacturer")} ${device.optString("model")}  Android ${device.optInt("sdk")}")
            sb.appendLine("  硬件 ${device.optString("hardware")}    SoC ${device.optString("soc_model")} / ${device.optString("soc_manufacturer")}")
            sb.appendLine("  ABI ${device.optString("abi")}")
        }
        sb.appendLine()
        val providers = json.optJSONArray("providers")
        val providerText = if (providers == null || providers.length() == 0) "未知" else
            (0 until providers.length()).joinToString(", ") { providers.optString(it) }
        sb.appendLine("【ORT 编译期后端】$providerText")
        sb.appendLine()
        val books = json.optJSONArray("backends")
        if (books != null && books.length() > 0) {
            sb.appendLine("【实机探测】探针 ${json.optString("probe_model").substringAfterLast('/')}")
            for (i in 0 until books.length()) {
                val b = books.getJSONObject(i)
                val mark = if (b.optBoolean("ok")) "✔" else "✘"
                val extra = if (b.optBoolean("ok")) "${b.optInt("ms")}ms" else b.optString("error")
                sb.appendLine("  $mark ${b.optString("label")} — $extra")
            }
        } else {
            sb.appendLine(json.optString("note"))
        }
        val npu = json.optJSONObject("npu")
        if (npu != null) {
            sb.appendLine()
            sb.appendLine("【NPU 接口】")
            sb.appendLine("  芯片：${npu.optString("soc")}（${npu.optString("hardware")}）")
            sb.appendLine("  对应接口：${npu.optString("api")}")
            val vendor = npu.optJSONArray("vendor_libs")
            if (vendor != null && vendor.length() > 0) {
                sb.appendLine("  设备侧加速库：")
                for (i in 0 until vendor.length()) sb.appendLine("    ${vendor.getString(i)}")
            } else {
                sb.appendLine("  设备侧加速库：未发现（或无权读取 /vendor）")
            }
        }
        val libs = json.optJSONArray("libs")
        sb.appendLine()
        if (libs != null && libs.length() > 0) {
            sb.appendLine("【厂商 NPU/DSP 库】")
            for (i in 0 until libs.length()) sb.appendLine("  ${libs.getString(i)}")
            sb.appendLine()
            sb.appendLine("提示：检测到厂商加速库，但当前 ORT 包未编入对应 EP（如 QNN），")
            sb.appendLine("      需要替换成带该 EP 的 libonnxruntime.so 才能真正调用 NPU。")
        } else {
            sb.appendLine("【厂商 NPU/DSP 库】未发现（或无权读取 /vendor）")
        }
        return sb.toString()
    }
}
