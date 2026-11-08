package com.tg2oto.mobile.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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

private const val DEFAULT_VALUE = 0.62f
private const val MIN_VALUE = 0.15f

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
 * 圆环取色器：外环选色相，拖内圈调彩度，滑块调种子色明度。
 *
 * 交互要点：
 * 1. 按下时一次性判定这次手势是"选色相"还是"调彩度"，整个手势只改一个参数。
 *    早先按每一帧的半径判断，手指在内圈边界附近抖动会让色相与彩度反复抢焦点，
 *    光标就在环上和中心圈之间来回跳。
 * 2. 拖动过程只更新本地状态重绘 Canvas，不回调外部；松手才提交一次。
 *    onColorChange / onColorCommit 会写 DataStore 并重组整套主题，逐帧回调必卡。
 */
@Composable
fun RingColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    onColorCommit: ((Color) -> Unit)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp
) {
    val init = remember { colorToHsv(color) }
    val hueState = remember { mutableFloatStateOf(init.first) }
    val satState = remember { mutableFloatStateOf(init.second) }
    val valState = remember { mutableFloatStateOf(init.third.coerceAtLeast(MIN_VALUE)) }
    val dragging = remember { mutableStateOf(false) }

    val hueColors = remember { (0..360 step 30).map { hsvToColor(it.toFloat() % 360f, 1f, 1f) } }

    fun current(): Color = hsvToColor(hueState.floatValue, satState.floatValue, valState.floatValue)

    fun commit() {
        (onColorCommit ?: onColorChange)(current())
    }

    // 外部颜色变了（预设、撤销等）且当前没在拖动时，同步到本地
    LaunchedEffect(color) {
        if (!dragging.value) {
            val h = colorToHsv(color)
            hueState.floatValue = h.first
            satState.floatValue = h.second
            valState.floatValue = h.third.coerceAtLeast(MIN_VALUE)
        }
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
                        val halfW = this.size.width / 2f
                        val halfH = this.size.height / 2f
                        val center = Offset(halfW, halfH)
                        val ringInner = halfW * 0.74f

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startRadius = hypot(
                                down.position.x - center.x,
                                down.position.y - center.y
                            )
                            // 只在按下瞬间定一次意图，之后整段手势不再切换
                            val pickHue = startRadius >= ringInner
                            dragging.value = true

                            fun applyAt(p: Offset) {
                                val dx = p.x - center.x
                                val dy = p.y - center.y
                                if (pickHue) {
                                    var angle = Math.toDegrees(
                                        atan2(dy.toDouble(), dx.toDouble())
                                    ).toFloat()
                                    if (angle < 0f) angle += 360f
                                    hueState.floatValue = angle
                                } else {
                                    val radius = hypot(dx, dy)
                                    satState.floatValue = (radius / ringInner).coerceIn(0f, 1f)
                                }
                            }

                            applyAt(down.position)
                            down.consume()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (change == null || !change.pressed) break
                                applyAt(change.position)
                                change.consume()
                            }

                            dragging.value = false
                            commit()
                        }
                    }
            ) {
                val radius = this.size.width / 2f
                val stroke = 34.dp.toPx()
                val innerRadius = radius - stroke - 10.dp.toPx()

                drawCircle(
                    brush = Brush.sweepGradient(hueColors),
                    radius = radius - stroke / 2f,
                    center = Offset(radius, radius),
                    style = Stroke(width = stroke)
                )
                drawCircle(
                    color = hsvToColor(hueState.floatValue, satState.floatValue, valState.floatValue),
                    radius = innerRadius,
                    center = Offset(radius, radius)
                )

                val angleRad = Math.toRadians(hueState.floatValue.toDouble())
                val knobR = radius - stroke / 2f
                val hueX = radius + cos(angleRad).toFloat() * knobR
                val hueY = radius + sin(angleRad).toFloat() * knobR
                drawCircle(color = Color.White, radius = 9.dp.toPx(), center = Offset(hueX, hueY))
                drawCircle(
                    color = hsvToColor(hueState.floatValue, 1f, 1f),
                    radius = 6.dp.toPx(),
                    center = Offset(hueX, hueY)
                )

                // 中心圆本身就是当前种子色预览，不再绘制随拖动移动的彩度指示点
                // （那个点会被误认为可拖拽的把手，实际不参与选取）
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                L.s("明度"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = valState.floatValue,
                onValueChange = {
                    valState.floatValue = it
                    dragging.value = true
                },
                onValueChangeFinished = {
                    dragging.value = false
                    commit()
                },
                valueRange = MIN_VALUE..1f,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(hsvToColor(hueState.floatValue, satState.floatValue, valState.floatValue))
            )
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
                                hueState.floatValue = h.first
                                satState.floatValue = h.second
                                valState.floatValue = h.third.coerceAtLeast(MIN_VALUE)
                                (onColorCommit ?: onColorChange)(preset)
                            }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            L.s("拖外环选色相，拖内圈调彩度，滑块调明度；界面明暗由 M3 按 tone 生成"),
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
