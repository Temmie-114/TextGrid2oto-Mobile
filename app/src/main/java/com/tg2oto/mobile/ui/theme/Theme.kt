package com.tg2oto.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.tg2oto.mobile.data.DarkMode

@Composable
fun AppTheme(
    seed: Color,
    darkMode: DarkMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (darkMode) {
        DarkMode.SYSTEM -> systemDark
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val context = LocalContext.current
    // 缓存配色方案：否则每次重组都会重新算一整套 colorScheme
    val scheme = remember(seed, dark, dynamicColor) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            schemeFromSeed(seed, dark)
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
