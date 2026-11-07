package com.tg2oto.mobile.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import android.os.SystemClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import com.tg2oto.mobile.ui.i18n.L

// 取色器只负责色相与彩度，产出的 seed 明度固定在中调，
// 具体明暗全部交给 M3 的 tone 派生。
private const val SEED_VALUE = 0.62f

private fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val x = c * (1f - kotlin.math.abs(((h / 60f) % 2f) - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

private fun colorToHsv(color: Color): Triple<Float, Float, Float> {
    val r = color.red; val g = color.green; val b = color.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val d = max - min
    val v = max
    val s = if (max == 0f) 0f else d / max
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return Triple(if (h < 0) h + 360f else h, s, v)
}

/**
 * 圆环取色器：外环选色相，拖到内圈调饱和度，下面滑条调亮度。
 *
 * 拖动过程中只更新本地状态（Canvas 重绘），松手才回调 onColorChange。
 * 之前每次 move 都回调 → 写 DataStore → 主题重组整个 App，这才是卡顿主因。
 */
@Composable
fun RingColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    onColorCommit: ((Color) -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp
) {
    var hue by remember { mutableFloatStateOf(colorToHsv(color).first) }
    var sat by remember { mutableFloatStateOf(colorToHsv(color).second) }
    var dragging by remember { mutableStateOf(false) }

    // 色相环的渐变色只算一次
    val hueColors = remember { (0..360 step 30).map { hsvToColor(it.toFloat() % 360f, 1f, 1f) } }

    // 外部颜色变了（预设、撤销等）且当前没在拖动时，同步到本地
    LaunchedEffect(color) {
        if (!dragging) {
            val h = colorToHsv(color)
            hue = h.first
            sat = h.second
        }
    }

    // 拖动中实时生效：按 40ms 节流提交，避免每个指针事件都触发整棵树重组
    var lastEmit by remember { mutableLongStateOf(0L) }

    fun commit() {
        lastEmit = SystemClock.uptimeMillis()
        val c = hsvToColor(hue, sat, SEED_VALUE)
        (onColorCommit ?: onColorChange)(c)
    }

    fun emitLive() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEmit < 40L) return
        lastEmit = now
        onColorChange(hsvToColor(hue, sat, SEED_VALUE))
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(size),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(size)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                val outer = this@pointerInput.size.width / 2f
                                val center = Offset(outer, this@pointerInput.size.height / 2f)
                                if (change.pressed) {
                                    dragging = true
                                    val dx = change.position.x - center.x
                                    val dy = change.position.y - center.y
                                    val radius = hypot(dx, dy)
                                    val ringInner = outer * 0.78f
                                    if (radius >= ringInner) {
                                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                        if (angle < 0) angle += 360f
                                        hue = angle
                                        emitLive()
                                    } else if (radius > 0f) {
                                        sat = (radius / ringInner).coerceIn(0f, 1f)
                                        emitLive()
                                    }
                                    change.consume()
                                } else if (dragging) {
                                    dragging = false
                                    commit()
                                }
                            }
                        }
                    }
            ) {
                val radius = this.size.width / 2f
                val stroke = 34.dp.toPx()
                drawCircle(
                    brush = Brush.sweepGradient(hueColors),
                    radius = radius - stroke / 2f,
                    center = Offset(radius, radius),
                    style = Stroke(width = stroke)
                )
                drawCircle(
                    color = hsvToColor(hue, sat, SEED_VALUE),
                    radius = radius - stroke - 10.dp.toPx(),
                    center = Offset(radius, radius)
                )
                val angleRad = Math.toRadians(hue.toDouble())
                val knobR = radius - stroke / 2f
                val kx = radius + cos(angleRad).toFloat() * knobR
                val ky = radius + sin(angleRad).toFloat() * knobR
                drawCircle(color = Color.White, radius = 9.dp.toPx(), center = Offset(kx, ky))
                drawCircle(color = hsvToColor(hue, 1f, 1f), radius = 6.dp.toPx(), center = Offset(kx, ky))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PRESET_COLORS.forEach { preset ->
                val selected = preset.toArgb() == color.toArgb()
                Box(
                    modifier = Modifier
                        .size(if (selected) 34.dp else 28.dp)
                        .clip(CircleShape)
                        .background(preset),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(if (selected) 34.dp else 28.dp)
                            .clickable {
                                val h = colorToHsv(preset)
                                hue = h.first
                                sat = h.second
                                (onColorCommit ?: onColorChange)(preset)
                            }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            L.s("拖外环选色相，拖内圈调饱和度；明暗由 M3 按 tone 生成，不随种子色变化"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

val PRESET_COLORS = listOf(
    Color(0xFF6750A4),
    Color(0xFF0061A4),
    Color(0xFF006D3B),
    Color(0xFF9A4C00),
    Color(0xFFB3261E),
    Color(0xFF7D5260),
    Color(0xFF49454F)
)
