package com.tg2oto.mobile.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 让 Python 用上 Kotlin 侧的 ONNX Runtime。
 *
 * 起因：Chaquopy 的 Python 里没有 onnxruntime（Android 上拿不到可用的 wheel），
 * rmvpe_infer.py 的 `ort` 恒为 None，构造 RMVPE 时抛 RuntimeError，
 * 被外层 try 吞成一条「生成失败」，.frq 一个也写不出来。
 * 现在 Python 只做 mel 前端和 cents 后处理，中间那次 session.run 走这里。
 *
 * 张量用 float32 小端二进制文件交换（numpy.tofile 在 arm64 上就是小端），
 * 避免把 128×T 的数组逐元素塞进 Java 数组。
 */
object RmvpeBridge {

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName = ""
    private var loadedPath = ""

    /** 加载模型；同路径重复调用直接复用。 */
    fun load(modelPath: String, threads: Int) {
        synchronized(lock) {
            if (session != null && loadedPath == modelPath) return
            closeQuietly()
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads.coerceAtLeast(1))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = e.createSession(modelPath, opts)
            env = e
            session = s
            inputName = s.inputNames.first()
            loadedPath = modelPath
        }
    }

    /**
     * melPath: [1, bins, frames] float32 LE
     * outPath:  [1, frames, classes] float32 LE
     */
    fun runToFile(melPath: String, outPath: String, bins: Int, frames: Int) {
        synchronized(lock) {
            val s = session ?: throw IllegalStateException("RMVPE 模型尚未加载")
            val e = env ?: throw IllegalStateException("ONNX Runtime 环境未初始化")
            val f = File(melPath)
            val need = bins.toLong() * frames * 4L
            require(f.length() >= need) {
                "mel 张量只有 ${f.length()} 字节，按 ${bins}x${frames} 至少需要 $need"
            }
            val buf = ByteBuffer.wrap(f.readBytes(), 0, need.toInt())
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
            val tensor = OnnxTensor.createTensor(
                e, buf, longArrayOf(1, bins.toLong(), frames.toLong())
            )
            try {
                s.run(mapOf(inputName to tensor)).use { result ->
                    val out = result[0] as OnnxTensor
                    val fb = out.floatBuffer
                    val bb = ByteBuffer.allocate(fb.remaining() * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asFloatBuffer().put(fb)
                    File(outPath).writeBytes(bb.array())
                }
            } finally {
                tensor.close()
            }
        }
    }

    fun release() {
        synchronized(lock) { closeQuietly() }
    }

    private fun closeQuietly() {
        runCatching { session?.close() }
        session = null
        loadedPath = ""
        inputName = ""
        // OrtEnvironment 是进程级单例，不去 close，交给系统回收
    }
}
