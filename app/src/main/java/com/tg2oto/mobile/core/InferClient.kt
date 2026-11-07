package com.tg2oto.mobile.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * 主进程侧的推理进程客户端。
 *
 * 绑定 `:infer` 进程里的 [InferService]，把日志 / 进度 / 结果转发给界面，
 * 并在服务意外死亡（OOM、native crash、被系统杀死）时回调 [onCrash]。
 */
class InferClient(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onProgress: (Float) -> Unit,
    private val onResult: (String) -> Unit,
    private val onCrash: (String) -> Unit
) {

    private var remote: Messenger? = null
    private var bound = false
    private var pendingPayload: String? = null
    private var expectingResult = false

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            InferService.MSG_LOG -> onLog(msg.data?.getString(InferService.KEY_TEXT).orEmpty())
            InferService.MSG_PROGRESS -> onProgress(msg.data?.getFloat(InferService.KEY_PROGRESS) ?: 0f)
            InferService.MSG_RESULT -> {
                expectingResult = false
                onResult(msg.data?.getString(InferService.KEY_RESULT).orEmpty())
            }
            InferService.MSG_CRASH -> {
                expectingResult = false
                onCrash(msg.data?.getString(InferService.KEY_TEXT).orEmpty())
            }
            else -> return@Handler false
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
            bound = true
            pendingPayload?.let { payload ->
                pendingPayload = null
                sendRun(payload)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 推理进程已经死了（崩溃被系统清理 / OOM / 手动杀）
            remote = null
            bound = false
            val wasRunning = expectingResult
            expectingResult = false
            // 空闲时被系统回收是正常现象，只有任务执行中掉线才需要提醒用户
            if (wasRunning) {
                onCrash(
                    buildString {
                        append("推理进程在执行任务时退出（可能是内存不足、native 崩溃或被系统回收）")
                        append('\n')
                        val file = InferService.crashFile(context.filesDir)
                        if (file.exists()) {
                            append("\n--- 崩溃堆栈 ---\n")
                            append(runCatching { file.readText() }.getOrDefault(""))
                        }
                    }
                )
            }
        }
    }

    fun bind() {
        if (bound) return
        val intent = Intent(context, InferService::class.java)
        runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { onLog("✘ 无法启动推理进程：${it.message}") }
    }

    fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        bound = false
        remote = null
    }

    fun run(payload: String) {
        expectingResult = true
        bind()
        val messenger = remote
        if (messenger == null) {
            pendingPayload = payload
            return
        }
        sendRun(payload, messenger)
    }

    private fun sendRun(payload: String) {
        val messenger = remote ?: run {
            pendingPayload = payload
            return
        }
        sendRun(payload, messenger)
    }

    private fun sendRun(payload: String, messenger: Messenger) {
        runCatching {
            messenger.send(Message.obtain(null, InferService.MSG_RUN).apply {
                data = Bundle().apply { putString(InferService.KEY_PAYLOAD, payload) }
                replyTo = incoming
            })
        }.onFailure {
            expectingResult = false
            onLog("✘ 发送任务失败：${it.message}")
        }
    }

    fun cancel() {
        val messenger = remote ?: return
        runCatching {
            messenger.send(Message.obtain(null, InferService.MSG_CANCEL))
        }
    }
}
