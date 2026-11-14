package com.muse.player.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拖动进度条时按时间点抽取预览帧。
 * 复用同一个 retriever，避免每次拖动都重新打开文件。
 */
class SeekPreviewLoader(private val context: Context) {

    private var retriever: MediaMetadataRetriever? = null
    private var sourceKey: String? = null

    suspend fun load(uri: Uri, timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val key = uri.toString()
        if (key != sourceKey) {
            release()
            sourceKey = key
            retriever = createRetriever(uri)
        }
        val target = retriever ?: return@withContext null
        try {
            val timeUs = timeMs.coerceAtLeast(0L) * 1000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                target.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    480,
                    270
                )
            } else {
                target.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createRetriever(uri: Uri): MediaMetadataRetriever? {
        val created = MediaMetadataRetriever()
        return try {
            when (uri.scheme?.lowercase()) {
                "content" -> created.setDataSource(context, uri)
                "file" -> created.setDataSource(uri.path ?: uri.toString())
                "http", "https" -> created.setDataSource(uri.toString(), emptyMap<String, String>())
                else -> {
                    created.release()
                    return null
                }
            }
            created
        } catch (_: Exception) {
            runCatching { created.release() }
            null
        }
    }

    fun release() {
        runCatching { retriever?.release() }
        retriever = null
        sourceKey = null
    }
}
