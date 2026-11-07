package com.tg2oto.mobile.core

import android.content.Context
import android.net.Uri
import java.io.File

data class DictFile(
    val name: String,
    val file: File,
    val builtIn: Boolean
) {
    val path: String get() = file.absolutePath
}

/**
 * 字典库：内置字典（Python 包内 dictionary/）+ 用户导入的 txt。
 *
 * 「两段式字典」格式与项目一致：每行 `音素<TAB>音素序列`，例如 `lue	zh/l zh/ve`。
 */
object DictionaryManager {

    fun root(context: Context): File = File(context.filesDir, "dictionaries").apply { mkdirs() }

    fun imported(context: Context): List<DictFile> =
        root(context).listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?.map { DictFile(it.nameWithoutExtension, it, false) }
            ?: emptyList()

    /** 校验：必须能按 UTF-8/GBK 读出若干行，且是「两段式」结构。 */
    private fun looksLikeDictionary(file: File): Boolean {
        val head = runCatching { file.readLines().take(50) }.getOrNull() ?: return false
        val meaningful = head.filter { it.isNotBlank() && !it.startsWith("#") }
        if (meaningful.isEmpty()) return false
        val tabbed = meaningful.count { it.contains('\t') || it.split(Regex("\\s+")).size >= 2 }
        return tabbed >= meaningful.size * 0.6
    }

    suspend fun import(context: Context, uri: Uri, displayName: String?): DictFile {
        val target = File(root(context), (displayName ?: "dict").ensureTxt())
        var index = 2
        var finalFile = target
        while (finalFile.exists()) {
            finalFile = File(root(context), "${target.nameWithoutExtension}_$index.txt")
            index++
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            finalFile.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalMessage("无法读取所选文件")

        if (finalFile.length() > 80L * 1024 * 1024) {
            finalFile.delete()
            throw IllegalMessage("字典文件过大（>80MB）")
        }
        if (!looksLikeDictionary(finalFile)) {
            finalFile.delete()
            throw IllegalMessage("这个 txt 不像两段式字典：每行应为「音素<TAB>音素序列」")
        }
        return DictFile(finalFile.nameWithoutExtension, finalFile, false)
    }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    private fun String.ensureTxt(): String =
        if (endsWith(".txt", ignoreCase = true)) this else "$this.txt"
}
