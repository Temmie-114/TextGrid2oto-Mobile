package com.tg2oto.mobile.core

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * ONNX Runtime 执行层。
 *
 * Python 侧通过 `tg2oto.ort_shim` 调用本对象，走「二进制文件 + manifest」
 * 协议：既避免 JNI 大数组拷贝，也让 Python 不用安装 onnxruntime（Android
 * 上没有对应 wheel）。
 */
object OrtEngine {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private class Cached(val session: OrtSession, val backend: Backend, val fallback: String?)

    private val sessions = ConcurrentHashMap<String, Cached>()

    /** 当前选择的后端；由任务 payload 注入（推理进程重启后也不会丢）。 */
    @Volatile
    var backend: Backend = Backend.AUTO
        private set

    /** 最近一次真正建成的后端，以及它从哪个后端退回来的。 */
    @Volatile
    var lastBackend: String = "CPU"
        private set

    @Volatile
    var lastFallback: String? = null
        private set

    @JvmStatic
    fun setBackend(id: String?) {
        val next = Backend.of(id)
        if (next == backend) return
        backend = next
        releaseAll()
    }

    @JvmStatic
    fun currentBackend(): String = backend.id

    // 0 = 按设备核数自动决定；>0 时用界面上选的线程数
    @Volatile
    private var threadCount: Int = 0

    @JvmStatic
    fun setThreads(n: Int) {
        val next = if (n > 0) n else 0
        if (next == threadCount) return
        threadCount = next
        releaseAll()
    }

