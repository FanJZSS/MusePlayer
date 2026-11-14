package com.muse.player.data

import java.io.File

/**
 * 查找与视频同目录、同名的外挂字幕文件。
 */
object SubtitleFinder {
    private val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "ttml", "dfxp")
    private val dirCache = HashMap<String, List<File>>()

    fun findFor(videoPath: String?): List<String> {
        if (videoPath.isNullOrBlank()) return emptyList()
        val file = File(videoPath)
        val dir = file.parentFile ?: return emptyList()
        val baseName = file.nameWithoutExtension
        val files = dirCache.getOrPut(dir.absolutePath) {
            try {
                dir.listFiles()?.filter { it.isFile } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        return files
            .filter { candidate ->
                candidate.extension.lowercase() in EXTENSIONS &&
                    candidate.nameWithoutExtension.startsWith(baseName, ignoreCase = true)
            }
            .sortedBy { it.name }
            .take(8)
            .map { it.absolutePath }
    }
}
