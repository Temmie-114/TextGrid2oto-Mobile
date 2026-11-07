package com.tg2oto.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tg2oto_settings")

enum class DarkMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val seedColor: Int = 0xFF6750A4.toInt(),
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val lastLabDir: String = "",
    val lastAlignDir: String = "",
    val lastJsonDir: String = "",
    val lastOtoDir: String = "",
    val selectedModelId: String = "",
    val backend: String = "auto",
    val lastDir: String = "",      // 跨工作流共享的「最近一次选择的文件夹」
    val presampPath: String = "",   // 自定义 presamp.ini 的绝对路径（空 = 使用内置）
    val language: String = ""       // "" = 跟随系统；"zh" / "en"
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val seed = longPreferencesKey("seed_color")
        val dark = intPreferencesKey("dark_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val labDir = stringPreferencesKey("last_lab_dir")
        val alignDir = stringPreferencesKey("last_align_dir")
        val jsonDir = stringPreferencesKey("last_json_dir")
        val otoDir = stringPreferencesKey("last_oto_dir")
        val modelId = stringPreferencesKey("selected_model")
        val backend = stringPreferencesKey("infer_backend")
        val sharedDir = stringPreferencesKey("shared_dir")
        val presamp = stringPreferencesKey("presamp_path")
        val language = stringPreferencesKey("app_language")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            seedColor = (p[Keys.seed] ?: 0xFF6750A4) .toInt(),
            darkMode = DarkMode.entries.getOrElse(p[Keys.dark] ?: 0) { DarkMode.SYSTEM },
            dynamicColor = p[Keys.dynamic] ?: false,
            lastLabDir = p[Keys.labDir] ?: "",
            lastAlignDir = p[Keys.alignDir] ?: "",
            lastJsonDir = p[Keys.jsonDir] ?: "",
            lastOtoDir = p[Keys.otoDir] ?: "",
            selectedModelId = p[Keys.modelId] ?: "",
            backend = p[Keys.backend] ?: "auto",
            lastDir = p[Keys.sharedDir] ?: "",
            presampPath = p[Keys.presamp] ?: "",
            language = p[Keys.language] ?: ""
        )
    }

    suspend fun setSeed(color: Int) = context.dataStore.edit { it[Keys.seed] = color.toLong() }

    suspend fun setDarkMode(mode: DarkMode) = context.dataStore.edit { it[Keys.dark] = mode.ordinal }

    suspend fun setBackend(id: String) = context.dataStore.edit { it[Keys.backend] = id }

    suspend fun setLastDir(path: String) = context.dataStore.edit { it[Keys.sharedDir] = path }

    suspend fun setPresampPath(path: String) = context.dataStore.edit { it[Keys.presamp] = path }

    suspend fun setLanguage(v: String) = context.dataStore.edit { it[Keys.language] = v }

    suspend fun setDynamic(enabled: Boolean) = context.dataStore.edit { it[Keys.dynamic] = enabled }

    suspend fun setLabDir(path: String) = context.dataStore.edit { it[Keys.labDir] = path }

    suspend fun setAlignDir(path: String) = context.dataStore.edit { it[Keys.alignDir] = path }

    suspend fun setJsonDir(path: String) = context.dataStore.edit { it[Keys.jsonDir] = path }

    suspend fun setOtoDir(path: String) = context.dataStore.edit { it[Keys.otoDir] = path }

    suspend fun setModelId(id: String) = context.dataStore.edit { it[Keys.modelId] = id }
}
