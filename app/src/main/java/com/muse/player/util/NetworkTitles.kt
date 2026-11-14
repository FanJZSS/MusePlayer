package com.muse.player.util

import android.net.Uri

/**
 * 从网络地址推断可读标题。
 *
 * 很多流媒体地址以 index.m3u8、playlist.m3u8 这类通用文件名结尾，
 * 直接取文件名会得到 “index”，因此这里会跳过通用名，优先取路径中更有意义的片段，
 * 都找不到时退化为域名。
 */
object NetworkTitles {

    private val GENERIC_NAMES = setOf(
        "index", "playlist", "master", "stream", "streams", "video", "medias", "media",
        "chunklist", "manifest", "out", "live", "hls", "default", "main", "prog_index",
        "play", "v", "watch", "player", "m3u8", "mpd", "ts", "mp4"
    )

    private val DOMAIN_REGEX = Regex("^(?:[a-z0-9-]+\\.)+[a-z]{2,}$", RegexOption.IGNORE_CASE)

    /** 该标题是否只是占位（通用文件名、域名或空），可用播放器读到的元数据替换 */
    fun isPlaceholder(title: String): Boolean {
        val text = title.trim()
        if (text.isEmpty() || text == "网络串流" || text == "视频") return true
        val lower = text.lowercase()
        if (lower in GENERIC_NAMES || lower.startsWith("index")) return true
        return DOMAIN_REGEX.matches(text)
    }

    fun of(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val segments = clean.substringAfter("://", clean)
            .split('/')
            .filter { it.isNotBlank() }
        // 跳过域名段，从后往前找第一个有意义的路径片段
        for (segment in segments.drop(1).reversed()) {
            val stem = segment.substringBefore('.').trim()
            if (isMeaningful(stem)) return stem
        }
        val host = runCatching { Uri.parse(url).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        return host ?: "网络串流"
    }

    private fun isMeaningful(stem: String): Boolean {
        if (stem.length !in 2..60) return false
        val lower = stem.lowercase()
        if (lower in GENERIC_NAMES || lower.startsWith("index")) return false
        // 纯数字 / 纯十六进制串（常见于视频 ID）不作为标题
        if (lower.all { it.isDigit() }) return false
        if (lower.length >= 8 && lower.all { it in "0123456789abcdef" }) return false
        return true
    }
}
