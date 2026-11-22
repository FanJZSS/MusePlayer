package com.muse.player.data.subtitle

/**
 * 弹幕字幕（ASS）的数据模型与解析。
 *
 * 只覆盖弹幕类字幕实际会用到的子集：`Effect: Banner` 的滚动效果、
 * 按 `MarginV` 分轨、`\c` 颜色覆盖。完整的 ASS 特效（`\pos`、`\move`、
 * `{\p1}` 绘图、`\t` 动画）不在这里处理，它们交给 libass 之类的完整渲染器。
 */

/** 一条弹幕 */
data class DanmakuEntry(
    /** 出现时间（毫秒） */
    val startMs: Long,
    /** 结束时间（毫秒） */
    val endMs: Long,
    /** 分轨序号，由 MarginV 换算成从 0 开始的连续轨道，0 为最靠上的一条轨道 */
    val lane: Int,
    /** 原始 MarginV 像素值（相对 ASS 画布），用于还原弹幕的垂直分布 */
    val marginV: Int = 0,
    /** 显示文本（已去掉样式标签） */
    val text: String,
    /** 文字颜色（ARGB），来自 \c 或 Style 的 PrimaryColour */
    val color: Int,
    /** 是否横向滚动（Banner 效果）；false 表示静止显示在该轨道 */
    val scrolling: Boolean
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** 解析结果 */
data class DanmakuTrack(
    val entries: List<DanmakuEntry>,
    /** 轨道数量 */
    val laneCount: Int,
    /** 画布宽度，用于换算滚动距离 */
    val playResX: Int,
    /** 画布高度 */
    val playResY: Int,
    /** 各条轨道对应的 MarginV 像素值（升序），用于按原始分布定位 */
    val laneMargins: List<Int> = emptyList()
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * 极简 ASS 字幕解析器，面向弹幕场景。
 *
 * 之所以不直接用 Media3 内置的 SsaParser：它不解析 Effect 字段，
 * `Banner` 滚动会被丢掉，所有弹幕会静止堆叠在画面中央。
 */
object AssDanmakuParser {

    /** 行首样式定义 */
    private data class Style(
        val name: String,
        val fontSize: Float,
        val primaryColor: Int,
        val alignment: Int,
        val marginV: Int
    )

    private val COLOR_TAG = Regex("""\\c&H([0-9A-Fa-f]{1,8})&?""")
    private val TEXT_TAG = Regex("""\{[^}]*\}""")
    private val LINE_BREAK = Regex("""\\N""", RegexOption.IGNORE_CASE)

    /**
     * 解析 ASS 文本。
     * 解析失败或没有可显示的弹幕时返回 null，调用方据此回退到普通字幕管线。
     */
    fun parse(raw: String): DanmakuTrack? {
        if (raw.isBlank()) return null

        var playResX = 1920
        var playResY = 1080
        val styles = HashMap<String, Style>()
        val entries = ArrayList<DanmakuEntry>(1024)
        var inEvents = false
        // Events 段的列顺序由 Format 行决定，不同导出工具顺序不同
        var format = emptyList<String>()

        // 统一换行并逐行处理
        raw.lineSequence().forEach { original ->
            val line = original.trim()
            when {
                line.startsWith("[") -> {
                    inEvents = line.equals("[Events]", ignoreCase = true)
                }

                line.startsWith("PlayResX:", ignoreCase = true) -> {
                    playResX = line.substringAfter(':').trim().toIntOrNull() ?: playResX
                }

                line.startsWith("PlayResY:", ignoreCase = true) -> {
                    playResY = line.substringAfter(':').trim().toIntOrNull() ?: playResY
                }

                line.startsWith("Format:", ignoreCase = true) && !inEvents -> {
                    // Style 段的格式，只需要知道列名即可按需取值
                }

                line.startsWith("Style:", ignoreCase = true) -> {
                    parseStyle(line, styles)
                }

                inEvents && line.startsWith("Format:", ignoreCase = true) -> {
                    format = line.substringAfter(':')
                        .split(',')
                        .map { it.trim().lowercase() }
                }

                inEvents && line.startsWith("Dialogue:", ignoreCase = true) -> {
                    parseDialogue(line, format, styles)?.let(entries::add)
                }
            }
        }

        if (entries.isEmpty()) return null
        entries.sortBy { it.startMs }

        // MarginV 是像素值，同一种字幕里往往只有几档（如 0/40/80/120）。
        // 把它换算成从 0 开始的连续轨道号，避免出现上百条空轨道；
        // 同时保留像素值，渲染层据此还原弹幕的垂直分布。
        val margins = entries.map { it.marginV }.distinct().sorted()
        val laneOf = margins.withIndex().associate { (index, margin) -> margin to index }
        val normalized = entries.map { it.copy(lane = laneOf[it.marginV] ?: 0) }

        return DanmakuTrack(
            entries = normalized,
            laneCount = margins.size.coerceAtLeast(1),
            playResX = playResX.coerceAtLeast(1),
            playResY = playResY.coerceAtLeast(1),
            laneMargins = margins
        )
    }

    private fun parseStyle(line: String, into: MutableMap<String, Style>) {
        // Style: Name, Fontname, Fontsize, PrimaryColour, ..., Alignment, MarginL, MarginR, MarginV, Encoding
        val body = line.substringAfter(':').trim()
        val parts = body.split(',')
        if (parts.size < 22) return
        val name = parts[0].trim()
        if (name.isEmpty()) return
        into[name] = Style(
            name = name,
            fontSize = parts[2].trim().toFloatOrNull() ?: 36f,
            primaryColor = parseAssColor(parts[3].trim()),
            alignment = parts[18].trim().toIntOrNull() ?: 2,
            marginV = parts[21].trim().toIntOrNull() ?: 0
        )
    }

    private fun parseDialogue(
        line: String,
        format: List<String>,
        styles: Map<String, Style>
    ): DanmakuEntry? {
        val body = line.substringAfter(':').trim()
        // Text 是最后一列，且本身可以包含逗号，因此限制切分次数
        val limit = format.size.takeIf { it > 0 } ?: 10
        val parts = body.split(',', limit = limit.coerceAtMost(10))
        if (parts.size < limit) return null

        fun column(name: String, default: String = ""): String {
            val index = format.indexOf(name)
            return if (index in parts.indices) parts[index].trim() else default
        }

        val startText = column("start", "")
        val endText = column("end", "")
        val styleName = column("style", "Default")
        val marginVText = column("marginv", "")
        val effect = column("effect", "")
        val text = parts.last()

        val startMs = parseTime(startText) ?: return null
        val endMs = parseTime(endText) ?: return null
        if (endMs <= startMs) return null

        val style = styles[styleName]
        val marginV = marginVText.toIntOrNull() ?: style?.marginV ?: 0
        val scrolling = effect.startsWith("Banner", ignoreCase = true) ||
            effect.startsWith("Scroll", ignoreCase = true)

        val color = parseColorOverride(text) ?: style?.primaryColor ?: 0xFFFFFFFF.toInt()
        val plain = stripTags(text).trim()
        if (plain.isEmpty()) return null

        return DanmakuEntry(
            startMs = startMs,
            endMs = endMs,
            lane = marginV,
            marginV = marginV,
            text = plain,
            color = color,
            scrolling = scrolling
        )
    }

    /** 取文本中第一个 \c 覆盖的颜色 */
    private fun parseColorOverride(text: String): Int? {
        val match = COLOR_TAG.find(text) ?: return null
        return parseAssColor("&H${match.groupValues[1]}&")
    }

    /**
     * ASS 颜色写作 &HAABBGGRR&（BGR 顺序，AA 为透明度）。
     * 转换为 Android 的 ARGB。
     */
    private fun parseAssColor(raw: String): Int {
        val cleaned = raw.replace("&", "").replace("H", "", ignoreCase = true).trim()
        val value = cleaned.toLongOrNull(16) ?: return 0xFFFFFFFF.toInt()
        val b = (value and 0xFF).toInt()
        val g = (value shr 8 and 0xFF).toInt()
        val r = (value shr 16 and 0xFF).toInt()
        val a = if (cleaned.length > 6) (value shr 24 and 0xFF).toInt() else 0xFF
        // ASS 的透明度是「越大越透明」，转换为标准 alpha
        val alpha = if (cleaned.length > 6) 0xFF - a else 0xFF
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 去掉 {..} 样式标签并处理换行 */
    private fun stripTags(text: String): String =
        LINE_BREAK.replace(TEXT_TAG.replace(text, ""), " ")

    /** 解析 `H:MM:SS.cc` 形式的时间 */
    private fun parseTime(raw: String): Long? {
        if (raw.isBlank()) return null
        val parts = raw.split(':')
        if (parts.size != 3) return null
        val h = parts[0].trim().toLongOrNull() ?: return null
        val m = parts[1].trim().toLongOrNull() ?: return null
        // 秒部分可能是 SS.cc 或 SS,cc
        val secText = parts[2].trim().replace(',', '.')
        val sec = secText.toDoubleOrNull() ?: return null
        return h * 3_600_000L + m * 60_000L + (sec * 1000).toLong()
    }
}
