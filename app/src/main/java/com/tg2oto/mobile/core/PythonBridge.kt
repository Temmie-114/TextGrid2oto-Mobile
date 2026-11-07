package com.tg2oto.mobile.core

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

/** Python 日志接收器：Python 侧逐行回调到这里。 */
class LogSink(
    private val onLog: (String) -> Unit,
    private val onProgress: (Float) -> Unit
) {
    fun log(line: String) = onLog(line)

    fun progress(value: Float) = onProgress(value)
}

object JobControl {
    @Volatile
    private var cancelled = false

    @JvmStatic
    fun isCancelled(): Boolean = cancelled

    @JvmStatic
    fun reset() {
        cancelled = false
    }

    @JvmStatic
    fun cancel() {
        cancelled = true
    }
}

object PythonBridge {

    @Volatile
    private var moduleReady = false

    private var runner: com.chaquo.python.PyObject? = null

    @Synchronized
    fun ensureStarted(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        if (!moduleReady) {
            runner = Python.getInstance().getModule("tg2oto.runner")
            moduleReady = true
        }
    }

    fun setSink(sink: LogSink) {
        runner?.callAttr("set_sink", sink)
    }

    /** 只执行任务，不设置 sink（sink 已由调用方通过 [setSink] 注入）。 */
    @Synchronized
    fun runJobRaw(context: Context, payload: String): String {
        ensureStarted(context)
        val mod = runner ?: Python.getInstance().getModule("tg2oto.runner").also { runner = it }
        return mod.callAttr("run_job", payload).toString()
    }

    /** 请求中止当前任务（Python 侧的 _StopEvent 会轮询 JobControl）。 */
    fun requestCancel(context: Context) {
        runCatching {
            ensureStarted(context)
            runner?.callAttr("request_cancel")
        }
    }

    /**
     * 在后台线程执行一个任务，返回 Python 侧的结果 JSON。
     * [payload] 为任务描述（kind + 参数）。
     */
    fun runJob(context: Context, payload: String, sink: LogSink): JSONObject {
        ensureStarted(context)
        val mod = runner ?: Python.getInstance().getModule("tg2oto.runner").also { runner = it }
        mod.callAttr("set_sink", sink)
        JobControl.reset()
        val raw = mod.callAttr("run_job", payload).toString()
        return runCatching { JSONObject(raw) }.getOrElse {
            JSONObject().put("ok", false).put("error", raw)
        }
    }

    /** 读取内置 presamp / 字典清单，供界面下拉使用。 */
    fun resources(context: Context): String {
        return runCatching {
            ensureStarted(context)
            val mod = runner ?: Python.getInstance().getModule("tg2oto.runner").also { runner = it }
            mod.callAttr("resources").toString()
        }.getOrDefault("{}")
    }
}
