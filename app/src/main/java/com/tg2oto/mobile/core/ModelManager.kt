package com.tg2oto.mobile.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

data class ModelEntry(
    val id: String,
    val name: String,
    val type: String,          // hubertfa / tifa / game
    val dir: File,
    val builtIn: Boolean,
    val sizeBytes: Long,
    val precision: String = "unknown"   // fp32 / fp16 / int8
)

/**
 * 模型管理：内置模型首次启动时从 assets 复制到内部存储（ONNX Runtime 需要
 * 真实文件路径），并支持导入用户自己的 onnx 模型文件夹（zip）。
 */
object ModelManager {

    private const val BUILTIN_MARKER = ".builtin_v1"
    private const val META_FILE = ".tg2oto_precision"

    fun modelsRoot(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun isReady(context: Context): Boolean =
        File(modelsRoot(context), BUILTIN_MARKER).exists()

    /** 把 assets 里的内置模型复制出来（幂等，可显示进度）。 */
    suspend fun installBuiltin(
        context: Context,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val root = modelsRoot(context)
        val marker = File(root, BUILTIN_MARKER)
        if (marker.exists()) {
            onProgress(1f, "内置模型已就绪")
            return@withContext true
        }
        val assets = context.assets
        val dirs = assets.list("models") ?: emptyArray()
        var total = 0L
        val files = ArrayList<Triple<String, String, Long>>()
        fun walk(path: String, name: String) {
            val children = assets.list(path) ?: return
            for (child in children) {
                val childPath = "$path/$child"
                val childName = if (name.isEmpty()) child else "$name/$child"
                val sub = assets.list(childPath) ?: emptyArray()
                if (sub.isNotEmpty()) {
                    walk(childPath, childName)
                } else {
                    val size = runCatching {
                        assets.openFd(childPath).use { it.length }
                    }.getOrDefault(0L)
                    files.add(Triple(childPath, childName, size))
                    total += size
                }
            }
        }
        walk("models", "")

        var copied = 0L
        for ((assetPath, relPath, size) in files) {
            val target = File(root, relPath)
            if (size > 0 && target.exists() && target.length() == size) {
                copied += size
                continue
            }
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            copied += size
            val pct = if (total > 0) copied.toFloat() / total else 0f
            onProgress(pct, relPath)
        }
        marker.writeText("ok")
        onProgress(1f, "内置模型安装完成")
        true
    }

    fun list(context: Context): List<ModelEntry> {
        val root = modelsRoot(context)
        val out = ArrayList<ModelEntry>()
        val candidates = ArrayList<File>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { level1 ->
            if (detect(level1) != null) {
                candidates.add(level1)
            } else {
                level1.listFiles()?.filter { it.isDirectory }?.forEach { level2 ->
                    if (detect(level2) != null) candidates.add(level2)
                }
            }
        }
        for (dir in candidates.distinctBy { it.absolutePath }.sortedBy { it.name }) {
            val info = detect(dir) ?: continue
            out.add(
                ModelEntry(
                    id = dir.absolutePath,
                    name = dir.name,
                    type = info,
                    dir = dir,
                    builtIn = !dir.absolutePath.contains("/imported/"),
                    sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    precision = precisionOf(dir)
                )
            )
        }
        return out
    }

    /** 判断一个目录属于哪种模型；不是模型目录返回 null。 */
    fun detect(dir: File): String? {
        val files = dir.listFiles()?.map { it.name } ?: return null
        return when {
            files.contains("model.onnx") && files.contains("spectrogram.onnx") -> "tifa"
            files.contains("model.onnx") && files.contains("vocab.json") -> "hubertfa"
            files.contains("model.onnx") && files.contains("vocabulary.json") -> "tifa"
            files.contains("encoder.onnx") && files.contains("segmenter.onnx") -> "game"
            else -> null
        }
    }

    /**
     * 导入用户模型（zip）。支持两种结构：
     *  1) zip 顶层直接就是模型文件
     *  2) zip 顶层是一个模型文件夹（允许多套）
     */
    suspend fun importZip(
        context: Context,
        zipFile: File,
        zipName: String,
        onProgress: (String) -> Unit
    ): List<ModelEntry> = withContext(Dispatchers.IO) {
        val root = File(modelsRoot(context), "imported").apply { mkdirs() }
        val staging = File(root, ".staging_${System.currentTimeMillis()}").apply { mkdirs() }

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name
                if (!name.contains("__MACOSX") && !name.startsWith(".")) {
                    val target = File(staging, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zis.copyTo(out, 1 shl 20) }
                    }
                    count++
                    if (count % 5 == 0) onProgress("解压 $name")
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 找出所有模型目录（可能是 staging 本身，或其直接子目录）
        val candidates = ArrayList<File>()
        if (detect(staging) != null) candidates.add(staging)
        staging.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            if (detect(child) != null) candidates.add(child)
            else child.listFiles()?.filter { it.isDirectory }?.forEach { grand ->
                if (detect(grand) != null) candidates.add(grand)
            }
        }

        if (candidates.isEmpty()) {
            staging.deleteRecursively()
            throw IllegalMessage("没有在压缩包里找到可识别的模型（需要 model.onnx + vocab.json，或 model.onnx + spectrogram.onnx / vocabulary.json）")
        }

        val imported = ArrayList<ModelEntry>()
        var seq = 0
        for (dir in candidates) {
            // 导入的模型一律按压缩包名字显示，不再用内部文件夹名（内置的两个才显示类型名）
            seq++
            var name = if (seq == 1) zipName else "${zipName}_$seq"
            if (name.isBlank()) name = "imported_model"
            var target = File(root, name)
            var i = 2
            while (target.exists()) {
                target = File(root, "${name}_$i")
                i++
            }
            dir.copyRecursively(target, overwrite = true)
            val type = detect(target) ?: continue
            onProgress("已导入：${target.name}")
            imported.add(
                ModelEntry(target.absolutePath, target.name, type, target, false,
                    target.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    precisionOf(target))
            )
        }
        staging.deleteRecursively()
        imported
    }

    /** 识别模型精度，结果缓存在目录里，避免每次列目录都重扫 200MB。 */
    fun precisionOf(dir: File): String {
        val cache = File(dir, META_FILE)
        if (cache.exists()) {
            val v = runCatching { cache.readText().trim() }.getOrDefault("")
            if (v.isNotBlank()) return v
        }
        val onnx = listOf("model.onnx", "encoder.onnx", "estimator.onnx")
            .map { File(dir, it) }
            .firstOrNull { it.exists() }
            ?: return "unknown"
        val p = OnnxInspector.inspect(onnx)?.precision ?: "unknown"
        runCatching { cache.writeText(p) }
        return p
    }

    fun delete(entry: ModelEntry): Boolean = runCatching { entry.dir.deleteRecursively() }.getOrDefault(false)

    fun describe(context: Context, entry: ModelEntry): JSONObject {
        val json = JSONObject()
        val files = entry.dir.listFiles()?.map { it.name }?.filter { !it.startsWith(".") } ?: emptyList()
        json.put("type", entry.type)
        json.put("name", entry.name)
        json.put("dir", entry.dir.absolutePath)
        json.put("size", entry.sizeBytes)
        json.put("hasVocab", files.contains("vocab.json") || files.contains("vocabulary.json"))
        json.put("dictionaries", JSONArray(files.filter { it.endsWith(".txt") }))
        return json
    }
}

class IllegalMessage(message: String) : Exception(message)
