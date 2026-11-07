package com.tg2oto.mobile.core

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors

/**
 * 推理进程（`android:process=":infer"`）。
 *
 * 单独进程的好处：
 *  - ONNX Runtime + Python + 200MB 模型的内存峰值与 UI 进程隔离，
 *    即使 OOM 或被系统杀死，界面进程也不会一起崩；
 *  - 崩溃/异常可以清空重来，不必重启整个 App。
 *
 * 与主进程的全部通信走 Messenger：
 *  RUN(1) / CANCEL(2) → 服务
 *  LOG(3) / PROGRESS(4) / RESULT(5) / CRASH(6) → 主进程
 */
class InferService : Service() {

    companion object {
        const val MSG_RUN = 1
        const val MSG_CANCEL = 2
        const val MSG_LOG = 3
        const val MSG_PROGRESS = 4
        const val MSG_RESULT = 5
        const val MSG_CRASH = 6
        const val PROBE_KIND = "probe"
        const val BENCH_KIND = "bench"

        const val KEY_PAYLOAD = "payload"
        const val KEY_TEXT = "text"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT = "result"

        const val CRASH_FILE = "infer_crash.txt"

        fun crashFile(dir: File): File = File(dir, CRASH_FILE)
    }

    private var client: Messenger? = null
    private var running = false
    private val executor = Executors.newSingleThreadExecutor()

    private val handler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_RUN -> {
                val payload = msg.data?.getString(KEY_PAYLOAD).orEmpty()
                client = msg.replyTo
                if (running) {
                    sendLog("⚠ 推理进程正忙，忽略本次请求")
                } else {
                    running = true
                    executor.execute { executeJob(payload) }
                }
                true
            }
            MSG_CANCEL -> {
                JobControl.cancel()
                runCatching { PythonBridge.requestCancel(this) }
                sendLog("⏹ 收到中止请求")
                true
            }
            else -> false
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        // 未捕获异常（Kotlin/Java/PyObject 抛出的）先落盘，主进程绑定断开时读取
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val text = buildString {
                append("线程：").append(thread.name).append('\n')
                append("异常：").append(error.javaClass.name).append(": ").append(error.message).append('\n')
                append(sw.toString())
            }
            runCatching { crashFile(filesDir).writeText(text) }
            runCatching {
                client?.send(Message.obtain(null, MSG_CRASH).apply {
                    data = Bundle().apply { putString(KEY_TEXT, text) }
                })
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun executeJob(payload: String) {
        runCatching { crashFile(filesDir).delete() }
        val parsed = runCatching { org.json.JSONObject(payload) }.getOrNull()
        // 探测是同步的短任务，不经过 Python
        if (parsed?.optString("kind") == PROBE_KIND) {
            val report = runCatching { BackendProbe.report(this) }
                .getOrElse { "探测失败：${it.message}" }
            sendLog(report)
            runCatching {
                client?.send(Message.obtain(null, MSG_RESULT).apply {
                    data = Bundle().apply {
                        putString(KEY_RESULT, """{"ok":true,"probe":${org.json.JSONObject.quote(report)}}""")
                    }
                })
            }
            running = false
            return
        }
        // 真实模型基准测试：用固定输入把各后端跑一遍，看是否真加速
        if (parsed?.optString("kind") == BENCH_KIND) {
            val report = runCatching {
                BackendProbe.bench(
                    parsed.optString("model"),
                    parsed.optInt("samples", 32000),
                    parsed.optInt("runs", 3)
                )
            }.getOrElse { "基准测试失败：${it.message}" }
            sendLog(report)
            runCatching {
                client?.send(Message.obtain(null, MSG_RESULT).apply {
                    data = Bundle().apply {
                        putString(KEY_RESULT, """{"ok":true,"bench":${org.json.JSONObject.quote(report)}}""")
                    }
                })
            }
            running = false
            return
        }
        // 主进程选定的后端随任务带进来，推理进程据此建 session
        parsed?.optString("backend")?.takeIf { it.isNotBlank() }?.let {
            runCatching { OrtEngine.setBackend(it) }
        }
        val result = try {
            JobControl.reset()
            PythonBridge.ensureStarted(this)
            val sink = LogSink(
                onLog = { line -> sendLog(line) },
                onProgress = { p -> sendProgress(p) }
            )
            PythonBridge.setSink(sink)
            PythonBridge.runJobRaw(this, payload)
        } catch (e: Throwable) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            sendLog("✘ 推理进程异常：${e.javaClass.simpleName}: ${e.message}")
            sendLog(sw.toString())
            """{"ok":false,"error":"${e.message?.replace("\"", "'") ?: "unknown"}"}"""
        } finally {
            running = false
        }
        runCatching {
            client?.send(Message.obtain(null, MSG_RESULT).apply {
                data = Bundle().apply { putString(KEY_RESULT, result) }
            })
        }
    }

    private fun sendLog(text: String) {
        runCatching {
            client?.send(Message.obtain(null, MSG_LOG).apply {
                data = Bundle().apply { putString(KEY_TEXT, text) }
            })
        }
    }

    private fun sendProgress(value: Float) {
        runCatching {
            client?.send(Message.obtain(null, MSG_PROGRESS).apply {
                data = Bundle().apply { putFloat(KEY_PROGRESS, value) }
            })
        }
    }

    override fun onDestroy() {
        runCatching { JobControl.cancel() }
        executor.shutdownNow()
        super.onDestroy()
    }
}
