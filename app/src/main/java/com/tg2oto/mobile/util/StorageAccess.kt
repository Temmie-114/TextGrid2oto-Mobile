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

    /**
     * SAF 的 tree URI → 真实路径。只认 externalstorage provider 的卷映射，
     * 其它来源（网盘、第三方文件管理器）返回 null，调用方必须给出明确提示，
     * 否则会把 content:// 交给 Python，在那边变成一个看不懂的路径错误。
     */
    fun treeToPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val split = docId.split(":", limit = 2)
        val volume = split[0]
        val rel = split.getOrElse(1) { "" }
        val base = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory().absolutePath
            "home" -> "${Environment.getExternalStorageDirectory().absolutePath}/Documents"
            else -> {
                // 形如 "XXXX-XXXX" 的可移动卡卷 ID；其它字符串视为不认识的 provider
                if (!volume.matches(Regex("^[A-Za-z0-9]{4}-[A-Za-z0-9]{4}$"))) return null
                "/storage/$volume"
            }
        }
        return if (rel.isBlank()) base else "$base/$rel"
    }

    /** 在私有目录里取得一个不冲突的文件名，避免同名导入互相覆盖。 */
    fun uniqueFile(dir: java.io.File, name: String): java.io.File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var target = java.io.File(dir, name)
        var i = 2
        while (target.exists()) {
            target = java.io.File(dir, "$base($i)$ext")
            i++
        }
        return target
    }
}

@Composable
fun rememberFolderPicker(onPicked: (String) -> Unit): androidx.activity.result.ActivityResultLauncher<Uri?> {
    val ctx = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
        // 有些 provider 只授予读权限，按「读写优先、退了再只要读」的方式申请持久授权
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.recoverCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val path = StorageAccess.treeToPath(uri)
        if (path == null) {
            // 网盘 / 第三方文件管理器等 provider 无法映射成文件系统路径，
            // Python 侧只认真实路径，这里必须让用户知道，而不是静默失败。
            android.widget.Toast.makeText(
                ctx,
                com.tg2oto.mobile.ui.i18n.L.s("该目录来源不受支持，请选择本机存储里的文件夹"),
                android.widget.Toast.LENGTH_LONG
            ).show()
            // 不再把 content:// 回调给下游：传过去只会在更靠后的生成步骤里
            // 报一个看不懂的路径错误，停在这里让用户重选更直接。
        } else {
            onPicked(path)
        }
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
        // 文件类 URI 只能拿到 content://，复制到应用私有目录供 Python 使用。
        // 同名文件不能直接覆盖：用户可能分两次导入同名但内容不同的字典/presamp。
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val dir = java.io.File(ctx.filesDir, "picked").apply { mkdirs() }
        val out = StorageAccess.uniqueFile(dir, name)
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
