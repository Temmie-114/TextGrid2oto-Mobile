package com.tg2oto.mobile.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

object StorageAccess {

    fun hasAllFiles(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else true

    fun requestAllFiles(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }.onFailure {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    /** SAF 的 tree URI → 真实路径（externalstorage provider 的常见映射）。 */
    fun treeToPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val split = docId.split(":", limit = 2)
        val volume = split[0]
        val rel = split.getOrElse(1) { "" }
        val base = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory().absolutePath
            "home" -> "${Environment.getExternalStorageDirectory().absolutePath}/Documents"
            else -> "/storage/$volume"
        }
        return if (rel.isBlank()) base else "$base/$rel"
    }
}

@Composable
fun rememberFolderPicker(onPicked: (String) -> Unit): androidx.activity.result.ActivityResultLauncher<Uri?> {
    val ctx = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val path = StorageAccess.treeToPath(uri)
        onPicked(path ?: uri.toString())
    }
    }
}

@Composable
fun rememberFilePathPicker(onPicked: (String) -> Unit): androidx.activity.result.ActivityResultLauncher<Array<String>> {
    val ctx = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 文件类 URI 只能拿到 content://，复制到应用私有目录供 Python 使用
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val dir = java.io.File(ctx.filesDir, "picked").apply { mkdirs() }
        val out = java.io.File(dir, name)
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { o -> input.copyTo(o) }
            }
            onPicked(out.absolutePath)
        }.onFailure { onPicked(uri.toString()) }
    }
    }
}

@Composable
fun rememberFilePicker(mime: String, onPicked: (Uri) -> Unit) = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri -> if (uri != null) onPicked(uri) }
