package com.tg2oto.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs

/**
 * 由单一 seed 色生成 M3 风格的完整配色。
 *
 * Compose Material3 没有公开 tone palette API，这里用 HSL 近似 HCT：
 * 保持 seed 的色相，用感知化亮度（tone 0–100）逐级取色，并对低饱和区域
 * 做色度衰减，得到与 Material Theme Builder 接近的观感。
 */
private fun Color.toHsl(): FloatArray {
    val argb = toArgb()
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf(if (h < 0) h + 360f else h, s, l)
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f)
    )
}

class TonalPalette(
    private val hue: Float,
    private val chroma: Float,
    private val dark: Boolean
) {
    fun tone(tone: Float, chromaScale: Float = 1f, hueShift: Float = 0f): Color {
        val h = (hue + hueShift + 360f) % 360f
        // M3 的 surface 系列是低饱和的，容器色比主色稍淡
        val s = (chroma * chromaScale).coerceIn(0f, 1f)
        val l = (tone.coerceIn(0f, 100f) / 100f).coerceIn(0f, 1f)
        return hsl(h, s, if (dark) l else l)
    }
}

fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val hsl = seed.toHsl()
    val hue = hsl[0]
    val sat = hsl[1]
    // seed 只提供色相与彩度；明暗完全由 tone 决定，seed 的亮度不参与，
    // 否则把亮 seed 的偏差带进 surface，白色表面也会跟着变灰。
    val p = TonalPalette(hue, sat, dark)
    val s = TonalPalette(hue, sat * 0.55f, dark)
    val t = TonalPalette((hue + 60f) % 360f, sat * 0.7f, dark)
    val n = TonalPalette(hue, sat * 0.10f, dark)

    return if (!dark) {
        lightColorScheme(
            primary = p.tone(40f),
            onPrimary = p.tone(100f, 0.2f),
            primaryContainer = p.tone(90f, 0.55f),
            onPrimaryContainer = p.tone(10f, 0.8f),
            inversePrimary = p.tone(80f, 0.5f),

            secondary = s.tone(40f),
            onSecondary = s.tone(100f, 0.2f),
            secondaryContainer = s.tone(90f, 0.5f),
            onSecondaryContainer = s.tone(10f, 0.7f),

            tertiary = t.tone(40f),
            onTertiary = t.tone(100f, 0.2f),
            tertiaryContainer = t.tone(90f, 0.5f),
            onTertiaryContainer = t.tone(10f, 0.7f),

            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),

            background = n.tone(98f, 0.6f),
            onBackground = n.tone(10f, 0.6f),
            surface = n.tone(98f, 0.6f),
            onSurface = n.tone(10f, 0.6f),
            surfaceVariant = n.tone(92f, 1.2f),
            onSurfaceVariant = n.tone(30f, 0.9f),
            surfaceTint = p.tone(40f),
            inverseSurface = n.tone(20f, 0.5f),
            inverseOnSurface = n.tone(95f, 0.5f),
            outline = n.tone(52f, 1.2f),
            outlineVariant = n.tone(84f, 1.0f),
            scrim = Color(0xFF000000),

            surfaceBright = n.tone(99f, 0.6f),
            surfaceDim = n.tone(90f, 0.8f),
            surfaceContainerLowest = n.tone(100f, 0.4f),
            surfaceContainerLow = n.tone(96f, 0.7f),
            surfaceContainer = n.tone(94f, 0.9f),
            surfaceContainerHigh = n.tone(92f, 1.0f),
            surfaceContainerHighest = n.tone(90f, 1.1f)
        )
    } else {
        darkColorScheme(
            primary = p.tone(80f),
            onPrimary = p.tone(20f, 0.6f),
            primaryContainer = p.tone(30f, 0.7f),
            onPrimaryContainer = p.tone(90f, 0.5f),
            inversePrimary = p.tone(40f),

            secondary = s.tone(80f),
            onSecondary = s.tone(20f, 0.4f),
            secondaryContainer = s.tone(30f, 0.6f),
            onSecondaryContainer = s.tone(90f, 0.4f),

            tertiary = t.tone(80f),
            onTertiary = t.tone(20f, 0.4f),
            tertiaryContainer = t.tone(30f, 0.6f),
            onTertiaryContainer = t.tone(90f, 0.4f),

            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC),

            background = n.tone(6f, 0.5f),
            onBackground = n.tone(90f, 0.5f),
            surface = n.tone(6f, 0.5f),
            onSurface = n.tone(90f, 0.5f),
            surfaceVariant = n.tone(30f, 1.0f),
            onSurfaceVariant = n.tone(80f, 0.8f),
            surfaceTint = p.tone(80f),
            inverseSurface = n.tone(90f, 0.5f),
            inverseOnSurface = n.tone(20f, 0.5f),
            outline = n.tone(60f, 1.2f),
            outlineVariant = n.tone(30f, 1.0f),
            scrim = Color(0xFF000000),

            surfaceBright = n.tone(24f, 0.8f),
            surfaceDim = n.tone(6f, 0.5f),
            surfaceContainerLowest = n.tone(4f, 0.4f),
            surfaceContainerLow = n.tone(10f, 0.6f),
            surfaceContainer = n.tone(12f, 0.7f),
            surfaceContainerHigh = n.tone(17f, 0.8f),
            surfaceContainerHighest = n.tone(22f, 0.9f)
        )
    }
}