    internal fun options(b: Backend, threads: Int = 4): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
        }
        when (b) {
            Backend.XNNPACK -> opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            Backend.NNAPI -> opts.addNnapi()
            Backend.NNAPI_HW -> opts.addNnapi(
                java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED)
            )
            else -> Unit
        }
        return opts
    }

    /** 探测用：建一个 session 立刻关掉，只关心能不能建起来。 */
    @JvmStatic
    fun probeSession(modelPath: String, backendId: String) {
        val b = Backend.of(backendId)
        if (b == Backend.AUTO) return
        val opts = options(b, 1)
        try {
            env.createSession(modelPath, opts).close()
        } finally {
            runCatching { opts.close() }
        }
    }

    private fun chain(): List<Backend> = when (backend) {
        // 自动模式先试 CPU 加速，不动 NNAPI（它对大模型经常拆图反而更慢）
        Backend.AUTO -> listOf(Backend.XNNPACK, Backend.CPU)
        else -> listOf(backend, Backend.CPU)
    }

    private fun session(modelPath: String): OrtSession {
        val key = "${backend.id}|$modelPath"
        sessions[key]?.let { return it.session }
        return synchronized(this) {
            sessions[key]?.session ?: run {
                val threads = if (threadCount > 0) threadCount
                else Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                var firstError: String? = null
                for (candidate in chain()) {
                    val opts = runCatching { options(candidate, threads) }.getOrNull() ?: continue
                    try {
                        val session = env.createSession(modelPath, opts)
                        val fallback = firstError
                        lastBackend = candidate.name
                        lastFallback = fallback
                        sessions[key] = Cached(session, candidate, fallback)
                        return@run session
                    } catch (e: Throwable) {
                        if (firstError == null) {
                            firstError = "${candidate.label}: ${e.javaClass.simpleName}: ${e.message}"
                        }
                    } finally {
                        runCatching { opts.close() }
                    }
                }
                throw IllegalStateException(
                    "无法创建推理会话，所有后端都失败。${firstError ?: ""}"
                )
            }
        }
    }

    private val providerCache: Array<String> by lazy {
        val list = ArrayList<String>()
        list.add("CPUExecutionProvider")
        runCatching {
            ai.onnxruntime.OrtEnvironment.getAvailableProviders().forEach {
                when (it.name.uppercase()) {
                    "XNNPACK" -> list.add("XnnpackExecutionProvider")
                    "NNAPI" -> list.add("NNAPIExecutionProvider")
                }
            }
        }
        if (backend == Backend.XNNPACK && list.size == 1) list.add("XnnpackExecutionProvider")
        if (backend == Backend.NNAPI_HW && !list.contains("NNAPIExecutionProvider")) {
            list.add("NNAPIExecutionProvider")
        }
        list.toTypedArray()
    }

    @JvmStatic
    fun availableProviders(): Array<String> = providerCache

    /** 返回模型的输入/输出签名，供 Python 侧构造 onnxruntime 的 NodeArg 视图。 */
    @JvmStatic
    fun describe(modelPath: String): String {
        val s = session(modelPath)
        val json = JSONObject()

        val inputs = JSONArray()
        s.inputInfo.forEach { (name, node) ->
            val tensor = node.info as? ai.onnxruntime.TensorInfo
            inputs.put(
                JSONObject().apply {
                    put("name", name)
                    put("shape", JSONArray(tensor?.shape ?: longArrayOf()))
                    put("dtype", typeName(tensor?.type))
                }
            )
        }
        val outputs = JSONArray()
        s.outputInfo.forEach { (name, node) ->
            val tensor = node.info as? ai.onnxruntime.TensorInfo
            outputs.put(
                JSONObject().apply {
                    put("name", name)
                    put("shape", JSONArray(tensor?.shape ?: longArrayOf()))
                    put("dtype", typeName(tensor?.type))
                }
            )
        }
        json.put("inputs", inputs)
        json.put("outputs", outputs)
        return json.toString()
    }

    private fun typeName(type: OnnxJavaType?): String = when (type) {
        OnnxJavaType.FLOAT -> "float32"
        OnnxJavaType.DOUBLE -> "float64"
        OnnxJavaType.INT64 -> "int64"
        OnnxJavaType.INT32 -> "int32"
        OnnxJavaType.BOOL -> "bool"
        OnnxJavaType.FLOAT16 -> "float16"
        OnnxJavaType.UINT8 -> "uint8"
        else -> "float32"
    }

    /**
     * 执行一次推理。meta 文件内容：
     * {"inputs":[{name,shape,dtype,file}],"outputs":[name...],"outDir":"..."}
     * 返回各输出的 {name,shape,dtype,file} 清单。
     */
    @JvmStatic
    fun execute(modelPath: String, metaPath: String): String {
        val meta = JSONObject(File(metaPath).readText(Charsets.UTF_8))
        val outDir = meta.getString("outDir")
        val wanted = meta.getJSONArray("outputs").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }

        val feeds = HashMap<String, OnnxTensor>()
        val tensors = ArrayList<OnnxTensor>()
        try {
            val inputs = meta.getJSONArray("inputs")
            for (i in 0 until inputs.length()) {
                val item = inputs.getJSONObject(i)
                val shape = item.getJSONArray("shape").let { a ->
                    LongArray(a.length()) { a.getLong(it) }
                }
                val dtype = item.getString("dtype")
                val file = File(item.getString("file"))
                val name = item.getString("name")
                val tensor = createTensor(file, shape, dtype)
                tensors.add(tensor)
                feeds[name] = tensor
            }

            val result = session(modelPath).run(feeds, wanted.toSet())
            val manifest = JSONObject()
            val outs = JSONArray()
            result.use { r ->
                for ((name, value) in r) {
                    if (name !in wanted) continue
                    outs.put(writeOutput(name, value, outDir))
                }
            }
            manifest.put("outputs", outs)
            return manifest.toString()
        } finally {
            tensors.forEach { runCatching { it.close() } }
            feeds.values.forEach { runCatching { it.close() } }
        }
    }

    private fun createTensor(file: File, shape: LongArray, dtype: String): OnnxTensor {
        val buffer = ByteBuffer.allocateDirect(file.length().toInt())
        file.inputStream().use { input ->
            val chunk = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffer.put(chunk, 0, read)
            }
        }
        buffer.flip()
        return when (dtype) {
            "float32" -> OnnxTensor.createTensor(env, buffer.asFloatBuffer(), shape)
            "float64" -> OnnxTensor.createTensor(env, buffer.asDoubleBuffer(), shape)
            "int64" -> OnnxTensor.createTensor(env, buffer.asLongBuffer(), shape)
            "int32" -> OnnxTensor.createTensor(env, buffer.asIntBuffer(), shape)
            "bool" -> OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.BOOL)
            else -> OnnxTensor.createTensor(env, buffer.asFloatBuffer(), shape)
        }
    }

    private fun writeOutput(name: String, value: OnnxValue, outDir: String): JSONObject {
        val tensor = value as OnnxTensor
        val shape = tensor.info.shape
        val info = tensor.info
        val file = File(outDir, "out_$name.bin")

        val bytes = when (info.type) {
            OnnxJavaType.FLOAT -> floatBytes(tensor.value)
            OnnxJavaType.INT64 -> longBytes(tensor.value)
            OnnxJavaType.BOOL -> boolBytes(tensor.value)
            OnnxJavaType.INT32 -> int32Bytes(tensor.value)
            else -> floatBytes(tensor.value)
        }
        file.outputStream().use { it.write(bytes) }

        val dtype = when (info.type) {
            OnnxJavaType.FLOAT -> "float32"
            OnnxJavaType.INT64 -> "int64"
            OnnxJavaType.INT32 -> "int32"
            OnnxJavaType.BOOL -> "bool"
            else -> "float32"
        }
        return JSONObject().apply {
            put("name", name)
            put("dtype", dtype)
            put("shape", JSONArray(shape))
            put("file", file.absolutePath)
        }
    }

    private fun floatBytes(value: Any?): ByteArray {
        val out = java.io.ByteArrayOutputStream(4096)
        fun walk(v: Any?) {
            when (v) {
                null -> {}
                is Float -> out.write(leBytes(java.lang.Float.floatToRawIntBits(v).toLong(), 4))
                is Double -> out.write(leBytes(java.lang.Double.doubleToRawLongBits(v), 8))
                is Long -> out.write(leBytes(v, 8))
                is Int -> out.write(leBytes(v.toLong(), 8))
                is Boolean -> out.write(if (v) 1 else 0)
                is FloatArray -> v.forEach { walk(it) }
                is DoubleArray -> v.forEach { walk(it) }
                is LongArray -> v.forEach { walk(it) }
                is Array<*> -> v.forEach { walk(it) }
                is List<*> -> v.forEach { walk(it) }
                is java.nio.FloatBuffer -> while (v.hasRemaining()) out.write(
                    leBytes(java.lang.Float.floatToRawIntBits(v.get()).toLong(), 4)
                )
                is java.nio.LongBuffer -> while (v.hasRemaining()) out.write(leBytes(v.get(), 8))
                else -> {}
            }
        }
        walk(value)
        return out.toByteArray()
    }

    private fun longBytes(value: Any?): ByteArray {
        val out = java.io.ByteArrayOutputStream(4096)
        fun walk(v: Any?) {
            when (v) {
                null -> {}
                is Long -> out.write(leBytes(v, 8))
                is Int -> out.write(leBytes(v.toLong(), 8))
                is LongArray -> v.forEach { walk(it) }
                is Array<*> -> v.forEach { walk(it) }
                is List<*> -> v.forEach { walk(it) }
                is java.nio.LongBuffer -> while (v.hasRemaining()) out.write(leBytes(v.get(), 8))
                else -> {}
            }
        }
        walk(value)
        return out.toByteArray()
    }

    private fun boolBytes(value: Any?): ByteArray {
        val out = java.io.ByteArrayOutputStream(4096)
        fun walk(v: Any?) {
            when (v) {
                null -> {}
                is Boolean -> out.write(if (v) 1 else 0)
                is BooleanArray -> v.forEach { out.write(if (it) 1 else 0) }
                is ByteArray -> v.forEach { out.write(it.toInt()) }
                is Array<*> -> v.forEach { walk(it) }
                is List<*> -> v.forEach { walk(it) }
                else -> {}
            }
        }
        walk(value)
        return out.toByteArray()
    }

    private fun int32Bytes(value: Any?): ByteArray {
        val out = java.io.ByteArrayOutputStream(4096)
        fun walk(v: Any?) {
            when (v) {
                null -> {}
                is Int -> out.write(leBytes(v.toLong(), 4))
                is IntArray -> v.forEach { walk(it) }
                is Array<*> -> v.forEach { walk(it) }
                is List<*> -> v.forEach { walk(it) }
                is java.nio.IntBuffer -> while (v.hasRemaining()) out.write(leBytes(v.get().toLong(), 4))
                else -> {}
            }
        }
        walk(value)
        return out.toByteArray()
    }

    private fun leBytes(v: Long, width: Int): ByteArray {
        val bytes = ByteArray(width)
        var x = v
        for (i in 0 until width) {
            bytes[i] = (x and 0xFF).toByte()
            x = x shr 8
        }
        return bytes
    }

    @JvmStatic
    fun release(modelPath: String) {
        val keys = sessions.keys.filter { it.endsWith("|$modelPath") }
        keys.forEach { k -> sessions.remove(k)?.let { runCatching { it.session.close() } } }
    }

    @JvmStatic
    fun releaseAll() {
        sessions.values.forEach { runCatching { it.session.close() } }
        sessions.clear()
    }
}
