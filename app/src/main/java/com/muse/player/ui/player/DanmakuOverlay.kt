package com.muse.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.muse.player.data.subtitle.DanmakuEntry
import com.muse.player.data.subtitle.DanmakuTrack
import kotlin.math.abs

/**
 * 弹幕渲染层。
 *
 * Media3 的 SubtitleView 无法表达「文字在画布上连续滚动」，因此这里自绘：
 * - 用本地时钟在两次播放器进度回调之间插值，避免 500ms 的进度粒度导致卡顿；
 * - 轨道按原始 MarginV 的像素分布还原，而不是均分整屏，避免弹幕盖住画面；
 * - 文字先描一圈黑边再填色，明亮画面下也清晰可读。
 */
@Composable
fun DanmakuOverlay(
    track: DanmakuTrack,
    /** 播放器当前的进度基准值（毫秒） */
    positionMs: Long,
    isPlaying: Boolean,
    /** 播放速度，用于让弹幕跟着变速 */
    speed: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    // 本地时钟：播放器进度每次变化时对齐基准，之后按帧自行推进。
    // 这样即使进度每 500ms 才回调一次，弹幕仍能逐帧平滑移动
    var clockMs by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs) {
        // 差值过大说明发生了跳转（拖动进度条），直接对齐避免弹幕瞬移
        if (abs(positionMs - clockMs) > 250L) clockMs = positionMs
    }
    LaunchedEffect(isPlaying, speed) {
        if (!isPlaying) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameMillis { it }
            if (last != 0L) {
                // 单帧增量封顶，避免切后台回来后弹幕一次性跳跃
                val delta = (now - last).coerceIn(0L, 100L)
                clockMs += (delta * speed).toLong()
            }
            last = now
        }
    }

    val entries = track.entries
    val now = clockMs
    val margins = track.laneMargins

    Canvas(modifier = modifier) {
        if (entries.isEmpty() || size.height <= 0f || size.width <= 0f) return@Canvas

        // 字号按画布高度换算，并限制上下限，保证不同尺寸下都接近系统字幕的观感
        val basePx = (size.height / track.playResY * 46f)
            .coerceIn(11f, size.height * 0.085f)
        val textSize = (basePx / density.density).sp
        val fontSizePx = basePx

        val visible = visibleAt(entries, now)
        if (visible.isEmpty()) return@Canvas

        // 弹幕区域：顶部到 72% 高度，下方留给画面主体
        val regionTop = size.height * 0.02f
        val regionBottom = size.height * 0.72f
        val laneCount = track.laneCount.coerceAtLeast(1)
        val spacing = resolveSpacing(margins, track.playResY, regionTop, regionBottom, laneCount, basePx)

        val outlineWidth = (fontSizePx * 0.09f).coerceAtLeast(1f)
        val shadow = Shadow(
            color = Color(0xCC000000),
            offset = Offset(fontSizePx * 0.04f, fontSizePx * 0.04f),
            blurRadius = fontSizePx * 0.10f
        )
        val cache = HashMap<String, TextLayoutResult>(visible.size)

        visible.forEach { entry ->
            val layout = cache.getOrPut(entry.text) {
                measurer.measure(
                    text = entry.text,
                    style = TextStyle(
                        fontSize = textSize,
                        fontWeight = FontWeight.Medium,
                        color = Color(entry.color),
                        shadow = shadow
                    ),
                    maxLines = 1
                )
            }
            val textWidth = layout.size.width.toFloat()
            val textHeight = layout.size.height.toFloat()

            // 垂直位置：按 MarginV 的顺序从区域内自上而下排布
            val y = regionTop + spacing * entry.lane + (spacing - textHeight) / 2f
            if (y > regionBottom) return@forEach

            val x = if (entry.scrolling) {
                // 车头从右边缘外进入，到完全移出左侧
                val span = entry.durationMs.coerceAtLeast(1L)
                val progress = ((now - entry.startMs).toFloat() / span).coerceIn(0f, 1f)
                size.width - progress * (size.width + textWidth)
            } else {
                (size.width - textWidth) / 2f
            }
            // 完全移出可视区的条目直接跳过
            if (x > size.width || x + textWidth < 0f) return@forEach

            drawDanmakuText(layout, Offset(x, y), entry.color, outlineWidth, fontSizePx)
        }
    }
}

/**
 * 计算轨道间距。
 *
 * 若字幕里 MarginV 有多档，用相邻档的像素差换算到当前画布；但弹幕原文的
 * MarginV 间隔往往很紧（如 40px），直接照搬会让相邻两轨文字贴在一起，
 * 因此行距至少保证为字号的 1.35 倍。只有一档或没有时按弹幕区域均分。
 */
private fun resolveSpacing(
    margins: List<Int>,
    playResY: Int,
    regionTop: Float,
    regionBottom: Float,
    laneCount: Int,
    fontSizePx: Float
): Float {
    val regionHeight = (regionBottom - regionTop).coerceAtLeast(1f)
    // 每行至少要放得下文字，且留出呼吸感
    val minSpacing = fontSizePx * 1.35f
    // 上限：让所有轨道都落在弹幕区域内
    val maxSpacing = regionHeight / laneCount.coerceAtLeast(1)

    if (margins.size >= 2 && playResY > 0) {
        val sorted = margins.sorted()
        val stepPx = (sorted.last() - sorted.first()).toFloat() / (sorted.size - 1)
        val scaled = stepPx / playResY * regionHeight / 0.7f
        // 在「最小行距」与「不超出区域」之间取值
        return scaled.coerceIn(minSpacing, maxOf(minSpacing, maxSpacing))
    }
    return maxOf(minSpacing, maxSpacing)
}

/**
 * 绘制一条弹幕：先用描边画出黑色外框，再填充正文颜色。
 * Compose 的 drawText 支持 DrawStyle，可直接用 Stroke 得到真正的描边而不是多次偏移。
 */
private fun DrawScope.drawDanmakuText(
    layout: TextLayoutResult,
    topLeft: Offset,
    argb: Int,
    outlineWidth: Float,
    fontSizePx: Float
) {
    drawText(
        textLayoutResult = layout,
        topLeft = topLeft,
        color = Color.Black,
        drawStyle = Stroke(width = outlineWidth * 2f, miter = fontSizePx * 0.6f)
    )
    drawText(
        textLayoutResult = layout,
        topLeft = topLeft,
        color = Color(argb)
    )
}

/**
 * 取出当前时间点可见的弹幕。
 *
 * 条目按 [DanmakuEntry.startMs] 升序，先二分找到最后一个已开始的条目，
 * 再向前回溯收集仍在有效期内的项，避免每帧扫描全部 5000+ 条。
 */
private const val MAX_LOOKBACK_MS = 300_000L

private fun visibleAt(entries: List<DanmakuEntry>, now: Long): List<DanmakuEntry> {
    if (entries.isEmpty()) return emptyList()

    var low = 0
    var high = entries.size - 1
    var lastStarted = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (entries[mid].startMs <= now) {
            lastStarted = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    if (lastStarted < 0) return emptyList()

    val result = ArrayList<DanmakuEntry>(16)
    var i = lastStarted
    while (i >= 0) {
        val entry = entries[i]
        if (now < entry.endMs) {
            result.add(entry)
        } else if (now - entry.startMs > MAX_LOOKBACK_MS) {
            break
        }
        i--
    }
    return result
}
